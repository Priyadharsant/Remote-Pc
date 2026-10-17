import pyautogui

def play_pause():
    pyautogui.press('playpause')

def forward():
    pyautogui.press('right')

def backward():
    pyautogui.press('left')

def next_track():
    pyautogui.press('nexttrack')

def previous_track():
    pyautogui.press('prevtrack')

def fullscreen():
    pyautogui.press('f')

def forward_10():
    try:
        pyautogui.press('right')
    except Exception:
        pass

def backward_10():
    try:
        pyautogui.press('left')
    except Exception:
        pass
