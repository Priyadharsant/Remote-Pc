import os
import sys
import string
import mimetypes
import subprocess
import shutil
import webbrowser
from typing import List, Dict, Any, Generator, Tuple

VIDEO_EXTENSIONS = {
    '.mp4', '.mkv', '.avi', '.mov', '.webm',
    '.flv', '.wmv', '.m4v', '.3gp', '.ts', '.mts'
}
AUDIO_EXTENSIONS = {
    '.mp3', '.wav', '.flac', '.aac', '.ogg', '.m4a', '.wma', '.opus', '.mid'
}
IMAGE_EXTENSIONS = {
    '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg', '.ico', '.tiff'
}
DOC_EXTENSIONS = {
    '.pdf', '.doc', '.docx', '.txt', '.rtf', '.xls', '.xlsx', '.ppt', '.pptx',
    '.csv', '.json', '.xml', '.html', '.md', '.log', '.py', '.java', '.kt',
    '.c', '.cpp', '.h', '.cs', '.js', '.ts', '.jsx', '.tsx', '.dart', '.go',
    '.rs', '.yaml', '.yml', '.sh', '.bat', '.cmd', '.ps1', '.ini', '.cfg',
    '.toml', '.env', '.sql', '.gradle', '.properties'
}
ARCHIVE_EXTENSIONS = {
    '.zip', '.rar', '.7z', '.tar', '.gz', '.bz2', '.iso'
}

def get_drives() -> List[Dict[str, Any]]:
    """Returns available logical drives on Windows, or root on POSIX."""
    drives = []
    if os.name == 'nt':
        for letter in string.ascii_uppercase:
            drive_path = f"{letter}:\\"
            if os.path.exists(drive_path):
                try:
                    total, used, free = shutil.disk_usage(drive_path)
                    drives.append({
                        "name": f"Local Disk ({letter}:)",
                        "path": drive_path,
                        "is_dir": True,
                        "is_drive": True,
                        "free_gb": round(free / (1024 ** 3), 1),
                        "total_gb": round(total / (1024 ** 3), 1)
                    })
                except Exception:
                    drives.append({
                        "name": f"Drive ({letter}:)",
                        "path": drive_path,
                        "is_dir": True,
                        "is_drive": True
                    })
    else:
        drives.append({"name": "Root (/)", "path": "/", "is_dir": True, "is_drive": True})
    return drives

import time

_FOLDER_CACHE: Dict[str, Tuple[float, Dict[str, Any]]] = {}
_FOLDER_CACHE_TTL = 15.0  # 15 seconds in-memory directory cache

def get_file_info(entry: os.DirEntry) -> Dict[str, Any]:
    """Helper to convert DirEntry to Fs item dictionary with fast extension lookup."""
    name = entry.name
    dot_idx = name.rfind('.')
    ext = name[dot_idx:].lower() if dot_idx != -1 else ''

    if ext in VIDEO_EXTENSIONS:
        file_type = "video"
        is_vid, is_aud, is_img = True, False, False
    elif ext in AUDIO_EXTENSIONS:
        file_type = "audio"
        is_vid, is_aud, is_img = False, True, False
    elif ext in IMAGE_EXTENSIONS:
        file_type = "image"
        is_vid, is_aud, is_img = False, False, True
    elif ext in DOC_EXTENSIONS:
        file_type = "document"
        is_vid, is_aud, is_img = False, False, False
    elif ext in ARCHIVE_EXTENSIONS:
        file_type = "archive"
        is_vid, is_aud, is_img = False, False, False
    else:
        file_type = "file"
        is_vid, is_aud, is_img = False, False, False

    try:
        size = entry.stat(follow_symlinks=False).st_size
    except OSError:
        size = 0

    return {
        "name": name,
        "path": entry.path,
        "is_dir": False,
        "file_type": file_type,
        "is_video": is_vid,
        "is_audio": is_aud,
        "is_image": is_img,
        "size": size,
        "ext": ext.lstrip('.')
    }

_FOLDER_CACHE_TTL = 30.0  # 30 seconds in-memory directory cache

def list_folder(path: str, max_items: int = 2000) -> Dict[str, Any]:
    """Lists files and folders in the given directory with high-performance caching and fast scandir."""
    if not path or path.strip() == '':
        return {
            "current_path": "",
            "parent_path": None,
            "items": get_drives(),
            "total_count": len(get_drives()),
            "truncated": False
        }

    norm_path = os.path.abspath(path)
    now = time.time()
    cached = _FOLDER_CACHE.get(norm_path)
    if cached and (now - cached[0] < _FOLDER_CACHE_TTL):
        return cached[1]

    if not os.path.exists(norm_path) or not os.path.isdir(norm_path):
        raise FileNotFoundError(f"Directory not found: {norm_path}")

    parent_path = os.path.dirname(norm_path)
    if parent_path == norm_path:
        parent_path = ""  # Root reached, next back goes to drive list

    dirs_list = []
    files_list = []
    total_found = 0
    truncated = False

    try:
        with os.scandir(norm_path) as entries:
            for entry in entries:
                try:
                    name = entry.name
                    # Skip hidden system files
                    if name.startswith('$') or (name.startswith('.') and not name.startswith('.git')):
                        continue
                    
                    total_found += 1
                    if total_found > max_items:
                        truncated = True
                        continue

                    if entry.is_dir(follow_symlinks=False):
                        dirs_list.append({
                            "name": name,
                            "path": entry.path,
                            "is_dir": True,
                            "file_type": "folder",
                            "is_video": False,
                            "is_audio": False,
                            "is_image": False,
                            "size": 0,
                            "ext": ""
                        })
                    elif entry.is_file(follow_symlinks=False):
                        files_list.append(get_file_info(entry))
                except (PermissionError, OSError):
                    continue
    except PermissionError:
        raise PermissionError(f"Access denied: {norm_path}")

    # Sort directories first, then files alphabetically
    dirs_list.sort(key=lambda x: x["name"].lower())
    files_list.sort(key=lambda x: x["name"].lower())
    items = dirs_list + files_list

    res = {
        "current_path": norm_path,
        "parent_path": parent_path,
        "items": items,
        "total_count": total_found,
        "truncated": truncated
    }
    _FOLDER_CACHE[norm_path] = (now, res)
    return res

def search_folder(path: str, query: str, max_results: int = 150) -> List[Dict[str, Any]]:
    """Searches for files and folders matching query within path."""
    if not path or not os.path.isdir(path):
        return []
    
    q = query.lower().strip()
    if not q:
        return []

    results = []
    try:
        for root, dirs, files in os.walk(path):
            # Exclude hidden or system directories
            dirs[:] = [d for d in dirs if not d.startswith('$') and not d.startswith('.')]
            
            for d in dirs:
                if q in d.lower():
                    results.append({
                        "name": d,
                        "path": os.path.join(root, d),
                        "is_dir": True,
                        "file_type": "folder",
                        "is_video": False,
                        "is_audio": False,
                        "is_image": False,
                        "size": 0,
                        "ext": ""
                    })
                    if len(results) >= max_results:
                        return results

            for f in files:
                if f.startswith('$') or f.startswith('.'):
                    continue
                if q in f.lower():
                    ext = os.path.splitext(f)[1].lower()
                    is_vid = ext in VIDEO_EXTENSIONS
                    is_aud = ext in AUDIO_EXTENSIONS
                    is_img = ext in IMAGE_EXTENSIONS
                    file_p = os.path.join(root, f)
                    try:
                        sz = os.path.getsize(file_p)
                    except OSError:
                        sz = 0
                    results.append({
                        "name": f,
                        "path": file_p,
                        "is_dir": False,
                        "file_type": "video" if is_vid else ("audio" if is_aud else ("image" if is_img else "file")),
                        "is_video": is_vid,
                        "is_audio": is_aud,
                        "is_image": is_img,
                        "size": sz,
                        "ext": ext.lstrip('.')
                    })
                    if len(results) >= max_results:
                        return results
    except Exception:
        pass
    return results

def get_shortcuts() -> List[Dict[str, str]]:
    """Returns standard user directories that exist."""
    shortcuts = []
    user_home = os.path.expanduser("~")
    candidates = [
        ("Downloads", os.path.join(user_home, "Downloads")),
        ("Desktop", os.path.join(user_home, "Desktop")),
        ("Documents", os.path.join(user_home, "Documents")),
        ("Pictures", os.path.join(user_home, "Pictures")),
        ("Music", os.path.join(user_home, "Music")),
        ("Videos", os.path.join(user_home, "Videos")),
    ]
    for name, p in candidates:
        if os.path.exists(p):
            shortcuts.append({"name": name, "path": p})
    return shortcuts

def get_file_range_generator(
    file_path: str, start: int, end: int, chunk_size: int = 1024 * 1024
) -> Generator[bytes, None, None]:
    """Yields chunks of a file between start and end byte offsets."""
    with open(file_path, 'rb') as f:
        f.seek(start)
        remaining = end - start + 1
        while remaining > 0:
            read_size = min(chunk_size, remaining)
            data = f.read(read_size)
            if not data:
                break
            remaining -= len(data)
            yield data

def parse_range_header(range_header: str | None, file_size: int) -> Tuple[int, int]:
    """Parses standard HTTP Range header (bytes=start-end)."""
    if not range_header or not range_header.startswith('bytes='):
        return 0, file_size - 1

    range_spec = range_header.replace('bytes=', '').strip()
    parts = range_spec.split('-')
    start_str = parts[0].strip()
    end_str = parts[1].strip() if len(parts) > 1 else ''

    if start_str and end_str:
        start = int(start_str)
        end = int(end_str)
    elif start_str:
        start = int(start_str)
        end = file_size - 1
    elif end_str:
        # Suffix byte range
        length = int(end_str)
        start = max(0, file_size - length)
        end = file_size - 1
    else:
        start = 0
        end = file_size - 1

    end = min(end, file_size - 1)
    return start, end

def open_media_on_pc(stream_url: str) -> bool:
    """Launches stream URL live in media player or browser on Windows without saving to disk."""
    if not stream_url:
        return False

    lower_url = stream_url.lower()
    is_image = any(img_ext in lower_url for img_ext in ('type=image', '.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.svg'))
    is_doc = any(doc_ext in lower_url for doc_ext in ('type=document', '.pdf', '.txt', '.doc', '.docx', '.csv', '.json', '.xml'))

    # 1. For video or audio: Prioritize VLC for smooth HTTP range-streaming
    if not is_image and not is_doc:
        vlc_paths = [
            r"C:\Program Files\VideoLAN\VLC\vlc.exe",
            r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
            shutil.which("vlc")
        ]
        for vlc in vlc_paths:
            if vlc and os.path.exists(vlc):
                try:
                    creation_flags = subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0
                    subprocess.Popen([vlc, stream_url], creationflags=creation_flags)
                    return True
                except Exception:
                    pass

        # Try Windows Media Player
        wmp_paths = [
            r"C:\Program Files\Windows Media Player\wmplayer.exe",
            r"C:\Program Files (x86)\Windows Media Player\wmplayer.exe"
        ]
        for wmp in wmp_paths:
            if wmp and os.path.exists(wmp):
                try:
                    creation_flags = subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0
                    subprocess.Popen([wmp, stream_url], creationflags=creation_flags)
                    return True
                except Exception:
                    pass

    # 2. For images, documents, or fallback for media: Open directly in default browser or viewer
    try:
        if webbrowser.open(stream_url):
            return True
    except Exception:
        pass

    try:
        os.startfile(stream_url)
        return True
    except Exception:
        pass

    try:
        creation_flags = subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0
        subprocess.Popen(
            ["powershell", "-NoProfile", "-c", f"Start-Process '{stream_url}'"],
            creationflags=creation_flags
        )
        return True
    except Exception:
        return False

def open_file_on_pc(file_path: str) -> bool:
    """Opens a file with its default registered program on Windows."""
    if not os.path.exists(file_path):
        return False
    try:
        os.startfile(file_path)
        return True
    except Exception:
        pass
    try:
        subprocess.Popen(["powershell", "-c", f"Start-Process '{file_path}'"], shell=True)
        return True
    except Exception:
        return False

