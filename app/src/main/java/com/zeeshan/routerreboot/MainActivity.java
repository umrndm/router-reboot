package com.zeeshan.routerreboot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Launcher entry. Tap the icon -> "Restart router?" popup -> OK -> the router is
 * restarted in the background and a "Router Restarted" toast is shown.
 * First run (no saved login) or a rejected login opens the setup screen instead.
 */
public class MainActivity extends Activity {

    private static volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Store.hasCreds(this)) {
            startSetup(this, null);
            finish();
            return;
        }
        if (busy) {                       // ignore taps while a reboot is in progress
            toast(getApplicationContext(), "Restart already in progress…");
            finish();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Restart router?")
                .setMessage("Internet will be unavailable for about a minute.")
                .setCancelable(true)
                .setPositiveButton("Restart", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startReboot(); }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { finish(); }
                })
                .show();
    }

    private void startReboot() {
        busy = true;
        final Context app = getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        final String host = Store.host(app), user = Store.user(app), pass = Store.pass(app);

        toast(app, "Restarting router…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                RouterClient.Result r;
                try {
                    r = RouterClient.reboot(app, host, user, pass);
                } catch (Throwable t) {
                    r = new RouterClient.Result(RouterClient.FAILED, "crash: " + t.getClass().getSimpleName());
                }
                final RouterClient.Result res = r;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        switch (res.code) {
                            case RouterClient.OK:
                                toast(app, "Router Restarted");
                                break;
                            case RouterClient.BAD_AUTH:
                                toast(app, "Login rejected. Check username and password.");
                                startSetup(app, "Router rejected the saved login. Please re-enter it.");
                                break;
                            case RouterClient.UNREACHABLE:
                                toast(app, "Router not reachable. Connect to your home Wi-Fi.");
                                break;
                            default:
                                toast(app, "Restart failed. Open Setup to see details.");
                                startSetup(app, "Restart failed. Details are at the bottom of this page.");
                                break;
                        }
                        if (!isFinishing()) finish();
                    }
                });
            }
        }, "router-reboot").start();
    }

    static void startSetup(Context c, String message) {
        Intent i = new Intent(c, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (message != null) i.putExtra("msg", message);
        c.startActivity(i);
    }

    static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }
}
