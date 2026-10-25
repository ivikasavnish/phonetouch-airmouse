#!/usr/bin/env python3
"""
PhoneTouch Server:
Turns your USB-connected Android phone into a low-latency:
1. Gyro + Accelerometer Air Mouse (Point & move in 3D space)
2. Multi-gesture Trackpad (1-finger move, 2-finger scroll, tap-to-click)
3. Direct 1:1 Absolute Touchscreen (maps phone screen to desktop screen)

Zero external dependencies. Uses Python standard library + Linux /dev/uinput.
"""

import asyncio
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import subprocess
import sys
from uinput_device import VirtualInput

PORT = 8443
TCP_PORT = 9999
ADB_BIN = "/home/vikasavn/Android/Sdk/platform-tools/adb"
CERT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cert.pem")
KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "key.pem")
WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>PhoneTouch & AirMouse</title>
<style>
  :root {
    --bg: #0f172a;
    --panel: #1e293b;
    --accent: #38bdf8;
    --accent-active: #0284c7;
    --green: #22c55e;
    --text: #f8fafc;
    --muted: #94a3b8;
  }
  * {
    box-sizing: border-box;
    user-select: none;
    -webkit-user-select: none;
    -webkit-touch-callout: none;
  }
  body, html {
    margin: 0; padding: 0;
    width: 100%; height: 100%;
    background: var(--bg);
    color: var(--text);
    font-family: system-ui, -apple-system, sans-serif;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 12px;
    background: var(--panel);
    border-bottom: 1px solid #334155;
    font-size: 13px;
  }
  .status-dot {
    display: inline-block;
    width: 9px; height: 9px;
    border-radius: 50%;
    background: #ef4444;
    margin-right: 6px;
  }
  .status-dot.connected { background: var(--green); }
  .tabs {
    display: flex;
    gap: 6px;
    padding: 8px 12px;
    background: var(--bg);
  }
  .tab {
    flex: 1;
    padding: 9px 4px;
    text-align: center;
    background: var(--panel);
    border: 1px solid #334155;
    border-radius: 8px;
    font-weight: 600;
    font-size: 13px;
    color: var(--muted);
  }
  .tab.active {
    background: var(--accent-active);
    color: white;
    border-color: var(--accent);
  }
  .controls-bar {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 4px 14px 8px;
    font-size: 12px;
    color: var(--muted);
  }
  .controls-bar input[type=range] {
    flex: 1;
    accent-color: var(--accent);
  }
  #touch-area {
    flex: 1;
    margin: 4px 12px;
    background: radial-gradient(circle at center, #1e293b 0%, #0f172a 100%);
    border: 2px dashed #334155;
    border-radius: 16px;
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    touch-action: none;
    overflow: hidden;
  }
  #touch-area.air-active {
    border-color: var(--accent);
    box-shadow: inset 0 0 30px rgba(56, 189, 248, 0.2);
  }
  .hint-title {
    font-size: 18px;
    font-weight: 700;
    color: var(--text);
    margin-bottom: 6px;
    pointer-events: none;
    text-align: center;
  }
  .hint-sub {
    font-size: 13px;
    color: var(--muted);
    text-align: center;
    max-width: 80%;
    line-height: 1.4;
    pointer-events: none;
  }
  #sensor-readout {
    position: absolute;
    bottom: 10px;
    font-family: monospace;
    font-size: 11px;
    color: var(--accent);
    pointer-events: none;
  }
  .air-trigger {
    margin: 4px 12px;
    padding: 16px;
    background: #1e293b;
    border: 2px solid var(--accent);
    border-radius: 12px;
    text-align: center;
    font-weight: 700;
    font-size: 15px;
    color: var(--accent);
    touch-action: none;
  }
  .air-trigger.active {
    background: var(--accent-active);
    color: white;
  }
  .mouse-buttons {
    display: flex;
    gap: 8px;
    padding: 8px 12px 14px;
    height: 76px;
  }
  .mbtn {
    flex: 1;
    background: var(--panel);
    border: 1px solid #475569;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    font-size: 14px;
    color: var(--text);
    touch-action: none;
  }
  .mbtn.middle { flex: 0.5; font-size: 12px; color: var(--muted); }
  .mbtn:active, .mbtn.pressed {
    background: var(--accent-active);
    border-color: var(--accent);
  }
</style>
</head>
<body>

<header>
  <div><span id="dot" class="status-dot"></span><span id="status">Connecting...</span></div>
  <div id="sensor-status">Sensors: Ready</div>
</header>

<div class="tabs">
  <div class="tab active" data-mode="trackpad">Trackpad + Air</div>
  <div class="tab" data-mode="touchscreen">1:1 Touchscreen</div>
  <div class="tab" data-mode="aironly">Continuous Air</div>
</div>

<div class="controls-bar">
  <span>Sensitivity</span>
  <input id="sens" type="range" min="1" max="30" value="12">
  <span id="sens-val">12</span>
</div>

<div id="touch-area">
  <div class="hint-title" id="mode-title">Trackpad + Air Mouse</div>
  <div class="hint-sub" id="mode-desc">
    Slide 1 finger to move cursor • Tap to click<br>
    Slide 2 fingers to scroll • Hold button below for Air Mouse
  </div>
  <div id="sensor-readout">Gyro: 0.0, 0.0 | Accel: 0.0, 0.0</div>
</div>

<div id="air-btn" class="air-trigger">
  HOLD FOR AIR MOUSE (GYRO + ACCEL)
</div>

<div class="mouse-buttons">
  <div class="mbtn" id="btn-left">LEFT</div>
  <div class="mbtn middle" id="btn-middle">MID</div>
  <div class="mbtn" id="btn-right">RIGHT</div>
</div>

<script>
(() => {
  const dot = document.getElementById('dot');
  const statusEl = document.getElementById('status');
  const sensorStatusEl = document.getElementById('sensor-status');
  const readoutEl = document.getElementById('sensor-readout');
  const touchArea = document.getElementById('touch-area');
  const airBtn = document.getElementById('air-btn');
  const sensInput = document.getElementById('sens');
  const sensVal = document.getElementById('sens-val');
  const modeTitle = document.getElementById('mode-title');
  const modeDesc = document.getElementById('mode-desc');

  let ws = null;
  let mode = 'trackpad'; // 'trackpad' | 'touchscreen' | 'aironly'
  let airHold = false;
  let sensitivity = 12;

  sensInput.addEventListener('input', () => {
    sensitivity = parseFloat(sensInput.value);
    sensVal.textContent = sensitivity;
  });

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${proto}//${location.host}/ws`);
    ws.onopen = () => {
      dot.classList.add('connected');
      statusEl.textContent = 'Connected (Low Latency USB)';
    };
    ws.onclose = () => {
      dot.classList.remove('connected');
      statusEl.textContent = 'Reconnecting...';
      setTimeout(connect, 1000);
    };
  }
  connect();

  function send(payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
    }
  }

  // Mode switching
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('touchstart', (e) => {
      e.preventDefault();
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      mode = tab.dataset.mode;
      requestSensorPermissions();

      if (mode === 'trackpad') {
        modeTitle.textContent = 'Trackpad + Air Mouse';
        modeDesc.innerHTML = 'Slide 1 finger to move • Tap to click<br>2 fingers to scroll • Hold Air button to point in air';
        airBtn.style.display = 'block';
        touchArea.classList.remove('air-active');
      } else if (mode === 'touchscreen') {
        modeTitle.textContent = '1:1 Absolute Touchscreen';
        modeDesc.innerHTML = 'Touching anywhere inside this box maps directly<br>to the exact position on your desktop monitor';
        airBtn.style.display = 'none';
        touchArea.classList.remove('air-active');
      } else if (mode === 'aironly') {
        modeTitle.textContent = 'Continuous Air Mouse Active';
        modeDesc.innerHTML = 'Move & tilt your phone in the air to steer cursor!<br>Touch anywhere in this box to pause/clutch';
        airBtn.style.display = 'none';
        touchArea.classList.add('air-active');
      }
    });
  });

  // Air Mouse Trigger Button
  let touchClutch = false; // In aironly mode, touching the pad pauses air motion
  airBtn.addEventListener('touchstart', (e) => {
    e.preventDefault();
    requestSensorPermissions();
    airHold = true;
    airBtn.classList.add('active');
    touchArea.classList.add('air-active');
  });
  const stopAirHold = (e) => {
    e.preventDefault();
    airHold = false;
    airBtn.classList.remove('active');
    if (mode !== 'aironly') touchArea.classList.remove('air-active');
  };
  airBtn.addEventListener('touchend', stopAirHold);
  airBtn.addEventListener('touchcancel', stopAirHold);

  // Mouse Buttons
  ['left', 'middle', 'right'].forEach(btn => {
    const el = document.getElementById(`btn-${btn}`);
    el.addEventListener('touchstart', (e) => {
      e.preventDefault();
      el.classList.add('pressed');
      send({ t: 'btn', b: btn, p: true });
    });
    const release = (e) => {
      e.preventDefault();
      el.classList.remove('pressed');
      send({ t: 'btn', b: btn, p: false });
    };
    el.addEventListener('touchend', release);
    el.addEventListener('touchcancel', release);
  });

  // Touch Area Handling (Trackpad + 1:1 Touchscreen)
  let lastX = 0, lastY = 0;
  let lastScrollY = 0;
  let touchStartTime = 0;
  let totalMove = 0;
  let maxTouches = 0;

  touchArea.addEventListener('touchstart', (e) => {
    e.preventDefault();
    requestSensorPermissions();
    touchClutch = true;
    const touches = e.touches;
    maxTouches = Math.max(maxTouches, touches.length);

    if (mode === 'touchscreen') {
      const rect = touchArea.getBoundingClientRect();
      const nx = (touches[0].clientX - rect.left) / rect.width;
      const ny = (touches[0].clientY - rect.top) / rect.height;
      send({ t: 'abs', x: nx, y: ny, d: true });
      return;
    }

    if (touches.length === 1) {
      lastX = touches[0].clientX;
      lastY = touches[0].clientY;
      touchStartTime = performance.now();
      totalMove = 0;
    } else if (touches.length === 2) {
      lastScrollY = (touches[0].clientY + touches[1].clientY) / 2;
    }
  });

  touchArea.addEventListener('touchmove', (e) => {
    e.preventDefault();
    const touches = e.touches;

    if (mode === 'touchscreen') {
      const rect = touchArea.getBoundingClientRect();
      const nx = (touches[0].clientX - rect.left) / rect.width;
      const ny = (touches[0].clientY - rect.top) / rect.height;
      send({ t: 'abs', x: nx, y: ny, d: true });
      return;
    }

    if (touches.length === 1 && maxTouches === 1) {
      const dx = (touches[0].clientX - lastX) * (sensitivity / 6.0);
      const dy = (touches[0].clientY - lastY) * (sensitivity / 6.0);
      totalMove += Math.abs( touches[0].clientX - lastX ) + Math.abs( touches[0].clientY - lastY );
      lastX = touches[0].clientX;
      lastY = touches[0].clientY;
      send({ t: 'rel', dx: dx, dy: dy });
    } else if (touches.length === 2) {
      const curY = (touches[0].clientY + touches[1].clientY) / 2;
      const dy = curY - lastScrollY;
      if (Math.abs(dy) > 10) {
        send({ t: 'scroll', dy: dy > 0 ? 1 : -1 });
        lastScrollY = curY;
      }
    }
  });

  const onTouchEnd = (e) => {
    e.preventDefault();
    if (e.touches.length === 0) {
      touchClutch = false;
      if (mode === 'touchscreen') {
        send({ t: 'abs', x: 0, y: 0, d: false });
      } else {
        const dt = performance.now() - touchStartTime;
        if (dt < 220 && totalMove < 12) {
          if (maxTouches === 1) send({ t: 'click', b: 'left' });
          else if (maxTouches === 2) send({ t: 'click', b: 'right' });
        }
      }
      maxTouches = 0;
    }
  };
  touchArea.addEventListener('touchend', onTouchEnd);
  touchArea.addEventListener('touchcancel', onTouchEnd);

  // Gyroscope + Accelerometer Sensor Fusion for Air Mouse
  let sensorsStarted = false;
  let smoothVx = 0, smoothVy = 0;

  function requestSensorPermissions() {
    if (sensorsStarted) return;
    if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
      DeviceMotionEvent.requestPermission().then(state => {
        if (state === 'granted') startSensors();
      }).catch(console.error);
    } else {
      startSensors();
    }
  }

  function startSensors() {
    if (sensorsStarted) return;
    sensorsStarted = true;

    window.addEventListener('devicemotion', (e) => {
      const rot = e.rotationRate;
      const acc = e.acceleration; // Linear acceleration (without gravity)

      if (!rot && !acc) {
        sensorStatusEl.textContent = 'Sensors: Needs HTTPS';
        return;
      }

      // Gyro rotation rate (deg/s):
      // alpha = Z-axis (yaw: pointing phone left/right)
      // beta  = X-axis (pitch: tilting phone up/down)
      const alpha = (rot && rot.alpha) ? rot.alpha : 0;
      const beta  = (rot && rot.beta)  ? rot.beta  : 0;

      // Linear acceleration (m/s^2) for air translation boost
      const ax = (acc && acc.x) ? acc.x : 0;
      const ay = (acc && acc.y) ? acc.y : 0;

      readoutEl.textContent = `Gyro: ${alpha.toFixed(1)}, ${beta.toFixed(1)} | Accel: ${ax.toFixed(1)}, ${ay.toFixed(1)}`;
      sensorStatusEl.textContent = 'Sensors: Active';

      const isAirActive = airHold || (mode === 'aironly' && !touchClutch);
      if (!isAirActive) {
        smoothVx = 0;
        smoothVy = 0;
        return;
      }

      // Combine gyroscope angular velocity + linear accelerometer translation
      // Deadzone filtering to prevent drift when holding still
      const deadzone = 1.2;
      const gYaw   = Math.abs(alpha) > deadzone ? -alpha : 0;
      const gPitch = Math.abs(beta)  > deadzone ? -beta  : 0;

      const aX = Math.abs(ax) > 0.35 ? -ax * 6.0 : 0;
      const aY = Math.abs(ay) > 0.35 ?  ay * 6.0 : 0;

      const rawVx = (gYaw + aX) * (sensitivity * 0.032);
      const rawVy = (gPitch + aY) * (sensitivity * 0.032);

      // Exponential moving average for silky-smooth cursor motion
      smoothVx = smoothVx * 0.55 + rawVx * 0.45;
      smoothVy = smoothVy * 0.55 + rawVy * 0.45;

      if (Math.abs(smoothVx) > 0.05 || Math.abs(smoothVy) > 0.05) {
        send({ t: 'rel', dx: smoothVx, dy: smoothVy });
      }
    }, true);
  }
})();
</script>
</body>
</html>
"""


def ensure_tls_cert():
    """Generates a self-signed TLS certificate so Android Chrome allows Gyro/Accelerometer APIs."""
    if os.path.exists(CERT_FILE) and os.path.exists(KEY_FILE):
        return
    print("[*] Generating self-signed TLS certificate for Web Sensor APIs...")
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-keyout",
            KEY_FILE,
            "-out",
            CERT_FILE,
            "-days",
            "365",
            "-nodes",
            "-subj",
            "/CN=phonetouch.local",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def get_local_ips():
    ips = []
    try:
        out = subprocess.check_output(["ip", "-4", "-br", "addr"], text=True)
        for line in out.strip().splitlines():
            parts = line.split()
            if len(parts) >= 3 and parts[0] != "lo":
                ip = parts[2].split("/")[0]
                ips.append((parts[0], ip))
    except Exception:
        pass
    return ips


def try_adb_reverse():
    adb_cmd = ADB_BIN if os.path.exists(ADB_BIN) else "adb"
    ok = False
    for p in (TCP_PORT, PORT):
        try:
            res = subprocess.run(
                [adb_cmd, "reverse", f"tcp:{p}", f"tcp:{p}"],
                capture_output=True,
                text=True,
                timeout=3,
            )
            if res.returncode == 0:
                ok = True
        except Exception:
            pass
    return ok


async def handle_tcp_client(
    reader: asyncio.StreamReader, writer: asyncio.StreamWriter, vinput: VirtualInput
):
    """Handles ultra-low-latency newline-delimited JSON stream from the Native Android App."""
    peer = writer.get_extra_info("peername")
    print(f"[+] Native Android App connected over USB from {peer}")
    try:
        while True:
            line = await reader.readline()
            if not line:
                break
            data = json.loads(line.decode("utf-8", errors="ignore"))
            handle_input_message(vinput, data)
    except (asyncio.IncompleteReadError, ConnectionResetError):
        pass
    except Exception as e:
        print(f"[!] Native app stream error: {e}")
    finally:
        print(f"[-] Native Android App disconnected: {peer}")
        writer.close()


async def read_ws_frame(reader: asyncio.StreamReader):
    hdr = await reader.readexactly(2)
    b1, b2 = hdr[0], hdr[1]
    opcode = b1 & 0x0F
    masked = bool(b2 & 0x80)
    length = b2 & 0x7F

    if length == 126:
        length = struct.unpack("!H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", await reader.readexactly(8))[0]

    mask_key = await reader.readexactly(4) if masked else b""
    payload = bytearray(await reader.readexactly(length))
    if masked:
        for i in range(length):
            payload[i] ^= mask_key[i % 4]

    return opcode, bytes(payload)


def handle_input_message(vinput: VirtualInput, msg: dict):
    t = msg.get("t")
    if t == "rel":
        vinput.move_rel(float(msg.get("dx", 0.0)), float(msg.get("dy", 0.0)))
    elif t == "abs":
        vinput.touch_abs(
            float(msg.get("x", 0.0)),
            float(msg.get("y", 0.0)),
            bool(msg.get("d", False)),
        )
    elif t == "btn":
        vinput.mouse_button(str(msg.get("b", "left")), bool(msg.get("p", False)))
    elif t == "click":
        vinput.click(str(msg.get("b", "left")))
    elif t == "scroll":
        vinput.scroll(int(msg.get("dy", 0)), int(msg.get("dx", 0)))


async def handle_client(
    reader: asyncio.StreamReader, writer: asyncio.StreamWriter, vinput: VirtualInput
):
    try:
        request_data = await reader.readuntil(b"\r\n\r\n")
    except Exception:
        writer.close()
        return

    req_text = request_data.decode("utf-8", errors="ignore")
    lines = req_text.split("\r\n")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()

    if headers.get("upgrade", "").lower() == "websocket":
        key = headers.get("sec-websocket-key", "")
        accept = base64.b64encode(
            hashlib.sha1((key + WS_MAGIC).encode("utf-8")).digest()
        ).decode("utf-8")
        handshake = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
        )
        writer.write(handshake.encode("utf-8"))
        await writer.drain()

        peer = writer.get_extra_info("peername")
        print(f"[+] Phone connected from {peer}")
        try:
            while True:
                opcode, payload = await read_ws_frame(reader)
                if opcode == 0x8:  # Close
                    break
                if opcode == 0x1:  # Text
                    data = json.loads(payload.decode("utf-8"))
                    handle_input_message(vinput, data)
        except (asyncio.IncompleteReadError, ConnectionResetError):
            pass
        except Exception as e:
            print(f"[!] Client error: {e}")
        finally:
            print(f"[-] Phone disconnected: {peer}")
            writer.close()
    else:
        body = HTML_PAGE.encode("utf-8")
        resp = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: text/html; charset=utf-8\r\n"
            f"Content-Length: {len(body)}\r\n"
            "Cache-Control: no-store\r\n"
            "Connection: close\r\n\r\n"
        ).encode("utf-8") + body
        writer.write(resp)
        await writer.drain()
        writer.close()


async def main():
    vinput = VirtualInput()
    print("[+] Created kernel virtual devices via /dev/uinput:")
    print("    - PhoneTouch Virtual AirMouse (Relative Mouse + Scroll + Buttons)")
    print("    - PhoneTouch Virtual Touchscreen (1:1 Absolute Touch)")

    tcp_server = await asyncio.start_server(
        lambda r, w: handle_tcp_client(r, w, vinput),
        host="0.0.0.0",
        port=TCP_PORT,
        reuse_address=True,
    )

    ws_server = None
    try:
        ensure_tls_cert()
        ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_ctx.load_cert_chain(certfile=CERT_FILE, keyfile=KEY_FILE)
        ws_server = await asyncio.start_server(
            lambda r, w: handle_client(r, w, vinput),
            host="0.0.0.0",
            port=PORT,
            ssl=ssl_ctx,
            reuse_address=True,
        )
    except OSError:
        print(f"[!] Note: Web port {PORT} already in use; continuing with Native Android App TCP server on port {TCP_PORT}.")

    adb_ok = try_adb_reverse()
    ips = get_local_ips()

    print("\n" + "=" * 62)
    print(" PHONETOUCH + GYRO/ACCEL AIR MOUSE SERVER IS RUNNING")
    print("=" * 62)
    print(f" [Native Android App] Listening on TCP port {TCP_PORT} (ADB USB Tunnel: {'ACTIVE' if adb_ok else 'INACTIVE'})")
    if ws_server and adb_ok:
        print(f" [Web Fallback]       https://localhost:{PORT}")
    if ips:
        for iface, ip in ips:
            print(f" [Network Interface]  {ip}:{TCP_PORT} ({iface})")
    print("=" * 62 + "\n")

    try:
        if ws_server:
            async with ws_server, tcp_server:
                await asyncio.gather(
                    ws_server.serve_forever(),
                    tcp_server.serve_forever(),
                )
        else:
            async with tcp_server:
                await tcp_server.serve_forever()
    finally:
        vinput.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nShutting down.")
        sys.exit(0)
