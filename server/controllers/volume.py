import ctypes
from ctypes import POINTER, cast
import threading
import pyautogui

_cached_endpoint = None
_endpoint_lock = threading.Lock()

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
        # If COM pointer became invalid (e.g. audio device changed), recreate once
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

def volume_up():
    pyautogui.press('volumeup')

def volume_down():
    pyautogui.press('volumedown')

def set_volume(level: int) -> int:
    level = max(0, min(level, 100))
    _set_windows_volume(level)
    return level

def get_volume() -> int:
    return _get_windows_volume()
