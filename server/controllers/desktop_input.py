"""Lazy PyAutoGUI access so a missing desktop session cannot stop the server."""

from __future__ import annotations

import glob
import importlib
import os
import subprocess
import sys
import types
from types import ModuleType
from typing import Any


class DesktopSessionUnavailable(RuntimeError):
    """Raised when desktop input is requested outside an accessible X11 session."""


def ensure_x11_display_env() -> None:
    """Detect and configure X11/XWayland display and authority on Linux."""
    if sys.platform == "win32":
        return

    # Ensure DISPLAY is set (default :0)
    if not os.environ.get("DISPLAY"):
        os.environ["DISPLAY"] = ":0"

    # Ensure XAUTHORITY points to an accessible authority file
    xauth = os.environ.get("XAUTHORITY")
    if not xauth or not os.path.exists(xauth):
        uid = os.getuid() if hasattr(os, "getuid") else 1000
        patterns = [
            f"/run/user/{uid}/.mutter-Xwaylandauth*",
            f"/run/user/{uid}/xauth*",
            f"/run/user/{uid}/Xauthority",
            os.path.expanduser("~/.Xauthority"),
        ]
        for pattern in patterns:
            matches = glob.glob(pattern)
            if matches:
                os.environ["XAUTHORITY"] = matches[0]
                break

    # Grant local connections via xhost quietly
    try:
        subprocess.run(["xhost", "+local:"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1.0)
    except Exception:
        pass


def _prepare_pyautogui_environment() -> None:
    if sys.platform == "win32":
        return

    ensure_x11_display_env()

    # Prevent mouseinfo and pymsgbox from calling sys.exit when tkinter is not installed
    if "tkinter" not in sys.modules:
        try:
            import tkinter  # noqa: F401
        except ImportError:
            tk = types.ModuleType("tkinter")
            tk.TkVersion = 8.6
            tk.Event = object
            sys.modules["tkinter"] = tk
            sys.modules["tkinter.ttk"] = types.ModuleType("tkinter.ttk")

    if "mouseinfo" not in sys.modules:
        try:
            import mouseinfo  # noqa: F401
        except (ImportError, SystemExit):
            sys.modules["mouseinfo"] = types.ModuleType("mouseinfo")


# Ensure environment is configured at import time
if sys.platform != "win32":
    ensure_x11_display_env()


class _PyAutoGUIProxy:
    def __init__(self) -> None:
        object.__setattr__(self, "_module", None)
        object.__setattr__(self, "_settings", {"FAILSAFE": False, "PAUSE": 0})

    def _load(self) -> ModuleType:
        module = object.__getattribute__(self, "_module")
        if module is not None:
            return module

        _prepare_pyautogui_environment()

        try:
            module = importlib.import_module("pyautogui")
        except BaseException as exc:
            detail = str(exc).strip() or exc.__class__.__name__
            raise DesktopSessionUnavailable(
                "Desktop input is unavailable. Start Remote PC from your signed-in Ubuntu desktop session "
                "and allow access to its X11/XWayland display. "
                f"Display error: {detail}"
            ) from exc

        for name, value in object.__getattribute__(self, "_settings").items():
            setattr(module, name, value)
        object.__setattr__(self, "_module", module)
        return module

    def __getattr__(self, name: str) -> Any:
        settings = object.__getattribute__(self, "_settings")
        if name in settings:
            return settings[name]
        return getattr(self._load(), name)

    def __setattr__(self, name: str, value: Any) -> None:
        settings = object.__getattribute__(self, "_settings")
        settings[name] = value
        module = object.__getattribute__(self, "_module")
        if module is not None:
            setattr(module, name, value)


pyautogui = _PyAutoGUIProxy()
