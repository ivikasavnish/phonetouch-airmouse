#!/usr/bin/env python3
"""
Linux uinput Virtual Mouse & Touchscreen controller (Zero external dependencies).
Supports:
- Relative Mouse (Air Mouse, Trackpad, Scroll, Left/Right/Middle Click)
- Absolute Touchscreen (1:1 Direct Touch Mapping)
"""

import ctypes
import fcntl
import os
import struct
import time

# Linux input event constants (<linux/input-event-codes.h> & <linux/uinput.h>)
EV_SYN = 0x00
EV_KEY = 0x01
EV_REL = 0x02
EV_ABS = 0x03

SYN_REPORT = 0x00

BTN_LEFT = 0x110
BTN_RIGHT = 0x111
BTN_MIDDLE = 0x112
BTN_TOUCH = 0x14a

REL_X = 0x00
REL_Y = 0x01
REL_WHEEL = 0x08
REL_HWHEEL = 0x06

ABS_X = 0x00
ABS_Y = 0x01

BUS_USB = 0x03

# ioctl constants for uinput
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_RELBIT = 0x40045566
UI_SET_ABSBIT = 0x40045567
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502

UINPUT_MAX_NAME_SIZE = 80
ABS_CNT = 64


class InputId(ctypes.Structure):
    _fields_ = [
        ("bustype", ctypes.c_uint16),
        ("vendor", ctypes.c_uint16),
        ("product", ctypes.c_uint16),
        ("version", ctypes.c_uint16),
    ]


class UinputUserDev(ctypes.Structure):
    _fields_ = [
        ("name", ctypes.c_char * UINPUT_MAX_NAME_SIZE),
        ("id", InputId),
        ("ff_effects_max", ctypes.c_uint32),
        ("absmax", ctypes.c_int32 * ABS_CNT),
        ("absmin", ctypes.c_int32 * ABS_CNT),
        ("absfuzz", ctypes.c_int32 * ABS_CNT),
        ("absflat", ctypes.c_int32 * ABS_CNT),
    ]


class VirtualInput:
    """Manages two virtual kernel devices: a Relative Mouse and an Absolute Touchscreen."""

    def __init__(self, abs_max_x=10000, abs_max_y=10000):
        self.abs_max_x = abs_max_x
        self.abs_max_y = abs_max_y
        self.mouse_fd = self._create_mouse()
        self.touch_fd = self._create_touchscreen()
        self._rem_x = 0.0
        self._rem_y = 0.0

    def _create_mouse(self):
        fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_REL)
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_SYN)

        for btn in (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE):
            fcntl.ioctl(fd, UI_SET_KEYBIT, btn)

        for rel in (REL_X, REL_Y, REL_WHEEL, REL_HWHEEL):
            fcntl.ioctl(fd, UI_SET_RELBIT, rel)

        udev = UinputUserDev()
        udev.name = b"PhoneTouch Virtual AirMouse"
        udev.id.bustype = BUS_USB
        udev.id.vendor = 0x18D1
        udev.id.product = 0x4EE1
        udev.id.version = 1

        os.write(fd, bytes(udev))
        fcntl.ioctl(fd, UI_DEV_CREATE)
        return fd

    def _create_touchscreen(self):
        fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_ABS)
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_SYN)

        fcntl.ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH)
        fcntl.ioctl(fd, UI_SET_ABSBIT, ABS_X)
        fcntl.ioctl(fd, UI_SET_ABSBIT, ABS_Y)

        udev = UinputUserDev()
        udev.name = b"PhoneTouch Virtual Touchscreen"
        udev.id.bustype = BUS_USB
        udev.id.vendor = 0x18D1
        udev.id.product = 0x4EE2
        udev.id.version = 1
        udev.absmin[ABS_X] = 0
        udev.absmax[ABS_X] = self.abs_max_x
        udev.absmin[ABS_Y] = 0
        udev.absmax[ABS_Y] = self.abs_max_y

        os.write(fd, bytes(udev))
        fcntl.ioctl(fd, UI_DEV_CREATE)
        return fd

    def _emit(self, fd, ev_type, ev_code, ev_value):
        now = time.time()
        sec = int(now)
        usec = int((now - sec) * 1_000_000)
        # struct input_event on 64-bit Linux: llHHi (24 bytes)
        pkt = struct.pack("llHHi", sec, usec, ev_type, ev_code, int(ev_value))
        os.write(fd, pkt)

    def move_rel(self, dx: float, dy: float):
        """Move relative cursor with sub-pixel remainder accumulation for smooth gyro motion."""
        self._rem_x += dx
        self._rem_y += dy
        ix = int(self._rem_x)
        iy = int(self._rem_y)
        if ix == 0 and iy == 0:
            return
        self._rem_x -= ix
        self._rem_y -= iy
        if ix != 0:
            self._emit(self.mouse_fd, EV_REL, REL_X, ix)
        if iy != 0:
            self._emit(self.mouse_fd, EV_REL, REL_Y, iy)
        self._emit(self.mouse_fd, EV_SYN, SYN_REPORT, 0)

    def scroll(self, dy: int, dx: int = 0):
        if dy != 0:
            self._emit(self.mouse_fd, EV_REL, REL_WHEEL, int(dy))
        if dx != 0:
            self._emit(self.mouse_fd, EV_REL, REL_HWHEEL, int(dx))
        if dy != 0 or dx != 0:
            self._emit(self.mouse_fd, EV_SYN, SYN_REPORT, 0)

    def mouse_button(self, button: str, pressed: bool):
        btn_map = {
            "left": BTN_LEFT,
            "right": BTN_RIGHT,
            "middle": BTN_MIDDLE,
        }
        code = btn_map.get(button, BTN_LEFT)
        self._emit(self.mouse_fd, EV_KEY, code, 1 if pressed else 0)
        self._emit(self.mouse_fd, EV_SYN, SYN_REPORT, 0)

    def click(self, button: str = "left"):
        self.mouse_button(button, True)
        time.sleep(0.015)
        self.mouse_button(button, False)

    def touch_abs(self, norm_x: float, norm_y: float, touching: bool):
        """Move and/or press absolute touchscreen (norm_x, norm_y in [0.0, 1.0])."""
        x = max(0, min(self.abs_max_x, int(norm_x * self.abs_max_x)))
        y = max(0, min(self.abs_max_y, int(norm_y * self.abs_max_y)))
        if touching:
            self._emit(self.touch_fd, EV_ABS, ABS_X, x)
            self._emit(self.touch_fd, EV_ABS, ABS_Y, y)
            self._emit(self.touch_fd, EV_KEY, BTN_TOUCH, 1)
        else:
            self._emit(self.touch_fd, EV_KEY, BTN_TOUCH, 0)
        self._emit(self.touch_fd, EV_SYN, SYN_REPORT, 0)

    def close(self):
        for fd in (getattr(self, "mouse_fd", None), getattr(self, "touch_fd", None)):
            if fd is not None:
                try:
                    fcntl.ioctl(fd, UI_DEV_DESTROY)
                except OSError:
                    pass
                try:
                    os.close(fd)
                except OSError:
                    pass
