package com.recall.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App settings. Lives in the app's private data dir, which other apps cannot read on a
 * non-rooted device. Not hardware-backed encryption -- that needs androidx.security,
 * which this build deliberately has no dependency on.
 */
public class Prefs {
    private static final String FILE = "recall";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String key(Context c) { return p(c).getString("key", ""); }
    public static void setKey(Context c, String v) { p(c).edit().putString("key", v.trim()).apply(); }

    public static boolean hasKey(Context c) { return key(c).length() >= 10; }

    /** Concept the learner explicitly asked to drill. Cleared once that concept is touched. */
    public static long pinned(Context c) { return p(c).getLong("pinned", -1); }
    public static void setPinned(Context c, long id) {
        p(c).edit().putLong("pinned", id).apply();
    }

    public static String who(Context c) { return p(c).getString("who", ""); }
    public static void setWho(Context c, String v) { p(c).edit().putString("who", v.trim()).apply(); }

    /** "gemini" or "groq". Kept as a literal so this class stays dependency-free. */
    public static String provider(Context c) { return p(c).getString("provider", "gemini"); }
    public static void setProvider(Context c, String v) {
        p(c).edit().putString("provider", v).apply();
    }

    public static String model(Context c) { return p(c).getString("model", "gemini-3.6-flash"); }
    public static void setModel(Context c, String v) { p(c).edit().putString("model", v).apply(); }

    // ---- the daily nudge -------------------------------------------------

    public static boolean remindOn(Context c) { return p(c).getBoolean("remindOn", true); }
    public static void setRemindOn(Context c, boolean v) {
        p(c).edit().putBoolean("remindOn", v).apply();
    }

    public static boolean onboarded(Context c) { return p(c).getBoolean("onboarded", false); }
    public static void setOnboarded(Context c, boolean v) {
        p(c).edit().putBoolean("onboarded", v).apply();
    }

    public static String theme(Context c) { return p(c).getString("theme", "system"); }
    public static void setTheme(Context c, String v) { p(c).edit().putString("theme", v).apply(); }

    public static int remindHour(Context c) { return p(c).getInt("remindHour", 20); }
    public static int remindMinute(Context c) { return p(c).getInt("remindMinute", 0); }
    public static void setRemindAt(Context c, int hour, int minute) {
        p(c).edit().putInt("remindHour", hour).putInt("remindMinute", minute).apply();
    }
}
