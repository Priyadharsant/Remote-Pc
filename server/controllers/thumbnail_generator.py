import os
import io
import sys
import hashlib
import zipfile
from typing import Optional
from PIL import Image, ImageDraw, ImageFont

CACHE_DIR = os.path.expanduser("~/.cache/remote-pc/thumbnails")
os.makedirs(CACHE_DIR, exist_ok=True)

IMAGE_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.gif', '.ico', '.tiff'}
APK_EXTENSIONS = {'.apk', '.aab', '.xapk', '.apks'}
EXE_EXTENSIONS = {'.exe', '.msi'}
UBT_EXTENSIONS = {'.deb', '.appimage', '.desktop'}

def _get_cache_path(file_path: str, mtime: float, size: int) -> str:
    key = hashlib.sha1(f"{file_path}_{mtime}_{size}".encode('utf-8')).hexdigest()
    return os.path.join(CACHE_DIR, f"{key}.png")

def _create_badge(
    bg_color: tuple,
    border_color: tuple,
    badge_text: str,
    badge_bg: tuple,
    title: str,
    icon_type: str = "app"
) -> Image.Image:
    """Generates a sleek, high-resolution 128x128 app thumbnail badge."""
    size = (128, 128)
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Rounded card background
    draw.rounded_rectangle([4, 4, 123, 123], radius=22, fill=bg_color, outline=border_color, width=2)

    # Ambient inner glow
    draw.rounded_rectangle([8, 8, 119, 119], radius=18, outline=(255, 255, 255, 20), width=1)

    # Platform Icon / Symbol in center
    if icon_type == "apk":
        # Draw Android Robot head silhouette
        cx, cy = 64, 52
        # Head arc
        draw.pieslice([cx - 24, cy - 20, cx + 24, cy + 28], 180, 360, fill=(34, 197, 94, 240))
        # Eyes
        draw.ellipse([cx - 12, cy - 4, cx - 6, cy + 2], fill=bg_color)
        draw.ellipse([cx + 6, cy - 4, cx + 12, cy + 2], fill=bg_color)
        # Antennae
        draw.line([cx - 14, cy - 14, cx - 22, cy - 24], fill=(34, 197, 94, 240), width=3)
        draw.line([cx + 14, cy - 14, cx + 22, cy - 24], fill=(34, 197, 94, 240), width=3)
    elif icon_type == "exe":
        # Draw modern Windows 4-grid squares
        cx, cy = 64, 52
        s = 14
        gap = 3
        draw.rounded_rectangle([cx - s - gap, cy - s - gap, cx - gap, cy - gap], radius=2, fill=(0, 164, 239, 240))
        draw.rounded_rectangle([cx + gap, cy - s - gap, cx + s + gap, cy - gap], radius=2, fill=(0, 164, 239, 240))
        draw.rounded_rectangle([cx - s - gap, cy + gap, cx - gap, cy + s + gap], radius=2, fill=(0, 164, 239, 240))
        draw.rounded_rectangle([cx + gap, cy + gap, cx + s + gap, cy + s + gap], radius=2, fill=(0, 164, 239, 240))
    elif icon_type == "ubt":
        # Draw Ubuntu Circular Swirl / Debian badge
        cx, cy = 64, 52
        r = 18
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(233, 84, 32, 255), width=4)
        # 3 Ubuntu dots
        draw.ellipse([cx - r - 4, cy - 4, cx - r + 4, cy + 4], fill=(245, 158, 11, 255))
        draw.ellipse([cx + 8, cy - r - 2, cx + 16, cy - r + 6], fill=(233, 84, 32, 255))
        draw.ellipse([cx + 8, cy + r - 6, cx + 16, cy + r + 2], fill=(233, 84, 32, 255))
    else:
        # Default package / executable symbol
        cx, cy = 64, 52
        draw.rectangle([cx - 18, cy - 14, cx + 18, cy + 18], fill=(56, 189, 248, 220))

    # Badge Tag Pill at bottom (e.g. "APK", "EXE", "UBT")
    bx, by = 64, 98
    bw, bh = 28, 10
    draw.rounded_rectangle([bx - bw, by - bh, bx + bw, by + bh], radius=8, fill=badge_bg)
    draw.text((bx, by), badge_text, anchor="mm", fill=(255, 255, 255, 255))

    return img

def _extract_apk_icon(file_path: str) -> Optional[Image.Image]:
    """Extracts highest-resolution app icon from inside APK."""
    if not zipfile.is_zipfile(file_path):
        return None

    try:
        with zipfile.ZipFile(file_path, "r") as z:
            names = z.namelist()
            # Prioritized icon searches
            search_order = [
                lambda n: 'res/mipmap-xxxhdpi' in n and 'ic_launcher' in n,
                lambda n: 'res/mipmap-xxhdpi' in n and 'ic_launcher' in n,
                lambda n: 'res/mipmap-xhdpi' in n and 'ic_launcher' in n,
                lambda n: 'res/drawable-xxhdpi' in n and 'ic_launcher' in n,
                lambda n: 'res/drawable' in n and 'ic_launcher' in n,
                lambda n: 'res/drawable' in n and ('logo' in n or 'icon' in n),
                lambda n: 'ic_launcher' in n and any(n.endswith(ext) for ext in ('.webp', '.png', '.jpg'))
            ]

            target_entry = None
            for predicate in search_order:
                candidates = [n for n in names if predicate(n) and not n.endswith('.xml')]
                if candidates:
                    # Prefer webp/png
                    candidates.sort(key=lambda x: (not x.endswith('.png'), not x.endswith('.webp')))
                    target_entry = candidates[0]
                    break

            if target_entry:
                data = z.read(target_entry)
                img = Image.open(io.BytesIO(data))
                img = img.convert("RGBA")
                img.thumbnail((128, 128), Image.Resampling.LANCZOS)
                return img
    except Exception:
        pass
    return None

def _extract_desktop_icon(file_path: str) -> Optional[Image.Image]:
    """Extracts Linux icon for .desktop application shortcuts."""
    try:
        icon_val = None
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if line.startswith("Icon="):
                    icon_val = line.split("=", 1)[1].strip()
                    break

        if not icon_val:
            return None

        # If it's a full path
        if os.path.exists(icon_val):
            img = Image.open(icon_val).convert("RGBA")
            img.thumbnail((128, 128), Image.Resampling.LANCZOS)
            return img

        # Search standard system icon directories
        icon_candidates = [
            f"/usr/share/icons/hicolor/512x512/apps/{icon_val}.png",
            f"/usr/share/icons/hicolor/256x256/apps/{icon_val}.png",
            f"/usr/share/icons/hicolor/128x128/apps/{icon_val}.png",
            f"/usr/share/icons/hicolor/scalable/apps/{icon_val}.svg",
            f"/usr/share/pixmaps/{icon_val}.png",
            f"/usr/share/pixmaps/{icon_val}.xpm",
            os.path.expanduser(f"~/.local/share/icons/hicolor/512x512/apps/{icon_val}.png"),
            os.path.expanduser(f"~/.local/share/pixmaps/{icon_val}.png")
        ]
        for candidate in icon_candidates:
            if os.path.exists(candidate) and not candidate.endswith('.svg'):
                try:
                    img = Image.open(candidate).convert("RGBA")
                    img.thumbnail((128, 128), Image.Resampling.LANCZOS)
                    return img
                except Exception:
                    continue
    except Exception:
        pass
    return None

def get_thumbnail(file_path: str) -> bytes:
    """Returns thumbnail PNG bytes for any file (APK, EXE, UBT, images, docs)."""
    if not os.path.exists(file_path):
        # Empty transparent PNG
        img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
        out = io.BytesIO()
        img.save(out, format="PNG")
        return out.getvalue()

    stat = os.stat(file_path)
    cache_path = _get_cache_path(file_path, stat.st_mtime, stat.st_size)
    if os.path.exists(cache_path):
        try:
            with open(cache_path, "rb") as f:
                return f.read()
        except Exception:
            pass

    ext = os.path.splitext(file_path)[1].lower()
    base_name = os.path.basename(file_path)
    result_img: Optional[Image.Image] = None

    # 1. Images
    if ext in IMAGE_EXTENSIONS:
        try:
            with Image.open(file_path) as src:
                result_img = src.convert("RGBA")
                result_img.thumbnail((128, 128), Image.Resampling.LANCZOS)
        except Exception:
            pass

    # 2. Android APK
    elif ext in APK_EXTENSIONS:
        result_img = _extract_apk_icon(file_path)
        if result_img is None:
            result_img = _create_badge(
                bg_color=(15, 30, 20, 255),
                border_color=(34, 197, 94, 200),
                badge_text="APK",
                badge_bg=(22, 101, 52, 255),
                title=base_name,
                icon_type="apk"
            )

    # 3. Windows EXE
    elif ext in EXE_EXTENSIONS:
        result_img = _create_badge(
            bg_color=(12, 25, 45, 255),
            border_color=(14, 165, 233, 200),
            badge_text="EXE",
            badge_bg=(3, 105, 161, 255),
            title=base_name,
            icon_type="exe"
        )

    # 4. Ubuntu / Linux App
    elif ext in UBT_EXTENSIONS:
        if ext == ".desktop":
            result_img = _extract_desktop_icon(file_path)

        if result_img is None:
            tag = "UBT" if ext == ".deb" else ("APP" if ext == ".appimage" else "DESK")
            result_img = _create_badge(
                bg_color=(35, 18, 25, 255),
                border_color=(233, 84, 32, 200),
                badge_text=tag,
                badge_bg=(194, 65, 12, 255),
                title=base_name,
                icon_type="ubt"
            )

    # 5. Default generic thumbnail badge
    if result_img is None:
        result_img = _create_badge(
            bg_color=(15, 23, 42, 255),
            border_color=(100, 116, 139, 150),
            badge_text=ext.replace(".", "").upper()[:4] or "FILE",
            badge_bg=(51, 65, 85, 255),
            title=base_name,
            icon_type="file"
        )

    out = io.BytesIO()
    result_img.save(out, format="PNG", optimize=True)
    thumb_bytes = out.getvalue()

    # Save to disk cache
    try:
        with open(cache_path, "wb") as f:
            f.write(thumb_bytes)
    except Exception:
        pass

    return thumb_bytes
