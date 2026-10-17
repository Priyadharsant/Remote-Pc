try:
    from pynput.keyboard import Controller as PynputKeyboardController, Key
except ImportError:
    PynputKeyboardController = None
    Key = None

import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

_keyboard = PynputKeyboardController() if PynputKeyboardController is not None else None

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
    """Presses a combination of keys (e.g. ['ctrl', 'c'] or ['win', 'd'])."""
    valid_keys = []
    for k in keys:
        norm = k.strip().lower()
        if norm.startswith('key:'):
            norm = norm[4:].strip()
        if norm in _KEY_MAP:
            valid_keys.append(_KEY_MAP[norm][1])
        else:
            valid_keys.append(norm)
    if valid_keys:
        try:
            pyautogui.hotkey(*valid_keys)
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
        parts = text[7:].split("+")
        press_hotkey(*parts)
        return

    # Default fallback: type raw text as literal
    type_text_literal(raw_text)


