import sys
import time

try:
    from pynput.keyboard import Controller as PynputKeyboardController, Key
except ImportError:
    PynputKeyboardController = None
    Key = None

import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

_keyboard = PynputKeyboardController() if PynputKeyboardController is not None else None

# Windows native Virtual Key codes for reliable system-level hotkey injection (Alt+Tab, Ctrl+Tab, etc.)
_IS_WINDOWS = sys.platform == "win32"
if _IS_WINDOWS:
    import ctypes
    _user32 = ctypes.windll.user32
    _KEYEVENTF_KEYUP = 0x0002
else:
    _user32 = None
    _KEYEVENTF_KEYUP = 2

_VK_MAP = {
    'ctrl': 0x11,
    'control': 0x11,
    'ctrlleft': 0xA2,
    'ctrlright': 0xA3,
    'alt': 0x12,
    'menu': 0x12,
    'altleft': 0xA4,
    'altright': 0xA5,
    'shift': 0x10,
    'shiftleft': 0xA0,
    'shiftright': 0xA1,
    'win': 0x5B,
    'windows': 0x5B,
    'cmd': 0x5B,
    'super': 0x5B,
    'tab': 0x09,
    'enter': 0x0D,
    'return': 0x0D,
    'esc': 0x1B,
    'escape': 0x1B,
    'space': 0x20,
    'backspace': 0x08,
    'del': 0x2E,
    'delete': 0x2E,
    'insert': 0x2D,
    'home': 0x24,
    'end': 0x23,
    'pageup': 0x21,
    'pagedown': 0x22,
    'up': 0x26,
    'down': 0x28,
    'left': 0x25,
    'right': 0x27,
    'capslock': 0x14,
    'f1': 0x70,
    'f2': 0x71,
    'f3': 0x72,
    'f4': 0x73,
    'f5': 0x74,
    'f6': 0x75,
    'f7': 0x76,
    'f8': 0x77,
    'f9': 0x78,
    'f10': 0x79,
    'f11': 0x7A,
    'f12': 0x7B,
    'prtscr': 0x2C,
    'printscreen': 0x2C,
}

def _to_vk(k: str):
    k_clean = str(k).strip().lower()
    if k_clean.startswith('key:'):
        k_clean = k_clean[4:].strip()
    if k_clean in _VK_MAP:
        return _VK_MAP[k_clean]
    if len(k_clean) == 1:
        ch = k_clean.upper()
        if 'A' <= ch <= 'Z' or '0' <= ch <= '9':
            return ord(ch)
        if _user32 is not None:
            try:
                res = _user32.VkKeyScanW(ord(k_clean))
                if res != -1:
                    return res & 0xFF
            except Exception:
                pass
    return None

# Mapping key name -> (pynput_attr_name_or_None, pyautogui_key_name)
_KEY_MAP = {
    'win': ('cmd', 'win'),
    'windows': ('cmd', 'win'),
    'cmd': ('cmd', 'win'),
    'super': ('cmd', 'win'),
    'enter': ('enter', 'enter'),
    'return': ('enter', 'enter'),
    'backspace': ('backspace', 'backspace'),
    'space': ('space', 'space'),
    'tab': ('tab', 'tab'),
    'esc': ('esc', 'esc'),
    'escape': ('esc', 'esc'),
    'delete': ('delete', 'delete'),
    'del': ('delete', 'delete'),
    'up': ('up', 'up'),
    'down': ('down', 'down'),
    'left': ('left', 'left'),
    'right': ('right', 'right'),
    'home': ('home', 'home'),
    'end': ('end', 'end'),
    'pageup': ('page_up', 'pageup'),
    'pagedown': ('page_down', 'pagedown'),
    'insert': ('insert', 'insert'),
    'capslock': ('caps_lock', 'capslock'),
    'alt': ('alt', 'alt'),
    'altleft': ('alt_l', 'altleft'),
    'altright': ('alt_r', 'altright'),
    'ctrl': ('ctrl', 'ctrl'),
    'control': ('ctrl', 'ctrl'),
    'ctrlleft': ('ctrl_l', 'ctrlleft'),
    'ctrlright': ('ctrl_r', 'ctrlright'),
    'shift': ('shift', 'shift'),
    'shiftleft': ('shift_l', 'shiftleft'),
    'shiftright': ('shift_r', 'shiftright'),
    'f1': ('f1', 'f1'),
    'f2': ('f2', 'f2'),
    'f3': ('f3', 'f3'),
    'f4': ('f4', 'f4'),
    'f5': ('f5', 'f5'),
    'f6': ('f6', 'f6'),
    'f7': ('f7', 'f7'),
    'f8': ('f8', 'f8'),
    'f9': ('f9', 'f9'),
    'f10': ('f10', 'f10'),
    'f11': ('f11', 'f11'),
    'f12': ('f12', 'f12'),
    'prtscr': ('print_screen', 'printscreen'),
    'printscreen': ('print_screen', 'printscreen'),
}

def _get_pynput_key(name: str):
    if Key is None:
        return None
    entry = _KEY_MAP.get(name.lower())
    if entry and entry[0]:
        return getattr(Key, entry[0], None)
    return None

def press_key(name: str) -> None:
    key_name = name.strip().lower()
    if key_name.startswith('key:'):
        key_name = key_name[4:].strip()

    if _user32 is not None:
        vk = _to_vk(key_name)
        if vk is not None:
            try:
                _user32.keybd_event(vk, 0, 0, 0)
                time.sleep(0.02)
                _user32.keybd_event(vk, 0, _KEYEVENTF_KEYUP, 0)
                return
            except Exception:
                pass

    if key_name in _KEY_MAP:
        pynput_attr, pyautogui_name = _KEY_MAP[key_name]
        if _keyboard is not None and pynput_attr:
            p_key = getattr(Key, pynput_attr, None)
            if p_key is not None:
                try:
                    _keyboard.press(p_key)
                    _keyboard.release(p_key)
                    return
                except Exception:
                    pass
        try:
            pyautogui.press(pyautogui_name)
        except Exception:
            pass
        return

    # If key name is unknown, type it as literal text
    type_text_literal(name)

def press_hotkey(*keys: str) -> None:
    """Presses a combination of keys (e.g. ['ctrl', 'tab'] or ['alt', 'tab'] or ['shift', 'a'])."""
    if not keys:
        return
    norm_keys = [str(k).strip().lower() for k in keys if str(k).strip()]
    if not norm_keys:
        return

    # On Windows, native keybd_event works best for Alt+Tab, Ctrl+Tab, Win shortcuts
    if _user32 is not None:
        vks = [_to_vk(k) for k in norm_keys]
        if all(vk is not None for vk in vks):
            try:
                for vk in vks:
                    _user32.keybd_event(vk, 0, 0, 0)
                time.sleep(0.04)
                for vk in reversed(vks):
                    _user32.keybd_event(vk, 0, _KEYEVENTF_KEYUP, 0)
                return
            except Exception:
                pass

    # Check if we can use pynput for native low-level keyboard simulation
    if _keyboard is not None and Key is not None:
        p_keys = []
        for k in norm_keys:
            if k.startswith('key:'):
                k = k[4:].strip()
            pk = _get_pynput_key(k)
            if pk is not None:
                p_keys.append(pk)
            elif len(k) == 1:
                p_keys.append(k)
            else:
                p_keys = None
                break
        if p_keys:
            try:
                for pk in p_keys:
                    _keyboard.press(pk)
                time.sleep(0.02)
                for pk in reversed(p_keys):
                    _keyboard.release(pk)
                return
            except Exception:
                pass

    # Fallback to pyautogui
    valid_keys = []
    for k in norm_keys:
        if k.startswith('key:'):
            k = k[4:].strip()
        if k in _KEY_MAP:
            valid_keys.append(_KEY_MAP[k][1])
        else:
            valid_keys.append(k)
    if valid_keys:
        try:
            pyautogui.hotkey(*valid_keys)
        except Exception:
            pass

def key_down(name: str) -> None:
    """Holds down a key until key_up is called."""
    key_name = str(name).strip().lower()
    if key_name.startswith('key:'):
        key_name = key_name[4:].strip()
    
    if _user32 is not None:
        vk = _to_vk(key_name)
        if vk is not None:
            try:
                _user32.keybd_event(vk, 0, 0, 0)
                return
            except Exception:
                pass

    p_key = _get_pynput_key(key_name)
    if _keyboard is not None:
        if p_key is not None:
            try:
                _keyboard.press(p_key)
                return
            except Exception:
                pass
        elif len(key_name) == 1:
            try:
                _keyboard.press(key_name)
                return
            except Exception:
                pass

    if key_name in _KEY_MAP:
        pyautogui_name = _KEY_MAP[key_name][1]
    else:
        pyautogui_name = key_name
    try:
        pyautogui.keyDown(pyautogui_name)
    except Exception:
        pass

def key_up(name: str) -> None:
    """Releases a key previously held down."""
    key_name = str(name).strip().lower()
    if key_name.startswith('key:'):
        key_name = key_name[4:].strip()
    
    if _user32 is not None:
        vk = _to_vk(key_name)
        if vk is not None:
            try:
                _user32.keybd_event(vk, 0, _KEYEVENTF_KEYUP, 0)
                return
            except Exception:
                pass

    p_key = _get_pynput_key(key_name)
    if _keyboard is not None:
        if p_key is not None:
            try:
                _keyboard.release(p_key)
                return
            except Exception:
                pass
        elif len(key_name) == 1:
            try:
                _keyboard.release(key_name)
                return
            except Exception:
                pass

    if key_name in _KEY_MAP:
        pyautogui_name = _KEY_MAP[key_name][1]
    else:
        pyautogui_name = key_name
    try:
        pyautogui.keyUp(pyautogui_name)
    except Exception:
        pass

def type_text_literal(text: str) -> None:
    """Types raw text characters strictly as literal text without triggering hotkeys or key names."""
    if not text:
        return
    if _keyboard is not None:
        try:
            _keyboard.type(text)
            return
        except Exception:
            pass
    try:
        pyautogui.write(text, interval=0)
    except Exception:
        pass

def type_text(text: str) -> None:
    """Alias for backwards compatibility."""
    type_text_literal(text)

def process_keyboard_command(raw_text: str) -> None:
    """Entry point for incoming UDP keyboard packet strings."""
    if not raw_text:
        return
    text = raw_text.strip()
    lower = text.lower()

    if lower.startswith("down:"):
        key_down(text[5:])
        return

    if lower.startswith("up:"):
        key_up(text[3:])
        return

    if lower.startswith("key:"):
        press_key(text[4:])
        return

    if lower.startswith("text:"):
        type_text_literal(text[5:])
        return

    if lower.startswith("type:"):
        type_text_literal(text[5:])
        return

    if lower.startswith("hotkey:"):
        parts = [p.strip() for p in text[7:].split("+") if p.strip()]
        press_hotkey(*parts)
        return

    # Default fallback: type raw text as literal
    type_text_literal(raw_text)


