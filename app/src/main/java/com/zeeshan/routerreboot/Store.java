package com.zeeshan.routerreboot;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around private SharedPreferences. */
final class Store {
    private static final String FILE = "router_prefs";

    private Store() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean hasCreds(Context c) {
        SharedPreferences s = p(c);
        return s.getString("user", "").length() > 0 && s.getString("pass", "").length() > 0;
    }

    static String host(Context c) { return p(c).getString("host", "192.168.0.1"); }
    static String user(Context c) { return p(c).getString("user", ""); }
    static String pass(Context c) { return p(c).getString("pass", ""); }
    static String diag(Context c) { return p(c).getString("diag", ""); }

    /** Index of the auth/command combination that worked last time, -1 if unknown. */
    static int variant(Context c) { return p(c).getInt("variant", -1); }

    static void save(Context c, String host, String user, String pass) {
        SharedPreferences.Editor e = p(c).edit();
        e.putString("host", host).putString("user", user).putString("pass", pass);
        e.putInt("variant", -1); // credentials changed: re-learn what works
        e.commit();
    }

    static void setVariant(Context c, int v) { p(c).edit().putInt("variant", v).commit(); }
    static void setDiag(Context c, String d) { p(c).edit().putString("diag", d).commit(); }
}
