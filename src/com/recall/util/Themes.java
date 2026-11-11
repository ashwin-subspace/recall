package com.recall.util;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.os.Build;

/**
 * Light and dark come from values/ and values-night/, so the system setting works with no
 * code at all. An explicit override needs UiModeManager, which arrived in API 31; below
 * that the app simply follows the system, and Setup says so rather than offering a
 * control that would do nothing.
 */
public class Themes {

    public static final String SYSTEM = "system", LIGHT = "light", DARK = "dark";

    public static boolean canOverride() {
        return Build.VERSION.SDK_INT >= 31;
    }

    public static void apply(Activity a) {
        if (!canOverride()) return;
        UiModeManager m = (UiModeManager) a.getSystemService(Context.UI_MODE_SERVICE);
        if (m == null) return;
        String want = Prefs.theme(a);
        int mode = UiModeManager.MODE_NIGHT_AUTO;
        if (DARK.equals(want)) mode = UiModeManager.MODE_NIGHT_YES;
        else if (LIGHT.equals(want)) mode = UiModeManager.MODE_NIGHT_NO;
        try {
            m.setApplicationNightMode(mode);
        } catch (Exception unsupported) { }
    }
}
