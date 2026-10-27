package com.phonetouch.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Dev Guard: turns Developer options + USB debugging OFF when the phone is
 * unplugged from a computer, and back ON as soon as it is plugged in again.
 *
 * Writing these global settings needs WRITE_SECURE_SETTINGS, granted once over adb:
 *   adb shell pm grant com.phonetouch.app android.permission.WRITE_SECURE_SETTINGS
 */
public class DevGuardService extends Service {
    static final String PREFS = "phonetouch_prefs";
    static final String PREF_ENABLED = "dev_guard";
    static final String ACTION_STOP = "com.phonetouch.app.DEV_GUARD_STOP";

    private static final String TAG = "DevGuard";
    private static final String USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String CHANNEL_ID = "dev_guard";
    private static final int NOTIFICATION_ID = 7;
    private static final String REPLUG_CHANNEL_ID = "dev_guard_replug";
    private static final int REPLUG_NOTIFICATION_ID = 8;
    // Disabling adb re-enumerates the USB gadget, which can blip "connected" off;
    // wait before treating an unplug as real.
    private static final long DISCONNECT_DEBOUNCE_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean usbConnected;
    private boolean usbPowered;
    // Last plug state acted on (null = not yet). Only edges are acted on, so a
    // manual tile toggle sticks until the cable is next plugged or pulled.
    private Boolean appliedHost;
    private boolean disablePending;
    private boolean replugShown;
    private final Runnable replugCheck = this::updateReplugNotification;

    private final Runnable disableRunnable = () -> {
        disablePending = false;
        if (isHostConnected()) return;
        setDevMode(this, false);
        appliedHost = false;
        updateNotification();
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_STATE.equals(action)) {
                usbConnected = intent.getBooleanExtra("connected", false);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                usbPowered = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                        == BatteryManager.BATTERY_PLUGGED_USB;
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // The device list may still hold the detached device; look again shortly.
                handler.removeCallbacks(replugCheck);
                handler.postDelayed(replugCheck, 1000);
            }
            Log.i(TAG, action + " usbConnected=" + usbConnected + " usbPowered=" + usbPowered
                    + " applied=" + appliedHost);
            evaluate();
        }
    };

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isGuardEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, true);
    }

    static void setGuardEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            start(context);
        } else {
            context.stopService(new Intent(context, DevGuardService.class));
        }
    }

    /** Starts the guard if the user wants it and the adb grant is present. */
    static void start(Context context) {
        if (!isGuardEnabled(context) || !hasPermission(context)) return;
        Intent intent = new Intent(context, DevGuardService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static boolean isDevModeOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    /** Flips Developer options and USB debugging together, in one go. */
    static boolean setDevMode(Context context, boolean on) {
        if (!hasPermission(context)) {
            Log.w(TAG, "setDevMode(" + on + "): WRITE_SECURE_SETTINGS not granted");
            return false;
        }
        try {
            int value = on ? 1 : 0;
            if (on) {
                // Developer options must be on before adb can be.
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, value);
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, value);
            } else {
                Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, value);
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, value);
            }
            Log.i(TAG, "setDevMode(" + on + ") ok, now dev=" + isDevModeOn(context));
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "setDevMode(" + on + ") failed", e);
            return false;
        }
    }

    private boolean isHostConnected() {
        return usbConnected || usbPowered;
    }

    private void evaluate() {
        if (isHostConnected()) {
            handler.removeCallbacks(disableRunnable);
            disablePending = false;
            if (!Boolean.TRUE.equals(appliedHost) && setDevMode(this, true)) {
                appliedHost = true;
                updateNotification();
            }
        } else if (!Boolean.FALSE.equals(appliedHost) && !disablePending) {
            disablePending = true;
            handler.postDelayed(disableRunnable, DISCONNECT_DEBOUNCE_MS);
        }
        updateReplugNotification();
    }

    /**
     * A USB-C cable or hub can come up with the phone as the USB host. The computer
     * then shows up as an attached device instead of a host, so no USB_STATE connect
     * arrives and adb can't work either. Only a replug fixes it, so say so.
     */
    private boolean isPhoneUsbHost() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        return usb != null && !usb.getDeviceList().isEmpty();
    }

    private void updateReplugNotification() {
        boolean show = !isHostConnected() && isPhoneUsbHost();
        if (show == replugShown) return;
        replugShown = show;
        Log.i(TAG, "phone is USB host without a computer link: " + show);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (!show) {
            nm.cancel(REPLUG_NOTIFICATION_ID);
            return;
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, REPLUG_CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);
        String text = "The phone came up as the USB host, so developer mode can't turn on. "
                + "If this is your computer, unplug the cable and plug it back in.";
        nm.notify(REPLUG_NOTIFICATION_ID, builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Replug the USB cable")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .build());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Dev Guard", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
            nm.createNotificationChannel(new NotificationChannel(
                    REPLUG_CHANNEL_ID, "Replug cable", NotificationManager.IMPORTANCE_HIGH));
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(USB_STATE);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setGuardEnabled(this, false);
            return START_NOT_STICKY;
        }
        if (!hasPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(disableRunnable);
        handler.removeCallbacks(replugCheck);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(REPLUG_NOTIFICATION_ID);
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DevGuardService.class).setAction(ACTION_STOP), piFlags);
        boolean devOn = isDevModeOn(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Dev Guard")
                .setContentText(devOn
                        ? "Computer connected: developer mode ON"
                        : "Unplugged: developer mode OFF")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop guard", stop).build())
                .build();
    }
}
