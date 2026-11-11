package com.recall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.recall.util.Alarms;

/** Alarms do not survive a reboot, so put the daily nudge back. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Alarms.arm(ctx);
    }
}
