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
 * Restarts a TP-Link TL-WR840N (v5, fw 3.16 "oid" UI) like the web page's Reboot button.
 *
 * Login is a cookie "Authorization=Basic base64(user:secret)", secret = plain password or its
 * MD5 (build dependent). Reboot = POST /cgi?7 with an ACT_REBOOT body. Success is only reported
 * when the router really stops answering afterwards, never from the HTTP reply alone.
 */
final class RouterClient {

    static final int OK = 0, BAD_AUTH = 1, UNREACHABLE = 2, FAILED = 3;

    static final class Result {
        final int code;
        final String detail;
        Result(int code, String detail) { this.code = code; this.detail = detail; }
    }

    private static final String REBOOT_BODY = "[ACT_REBOOT#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n";
    // Harmless read used to check the login before rebooting.
    private static final String PROBE_BODY = "[IGD_DEV_INFO#0,0,0,0,0,0#0,0,0,0,0,0]0,1\r\nupTime\r\n";

    private static final int P_AUTH_OK = 0, P_AUTH_BAD = 1, P_NET = 2, P_UNKNOWN = 3;

    private RouterClient() {}

    // ------------------------------------------------------------------ public API

    /** Blocking. Call from a worker thread. */
    static Result reboot(Context ctx, String host, String user, String pass) {
        StringBuilder log = new StringBuilder();
        Result res = doReboot(ctx, host, user, pass, log);
        Store.setDiag(ctx, log.toString());
        return res;
    }

    private static Result doReboot(Context ctx, String host, String user, String pass, StringBuilder log) {
        String[] secrets;
        try {
            secrets = new String[]{pass, md5(pass)};
        } catch (Exception e) {
            return new Result(FAILED, "md5 unavailable");
        }
        int first = Store.variant(ctx);
        int[] order = first == 1 ? new int[]{1, 0} : new int[]{0, 1};

        // 1) Check which login the router accepts (harmless read).
        int good = -1;
        boolean unknownProbe = false;
        for (int attempt = 0; attempt < 2 && good < 0; attempt++) {
            boolean net = false;
            for (int a : order) {
                String[] out = new String[1];
                int p = probe(ctx, host, user, secrets[a], out);
                log.append("login check ").append(a == 0 ? "plain" : "md5").append(": ").append(out[0]).append('\n');
                if (p == P_AUTH_OK) { good = a; break; }
                if (p == P_NET) { net = true; break; }
                if (p == P_UNKNOWN) unknownProbe = true;
            }
            if (good >= 0) break;
            if (net) {
                if (attempt == 0) { sleep(2500); continue; }
                return new Result(UNREACHABLE, "router not reachable");
            }
            break;
        }

        // 2) Reboot. With a verified login use it; otherwise try both blindly.
        int[] tryList = good >= 0 ? new int[]{good} : order;
        Result last = new Result(BAD_AUTH, "login not accepted");
        for (int a : tryList) {
            Result r = sendReboot(ctx, host, user, secrets[a], log, a == 0 ? "plain" : "md5");
            if (r.code == OK) {
                Store.setVariant(ctx, a);
                return r;
            }
            last = r;
            if (r.code == UNREACHABLE) return r;
        }
        if (good < 0 && last.code == FAILED && !unknownProbe) {
            return new Result(BAD_AUTH, last.detail);
        }
        return last;
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

    private static int probe(Context ctx, String host, String user, String secret, String[] out) {
        HttpURLConnection c = null;
        try {
            c = post(ctx, host, "/cgi?1", user, secret, PROBE_BODY);
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            out[0] = "HTTP " + code + " " + shorten(text);
            if (code == 401 || code == 403) return P_AUTH_BAD;
            if (text.contains("upTime")) return P_AUTH_OK;
            if (text.toLowerCase().contains("<html")) return P_AUTH_BAD;
            return P_UNKNOWN;
        } catch (IOException e) {
            out[0] = "network: " + e.getClass().getSimpleName();
            return P_NET;
        } catch (Exception e) {
            out[0] = "error: " + e.getClass().getSimpleName();
            return P_UNKNOWN;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Result sendReboot(Context ctx, String host, String user, String secret,
                                     StringBuilder log, String label) {
        HttpURLConnection c = null;
        String reply;
        try {
            c = post(ctx, host, "/cgi?7", user, secret, REBOOT_BODY);
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            reply = "HTTP " + code + " " + shorten(text);
            if (code == 401 || code == 403) {
                log.append("reboot ").append(label).append(": ").append(reply).append('\n');
                return new Result(BAD_AUTH, reply);
            }
        } catch (IOException e) {
            // Delivered request, then the connection died: typical while a router reboots.
            reply = "connection closed: " + e.getClass().getSimpleName();
        } catch (Exception e) {
            log.append("reboot ").append(label).append(": error ").append(e.getClass().getSimpleName()).append('\n');
            return new Result(FAILED, "error: " + e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }

        boolean down = waitUntilDown(ctx, host);
        log.append("reboot ").append(label).append(": ").append(reply)
                .append(down ? " -> router went offline" : " -> router STAYED ONLINE").append('\n');
        if (down) return new Result(OK, reply);
        return new Result(FAILED, "router answered but did not restart: " + reply);
    }

    /** True once the router fails to answer twice in a row within ~25 seconds. */
    private static boolean waitUntilDown(Context ctx, String host) {
        int fails = 0;
        for (int i = 0; i < 25; i++) {
            HttpURLConnection c = null;
            boolean up;
            try {
                c = open(ctx, new URL("http://" + host + "/"));
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.getResponseCode();
                up = true;
            } catch (Exception e) {
                up = false;
            } finally {
                if (c != null) c.disconnect();
            }
            if (up) fails = 0; else if (++fails >= 2) return true;
            sleep(700);
        }
        return false;
    }

    private static HttpURLConnection post(Context ctx, String host, String path, String user,
                                          String secret, String bodyText) throws Exception {
        String basic = "Basic " + Base64.encodeToString((user + ":" + secret).getBytes("UTF-8"), Base64.NO_WRAP);
        String cookie = "Authorization=" + URLEncoder.encode(basic, "UTF-8").replace("+", "%20");
        HttpURLConnection c = open(ctx, new URL("http://" + host + path));
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
        byte[] body = bodyText.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.flush();
        os.close();
        return c;
    }

    private static String shorten(String s) {
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() > 90 ? t.substring(0, 90) : t;
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
