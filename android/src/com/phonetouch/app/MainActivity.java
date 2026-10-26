package com.phonetouch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.util.Rational;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int PORT = 9999;
    private static final int UDP_DISCOVERY_PORT = 9998;

    // Modes: 0 = Trackpad + Air Trigger, 1 = 1:1 Absolute Touchscreen, 2 = Continuous Air Mouse
    private volatile int currentMode = 0;
    private volatile boolean airHold = false;
    private volatile boolean touchClutch = false;
    private volatile float sensitivity = 14.0f;

    // Power / Disconnect switch, QR Auth Token, Face-Down Standby & OLED Stealth Mode
    private volatile boolean powerEnabled = true;
    private volatile boolean stealthMode = false;
    private volatile boolean minimalMode = false;
    private volatile boolean enteringMinimalMode = false;
    private volatile boolean faceDownStandby = false;
    private volatile Socket activeSocket = null;
    private volatile DatagramSocket udpMotionSocket = null;
    private volatile InetAddress activeHostAddr = null;
    private volatile String discoveredWifiHost = null;
    private volatile String authToken = "c09ca300459f9302";
    private volatile int tokenHash32 = 0;

    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private Sensor accelSensor;
    private Sensor gravitySensor;
    private Sensor proximitySensor;
    private volatile boolean isNearProximity = false;
    private volatile boolean isFaceDown = false;

    private final float[] latestAccel = new float[]{0f, 0f, 0f};
    private float smoothVx = 0f;
    private float smoothVy = 0f;

    private final LinkedBlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(512);
    private volatile boolean running = true;
    private volatile boolean connected = false;

    private LinearLayout rootLayout;
    private LinearLayout headerLayout;
    private LinearLayout tabsRowLayout;
    private LinearLayout sensRowLayout;
    private LinearLayout mediaRowLayout;
    private LinearLayout keyRowLayout;
    private LinearLayout btnRowLayout;
    private TextView statusText;
    private TextView sensorReadout;
    private TextView kbdBtn;
    private TextView pairBtn;
    private TextView stealthBtn;
    private TextView minimalBtn;
    private TextView powerBtn;
    private TextView airTriggerBtn;
    private TextView[] tabButtons = new TextView[3];
    private TouchPadView touchPadView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private void updateTokenHash() {
        long hash = 0x811c9dc5L;
        byte[] bytes = authToken.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xFFL);
            hash = (hash * 0x01000193L) & 0xFFFFFFFFL;
        }
        tokenHash32 = (int) hash;
    }

    private void hapticTick(View v) {
        if (v != null) {
            v.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences("phonetouch_prefs", MODE_PRIVATE);
        authToken = prefs.getString("token", "c09ca300459f9302");
        String savedHost = prefs.getString("host", null);
        if (savedHost != null && !savedHost.isEmpty()) {
            discoveredWifiHost = savedHost;
        }
        updateTokenHash();

        handlePairingIntent(getIntent());

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (accelSensor == null) {
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        try {
            udpMotionSocket = new DatagramSocket();
        } catch (Exception ignored) {}

        setContentView(buildUI());
        updatePictureInPictureParams();
        startNetworkWorker();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    private void handlePairingIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && "phonetouch".equalsIgnoreCase(data.getScheme())) {
            applyPairingString(data.toString());
        }
    }

    private void applyPairingString(String raw) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.startsWith("phonetouch://")) {
            Uri u = Uri.parse(s);
            String host = u.getHost();
            String path = u.getPath();
            if (host != null && !host.isEmpty()) {
                discoveredWifiHost = host;
            }
            if (path != null && path.length() > 1) {
                authToken = path.substring(1).trim();
            }
        } else if (!s.isEmpty()) {
            authToken = s;
        }

        getSharedPreferences("phonetouch_prefs", MODE_PRIVATE)
                .edit()
                .putString("token", authToken)
                .putString("host", discoveredWifiHost != null ? discoveredWifiHost : "")
                .apply();
        updateTokenHash();

        sendCmd(String.format(Locale.US, "{\"t\":\"auth\",\"k\":\"%s\"}", authToken));
        try {
            if (activeSocket != null) activeSocket.close();
        } catch (Exception ignored) {}
    }

    private void showKeyboardInputDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView help = new TextView(this);
        help.setText("Type or use Android Voice Dictation (🎤 on your keyboard) to send text directly to your Linux PC:");
        help.setTextSize(13f);
        box.addView(help);

        final EditText input = new EditText(this);
        input.setHint("Type or dictate text here...");
        box.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("⌨️ Remote Keyboard & Voice Input")
                .setView(box)
                .setPositiveButton("Send + Enter ↵", (d, w) -> {
                    String txt = input.getText().toString().replace("\\", "\\\\").replace("\"", "\\\"");
                    if (!txt.isEmpty()) {
                        sendCmd(String.format(Locale.US, "{\"t\":\"text\",\"s\":\"%s\\n\"}", txt));
                    }
                })
                .setNeutralButton("Send Text Only", (d, w) -> {
                    String txt = input.getText().toString().replace("\\", "\\\\").replace("\"", "\\\"");
                    if (!txt.isEmpty()) {
                        sendCmd(String.format(Locale.US, "{\"t\":\"text\",\"s\":\"%s\"}", txt));
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void showPairingDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView help = new TextView(this);
        help.setText("Scan the desktop QR code using your phone Camera / QR Scanner (it opens this app automatically via phonetouch://), or paste the Pairing URI / Token below:");
        help.setTextSize(13f);
        box.addView(help);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        String currentUri = (discoveredWifiHost != null ? "phonetouch://" + discoveredWifiHost + ":9999/" : "") + authToken;
        input.setText(currentUri);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("🔑 QR Auth Pairing")
                .setView(box)
                .setPositiveButton("Save & Pair", (d, w) -> applyPairingString(input.getText().toString()))
                .setNeutralButton("Scan QR Camera", (d, w) -> {
                    try {
                        Intent cam = new Intent("android.media.action.STILL_IMAGE_CAMERA");
                        startActivity(cam);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUI() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#0F172A"));
        rootLayout.setPadding(dp(12), dp(10), dp(12), dp(12));

        // Top Header Bar (Fixed-height single-line Status | Stealth Button | Power Switch)
        headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding(dp(4), dp(2), dp(4), dp(6));

        statusText = new TextView(this);
        statusText.setText("● Connecting...");
        statusText.setTextColor(Color.parseColor("#F59E0B"));
        statusText.setTextSize(12f);
        statusText.setSingleLine(true);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerLayout.addView(statusText, statusLp);

        sensorReadout = new TextView(this);
        sensorReadout.setVisibility(View.GONE); // Drawn on canvas to prevent top bar layout flicker

        kbdBtn = new TextView(this);
        kbdBtn.setText("⌨️");
        kbdBtn.setTextColor(Color.WHITE);
        kbdBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        kbdBtn.setTextSize(11f);
        kbdBtn.setSingleLine(true);
        kbdBtn.setTypeface(Typeface.DEFAULT_BOLD);
        kbdBtn.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams kbdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kbdLp.rightMargin = dp(5);
        kbdBtn.setOnClickListener(v -> {
            hapticTick(v);
            showKeyboardInputDialog();
        });
        headerLayout.addView(kbdBtn, kbdLp);

        // Page Grab: open the desktop's current browser tab on this phone
        TextView grabBtn = new TextView(this);
        grabBtn.setText("🌐");
        grabBtn.setTextColor(Color.WHITE);
        grabBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        grabBtn.setTextSize(11f);
        grabBtn.setSingleLine(true);
        grabBtn.setTypeface(Typeface.DEFAULT_BOLD);
        grabBtn.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        grabLp.rightMargin = dp(5);
        grabBtn.setOnClickListener(v -> {
            hapticTick(v);
            if (!connected) {
                showHudBanner("🌐 NOT CONNECTED TO PC");
                return;
            }
            showHudBanner("🌐 GRABBING DESKTOP PAGE...");
            sendCmd("{\"t\":\"grab\"}");
        });
        headerLayout.addView(grabBtn, grabLp);

        pairBtn = new TextView(this);
        pairBtn.setText("🔑 QR");
        pairBtn.setTextColor(Color.parseColor("#38BDF8"));
        pairBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        pairBtn.setTextSize(11f);
        pairBtn.setSingleLine(true);
        pairBtn.setTypeface(Typeface.DEFAULT_BOLD);
        pairBtn.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pairLp.rightMargin = dp(6);
        pairBtn.setOnClickListener(v -> showPairingDialog());
        headerLayout.addView(pairBtn, pairLp);

        minimalBtn = new TextView(this);
        minimalBtn.setText("▣ MINI");
        minimalBtn.setTextColor(Color.parseColor("#BAE6FD"));
        minimalBtn.setBackgroundColor(Color.parseColor("#075985"));
        minimalBtn.setTextSize(11f);
        minimalBtn.setSingleLine(true);
        minimalBtn.setTypeface(Typeface.DEFAULT_BOLD);
        minimalBtn.setPadding(dp(8), dp(7), dp(8), dp(7));
        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        miniLp.rightMargin = dp(6);
        minimalBtn.setOnClickListener(v -> {
            hapticTick(v);
            enterMinimalMode();
        });
        headerLayout.addView(minimalBtn, miniLp);

        stealthBtn = new TextView(this);
        stealthBtn.setText("🥷 STEALTH");
        stealthBtn.setTextColor(Color.parseColor("#E2E8F0"));
        stealthBtn.setBackgroundColor(Color.parseColor("#334155"));
        stealthBtn.setTextSize(11f);
        stealthBtn.setSingleLine(true);
        stealthBtn.setTypeface(Typeface.DEFAULT_BOLD);
        stealthBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        stealthBtn.setOnClickListener(v -> setStealthMode(true));
        headerLayout.addView(stealthBtn);

        powerBtn = new TextView(this);
        powerBtn.setText("⚡ ON");
        powerBtn.setTextColor(Color.WHITE);
        powerBtn.setBackgroundColor(Color.parseColor("#16A34A"));
        powerBtn.setTextSize(11f);
        powerBtn.setSingleLine(true);
        powerBtn.setTypeface(Typeface.DEFAULT_BOLD);
        powerBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams pwrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pwrLp.leftMargin = dp(6);
        powerBtn.setOnClickListener(v -> togglePower());
        headerLayout.addView(powerBtn, pwrLp);

        rootLayout.addView(headerLayout);

        // Mode Tabs
        tabsRowLayout = new LinearLayout(this);
        tabsRowLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabsRowLayout.setPadding(0, dp(4), 0, dp(6));
        String[] titles = {"Trackpad + Air", "1:1 Touchscreen", "Continuous Air"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(titles[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(6), dp(10), dp(6), dp(10));
            tab.setTextSize(12.5f);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            tab.setOnClickListener(v -> selectMode(idx));
            tabButtons[i] = tab;
            tabsRowLayout.addView(tab, lp);
        }
        rootLayout.addView(tabsRowLayout);

        // Sensitivity Slider
        sensRowLayout = new LinearLayout(this);
        sensRowLayout.setOrientation(LinearLayout.HORIZONTAL);
        sensRowLayout.setGravity(Gravity.CENTER_VERTICAL);
        sensRowLayout.setPadding(dp(4), dp(2), dp(4), dp(6));

        TextView sensLabel = new TextView(this);
        sensLabel.setText("Sensitivity: 14");
        sensLabel.setTextColor(Color.parseColor("#94A3B8"));
        sensLabel.setTextSize(12f);
        sensRowLayout.addView(sensLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(30);
        seekBar.setProgress(14);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = Math.max(1, progress);
                sensitivity = val;
                sensLabel.setText("Sensitivity: " + val);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sensRowLayout.addView(seekBar, seekLp);
        rootLayout.addView(sensRowLayout);

        // Main Interactive Touchpad / Touchscreen Surface
        touchPadView = new TouchPadView(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        padLp.topMargin = dp(4);
        padLp.bottomMargin = dp(8);
        rootLayout.addView(touchPadView, padLp);

        // Hold for Air Mouse Trigger Button
        airTriggerBtn = new TextView(this);
        airTriggerBtn.setText("HOLD FOR AIR MOUSE (GYRO + ACCEL)");
        airTriggerBtn.setGravity(Gravity.CENTER);
        airTriggerBtn.setTextSize(15f);
        airTriggerBtn.setTypeface(Typeface.DEFAULT_BOLD);
        airTriggerBtn.setTextColor(Color.parseColor("#38BDF8"));
        airTriggerBtn.setBackgroundColor(Color.parseColor("#1E293B"));
        airTriggerBtn.setPadding(dp(12), dp(18), dp(12), dp(18));
        airTriggerBtn.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                hapticTick(v);
                airHold = true;
                airTriggerBtn.setBackgroundColor(Color.parseColor("#0284C7"));
                airTriggerBtn.setTextColor(Color.WHITE);
                sendCmd("{\"t\":\"shortcut\",\"a\":\"laser_ring\"}");
                touchPadView.invalidate();
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                airHold = false;
                airTriggerBtn.setBackgroundColor(Color.parseColor("#1E293B"));
                airTriggerBtn.setTextColor(Color.parseColor("#38BDF8"));
                touchPadView.invalidate();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams airLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        airLp.bottomMargin = dp(6);
        rootLayout.addView(airTriggerBtn, airLp);

        // Media, Volume, Laser Spotlight & Monitor Selector Row
        mediaRowLayout = new LinearLayout(this);
        mediaRowLayout.setOrientation(LinearLayout.HORIZONTAL);
        mediaRowLayout.addView(createKeyButton("🔇", "vol_mute", true, 0.75f, 0, "#1E293B"));
        mediaRowLayout.addView(createKeyButton("🔉−", "vol_down", true, 0.85f, dp(4), "#1E293B"));
        mediaRowLayout.addView(createKeyButton("🔊+", "vol_up", true, 0.85f, dp(4), "#1E293B"));
        mediaRowLayout.addView(createKeyButton("⏮", "media_prev", true, 0.7f, dp(4), "#1E293B"));
        mediaRowLayout.addView(createKeyButton("⏯", "media_play", true, 0.85f, dp(4), "#1E293B"));
        mediaRowLayout.addView(createKeyButton("⏭", "media_next", true, 0.7f, dp(4), "#1E293B"));
        mediaRowLayout.addView(createKeyButton("🔴", "laser_ring", true, 0.7f, dp(4), "#7F1D1D"));
        mediaRowLayout.addView(createKeyButton("🖥️", "mon_cycle", true, 0.75f, dp(4), "#334155"));
        LinearLayout.LayoutParams mediaRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mediaRowLp.bottomMargin = dp(6);
        rootLayout.addView(mediaRowLayout, mediaRowLp);

        // Quick Keys Row (ESC | 🔍− | 100% | 🔍+ | ⇤TAB | TAB⇥ | SPC | ⌫ | ENTER)
        keyRowLayout = new LinearLayout(this);
        keyRowLayout.setOrientation(LinearLayout.HORIZONTAL);
        keyRowLayout.addView(createKeyButton("ESC", "esc", false, 0.85f, 0, "#334155"));
        keyRowLayout.addView(createKeyButton("🔍−", "zoom_out", true, 0.8f, dp(4), "#0F766E"));
        keyRowLayout.addView(createKeyButton("100%", "zoom_reset", true, 0.8f, dp(4), "#1E293B"));
        keyRowLayout.addView(createKeyButton("🔍+", "zoom_in", true, 0.8f, dp(4), "#0F766E"));
        keyRowLayout.addView(createKeyButton("⇤", "tab_prev", true, 0.65f, dp(4), "#1E293B"));
        keyRowLayout.addView(createKeyButton("⇥", "tab_next", true, 0.65f, dp(4), "#1E293B"));
        keyRowLayout.addView(createKeyButton("SPC", "space", false, 0.75f, dp(4), "#1E293B"));
        keyRowLayout.addView(createKeyButton("⌫", "backspace", false, 0.65f, dp(4), "#1E293B"));
        keyRowLayout.addView(createKeyButton("↵", "enter", false, 0.9f, dp(4), "#0369A1"));
        LinearLayout.LayoutParams keyRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keyRowLp.bottomMargin = dp(8);
        rootLayout.addView(keyRowLayout, keyRowLp);

        // Bottom Mouse Buttons (LEFT | MID | RIGHT)
        btnRowLayout = new LinearLayout(this);
        btnRowLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnRowLayout.addView(createMouseButton("LEFT CLICK", "left", 1.2f, 0));
        btnRowLayout.addView(createMouseButton("MID", "middle", 0.6f, dp(8)));
        btnRowLayout.addView(createMouseButton("RIGHT CLICK", "right", 1.2f, dp(8)));
        rootLayout.addView(btnRowLayout);

        selectMode(0);
        return rootLayout;
    }

    private View createKeyButton(String label, String code, boolean isShortcut, float weight, int leftMargin, String bgHex) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(12.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        final int normalColor = Color.parseColor(bgHex);
        btn.setBackgroundColor(normalColor);
        btn.setPadding(dp(4), dp(14), dp(4), dp(14));
        btn.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                hapticTick(v);
                btn.setBackgroundColor(Color.parseColor("#38BDF8"));
                if ("mon_cycle".equals(code)) {
                    hudBannerText = "🖥️ SWITCHED TARGET MONITOR REGION";
                    hudBannerUntil = System.currentTimeMillis() + 800;
                    if (touchPadView != null) touchPadView.invalidate();
                } else if ("laser_ring".equals(code)) {
                    hudBannerText = "🔴 DESKTOP CURSOR SPOTLIGHT";
                    hudBannerUntil = System.currentTimeMillis() + 800;
                    if (touchPadView != null) touchPadView.invalidate();
                }
                if (isShortcut) {
                    sendCmd(String.format(Locale.US, "{\"t\":\"shortcut\",\"a\":\"%s\"}", code));
                } else {
                    sendCmd(String.format(Locale.US, "{\"t\":\"btn\",\"b\":\"%s\",\"p\":true}", code));
                }
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                btn.setBackgroundColor(normalColor);
                if (!isShortcut) {
                    sendCmd(String.format(Locale.US, "{\"t\":\"btn\",\"b\":\"%s\",\"p\":false}", code));
                }
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = leftMargin;
        btn.setLayoutParams(lp);
        return btn;
    }

    private View createMouseButton(String label, String btnCode, float weight, int leftMargin) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor("#1E293B"));
        btn.setPadding(dp(8), dp(20), dp(8), dp(20));
        btn.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                hapticTick(v);
                btn.setBackgroundColor(Color.parseColor("#0284C7"));
                sendCmd(String.format(Locale.US, "{\"t\":\"btn\",\"b\":\"%s\",\"p\":true}", btnCode));
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                btn.setBackgroundColor(Color.parseColor("#1E293B"));
                sendCmd(String.format(Locale.US, "{\"t\":\"btn\",\"b\":\"%s\",\"p\":false}", btnCode));
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = leftMargin;
        btn.setLayoutParams(lp);
        return btn;
    }

    private void setStealthMode(boolean enable) {
        stealthMode = enable;
        WindowManager.LayoutParams winParams = getWindow().getAttributes();
        if (enable) {
            winParams.screenBrightness = 0.01f; // Minimum OLED brightness
            getWindow().setAttributes(winParams);
            rootLayout.setBackgroundColor(Color.BLACK);
            rootLayout.setPadding(0, 0, 0, 0);
            headerLayout.setVisibility(View.GONE);
            tabsRowLayout.setVisibility(View.GONE);
            sensRowLayout.setVisibility(View.GONE);
            airTriggerBtn.setVisibility(View.GONE);
            mediaRowLayout.setVisibility(View.GONE);
            keyRowLayout.setVisibility(View.GONE);
            btnRowLayout.setVisibility(View.GONE);
        } else {
            winParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(winParams);
            rootLayout.setBackgroundColor(Color.parseColor("#0F172A"));
            rootLayout.setPadding(dp(12), dp(10), dp(12), dp(12));
            headerLayout.setVisibility(View.VISIBLE);
            tabsRowLayout.setVisibility(View.VISIBLE);
            sensRowLayout.setVisibility(View.VISIBLE);
            airTriggerBtn.setVisibility(currentMode == 0 ? View.VISIBLE : View.GONE);
            mediaRowLayout.setVisibility(View.VISIBLE);
            keyRowLayout.setVisibility(View.VISIBLE);
            btnRowLayout.setVisibility(View.VISIBLE);
        }
        if (touchPadView != null) {
            touchPadView.invalidate();
        }
    }

    private void updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(9, 16));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(false);
        }
        setPictureInPictureParams(builder.build());
    }

    /**
     * Uses Android's system Picture-in-Picture window instead of an accessibility service or
     * draw-over-other-apps overlay. This keeps PhoneTouch visible (and therefore eligible for
     * sensor delivery) while another app has focus, without stealing that app's gestures.
     */
    private void enterMinimalMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            new AlertDialog.Builder(this)
                    .setTitle("Minimal mode unavailable")
                    .setMessage("Android 8.0 or newer is required for the non-conflicting mini window.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        setStealthMode(false);
        updatePictureInPictureParams();
        enteringMinimalMode = enterPictureInPictureMode(new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(9, 16))
                .build());
    }

    private void applyMinimalUi(boolean enable) {
        minimalMode = enable;
        headerLayout.setVisibility(enable ? View.GONE : View.VISIBLE);
        tabsRowLayout.setVisibility(enable ? View.GONE : View.VISIBLE);
        sensRowLayout.setVisibility(enable ? View.GONE : View.VISIBLE);
        airTriggerBtn.setVisibility(enable ? View.GONE : (currentMode == 0 ? View.VISIBLE : View.GONE));
        mediaRowLayout.setVisibility(enable ? View.GONE : View.VISIBLE);
        keyRowLayout.setVisibility(enable ? View.GONE : View.VISIBLE);
        btnRowLayout.setVisibility(enable ? View.GONE : View.VISIBLE);
        rootLayout.setPadding(enable ? dp(4) : dp(12), enable ? dp(4) : dp(10),
                enable ? dp(4) : dp(12), enable ? dp(4) : dp(12));
        if (touchPadView != null) touchPadView.invalidate();
    }

    private void togglePower() {
        powerEnabled = !powerEnabled;
        if (!powerEnabled) {
            airHold = false;
            touchClutch = false;
            sendQueue.clear();
            powerBtn.setText("🔒 OFF");
            powerBtn.setBackgroundColor(Color.parseColor("#DC2626"));
            statusText.setText("● Disconnected (Standby)");
            statusText.setTextColor(Color.parseColor("#94A3B8"));
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            try {
                if (activeSocket != null) {
                    activeSocket.close();
                }
            } catch (Exception ignored) {}
        } else {
            powerBtn.setText("⚡ ON");
            powerBtn.setBackgroundColor(Color.parseColor("#16A34A"));
            statusText.setText("● Reconnecting...");
            statusText.setTextColor(Color.parseColor("#F59E0B"));
            registerSensorsIfActive();
        }
        if (touchPadView != null) {
            touchPadView.invalidate();
        }
    }

    private void registerSensorsIfActive() {
        if (!powerEnabled || sensorManager == null) return;
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gravitySensor != null) {
            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void sendFastUdpMotion(int opcode, int slotOrFlags, float v1, float v2) {
        if (!powerEnabled || faceDownStandby) return;
        InetAddress target = activeHostAddr;
        DatagramSocket ds = udpMotionSocket;
        // Fallback to TCP if UDP socket/address not ready, or over USB: `adb reverse` only
        // tunnels TCP, so UDP to 127.0.0.1 would land on the phone itself
        if (target == null || ds == null || target.isLoopbackAddress()) {
            if (opcode == 1) {
                sendCmd(String.format(Locale.US, "{\"t\":\"rel\",\"dx\":%.3f,\"dy\":%.3f}", v1, v2));
            } else if (opcode == 2) {
                sendCmd(String.format(Locale.US, "{\"t\":\"scroll\",\"dy\":%.3f,\"dx\":%.3f}", v1, v2));
            } else if (opcode == 3) {
                sendCmd(String.format(Locale.US, "{\"t\":\"mt\",\"s\":%d,\"x\":%.5f,\"y\":%.5f,\"d\":%b}",
                        slotOrFlags & 0x0F, v1, v2, (slotOrFlags & 0x80) != 0));
            }
            return;
        }
        new Thread(() -> {
            try {
                ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) 0x50); // 'P'
                bb.put((byte) 0x54); // 'T'
                bb.put((byte) opcode);
                bb.put((byte) slotOrFlags);
                int sig = tokenHash32;
                bb.putInt(sig);
                int iv1 = Float.floatToRawIntBits(v1) ^ sig;
                int iv2 = Float.floatToRawIntBits(v2) ^ sig;
                bb.putInt(iv1);
                bb.putInt(iv2);
                byte[] arr = bb.array();
                DatagramPacket pkt = new DatagramPacket(arr, arr.length, target, UDP_DISCOVERY_PORT);
                ds.send(pkt);
            } catch (Exception ignored) {}
        }).start();
    }

    private String discoverDesktopOverUdp() {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            ds.setSoTimeout(350);
            byte[] req = "PHONETOUCH_DISCOVER".getBytes(StandardCharsets.UTF_8);
            String[] broadcastTargets = {"255.255.255.255", "192.168.1.255", "192.168.0.255", "192.168.42.255"};
            for (String bcast : broadcastTargets) {
                try {
                    DatagramPacket pkt = new DatagramPacket(
                            req, req.length, InetAddress.getByName(bcast), UDP_DISCOVERY_PORT);
                    ds.send(pkt);
                } catch (Exception ignored) {}
            }
            byte[] buf = new byte[128];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            ds.receive(resp);
            String reply = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8).trim();
            if (reply.startsWith("PHONETOUCH_HERE")) {
                return resp.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void selectMode(int mode) {
        currentMode = mode;
        for (int i = 0; i < 3; i++) {
            if (i == mode) {
                tabButtons[i].setBackgroundColor(Color.parseColor("#0284C7"));
                tabButtons[i].setTextColor(Color.WHITE);
            } else {
                tabButtons[i].setBackgroundColor(Color.parseColor("#1E293B"));
                tabButtons[i].setTextColor(Color.parseColor("#94A3B8"));
            }
        }
        if (airTriggerBtn != null && !stealthMode) {
            airTriggerBtn.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        }
        if (touchPadView != null) {
            touchPadView.invalidate();
        }
    }

    private void sendCmd(String jsonLine) {
        if (!powerEnabled) return;
        if (!sendQueue.offer(jsonLine)) {
            sendQueue.poll();
            sendQueue.offer(jsonLine);
        }
    }

    private void showHudBanner(String text) {
        hudBannerText = text;
        hudBannerUntil = System.currentTimeMillis() + 1200;
        if (touchPadView != null) touchPadView.invalidate();
    }

    // Reads daemon -> phone lines on the control socket: {"t":"open","u":"..."} / {"t":"grab_fail"}
    private void startReplyReader(Socket sock) {
        Thread reader = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.contains("\"t\":\"open\"")) {
                        String url = extractJsonString(line, "u");
                        if (url != null) uiHandler.post(() -> openGrabbedUrl(url));
                    } else if (line.contains("\"t\":\"grab_fail\"")) {
                        uiHandler.post(() -> showHudBanner("🌐 NO BROWSER PAGE FOUND ON PC"));
                    }
                }
            } catch (Exception ignored) {
                // Socket closed; the network worker reconnects and starts a new reader
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    private static String extractJsonString(String line, String key) {
        String pat = "\"" + key + "\":\"";
        int start = line.indexOf(pat);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start + pat.length(); i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                sb.append(line.charAt(++i));
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private void openGrabbedUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception e) {
            showHudBanner("🌐 NO APP TO OPEN PAGE");
        }
    }

    private void startNetworkWorker() {
        Thread worker = new Thread(() -> {
            // Prioritizes Wi-Fi (192.168.1.7 + Auto-Discovered LAN IP + Tailscale) and also supports USB
            String[] baseHosts = {"192.168.1.7", "100.90.131.6", "127.0.0.1", "10.20.226.216", "192.168.42.100"};
            int hostIdx = 0;
            while (running) {
                if (!powerEnabled) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ignored) {}
                    continue;
                }

                String host;
                if (discoveredWifiHost != null && (hostIdx % 2 == 0)) {
                    host = discoveredWifiHost;
                } else {
                    host = baseHosts[hostIdx % baseHosts.length];
                }
                hostIdx++;

                if (hostIdx % baseHosts.length == 0) {
                    String found = discoverDesktopOverUdp();
                    if (found != null) {
                        discoveredWifiHost = found;
                        host = found;
                    }
                }

                try (Socket sock = new Socket()) {
                    activeSocket = sock;
                    sock.connect(new InetSocketAddress(host, PORT), 600);
                    sock.setTcpNoDelay(true);
                    activeHostAddr = InetAddress.getByName(host);
                    connected = true;
                    final String connectedHost = host;
                    final String linkType = "127.0.0.1".equals(host) ? "USB" : "Wi-Fi";
                    uiHandler.post(() -> {
                        if (powerEnabled) {
                            statusText.setText("● Online (" + linkType + ")");
                            statusText.setTextColor(Color.parseColor("#22C55E"));
                        }
                    });
                    OutputStream out = sock.getOutputStream();
                    String authHandshake = String.format(Locale.US, "{\"t\":\"auth\",\"k\":\"%s\"}\n", authToken);
                    out.write(authHandshake.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    startReplyReader(sock);
                    while (running && powerEnabled && !sock.isClosed()) {
                        String msg = sendQueue.take();
                        if (!powerEnabled) break;
                        byte[] data = (msg + "\n").getBytes(StandardCharsets.UTF_8);
                        out.write(data);
                        out.flush();
                    }
                } catch (Exception e) {
                    connected = false;
                    if (powerEnabled) {
                        uiHandler.post(() -> {
                            statusText.setText("● Searching PC...");
                            statusText.setTextColor(Color.parseColor("#F59E0B"));
                        });
                    }
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {}
                } finally {
                    activeSocket = null;
                }
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensorsIfActive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // PiP is the explicit minimal/background mode. Keep sensors registered while its
        // system-owned window remains visible; otherwise release them immediately.
        if (!enteringMinimalMode && !isInPictureInPictureMode() && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode,
                                              Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        enteringMinimalMode = false;
        applyMinimalUi(inPictureInPictureMode);
        if (inPictureInPictureMode) {
            registerSensorsIfActive();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isInPictureInPictureMode() && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        try {
            if (activeSocket != null) activeSocket.close();
            if (udpMotionSocket != null) udpMotionSocket.close();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private long lastWindowTiltTime = 0;
    private long rollNeutralSince = 0;
    private boolean windowTiltArmed = true;
    private volatile String hudBannerText = "";
    private volatile long hudBannerUntil = 0;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (stealthMode && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_BACK)) {
            setStealthMode(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (stealthMode) {
            setStealthMode(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sType = event.sensor.getType();
        if (sType == Sensor.TYPE_PROXIMITY) {
            float maxRange = proximitySensor != null ? proximitySensor.getMaximumRange() : 5.0f;
            isNearProximity = event.values[0] < Math.min(2.5f, maxRange);
            boolean newStandby = isFaceDown && isNearProximity;
            if (newStandby != faceDownStandby) {
                faceDownStandby = newStandby;
                if (touchPadView != null) touchPadView.invalidate();
            }
            return;
        }

        if (sType == Sensor.TYPE_GRAVITY) {
            // Z < -8.0 means phone screen is facing straight down toward desk
            isFaceDown = event.values[2] < -8.0f;
            boolean newStandby = isFaceDown;
            if (newStandby != faceDownStandby) {
                faceDownStandby = newStandby;
                if (touchPadView != null) touchPadView.invalidate();
            }
            return;
        }

        if (sType == Sensor.TYPE_LINEAR_ACCELERATION || sType == Sensor.TYPE_ACCELEROMETER) {
            latestAccel[0] = event.values[0];
            latestAccel[1] = event.values[1];
            latestAccel[2] = event.values[2];
            if (gravitySensor == null && sType == Sensor.TYPE_ACCELEROMETER) {
                isFaceDown = event.values[2] < -8.2f;
                if (isFaceDown != faceDownStandby) {
                    faceDownStandby = isFaceDown;
                    if (touchPadView != null) touchPadView.invalidate();
                }
            }
            return;
        }

        if (sType == Sensor.TYPE_GYROSCOPE) {
            if (faceDownStandby) return;
            // Android Gyroscope in rad/s:
            // values[0] = X-axis (Pitch: tilting phone up/down)
            // values[1] = Y-axis (Roll: flipping/rolling phone wrist left/right)
            // values[2] = Z-axis (Yaw: pointing phone left/right)
            float pitchRad = event.values[0];
            float rollRad = event.values[1];
            float yawRad = event.values[2];

            long now = System.currentTimeMillis();

            // A deliberate horizontal wrist tilt switches desktop windows. It must settle near
            // neutral before it can fire again, preventing normal air-mouse motion from repeating.
            if (Math.abs(rollRad) < 0.55f) {
                if (rollNeutralSince == 0) rollNeutralSince = now;
                if (now - rollNeutralSince > 180) windowTiltArmed = true;
            } else {
                rollNeutralSince = 0;
            }
            if (!touchClutch && windowTiltArmed && (now - lastWindowTiltTime > 650)
                    && Math.abs(rollRad) > 3.6f
                    && Math.abs(rollRad) > Math.abs(pitchRad) * 1.5f
                    && Math.abs(rollRad) > Math.abs(yawRad) * 1.5f) {
                lastWindowTiltTime = now;
                windowTiltArmed = false;
                hapticTick(touchPadView);
                if (rollRad > 0) {
                    sendCmd("{\"t\":\"shortcut\",\"a\":\"app_next\"}");
                    hudBannerText = "⚡ TILT RIGHT → NEXT WINDOW";
                } else {
                    sendCmd("{\"t\":\"shortcut\",\"a\":\"app_prev\"}");
                    hudBannerText = "⚡ TILT LEFT → PREV WINDOW";
                }
                hudBannerUntil = now + 700;
                if (touchPadView != null) touchPadView.invalidate();
                return;
            }

            boolean airActive = airHold || (currentMode == 2 && !touchClutch);
            if (!airActive) {
                smoothVx = 0f;
                smoothVy = 0f;
                return;
            }

            // Deadzone filtering to avoid drift when stationary
            float deadzone = 0.025f; // rad/s
            float gYaw = Math.abs(yawRad) > deadzone ? -yawRad : 0f;
            float gPitch = Math.abs(pitchRad) > deadzone ? -pitchRad : 0f;

            // Linear accelerometer boost for fast hand sweeps
            float ax = Math.abs(latestAccel[0]) > 0.45f ? -latestAccel[0] * 0.12f : 0f;
            float ay = Math.abs(latestAccel[1]) > 0.45f ? latestAccel[1] * 0.12f : 0f;

            float rawVx = (gYaw + ax) * sensitivity * 1.9f;
            float rawVy = (gPitch + ay) * sensitivity * 1.9f;

            smoothVx = smoothVx * 0.55f + rawVx * 0.45f;
            smoothVy = smoothVy * 0.55f + rawVy * 0.45f;

            if (Math.abs(smoothVx) > 0.04f || Math.abs(smoothVy) > 0.04f) {
                sendFastUdpMotion(1, 0, smoothVx, smoothVy);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Custom Canvas View for Multi-Touch Trackpad, Easy Right-Edge Scroll Strip, and 1:1 Multi-Touch Screen
     */
    private class TouchPadView extends View {
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stripTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF stripRect = new RectF();
        private final RectF zoomStripRect = new RectF();

        private float lastX = 0f, lastY = 0f;
        private float lastScrollX = 0f, lastScrollY = 0f;
        private float lastZoomY = 0f;
        private float lastPinchDist = 0f;
        private float lastThreeX = 0f, lastThreeY = 0f;
        private boolean threeGestureTriggered = false;
        private boolean inScrollStrip = false;
        private boolean inZoomStrip = false;
        private long downTime = 0;
        private float totalMove = 0f;
        private int maxPointers = 0;

        private final float[] ptX = new float[5];
        private final float[] ptY = new float[5];
        private int activePtCount = 0;

        public TouchPadView(Context ctx) {
            super(ctx);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2));
            borderPaint.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));

            stripPaint.setColor(Color.parseColor("#1E293B"));
            stripPaint.setStyle(Paint.Style.FILL);

            stripTextPaint.setColor(Color.parseColor("#38BDF8"));
            stripTextPaint.setTextSize(dp(11));
            stripTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            stripTextPaint.setTextAlign(Paint.Align.CENTER);

            titlePaint.setColor(Color.parseColor("#F8FAFC"));
            titlePaint.setTextSize(dp(17));
            titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
            titlePaint.setTextAlign(Paint.Align.CENTER);

            subPaint.setColor(Color.parseColor("#94A3B8"));
            subPaint.setTextSize(dp(12));
            subPaint.setTextAlign(Paint.Align.CENTER);

            touchPaint.setColor(Color.parseColor("#38BDF8"));
            touchPaint.setStyle(Paint.Style.STROKE);
            touchPaint.setStrokeWidth(dp(2));
        }

        private final RectF stealthExitRect = new RectF();

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();

            if (stealthMode) {
                canvas.drawColor(Color.BLACK);

                // Draw dim, OLED-safe EXIT STEALTH pill at top-center for 1-tap wake
                float pillW = dp(210);
                float pillH = dp(44);
                stealthExitRect.set((w - pillW) / 2f, dp(14), (w + pillW) / 2f, dp(14) + pillH);
                stripPaint.setColor(Color.parseColor("#0D1520"));
                canvas.drawRoundRect(stealthExitRect, dp(22), dp(22), stripPaint);
                borderPaint.setColor(Color.parseColor("#1E293B"));
                canvas.drawRoundRect(stealthExitRect, dp(22), dp(22), borderPaint);

                subPaint.setColor(Color.parseColor("#475569"));
                canvas.drawText("☀ TAP HERE TO EXIT STEALTH", w / 2f, stealthExitRect.centerY() + dp(4), subPaint);
                canvas.drawText(
                        "or press Volume / Back button",
                        w / 2f, stealthExitRect.bottom + dp(18), subPaint);
                subPaint.setColor(Color.parseColor("#94A3B8"));
                return;
            }

            if (minimalMode) {
                canvas.drawColor(Color.parseColor("#07111F"));
                rect.set(dp(4), dp(4), w - dp(4), h - dp(4));
                borderPaint.setColor(connected ? Color.parseColor("#22C55E") : Color.parseColor("#F59E0B"));
                canvas.drawRoundRect(rect, dp(16), dp(16), borderPaint);
                titlePaint.setTextSize(dp(15));
                titlePaint.setColor(Color.parseColor("#F8FAFC"));
                canvas.drawText(connected ? "PhoneTouch active" : "Finding desktop…",
                        w / 2f, h / 2f - dp(24), titlePaint);
                subPaint.setTextSize(dp(11));
                canvas.drawText("← quick tilt   switch windows   quick tilt →",
                        w / 2f, h / 2f + dp(2), subPaint);
                canvas.drawText("Tap the mini window to expand or close",
                        w / 2f, h / 2f + dp(24), subPaint);
                titlePaint.setTextSize(dp(17));
                subPaint.setTextSize(dp(12));
                return;
            }

            if (!powerEnabled) {
                rect.set(dp(4), dp(4), w - dp(4), h - dp(4));
                borderPaint.setColor(Color.parseColor("#7F1D1D"));
                canvas.drawRoundRect(rect, dp(16), dp(16), borderPaint);
                titlePaint.setColor(Color.parseColor("#EF4444"));
                canvas.drawText("🔒 DISCONNECTED (STANDBY)", w / 2f, h / 2f - dp(12), titlePaint);
                titlePaint.setColor(Color.parseColor("#F8FAFC"));
                canvas.drawText("Touch & Gyro sensors are completely switched OFF", w / 2f, h / 2f + dp(14), subPaint);
                canvas.drawText("Tap [🔒 OFF] at top-right to reconnect when needed", w / 2f, h / 2f + dp(34), subPaint);
                return;
            }

            if (faceDownStandby) {
                rect.set(dp(4), dp(4), w - dp(4), h - dp(4));
                borderPaint.setColor(Color.parseColor("#F59E0B"));
                canvas.drawRoundRect(rect, dp(16), dp(16), borderPaint);
                titlePaint.setColor(Color.parseColor("#F59E0B"));
                canvas.drawText("🌙 FACE-DOWN / POCKET AUTO-STANDBY", w / 2f, h / 2f - dp(12), titlePaint);
                titlePaint.setColor(Color.parseColor("#F8FAFC"));
                canvas.drawText("Input paused automatically while phone is face-down", w / 2f, h / 2f + dp(14), subPaint);
                canvas.drawText("Pick up phone to resume instant control", w / 2f, h / 2f + dp(34), subPaint);
                return;
            }

            rect.set(dp(4), dp(4), w - dp(4), h - dp(4));

            // Draw dedicated Left-Edge Easy Zoom Strip and Right-Edge Easy Scroll Strip
            int stripW = dp(40);
            if (currentMode != 1) {
                // Left Edge: 1-Finger Easy Zoom Strip
                zoomStripRect.set(dp(6), dp(8), dp(6) + stripW, h - dp(8));
                stripPaint.setColor(inZoomStrip ? Color.parseColor("#0F766E") : Color.parseColor("#1E293B"));
                canvas.drawRoundRect(zoomStripRect, dp(12), dp(12), stripPaint);
                float zx = zoomStripRect.centerX();
                canvas.drawText("＋", zx, zoomStripRect.top + dp(22), stripTextPaint);
                canvas.drawText("Z", zx, h / 2f - dp(16), stripTextPaint);
                canvas.drawText("O", zx, h / 2f - dp(2), stripTextPaint);
                canvas.drawText("O", zx, h / 2f + dp(12), stripTextPaint);
                canvas.drawText("M", zx, h / 2f + dp(26), stripTextPaint);
                canvas.drawText("－", zx, zoomStripRect.bottom - dp(12), stripTextPaint);

                // Right Edge: 1-Finger Easy Scroll Strip
                stripRect.set(w - stripW - dp(6), dp(8), w - dp(6), h - dp(8));
                stripPaint.setColor(inScrollStrip ? Color.parseColor("#0369A1") : Color.parseColor("#1E293B"));
                canvas.drawRoundRect(stripRect, dp(12), dp(12), stripPaint);
                float cx = stripRect.centerX();
                canvas.drawText("▲", cx, stripRect.top + dp(22), stripTextPaint);
                canvas.drawText("S", cx, h / 2f - dp(24), stripTextPaint);
                canvas.drawText("C", cx, h / 2f - dp(10), stripTextPaint);
                canvas.drawText("R", cx, h / 2f + dp(4), stripTextPaint);
                canvas.drawText("O", cx, h / 2f + dp(18), stripTextPaint);
                canvas.drawText("L", cx, h / 2f + dp(32), stripTextPaint);
                canvas.drawText("L", cx, h / 2f + dp(46), stripTextPaint);
                canvas.drawText("▼", cx, stripRect.bottom - dp(12), stripTextPaint);
            }

            boolean airActive = airHold || currentMode == 2;
            borderPaint.setColor(airActive ? Color.parseColor("#38BDF8") : Color.parseColor("#334155"));
            canvas.drawRoundRect(rect, dp(16), dp(16), borderPaint);

            float centerX = w / 2f;
            String title;
            String sub1;
            String sub2;
            String sub3;
            if (currentMode == 0) {
                title = "Multi-Touch + Zoom + Scroll";
                sub1 = "Left Bar: 1-Finger Zoom • Right Bar: 1-Finger Scroll";
                sub2 = "2-Finger Pinch: Zoom In/Out • 2-Finger Slide: Scroll";
                sub3 = "3-Finger Swipe: Apps/Overview • Quick Tilt: Switch Windows";
            } else if (currentMode == 1) {
                title = "1:1 Multi-Touch Desktop Screen";
                sub1 = "Up to 5 simultaneous fingers mapped 1:1";
                sub2 = "to your Linux desktop monitor";
                sub3 = "Quick horizontal tilt Left/Right to switch Windows";
            } else {
                title = "Continuous 3D Air Mouse Active";
                sub1 = "Point phone in air • Quick tilt L/R: Switch Windows";
                sub2 = "Left Bar: Easy Zoom • Right Bar: Easy Scroll";
                sub3 = "Touch center pad to clutch/pause Air cursor";
            }

            canvas.drawText(title, centerX, h / 2f - dp(24), titlePaint);
            canvas.drawText(sub1, centerX, h / 2f + dp(2), subPaint);
            canvas.drawText(sub2, centerX, h / 2f + dp(20), subPaint);
            canvas.drawText(sub3, centerX, h / 2f + dp(38), subPaint);

            if (System.currentTimeMillis() < hudBannerUntil && !hudBannerText.isEmpty()) {
                stripTextPaint.setTextSize(dp(13));
                canvas.drawText(hudBannerText, centerX, dp(28), stripTextPaint);
                stripTextPaint.setTextSize(dp(11));
            }

            for (int i = 0; i < activePtCount; i++) {
                canvas.drawCircle(ptX[i], ptY[i], dp(22), touchPaint);
            }
        }

        private float pinchDistance(MotionEvent ev) {
            if (ev.getPointerCount() < 2) return 0f;
            float dx = ev.getX(0) - ev.getX(1);
            float dy = ev.getY(0) - ev.getY(1);
            return (float) Math.hypot(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            int actionIdx = ev.getActionIndex();
            int count = ev.getPointerCount();

            // Easy 1-tap on top pill button exits Stealth Mode immediately
            if (stealthMode && action == MotionEvent.ACTION_DOWN) {
                if (stealthExitRect.contains(ev.getX(0), ev.getY(0))) {
                    setStealthMode(false);
                    return true;
                }
            }

            if (!powerEnabled || faceDownStandby) {
                return true;
            }

            maxPointers = Math.max(maxPointers, count);

            activePtCount = Math.min(5, count);
            for (int i = 0; i < activePtCount; i++) {
                ptX[i] = ev.getX(i);
                ptY[i] = ev.getY(i);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePtCount = 0;
            }
            invalidate();

            if (currentMode == 1) {
                // 1:1 Direct Multi-Touch Touchscreen Mode (up to 5 fingers via Type-B MT slots)
                float w = Math.max(1f, getWidth());
                float h = Math.max(1f, getHeight());
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                        || action == MotionEvent.ACTION_MOVE) {
                    for (int i = 0; i < count; i++) {
                        int slot = ev.getPointerId(i);
                        if (slot >= 0 && slot < 5) {
                            float nx = Math.max(0f, Math.min(1f, ev.getX(i) / w));
                            float ny = Math.max(0f, Math.min(1f, ev.getY(i) / h));
                            sendCmd(String.format(Locale.US,
                                    "{\"t\":\"mt\",\"s\":%d,\"x\":%.4f,\"y\":%.4f,\"d\":true}", slot, nx, ny));
                        }
                    }
                } else if (action == MotionEvent.ACTION_POINTER_UP) {
                    int slot = ev.getPointerId(actionIdx);
                    if (slot >= 0 && slot < 5) {
                        sendCmd(String.format(Locale.US,
                                "{\"t\":\"mt\",\"s\":%d,\"x\":0,\"y\":0,\"d\":false}", slot));
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    for (int s = 0; s < 5; s++) {
                        sendCmd(String.format(Locale.US,
                                "{\"t\":\"mt\",\"s\":%d,\"x\":0,\"y\":0,\"d\":false}", s));
                    }
                    maxPointers = 0;
                }
                return true;
            }

            // Mode 0 (Trackpad) or Mode 2 (Continuous Air with Touch Clutch + Trackpad)
            int stripW = dp(46);
            if (action == MotionEvent.ACTION_DOWN) {
                touchClutch = true;
                lastX = ev.getX(0);
                lastY = ev.getY(0);
                downTime = System.currentTimeMillis();
                totalMove = 0f;
                threeGestureTriggered = false;
                inScrollStrip = (lastX >= getWidth() - stripW);
                inZoomStrip = (!inScrollStrip && lastX <= stripW);
                if (inScrollStrip) {
                    lastScrollY = lastY;
                    invalidate();
                } else if (inZoomStrip) {
                    lastZoomY = lastY;
                    invalidate();
                }
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                inScrollStrip = false;
                inZoomStrip = false;
                if (count == 2) {
                    lastScrollX = (ev.getX(0) + ev.getX(1)) * 0.5f;
                    lastScrollY = (ev.getY(0) + ev.getY(1)) * 0.5f;
                    lastPinchDist = pinchDistance(ev);
                } else if (count == 3) {
                    lastThreeX = (ev.getX(0) + ev.getX(1) + ev.getX(2)) / 3f;
                    lastThreeY = (ev.getY(0) + ev.getY(1) + ev.getY(2)) / 3f;
                    threeGestureTriggered = false;
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (inZoomStrip && count == 1 && maxPointers == 1) {
                    // Dedicated Left-Edge 1-Finger Easy Zoom Strip (slide up = zoom in, down = zoom out)
                    float dy = ev.getY(0) - lastZoomY;
                    totalMove += Math.abs(dy);
                    if (Math.abs(dy) > dp(22)) {
                        hapticTick(this);
                        sendCmd(dy < 0
                                ? "{\"t\":\"shortcut\",\"a\":\"zoom_in\"}"
                                : "{\"t\":\"shortcut\",\"a\":\"zoom_out\"}");
                        lastZoomY = ev.getY(0);
                    }
                } else if (inScrollStrip && count == 1 && maxPointers == 1) {
                    // Dedicated Right-Edge Easy Scroll Strip (1-finger smooth scroll)
                    float dy = ev.getY(0) - lastScrollY;
                    lastScrollY = ev.getY(0);
                    totalMove += Math.abs(dy);
                    float scrollDelta = -dy / dp(18);
                    if (Math.abs(scrollDelta) > 0.01f) {
                        sendFastUdpMotion(2, 0, scrollDelta, 0f);
                    }
                } else if (count == 1 && maxPointers == 1) {
                    float dx = (ev.getX(0) - lastX) * (sensitivity / 9.0f);
                    float dy = (ev.getY(0) - lastY) * (sensitivity / 9.0f);
                    totalMove += Math.abs(ev.getX(0) - lastX) + Math.abs(ev.getY(0) - lastY);
                    lastX = ev.getX(0);
                    lastY = ev.getY(0);
                    if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f) {
                        sendFastUdpMotion(1, 0, dx, dy);
                    }
                } else if (count == 2 && maxPointers == 2) {
                    float curX = (ev.getX(0) + ev.getX(1)) * 0.5f;
                    float curY = (ev.getY(0) + ev.getY(1)) * 0.5f;
                    float dx = curX - lastScrollX;
                    float dy = curY - lastScrollY;
                    totalMove += Math.abs(dx) + Math.abs(dy);

                    float curDist = pinchDistance(ev);
                    float distDelta = curDist - lastPinchDist;

                    // Responsive 2-finger pinch-to-zoom vs 2-finger smooth scroll
                    if (Math.abs(distDelta) > dp(14) && Math.abs(distDelta) > (Math.abs(dx) + Math.abs(dy)) * 0.9f) {
                        hapticTick(this);
                        sendCmd(distDelta > 0
                                ? "{\"t\":\"shortcut\",\"a\":\"zoom_in\"}"
                                : "{\"t\":\"shortcut\",\"a\":\"zoom_out\"}");
                        lastPinchDist = curDist;
                        lastScrollX = curX;
                        lastScrollY = curY;
                    } else {
                        float vScroll = dy / dp(20);
                        float hScroll = -dx / dp(24);
                        lastScrollX = curX;
                        lastScrollY = curY;
                        lastPinchDist = curDist;
                        if (Math.abs(vScroll) > 0.01f || Math.abs(hScroll) > 0.01f) {
                            sendFastUdpMotion(2, 0, vScroll, hScroll);
                        }
                    }
                } else if (count >= 3 && !threeGestureTriggered) {
                    float curX = (ev.getX(0) + ev.getX(1) + ev.getX(2)) / 3f;
                    float curY = (ev.getY(0) + ev.getY(1) + ev.getY(2)) / 3f;
                    float dx = curX - lastThreeX;
                    float dy = curY - lastThreeY;
                    totalMove += Math.abs(dx) + Math.abs(dy);
                    if (Math.abs(dx) > dp(42) && Math.abs(dx) > Math.abs(dy)) {
                        threeGestureTriggered = true;
                        hapticTick(this);
                        sendCmd(dx > 0
                                ? "{\"t\":\"shortcut\",\"a\":\"app_next\"}"
                                : "{\"t\":\"shortcut\",\"a\":\"app_prev\"}");
                    } else if (Math.abs(dy) > dp(42) && Math.abs(dy) > Math.abs(dx)) {
                        threeGestureTriggered = true;
                        hapticTick(this);
                        sendCmd("{\"t\":\"shortcut\",\"a\":\"overview\"}");
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchClutch = false;
                inScrollStrip = false;
                inZoomStrip = false;
                long dt = System.currentTimeMillis() - downTime;
                if (dt < 230 && totalMove < dp(12)) {
                    hapticTick(this);
                    if (maxPointers == 1) {
                        sendCmd("{\"t\":\"click\",\"b\":\"left\"}");
                    } else if (maxPointers == 2) {
                        sendCmd("{\"t\":\"click\",\"b\":\"right\"}");
                    } else if (maxPointers == 3) {
                        sendCmd("{\"t\":\"click\",\"b\":\"middle\"}");
                    }
                }
                maxPointers = 0;
                invalidate();
            }
            return true;
        }
    }
}
