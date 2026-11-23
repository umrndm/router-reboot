package com.zeeshan.routerreboot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** Setup screen: a local HTML page in a WebView. Never loads remote content. */
public class SetupActivity extends Activity {

    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1020"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setSaveFormData(false);
        s.setBuiltInZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return true; // stay on the local page
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/setup.html");
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override public void run() { web.loadUrl("javascript:" + code); }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private final class Bridge {

        @JavascriptInterface
        public String getConfig() {
            try {
                JSONObject o = new JSONObject();
                o.put("host", Store.host(SetupActivity.this));
                o.put("user", Store.user(SetupActivity.this));
                o.put("hasPass", Store.pass(SetupActivity.this).length() > 0);
                o.put("diag", Store.diag(SetupActivity.this));
                String m = getIntent().getStringExtra("msg");
                o.put("msg", m == null ? "" : m);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void save(String host, String user, String pass) {
            host = host == null ? "" : host.trim().replace("http://", "").replace("https://", "");
            int slash = host.indexOf('/');
            if (slash >= 0) host = host.substring(0, slash);
            user = user == null ? "" : user.trim();
            if (pass == null) pass = "";
            if (pass.length() == 0) pass = Store.pass(SetupActivity.this); // keep existing

            if (!host.matches("[A-Za-z0-9._-]+") || host.length() == 0) {
                js("onSaved(false," + q("Enter a valid router address, e.g. 192.168.0.1") + ")");
                return;
            }
            if (user.length() == 0 || pass.length() == 0) {
                js("onSaved(false," + q("Username and password are required") + ")");
                return;
            }
            Store.save(SetupActivity.this, host, user, pass);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    MainActivity.toast(getApplicationContext(),
                            "Saved. Tap the icon any time to restart the router.");
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void check(final String host) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String h = host == null ? "" : host.trim();
                    RouterClient.Result r = RouterClient.ping(SetupActivity.this, h);
                    if (r.code == RouterClient.OK) {
                        js("onCheck(true," + q("Router is reachable (" + r.detail + ")") + ")");
                    } else {
                        js("onCheck(false," + q("Not reachable. Connect to your home Wi-Fi first.") + ")");
                    }
                }
            }).start();
        }
    }
}
