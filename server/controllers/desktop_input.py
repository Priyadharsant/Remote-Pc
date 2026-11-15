"""Lazy PyAutoGUI access so a missing desktop session cannot stop the server."""

from __future__ import annotations

import importlib
import sys
from types import ModuleType
from typing import Any


class DesktopSessionUnavailable(RuntimeError):
    """Raised when desktop input is requested outside an accessible X11 session."""


class _PyAutoGUIProxy:
    def __init__(self) -> None:
        object.__setattr__(self, "_module", None)
        object.__setattr__(self, "_settings", {"FAILSAFE": False, "PAUSE": 0})

    def _load(self) -> ModuleType:
        module = object.__getattribute__(self, "_module")
        if module is not None:
            return module

        try:
            module = importlib.import_module("pyautogui")
        except Exception as exc:
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
