import sys
import subprocess
import re
from controllers.desktop_input import pyautogui

def _send_mpris(method: str) -> bool:
    if sys.platform == "win32":
        return False
    # 1. Try playerctl first if available
    try:
        res = subprocess.run(['playerctl', method.lower()], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
        if res.returncode == 0:
            return True
    except Exception:
        pass
    # 2. Try gdbus to MPRIS players
    try:
        res = subprocess.run(
            ['gdbus', 'call', '--session', '--dest', 'org.freedesktop.DBus',
             '--object-path', '/org/freedesktop/DBus',
             '--method', 'org.freedesktop.DBus.ListNames'],
            capture_output=True, text=True, timeout=1.0
        )
        if res.returncode == 0:
            mpris_destinations = re.findall(r"'(org\.mpris\.MediaPlayer2\.[^']+)'", res.stdout)
            if mpris_destinations:
                for dest in mpris_destinations:
                    subprocess.run(
                        ['gdbus', 'call', '--session', '--dest', dest,
                         '--object-path', '/org/mpris/MediaPlayer2',
                         '--method', f'org.mpris.MediaPlayer2.Player.{method}'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0
                    )
                return True
    except Exception:
        pass
    return False

def play_pause():
    if not _send_mpris('PlayPause'):
        try:
            pyautogui.press('playpause')
        except Exception:
            pass

def forward():
    forward_10()

def backward():
    backward_10()

def next_track():
    if not _send_mpris('Next'):
        try:
            pyautogui.press('nexttrack')
        except Exception:
            pass

def previous_track():
    if not _send_mpris('Previous'):
        try:
            pyautogui.press('prevtrack')
        except Exception:
            pass

def fullscreen():
    try:
        pyautogui.press('f')
    except Exception:
        pass

def forward_10():
    if sys.platform != "win32":
        try:
            res = subprocess.run(['playerctl', 'position', '10+'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
            if res.returncode == 0:
                return
        except Exception:
            pass
    try:
        pyautogui.press('right')
    except Exception:
        pass

def backward_10():
    if sys.platform != "win32":
        try:
            res = subprocess.run(['playerctl', 'position', '10-'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
            if res.returncode == 0:
                return
        except Exception:
            pass
    try:
        pyautogui.press('left')
    except Exception:
        pass
