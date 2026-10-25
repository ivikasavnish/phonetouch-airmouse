// PhoneTouch Native Rust Daemon (Zero external dependencies, pure std + OS input APIs)
// Features:
// - Linux (input_linux.rs): creates two kernel virtual devices via /dev/uinput:
//     1) PhoneTouch Virtual AirMouse (Relative movement, scroll wheel, Left/Middle/Right buttons)
//     2) PhoneTouch Virtual Touchscreen (1:1 Absolute desktop screen mapping)
// - macOS (input_macos.rs): posts synthetic CoreGraphics (Quartz) events; needs Accessibility permission
// - Auto-maintains ADB USB reverse tunnel (`adb reverse tcp:9999 tcp:9999`) whenever phone is plugged in
// - Ultra-low-latency TCP server on 0.0.0.0:9999 with TCP_NODELAY

use std::env;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const TCP_PORT: u16 = 9999;
const HTTP_CONTROL_PORT: u16 = 9997;
const UDP_DISCOVERY_PORT: u16 = 9998;
const ADB_BINS: [&str; 3] = [
    "/home/vikasavn/Android/Sdk/platform-tools/adb",
    "/opt/homebrew/bin/adb",
    "/usr/local/bin/adb",
];

#[cfg(target_os = "linux")]
#[path = "input_linux.rs"]
mod input;
#[cfg(target_os = "macos")]
#[path = "input_macos.rs"]
mod input;
use input::VirtualInput;


// Fast, zero-allocation JSON field extractors for the flat protocol messages:
// {"t":"rel","dx":1.2,"dy":-0.5}
// {"t":"abs","x":0.5,"y":0.5,"d":true}
// {"t":"btn","b":"left","p":true}
// {"t":"click","b":"left"}
// {"t":"scroll","dy":1}
fn extract_str<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    let pat = format!("\"{}\":\"", key);
    let start = line.find(&pat)? + pat.len();
    let rest = &line[start..];
    let end = rest.find('"')?;
    Some(&rest[..end])
}

fn extract_f64(line: &str, key: &str) -> Option<f64> {
    let pat = format!("\"{}\":", key);
    let start = line.find(&pat)? + pat.len();
    let rest = line[start..].trim_start();
    let end = rest
        .find(|c: char| !c.is_ascii_digit() && c != '.' && c != '-' && c != '+')
        .unwrap_or(rest.len());
    rest[..end].parse::<f64>().ok()
}

fn extract_bool(line: &str, key: &str) -> Option<bool> {
    let pat = format!("\"{}\":", key);
    let start = line.find(&pat)? + pat.len();
    let rest = line[start..].trim_start();
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

fn process_line(vinput: &Arc<Mutex<VirtualInput>>, line: &str) {
    let Some(t) = extract_str(line, "t") else { return };
    let Ok(mut dev) = vinput.lock() else { return };

    match t {
        "rel" => {
            let dx = extract_f64(line, "dx").unwrap_or(0.0);
            let dy = extract_f64(line, "dy").unwrap_or(0.0);
            dev.move_rel(dx, dy);
        }
        "abs" => {
            let x = extract_f64(line, "x").unwrap_or(0.0);
            let y = extract_f64(line, "y").unwrap_or(0.0);
            let d = extract_bool(line, "d").unwrap_or(false);
            dev.touch_abs(x, y, d);
        }
        "mt" => {
            let slot = extract_f64(line, "s").unwrap_or(0.0) as usize;
            let x = extract_f64(line, "x").unwrap_or(0.0);
            let y = extract_f64(line, "y").unwrap_or(0.0);
            let d = extract_bool(line, "d").unwrap_or(false);
            dev.touch_mt(slot, x, y, d);
        }
        "btn" => {
            let b = extract_str(line, "b").unwrap_or("left");
            let p = extract_bool(line, "p").unwrap_or(false);
            dev.mouse_button(b, p);
        }
        "click" => {
            let b = extract_str(line, "b").unwrap_or("left");
            dev.click(b);
        }
        "scroll" => {
            let dy = extract_f64(line, "dy").unwrap_or(0.0);
            let dx = extract_f64(line, "dx").unwrap_or(0.0);
            dev.scroll(dy, dx);
        }
        "shortcut" => {
            let a = extract_str(line, "a").unwrap_or("");
            dev.shortcut(a);
        }
        "text" => {
            let s = extract_str(line, "s").unwrap_or("");
            dev.type_text(s);
        }
        _ => {}
    }
}

struct DaemonState {
    enabled: AtomicBool,
    token: Mutex<String>,
    connected_peer: Mutex<String>,
}

fn token_file_path() -> PathBuf {
    let home = env::var("HOME").unwrap_or_else(|_| "/tmp".into());
    PathBuf::from(home).join(".phonetouch_token")
}

fn generate_random_token() -> String {
    let mut buf = [0u8; 8];
    if let Ok(mut f) = File::open("/dev/urandom") {
        let _ = f.read_exact(&mut buf);
    } else {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or(Duration::ZERO)
            .as_nanos();
        buf.copy_from_slice(&nanos.to_le_bytes()[..8]);
    }
    buf.iter().map(|b| format!("{:02x}", b)).collect()
}

fn load_or_create_token() -> String {
    let path = token_file_path();
    if let Ok(existing) = fs::read_to_string(&path) {
        let t = existing.trim().to_string();
        if t.len() >= 8 {
            return t;
        }
    }
    let new_token = generate_random_token();
    let _ = fs::write(&path, &new_token);
    new_token
}

fn get_primary_lan_ip() -> String {
    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
        if sock.connect("8.8.8.8:80").is_ok() {
            if let Ok(local) = sock.local_addr() {
                return local.ip().to_string();
            }
        }
    }
    "192.168.1.7".into()
}

fn build_pairing_uri(token: &str) -> String {
    let ip = get_primary_lan_ip();
    format!("phonetouch://{}:{}/{}", ip, TCP_PORT, token)
}

fn generate_qr_utf8(uri: &str) -> String {
    if let Ok(out) = Command::new("qrencode").args(["-t", "UTF8", uri]).output() {
        if out.status.success() {
            return String::from_utf8_lossy(&out.stdout).to_string();
        }
    }
    format!("(Install `qrencode` to render QR) URI: {uri}")
}

fn generate_qr_svg(uri: &str) -> String {
    if let Ok(out) = Command::new("qrencode")
        .args(["-t", "SVG", "-m", "1", uri])
        .output()
    {
        if out.status.success() {
            return String::from_utf8_lossy(&out.stdout).to_string();
        }
    }
    String::new()
}

fn handle_client(
    stream: TcpStream,
    vinput: Arc<Mutex<VirtualInput>>,
    state: Arc<DaemonState>,
) {
    let _ = stream.set_nodelay(true);
    let peer_addr = stream
        .peer_addr()
        .map(|a| a.to_string())
        .unwrap_or_else(|_| "unknown".into());
    let is_localhost_usb = peer_addr.starts_with("127.0.0.1:");
    let mut authenticated = is_localhost_usb;

    println!(
        "[+] PhoneTouch client connected from {peer_addr} (pre-auth={authenticated})"
    );
    if let Ok(mut p) = state.connected_peer.lock() {
        *p = peer_addr.clone();
    }

    let reader = BufReader::new(stream);
    for line in reader.lines() {
        let Ok(l) = line else { break };
        if l.is_empty() {
            continue;
        }

        // Check for authentication packet: {"t":"auth","k":"<token>"}
        if let Some("auth") = extract_str(&l, "t") {
            let provided = extract_str(&l, "k").unwrap_or("");
            let expected = state.token.lock().map(|g| g.clone()).unwrap_or_default();
            if !expected.is_empty() && provided == expected {
                authenticated = true;
                println!("[+] Client {peer_addr} authenticated via QR/Token!");
            } else if !is_localhost_usb {
                println!("[!] Client {peer_addr} sent invalid auth token");
            }
            continue;
        }

        // Also allow inline token `"k":"..."` on any packet
        if !authenticated {
            if let Some(provided) = extract_str(&l, "k") {
                let expected = state.token.lock().map(|g| g.clone()).unwrap_or_default();
                if !expected.is_empty() && provided == expected {
                    authenticated = true;
                    println!("[+] Client {peer_addr} authenticated inline!");
                }
            }
        }

        if !authenticated {
            continue;
        }

        // Server-side Master Turn-Off Switch check
        if !state.enabled.load(Ordering::Relaxed) {
            continue;
        }

        process_line(&vinput, &l);
    }
    println!("[-] PhoneTouch client disconnected: {peer_addr}");
    if let Ok(mut p) = state.connected_peer.lock() {
        if *p == peer_addr {
            *p = "None".into();
        }
    }
}

fn spawn_http_control_server(state: Arc<DaemonState>) {
    thread::spawn(move || {
        let addr = format!("127.0.0.1:{HTTP_CONTROL_PORT}");
        let Ok(listener) = TcpListener::bind(&addr) else {
            eprintln!("[!] Could not bind control server on {addr}");
            return;
        };
        println!("[+] Server Control & QR Auth Dashboard: http://{addr}");

        for stream in listener.incoming().flatten() {
            let mut reader = BufReader::new(&stream);
            let mut req_line = String::new();
            let _ = reader.read_line(&mut req_line);
            let parts: Vec<&str> = req_line.split_whitespace().collect();
            let path = parts.get(1).copied().unwrap_or("/");

            match path {
                "/on" | "/api/on" => {
                    state.enabled.store(true, Ordering::Relaxed);
                    println!("[*] Server Control: Input ENABLED (ON)");
                }
                "/off" | "/api/off" => {
                    state.enabled.store(false, Ordering::Relaxed);
                    println!("[*] Server Control: Input DISABLED (TURNED OFF)");
                }
                "/toggle" | "/api/toggle" => {
                    let prev = state.enabled.load(Ordering::Relaxed);
                    state.enabled.store(!prev, Ordering::Relaxed);
                    println!("[*] Server Control: Toggled to {}", !prev);
                }
                "/rotate" | "/api/rotate" => {
                    let new_tok = generate_random_token();
                    let _ = fs::write(token_file_path(), &new_tok);
                    if let Ok(mut t) = state.token.lock() {
                        *t = new_tok;
                    }
                    println!("[*] Server Control: Rotated QR Auth Token");
                }
                _ => {}
            }

            let is_on = state.enabled.load(Ordering::Relaxed);
            let tok = state.token.lock().map(|g| g.clone()).unwrap_or_default();
            let peer = state
                .connected_peer
                .lock()
                .map(|g| g.clone())
                .unwrap_or_else(|_| "None".into());
            let uri = build_pairing_uri(&tok);

            if path.starts_with("/api/") {
                let body = format!(
                    "{{\"enabled\":{},\"input_ok\":{},\"ip\":\"{}\",\"port\":{},\"token\":\"{}\",\"uri\":\"{}\",\"peer\":\"{}\"}}\n",
                    is_on,
                    input::input_permitted(),
                    get_primary_lan_ip(),
                    TCP_PORT,
                    tok,
                    uri,
                    peer
                );
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = (&stream).write_all(resp.as_bytes());
                continue;
            }

            if path == "/qr.txt" {
                let qr_txt = format!(
                    "\nPhoneTouch QR Pairing ({}):\n{}\nURI: {}\nToken: {}\n",
                    if is_on { "ENABLED" } else { "DISABLED / OFF" },
                    generate_qr_utf8(&uri),
                    uri,
                    tok
                );
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    qr_txt.len(),
                    qr_txt
                );
                let _ = (&stream).write_all(resp.as_bytes());
                continue;
            }

            let qr_svg = generate_qr_svg(&uri);
            if path == "/qr.svg" {
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: image/svg+xml; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    qr_svg.len(),
                    qr_svg
                );
                let _ = (&stream).write_all(resp.as_bytes());
                continue;
            }
            let status_badge = if is_on {
                "<span style='color:#22c55e;font-weight:bold;'>● ACTIVE (RECEIVING INPUT)</span>"
            } else {
                "<span style='color:#ef4444;font-weight:bold;'>🔒 TURNED OFF (INPUT BLOCKED)</span>"
            };

            let html = format!(
                r#"<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>PhoneTouch Server Control & QR Auth</title>
<style>
  body {{ background:#0f172a; color:#f8fafc; font-family:system-ui,sans-serif; display:flex; justify-content:center; padding:32px; margin:0; }}
  .card {{ background:#1e293b; border:1px solid #334155; border-radius:16px; padding:28px; max-width:520px; width:100%; text-align:center; box-shadow:0 10px 30px rgba(0,0,0,0.4); }}
  h2 {{ margin-top:0; color:#38bdf8; }}
  .qr-box {{ background:white; padding:16px; border-radius:12px; display:inline-block; margin:16px 0; width:240px; height:240px; }}
  .qr-box svg {{ width:100%; height:100%; }}
  .code {{ background:#0f172a; padding:10px 14px; border-radius:8px; font-family:monospace; color:#38bdf8; font-size:14px; word-break:break-all; margin:10px 0; }}
  .btns {{ display:flex; gap:10px; justify-content:center; margin-top:20px; flex-wrap:wrap; }}
  a.btn {{ text-decoration:none; padding:12px 20px; border-radius:10px; font-weight:bold; color:white; font-size:14px; }}
  .btn-on {{ background:#16a34a; }}
  .btn-off {{ background:#dc2626; }}
  .btn-rot {{ background:#475569; }}
</style>
</head>
<body>
  <div class="card">
    <h2>PhoneTouch Desktop Server</h2>
    <p>Status: {status_badge}</p>
    <p style="color:#94a3b8;font-size:13px;">Connected Phone: <b>{peer}</b></p>
    <div class="qr-box">{qr_svg}</div>
    <p style="margin:4px 0;color:#94a3b8;font-size:13px;">Scan QR with Phone or enter Pairing URI / Token in App:</p>
    <div class="code">{uri}</div>
    <div class="code">Auth Token: <b>{tok}</b></div>
    <div class="btns">
      <a class="btn btn-on" href="/on">⚡ TURN SERVER ON</a>
      <a class="btn btn-off" href="/off">🔒 TURN SERVER OFF</a>
      <a class="btn btn-rot" href="/rotate">🔄 NEW QR TOKEN</a>
    </div>
  </div>
</body>
</html>"#
            );

            let resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                html.len(),
                html
            );
            let _ = (&stream).write_all(resp.as_bytes());
        }
    });
}

fn spawn_udp_discovery_responder(
    vinput: Arc<Mutex<VirtualInput>>,
    state: Arc<DaemonState>,
) {
    thread::spawn(move || {
        let Ok(sock) = UdpSocket::bind(format!("0.0.0.0:{UDP_DISCOVERY_PORT}")) else {
            return;
        };
        let mut buf = [0u8; 256];
        while let Ok((len, src)) = sock.recv_from(&mut buf) {
            // Fast 16-byte Authenticated Binary UDP Packet for Motion & Scroll:
            // [0..2]: Magic 0x50, 0x54 ("PT")
            // [2]:    Opcode: 1 = rel (dx, dy), 2 = scroll (dy, dx), 3 = mt (slot, x, y, down)
            // [3]:    Slot / Flags
            // [4..8]: Token FNV-1a 32-bit signature XOR-masked with payload
            // [8..12]: f32 val1 (little-endian)
            // [12..16]: f32 val2 (little-endian)
            if len == 16 && buf[0] == 0x50 && buf[1] == 0x54 {
                if !state.enabled.load(Ordering::Relaxed) {
                    continue;
                }
                let tok = state.token.lock().map(|g| g.clone()).unwrap_or_default();
                let mut hash: u32 = 0x811c9dc5;
                for b in tok.as_bytes() {
                    hash ^= *b as u32;
                    hash = hash.wrapping_mul(0x01000193);
                }
                let pkt_sig = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
                if pkt_sig != hash {
                    continue;
                }
                let mask = hash.to_le_bytes();
                let b1 = [
                    buf[8] ^ mask[0],
                    buf[9] ^ mask[1],
                    buf[10] ^ mask[2],
                    buf[11] ^ mask[3],
                ];
                let b2 = [
                    buf[12] ^ mask[0],
                    buf[13] ^ mask[1],
                    buf[14] ^ mask[2],
                    buf[15] ^ mask[3],
                ];
                let v1 = f32::from_le_bytes(b1) as f64;
                let v2 = f32::from_le_bytes(b2) as f64;

                if let Ok(mut dev) = vinput.lock() {
                    match buf[2] {
                        1 => dev.move_rel(v1, v2),
                        2 => dev.scroll(v1, v2),
                        3 => {
                            let slot = (buf[3] & 0x0F) as usize;
                            let down = (buf[3] & 0x80) != 0;
                            dev.touch_mt(slot, v1, v2, down);
                        }
                        _ => {}
                    }
                }
                continue;
            }

            if let Ok(msg) = std::str::from_utf8(&buf[..len]) {
                if msg.trim() == "PHONETOUCH_DISCOVER" {
                    let _ = sock.send_to(b"PHONETOUCH_HERE:9999", src);
                }
            }
        }
    });
}

fn spawn_adb_watchdog() {
    thread::spawn(move || {
        let adb_path = ADB_BINS
            .iter()
            .copied()
            .find(|p| std::path::Path::new(p).exists())
            .unwrap_or("adb");
        loop {
            let _ = Command::new(adb_path)
                .args(["reverse", &format!("tcp:{TCP_PORT}"), &format!("tcp:{TCP_PORT}")])
                .output();
            thread::sleep(Duration::from_secs(5));
        }
    });
}

fn send_control_request(endpoint: &str) {
    let addr = format!("127.0.0.1:{HTTP_CONTROL_PORT}");
    if let Ok(mut s) = TcpStream::connect(&addr) {
        let req = format!(
            "GET {endpoint} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        );
        let _ = s.write_all(req.as_bytes());
        let mut resp = String::new();
        let _ = s.read_to_string(&mut resp);
        if let Some(body) = resp.split("\r\n\r\n").nth(1) {
            println!("{body}");
        }
    } else {
        eprintln!("[!] PhoneTouch daemon is not running on {addr}");
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() > 1 {
        match args[1].as_str() {
            "on" => {
                send_control_request("/api/on");
                return;
            }
            "off" => {
                send_control_request("/api/off");
                return;
            }
            "toggle" => {
                send_control_request("/api/toggle");
                return;
            }
            "status" => {
                send_control_request("/api/status");
                return;
            }
            "rotate" => {
                send_control_request("/api/rotate");
                return;
            }
            "qr" => {
                send_control_request("/qr.txt");
                return;
            }
            _ => {
                println!("Usage: phonetouch-daemon [on|off|toggle|status|qr|rotate]");
                return;
            }
        }
    }

    let token = load_or_create_token();
    let uri = build_pairing_uri(&token);
    let state = Arc::new(DaemonState {
        enabled: AtomicBool::new(true),
        token: Mutex::new(token.clone()),
        connected_peer: Mutex::new("None".into()),
    });

    let vinput = match VirtualInput::new() {
        Ok(v) => Arc::new(Mutex::new(v)),
        Err(e) => {
            eprintln!("[!] Failed to initialize virtual input backend: {e}");
            std::process::exit(1);
        }
    };

    println!("[+] Virtual input backend: {}", input::BACKEND_NAME);
    println!("[+] Authenticated AirMouse QR Pairing URI: {uri}");
    println!("{}", generate_qr_utf8(&uri));

    spawn_http_control_server(Arc::clone(&state));
    spawn_udp_discovery_responder(Arc::clone(&vinput), Arc::clone(&state));
    spawn_adb_watchdog();

    let addr = format!("0.0.0.0:{TCP_PORT}");
    let listener = TcpListener::bind(&addr).unwrap_or_else(|e| {
        eprintln!("[!] Failed to bind TCP {addr}: {e}");
        std::process::exit(1);
    });

    println!("[+] PhoneTouch Rust Daemon listening on {addr} (Control UI: http://127.0.0.1:{HTTP_CONTROL_PORT})");

    for incoming in listener.incoming() {
        match incoming {
            Ok(stream) => {
                let vclone = Arc::clone(&vinput);
                let sclone = Arc::clone(&state);
                thread::spawn(move || handle_client(stream, vclone, sclone));
            }
            Err(e) => eprintln!("[!] Accept error: {e}"),
        }
    }
}
