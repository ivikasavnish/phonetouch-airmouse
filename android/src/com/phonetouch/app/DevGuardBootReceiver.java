package com.phonetouch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts Dev Guard after a reboot or an app update. */
public class DevGuardBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DevGuardService.start(context);
    }
}
