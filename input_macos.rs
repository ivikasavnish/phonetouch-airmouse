// macOS backend: synthetic Quartz (CoreGraphics) events posted at the HID event tap.
// - Relative motion, buttons (with double-click state), drags and pixel-precise scrolling
// - Unicode text typing via CGEventKeyboardSetUnicodeString (no keymap needed)
// - Media / volume keys via NSEvent NSSystemDefined (AppKit, through the ObjC runtime)
// - 1:1 touchscreen mode maps slot 0 to an absolute left-button pointer (macOS has no touch injection)
// Requires: System Settings → Privacy & Security → Accessibility → allow the daemon binary.

use std::ffi::{c_char, c_void};
use std::thread;
use std::time::{Duration, Instant};

pub const BACKEND_NAME: &str = "macOS CoreGraphics (Quartz HID event tap)";

#[repr(C)]
#[derive(Copy, Clone)]
struct CGPoint {
    x: f64,
    y: f64,
}

#[repr(C)]
#[derive(Copy, Clone)]
struct CGSize {
    width: f64,
    height: f64,
}

#[repr(C)]
#[derive(Copy, Clone)]
struct CGRect {
    origin: CGPoint,
    size: CGSize,
}

impl CGRect {
    fn contains(&self, p: CGPoint) -> bool {
        p.x >= self.origin.x
            && p.y >= self.origin.y
            && p.x < self.origin.x + self.size.width
            && p.y < self.origin.y + self.size.height
    }

    fn clamp(&self, p: CGPoint) -> CGPoint {
        CGPoint {
            x: p.x.clamp(self.origin.x, self.origin.x + self.size.width - 1.0),
            y: p.y.clamp(self.origin.y, self.origin.y + self.size.height - 1.0),
        }
    }
}

type CGEventRef = *mut c_void;
type CGEventSourceRef = *mut c_void;

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGEventSourceCreate(state_id: i32) -> CGEventSourceRef;
    fn CGEventCreate(source: CGEventSourceRef) -> CGEventRef;
    fn CGEventGetLocation(event: CGEventRef) -> CGPoint;
    fn CGEventCreateMouseEvent(
        source: CGEventSourceRef,
        mouse_type: u32,
        cursor: CGPoint,
        button: u32,
    ) -> CGEventRef;
    fn CGEventCreateKeyboardEvent(source: CGEventSourceRef, keycode: u16, down: bool) -> CGEventRef;
    fn CGEventCreateScrollWheelEvent2(
        source: CGEventSourceRef,
        units: u32,
        wheel_count: u32,
        wheel1: i32,
        wheel2: i32,
        wheel3: i32,
    ) -> CGEventRef;
    fn CGEventSetFlags(event: CGEventRef, flags: u64);
    fn CGEventSetIntegerValueField(event: CGEventRef, field: u32, value: i64);
    fn CGEventKeyboardSetUnicodeString(event: CGEventRef, len: usize, chars: *const u16);
    fn CGEventPost(tap: u32, event: CGEventRef);
    fn CGGetActiveDisplayList(max: u32, displays: *mut u32, count: *mut u32) -> i32;
    fn CGDisplayBounds(display: u32) -> CGRect;
}

#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFRelease(cf: *const c_void);
    fn CFDictionaryCreate(
        allocator: *const c_void,
        keys: *const *const c_void,
        values: *const *const c_void,
        count: isize,
        key_callbacks: *const c_void,
        value_callbacks: *const c_void,
    ) -> *const c_void;
    static kCFBooleanTrue: *const c_void;
    static kCFTypeDictionaryKeyCallBacks: u8;
    static kCFTypeDictionaryValueCallBacks: u8;
}

#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    fn AXIsProcessTrusted() -> bool;
    fn AXIsProcessTrustedWithOptions(options: *const c_void) -> bool;
    static kAXTrustedCheckOptionPrompt: *const c_void;
}

#[link(name = "AppKit", kind = "framework")]
unsafe extern "C" {}

#[link(name = "objc")]
unsafe extern "C" {
    fn objc_getClass(name: *const c_char) -> *mut c_void;
    fn sel_registerName(name: *const c_char) -> *mut c_void;
    fn objc_msgSend();
    fn objc_autoreleasePoolPush() -> *mut c_void;
    fn objc_autoreleasePoolPop(pool: *mut c_void);
}

// Whether macOS will deliver our synthetic events (Accessibility permission)
pub fn input_permitted() -> bool {
    unsafe { AXIsProcessTrusted() }
}

// Ask macOS to show its own Accessibility prompt; this registers the entry for the
// exact running binary (a manually added entry goes stale whenever the binary is rebuilt)
fn request_permission() -> bool {
    unsafe {
        let keys = [kAXTrustedCheckOptionPrompt];
        let values = [kCFBooleanTrue];
        let opts = CFDictionaryCreate(
            std::ptr::null(),
            keys.as_ptr(),
            values.as_ptr(),
            1,
            &kCFTypeDictionaryKeyCallBacks as *const u8 as *const c_void,
            &kCFTypeDictionaryValueCallBacks as *const u8 as *const c_void,
        );
        let trusted = AXIsProcessTrustedWithOptions(opts);
        if !opts.is_null() {
            CFRelease(opts);
        }
        trusted
    }
}

const HID_SYSTEM_STATE: i32 = 1;
const HID_EVENT_TAP: u32 = 0;

const EV_LEFT_DOWN: u32 = 1;
const EV_LEFT_UP: u32 = 2;
const EV_RIGHT_DOWN: u32 = 3;
const EV_RIGHT_UP: u32 = 4;
const EV_MOUSE_MOVED: u32 = 5;
const EV_LEFT_DRAGGED: u32 = 6;
const EV_RIGHT_DRAGGED: u32 = 7;
const EV_OTHER_DOWN: u32 = 25;
const EV_OTHER_UP: u32 = 26;
const EV_OTHER_DRAGGED: u32 = 27;

const BTN_LEFT: usize = 0;
const BTN_RIGHT: usize = 1;
const BTN_MIDDLE: usize = 2;

const FIELD_CLICK_STATE: u32 = 1;
const FIELD_DELTA_X: u32 = 4;
const FIELD_DELTA_Y: u32 = 5;
const SCROLL_UNIT_PIXEL: u32 = 0;
const SCROLL_PX_PER_NOTCH: f64 = 32.0;
const DOUBLE_CLICK_SECS: f64 = 0.4;

const FLAG_SHIFT: u64 = 0x0002_0000;
const FLAG_CONTROL: u64 = 0x0004_0000;
const FLAG_COMMAND: u64 = 0x0010_0000;

// Carbon virtual keycodes (kVK_*)
const VK_RETURN: u16 = 36;
const VK_TAB: u16 = 48;
const VK_SPACE: u16 = 49;
const VK_DELETE: u16 = 51;
const VK_ESCAPE: u16 = 53;
const VK_COMMAND: u16 = 55;
const VK_SHIFT: u16 = 56;
const VK_CONTROL: u16 = 59;
const VK_EQUAL: u16 = 24;
const VK_MINUS: u16 = 27;
const VK_0: u16 = 29;
const VK_UP: u16 = 126;
const VK_L: u16 = 37;
const VK_C: u16 = 8;

// NX_KEYTYPE_* media keys (IOKit/hidsystem/ev_keymap.h)
const NX_SOUND_UP: i64 = 0;
const NX_SOUND_DOWN: i64 = 1;
const NX_MUTE: i64 = 7;
const NX_PLAY: i64 = 16;
const NX_NEXT: i64 = 17;
const NX_PREVIOUS: i64 = 18;

pub struct VirtualInput {
    source: CGEventSourceRef,
    rem_x: f64,
    rem_y: f64,
    rem_vscroll: f64,
    rem_hscroll: f64,
    buttons_down: [bool; 3],
    last_click: Option<(usize, Instant, CGPoint)>,
    click_count: i64,
    touch_down: bool,
    monitor_region: usize, // 0 = Full Desktop, 1 = Left Half, 2 = Right Half
}

// The event source is only touched while holding the daemon's Mutex.
unsafe impl Send for VirtualInput {}

impl VirtualInput {
    pub fn new() -> Result<Self, String> {
        if !request_permission() {
            eprintln!(
                "[!] Accessibility permission missing: input events will be ignored by macOS.\n    \
                 Grant it in System Settings → Privacy & Security → Accessibility for:\n    {}",
                std::env::current_exe()
                    .map(|p| p.display().to_string())
                    .unwrap_or_else(|_| "phonetouch-daemon".into())
            );
        }
        let source = unsafe { CGEventSourceCreate(HID_SYSTEM_STATE) };
        if source.is_null() {
            return Err("CGEventSourceCreate failed".into());
        }
        Ok(Self {
            source,
            rem_x: 0.0,
            rem_y: 0.0,
            rem_vscroll: 0.0,
            rem_hscroll: 0.0,
            buttons_down: [false; 3],
            last_click: None,
            click_count: 1,
            touch_down: false,
            monitor_region: 0,
        })
    }

    fn post(event: CGEventRef) {
        if event.is_null() {
            return;
        }
        unsafe {
            CGEventPost(HID_EVENT_TAP, event);
            CFRelease(event);
        }
    }

    fn cursor_pos(&self) -> CGPoint {
        unsafe {
            let ev = CGEventCreate(self.source);
            if ev.is_null() {
                return CGPoint { x: 0.0, y: 0.0 };
            }
            let p = CGEventGetLocation(ev);
            CFRelease(ev);
            p
        }
    }

    fn display_bounds() -> Vec<CGRect> {
        let mut ids = [0u32; 16];
        let mut count = 0u32;
        unsafe {
            if CGGetActiveDisplayList(ids.len() as u32, ids.as_mut_ptr(), &mut count) != 0 {
                return Vec::new();
            }
            ids[..count as usize]
                .iter()
                .map(|&id| CGDisplayBounds(id))
                .collect()
        }
    }

    fn desktop_bounds() -> CGRect {
        let displays = Self::display_bounds();
        let Some(first) = displays.first().copied() else {
            return CGRect {
                origin: CGPoint { x: 0.0, y: 0.0 },
                size: CGSize { width: 1920.0, height: 1080.0 },
            };
        };
        let (mut x0, mut y0) = (first.origin.x, first.origin.y);
        let (mut x1, mut y1) = (x0 + first.size.width, y0 + first.size.height);
        for d in &displays[1..] {
            x0 = x0.min(d.origin.x);
            y0 = y0.min(d.origin.y);
            x1 = x1.max(d.origin.x + d.size.width);
            y1 = y1.max(d.origin.y + d.size.height);
        }
        CGRect {
            origin: CGPoint { x: x0, y: y0 },
            size: CGSize { width: x1 - x0, height: y1 - y0 },
        }
    }

    // Keep the pointer on a real display (multi-monitor layouts are rarely rectangular)
    fn constrain(from: CGPoint, to: CGPoint) -> CGPoint {
        let displays = Self::display_bounds();
        if displays.is_empty() || displays.iter().any(|d| d.contains(to)) {
            return to;
        }
        let home = displays
            .iter()
            .find(|d| d.contains(from))
            .unwrap_or(&displays[0]);
        home.clamp(to)
    }

    fn drag_type(&self) -> (u32, u32) {
        if self.buttons_down[BTN_LEFT] {
            (EV_LEFT_DRAGGED, 0)
        } else if self.buttons_down[BTN_RIGHT] {
            (EV_RIGHT_DRAGGED, 1)
        } else if self.buttons_down[BTN_MIDDLE] {
            (EV_OTHER_DRAGGED, 2)
        } else {
            (EV_MOUSE_MOVED, 0)
        }
    }

    pub fn move_rel(&mut self, dx: f64, dy: f64) {
        self.rem_x += dx;
        self.rem_y += dy;
        let ix = self.rem_x.trunc();
        let iy = self.rem_y.trunc();
        if ix == 0.0 && iy == 0.0 {
            return;
        }
        self.rem_x -= ix;
        self.rem_y -= iy;

        let cur = self.cursor_pos();
        let target = Self::constrain(cur, CGPoint { x: cur.x + ix, y: cur.y + iy });
        let (ev_type, button) = self.drag_type();
        unsafe {
            let ev = CGEventCreateMouseEvent(self.source, ev_type, target, button);
            if !ev.is_null() {
                CGEventSetIntegerValueField(ev, FIELD_DELTA_X, ix as i64);
                CGEventSetIntegerValueField(ev, FIELD_DELTA_Y, iy as i64);
            }
            Self::post(ev);
        }
    }

    pub fn scroll(&mut self, dy: f64, dx: f64) {
        // Linux REL_WHEEL > 0 = up (same as Quartz wheel1); REL_HWHEEL > 0 = right (Quartz wheel2 > 0 = left)
        self.rem_vscroll += dy * SCROLL_PX_PER_NOTCH;
        self.rem_hscroll -= dx * SCROLL_PX_PER_NOTCH;
        let py = self.rem_vscroll.trunc();
        let px = self.rem_hscroll.trunc();
        if py == 0.0 && px == 0.0 {
            return;
        }
        self.rem_vscroll -= py;
        self.rem_hscroll -= px;
        let ev = unsafe {
            CGEventCreateScrollWheelEvent2(self.source, SCROLL_UNIT_PIXEL, 2, py as i32, px as i32, 0)
        };
        Self::post(ev);
    }

    fn key(&self, code: u16, down: bool, flags: u64) {
        unsafe {
            let ev = CGEventCreateKeyboardEvent(self.source, code, down);
            if !ev.is_null() {
                CGEventSetFlags(ev, flags);
            }
            Self::post(ev);
        }
    }

    // Press modifiers (in order), tap `code`, release modifiers (reverse order)
    fn chord(&self, mods: &[(u16, u64)], code: u16, hold_ms: u64) {
        let mut flags = 0u64;
        for &(m, f) in mods {
            flags |= f;
            self.key(m, true, flags);
        }
        self.key(code, true, flags);
        thread::sleep(Duration::from_millis(hold_ms));
        self.key(code, false, flags);
        for &(m, f) in mods.iter().rev() {
            flags &= !f;
            self.key(m, false, flags);
        }
    }

    fn media_key(&self, nx_key: i64) {
        unsafe {
            let pool = objc_autoreleasePoolPush();
            let cls = objc_getClass(b"NSEvent\0".as_ptr() as *const c_char);
            let sel_other = sel_registerName(
                b"otherEventWithType:location:modifierFlags:timestamp:windowNumber:context:subtype:data1:data2:\0".as_ptr() as *const c_char,
            );
            let sel_cg = sel_registerName(b"CGEvent\0".as_ptr() as *const c_char);
            let other_event: extern "C" fn(
                *mut c_void, *mut c_void, u64, CGPoint, u64, f64, i64, *mut c_void, i16, i64, i64,
            ) -> *mut c_void = std::mem::transmute(objc_msgSend as unsafe extern "C" fn());
            let to_cg: extern "C" fn(*mut c_void, *mut c_void) -> CGEventRef =
                std::mem::transmute(objc_msgSend as unsafe extern "C" fn());

            for down in [true, false] {
                let state: i64 = if down { 0xa } else { 0xb };
                let ns_event = other_event(
                    cls,
                    sel_other,
                    14, // NSEventTypeSystemDefined
                    CGPoint { x: 0.0, y: 0.0 },
                    (state as u64) << 8,
                    0.0,
                    0,
                    std::ptr::null_mut(),
                    8, // NX_SUBTYPE_AUX_CONTROL_BUTTONS
                    (nx_key << 16) | (state << 8),
                    -1,
                );
                if !ns_event.is_null() {
                    let cg = to_cg(ns_event, sel_cg);
                    if !cg.is_null() {
                        CGEventPost(HID_EVENT_TAP, cg); // owned by the autoreleased NSEvent
                    }
                }
            }
            objc_autoreleasePoolPop(pool);
        }
    }

    pub fn shortcut(&mut self, action: &str) {
        match action {
            // Ctrl + Tab / Ctrl + Shift + Tab (browser & editor tabs; same on macOS)
            "tab_next" => self.chord(&[(VK_CONTROL, FLAG_CONTROL)], VK_TAB, 12),
            "tab_prev" => self.chord(
                &[(VK_CONTROL, FLAG_CONTROL), (VK_SHIFT, FLAG_SHIFT)],
                VK_TAB,
                12,
            ),
            // Cmd + Tab / Cmd + Shift + Tab (app switcher)
            "app_next" => self.chord(&[(VK_COMMAND, FLAG_COMMAND)], VK_TAB, 15),
            "app_prev" => self.chord(
                &[(VK_COMMAND, FLAG_COMMAND), (VK_SHIFT, FLAG_SHIFT)],
                VK_TAB,
                15,
            ),
            // Ctrl + Up = Mission Control
            "overview" => self.chord(&[(VK_CONTROL, FLAG_CONTROL)], VK_UP, 20),
            "zoom_in" => self.chord(&[(VK_COMMAND, FLAG_COMMAND)], VK_EQUAL, 12),
            "zoom_out" => self.chord(&[(VK_COMMAND, FLAG_COMMAND)], VK_MINUS, 12),
            "zoom_reset" => self.chord(&[(VK_COMMAND, FLAG_COMMAND)], VK_0, 12),
            // Cmd + L, Cmd + C, Esc: copy the focused browser's address bar to the clipboard
            "copy_url" => {
                self.chord(&[(VK_COMMAND, FLAG_COMMAND)], VK_L, 12);
                thread::sleep(Duration::from_millis(40));
                self.chord(&[(VK_COMMAND, FLAG_COMMAND)], VK_C, 12);
                thread::sleep(Duration::from_millis(40));
                self.chord(&[], VK_ESCAPE, 8);
            }
            "vol_up" => self.media_key(NX_SOUND_UP),
            "vol_down" => self.media_key(NX_SOUND_DOWN),
            "vol_mute" => self.media_key(NX_MUTE),
            "media_play" => self.media_key(NX_PLAY),
            "media_next" => self.media_key(NX_NEXT),
            "media_prev" => self.media_key(NX_PREVIOUS),
            "laser_ring" => {
                // Rapid wiggle to trigger macOS "Shake mouse pointer to locate"
                for i in 0..12 {
                    let d = if i % 2 == 0 { 60.0 } else { -60.0 };
                    self.move_rel(d, 0.0);
                    thread::sleep(Duration::from_millis(10));
                }
            }
            "mon_cycle" => {
                self.monitor_region = (self.monitor_region + 1) % 3;
                println!("[*] 1:1 Touchscreen Monitor Region set to {}", self.monitor_region);
            }
            _ => {}
        }
    }

    fn type_char(&self, ch: char) {
        match ch {
            '\n' => return self.chord(&[], VK_RETURN, 8),
            '\t' => return self.chord(&[], VK_TAB, 8),
            _ => {}
        }
        let mut buf = [0u16; 2];
        let units = ch.encode_utf16(&mut buf);
        for down in [true, false] {
            unsafe {
                let ev = CGEventCreateKeyboardEvent(self.source, 0, down);
                if !ev.is_null() {
                    CGEventSetFlags(ev, 0);
                    CGEventKeyboardSetUnicodeString(ev, units.len(), units.as_ptr());
                }
                Self::post(ev);
            }
        }
        thread::sleep(Duration::from_millis(4));
    }

    pub fn type_text(&mut self, text: &str) {
        for ch in text.chars() {
            self.type_char(ch);
        }
    }

    fn post_button(&mut self, idx: usize, pressed: bool, at: CGPoint) {
        if pressed {
            let now = Instant::now();
            self.click_count = match self.last_click {
                Some((b, t, p))
                    if b == idx
                        && now.duration_since(t).as_secs_f64() < DOUBLE_CLICK_SECS
                        && (p.x - at.x).abs() < 5.0
                        && (p.y - at.y).abs() < 5.0 =>
                {
                    self.click_count + 1
                }
                _ => 1,
            };
            self.last_click = Some((idx, now, at));
        }
        self.buttons_down[idx] = pressed;
        let (ev_type, cg_button) = match (idx, pressed) {
            (BTN_RIGHT, true) => (EV_RIGHT_DOWN, 1),
            (BTN_RIGHT, false) => (EV_RIGHT_UP, 1),
            (BTN_MIDDLE, true) => (EV_OTHER_DOWN, 2),
            (BTN_MIDDLE, false) => (EV_OTHER_UP, 2),
            (_, true) => (EV_LEFT_DOWN, 0),
            (_, false) => (EV_LEFT_UP, 0),
        };
        unsafe {
            let ev = CGEventCreateMouseEvent(self.source, ev_type, at, cg_button);
            if !ev.is_null() {
                CGEventSetIntegerValueField(ev, FIELD_CLICK_STATE, self.click_count);
            }
            Self::post(ev);
        }
    }

    pub fn mouse_button(&mut self, btn: &str, pressed: bool) {
        let idx = match btn {
            "right" => BTN_RIGHT,
            "middle" => BTN_MIDDLE,
            "esc" => return self.key(VK_ESCAPE, pressed, 0),
            "enter" => return self.key(VK_RETURN, pressed, 0),
            "backspace" => return self.key(VK_DELETE, pressed, 0),
            "space" => return self.key(VK_SPACE, pressed, 0),
            _ => BTN_LEFT,
        };
        let at = self.cursor_pos();
        self.post_button(idx, pressed, at);
    }

    pub fn click(&mut self, btn: &str) {
        self.mouse_button(btn, true);
        thread::sleep(Duration::from_millis(12));
        self.mouse_button(btn, false);
    }

    pub fn touch_abs(&mut self, norm_x: f64, norm_y: f64, down: bool) {
        self.touch_mt(0, norm_x, norm_y, down);
    }

    pub fn touch_mt(&mut self, slot: usize, norm_x: f64, norm_y: f64, down: bool) {
        // macOS cannot inject touchscreen contacts; the primary finger drives the pointer
        if slot != 0 {
            return;
        }
        let adj_x = match self.monitor_region {
            1 => norm_x * 0.5,       // Primary / Left Display
            2 => 0.5 + norm_x * 0.5, // Secondary / Right Display
            _ => norm_x,             // Full Combined Desktop
        };
        let desk = Self::desktop_bounds();
        let raw = CGPoint {
            x: desk.origin.x + adj_x.clamp(0.0, 1.0) * desk.size.width,
            y: desk.origin.y + norm_y.clamp(0.0, 1.0) * desk.size.height,
        };
        let at = Self::constrain(self.cursor_pos(), desk.clamp(raw));

        match (down, self.touch_down) {
            (true, false) => {
                self.touch_down = true;
                Self::post(unsafe { CGEventCreateMouseEvent(self.source, EV_MOUSE_MOVED, at, 0) });
                self.post_button(BTN_LEFT, true, at);
            }
            (true, true) => {
                Self::post(unsafe { CGEventCreateMouseEvent(self.source, EV_LEFT_DRAGGED, at, 0) });
            }
            (false, true) => {
                self.touch_down = false;
                self.post_button(BTN_LEFT, false, at);
            }
            (false, false) => {}
        }
    }
}

impl Drop for VirtualInput {
    fn drop(&mut self) {
        for (idx, down) in self.buttons_down.clone().iter().copied().enumerate() {
            if down {
                let at = self.cursor_pos();
                self.post_button(idx, false, at);
            }
        }
        unsafe { CFRelease(self.source) };
    }
}
