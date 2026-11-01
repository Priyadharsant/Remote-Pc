import sys
import ctypes
import subprocess
import threading
import time
import pyautogui

_IS_WINDOWS = sys.platform == "win32"

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
    if _IS_WINDOWS:
        pyautogui.press('volumemute')
    else:
        # PipeWire wpctl or ALSA amixer toggle
        try:
            res = subprocess.run(['wpctl', 'set-mute', '@DEFAULT_AUDIO_SINK@', 'toggle'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
            if res.returncode == 0:
                return
        except Exception:
            pass
        try:
            subprocess.run(['amixer', '-q', 'sset', 'Master', 'toggle'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
        except Exception:
            try:
                pyautogui.press('volumemute')
            except Exception:
                pass

def open_start_menu() -> None:
    if _IS_WINDOWS:
        pyautogui.press('win')
    else:
        # Super key toggles GNOME Shell Overview / Dash
        try:
            pyautogui.press('super')
        except Exception:
            try:
                pyautogui.press('win')
            except Exception:
                pass

def show_desktop() -> None:
    if _IS_WINDOWS:
        pyautogui.hotkey('win', 'd')
    else:
        # On Ubuntu / GNOME, Super+D or Ctrl+Alt+D shows desktop
        try:
            pyautogui.hotkey('super', 'd')
        except Exception:
            try:
                pyautogui.hotkey('ctrl', 'alt', 'd')
            except Exception:
                pass

def screen_off() -> None:
    if _IS_WINDOWS:
        try:
            ctypes.windll.user32.SendMessageW(
                HWND_BROADCAST,
                WM_SYSCOMMAND,
                SC_MONITORPOWER,
                MONITOR_POWER_OFF,
            )
        except Exception:
            pass
    else:
        # Linux: try xset dpms, loginctl, or systemctl
        try:
            subprocess.run(['xset', 'dpms', 'force', 'off'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
        except Exception:
            try:
                subprocess.run(['loginctl', 'lock-session'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
            except Exception:
                pass

def lock_pc() -> None:
    if _IS_WINDOWS:
        try:
            ctypes.windll.user32.LockWorkStation()
        except Exception:
            pass
    else:
        # Linux session locking
        for cmd in (['loginctl', 'lock-session'], ['xdg-screensaver', 'lock'], ['gnome-screensaver-command', '-l']):
            try:
                if subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0).returncode == 0:
                    break
            except Exception:
                continue

def sleep_pc() -> None:
    if _IS_WINDOWS:
        subprocess.Popen(['rundll32.exe', 'powrprof.dll,SetSuspendState', '0,1,0'], shell=False)
    else:
        subprocess.Popen(['systemctl', 'suspend'])

def restart_pc() -> None:
    if _IS_WINDOWS:
        subprocess.Popen(['shutdown', '/r', '/t', '0'], shell=False)
    else:
        subprocess.Popen(['systemctl', 'reboot'])

def shutdown_pc() -> None:
    if _IS_WINDOWS:
        subprocess.Popen(['shutdown', '/s', '/t', '0'], shell=False)
    else:
        subprocess.Popen(['systemctl', 'poweroff'])
