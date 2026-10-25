// PhoneTouch macOS menu bar control
// Talks to the local daemon control API (http://127.0.0.1:9997) to provide:
//   - Live menu bar icon (Connected / Active / Turned Off / Daemon down / Needs permission)
//   - Turn input ON / OFF, inline pairing QR code, copy pairing URI, rotate token
//   - Start / restart the launchd daemon, open dashboard & Accessibility settings
// Build: see build_menubar.sh

import AppKit
import CoreImage

let apiBase = "http://127.0.0.1:9997"
let daemonLabel = "com.phonetouch.daemon"

struct DaemonStatus {
    var enabled: Bool
    var inputOK: Bool
    var ip: String
    var port: Int
    var token: String
    var uri: String
    var peer: String

    var connected: Bool { !peer.isEmpty && peer != "None" }

    init?(json: Data) {
        guard let o = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else { return nil }
        enabled = o["enabled"] as? Bool ?? false
        inputOK = o["input_ok"] as? Bool ?? true
        ip = o["ip"] as? String ?? "?"
        port = o["port"] as? Int ?? 9999
        token = o["token"] as? String ?? ""
        uri = o["uri"] as? String ?? ""
        peer = o["peer"] as? String ?? "None"
    }
}

final class MenuBarController: NSObject, NSMenuDelegate {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private var status: DaemonStatus?
    private var timer: Timer?

    override init() {
        super.init()
        menu.delegate = self
        item.menu = menu
        updateIcon()
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.refresh()
        }
    }

    // MARK: Daemon API

    private func call(_ path: String, then: ((DaemonStatus?) -> Void)? = nil) {
        guard let url = URL(string: apiBase + path) else { return }
        var req = URLRequest(url: url)
        req.timeoutInterval = 1.5
        URLSession.shared.dataTask(with: req) { data, _, _ in
            let st = data.flatMap { DaemonStatus(json: $0) }
            DispatchQueue.main.async {
                self.status = st
                self.updateIcon()
                then?(st)
            }
        }.resume()
    }

    private func refresh() { call("/api/status") }

    private func launchctl(_ args: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        p.arguments = args
        try? p.run()
        p.waitUntilExit()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.refresh() }
    }

    // MARK: Icon

    private func updateIcon() {
        let (symbol, tint, tip): (String, NSColor?, String) = {
            guard let s = status else { return ("iphone.slash", .systemGray, "PhoneTouch: daemon not running") }
            if !s.inputOK { return ("exclamationmark.triangle", .systemOrange, "PhoneTouch: needs Accessibility permission") }
            if !s.enabled { return ("iphone.slash", .systemRed, "PhoneTouch: input turned off") }
            if s.connected { return ("iphone.radiowaves.left.and.right", nil, "PhoneTouch: phone connected") }
            return ("iphone", nil, "PhoneTouch: waiting for phone")
        }()
        guard let button = item.button else { return }
        let img = NSImage(systemSymbolName: symbol, accessibilityDescription: tip)
        if let tint {
            let cfg = NSImage.SymbolConfiguration(paletteColors: [tint])
            button.image = img?.withSymbolConfiguration(cfg)
            button.image?.isTemplate = false
        } else {
            button.image = img
            button.image?.isTemplate = true
        }
        button.toolTip = tip
    }

    // MARK: Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()

        guard let s = status else {
            addDisabled("PhoneTouch — daemon not running")
            menu.addItem(.separator())
            add("Start Daemon", #selector(startDaemon))
            addFooter()
            return
        }

        addDisabled(s.enabled ? "PhoneTouch — ● Active" : "PhoneTouch — 🔒 Turned Off")
        addDisabled(s.connected ? "Phone: \(s.peer)" : "Phone: not connected")
        addDisabled("Listening on \(s.ip):\(s.port)")

        if !s.inputOK {
            menu.addItem(.separator())
            add("⚠️ Grant Accessibility Permission…", #selector(openAccessibility))
            addDisabled("   then choose Restart Daemon")
        }

        menu.addItem(.separator())
        let toggle = add(s.enabled ? "Turn Input Off" : "Turn Input On", #selector(toggleInput), key: "t")
        toggle.state = s.enabled ? .on : .off

        menu.addItem(.separator())
        if let qr = qrImage(s.uri, size: 180) {
            let qrItem = NSMenuItem()
            let v = NSView(frame: NSRect(x: 0, y: 0, width: 220, height: 200))
            let iv = NSImageView(frame: NSRect(x: 20, y: 10, width: 180, height: 180))
            iv.image = qr
            iv.wantsLayer = true
            iv.layer?.backgroundColor = NSColor.white.cgColor
            iv.layer?.cornerRadius = 8
            v.addSubview(iv)
            qrItem.view = v
            menu.addItem(qrItem)
        }
        addDisabled("Scan with phone camera to pair")
        add("Copy Pairing URI", #selector(copyURI), key: "c")
        add("New QR Token", #selector(rotateToken))

        menu.addItem(.separator())
        add("Open Dashboard", #selector(openDashboard), key: "d")
        add("Restart Daemon", #selector(restartDaemon), key: "r")
        add("Stop Daemon", #selector(stopDaemon))
        addFooter()
    }

    @discardableResult
    private func add(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let mi = NSMenuItem(title: title, action: action, keyEquivalent: key)
        mi.target = self
        menu.addItem(mi)
        return mi
    }

    private func addDisabled(_ title: String) {
        let mi = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        mi.isEnabled = false
        menu.addItem(mi)
    }

    private func addFooter() {
        menu.addItem(.separator())
        add("View Log", #selector(openLog))
        add("Quit Menu Bar Control", #selector(quit), key: "q")
    }

    private func qrImage(_ text: String, size: CGFloat) -> NSImage? {
        guard !text.isEmpty, let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(text.utf8), forKey: "inputMessage")
        f.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f.outputImage else { return nil }
        let scale = floor(size / out.extent.width)
        let scaled = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }

    // MARK: Actions

    @objc private func toggleInput() { call("/api/toggle") }
    @objc private func rotateToken() { call("/api/rotate") }

    @objc private func copyURI() {
        guard let uri = status?.uri else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(uri, forType: .string)
    }

    @objc private func openDashboard() { NSWorkspace.shared.open(URL(string: apiBase)!) }

    @objc private func openAccessibility() {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    @objc private func openLog() { NSWorkspace.shared.open(URL(fileURLWithPath: "/tmp/phonetouch.log")) }

    @objc private func startDaemon() {
        let uid = getuid()
        let plist = NSHomeDirectory() + "/Library/LaunchAgents/\(daemonLabel).plist"
        launchctl(["bootstrap", "gui/\(uid)", plist])
        launchctl(["kickstart", "gui/\(uid)/\(daemonLabel)"])
    }

    @objc private func restartDaemon() { launchctl(["kickstart", "-k", "gui/\(getuid())/\(daemonLabel)"]) }

    @objc private func stopDaemon() {
        launchctl(["bootout", "gui/\(getuid())/\(daemonLabel)"])
    }

    @objc private func quit() { NSApp.terminate(nil) }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let controller = MenuBarController()
app.run()
