// Linux backend: virtual input devices via /dev/uinput ioctls

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::unix::io::AsRawFd;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const BACKEND_NAME: &str = "Linux /dev/uinput (PhoneTouch Virtual AirMouse + Touchscreen)";

pub fn input_permitted() -> bool {
    true
}

// Linux input event constants (<linux/input-event-codes.h> & <linux/uinput.h>)
const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_REL: u16 = 0x02;
const EV_ABS: u16 = 0x03;

const SYN_REPORT: u16 = 0x00;

const KEY_ESC: u16 = 1;
const KEY_1: u16 = 2;
const KEY_2: u16 = 3;
const KEY_3: u16 = 4;
const KEY_4: u16 = 5;
const KEY_5: u16 = 6;
const KEY_6: u16 = 7;
const KEY_7: u16 = 8;
const KEY_8: u16 = 9;
const KEY_9: u16 = 10;
const KEY_0: u16 = 11;
const KEY_MINUS: u16 = 12;
const KEY_EQUAL: u16 = 13;
const KEY_BACKSPACE: u16 = 14;
const KEY_TAB: u16 = 15;
const KEY_Q: u16 = 16;
const KEY_W: u16 = 17;
const KEY_E: u16 = 18;
const KEY_R: u16 = 19;
const KEY_T: u16 = 20;
const KEY_Y: u16 = 21;
const KEY_U: u16 = 22;
const KEY_I: u16 = 23;
const KEY_O: u16 = 24;
const KEY_P: u16 = 25;
const KEY_LEFTBRACE: u16 = 26;
const KEY_RIGHTBRACE: u16 = 27;
const KEY_ENTER: u16 = 28;
const KEY_LEFTCTRL: u16 = 29;
const KEY_A: u16 = 30;
const KEY_S: u16 = 31;
const KEY_D: u16 = 32;
const KEY_F: u16 = 33;
const KEY_G: u16 = 34;
const KEY_H: u16 = 35;
const KEY_J: u16 = 36;
const KEY_K: u16 = 37;
const KEY_L: u16 = 38;
const KEY_SEMICOLON: u16 = 39;
const KEY_APOSTROPHE: u16 = 40;
const KEY_GRAVE: u16 = 41;
const KEY_LEFTSHIFT: u16 = 42;
const KEY_BACKSLASH: u16 = 43;
const KEY_Z: u16 = 44;
const KEY_X: u16 = 45;
const KEY_C: u16 = 46;
const KEY_V: u16 = 47;
const KEY_B: u16 = 48;
const KEY_N: u16 = 49;
const KEY_M: u16 = 50;
const KEY_COMMA: u16 = 51;
const KEY_DOT: u16 = 52;
const KEY_SLASH: u16 = 53;
const KEY_LEFTALT: u16 = 56;
const KEY_SPACE: u16 = 57;
const KEY_MUTE: u16 = 113;
const KEY_VOLUMEDOWN: u16 = 114;
const KEY_VOLUMEUP: u16 = 115;
const KEY_LEFTMETA: u16 = 125;
const KEY_NEXTSONG: u16 = 163;
const KEY_PLAYPAUSE: u16 = 164;
const KEY_PREVIOUSSONG: u16 = 165;

const BTN_LEFT: u16 = 0x110;
const BTN_RIGHT: u16 = 0x111;
const BTN_MIDDLE: u16 = 0x112;
const BTN_TOUCH: u16 = 0x14a;

const REL_X: u16 = 0x00;
const REL_Y: u16 = 0x01;
const REL_HWHEEL: u16 = 0x06;
const REL_WHEEL: u16 = 0x08;
const REL_WHEEL_HI_RES: u16 = 0x0b;
const REL_HWHEEL_HI_RES: u16 = 0x0c;

const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_MT_SLOT: u16 = 0x2f;
const ABS_MT_POSITION_X: u16 = 0x35;
const ABS_MT_POSITION_Y: u16 = 0x36;
const ABS_MT_TRACKING_ID: u16 = 0x39;

const BUS_USB: u16 = 0x03;

// uinput ioctl numbers on 64-bit Linux
const UI_SET_EVBIT: u64 = 0x40045564;
const UI_SET_KEYBIT: u64 = 0x40045565;
const UI_SET_RELBIT: u64 = 0x40045566;
const UI_SET_ABSBIT: u64 = 0x40045567;
const UI_DEV_CREATE: u64 = 0x5501;
const UI_DEV_DESTROY: u64 = 0x5502;

const UINPUT_MAX_NAME_SIZE: usize = 80;
const ABS_CNT: usize = 64;

const ABS_MAX_X: i32 = 10000;
const ABS_MAX_Y: i32 = 10000;

unsafe extern "C" {
    fn ioctl(fd: i32, request: u64, ...) -> i32;
}

#[repr(C)]
#[derive(Copy, Clone)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
#[derive(Copy, Clone)]
struct UinputUserDev {
    name: [u8; UINPUT_MAX_NAME_SIZE],
    id: InputId,
    ff_effects_max: u32,
    absmax: [i32; ABS_CNT],
    absmin: [i32; ABS_CNT],
    absfuzz: [i32; ABS_CNT],
    absflat: [i32; ABS_CNT],
}

#[repr(C)]
#[derive(Copy, Clone)]
struct InputEvent {
    tv_sec: i64,
    tv_usec: i64,
    ev_type: u16,
    code: u16,
    value: i32,
}

pub struct VirtualInput {
    mouse_file: File,
    touch_file: File,
    rem_x: f64,
    rem_y: f64,
    rem_vscroll: f64,
    rem_hscroll: f64,
    active_slots: [bool; 5],
    monitor_region: usize, // 0 = Full Desktop, 1 = Left Half, 2 = Right Half
}

impl VirtualInput {
    pub fn new() -> Result<Self, String> {
        let mouse_file = Self::create_mouse()?;
        let touch_file = Self::create_touchscreen()?;
        Ok(Self {
            mouse_file,
            touch_file,
            rem_x: 0.0,
            rem_y: 0.0,
            rem_vscroll: 0.0,
            rem_hscroll: 0.0,
            active_slots: [false; 5],
            monitor_region: 0,
        })
    }

    fn set_name(target: &mut [u8; UINPUT_MAX_NAME_SIZE], name: &[u8]) {
        let len = name.len().min(UINPUT_MAX_NAME_SIZE - 1);
        target[..len].copy_from_slice(&name[..len]);
    }

    fn create_mouse() -> Result<File, String> {
        let mut file = OpenOptions::new()
            .write(true)
            .open("/dev/uinput")
            .map_err(|e| format!("Failed to open /dev/uinput: {e}"))?;
        let fd = file.as_raw_fd();

        unsafe {
            ioctl(fd, UI_SET_EVBIT, EV_KEY as i32);
            ioctl(fd, UI_SET_EVBIT, EV_REL as i32);
            ioctl(fd, UI_SET_EVBIT, EV_SYN as i32);

            for keycode in 1..=57 {
                ioctl(fd, UI_SET_KEYBIT, keycode as i32);
            }
            for btn in [
                BTN_LEFT,
                BTN_RIGHT,
                BTN_MIDDLE,
                KEY_MUTE,
                KEY_VOLUMEDOWN,
                KEY_VOLUMEUP,
                KEY_LEFTMETA,
                KEY_NEXTSONG,
                KEY_PLAYPAUSE,
                KEY_PREVIOUSSONG,
            ] {
                ioctl(fd, UI_SET_KEYBIT, btn as i32);
            }
            for rel in [
                REL_X,
                REL_Y,
                REL_WHEEL,
                REL_HWHEEL,
                REL_WHEEL_HI_RES,
                REL_HWHEEL_HI_RES,
            ] {
                ioctl(fd, UI_SET_RELBIT, rel as i32);
            }
        }

        let mut udev: UinputUserDev = unsafe { std::mem::zeroed() };
        Self::set_name(&mut udev.name, b"PhoneTouch Virtual AirMouse (Rust)");
        udev.id = InputId {
            bustype: BUS_USB,
            vendor: 0x18D1,
            product: 0x4EE1,
            version: 3,
        };

        let bytes = unsafe {
            std::slice::from_raw_parts(
                &udev as *const UinputUserDev as *const u8,
                std::mem::size_of::<UinputUserDev>(),
            )
        };
        file.write_all(bytes)
            .map_err(|e| format!("Failed writing mouse uinput_user_dev: {e}"))?;

        unsafe {
            if ioctl(fd, UI_DEV_CREATE) < 0 {
                return Err("UI_DEV_CREATE failed for mouse".into());
            }
        }
        Ok(file)
    }

    fn create_touchscreen() -> Result<File, String> {
        let mut file = OpenOptions::new()
            .write(true)
            .open("/dev/uinput")
            .map_err(|e| format!("Failed to open /dev/uinput: {e}"))?;
        let fd = file.as_raw_fd();

        unsafe {
            ioctl(fd, UI_SET_EVBIT, EV_KEY as i32);
            ioctl(fd, UI_SET_EVBIT, EV_ABS as i32);
            ioctl(fd, UI_SET_EVBIT, EV_SYN as i32);

            ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH as i32);
            for abs_code in [
                ABS_X,
                ABS_Y,
                ABS_MT_SLOT,
                ABS_MT_POSITION_X,
                ABS_MT_POSITION_Y,
                ABS_MT_TRACKING_ID,
            ] {
                ioctl(fd, UI_SET_ABSBIT, abs_code as i32);
            }
        }

        let mut udev: UinputUserDev = unsafe { std::mem::zeroed() };
        Self::set_name(&mut udev.name, b"PhoneTouch Virtual Touchscreen (Rust)");
        udev.id = InputId {
            bustype: BUS_USB,
            vendor: 0x18D1,
            product: 0x4EE2,
            version: 3,
        };
        udev.absmin[ABS_X as usize] = 0;
        udev.absmax[ABS_X as usize] = ABS_MAX_X;
        udev.absmin[ABS_Y as usize] = 0;
        udev.absmax[ABS_Y as usize] = ABS_MAX_Y;
        udev.absmin[ABS_MT_SLOT as usize] = 0;
        udev.absmax[ABS_MT_SLOT as usize] = 4;
        udev.absmin[ABS_MT_POSITION_X as usize] = 0;
        udev.absmax[ABS_MT_POSITION_X as usize] = ABS_MAX_X;
        udev.absmin[ABS_MT_POSITION_Y as usize] = 0;
        udev.absmax[ABS_MT_POSITION_Y as usize] = ABS_MAX_Y;
        udev.absmin[ABS_MT_TRACKING_ID as usize] = 0;
        udev.absmax[ABS_MT_TRACKING_ID as usize] = 65535;

        let bytes = unsafe {
            std::slice::from_raw_parts(
                &udev as *const UinputUserDev as *const u8,
                std::mem::size_of::<UinputUserDev>(),
            )
        };
        file.write_all(bytes)
            .map_err(|e| format!("Failed writing touchscreen uinput_user_dev: {e}"))?;

        unsafe {
            if ioctl(fd, UI_DEV_CREATE) < 0 {
                return Err("UI_DEV_CREATE failed for touchscreen".into());
            }
        }
        Ok(file)
    }

    fn emit(file: &mut File, ev_type: u16, code: u16, value: i32) {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or(Duration::ZERO);
        let ev = InputEvent {
            tv_sec: now.as_secs() as i64,
            tv_usec: now.subsec_micros() as i64,
            ev_type,
            code,
            value,
        };
        let bytes = unsafe {
            std::slice::from_raw_parts(
                &ev as *const InputEvent as *const u8,
                std::mem::size_of::<InputEvent>(),
            )
        };
        let _ = file.write_all(bytes);
    }

    pub fn move_rel(&mut self, dx: f64, dy: f64) {
        self.rem_x += dx;
        self.rem_y += dy;
        let ix = self.rem_x.trunc() as i32;
        let iy = self.rem_y.trunc() as i32;
        if ix == 0 && iy == 0 {
            return;
        }
        self.rem_x -= ix as f64;
        self.rem_y -= iy as f64;

        if ix != 0 {
            Self::emit(&mut self.mouse_file, EV_REL, REL_X, ix);
        }
        if iy != 0 {
            Self::emit(&mut self.mouse_file, EV_REL, REL_Y, iy);
        }
        Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
    }

    pub fn scroll(&mut self, dy: f64, dx: f64) {
        self.rem_vscroll += dy;
        self.rem_hscroll += dx;

        let hi_y = (dy * 120.0).round() as i32;
        let hi_x = (dx * 120.0).round() as i32;
        let mut emitted = false;

        if hi_y != 0 {
            Self::emit(&mut self.mouse_file, EV_REL, REL_WHEEL_HI_RES, hi_y);
            emitted = true;
        }
        if hi_x != 0 {
            Self::emit(&mut self.mouse_file, EV_REL, REL_HWHEEL_HI_RES, hi_x);
            emitted = true;
        }

        let steps_y = self.rem_vscroll.trunc() as i32;
        let steps_x = self.rem_hscroll.trunc() as i32;
        if steps_y != 0 {
            self.rem_vscroll -= steps_y as f64;
            Self::emit(&mut self.mouse_file, EV_REL, REL_WHEEL, steps_y);
            emitted = true;
        }
        if steps_x != 0 {
            self.rem_hscroll -= steps_x as f64;
            Self::emit(&mut self.mouse_file, EV_REL, REL_HWHEEL, steps_x);
            emitted = true;
        }

        if emitted {
            Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
        }
    }

    pub fn shortcut(&mut self, action: &str) {
        match action {
            "tab_next" => {
                // Ctrl + Tab (Next browser/editor tab)
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(12));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "tab_prev" => {
                // Ctrl + Shift + Tab (Previous browser/editor tab)
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTSHIFT, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(12));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTSHIFT, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "app_next" => {
                // Alt + Tab (Switch desktop window)
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTALT, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(15));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTALT, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "app_prev" => {
                // Alt + Shift + Tab
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTALT, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTSHIFT, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(15));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_TAB, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTSHIFT, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTALT, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "overview" => {
                // Super / Meta key (GNOME / Pop!_OS Workspace & Window Overview)
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTMETA, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(20));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTMETA, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "zoom_in" => {
                // Ctrl + Wheel Up AND Ctrl + Equal (+) for universal zoom support
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(8));
                Self::emit(&mut self.mouse_file, EV_REL, REL_WHEEL_HI_RES, 120);
                Self::emit(&mut self.mouse_file, EV_REL, REL_WHEEL, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_EQUAL, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(12));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_EQUAL, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "zoom_out" => {
                // Ctrl + Wheel Down AND Ctrl + Minus (-) for universal zoom support
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(8));
                Self::emit(&mut self.mouse_file, EV_REL, REL_WHEEL_HI_RES, -120);
                Self::emit(&mut self.mouse_file, EV_REL, REL_WHEEL, -1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_MINUS, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(12));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_MINUS, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "zoom_reset" => {
                // Ctrl + 0 (Reset zoom to 100%)
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 1);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_0, 1);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                thread::sleep(Duration::from_millis(12));
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_0, 0);
                Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 0);
                Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
            }
            "copy_url" => {
                // Ctrl + L, Ctrl + C, Esc: copy the focused browser's address bar to the clipboard
                for code in [KEY_L, KEY_C] {
                    Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 1);
                    Self::emit(&mut self.mouse_file, EV_KEY, code, 1);
                    Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                    thread::sleep(Duration::from_millis(12));
                    Self::emit(&mut self.mouse_file, EV_KEY, code, 0);
                    Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTCTRL, 0);
                    Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
                    thread::sleep(Duration::from_millis(40));
                }
                self.tap_key(KEY_ESC, false);
            }
            "vol_up" => self.tap_key(KEY_VOLUMEUP, false),
            "vol_down" => self.tap_key(KEY_VOLUMEDOWN, false),
            "vol_mute" => self.tap_key(KEY_MUTE, false),
            "media_play" => self.tap_key(KEY_PLAYPAUSE, false),
            "media_next" => self.tap_key(KEY_NEXTSONG, false),
            "media_prev" => self.tap_key(KEY_PREVIOUSSONG, false),
            "laser_ring" => {
                // GNOME / Pop!_OS 'Locate Pointer' visual cursor highlight (taps Left Ctrl)
                self.tap_key(KEY_LEFTCTRL, false);
            }
            "mon_cycle" => {
                self.monitor_region = (self.monitor_region + 1) % 3;
                println!("[*] 1:1 Touchscreen Monitor Region set to {}", self.monitor_region);
            }
            _ => {}
        }
    }

    fn tap_key(&mut self, code: u16, shift: bool) {
        if shift {
            Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTSHIFT, 1);
        }
        Self::emit(&mut self.mouse_file, EV_KEY, code, 1);
        Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
        thread::sleep(Duration::from_millis(8));
        Self::emit(&mut self.mouse_file, EV_KEY, code, 0);
        if shift {
            Self::emit(&mut self.mouse_file, EV_KEY, KEY_LEFTSHIFT, 0);
        }
        Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
        thread::sleep(Duration::from_millis(4));
    }

    pub fn type_text(&mut self, text: &str) {
        for ch in text.chars() {
            let mapped: Option<(u16, bool)> = match ch {
                'a'..='z' => {
                    let c = match ch {
                        'a' => KEY_A, 'b' => KEY_B, 'c' => KEY_C, 'd' => KEY_D, 'e' => KEY_E,
                        'f' => KEY_F, 'g' => KEY_G, 'h' => KEY_H, 'i' => KEY_I, 'j' => KEY_J,
                        'k' => KEY_K, 'l' => KEY_L, 'm' => KEY_M, 'n' => KEY_N, 'o' => KEY_O,
                        'p' => KEY_P, 'q' => KEY_Q, 'r' => KEY_R, 's' => KEY_S, 't' => KEY_T,
                        'u' => KEY_U, 'v' => KEY_V, 'w' => KEY_W, 'x' => KEY_X, 'y' => KEY_Y,
                        'z' => KEY_Z, _ => KEY_SPACE,
                    };
                    Some((c, false))
                }
                'A'..='Z' => {
                    let lower = ch.to_ascii_lowercase();
                    let c = match lower {
                        'a' => KEY_A, 'b' => KEY_B, 'c' => KEY_C, 'd' => KEY_D, 'e' => KEY_E,
                        'f' => KEY_F, 'g' => KEY_G, 'h' => KEY_H, 'i' => KEY_I, 'j' => KEY_J,
                        'k' => KEY_K, 'l' => KEY_L, 'm' => KEY_M, 'n' => KEY_N, 'o' => KEY_O,
                        'p' => KEY_P, 'q' => KEY_Q, 'r' => KEY_R, 's' => KEY_S, 't' => KEY_T,
                        'u' => KEY_U, 'v' => KEY_V, 'w' => KEY_W, 'x' => KEY_X, 'y' => KEY_Y,
                        'z' => KEY_Z, _ => KEY_SPACE,
                    };
                    Some((c, true))
                }
                '1' => Some((KEY_1, false)), '!' => Some((KEY_1, true)),
                '2' => Some((KEY_2, false)), '@' => Some((KEY_2, true)),
                '3' => Some((KEY_3, false)), '#' => Some((KEY_3, true)),
                '4' => Some((KEY_4, false)), '$' => Some((KEY_4, true)),
                '5' => Some((KEY_5, false)), '%' => Some((KEY_5, true)),
                '6' => Some((KEY_6, false)), '^' => Some((KEY_6, true)),
                '7' => Some((KEY_7, false)), '&' => Some((KEY_7, true)),
                '8' => Some((KEY_8, false)), '*' => Some((KEY_8, true)),
                '9' => Some((KEY_9, false)), '(' => Some((KEY_9, true)),
                '0' => Some((KEY_0, false)), ')' => Some((KEY_0, true)),
                '-' => Some((KEY_MINUS, false)), '_' => Some((KEY_MINUS, true)),
                '=' => Some((KEY_EQUAL, false)), '+' => Some((KEY_EQUAL, true)),
                '[' => Some((KEY_LEFTBRACE, false)), '{' => Some((KEY_LEFTBRACE, true)),
                ']' => Some((KEY_RIGHTBRACE, false)), '}' => Some((KEY_RIGHTBRACE, true)),
                '\\' => Some((KEY_BACKSLASH, false)), '|' => Some((KEY_BACKSLASH, true)),
                ';' => Some((KEY_SEMICOLON, false)), ':' => Some((KEY_SEMICOLON, true)),
                '\'' => Some((KEY_APOSTROPHE, false)), '"' => Some((KEY_APOSTROPHE, true)),
                '`' => Some((KEY_GRAVE, false)), '~' => Some((KEY_GRAVE, true)),
                ',' => Some((KEY_COMMA, false)), '<' => Some((KEY_COMMA, true)),
                '.' => Some((KEY_DOT, false)), '>' => Some((KEY_DOT, true)),
                '/' => Some((KEY_SLASH, false)), '?' => Some((KEY_SLASH, true)),
                ' ' => Some((KEY_SPACE, false)),
                '\n' => Some((KEY_ENTER, false)),
                '\t' => Some((KEY_TAB, false)),
                _ => None,
            };
            if let Some((code, shift)) = mapped {
                self.tap_key(code, shift);
            }
        }
    }

    pub fn mouse_button(&mut self, btn: &str, pressed: bool) {
        let code = match btn {
            "right" => BTN_RIGHT,
            "middle" => BTN_MIDDLE,
            "esc" => KEY_ESC,
            "enter" => KEY_ENTER,
            "backspace" => KEY_BACKSPACE,
            "space" => KEY_SPACE,
            _ => BTN_LEFT,
        };
        Self::emit(&mut self.mouse_file, EV_KEY, code, if pressed { 1 } else { 0 });
        Self::emit(&mut self.mouse_file, EV_SYN, SYN_REPORT, 0);
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
        if slot >= 5 {
            return;
        }
        let adj_x = match self.monitor_region {
            1 => norm_x * 0.5,         // Primary / Left Display
            2 => 0.5 + norm_x * 0.5,   // Secondary / Right Display
            _ => norm_x,               // Full Combined Desktop
        };
        let x = ((adj_x * ABS_MAX_X as f64) as i32).clamp(0, ABS_MAX_X);
        let y = ((norm_y * ABS_MAX_Y as f64) as i32).clamp(0, ABS_MAX_Y);

        Self::emit(&mut self.touch_file, EV_ABS, ABS_MT_SLOT, slot as i32);
        if down {
            if !self.active_slots[slot] {
                Self::emit(
                    &mut self.touch_file,
                    EV_ABS,
                    ABS_MT_TRACKING_ID,
                    (slot as i32) + 100,
                );
                self.active_slots[slot] = true;
            }
            Self::emit(&mut self.touch_file, EV_ABS, ABS_MT_POSITION_X, x);
            Self::emit(&mut self.touch_file, EV_ABS, ABS_MT_POSITION_Y, y);
            if slot == 0 {
                Self::emit(&mut self.touch_file, EV_ABS, ABS_X, x);
                Self::emit(&mut self.touch_file, EV_ABS, ABS_Y, y);
            }
        } else if self.active_slots[slot] {
            Self::emit(&mut self.touch_file, EV_ABS, ABS_MT_TRACKING_ID, -1);
            self.active_slots[slot] = false;
        }

        let any_down = self.active_slots.iter().any(|&active| active);
        Self::emit(
            &mut self.touch_file,
            EV_KEY,
            BTN_TOUCH,
            if any_down { 1 } else { 0 },
        );
        Self::emit(&mut self.touch_file, EV_SYN, SYN_REPORT, 0);
    }
}

impl Drop for VirtualInput {
    fn drop(&mut self) {
        unsafe {
            ioctl(self.mouse_file.as_raw_fd(), UI_DEV_DESTROY);
            ioctl(self.touch_file.as_raw_fd(), UI_DEV_DESTROY);
        }
    }
}
