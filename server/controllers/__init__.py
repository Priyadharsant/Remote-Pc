"""Controllers package setup for minimal and non-graphical Linux installs."""

import sys
import types


# PyAutoGUI and mouseinfo can terminate import with SystemExit or Xlib errors
# when optional Tk/X11 components are missing. Desktop input itself is loaded
# lazily in desktop_input.py; keep optional UI helpers from blocking the server.
if sys.platform != "win32":
    if "tkinter" not in sys.modules:
        try:
            import tkinter  # noqa: F401
        except (Exception, SystemExit):
            tk = types.ModuleType("tkinter")
            tk.TkVersion = 8.6
            tk.Tcl = lambda *args, **kwargs: None
            tk.Tk = lambda *args, **kwargs: None
            sys.modules["tkinter"] = tk

    if "mouseinfo" not in sys.modules:
        try:
            import mouseinfo  # noqa: F401
        except (Exception, SystemExit):
            mouseinfo = types.ModuleType("mouseinfo")
            mouseinfo.position = lambda: (0, 0)
            sys.modules["mouseinfo"] = mouseinfo
