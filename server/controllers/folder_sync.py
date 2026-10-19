"""
Folder Sync Controller for Remote PC.
Handles real-time receiving and archiving of synchronized files from mobile devices.
Supports custom Windows destination directory and subfolder preservation.
"""

import os
import sys
import time
import json
import shutil
import threading
from typing import Dict, Any, List, Optional

_LOCK = threading.Lock()
_CONFIG_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json")
_DEFAULT_DESTINATION = os.path.join("D:\\", "SyncedFiles") if os.path.exists("D:\\") else os.path.join(os.path.expanduser("~"), "SyncedFiles")

_sync_state = {
    "destination_dir": _DEFAULT_DESTINATION,
    "total_files_synced": 0,
    "total_bytes_synced": 0,
    "last_sync_time": 0.0,
    "recent_files": []  # List of { filename, relative_path, size_bytes, synced_at }
}

def _load_config():
    global _sync_state
    try:
        if os.path.exists(_CONFIG_PATH):
            with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                custom_dir = data.get("sync_destination_dir")
                if custom_dir and isinstance(custom_dir, str):
                    _sync_state["destination_dir"] = os.path.abspath(custom_dir)
    except Exception as e:
        print(f"[FolderSync] Error loading config: {e}")

    try:
        os.makedirs(_sync_state["destination_dir"], exist_ok=True)
    except Exception:
        pass

def _save_config():
    try:
        data = {}
        if os.path.exists(_CONFIG_PATH):
            try:
                with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
                    data = json.load(f)
            except Exception:
                data = {}
        data["sync_destination_dir"] = _sync_state["destination_dir"]
        with open(_CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=4)
    except Exception as e:
        print(f"[FolderSync] Error saving config: {e}")

_load_config()

def get_disk_free_gb(path: str) -> float:
    try:
        drive = os.path.splitdrive(os.path.abspath(path))[0] or path
        total, used, free = shutil.disk_usage(drive)
        return round(free / (1024 ** 3), 2)
    except Exception:
        return 0.0

def get_sync_status() -> Dict[str, Any]:
    with _LOCK:
        dest = _sync_state["destination_dir"]
        file_count = 0
        total_size = 0
        if os.path.exists(dest):
            for root, _, files in os.walk(dest):
                file_count += len(files)
                for f in files:
                    try:
                        total_size += os.path.getsize(os.path.join(root, f))
                    except OSError:
                        pass

        return {
            "status": "ok",
            "destination_dir": dest,
            "disk_free_gb": get_disk_free_gb(dest),
            "files_count": file_count,
            "total_mb": round(total_size / (1024 * 1024), 2),
            "last_sync_time": _sync_state["last_sync_time"],
            "recent_count": len(_sync_state["recent_files"])
        }

def set_destination_dir(new_path: str) -> Dict[str, Any]:
    with _LOCK:
        if not new_path or not isinstance(new_path, str):
            raise ValueError("Invalid directory path provided.")
        norm = os.path.abspath(new_path.strip())
        os.makedirs(norm, exist_ok=True)
        _sync_state["destination_dir"] = norm
        _save_config()
        return {
            "status": "ok",
            "destination_dir": norm,
            "disk_free_gb": get_disk_free_gb(norm)
        }

def save_uploaded_file(file_bytes: bytes, relative_path: str) -> Dict[str, Any]:
    """Saves a synchronized file sent from the mobile device to the target PC directory."""
    with _LOCK:
        dest_root = _sync_state["destination_dir"]
        clean_rel = relative_path.replace("\\", "/").lstrip("/")
        # Prevent directory traversal attacks
        clean_rel = os.path.normpath(clean_rel).replace("..", "").lstrip(os.sep)

        full_dest = os.path.join(dest_root, clean_rel)
        parent_dir = os.path.dirname(full_dest)
        os.makedirs(parent_dir, exist_ok=True)

        with open(full_dest, "wb") as f:
            f.write(file_bytes)

        size = len(file_bytes)
        now = time.time()
        _sync_state["total_files_synced"] += 1
        _sync_state["total_bytes_synced"] += size
        _sync_state["last_sync_time"] = now

        item = {
            "filename": os.path.basename(full_dest),
            "relative_path": clean_rel,
            "size_bytes": size,
            "size_kb": round(size / 1024, 1),
            "synced_at": now,
            "saved_path": full_dest
        }
        _sync_state["recent_files"].insert(0, item)
        if len(_sync_state["recent_files"]) > 50:
            _sync_state["recent_files"] = _sync_state["recent_files"][:50]

        print(f"[FolderSync] Synced from phone: {clean_rel} ({round(size/1024, 1)} KB) -> {full_dest}")

        return {
            "status": "ok",
            "file_path": full_dest,
            "filename": os.path.basename(full_dest),
            "size_bytes": size,
            "synced_at": now
        }

def get_sync_history() -> List[Dict[str, Any]]:
    with _LOCK:
        dest = _sync_state["destination_dir"]
        items = []
        if os.path.exists(dest):
            for root, _, files in os.walk(dest):
                for f in files:
                    full_path = os.path.join(root, f)
                    try:
                        st = os.stat(full_path)
                        rel_path = os.path.relpath(full_path, dest).replace("\\", "/")
                        items.append({
                            "filename": f,
                            "relative_path": rel_path,
                            "size_bytes": st.st_size,
                            "size_kb": round(st.st_size / 1024, 1),
                            "synced_at": st.st_mtime,
                            "saved_path": full_path
                        })
                    except OSError:
                        pass
        # Sort newest files first
        items.sort(key=lambda x: x["synced_at"], reverse=True)
        return items[:100]
