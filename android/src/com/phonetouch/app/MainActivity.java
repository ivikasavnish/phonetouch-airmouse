package com.phonetouch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    // Power / Disconnect switch, QR Auth Token & OLED Stealth Mode
    private volatile boolean powerEnabled = true;
    private volatile boolean stealthMode = false;
    private volatile Socket activeSocket = null;
    private volatile String discoveredWifiHost = null;
    private volatile String authToken = "c09ca300459f9302";

    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private Sensor accelSensor;

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
    private LinearLayout keyRowLayout;
    private LinearLayout btnRowLayout;
    private TextView statusText;
    private TextView sensorReadout;
    private TextView pairBtn;
    private TextView stealthBtn;
    private TextView powerBtn;
    private TextView airTriggerBtn;
    private TextView[] tabButtons = new TextView[3];
    private TouchPadView touchPadView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

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

        handlePairingIntent(getIntent());

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (accelSensor == null) {
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }

        setContentView(buildUI());
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

        sendCmd(String.format(Locale.US, "{\"t\":\"auth\",\"k\":\"%s\"}", authToken));
        try {
            if (activeSocket != null) activeSocket.close();
        } catch (Exception ignored) {}
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
                airHold = true;
                airTriggerBtn.setBackgroundColor(Color.parseColor("#0284C7"));
                airTriggerBtn.setTextColor(Color.WHITE);
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
        airLp.bottomMargin = dp(8);
        rootLayout.addView(airTriggerBtn, airLp);

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
                btn.setBackgroundColor(Color.parseColor("#38BDF8"));
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
            keyRowLayout.setVisibility(View.VISIBLE);
            btnRowLayout.setVisibility(View.VISIBLE);
        }
        if (touchPadView != null) {
            touchPadView.invalidate();
        }
    }

    private void togglePower() {
        powerEnabled = !powerEnabled;
        if (!powerEnabled) {
            airHold = false;
            touchClutch = false;
            sendQueue.clear();
            powerBtn.setText("🔒 OFF");
            powerBtn.setBackgroundColor(Color.parseColor("#DC2626"));
            statusText.setText("● Disconnected (Standby — No Mouse Input)");
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
            statusText.setText("● Reconnecting (Wi-Fi / USB)...");
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
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private long lastFlipTabTime = 0;
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
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION ||
            event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            latestAccel[0] = event.values[0];
            latestAccel[1] = event.values[1];
            latestAccel[2] = event.values[2];
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // Android Gyroscope in rad/s:
            // values[0] = X-axis (Pitch: tilting phone up/down)
            // values[1] = Y-axis (Roll: flipping/rolling phone wrist left/right)
            // values[2] = Z-axis (Yaw: pointing phone left/right)
            float pitchRad = event.values[0];
            float rollRad = event.values[1];
            float yawRad = event.values[2];

            long now = System.currentTimeMillis();

            // Flip phone left/right (quick wrist roll) to switch tabs when not dragging on pad
            if (!touchClutch && (now - lastFlipTabTime > 480) && Math.abs(rollRad) > 4.2f
                    && Math.abs(rollRad) > Math.abs(pitchRad) * 1.5f
                    && Math.abs(rollRad) > Math.abs(yawRad) * 1.5f) {
                lastFlipTabTime = now;
                if (rollRad > 0) {
                    sendCmd("{\"t\":\"shortcut\",\"a\":\"tab_next\"}");
                    hudBannerText = "⚡ FLIP RIGHT → NEXT TAB";
                } else {
                    sendCmd("{\"t\":\"shortcut\",\"a\":\"tab_prev\"}");
                    hudBannerText = "⚡ FLIP LEFT → PREV TAB";
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
                sendCmd(String.format(Locale.US, "{\"t\":\"rel\",\"dx\":%.3f,\"dy\":%.3f}", smoothVx, smoothVy));
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
                sub3 = "3-Finger Swipe: Apps/Overview • Flip Phone: Switch Tab";
            } else if (currentMode == 1) {
                title = "1:1 Multi-Touch Desktop Screen";
                sub1 = "Up to 5 simultaneous fingers mapped 1:1";
                sub2 = "to your Linux desktop monitor";
                sub3 = "Flip phone wrist Left/Right to switch Tabs";
            } else {
                title = "Continuous 3D Air Mouse Active";
                sub1 = "Point & tilt phone in air • Flip wrist L/R: Switch Tab";
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

            if (!powerEnabled) {
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
                        sendCmd(String.format(Locale.US, "{\"t\":\"scroll\",\"dy\":%.3f,\"dx\":0}", scrollDelta));
                    }
                } else if (count == 1 && maxPointers == 1) {
                    float dx = (ev.getX(0) - lastX) * (sensitivity / 9.0f);
                    float dy = (ev.getY(0) - lastY) * (sensitivity / 9.0f);
                    totalMove += Math.abs(ev.getX(0) - lastX) + Math.abs(ev.getY(0) - lastY);
                    lastX = ev.getX(0);
                    lastY = ev.getY(0);
                    if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f) {
                        sendCmd(String.format(Locale.US, "{\"t\":\"rel\",\"dx\":%.3f,\"dy\":%.3f}", dx, dy));
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
                            sendCmd(String.format(Locale.US,
                                    "{\"t\":\"scroll\",\"dy\":%.3f,\"dx\":%.3f}", vScroll, hScroll));
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
                        sendCmd(dx > 0
                                ? "{\"t\":\"shortcut\",\"a\":\"app_next\"}"
                                : "{\"t\":\"shortcut\",\"a\":\"app_prev\"}");
                    } else if (Math.abs(dy) > dp(42) && Math.abs(dy) > Math.abs(dx)) {
                        threeGestureTriggered = true;
                        sendCmd("{\"t\":\"shortcut\",\"a\":\"overview\"}");
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchClutch = false;
                inScrollStrip = false;
                inZoomStrip = false;
                long dt = System.currentTimeMillis() - downTime;
                if (dt < 230 && totalMove < dp(12)) {
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
