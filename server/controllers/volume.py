import sys
import os
import subprocess
import re
import threading

try:
    import pyautogui
except Exception:
    pyautogui = None

_cached_endpoint = None
_endpoint_lock = threading.Lock()
_IS_WINDOWS = sys.platform == "win32"

def _load_audio_dependencies():
    try:
        from comtypes import CLSCTX_ALL, CoInitialize, CoUninitialize
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        return CLSCTX_ALL, CoInitialize, CoUninitialize, AudioUtilities, IAudioEndpointVolume
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            'Missing dependency: pycaw. Install dependencies with `pip install -r requirements.txt` from the remote_server folder.'
        ) from exc

def _get_cached_endpoint():
    global _cached_endpoint
    if _cached_endpoint is not None:
        return _cached_endpoint
    with _endpoint_lock:
        if _cached_endpoint is not None:
            return _cached_endpoint
        clsctx_all, co_initialize, _, audio_utilities, endpoint_volume_interface = _load_audio_dependencies()
        try:
            co_initialize()
        except Exception:
            pass
        devices = audio_utilities.GetSpeakers()
        activate_device = devices
        for attribute in ('_dev', '_device', 'device'):
            wrapped_device = getattr(devices, attribute, None)
            if hasattr(wrapped_device, 'Activate'):
                activate_device = wrapped_device
                break

        if not hasattr(activate_device, 'Activate'):
            raise RuntimeError('Could not access the Windows audio endpoint volume interface.')

        import ctypes
        from ctypes import POINTER, cast
        interface = activate_device.Activate(endpoint_volume_interface._iid_, clsctx_all, None)
        _cached_endpoint = cast(interface, POINTER(endpoint_volume_interface))
        return _cached_endpoint

def _set_windows_volume(level: int):
    global _cached_endpoint
    try:
        ep = _get_cached_endpoint()
        ep.SetMasterVolumeLevelScalar(level / 100.0, None)
        return
    except Exception:
        with _endpoint_lock:
            _cached_endpoint = None
        try:
            ep = _get_cached_endpoint()
            ep.SetMasterVolumeLevelScalar(level / 100.0, None)
        except Exception:
            pass

def _get_windows_volume() -> int:
    global _cached_endpoint
    try:
        ep = _get_cached_endpoint()
        return int(round(ep.GetMasterVolumeLevelScalar() * 100))
    except Exception:
        with _endpoint_lock:
            _cached_endpoint = None
        try:
            ep = _get_cached_endpoint()
            return int(round(ep.GetMasterVolumeLevelScalar() * 100))
        except Exception:
            return 50

# ==================== Linux Audio Helpers ====================
def _get_linux_volume() -> int:
    # 1. PipeWire / WirePlumber (standard on Ubuntu 22.04 / 24.04+)
    try:
        res = subprocess.run(['wpctl', 'get-volume', '@DEFAULT_AUDIO_SINK@'], capture_output=True, text=True, timeout=1.0)
        m = re.search(r'Volume:\s+([0-9.]+)', res.stdout)
        if m:
            return int(round(float(m.group(1)) * 100))
    except Exception:
        pass

    # 2. PulseAudio pactl
    try:
        res = subprocess.run(['pactl', 'get-sink-volume', '@DEFAULT_SINK@'], capture_output=True, text=True, timeout=1.0)
        m = re.search(r'(\d+)%', res.stdout)
        if m:
            return int(m.group(1))
    except Exception:
        pass

    # 3. ALSA amixer
    for cmd in (['amixer', 'sget', 'Master'], ['amixer', '-D', 'pulse', 'sget', 'Master']):
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=1.0)
            m = re.search(r'\[(\d+)%\]', res.stdout)
            if m:
                return int(m.group(1))
        except Exception:
            pass

    return 50

def _set_linux_volume(level: int) -> None:
    level = max(0, min(level, 100))
    # 1. PipeWire / WirePlumber
    try:
        val = f"{level / 100.0:.2f}"
        res = subprocess.run(['wpctl', 'set-volume', '-l', '1.0', '@DEFAULT_AUDIO_SINK@', val], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
        if res.returncode == 0:
            return
    except Exception:
        pass

    # 2. PulseAudio pactl
    try:
        res = subprocess.run(['pactl', 'set-sink-volume', '@DEFAULT_SINK@', f"{level}%"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
        if res.returncode == 0:
            return
    except Exception:
        pass

    # 3. ALSA amixer
    for cmd in (['amixer', '-q', 'sset', 'Master', f"{level}%"], ['amixer', '-q', '-D', 'pulse', 'sset', 'Master', f"{level}%"]):
        try:
            if subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0).returncode == 0:
                return
        except Exception:
            pass

def volume_up():
    if _IS_WINDOWS and pyautogui is not None:
        pyautogui.press('volumeup')
    else:
        set_volume(min(get_volume() + 5, 100))

def volume_down():
    if _IS_WINDOWS and pyautogui is not None:
        pyautogui.press('volumedown')
    else:
        set_volume(max(get_volume() - 5, 0))

def set_volume(level: int) -> int:
    level = max(0, min(level, 100))
    if _IS_WINDOWS:
        _set_windows_volume(level)
    else:
        _set_linux_volume(level)
    return level

def get_volume() -> int:
    if _IS_WINDOWS:
        return _get_windows_volume()
    return _get_linux_volume()
