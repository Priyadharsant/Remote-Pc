import io
import time
import json
import struct
import asyncio
import ctypes
from ctypes import wintypes
import threading
from typing import List, Dict, Any, Optional, Tuple
from PIL import Image
import pyautogui
import pyperclip

from controllers import mouse, keyboard

# Zero delay for instant input response
pyautogui.PAUSE = 0
pyautogui.FAILSAFE = False

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32
try:
    dwmapi = ctypes.windll.dwmapi
except Exception:
    dwmapi = None

try:
    winmm = ctypes.windll.winmm
    winmm.timeBeginPeriod(1)
except Exception:
    pass

try:
    user32.SetProcessDPIAware()
except Exception:
    pass

HDC = wintypes.HDC
HBITMAP = wintypes.HBITMAP

# Ensure thread has access to interactive desktop
def ensure_desktop_access() -> None:
    try:
        hdesk = user32.OpenDesktopA(b'default', 0, False, 0x01FF)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
    except Exception:
        pass

class RECT(ctypes.Structure):
    _fields_ = [
        ('left', wintypes.LONG),
        ('top', wintypes.LONG),
        ('right', wintypes.LONG),
        ('bottom', wintypes.LONG)
    ]

class MONITORINFOEXW(ctypes.Structure):
    _fields_ = [
        ('cbSize', wintypes.DWORD),
        ('rcMonitor', RECT),
        ('rcWork', RECT),
        ('dwFlags', wintypes.DWORD),
        ('szDevice', wintypes.WCHAR * 32)
    ]

class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ('biSize', wintypes.DWORD),
        ('biWidth', wintypes.LONG),
        ('biHeight', wintypes.LONG),
        ('biPlanes', wintypes.WORD),
        ('biBitCount', wintypes.WORD),
        ('biCompression', wintypes.DWORD),
        ('biSizeImage', wintypes.DWORD),
        ('biXPelsPerMeter', wintypes.LONG),
        ('biYPelsPerMeter', wintypes.LONG),
        ('biClrUsed', wintypes.DWORD),
        ('biClrImportant', wintypes.DWORD)
    ]

class BITMAPINFO(ctypes.Structure):
    _fields_ = [
        ('bmiHeader', BITMAPINFOHEADER),
        ('bmiColors', wintypes.DWORD * 3)
    ]

# Explicit 64-bit Win32 GDI & User32 function signatures to prevent pointer truncation
user32.GetDC.restype = HDC
user32.GetDC.argtypes = [wintypes.HWND]
user32.ReleaseDC.restype = ctypes.c_int
user32.ReleaseDC.argtypes = [wintypes.HWND, HDC]

user32.IsWindow.restype = wintypes.BOOL
user32.IsWindow.argtypes = [wintypes.HWND]

user32.IsIconic.restype = wintypes.BOOL
user32.IsIconic.argtypes = [wintypes.HWND]

user32.ShowWindow.restype = wintypes.BOOL
user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]

user32.SetForegroundWindow.restype = wintypes.BOOL
user32.SetForegroundWindow.argtypes = [wintypes.HWND]

user32.GetForegroundWindow.restype = wintypes.HWND
user32.GetForegroundWindow.argtypes = []

user32.GetWindowRect.restype = wintypes.BOOL
user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]

user32.PrintWindow.restype = wintypes.BOOL
user32.PrintWindow.argtypes = [wintypes.HWND, HDC, wintypes.UINT]

user32.GetWindowLongW.restype = wintypes.LONG
user32.GetWindowLongW.argtypes = [wintypes.HWND, ctypes.c_int]

user32.GetWindowTextLengthW.restype = ctypes.c_int
user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]

user32.GetWindowTextW.restype = ctypes.c_int
user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]

user32.IsWindowVisible.restype = wintypes.BOOL
user32.IsWindowVisible.argtypes = [wintypes.HWND]

user32.GetWindow.restype = wintypes.HWND
user32.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]

def is_window_valid(hwnd: Any) -> bool:
    """Safely checks if an HWND is a currently existing, valid window handle."""
    try:
        if not hwnd:
            return False
        h = wintypes.HWND(int(hwnd))
        return bool(user32.IsWindow(h))
    except Exception:
        return False

gdi32.CreateCompatibleDC.restype = HDC
gdi32.CreateCompatibleDC.argtypes = [HDC]
gdi32.DeleteDC.restype = wintypes.BOOL
gdi32.DeleteDC.argtypes = [HDC]

gdi32.CreateDIBSection.restype = HBITMAP
gdi32.CreateDIBSection.argtypes = [
    HDC,
    ctypes.POINTER(BITMAPINFO),
    wintypes.UINT,
    ctypes.POINTER(ctypes.c_void_p),
    wintypes.HANDLE,
    wintypes.DWORD
]

gdi32.SelectObject.restype = wintypes.HGDIOBJ
gdi32.SelectObject.argtypes = [HDC, wintypes.HGDIOBJ]
gdi32.DeleteObject.restype = wintypes.BOOL
gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]

gdi32.BitBlt.restype = wintypes.BOOL
gdi32.BitBlt.argtypes = [
    HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    HDC, ctypes.c_int, ctypes.c_int, wintypes.DWORD
]

gdi32.StretchBlt.restype = wintypes.BOOL
gdi32.StretchBlt.argtypes = [
    HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.DWORD
]

gdi32.SetStretchBltMode.restype = ctypes.c_int
gdi32.SetStretchBltMode.argtypes = [HDC, ctypes.c_int]

def get_monitors_info() -> List[Dict[str, Any]]:
    """Enumerate all available physical and virtual monitors."""
    ensure_desktop_access()
    monitors = []

    def _monitor_enum_callback(hMonitor, hdcMonitor, lprcMonitor, dwData):
        mi = MONITORINFOEXW()
        mi.cbSize = ctypes.sizeof(MONITORINFOEXW)
        if user32.GetMonitorInfoW(hMonitor, ctypes.byref(mi)):
            w = mi.rcMonitor.right - mi.rcMonitor.left
            h = mi.rcMonitor.bottom - mi.rcMonitor.top
            idx = len(monitors) + 1
            is_prim = bool(mi.dwFlags & 1)
            monitors.append({
                "id": idx,
                "name": f"Display {idx}" + (" (Primary)" if is_prim else ""),
                "left": mi.rcMonitor.left,
                "top": mi.rcMonitor.top,
                "width": w,
                "height": h,
                "is_primary": is_prim
            })
        return True

    try:
        MONITORENUMPROC = ctypes.WINFUNCTYPE(
            ctypes.c_bool, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(RECT), wintypes.LPARAM
        )
        user32.EnumDisplayMonitors(None, None, MONITORENUMPROC(_monitor_enum_callback), 0)
    except Exception:
        pass

    if not monitors:
        w = user32.GetSystemMetrics(0) or 1920
        h = user32.GetSystemMetrics(1) or 1080
        monitors.append({
            "id": 1,
            "name": "Display 1 (Primary)",
            "left": 0,
            "top": 0,
            "width": w,
            "height": h,
            "is_primary": True
        })
    return monitors

def get_active_windows() -> List[Dict[str, Any]]:
    """Enumerate running top-level visible application windows for isolated window control."""
    ensure_desktop_access()
    windows = []

    GWL_EXSTYLE = -20
    WS_EX_TOOLWINDOW = 0x00000080
    WS_EX_APPWINDOW = 0x00040000
    DWMWA_CLOAKED = 14

    user32.GetWindowLongW.restype = wintypes.LONG
    user32.GetWindowLongW.argtypes = [wintypes.HWND, ctypes.c_int]
    user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    user32.GetWindow.argtypes = [wintypes.HWND, wintypes.UINT]

    def _enum_win_cb(hwnd, lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True

        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        title = buf.value.strip()
        if not title:
            return True

        ex_style = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
        if (ex_style & WS_EX_TOOLWINDOW) and not (ex_style & WS_EX_APPWINDOW):
            return True

        owner = user32.GetWindow(hwnd, 4)  # GW_OWNER
        if owner != 0 and not (ex_style & WS_EX_APPWINDOW):
            return True

        if dwmapi and hasattr(dwmapi, "DwmGetWindowAttribute"):
            cloaked = wintypes.DWORD()
            if dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, ctypes.byref(cloaked), ctypes.sizeof(cloaked)) == 0:
                if cloaked.value != 0:
                    return True

        rect = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))
        w = rect.right - rect.left
        h = rect.bottom - rect.top
        if w < 100 or h < 100:
            return True

        # Skip system infrastructure windows
        if title in ('Program Manager', 'Settings', 'Windows Input Experience', 'Microsoft Text Input Application'):
            return True

        windows.append({
            "hwnd": int(hwnd),
            "title": title,
            "width": w,
            "height": h
        })
        return True

    try:
        WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        user32.EnumWindows(WNDENUMPROC(_enum_win_cb), 0)
    except Exception:
        pass

    return windows

class FastGDIScreenCapturer:
    """
    Ultra-low-latency 60 FPS native Windows GDI screen capturer with direct DIBSection memory mapping.
    Bypasses GetDIBits buffer copies and GPU pipeline stalls to achieve ~10-15ms per frame.
    """
    def __init__(self):
        self._lock = threading.Lock()
        self.dest_w = 0
        self.dest_h = 0
        self.sdc = None
        self.mdc = None
        self.hbmp = None
        self.obmp = None
        self.bits_ptr = ctypes.c_void_p()
        self.bmi = None

    def _ensure_buffers(self, w: int, h: int):
        if self.mdc and self.dest_w == w and self.dest_h == h and self.bits_ptr.value:
            return
        self.cleanup()
        self.dest_w = w
        self.dest_h = h
        self.sdc = user32.GetDC(0)
        self.mdc = gdi32.CreateCompatibleDC(self.sdc)

        bmi = BITMAPINFO()
        bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        bmi.bmiHeader.biWidth = w
        bmi.bmiHeader.biHeight = -h # top-down DIB
        bmi.bmiHeader.biPlanes = 1
        bmi.bmiHeader.biBitCount = 32
        bmi.bmiHeader.biCompression = 0
        self.bmi = bmi

        self.bits_ptr = ctypes.c_void_p()
        self.hbmp = gdi32.CreateDIBSection(
            self.sdc,
            ctypes.byref(self.bmi),
            0,
            ctypes.byref(self.bits_ptr),
            None,
            0
        )
        self.obmp = gdi32.SelectObject(self.mdc, self.hbmp)
        gdi32.SetStretchBltMode(self.mdc, 3) # COLORONCOLOR for hardware downscaling

    def capture(self, mon_left: int, mon_top: int, orig_w: int, orig_h: int, quality: int = 55, scale: float = 1.0) -> Tuple[bytes, int, int]:
        with self._lock:
            target_w = orig_w
            target_h = orig_h
            if 0.2 <= scale < 0.98:
                target_w = max(320, int(orig_w * scale))
                target_h = max(240, int(orig_h * scale))

            self._ensure_buffers(target_w, target_h)

            if target_w == orig_w and target_h == orig_h:
                gdi32.BitBlt(self.mdc, 0, 0, target_w, target_h, self.sdc, mon_left, mon_top, 0x00CC0020)
            else:
                gdi32.StretchBlt(self.mdc, 0, 0, target_w, target_h, self.sdc, mon_left, mon_top, orig_w, orig_h, 0x00CC0020)

            raw_data = (ctypes.c_char * (target_w * target_h * 4)).from_address(self.bits_ptr.value)
            img = Image.frombuffer('RGB', (target_w, target_h), raw_data, 'raw', 'BGRX', 0, 1)

            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=quality, subsampling=2, optimize=False)
            return buf.getvalue(), orig_w, orig_h

    def cleanup(self):
        if self.mdc:
            if self.obmp:
                gdi32.SelectObject(self.mdc, self.obmp)
                self.obmp = None
            if self.hbmp:
                gdi32.DeleteObject(self.hbmp)
                self.hbmp = None
            gdi32.DeleteDC(self.mdc)
            self.mdc = None
        if self.sdc:
            user32.ReleaseDC(0, self.sdc)
            self.sdc = None
        self.bits_ptr = ctypes.c_void_p()

_capturer = FastGDIScreenCapturer()

def capture_screen_jpeg(monitor_idx: int = 1, quality: int = 55, scale: float = 1.0) -> Tuple[bytes, int, int]:
    """Captures a screenshot of the specified monitor using high-speed Win32 GDI."""
    ensure_desktop_access()
    monitors = get_monitors_info()
    mon = next((m for m in monitors if m["id"] == monitor_idx), monitors[0] if monitors else None)

    mon_left = mon["left"] if mon else 0
    mon_top = mon["top"] if mon else 0
    mon_w = mon["width"] if mon else 1920
    mon_h = mon["height"] if mon else 1080

    try:
        return _capturer.capture(mon_left, mon_top, mon_w, mon_h, quality=quality, scale=scale)
    except Exception:
        # Fallback to pyautogui in emergency
        img = pyautogui.screenshot()
        orig_w, orig_h = img.width, img.height
        if scale < 0.98 and scale >= 0.2:
            new_w = max(320, int(orig_w * scale))
            new_h = max(240, int(orig_h * scale))
            img = img.resize((new_w, new_h), Image.Resampling.BILINEAR)
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=quality, optimize=False)
        return buf.getvalue(), orig_w, orig_h

class FastWindowCapturer:
    """
    Dedicated isolated window capturer using PrintWindow with PW_RENDERFULLCONTENT (flag = 2).
    Streams only the targeted application window even if it is behind other windows.
    """
    def __init__(self):
        self._lock = threading.Lock()
        self.dest_w = 0
        self.dest_h = 0
        self.sdc = None
        self.mdc = None
        self.hbmp = None
        self.obmp = None
        self.bits_ptr = ctypes.c_void_p()
        self.bmi = None

    def _ensure_buffers(self, w: int, h: int):
        if self.mdc and self.dest_w == w and self.dest_h == h and self.bits_ptr.value:
            return
        self.cleanup()
        self.dest_w = w
        self.dest_h = h
        self.sdc = user32.GetDC(0)
        self.mdc = gdi32.CreateCompatibleDC(self.sdc)

        bmi = BITMAPINFO()
        bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        bmi.bmiHeader.biWidth = w
        bmi.bmiHeader.biHeight = -h  # top-down DIB
        bmi.bmiHeader.biPlanes = 1
        bmi.bmiHeader.biBitCount = 32
        bmi.bmiHeader.biCompression = 0
        self.bmi = bmi

        self.bits_ptr = ctypes.c_void_p()
        self.hbmp = gdi32.CreateDIBSection(
            self.sdc,
            ctypes.byref(self.bmi),
            0,
            ctypes.byref(self.bits_ptr),
            None,
            0
        )
        self.obmp = gdi32.SelectObject(self.mdc, self.hbmp)

    def capture(self, hwnd: int, quality: int = 55, scale: float = 1.0) -> Tuple[bytes, int, int]:
        h_val = wintypes.HWND(int(hwnd))
        if not user32.IsWindow(h_val):
            raise ValueError("Invalid HWND")

        if user32.IsIconic(h_val):
            user32.ShowWindow(h_val, 9)  # SW_RESTORE

        rect = wintypes.RECT()
        if not user32.GetWindowRect(h_val, ctypes.byref(rect)):
            raise ValueError("Cannot get window rect")

        orig_w = max(100, rect.right - rect.left)
        orig_h = max(100, rect.bottom - rect.top)

        with self._lock:
            self._ensure_buffers(orig_w, orig_h)
            
            # Try PrintWindow with PW_RENDERFULLCONTENT (2)
            ok = user32.PrintWindow(h_val, self.mdc, 2)
            if not ok:
                # Fallback to standard PrintWindow (0)
                ok = user32.PrintWindow(h_val, self.mdc, 0)
            
            # If PrintWindow failed (hardware-accelerated/DirectX windows),
            # capture window's screen rect directly from desktop DC
            if not ok:
                sdc = user32.GetDC(0)
                gdi32.BitBlt(self.mdc, 0, 0, orig_w, orig_h, sdc, rect.left, rect.top, 0x00CC0020)
                user32.ReleaseDC(0, sdc)

            raw_data = (ctypes.c_char * (orig_w * orig_h * 4)).from_address(self.bits_ptr.value)
            img = Image.frombuffer('RGB', (orig_w, orig_h), raw_data, 'raw', 'BGRX', 0, 1)

            if 0.2 <= scale < 0.98:
                new_w = max(320, int(orig_w * scale))
                new_h = max(240, int(orig_h * scale))
                img = img.resize((new_w, new_h), Image.Resampling.BILINEAR)

            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=quality, subsampling=2, optimize=False)
            return buf.getvalue(), orig_w, orig_h

    def cleanup(self):
        if self.mdc:
            if self.obmp:
                gdi32.SelectObject(self.mdc, self.obmp)
                self.obmp = None
            if self.hbmp:
                gdi32.DeleteObject(self.hbmp)
                self.hbmp = None
            gdi32.DeleteDC(self.mdc)
            self.mdc = None
        if self.sdc:
            user32.ReleaseDC(0, self.sdc)
            self.sdc = None
        self.bits_ptr = ctypes.c_void_p()

_window_capturer = FastWindowCapturer()

def capture_window_jpeg(hwnd: int, quality: int = 55, scale: float = 1.0) -> Tuple[bytes, int, int]:
    """Captures an isolated application window in background without desktop interference."""
    ensure_desktop_access()
    return _window_capturer.capture(hwnd, quality=quality, scale=scale)

def handle_input_action(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Processes incoming touch, mouse, and keyboard commands from the mobile client.
    Supports both Direct Touch Mode (normalized coordinates) and Virtual Trackpad Mode,
    with automatic coordinate mapping for isolated application windows.
    """
    action_type = data.get("type", "")
    mon_id = int(data.get("monitor", 1))
    mode = data.get("mode", "desktop")
    hwnd = data.get("hwnd")
    
    # Retrieve target monitor geometry
    monitors = get_monitors_info()
    mon = next((m for m in monitors if m["id"] == mon_id), monitors[0] if monitors else None)
    
    mon_left = mon["left"] if mon else 0
    mon_top = mon["top"] if mon else 0
    mon_width = mon["width"] if mon else 1920
    mon_height = mon["height"] if mon else 1080

    if mode == "window" and hwnd and is_window_valid(hwnd):
        h_val = wintypes.HWND(int(hwnd))
        rect = wintypes.RECT()
        if user32.GetWindowRect(h_val, ctypes.byref(rect)):
            win_left = rect.left
            win_top = rect.top
            win_width = max(1, rect.right - rect.left)
            win_height = max(1, rect.bottom - rect.top)

            if user32.IsIconic(h_val):
                user32.ShowWindow(h_val, 9)
            user32.SetForegroundWindow(h_val)

            def to_screen_coords(x: float, y: float, is_norm: bool) -> Tuple[int, int]:
                if is_norm:
                    px = win_left + int(max(0.0, min(1.0, x)) * win_width)
                    py = win_top + int(max(0.0, min(1.0, y)) * win_height)
                else:
                    px = win_left + int(x)
                    py = win_top + int(y)
                return px, py
        else:
            def to_screen_coords(x: float, y: float, is_norm: bool) -> Tuple[int, int]:
                if is_norm:
                    px = mon_left + int(max(0.0, min(1.0, x)) * mon_width)
                    py = mon_top + int(max(0.0, min(1.0, y)) * mon_height)
                else:
                    px = mon_left + int(x)
                    py = mon_top + int(y)
                return px, py
    else:
        def to_screen_coords(x: float, y: float, is_norm: bool) -> Tuple[int, int]:
            if is_norm:
                px = mon_left + int(max(0.0, min(1.0, x)) * mon_width)
                py = mon_top + int(max(0.0, min(1.0, y)) * mon_height)
            else:
                px = mon_left + int(x)
                py = mon_top + int(y)
            return px, py

    if action_type == "mouse_move":
        x = float(data.get("x", 0))
        y = float(data.get("y", 0))
        is_norm = bool(data.get("is_normalized", True))
        px, py = to_screen_coords(x, y, is_norm)
        pyautogui.moveTo(px, py)
        return {"status": "ok", "x": px, "y": py}

    elif action_type == "mouse_relative":
        # Used for Virtual Trackpad mode
        dx = float(data.get("dx", 0))
        dy = float(data.get("dy", 0))
        sensitivity = float(data.get("sensitivity", 1.5))
        mouse.move_cursor(dx, dy, sensitivity)
        return {"status": "ok"}

    elif action_type == "mouse_click":
        btn = data.get("button", "left").lower()
        clicks = int(data.get("clicks", 1))
        if "x" in data and "y" in data:
            x = float(data.get("x", 0))
            y = float(data.get("y", 0))
            is_norm = bool(data.get("is_normalized", True))
            px, py = to_screen_coords(x, y, is_norm)
            pyautogui.click(x=px, y=py, button=btn, clicks=clicks)
        else:
            pyautogui.click(button=btn, clicks=clicks)
        return {"status": "ok"}

    elif action_type == "mouse_down":
        btn = data.get("button", "left").lower()
        if "x" in data and "y" in data:
            x = float(data.get("x", 0))
            y = float(data.get("y", 0))
            is_norm = bool(data.get("is_normalized", True))
            px, py = to_screen_coords(x, y, is_norm)
            pyautogui.mouseDown(x=px, y=py, button=btn)
        else:
            pyautogui.mouseDown(button=btn)
        return {"status": "ok"}

    elif action_type == "mouse_up":
        btn = data.get("button", "left").lower()
        if "x" in data and "y" in data:
            x = float(data.get("x", 0))
            y = float(data.get("y", 0))
            is_norm = bool(data.get("is_normalized", True))
            px, py = to_screen_coords(x, y, is_norm)
            pyautogui.mouseUp(x=px, y=py, button=btn)
        else:
            pyautogui.mouseUp(button=btn)
        return {"status": "ok"}

    elif action_type == "mouse_scroll":
        dy = float(data.get("dy", 0))
        dx = float(data.get("dx", 0))
        if dy != 0:
            mouse.scroll_vertical(dy)
        if dx != 0:
            mouse.scroll_horizontal(dx)
        return {"status": "ok"}

    elif action_type == "key_press":
        key = str(data.get("key", ""))
        if key:
            keyboard.press_key(key)
        return {"status": "ok"}

    elif action_type == "hotkey":
        keys = data.get("keys", [])
        if isinstance(keys, list) and keys:
            keyboard.press_hotkey(*[str(k) for k in keys])
        return {"status": "ok"}

    elif action_type == "text_input":
        text = str(data.get("text", ""))
        if text:
            keyboard.type_text_literal(text)
        return {"status": "ok"}

    elif action_type == "clipboard_set":
        text = str(data.get("text", ""))
        try:
            pyperclip.copy(text)
            return {"status": "ok", "synced": True}
        except Exception as e:
            return {"status": "error", "message": str(e)}

    elif action_type == "clipboard_get":
        try:
            content = pyperclip.paste()
            return {"status": "ok", "text": content}
        except Exception as e:
            return {"status": "error", "message": str(e)}

    return {"status": "error", "message": f"Unsupported action: {action_type}"}

# Binary protocol packet creator
# [4B Magic: 'RD01'][2B Monitor ID][2B Width][2B Height][2B FrameId][Payload]
HEADER_FORMAT = ">4sHHHH"
MAGIC_BYTES = b"RD01"

def pack_frame(frame_bytes: bytes, monitor_id: int, width: int, height: int, frame_id: int) -> bytes:
    header = struct.pack(HEADER_FORMAT, MAGIC_BYTES, monitor_id, width, height, frame_id % 65536)
    return header + frame_bytes

def generate_audio_stream():
    """
    Generates a high-fidelity digital streamable WAV audio feed of Windows master sound output.
    Uses native Windows Core Audio WASAPI Loopback to capture 100% digital bit-perfect system audio
    (YouTube, games, media players, browser) with zero microphone noise or room acoustics.
    """
    try:
        import comtypes
        from comtypes import GUID, COMMETHOD, HRESULT, POINTER
        from ctypes import c_uint32, c_uint64, string_at
        from ctypes.wintypes import DWORD, BYTE
        from pycaw.pycaw import AudioUtilities, IAudioClient
        import numpy as np

        class IAudioCaptureClient(comtypes.IUnknown):
            _iid_ = GUID('{C8ADBD64-E71E-48a0-A4DE-185C395CD317}')
            _methods_ = [
                COMMETHOD([], HRESULT, 'GetBuffer',
                          (['out'], POINTER(POINTER(BYTE)), 'ppData'),
                          (['out'], POINTER(c_uint32), 'pNumFramesToRead'),
                          (['out'], POINTER(DWORD), 'pdwFlags'),
                          (['out'], POINTER(c_uint64), 'pu64DevicePosition'),
                          (['out'], POINTER(c_uint64), 'pu64QPCPosition')),
                COMMETHOD([], HRESULT, 'ReleaseBuffer',
                          (['in'], c_uint32, 'NumFramesRead')),
                COMMETHOD([], HRESULT, 'GetNextPacketSize',
                          (['out'], POINTER(c_uint32), 'pNumFramesInNextPacket'))
            ]

        comtypes.CoInitialize()
        client = None
        try:
            device = AudioUtilities.GetSpeakers()
            if not device or not hasattr(device, "_dev") or not device._dev:
                return

            client = device._dev.Activate(IAudioClient._iid_, comtypes.CLSCTX_ALL, None).QueryInterface(IAudioClient)
            mix_fmt = client.GetMixFormat()

            sample_rate = int(mix_fmt.contents.nSamplesPerSec)
            channels = int(mix_fmt.contents.nChannels)
            bits = int(mix_fmt.contents.wBitsPerSample)

            out_sample_rate = sample_rate
            out_channels = min(channels, 2)
            out_bits = 16

            # 44-byte streamable WAV header
            header = io.BytesIO()
            header.write(b'RIFF')
            header.write(struct.pack('<I', 0x7FFFFFFF))
            header.write(b'WAVEfmt ')
            header.write(struct.pack('<I', 16))
            header.write(struct.pack('<H', 1))  # PCM
            header.write(struct.pack('<H', out_channels))
            header.write(struct.pack('<I', out_sample_rate))
            header.write(struct.pack('<I', out_sample_rate * out_channels * 2))
            header.write(struct.pack('<H', out_channels * 2))
            header.write(struct.pack('<H', out_bits))
            header.write(b'data')
            header.write(struct.pack('<I', 0x7FFFFFFF))
            yield header.getvalue()

            # AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
            # AUDCLNT_SHAREMODE_SHARED = 0
            hns_buffer_duration = 10000000  # 1-second buffer
            client.Initialize(0, 0x00020000, hns_buffer_duration, 0, mix_fmt, None)
            capture_client = client.GetService(IAudioCaptureClient._iid_).QueryInterface(IAudioCaptureClient)
            client.Start()

            while True:
                packet_size = capture_client.GetNextPacketSize()
                if packet_size == 0:
                    time.sleep(0.005)
                    continue

                chunk_bytes = bytearray()
                while packet_size > 0:
                    p_data, num_frames, flags, dev_pos, qpc_pos = capture_client.GetBuffer()
                    if num_frames > 0:
                        if flags & 2:  # AUDCLNT_BUFFERFLAGS_SILENT
                            chunk_bytes.extend(bytes(num_frames * out_channels * 2))
                        else:
                            if bits == 32:
                                raw_floats = string_at(p_data, num_frames * channels * 4)
                                arr = np.frombuffer(raw_floats, dtype=np.float32)
                                if channels > 2 and out_channels == 2:
                                    arr = arr.reshape(-1, channels)[:, :2].flatten()
                                pcm16 = (np.clip(arr, -1.0, 1.0) * 32767.0).astype(np.int16).tobytes()
                                chunk_bytes.extend(pcm16)
                            elif bits == 16:
                                raw_int16 = string_at(p_data, num_frames * channels * 2)
                                if channels > 2 and out_channels == 2:
                                    arr = np.frombuffer(raw_int16, dtype=np.int16).reshape(-1, channels)[:, :2].flatten()
                                    chunk_bytes.extend(arr.tobytes())
                                else:
                                    chunk_bytes.extend(raw_int16)
                            else:
                                chunk_bytes.extend(bytes(num_frames * out_channels * 2))
                        capture_client.ReleaseBuffer(num_frames)
                    packet_size = capture_client.GetNextPacketSize()

                if chunk_bytes:
                    yield bytes(chunk_bytes)

        finally:
            if client:
                try:
                    client.Stop()
                except Exception:
                    pass
            try:
                comtypes.CoUninitialize()
            except Exception:
                pass

    except Exception as e:
        print("[RemoteAudio] Direct WASAPI loopback stream error:", e)

