package com.zeeshan.routerreboot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

/**
 * Talks to a TP-Link router (TL-WR840N family, "oid" firmware) and triggers
 * System Tools -> Reboot without needing the web UI.
 *
 * The firmware accepts the login as a cookie "Authorization=Basic base64(user:secret)"
 * where secret is either the plain password or its MD5 hex (depends on firmware build),
 * and takes the reboot as a POST to /cgi?7. Because builds differ, every combination is
 * tried and the one that works is remembered.
 */
final class RouterClient {

    static final int OK = 0, BAD_AUTH = 1, UNREACHABLE = 2, FAILED = 3;

    static final class Result {
        final int code;
        final String detail;
        Result(int code, String detail) { this.code = code; this.detail = detail; }
    }

    // Verified on the router itself (TL-WR840N v5, fw 0.9.1 3.16 Build 171030):
    // the Reboot page runs $.act(ACT_OP, ACT_OP_REBOOT) with ACT_OP = 7 and
    // ACT_OP_REBOOT = "ACT_REBOOT", i.e. POST /cgi?7 with this body.
    private static final String[] BODIES = {
            "[ACT_REBOOT#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n"
    };
    private static final int AUTH_PLAIN = 0, AUTH_MD5 = 1;
    // variant index = auth * BODIES.length + body

    private RouterClient() {}

    // ------------------------------------------------------------------ public API

    /** Blocking. Call from a worker thread. */
    static Result reboot(Context ctx, String host, String user, String pass) {
        int total = 2 * BODIES.length;
        int first = Store.variant(ctx);
        int[] order = new int[total];
        int n = 0;
        if (first >= 0 && first < total) order[n++] = first;
        for (int v = 0; v < total; v++) if (v != first) order[n++] = v;

        boolean sawAuthFail = false;
        String lastDetail = "no attempt made";
        StringBuilder log = new StringBuilder();

        for (int pass2 = 0; pass2 < 2; pass2++) {          // 2nd pass = router busy / transient error
            boolean transientProblem = false;
            for (int i = 0; i < total; i++) {
                int v = order[i];
                Result r = attempt(ctx, host, user, pass, v / BODIES.length, v % BODIES.length);
                log.append("v").append(v).append(": ").append(r.detail).append('\n');
                if (r.code == OK) {
                    Store.setVariant(ctx, v);
                    Store.setDiag(ctx, log.toString());
                    return r;
                }
                lastDetail = r.detail;
                if (r.code == UNREACHABLE) {
                    transientProblem = true;
                    break;                                   // no point trying other variants
                }
                if (r.code == BAD_AUTH) sawAuthFail = true;
                else transientProblem = true;
            }
            if (!transientProblem) break;
            sleep(2500);
        }
        Store.setDiag(ctx, log.toString());
        if (lastDetail.startsWith("unreachable")) return new Result(UNREACHABLE, lastDetail);
        if (sawAuthFail && !log.toString().contains("[error]")) return new Result(BAD_AUTH, lastDetail);
        return new Result(FAILED, lastDetail);
    }

    /** Reads the router's front page (no login, no reboot). */
    static Result ping(Context ctx, String host) {
        HttpURLConnection c = null;
        try {
            c = open(ctx, new URL("http://" + host + "/"));
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            return new Result(OK, "HTTP " + code);
        } catch (Exception e) {
            return new Result(UNREACHABLE, e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ internals

    private static Result attempt(Context ctx, String host, String user, String pass,
                                  int auth, int bodyIdx) {
        HttpURLConnection c = null;
        boolean sent = false;
        try {
            String secret = auth == AUTH_MD5 ? md5(pass) : pass;
            String basic = "Basic " + Base64.encodeToString(
                    (user + ":" + secret).getBytes("UTF-8"), Base64.NO_WRAP);
            String cookie = "Authorization=" + URLEncoder.encode(basic, "UTF-8").replace("+", "%20");

            c = open(ctx, new URL("http://" + host + "/cgi?7"));
            c.setConnectTimeout(5000);
            c.setReadTimeout(9000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "text/plain");
            c.setRequestProperty("Referer", "http://" + host + "/mainFrame.htm");
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Connection", "close");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RouterReboot");

            byte[] body = BODIES[bodyIdx].getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            sent = true;

            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            String snippet = text.replace('\r', ' ').replace('\n', ' ');
            if (snippet.length() > 80) snippet = snippet.substring(0, 80);

            if (code == 401 || code == 403) return new Result(BAD_AUTH, "HTTP " + code + " (login refused)");
            if (text.contains("[error]0")) {
                return new Result(OK, "HTTP " + code + " " + snippet);
            }
            if (text.contains("[error]")) {
                return new Result(FAILED, "HTTP " + code + " " + snippet);
            }
            if (text.toLowerCase().contains("<html") || code == 302 || code == 301) {
                return new Result(BAD_AUTH, "HTTP " + code + " (got a web page, not an API reply)");
            }
            return new Result(FAILED, "HTTP " + code + " " + snippet);

        } catch (IOException e) {
            if (sent) {
                // Request was delivered and the connection died: typical of a router that is
                // rebooting. Make sure it really went down before reporting success.
                if (waitUntilDown(ctx, host)) {
                    return new Result(OK, "connection dropped after send; router went offline");
                }
                return new Result(FAILED, "connection dropped but router still online");
            }
            return new Result(UNREACHABLE, "unreachable: " + e.getClass().getSimpleName());
        } catch (Exception e) {
            return new Result(FAILED, "error: " + e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** True if the router stops answering within ~12 seconds. */
    private static boolean waitUntilDown(Context ctx, String host) {
        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = null;
            try {
                c = open(ctx, new URL("http://" + host + "/"));
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.getResponseCode();
            } catch (Exception e) {
                return true;
            } finally {
                if (c != null) c.disconnect();
            }
            sleep(1500);
        }
        return false;
    }

    private static HttpURLConnection open(Context ctx, URL url) throws IOException {
        if (Build.VERSION.SDK_INT >= 21) {
            HttpURLConnection c = Lollipop.openOnWifi(ctx, url);
            if (c != null) return c;
        }
        return (HttpURLConnection) url.openConnection();
    }

    /** Separate class so old devices never load the API 21 types. */
    private static final class Lollipop {
        static HttpURLConnection openOnWifi(Context ctx, URL url) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return (HttpURLConnection) n.openConnection(url);
                    }
                }
            } catch (Exception ignored) {
                // fall back to default route
            }
            return null;
        }
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int r, total = 0;
            while ((r = in.read(buf)) != -1 && total < 4096) {
                out.write(buf, 0, r);
                total += r;
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
