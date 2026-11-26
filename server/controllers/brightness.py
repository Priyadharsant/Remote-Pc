import sys
import subprocess
import re
import threading
import screen_brightness_control as sbc

STEP = 5

_IS_WINDOWS = sys.platform == "win32"
_target_brightness = None
_current_applied_brightness = None
_brightness_event = threading.Event()
_brightness_lock = threading.Lock()
_worker_started = False

def _get_linux_brightness() -> int:
    # 1. GNOME SettingsDaemon DBus
    try:
        res = subprocess.run(
            ['gdbus', 'call', '--session', '--dest', 'org.gnome.SettingsDaemon.Power',
             '--object-path', '/org/gnome/SettingsDaemon/Power',
             '--method', 'org.freedesktop.DBus.Properties.Get',
             'org.gnome.SettingsDaemon.Power.Screen', 'Brightness'],
            capture_output=True, text=True, timeout=1.0
        )
        m = re.search(r'<(\d+)>', res.stdout)
        if m:
            return int(m.group(1))
    except Exception:
        pass

    # 2. brightnessctl
    try:
        cur = subprocess.run(['brightnessctl', 'get'], capture_output=True, text=True, timeout=1.0)
        max_b = subprocess.run(['brightnessctl', 'max'], capture_output=True, text=True, timeout=1.0)
        if cur.returncode == 0 and max_b.returncode == 0:
            return int(round((float(cur.stdout.strip()) / float(max_b.stdout.strip())) * 100))
    except Exception:
        pass

    # 3. Fallback to sbc
    try:
        return int(round(sbc.get_brightness()[0]))
    except Exception:
        return 50

def _set_linux_brightness(level: int) -> None:
    level = max(0, min(level, 100))
    # 1. GNOME SettingsDaemon DBus
    try:
        res = subprocess.run(
            ['gdbus', 'call', '--session', '--dest', 'org.gnome.SettingsDaemon.Power',
             '--object-path', '/org/gnome/SettingsDaemon/Power',
             '--method', 'org.freedesktop.DBus.Properties.Set',
             'org.gnome.SettingsDaemon.Power.Screen', 'Brightness', f'<int32 {level}>'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0
        )
        if res.returncode == 0:
            return
    except Exception:
        pass

    # 2. brightnessctl
    try:
        res = subprocess.run(['brightnessctl', 'set', f'{level}%'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
        if res.returncode == 0:
            return
    except Exception:
        pass

    # 3. Fallback to sbc
    try:
        sbc.set_brightness(level)
    except Exception:
        pass

def _brightness_worker():
    global _target_brightness, _current_applied_brightness
    if _IS_WINDOWS:
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
                if _IS_WINDOWS:
                    sbc.set_brightness(target)
                else:
                    _set_linux_brightness(target)
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
    if _IS_WINDOWS:
        try:
            return int(round(sbc.get_brightness()[0]))
        except Exception:
            return 50
    return _get_linux_brightness()

def set_brightness(level: int) -> int:
    global _target_brightness
    level = max(0, min(level, 100))
    _ensure_worker()
    with _brightness_lock:
        _target_brightness = level
    _brightness_event.set()
    return level
