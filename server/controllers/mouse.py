import sys
import threading
from controllers.desktop_input import pyautogui, ensure_x11_display_env

try:
    from pynput.mouse import Controller as PynputMouseController, Button
except Exception:
    PynputMouseController = None
    Button = None

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

_MAX_MOVE_STEP = 120
_MIN_SENSITIVITY = 0.3
_MAX_SENSITIVITY = 4.0
_SCROLL_STEP = 20
_ZOOM_THRESHOLD = 0.12

_mouse_lock = threading.Lock()
_pynput_mouse = None

def _get_pynput_mouse():
    global _pynput_mouse
    if _pynput_mouse is not None:
        return _pynput_mouse
    if PynputMouseController is None:
        return None
    try:
        if sys.platform != "win32":
            ensure_x11_display_env()
        _pynput_mouse = PynputMouseController()
        return _pynput_mouse
    except Exception:
        _pynput_mouse = None
        return None

def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(value, maximum))

def _normalize_delta(delta: float, sensitivity: float) -> int:
    adjusted_sensitivity = _clamp(sensitivity, _MIN_SENSITIVITY, _MAX_SENSITIVITY)
    scaled = delta * adjusted_sensitivity
    if abs(scaled) < 0.2:
        return 0
    return int(round(_clamp(scaled, -_MAX_MOVE_STEP, _MAX_MOVE_STEP)))

def move_cursor(dx: float, dy: float, sensitivity: float = 1.8) -> None:
    move_x = _normalize_delta(dx, sensitivity)
    move_y = _normalize_delta(dy, sensitivity)
    if move_x == 0 and move_y == 0:
        return
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None:
            try:
                mouse_ctrl.move(move_x, move_y)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.moveRel(move_x, move_y, duration=0)
        except Exception:
            pass

def left_click() -> None:
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None and Button is not None:
            try:
                mouse_ctrl.click(Button.left)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.click()
        except Exception:
            pass

def double_click() -> None:
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None and Button is not None:
            try:
                mouse_ctrl.click(Button.left, 2)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.doubleClick()
        except Exception:
            pass

def right_click() -> None:
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None and Button is not None:
            try:
                mouse_ctrl.click(Button.right)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.click(button='right')
        except Exception:
            pass

def drag_start() -> None:
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None and Button is not None:
            try:
                mouse_ctrl.press(Button.left)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.mouseDown()
        except Exception:
            pass

def drag_move(dx: float, dy: float, sensitivity: float = 1.4) -> None:
    move_cursor(dx, dy, sensitivity)

def drag_end() -> None:
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None and Button is not None:
            try:
                mouse_ctrl.release(Button.left)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.mouseUp()
        except Exception:
            pass

def scroll_vertical(delta: float) -> None:
    amount = int(round(_clamp(delta * _SCROLL_STEP, -240, 240)))
    if amount == 0:
        return
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None:
            try:
                steps = max(-5, min(5, int(round(delta * 2)))) or (1 if delta > 0 else -1)
                mouse_ctrl.scroll(0, steps)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.scroll(amount)
        except Exception:
            pass

def scroll_horizontal(delta: float) -> None:
    amount = int(round(_clamp(delta * _SCROLL_STEP, -240, 240)))
    if amount == 0:
        return
    with _mouse_lock:
        mouse_ctrl = _get_pynput_mouse()
        if mouse_ctrl is not None:
            try:
                steps = max(-5, min(5, int(round(delta * 2)))) or (1 if delta > 0 else -1)
                mouse_ctrl.scroll(steps, 0)
                return
            except Exception:
                global _pynput_mouse
                _pynput_mouse = None
        try:
            pyautogui.hscroll(amount)
        except Exception:
            pass

def zoom(delta: float) -> None:
    if abs(delta) < _ZOOM_THRESHOLD:
        return
    key = '+' if delta > 0 else '-'
    with _mouse_lock:
        try:
            pyautogui.hotkey('ctrl', key)
        except Exception:
            pass

def shortcut(name: str) -> None:
    with _mouse_lock:
        try:
            if name == 'taskview':
                if sys.platform == "win32":
                    pyautogui.hotkey('win', 'tab')
                else:
                    pyautogui.hotkey('super', 'tab')
            elif name == 'switch':
                pyautogui.hotkey('alt', 'tab')
            elif name == 'desktop':
                if sys.platform == "win32":
                    pyautogui.hotkey('win', 'd')
                else:
                    pyautogui.hotkey('super', 'd')
            else:
                raise ValueError(f'Unknown shortcut: {name}')
        except Exception:
            pass
