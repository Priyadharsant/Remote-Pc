import io
import time
import json
import struct
import asyncio
import ctypes
import threading
from typing import List, Dict, Any, Optional, Tuple
from PIL import Image
import mss
import pyautogui
import pyperclip

from controllers import mouse, keyboard

# Ensure thread has access to interactive desktop
def ensure_desktop_access() -> None:
    try:
        user32 = ctypes.windll.user32
        hdesk = user32.OpenDesktopA(b'default', 0, False, 0x01FF)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
    except Exception:
        pass

def get_monitors_info() -> List[Dict[str, Any]]:
    """Enumerate all available physical and virtual monitors."""
    ensure_desktop_access()
    monitors = []
    try:
        with mss.MSS() as sct:
            for idx, m in enumerate(sct.monitors):
                if idx == 0:
                    continue  # idx 0 is virtual combined multi-monitor canvas
                monitors.append({
                    "id": idx,
                    "name": f"Display {idx}" + (" (Primary)" if idx == 1 or m.get("is_primary") else ""),
                    "left": m["left"],
                    "top": m["top"],
                    "width": m["width"],
                    "height": m["height"],
                    "is_primary": idx == 1 or bool(m.get("is_primary"))
                })
    except Exception as e:
        # Fallback to pyautogui primary screen
        w, h = pyautogui.size()
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

def capture_screen_jpeg(monitor_idx: int = 1, quality: int = 65, scale: float = 1.0) -> Tuple[bytes, int, int]:
    """Captures a screenshot of the specified monitor and encodes to JPEG bytes."""
    ensure_desktop_access()
    try:
        with mss.MSS() as sct:
            if monitor_idx >= len(sct.monitors) or monitor_idx < 1:
                monitor_idx = 1
            mon = sct.monitors[monitor_idx]
            sct_img = sct.grab(mon)
            
            # Convert raw BGRA to PIL RGB Image
            img = Image.frombytes("RGB", sct_img.size, sct_img.bgra, "raw", "BGRX")
            
            orig_w, orig_h = img.width, img.height
            if scale < 0.98 and scale > 0.1:
                new_w = max(320, int(orig_w * scale))
                new_h = max(240, int(orig_h * scale))
                img = img.resize((new_w, new_h), Image.Resampling.BILINEAR)
                
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=quality)
            return buf.getvalue(), orig_w, orig_h
    except Exception as e:
        # Fallback using pyautogui screenshot
        try:
            img = pyautogui.screenshot()
            orig_w, orig_h = img.width, img.height
            if scale < 0.98 and scale > 0.1:
                new_w = max(320, int(orig_w * scale))
                new_h = max(240, int(orig_h * scale))
                img = img.resize((new_w, new_h), Image.Resampling.BILINEAR)
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=quality)
            return buf.getvalue(), orig_w, orig_h
        except Exception as e2:
            raise RuntimeError(f"Screen capture failed: {e}; Fallback failed: {e2}")

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

