import threading
import pyautogui

try:
    from pynput.mouse import Controller as PynputMouseController, Button
except ImportError:
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
_pynput_mouse = PynputMouseController() if PynputMouseController is not None else None

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
        if _pynput_mouse is not None:
            _pynput_mouse.move(move_x, move_y)
        else:
            pyautogui.moveRel(move_x, move_y, duration=0)

def left_click() -> None:
    with _mouse_lock:
        if _pynput_mouse is not None and Button is not None:
            _pynput_mouse.click(Button.left)
        else:
            try:
                pyautogui.click()
            except Exception:
                pass

def double_click() -> None:
    with _mouse_lock:
        if _pynput_mouse is not None and Button is not None:
            _pynput_mouse.click(Button.left, 2)
        else:
            try:
                pyautogui.doubleClick()
            except Exception:
                pass

def right_click() -> None:
    with _mouse_lock:
        if _pynput_mouse is not None and Button is not None:
            _pynput_mouse.click(Button.right)
        else:
            try:
                pyautogui.click(button='right')
            except Exception:
                pass

def drag_start() -> None:
    with _mouse_lock:
        if _pynput_mouse is not None and Button is not None:
            _pynput_mouse.press(Button.left)
        else:
            try:
                pyautogui.mouseDown()
            except Exception:
                pass

def drag_move(dx: float, dy: float, sensitivity: float = 1.4) -> None:
    move_cursor(dx, dy, sensitivity)

def drag_end() -> None:
    with _mouse_lock:
        if _pynput_mouse is not None and Button is not None:
            _pynput_mouse.release(Button.left)
        else:
            try:
                pyautogui.mouseUp()
            except Exception:
                pass

def scroll_vertical(delta: float) -> None:
    amount = int(round(_clamp(delta * _SCROLL_STEP, -240, 240)))
    if amount == 0:
        return
    with _mouse_lock:
        if _pynput_mouse is not None:
            steps = max(-5, min(5, int(round(delta * 2)))) or (1 if delta > 0 else -1)
            _pynput_mouse.scroll(0, steps)
        else:
            try:
                pyautogui.scroll(amount)
            except Exception:
                pass

def scroll_horizontal(delta: float) -> None:
    amount = int(round(_clamp(delta * _SCROLL_STEP, -240, 240)))
    if amount == 0:
        return
    with _mouse_lock:
        if _pynput_mouse is not None:
            steps = max(-5, min(5, int(round(delta * 2)))) or (1 if delta > 0 else -1)
            _pynput_mouse.scroll(steps, 0)
        else:
            try:
                pyautogui.hscroll(amount)
            except Exception:
                pass

def zoom(delta: float) -> None:
    if abs(delta) < _ZOOM_THRESHOLD:
        return
    key = '+' if delta > 0 else '-'
    with _mouse_lock:
        pyautogui.hotkey('ctrl', key)

def shortcut(name: str) -> None:
    with _mouse_lock:
        if name == 'taskview':
            pyautogui.hotkey('win', 'tab')
        elif name == 'switch':
            pyautogui.hotkey('alt', 'tab')
        elif name == 'desktop':
            pyautogui.hotkey('win', 'd')
        else:
            raise ValueError(f'Unknown shortcut: {name}')
