"""Controllers package for Remote PC Server."""
import sys
import types

# On Linux, python3-tk is often not pre-installed by default in minimal Ubuntu environments.
# PyAutoGUI / mouseinfo / pymsgbox try to import tkinter and call sys.exit() if missing.
# We provide a safe lightweight shim so PyAutoGUI and pynput work smoothly headless or on Ubuntu without requiring sudo apt install python3-tk.
if sys.platform != "win32":
    if "tkinter" not in sys.modules:
        try:
            import tkinter
        except (ImportError, ModuleNotFoundError, SystemExit):
            mock_tk = types.ModuleType("tkinter")
            mock_tk.TkVersion = 8.6
            mock_tk.Tcl = lambda *a, **k: None
            mock_tk.Tk = lambda *a, **k: None
            sys.modules["tkinter"] = mock_tk
    if "mouseinfo" not in sys.modules:
        try:
            import mouseinfo
        except (ImportError, ModuleNotFoundError, SystemExit):
            mock_mouseinfo = types.ModuleType("mouseinfo")
            mock_mouseinfo.position = lambda: (0, 0)
            sys.modules["mouseinfo"] = mock_mouseinfo
