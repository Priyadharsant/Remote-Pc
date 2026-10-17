import ctypes
import subprocess
import threading
import time
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

HWND_BROADCAST = 65535
WM_SYSCOMMAND = 274
SC_MONITORPOWER = 61808
MONITOR_POWER_OFF = 2

_cooldown_lock = threading.Lock()
_last_run_at: dict[str, float] = {}

def run_with_cooldown(name: str, action, cooldown_seconds: float) -> bool:
    now = time.monotonic()
    with _cooldown_lock:
        last_run_at = _last_run_at.get(name, 0.0)
        if now - last_run_at < cooldown_seconds:
            return False
        _last_run_at[name] = now
    action()
    return True

def mute_toggle() -> None:
    pyautogui.press('volumemute')

def open_start_menu() -> None:
    pyautogui.press('win')

def show_desktop() -> None:
    pyautogui.hotkey('win', 'd')

def screen_off() -> None:
    ctypes.windll.user32.SendMessageW(
        HWND_BROADCAST,
        WM_SYSCOMMAND,
        SC_MONITORPOWER,
        MONITOR_POWER_OFF,
    )

def lock_pc() -> None:
    ctypes.windll.user32.LockWorkStation()

def sleep_pc() -> None:
    subprocess.Popen(['rundll32.exe', 'powrprof.dll,SetSuspendState', '0,1,0'], shell=False)

def restart_pc() -> None:
    subprocess.Popen(['shutdown', '/r', '/t', '0'], shell=False)

def shutdown_pc() -> None:
    subprocess.Popen(['shutdown', '/s', '/t', '0'], shell=False)
