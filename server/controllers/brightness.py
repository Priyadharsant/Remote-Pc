import threading
import screen_brightness_control as sbc

STEP = 5

_target_brightness = None
_current_applied_brightness = None
_brightness_event = threading.Event()
_brightness_lock = threading.Lock()
_worker_started = False

def _brightness_worker():
    global _target_brightness, _current_applied_brightness
    try:
        import pythoncom
        pythoncom.CoInitialize()
    except Exception:
        pass

    while True:
        _brightness_event.wait()
        _brightness_event.clear()
        while True:
            with _brightness_lock:
                target = _target_brightness
            if target is None or target == _current_applied_brightness:
                break
            try:
                sbc.set_brightness(target)
                _current_applied_brightness = target
            except Exception:
                pass
            with _brightness_lock:
                if _target_brightness == target:
                    break

def _ensure_worker():
    global _worker_started
    if not _worker_started:
        with _brightness_lock:
            if not _worker_started:
                t = threading.Thread(target=_brightness_worker, daemon=True, name="BrightnessWorker")
                t.start()
                _worker_started = True

def brightness_up():
    brightness = get_brightness()
    set_brightness(min(brightness + STEP, 100))

def brightness_down():
    brightness = get_brightness()
    set_brightness(max(brightness - STEP, 0))

def get_brightness() -> int:
    with _brightness_lock:
        if _target_brightness is not None:
            return _target_brightness
    try:
        return int(round(sbc.get_brightness()[0]))
    except Exception:
        return 50

def set_brightness(level: int) -> int:
    global _target_brightness
    level = max(0, min(level, 100))
    _ensure_worker()
    with _brightness_lock:
        _target_brightness = level
    _brightness_event.set()
    return level
