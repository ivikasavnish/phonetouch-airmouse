# PhoneTouch AirMouse (Rust Linux Daemon + Native Android App)

Turn any Android phone into an ultra-low-latency **3D Gyroscope + Accelerometer Air Mouse**, **5-Finger Multi-Touch Trackpad**, and **1:1 Direct Desktop Touchscreen** for Linux over **Wi-Fi** or **USB**—with **zero external dependencies**.

---

## ✨ Key Features

- **🦀 Zero-Dependency Native Rust Kernel Daemon (`phonetouch-daemon`)**
  - Directly creates two Linux kernel input devices via `/dev/uinput`:
    - `PhoneTouch Virtual AirMouse (Rust)` — Relative mouse, high-resolution scroll wheel (`REL_WHEEL_HI_RES`), mouse buttons, media/volume keys, and full keyboard input.
    - `PhoneTouch Virtual Touchscreen (Rust)` — 5-slot Type-B Multi-Touch (`ABS_MT_SLOT` 0–4) 1:1 absolute screen mapping with multi-monitor region selection.
  - Uses `< 600 KB` of RAM and runs automatically on login via `systemd`.
- **🚀 Hybrid Binary UDP + TCP Protocol with Stream Authentication**
  - **16-Byte Authenticated Binary UDP Packets (`0x50 0x54`)** on port `9998` for zero-head-of-line-blocking 120Hz+ cursor motion (`rel`), smooth scrolling (`scroll`), and multi-touch (`mt`), authenticated via 32-bit FNV-1a token hashing.
  - **Reliable TCP Control Channel** on port `9999` for clicks, keyboard text/voice dictation, media keys, and state sync.
- **🔑 QR-Authenticated Air Mouse & Server Turn-Off Control**
  - Built-in desktop control CLI (`phonetouch-daemon on|off|toggle|qr|status|rotate`) and local web dashboard (`http://127.0.0.1:9997`) displaying an SVG **QR Code** (`phonetouch://<IP>:9999/<TOKEN>`) and a one-click **Server Turn-Off / Turn-On** switch.
  - Scanning the QR code with your phone's camera opens the Android app automatically via deep-link (`phonetouch://`), configures the Wi-Fi host IP, and authenticates both TCP and UDP channels.
- **📱 Native Android App (`PhoneTouch.apk`)**
  - **3 Operating Modes**:
    1. **Trackpad + Air Trigger**: Multi-touch trackpad + hold button to steer cursor in 3D space using hardware `TYPE_GYROSCOPE` + `TYPE_LINEAR_ACCELERATION`.
    2. **1:1 Multi-Touch Screen**: Up to 5 simultaneous fingers mapped 1:1 to your desktop monitor.
    3. **Continuous 3D Air Mouse**: Hands-free air pointer; touch the center pad to clutch/pause.
  - **⌨️ Full Remote Keyboard & Voice Dictation**: Tap `⌨️` to type or use Android Voice-to-Text (`🎤`) and beam complete sentences or commands straight to your Linux desktop.
  - **🎵 Desktop Media, Volume, Laser Spotlight & Multi-Monitor Bar**:
    - Dedicated quick-bar for Mute (`🔇`), Volume Down/Up (`🔉−` / `🔊+`), Previous/Play-Pause/Next Track (`⏮`, `⏯`, `⏭`).
    - **🔴 Laser Spotlight**: Triggers desktop cursor locator ring (`KEY_LEFTCTRL` pulse) on demand or automatically when engaging Air Mouse.
    - **🖥️ Multi-Monitor Selector**: Cycle 1:1 Touchscreen mapping between **Full Desktop (`0–100%`)**, **Left/Primary Monitor (`0–50%`)**, and **Right/Secondary Monitor (`50–100%`)**.
  - **📳 Tactile Haptic Feedback**: Subtle hardware haptic ticks on buttons, scroll/zoom notches, and wrist flip-tab gestures.
  - **🛌 Face-Down / Pocket Auto-Standby**: Uses `TYPE_GRAVITY` (`z < -7.8 m/s²`) and `TYPE_PROXIMITY` to automatically pause cursor and touch input whenever the phone is placed face-down on a desk or in a pocket, and resumes on pickup.
  - **Dedicated Edge Strips**:
    - **Left Edge (`＋ ZOOM －`)**: Slide 1 finger up/down to Zoom In / Out.
    - **Right Edge (`▲ SCROLL ▼`)**: Slide 1 finger up/down for effortless high-resolution scrolling.
  - **Multi-Touch & Wrist Gestures**:
    - **Flip Phone Wrist Left / Right**: Switch browser/editor tabs (`Ctrl+Shift+Tab` / `Ctrl+Tab`).
    - **2-Finger Pinch / Spread**: Zoom Out / In (`Ctrl+-` / `Ctrl++` + `Ctrl+Wheel`).
    - **2-Finger Slide**: Smooth vertical & horizontal scrolling.
    - **3-Finger Swipe Left/Right/Up**: Switch windows (`Alt+Tab`) or open desktop overview (`Super`).
  - **🥷 OLED Stealth Mode**: Turns the phone screen pure `#000000` black at 1% brightness to save battery. Wake anytime with 1 tap on the top pill or by pressing Volume / Back.
  - **⚡ Master ON / 🔒 OFF Switch**: Completely disconnects the socket and unregisters hardware Gyro/Accel sensors when not needed so your cursor never moves accidentally.

---

## 🚀 Quick Start (Linux Desktop Server)

### 1. Build the Rust Daemon
```bash
rustc -O phonetouch_daemon.rs -o phonetouch-daemon
```

### 2. Install & Enable Autostart Service (`systemd`)
```bash
mkdir -p ~/.config/systemd/user
cp phonetouch.service ~/.config/systemd/user/phonetouch.service
systemctl --user daemon-reload
systemctl --user enable --now phonetouch.service
```

### 3. Server Control & QR Code Pairing
```bash
# Show QR code in terminal for phone pairing
./phonetouch-daemon qr

# Turn server input OFF / ON from terminal
./phonetouch-daemon off
./phonetouch-daemon on
./phonetouch-daemon toggle

# Check status or rotate QR security token
./phonetouch-daemon status
./phonetouch-daemon rotate
```
Or open **`http://127.0.0.1:9997`** in your desktop browser for the visual **Server Control & QR Auth Dashboard**.

---

## 🍎 Quick Start (macOS Desktop Server)

The same daemon builds on macOS; `input_macos.rs` replaces `/dev/uinput` with CoreGraphics event injection. The 1:1 touchscreen mode drives the pointer with the first finger only, since macOS has no touch injection API.

```bash
brew install qrencode                      # QR codes in terminal & dashboard
mkdir -p bin && rustc -O phonetouch_daemon.rs -o bin/phonetouch-daemon

# Autostart on login (launchd)
cp com.phonetouch.daemon.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.phonetouch.daemon.plist
tail -f /tmp/phonetouch.log
```

On first start, macOS asks to let `phonetouch-daemon` control the computer. Allow it, otherwise macOS silently drops the injected events. Each rebuild changes the binary, so macOS asks again; turn the new entry on (remove a stale one first). The `on|off|toggle|qr|status|rotate` CLI and the dashboard work the same as on Linux.

### Menu bar control (`macos/`)

`PhoneTouchBar.app` (installed to `/Applications`) is a native menu bar item that shows daemon status. From it you can turn input on or off, show the pairing QR code, copy the URI, rotate the token, and start, restart or stop the daemon. It also warns when Accessibility permission is missing.

```bash
./macos/build_menubar.sh                   # builds and installs /Applications/PhoneTouchBar.app
cp macos/com.phonetouch.menubar.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.phonetouch.menubar.plist
```

---

## 📱 Building & Installing the Native Android App

Requires Android SDK (`aapt2`, `d8`, `apksigner`, `zipalign`, `adb`) and JDK:
```bash
bash build_and_install_apk.sh
```

---

## 📄 License
MIT License
