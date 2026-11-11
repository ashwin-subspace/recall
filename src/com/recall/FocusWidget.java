package com.recall;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import com.recall.data.Db;
import com.recall.data.Model;
import com.recall.util.Prefs;

/** The focus, on the home screen, one tap from a session on it. */
public class FocusWidget extends AppWidgetProvider {

    @Override public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        for (int i = 0; i < ids.length; i++) render(ctx, m, ids[i]);
    }

    /** Call after anything that changes the model so the widget never shows stale state. */
    public static void refresh(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, FocusWidget.class));
        if (ids == null) return;
        for (int i = 0; i < ids.length; i++) render(ctx, m, ids[i]);
    }

    private static void render(Context ctx, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_focus);

        if (!Prefs.hasKey(ctx)) {
            v.setTextViewText(R.id.wKicker, "SETUP");
            v.setTextViewText(R.id.wTitle, "Connect Recall");
            v.setTextViewText(R.id.wMeta, "Add a free API key to begin");
        } else {
            Model.Concept f = Db.get(ctx).focus();
            if (f == null) {
                v.setTextViewText(R.id.wKicker, "NOTHING YET");
                v.setTextViewText(R.id.wTitle, "Start your first session");
                v.setTextViewText(R.id.wMeta, "");
            } else {
                boolean due = f.nextDue <= System.currentTimeMillis();
                v.setTextViewText(R.id.wKicker, due ? "DUE FOR REVIEW" : "TODAY'S FOCUS");
                v.setTextViewText(R.id.wTitle, f.label);
                v.setTextViewText(R.id.wMeta,
                        "Confidence " + f.effective + " · " + Db.ago(f.lastSeen));
            }
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("openTarget", true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        v.setOnClickPendingIntent(R.id.wRoot,
                PendingIntent.getActivity(ctx, 4103, open, flags));

        m.updateAppWidget(id, v);
    }
}
