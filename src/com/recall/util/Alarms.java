package com.recall.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.recall.Reminder;

import java.util.Calendar;

/**
 * The daily nudge. Deliberately inexact: setAndAllowWhileIdle needs no special
 * permission and a study reminder does not care about a few minutes of drift, whereas
 * an exact alarm would make the user grant SCHEDULE_EXACT_ALARM for nothing.
 */
public class Alarms {

    private static final int CODE = 7301;

    private static PendingIntent intent(Context c) {
        Intent i = new Intent(c, Reminder.class);
        i.setAction("com.recall.REMIND");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, CODE, i, flags);
    }

    /** Re-arm for the next occurrence of the chosen time. Safe to call repeatedly. */
    public static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (!Prefs.remindOn(c)) { am.cancel(intent(c)); return; }

        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, Prefs.remindHour(c));
        at.set(Calendar.MINUTE, Prefs.remindMinute(c));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis() + 5000) {
            at.add(Calendar.DAY_OF_YEAR, 1);
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), intent(c));
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(intent(c));
    }
}
