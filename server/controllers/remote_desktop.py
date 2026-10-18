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

# Explicit 64-bit Win32 GDI function signatures to prevent pointer truncation
user32.GetDC.restype = HDC
user32.GetDC.argtypes = [wintypes.HWND]
user32.ReleaseDC.restype = ctypes.c_int
user32.ReleaseDC.argtypes = [wintypes.HWND, HDC]

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

def handle_input_action(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Processes incoming touch, mouse, and keyboard commands from the mobile client.
    Supports both Direct Touch Mode (normalized coordinates) and Virtual Trackpad Mode.
    """
    action_type = data.get("type", "")
    mon_id = int(data.get("monitor", 1))
    
    # Retrieve target monitor geometry
    monitors = get_monitors_info()
    mon = next((m for m in monitors if m["id"] == mon_id), monitors[0] if monitors else None)
    
    mon_left = mon["left"] if mon else 0
    mon_top = mon["top"] if mon else 0
    mon_width = mon["width"] if mon else 1920
    mon_height = mon["height"] if mon else 1080

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

def get_audio_input_device() -> Optional[int]:
    """Finds best audio device to record PC output (Stereo Mix or default input)."""
    try:
        import sounddevice as sd
        devices = sd.query_devices()
        for idx, d in enumerate(devices):
            if 'Stereo Mix' in d['name'] and d['max_input_channels'] > 0:
                return idx
        return sd.default.device[0]
    except Exception:
        return None

def generate_audio_stream():
    """Generates a streamable WAV audio feed of the Windows master sound output."""
    try:
        import sounddevice as sd
        dev_idx = get_audio_input_device()
        if dev_idx is None:
            return
        
        sample_rate = 44100
        channels = 2
        block_size = 2048
        
        # Streamable WAV header
        header = io.BytesIO()
        header.write(b'RIFF')
        header.write(struct.pack('<I', 0x7FFFFFFF))
        header.write(b'WAVEfmt ')
        header.write(struct.pack('<I', 16))
        header.write(struct.pack('<H', 1))
        header.write(struct.pack('<H', channels))
        header.write(struct.pack('<I', sample_rate))
        header.write(struct.pack('<I', sample_rate * channels * 2))
        header.write(struct.pack('<H', channels * 2))
        header.write(struct.pack('<H', 16))
        header.write(b'data')
        header.write(struct.pack('<I', 0x7FFFFFFF))
        yield header.getvalue()
        
        with sd.RawInputStream(samplerate=sample_rate, channels=channels, device=dev_idx, dtype='int16', blocksize=block_size) as stream:
            while True:
                data, overflowed = stream.read(block_size)
                if data:
                    yield bytes(data)
    except Exception as e:
        print("[RemoteAudio] Stream error:", e)

