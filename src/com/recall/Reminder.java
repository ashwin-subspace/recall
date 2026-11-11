package com.recall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.recall.data.Db;
import com.recall.data.Model;
import com.recall.util.Alarms;
import com.recall.util.Prefs;

/**
 * The half of the product that reaches out. A generic "time to study" would be another
 * badge to swipe away, so this names the actual concept and the actual misconception,
 * and tapping it opens straight into a session on that concept.
 */
public class Reminder extends BroadcastReceiver {

    public static final String CHANNEL = "recall.due";
    private static final int ID = 4101;

    @Override public void onReceive(Context ctx, Intent intent) {
        Alarms.arm(ctx);                       // always re-arm for tomorrow first
        if (!Prefs.remindOn(ctx)) return;
        if (!Prefs.hasKey(ctx)) return;        // nothing useful to offer yet

        Db db = Db.get(ctx);
        Model.Concept f = db.focus();
        if (f == null) return;
        if (f.nextDue > System.currentTimeMillis()) return;   // nothing genuinely due

        String title = f.label + ", " + coldFor(f) + ".";
        String body = f.gaps.isEmpty()
                ? "Confidence " + f.effective + ". Ten minutes?"
                : f.gaps.get(0) + " Ten minutes?";

        notify(ctx, title, body);
    }

    private String coldFor(Model.Concept f) {
        long days = Math.round((System.currentTimeMillis() - f.lastSeen) / 86400000.0);
        if (days <= 0) return "still fresh";
        if (days == 1) return "a day cold";
        return days + " days cold";
    }

    private void notify(Context ctx, String title, String body) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Due for review",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("One quiet nudge a day when something you learned is going cold.");
            ch.setSound(null, null);           // a nudge, not an alarm
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("openTarget", true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 4102, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            nm.notify(ID, b.build());
        } catch (SecurityException noPermission) {
            // POST_NOTIFICATIONS was declined; nothing to do but stay quiet.
        }
    }
}
