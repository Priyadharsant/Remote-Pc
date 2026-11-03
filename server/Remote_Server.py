from collections.abc import Callable
import base64
import hashlib
import hmac
import asyncio
import json
import logging
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import uuid
import psutil
import ctypes
from ctypes import wintypes
import traceback

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import HTMLResponse, StreamingResponse, FileResponse, Response

from controllers import brightness, keyboard, media, mouse, system_controls, volume, video_stream, remote_desktop, camera_recorder, folder_sync, thumbnail_generator


_LOG_ENTRIES = []
_MAX_LOGS = 250
SERVER_START_TIME = time.time()
_last_net_bytes = 0
_last_net_time = time.time()
_shutdown_timer = None

def get_mac_address() -> str:
    try:
        raw = uuid.getnode()
        mac = ':'.join(['{:02x}'.format((raw >> ele) & 0xff) for ele in range(0, 8*6, 8)][::-1])
        return mac.upper()
    except Exception:
        return "00:00:00:00:00:00"

def get_network_speed_mb() -> float:
    global _last_net_bytes, _last_net_time
    now = time.time()
    dt = max(now - _last_net_time, 0.1)
    try:
        counters = psutil.net_io_counters()
        total_bytes = counters.bytes_sent + counters.bytes_recv
        if _last_net_bytes == 0:
            speed = 0.0
        else:
            diff = max(total_bytes - _last_net_bytes, 0)
            speed = round((diff / (1024 * 1024)) / dt, 1)
        _last_net_bytes = total_bytes
        _last_net_time = now
        return speed
    except Exception:
        return 0.0

def log_server_event(message: str, level: str = "INFO") -> None:
    timestamp = time.strftime("%H:%M:%S")
    entry = {"time": timestamp, "message": message, "level": level}
    _LOG_ENTRIES.append(entry)
    if len(_LOG_ENTRIES) > _MAX_LOGS:
        _LOG_ENTRIES.pop(0)
    print(f"[{level}] [{timestamp}] {message}")

def update_server_config(new_name: str | None = None, new_password: str | None = None) -> None:
    global CONFIG, SERVER_NAME
    cfg = load_config() or {}
    if new_name:
        cfg['name'] = new_name
        SERVER_NAME = new_name
    if new_password:
        cfg.update(hash_password(new_password))
    save_config(cfg)
    CONFIG = cfg
    log_server_event(f"Configuration saved. PC Name: {SERVER_NAME}", "SUCCESS")

app = FastAPI()
app.add_middleware(GZipMiddleware, minimum_size=1000)

BASE_DIR = os.path.dirname(sys.executable) if getattr(sys, 'frozen', False) else os.path.dirname(os.path.abspath(__file__))
RESOURCE_DIR = getattr(sys, '_MEIPASS', BASE_DIR)

def _get_data_dir() -> str:
    try:
        test_file = os.path.join(BASE_DIR, '.write_test')
        with open(test_file, 'w') as f:
            f.write('1')
        os.remove(test_file)
        if not BASE_DIR.startswith(('/opt', '/usr')):
            return BASE_DIR
    except Exception:
        pass
    user_dir = os.path.expanduser('~/.config/remote-pc') if sys.platform != 'win32' else os.path.join(os.environ.get('APPDATA', BASE_DIR), 'Remote-Pc')
    os.makedirs(user_dir, exist_ok=True)
    return user_dir

DATA_DIR = _get_data_dir()
CONFIG_FILE = os.path.join(DATA_DIR, 'config.json')
if not os.path.exists(CONFIG_FILE):
    base_cfg = os.path.join(BASE_DIR, 'config.json')
    if os.path.exists(base_cfg):
        try:
            import shutil
            shutil.copy2(base_cfg, CONFIG_FILE)
        except Exception:
            pass

ICON_FILE = os.path.join(RESOURCE_DIR, 'logo.ico' if sys.platform == 'win32' else 'logo.png')
LOG_FILE = os.path.join(DATA_DIR, 'Remote_Server.log')

HOST = '0.0.0.0'
PORT = 5055
DISCOVERY_PORT = 9999
TOUCHPAD_UDP_PORT = 6000
KEYBOARD_UDP_PORT = 7000
SLIDER_UDP_PORT = 8000
DISCOVERY_REQUEST = 'DISCOVER_PC_REMOTE'
PRESENCE_INTERVAL_SECONDS = 10
PASSWORD_HASH_ITERATIONS = 260000

def setup_crash_logging() -> None:
    try:
        logging.basicConfig(
            filename=LOG_FILE,
            level=logging.ERROR,
            format='%(asctime)s %(levelname)s %(message)s',
        )
    except Exception:
        logging.basicConfig(
            level=logging.ERROR,
            format='%(asctime)s %(levelname)s %(message)s',
        )

    def log_uncaught_exception(exc_type, exc_value, exc_traceback):
        logging.critical('Uncaught exception', exc_info=(exc_type, exc_value, exc_traceback))
        traceback.print_exception(exc_type, exc_value, exc_traceback)

    sys.excepthook = log_uncaught_exception

class DATA_BLOB(ctypes.Structure):
    _fields_ = [('cbData', wintypes.DWORD), ('pbData', ctypes.POINTER(ctypes.c_byte))]

def dpapi_encrypt(data_bytes: bytes) -> bytes | None:
    if sys.platform != 'win32':
        return data_bytes
    try:
        cbData = len(data_bytes)
        pbData = ctypes.cast(ctypes.create_string_buffer(data_bytes, cbData), ctypes.POINTER(ctypes.c_byte))
        data_in = DATA_BLOB(cbData, pbData)
        data_out = DATA_BLOB()
        if ctypes.windll.crypt32.CryptProtectData(ctypes.byref(data_in), 'RemotePC-Vault', None, None, None, 1, ctypes.byref(data_out)):
            ciphertext = ctypes.string_at(data_out.pbData, data_out.cbData)
            ctypes.windll.kernel32.LocalFree(data_out.pbData)
            return ciphertext
    except Exception:
        pass
    return None

def dpapi_decrypt(cipher_bytes: bytes) -> bytes | None:
    if sys.platform != 'win32':
        return cipher_bytes
    try:
        cbData = len(cipher_bytes)
        pbData = ctypes.cast(ctypes.create_string_buffer(cipher_bytes, cbData), ctypes.POINTER(ctypes.c_byte))
        data_in = DATA_BLOB(cbData, pbData)
        data_out = DATA_BLOB()
        if ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(data_in), None, None, None, None, 1, ctypes.byref(data_out)):
            plaintext = ctypes.string_at(data_out.pbData, data_out.cbData)
            ctypes.windll.kernel32.LocalFree(data_out.pbData)
            return plaintext
    except Exception:
        pass
    return None

def save_config(data: dict) -> None:
    try:
        public_cfg = {
            '_security': 'Windows-DPAPI-Encrypted',
            'name': data.get('name', ''),
            'is_configured': bool(data.get('is_configured', False)),
            'strict_auto_connect': bool(data.get('strict_auto_connect', True))
        }
        sensitive_data = {
            'password_hash': data.get('password_hash'),
            'password_salt': data.get('password_salt'),
            'password_iterations': data.get('password_iterations', str(PASSWORD_HASH_ITERATIONS)),
            'trusted_tokens': data.get('trusted_tokens', {}),
            'trusted_devices': data.get('trusted_devices', []),
            'pin': data.get('pin', '482 917')
        }
        raw_sensitive = json.dumps(sensitive_data).encode('utf-8')
        enc_sensitive = dpapi_encrypt(raw_sensitive)
        if enc_sensitive:
            public_cfg['encrypted_vault'] = base64.b64encode(enc_sensitive).decode('ascii')
        else:
            public_cfg.update(sensitive_data)

        with open(CONFIG_FILE, 'w', encoding='utf-8') as file:
            json.dump(public_cfg, file, indent=2)
    except Exception as e:
        log_server_event(f"Error saving config: {e}", "ERROR")

def hash_password(password: str, salt: bytes | None = None) -> dict:
    password_salt = salt if salt is not None else secrets.token_bytes(16)
    password_hash = hashlib.pbkdf2_hmac(
        'sha256',
        password.encode('utf-8'),
        password_salt,
        PASSWORD_HASH_ITERATIONS,
    )
    return {
        'password_hash': base64.b64encode(password_hash).decode('ascii'),
        'password_salt': base64.b64encode(password_salt).decode('ascii'),
        'password_iterations': str(PASSWORD_HASH_ITERATIONS),
    }

def verify_password(password: str, config: dict) -> bool:
    try:
        stored_hash = base64.b64decode(config['password_hash'])
        salt = base64.b64decode(config['password_salt'])
        iterations = int(config.get('password_iterations', PASSWORD_HASH_ITERATIONS))
        supplied_hash = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, iterations)
        return hmac.compare_digest(supplied_hash, stored_hash)
    except (KeyError, ValueError, TypeError):
        return False

def migrate_plaintext_password(config: dict) -> dict:
    password = config.get('password')
    if not isinstance(password, str):
        return config
    migrated_config = dict(config)
    migrated_config.update(hash_password(password))
    migrated_config.pop('password', None)
    save_config(migrated_config)
    return migrated_config

def load_config():
    if not os.path.exists(CONFIG_FILE):
        return None
    try:
        with open(CONFIG_FILE, 'r', encoding='utf-8') as file:
            data = json.load(file)

        if 'encrypted_vault' in data:
            try:
                enc_bytes = base64.b64decode(data['encrypted_vault'])
                dec_bytes = dpapi_decrypt(enc_bytes)
                if dec_bytes:
                    try:
                        vault = json.loads(dec_bytes.decode('utf-8'))
                        data.update(vault)
                        data.pop('encrypted_vault', None)
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        log_server_event("Windows DPAPI vault detected on Linux; initializing fresh local configuration", "INFO")
                        data.pop('encrypted_vault', None)
                        data['is_configured'] = False
                else:
                    log_server_event("DPAPI vault decryption error; running in restricted mode", "ERROR")
            except Exception as e:
                log_server_event(f"Vault processing notice: {e}", "WARN")

        if 'password' in data:
            data = migrate_plaintext_password(data)
        return data
    except Exception as e:
        log_server_event(f"Error reading config: {e}", "ERROR")
        return None

def get_default_pc_name() -> str:
    username = os.environ.get('USERNAME') or os.environ.get('USER')
    if username:
        formatted_username = username.replace('.', ' ').replace('_', ' ').replace('-', ' ').title()
        return f'{formatted_username}-PC'
    hostname = socket.gethostname()
    return hostname or 'PC-Remote'

def setup_config():
    config = load_config()
    if config is not None:
        if 'trusted_tokens' not in config:
            config['trusted_tokens'] = {}
        if 'trusted_devices' not in config:
            config['trusted_devices'] = []
        if 'strict_auto_connect' not in config:
            config['strict_auto_connect'] = True
        if 'is_configured' not in config:
            config['is_configured'] = False
        return config

    default_config = {
        'name': '',
        'is_configured': False,
        'strict_auto_connect': True,
        'trusted_tokens': {},
        'trusted_devices': []
    }
    default_config.update(hash_password('1234'))
    save_config(default_config)
    return default_config

setup_crash_logging()
CONFIG = setup_config()
SERVER_NAME = CONFIG.get('name', '')

HTML = """<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
    <title>Remote PC • Modern Web Deck</title>
    <meta name="theme-color" content="#070B14">
    <style>
        :root {
            --bg-base: #070B14;
            --bg-surface: rgba(15, 23, 42, 0.75);
            --bg-elevated: rgba(30, 41, 59, 0.7);
            --border-subtle: rgba(255, 255, 255, 0.08);
            --border-accent: rgba(56, 189, 248, 0.35);
            --primary: #38BDF8;
            --primary-glow: rgba(56, 189, 248, 0.25);
            --accent-cyan: #00F0FF;
            --accent-emerald: #10B981;
            --accent-rose: #F43F5E;
            --accent-amber: #F59E0B;
            --accent-purple: #A855F7;
            --text-primary: #F8FAFC;
            --text-secondary: #94A3B8;
            --text-muted: #64748B;
            --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Inter", sans-serif;
        }

        * {
            box-sizing: border-box;
            -webkit-tap-highlight-color: transparent;
            margin: 0;
            padding: 0;
        }

        body {
            background-color: var(--bg-base);
            background-image: 
                radial-gradient(circle at 15% 15%, rgba(56, 189, 248, 0.08) 0%, transparent 40%),
                radial-gradient(circle at 85% 85%, rgba(168, 85, 247, 0.06) 0%, transparent 40%);
            color: var(--text-primary);
            font-family: var(--font-family);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: flex-start;
            user-select: none;
            -webkit-user-select: none;
            touch-action: manipulation;
            overflow-x: hidden;
            padding-bottom: 24px;
        }

        /* Ambient Glow & Container */
        .app-container {
            width: 100%;
            max-width: 480px;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            padding: 16px 16px 32px;
            gap: 16px;
        }

        /* Glassmorphic Cards */
        .glass-card {
            background: var(--bg-surface);
            backdrop-filter: blur(20px);
            -webkit-backdrop-filter: blur(20px);
            border: 1px solid var(--border-subtle);
            border-radius: 20px;
            padding: 18px;
            box-shadow: 0 12px 36px -4px rgba(0, 0, 0, 0.45);
            transition: border-color 0.25s, box-shadow 0.25s, transform 0.2s;
        }

        .glass-card:focus-within {
            border-color: var(--border-accent);
        }

        /* Header Bar */
        .app-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 12px 18px;
            background: rgba(11, 17, 32, 0.85);
            backdrop-filter: blur(16px);
            -webkit-backdrop-filter: blur(16px);
            border: 1px solid var(--border-subtle);
            border-radius: 18px;
        }

        .brand-block {
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .brand-icon {
            width: 36px;
            height: 36px;
            border-radius: 10px;
            background: linear-gradient(135deg, #0284C7, #00F0FF);
            display: flex;
            align-items: center;
            justify-content: center;
            color: #fff;
            box-shadow: 0 4px 14px rgba(0, 240, 255, 0.3);
        }

        .brand-icon svg {
            width: 20px;
            height: 20px;
            stroke-width: 2.2;
        }

        .brand-title {
            font-size: 15px;
            font-weight: 700;
            letter-spacing: -0.2px;
            color: var(--text-primary);
        }

        .brand-subtitle {
            font-size: 11px;
            color: var(--text-muted);
            font-weight: 500;
        }

        .status-pill {
            display: flex;
            align-items: center;
            gap: 7px;
            padding: 6px 12px;
            border-radius: 999px;
            background: rgba(16, 185, 129, 0.1);
            border: 1px solid rgba(16, 185, 129, 0.3);
            font-size: 11px;
            font-weight: 700;
            color: var(--accent-emerald);
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }

        .status-pill.offline {
            background: rgba(244, 63, 94, 0.1);
            border-color: rgba(244, 63, 94, 0.3);
            color: var(--accent-rose);
        }

        .status-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: currentColor;
            box-shadow: 0 0 10px currentColor;
            animation: pulse-dot 2s infinite ease-in-out;
        }

        @keyframes pulse-dot {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(0.85); }
        }

        /* Auth View */
        .auth-card {
            display: flex;
            flex-direction: column;
            gap: 16px;
            text-align: center;
            padding: 32px 24px;
        }

        .auth-shield {
            width: 60px;
            height: 60px;
            border-radius: 16px;
            background: rgba(56, 189, 248, 0.12);
            border: 1px solid var(--border-accent);
            display: flex;
            align-items: center;
            justify-content: center;
            color: var(--primary);
            margin: 0 auto 4px;
        }

        .auth-title {
            font-size: 20px;
            font-weight: 700;
            color: var(--text-primary);
        }

        .auth-desc {
            font-size: 13px;
            color: var(--text-secondary);
            line-height: 1.4;
        }

        .auth-input-group {
            position: relative;
            display: flex;
            align-items: center;
        }

        .auth-input {
            width: 100%;
            background: rgba(15, 23, 42, 0.9);
            border: 1.5px solid rgba(255, 255, 255, 0.12);
            border-radius: 14px;
            color: var(--text-primary);
            font-size: 16px;
            padding: 14px 16px;
            outline: none;
            transition: all 0.2s;
            text-align: center;
            letter-spacing: 2px;
        }

        .auth-input:focus {
            border-color: var(--primary);
            box-shadow: 0 0 0 4px var(--primary-glow);
        }

        .btn-connect {
            width: 100%;
            background: linear-gradient(135deg, #0284C7, #00F0FF);
            color: #070B14;
            border: none;
            border-radius: 14px;
            padding: 14px;
            font-size: 15px;
            font-weight: 700;
            letter-spacing: 0.3px;
            cursor: pointer;
            box-shadow: 0 8px 20px rgba(0, 240, 255, 0.25);
            transition: transform 0.15s, opacity 0.15s, box-shadow 0.15s;
        }

        .btn-connect:active {
            transform: scale(0.98);
            opacity: 0.9;
        }

        .auth-status-msg {
            font-size: 12px;
            min-height: 18px;
            color: var(--text-muted);
            font-weight: 500;
            transition: color 0.2s;
        }

        /* Navigation Tabs */
        .tab-bar {
            display: flex;
            background: rgba(11, 17, 32, 0.9);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-subtle);
            border-radius: 16px;
            padding: 5px;
            gap: 4px;
        }

        .tab-btn {
            flex: 1;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 4px;
            padding: 8px 4px;
            border-radius: 12px;
            border: none;
            background: transparent;
            color: var(--text-muted);
            font-size: 11px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s;
        }

        .tab-btn svg {
            width: 18px;
            height: 18px;
            stroke-width: 2;
        }

        .tab-btn.active {
            background: rgba(56, 189, 248, 0.14);
            color: var(--primary);
            border: 1px solid var(--border-accent);
            box-shadow: 0 4px 12px rgba(56, 189, 248, 0.15);
        }

        /* Screens */
        .screen-content {
            display: flex;
            flex-direction: column;
            gap: 14px;
        }

        .hidden {
            display: none !important;
        }

        /* Sliders Card */
        .slider-section {
            display: flex;
            flex-direction: column;
            gap: 16px;
        }

        .slider-group {
            display: flex;
            flex-direction: column;
            gap: 8px;
        }

        .slider-meta {
            display: flex;
            align-items: center;
            justify-content: space-between;
            font-size: 13px;
            font-weight: 600;
            color: var(--text-secondary);
        }

        .slider-val-badge {
            font-size: 12px;
            font-weight: 700;
            color: var(--primary);
            background: rgba(56, 189, 248, 0.12);
            padding: 3px 8px;
            border-radius: 8px;
            border: 1px solid rgba(56, 189, 248, 0.25);
        }

        .slider-row {
            display: flex;
            align-items: center;
            gap: 12px;
        }

        .slider-icon-btn {
            width: 38px;
            height: 38px;
            border-radius: 12px;
            background: var(--bg-elevated);
            border: 1px solid var(--border-subtle);
            color: var(--text-primary);
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            flex-shrink: 0;
            transition: background 0.15s, transform 0.1s;
        }

        .slider-icon-btn:active {
            transform: scale(0.92);
            background: rgba(56, 189, 248, 0.2);
        }

        .slider-input {
            flex: 1;
            -webkit-appearance: none;
            appearance: none;
            height: 8px;
            border-radius: 999px;
            background: rgba(51, 65, 85, 0.6);
            outline: none;
            transition: background 0.2s;
        }

        .slider-input::-webkit-slider-thumb {
            -webkit-appearance: none;
            appearance: none;
            width: 22px;
            height: 22px;
            border-radius: 50%;
            background: linear-gradient(135deg, #38BDF8, #00F0FF);
            cursor: pointer;
            box-shadow: 0 0 10px rgba(0, 240, 255, 0.6);
            border: 2px solid #fff;
            transition: transform 0.15s;
        }

        .slider-input::-webkit-slider-thumb:active {
            transform: scale(1.25);
        }

        /* Media Controller Deck */
        .media-deck {
            display: flex;
            flex-direction: column;
            gap: 12px;
            align-items: center;
            padding: 20px;
        }

        .media-title {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.8px;
        }

        .media-buttons {
            display: flex;
            align-items: center;
            gap: 16px;
        }

        .media-btn {
            width: 52px;
            height: 52px;
            border-radius: 16px;
            background: var(--bg-elevated);
            border: 1px solid var(--border-subtle);
            color: var(--text-primary);
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            transition: transform 0.15s, background 0.15s, border-color 0.15s;
        }

        .media-btn svg {
            width: 22px;
            height: 22px;
        }

        .media-btn:active {
            transform: scale(0.9);
            background: rgba(56, 189, 248, 0.15);
            border-color: var(--border-accent);
        }

        .media-btn.play-main {
            width: 64px;
            height: 64px;
            border-radius: 20px;
            background: linear-gradient(135deg, #0284C7, #00F0FF);
            color: #070B14;
            border: none;
            box-shadow: 0 8px 24px rgba(0, 240, 255, 0.35);
        }

        .media-btn.play-main svg {
            width: 28px;
            height: 28px;
            fill: currentColor;
        }

        /* Action Grid */
        .section-title {
            font-size: 12px;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.8px;
            color: var(--text-muted);
            margin: 6px 0 2px 4px;
            display: flex;
            align-items: center;
            gap: 6px;
        }

        .section-title::after {
            content: "";
            flex: 1;
            height: 1px;
            background: var(--border-subtle);
        }

        .actions-grid {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 10px;
        }

        .action-tile {
            background: var(--bg-surface);
            backdrop-filter: blur(16px);
            border: 1px solid var(--border-subtle);
            border-radius: 16px;
            padding: 16px 10px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            gap: 8px;
            color: var(--text-primary);
            font-size: 12px;
            font-weight: 600;
            cursor: pointer;
            min-height: 84px;
            transition: all 0.15s ease;
        }

        .action-tile svg {
            width: 22px;
            height: 22px;
            stroke-width: 2;
            color: var(--primary);
            transition: transform 0.15s;
        }

        .action-tile:active {
            transform: scale(0.95);
            background: rgba(56, 189, 248, 0.12);
            border-color: var(--border-accent);
        }

        .action-tile.danger svg {
            color: var(--accent-rose);
        }

        .action-tile.danger:active {
            background: rgba(244, 63, 94, 0.15);
            border-color: rgba(244, 63, 94, 0.4);
        }

        .action-tile.warning svg {
            color: var(--accent-amber);
        }

        .action-tile.warning:active {
            background: rgba(245, 158, 11, 0.15);
            border-color: rgba(245, 158, 11, 0.4);
        }

        /* Trackpad Tab */
        .trackpad-deck {
            display: flex;
            flex-direction: column;
            gap: 12px;
            height: 60vh;
            min-height: 380px;
        }

        .trackpad-area {
            flex: 1;
            background: rgba(15, 23, 42, 0.85);
            border: 1.5px dashed rgba(56, 189, 248, 0.25);
            border-radius: 20px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            position: relative;
            touch-action: none;
            overflow: hidden;
            background-image: radial-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px);
            background-size: 20px 20px;
        }

        .trackpad-area:active {
            border-style: solid;
            border-color: var(--primary);
            box-shadow: inset 0 0 20px rgba(56, 189, 248, 0.1);
        }

        .trackpad-hint {
            font-size: 12px;
            font-weight: 500;
            color: var(--text-muted);
            pointer-events: none;
            display: flex;
            align-items: center;
            gap: 6px;
        }

        .trackpad-buttons {
            display: flex;
            gap: 10px;
            height: 64px;
        }

        .click-btn {
            flex: 1;
            border-radius: 16px;
            background: var(--bg-elevated);
            border: 1px solid var(--border-subtle);
            color: var(--text-primary);
            font-size: 13px;
            font-weight: 700;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            cursor: pointer;
            transition: all 0.12s;
        }

        .click-btn:active {
            transform: scale(0.96);
            background: rgba(56, 189, 248, 0.18);
            border-color: var(--border-accent);
        }

        /* Quick Keys Tab */
        .keys-grid {
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 8px;
        }

        .key-btn {
            background: var(--bg-elevated);
            border: 1px solid var(--border-subtle);
            border-radius: 12px;
            padding: 14px 6px;
            text-align: center;
            font-size: 13px;
            font-weight: 700;
            color: var(--text-primary);
            cursor: pointer;
            transition: transform 0.1s, background 0.15s, border-color 0.15s;
        }

        .key-btn:active {
            transform: scale(0.92);
            background: rgba(56, 189, 248, 0.2);
            border-color: var(--border-accent);
        }

        .key-btn.wide {
            grid-column: span 2;
        }

        /* Modal Confirmation */
        .modal-overlay {
            position: fixed;
            inset: 0;
            background: rgba(0, 0, 0, 0.75);
            backdrop-filter: blur(8px);
            -webkit-backdrop-filter: blur(8px);
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
            z-index: 1000;
        }

        .modal-box {
            background: #0F172A;
            border: 1px solid rgba(255, 255, 255, 0.15);
            border-radius: 22px;
            padding: 24px;
            width: 100%;
            max-width: 340px;
            text-align: center;
            box-shadow: 0 20px 40px rgba(0,0,0,0.6);
            display: flex;
            flex-direction: column;
            gap: 14px;
        }

        .modal-title {
            font-size: 18px;
            font-weight: 700;
            color: var(--text-primary);
        }

        .modal-desc {
            font-size: 13px;
            color: var(--text-secondary);
            line-height: 1.4;
        }

        .modal-actions {
            display: flex;
            gap: 10px;
            margin-top: 6px;
        }

        .modal-btn {
            flex: 1;
            padding: 12px;
            border-radius: 12px;
            font-size: 14px;
            font-weight: 700;
            border: none;
            cursor: pointer;
        }

        .modal-btn.cancel {
            background: rgba(255, 255, 255, 0.08);
            color: var(--text-primary);
        }

        .modal-btn.confirm-danger {
            background: var(--accent-rose);
            color: #fff;
        }

        .modal-btn.confirm-warning {
            background: var(--accent-amber);
            color: #070B14;
        }

        /* Toast Feedback */
        .toast {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%) translateY(100px);
            background: rgba(15, 23, 42, 0.95);
            backdrop-filter: blur(12px);
            border: 1px solid var(--border-accent);
            color: var(--text-primary);
            padding: 10px 18px;
            border-radius: 999px;
            font-size: 13px;
            font-weight: 600;
            box-shadow: 0 10px 25px rgba(0,0,0,0.4);
            display: flex;
            align-items: center;
            gap: 8px;
            transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.3s;
            opacity: 0;
            pointer-events: none;
            z-index: 999;
        }

        .toast.show {
            transform: translateX(-50%) translateY(0);
            opacity: 1;
        }
    </style>
</head>
<body>
    <div class="app-container">
        <!-- Modern App Header -->
        <header class="app-header">
            <div class="brand-block">
                <div class="brand-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <rect x="2" y="3" width="20" height="14" rx="2"></rect>
                        <line x1="8" y1="21" x2="16" y2="21"></line>
                        <line x1="12" y1="17" x2="12" y2="21"></line>
                    </svg>
                </div>
                <div>
                    <h1 class="brand-title" id="serverNameTitle">Remote PC</h1>
                    <div class="brand-subtitle" id="connectionInfo">Web Controller</div>
                </div>
            </div>
            <div id="statusPill" class="status-pill offline">
                <span class="status-dot"></span>
                <span id="statusLabel">Offline</span>
            </div>
        </header>

        <!-- Authentication Screen -->
        <div id="authScreen" class="glass-card auth-card">
            <div class="auth-shield">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="28" height="28" stroke-width="2">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                    <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                </svg>
            </div>
            <h2 class="auth-title">Authentication Required</h2>
            <p class="auth-desc">Enter your server password or PIN code to unlock wireless PC remote control deck.</p>
            
            <div class="auth-input-group">
                <input id="passwordInput" class="auth-input" type="password" placeholder="Enter Password / PIN" autocomplete="current-password" autofocus>
            </div>
            <button class="btn-connect" onclick="connectToServer()">Connect to PC</button>
            <div id="authMessage" class="auth-status-msg">Enter password to unlock</div>
        </div>

        <!-- Main Controller View -->
        <div id="mainView" class="hidden">
            <!-- Navigation Tabs -->
            <nav class="tab-bar">
                <button class="tab-btn active" onclick="switchTab('controls')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <line x1="4" y1="21" x2="4" y2="14"></line>
                        <line x1="4" y1="10" x2="4" y2="3"></line>
                        <line x1="12" y1="21" x2="12" y2="12"></line>
                        <line x1="12" y1="8" x2="12" y2="3"></line>
                        <line x1="20" y1="21" x2="20" y2="16"></line>
                        <line x1="20" y1="12" x2="20" y2="3"></line>
                        <line x1="1" y1="14" x2="7" y2="14"></line>
                        <line x1="9" y1="8" x2="15" y2="8"></line>
                        <line x1="17" y1="16" x2="23" y2="16"></line>
                    </svg>
                    <span>Controls</span>
                </button>
                <button class="tab-btn" onclick="switchTab('trackpad')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <rect x="5" y="2" width="14" height="20" rx="7"></rect>
                        <line x1="12" y1="6" x2="12" y2="10"></line>
                    </svg>
                    <span>Trackpad</span>
                </button>
                <button class="tab-btn" onclick="switchTab('keys')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <rect x="2" y="4" width="20" height="16" rx="2"></rect>
                        <line x1="6" y1="8" x2="6" y2="8"></line>
                        <line x1="10" y1="8" x2="10" y2="8"></line>
                        <line x1="14" y1="8" x2="14" y2="8"></line>
                        <line x1="18" y1="8" x2="18" y2="8"></line>
                        <line x1="7" y1="16" x2="17" y2="16"></line>
                    </svg>
                    <span>Keys</span>
                </button>
                <button class="tab-btn" onclick="switchTab('power')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                        <path d="M18.36 6.64a9 9 0 1 1-12.73 0"></path>
                        <line x1="12" y1="2" x2="12" y2="12"></line>
                    </svg>
                    <span>Power</span>
                </button>
            </nav>

            <!-- Tab 1: Controls Screen -->
            <div id="tab-controls" class="screen-content">
                <!-- Hardware Sliders (Volume & Brightness) -->
                <div class="glass-card slider-section">
                    <!-- Volume Slider -->
                    <div class="slider-group">
                        <div class="slider-meta">
                            <span>Speaker Volume</span>
                            <span id="volumeVal" class="slider-val-badge">50%</span>
                        </div>
                        <div class="slider-row">
                            <button class="slider-icon-btn" onclick="send('mute')" title="Mute">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="18" height="18">
                                    <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                                    <line x1="23" y1="9" x2="17" y2="15"></line>
                                    <line x1="17" y1="9" x2="23" y2="15"></line>
                                </svg>
                            </button>
                            <input id="volumeSlider" class="slider-input" type="range" min="0" max="100" value="50" oninput="onVolumeSlide(this.value)">
                            <button class="slider-icon-btn" onclick="send('volup')" title="Volume Up">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="18" height="18">
                                    <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                                    <path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"></path>
                                </svg>
                            </button>
                        </div>
                    </div>

                    <!-- Brightness Slider -->
                    <div class="slider-group">
                        <div class="slider-meta">
                            <span>Display Brightness</span>
                            <span id="brightnessVal" class="slider-val-badge">75%</span>
                        </div>
                        <div class="slider-row">
                            <button class="slider-icon-btn" onclick="send('brightdown')" title="Dim">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="18" height="18">
                                    <circle cx="12" cy="12" r="5"></circle>
                                    <line x1="12" y1="1" x2="12" y2="3"></line>
                                    <line x1="12" y1="21" x2="12" y2="23"></line>
                                </svg>
                            </button>
                            <input id="brightnessSlider" class="slider-input" type="range" min="0" max="100" value="75" oninput="onBrightnessSlide(this.value)">
                            <button class="slider-icon-btn" onclick="send('brightup')" title="Brighten">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="18" height="18">
                                    <circle cx="12" cy="12" r="5"></circle>
                                    <line x1="12" y1="1" x2="12" y2="3"></line>
                                    <line x1="12" y1="21" x2="12" y2="23"></line>
                                    <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
                                    <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
                                    <line x1="1" y1="12" x2="3" y2="12"></line>
                                    <line x1="21" y1="12" x2="23" y2="12"></line>
                                    <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
                                    <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
                                </svg>
                            </button>
                        </div>
                    </div>
                </div>

                <!-- Media Player Deck -->
                <div class="glass-card media-deck">
                    <span class="media-title">Media Controls</span>
                    <div class="media-buttons">
                        <button class="media-btn" onclick="send('prev')" title="Previous Track">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                                <polygon points="19 20 9 12 19 4 19 20"></polygon>
                                <line x1="5" y1="19" x2="5" y2="5"></line>
                            </svg>
                        </button>
                        <button class="media-btn play-main" onclick="send('play')" title="Play / Pause">
                            <svg viewBox="0 0 24 24">
                                <polygon points="5 3 19 12 5 21 5 3"></polygon>
                            </svg>
                        </button>
                        <button class="media-btn" onclick="send('next')" title="Next Track">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                                <polygon points="5 4 15 12 5 20 5 4"></polygon>
                                <line x1="19" y1="5" x2="19" y2="19"></line>
                            </svg>
                        </button>
                    </div>
                </div>

                <!-- Quick Actions Grid -->
                <div class="section-title">Quick Actions</div>
                <div class="actions-grid">
                    <div class="action-tile" onclick="send('desktop')">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                            <line x1="9" y1="3" x2="9" y2="21"></line>
                        </svg>
                        <span>Desktop</span>
                    </div>
                    <div class="action-tile" onclick="send('startmenu')">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <rect x="3" y="3" width="8" height="8" rx="1"></rect>
                            <rect x="13" y="3" width="8" height="8" rx="1"></rect>
                            <rect x="3" y="13" width="8" height="8" rx="1"></rect>
                            <rect x="13" y="13" width="8" height="8" rx="1"></rect>
                        </svg>
                        <span>Start Menu</span>
                    </div>
                    <div class="action-tile" onclick="send('screenoff')">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <rect x="2" y="3" width="20" height="14" rx="2"></rect>
                            <line x1="2" y1="20" x2="22" y2="20"></line>
                        </svg>
                        <span>Screen Off</span>
                    </div>
                    <div class="action-tile" onclick="send('lock')">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                            <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                        </svg>
                        <span>Lock PC</span>
                    </div>
                    <div class="action-tile" onclick="send('mute')">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                            <line x1="23" y1="9" x2="17" y2="15"></line>
                            <line x1="17" y1="9" x2="23" y2="15"></line>
                        </svg>
                        <span>Toggle Mute</span>
                    </div>
                    <div class="action-tile warning" onclick="confirmAction('sleep', 'Sleep PC', 'Put the PC to sleep now?')">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
                        </svg>
                        <span>Sleep</span>
                    </div>
                </div>
            </div>

            <!-- Tab 2: Trackpad Screen -->
            <div id="tab-trackpad" class="screen-content hidden">
                <div class="glass-card trackpad-deck">
                    <div id="touchpadSurface" class="trackpad-area">
                        <div class="trackpad-hint">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="18" height="18">
                                <path d="M12 2v20M2 12h20"></path>
                            </svg>
                            <span>Slide finger to move mouse • Tap to Click</span>
                        </div>
                    </div>
                    <div class="trackpad-buttons">
                        <button class="click-btn" onclick="sendMouseClick('left')">
                            <span>Left Click</span>
                        </button>
                        <button class="click-btn" onclick="sendMouseClick('double')">
                            <span>Double</span>
                        </button>
                        <button class="click-btn" onclick="sendMouseClick('right')">
                            <span>Right Click</span>
                        </button>
                    </div>
                </div>
            </div>

            <!-- Tab 3: Quick Keys -->
            <div id="tab-keys" class="screen-content hidden">
                <div class="section-title">Navigation & Modifiers</div>
                <div class="keys-grid">
                    <button class="key-btn" onclick="sendKey('Escape')">Esc</button>
                    <button class="key-btn" onclick="sendKey('Tab')">Tab</button>
                    <button class="key-btn" onclick="send('startmenu')">Win</button>
                    <button class="key-btn" onclick="sendKey('Backspace')">Del</button>
                    <button class="key-btn wide" onclick="sendKey('Enter')">Enter</button>
                    <button class="key-btn wide" onclick="sendKey('Space')">Space</button>
                </div>

                <div class="section-title">Shortcuts</div>
                <div class="keys-grid">
                    <button class="key-btn" onclick="sendShortcut('copy')">Ctrl+C</button>
                    <button class="key-btn" onclick="sendShortcut('paste')">Ctrl+V</button>
                    <button class="key-btn" onclick="sendShortcut('alttab')">Alt+Tab</button>
                    <button class="key-btn" onclick="send('desktop')">Win+D</button>
                </div>

                <div class="section-title">Arrows</div>
                <div class="keys-grid">
                    <div style="grid-column: span 1"></div>
                    <button class="key-btn" onclick="sendKey('Up')">▲</button>
                    <div style="grid-column: span 2"></div>
                    <button class="key-btn" onclick="sendKey('Left')">◀</button>
                    <button class="key-btn" onclick="sendKey('Down')">▼</button>
                    <button class="key-btn" onclick="sendKey('Right')">▶</button>
                </div>
            </div>

            <!-- Tab 4: Power Screen -->
            <div id="tab-power" class="screen-content hidden">
                <div class="glass-card" style="display:flex; flex-direction:column; gap:12px;">
                    <div class="section-title">System Power Actions</div>
                    <div class="actions-grid" style="grid-template-columns: repeat(2, 1fr);">
                        <div class="action-tile" onclick="send('lock')">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                            </svg>
                            <span>Lock Session</span>
                        </div>
                        <div class="action-tile warning" onclick="confirmAction('sleep', 'Sleep PC', 'Put the computer into low-power sleep mode?')">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
                            </svg>
                            <span>Sleep PC</span>
                        </div>
                        <div class="action-tile warning" onclick="confirmAction('restart', 'Restart PC', 'Reboot the computer immediately?')">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                                <polyline points="23 4 23 10 17 10"></polyline>
                                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
                            </svg>
                            <span>Restart PC</span>
                        </div>
                        <div class="action-tile danger" onclick="confirmAction('shutdown', 'Shutdown PC', 'Shut down the computer completely?')">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
                                <path d="M18.36 6.64a9 9 0 1 1-12.73 0"></path>
                                <line x1="12" y1="2" x2="12" y2="12"></line>
                            </svg>
                            <span>Shutdown</span>
                        </div>
                    </div>
                </div>

                <div class="glass-card" style="text-align: center; margin-top: 8px;">
                    <button class="modal-btn cancel" style="width: 100%;" onclick="disconnect()">
                        Disconnect & Lock Deck
                    </button>
                </div>
            </div>
        </div>
    </div>

    <!-- Confirmation Modal -->
    <div id="confirmModal" class="modal-overlay hidden">
        <div class="modal-box">
            <h3 id="modalTitle" class="modal-title">Confirm Action</h3>
            <p id="modalDesc" class="modal-desc">Are you sure you want to proceed?</p>
            <div class="modal-actions">
                <button class="modal-btn cancel" onclick="closeModal()">Cancel</button>
                <button id="modalConfirmBtn" class="modal-btn confirm-danger" onclick="executeConfirmedAction()">Proceed</button>
            </div>
        </div>
    </div>

    <!-- Feedback Toast -->
    <div id="appToast" class="toast">
        <span id="toastMsg">Action executed</span>
    </div>

    <script>
        const passwordInput = document.getElementById('passwordInput');
        const authScreen = document.getElementById('authScreen');
        const mainView = document.getElementById('mainView');
        const authMessage = document.getElementById('authMessage');
        const statusPill = document.getElementById('statusPill');
        const statusLabel = document.getElementById('statusLabel');
        const serverNameTitle = document.getElementById('serverNameTitle');
        const connectionInfo = document.getElementById('connectionInfo');
        const appToast = document.getElementById('appToast');
        const toastMsg = document.getElementById('toastMsg');

        let authenticated = false;
        let pendingCommand = null;
        let volumeDebounce = null;
        let brightnessDebounce = null;

        // Auto-fill password if remembered
        passwordInput.value = localStorage.getItem('remotePassword') || '';

        function vibrate(ms = 12) {
            if (navigator.vibrate) {
                try { navigator.vibrate(ms); } catch (_) {}
            }
        }

        function showToast(msg) {
            toastMsg.textContent = msg;
            appToast.classList.add('show');
            setTimeout(() => appToast.classList.remove('show'), 2200);
        }

        async function connectToServer() {
            const password = passwordInput.value;
            authMessage.textContent = 'Verifying security credentials...';
            authMessage.style.color = 'var(--primary)';

            try {
                const response = await fetch('/status', {
                    headers: { 'X-Remote-Password': password }
                });

                if (!response.ok) {
                    authenticated = false;
                    localStorage.removeItem('remotePassword');
                    authMessage.textContent = response.status === 401 
                        ? 'Incorrect password. Access denied.' 
                        : 'Connection error (' + response.status + ')';
                    authMessage.style.color = 'var(--accent-rose)';
                    statusPill.className = 'status-pill offline';
                    statusLabel.textContent = 'Offline';
                    vibrate([40, 40, 40]);
                    return;
                }

                const data = await response.json();
                authenticated = true;
                localStorage.setItem('remotePassword', password);

                // Update UI state
                authScreen.classList.add('hidden');
                mainView.classList.remove('hidden');
                statusPill.className = 'status-pill';
                statusLabel.textContent = 'Online';
                vibrate(20);

                if (data.name || data.server) {
                    serverNameTitle.textContent = data.name || data.server;
                }
                connectionInfo.textContent = 'Connected • Port ' + (data.port || 5055);
                showToast('Connected to ' + (data.name || 'Remote PC'));

                // Fetch initial volume & brightness if available
                if (data.volume !== undefined) {
                    document.getElementById('volumeSlider').value = data.volume;
                    document.getElementById('volumeVal').textContent = data.volume + '%';
                }
                if (data.brightness !== undefined) {
                    document.getElementById('brightnessSlider').value = data.brightness;
                    document.getElementById('brightnessVal').textContent = data.brightness + '%';
                }

            } catch (err) {
                authenticated = false;
                authMessage.textContent = 'Failed to reach PC server. Check Wi-Fi.';
                authMessage.style.color = 'var(--accent-rose)';
                statusPill.className = 'status-pill offline';
                statusLabel.textContent = 'Offline';
            }
        }

        function disconnect() {
            authenticated = false;
            localStorage.removeItem('remotePassword');
            mainView.classList.add('hidden');
            authScreen.classList.remove('hidden');
            authMessage.textContent = 'Disconnected. Enter password to connect.';
            authMessage.style.color = 'var(--text-muted)';
            statusPill.className = 'status-pill offline';
            statusLabel.textContent = 'Locked';
            vibrate(15);
        }

        async function send(command, customLabel) {
            vibrate(10);
            if (!authenticated) {
                showToast('Please authenticate first');
                return;
            }

            const password = passwordInput.value;
            try {
                const response = await fetch('/' + command, {
                    headers: { 'X-Remote-Password': password }
                });

                if (!response.ok) {
                    if (response.status === 401) {
                        disconnect();
                        return;
                    }
                    showToast('Command error: ' + command);
                    return;
                }
                showToast(customLabel || ('✓ ' + command.charAt(0).toUpperCase() + command.slice(1)));
            } catch (err) {
                showToast('Network error');
            }
        }

        function onVolumeSlide(val) {
            document.getElementById('volumeVal').textContent = val + '%';
            clearTimeout(volumeDebounce);
            volumeDebounce = setTimeout(() => {
                send('volume/' + val, 'Volume ' + val + '%');
            }, 120);
        }

        function onBrightnessSlide(val) {
            document.getElementById('brightnessVal').textContent = val + '%';
            clearTimeout(brightnessDebounce);
            brightnessDebounce = setTimeout(() => {
                send('brightness/' + val, 'Brightness ' + val + '%');
            }, 120);
        }

        function sendMouseClick(type) {
            vibrate(15);
            send('mouse/' + type + '-click', type.toUpperCase() + ' Click');
        }

        function sendKey(key) {
            vibrate(10);
            send('key/press?key=' + encodeURIComponent(key), 'Key: ' + key);
        }

        function sendShortcut(shortcut) {
            vibrate(12);
            send('shortcut/' + shortcut, 'Shortcut: ' + shortcut);
        }

        // Tab Switching
        function switchTab(tabId) {
            vibrate(8);
            const tabs = ['controls', 'trackpad', 'keys', 'power'];
            tabs.forEach(t => {
                const el = document.getElementById('tab-' + t);
                if (el) el.classList.toggle('hidden', t !== tabId);
            });

            document.querySelectorAll('.tab-btn').forEach((btn, index) => {
                btn.classList.toggle('active', tabs[index] === tabId);
            });
        }

        // Confirmation Modal Dialog
        function confirmAction(cmd, title, desc) {
            vibrate(25);
            pendingCommand = cmd;
            document.getElementById('modalTitle').textContent = title;
            document.getElementById('modalDesc').textContent = desc;
            const confirmBtn = document.getElementById('modalConfirmBtn');
            confirmBtn.className = cmd === 'shutdown' ? 'modal-btn confirm-danger' : 'modal-btn confirm-warning';
            document.getElementById('confirmModal').classList.remove('hidden');
        }

        function closeModal() {
            vibrate(8);
            document.getElementById('confirmModal').classList.add('hidden');
            pendingCommand = null;
        }

        function executeConfirmedAction() {
            if (pendingCommand) {
                const cmd = pendingCommand;
                closeModal();
                send(cmd, 'Executing ' + cmd + '...');
            }
        }

        // Virtual Touchpad Touch Handling
        const touchpad = document.getElementById('touchpadSurface');
        let touchStartX = 0;
        let touchStartY = 0;
        let lastTouchTime = 0;
        let touchMoved = false;

        touchpad.addEventListener('touchstart', (e) => {
            if (e.touches.length === 1) {
                touchStartX = e.touches[0].clientX;
                touchStartY = e.touches[0].clientY;
                touchMoved = false;
            }
        }, { passive: true });

        touchpad.addEventListener('touchmove', (e) => {
            if (e.touches.length === 1 && authenticated) {
                const dx = (e.touches[0].clientX - touchStartX) * 1.5;
                const dy = (e.touches[0].clientY - touchStartY) * 1.5;
                touchStartX = e.touches[0].clientX;
                touchStartY = e.touches[0].clientY;

                if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                    touchMoved = true;
                    // Fire throttled mouse move
                    const password = passwordInput.value;
                    fetch('/mouse/move?dx=' + dx.toFixed(1) + '&dy=' + dy.toFixed(1), {
                        headers: { 'X-Remote-Password': password }
                    }).catch(() => {});
                }
            }
        }, { passive: true });

        touchpad.addEventListener('touchend', (e) => {
            const now = Date.now();
            if (!touchMoved && (now - lastTouchTime < 300)) {
                // Double tap
                sendMouseClick('double');
                lastTouchTime = 0;
            } else if (!touchMoved) {
                // Single tap
                sendMouseClick('left');
                lastTouchTime = now;
            }
        });

        // Password Enter submission
        passwordInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') connectToServer();
        });

        // Auto-connect if password remembered
        if (passwordInput.value) {
            connectToServer();
        }
    </script>
</body>
</html>
"""

def run_command(action: Callable[[], None], message: str) -> dict:
    try:
        log_server_event(f"Action: {message}", "CMD")
        action()
        return {'msg': message}
    except Exception as exc:
        log_server_event(f"Command error: {exc}", "ERROR")
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(exc)) from exc

def run_guarded_command(name: str, action: Callable[[], None], message: str, cooldown_seconds: float) -> dict:
    def guarded_action() -> None:
        executed = system_controls.run_with_cooldown(name, action, cooldown_seconds)
        if not executed:
            log_server_event(f"Action ignored (cooldown): {message}", "WARN")

    return run_command(guarded_action, message)

CONNECTED_CLIENTS = {}
PENDING_PAIRINGS = {}
ACTIVE_LIVE_STREAMS = {}
ACTIVE_FILE_TRANSFERS = {}

def get_active_live_streams() -> list:
    now = time.time()
    expired = [k for k, v in ACTIVE_LIVE_STREAMS.items() if now - v.get('last_active', 0) > 15]
    for k in expired:
        ACTIVE_LIVE_STREAMS.pop(k, None)
    return list(ACTIVE_LIVE_STREAMS.values())

def check_auth(supplied_password: str | None, x_device_token: str | None = None, client_ip: str | None = None) -> bool:
    # If strict_auto_connect is disabled, automatically allow local network connections
    if not CONFIG.get('strict_auto_connect', False):
        return True

    # Check trusted devices by IP or token
    if client_ip:
        for d in CONFIG.get('trusted_devices', []):
            if d.get('ip') == client_ip:
                return True

    if x_device_token:
        trusted_tokens = CONFIG.get('trusted_tokens', {})
        if x_device_token in trusted_tokens:
            return True
        for d in CONFIG.get('trusted_devices', []):
            if d.get('token') == x_device_token:
                return True

    if not CONFIG.get('is_configured', False):
        return True

    if isinstance(supplied_password, str) and supplied_password:
        if verify_password(supplied_password, CONFIG):
            return True
        clean_supplied = supplied_password.replace(' ', '').strip()
        expected_pin = str(CONFIG.get('pin', '482 917')).replace(' ', '').strip()
        if expected_pin and clean_supplied == expected_pin:
            return True

    if not CONFIG.get('strict_auto_connect', True):
        return True

    return False

def require_auth(
    request: Request,
    password: str | None = Query(default=None),
    x_remote_password: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
    x_device_name: str | None = Header(default=None),
    x_device_id: str | None = Header(default=None)
) -> None:
    client_ip = request.client.host if request.client else '127.0.0.1'
    supplied_token = x_device_token or request.headers.get('x-device-token') or request.query_params.get('token')
    supplied_password = x_remote_password or request.headers.get('x-remote-password') or password or request.query_params.get('password')
    device_name = x_device_name or request.headers.get('x-device-name') or "Mobile Device"
    device_id = x_device_id or request.headers.get('x-device-id') or client_ip

    # Local GUI client calls on loopback do not require external auth
    if client_ip in ('127.0.0.1', '::1', 'localhost'):
        return

    # Check device token for authorized auto-connect
    trusted_tokens = CONFIG.get('trusted_tokens', {})
    if supplied_token and supplied_token in trusted_tokens:
        tok_info = trusted_tokens[supplied_token]
        CONNECTED_CLIENTS[client_ip] = {
            'name': tok_info.get('name', device_name),
            'id': tok_info.get('id', device_id),
            'ip': client_ip,
            'token': supplied_token,
            'last_seen': time.time(),
            'auto_connect': True
        }
        return

    # Check password, pairing PIN, or unconfigured server state
    if check_auth(supplied_password, supplied_token, client_ip):
        CONNECTED_CLIENTS[client_ip] = {
            'name': device_name,
            'id': device_id,
            'ip': client_ip,
            'token': supplied_token or '',
            'last_seen': time.time(),
            'auto_connect': True
        }
        return

    log_server_event(f"Unauthorized access attempt blocked from {client_ip}", "WARN")
    raise HTTPException(status_code=401, detail='Authentication required')

def get_lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(('8.8.8.8', 80))
            return sock.getsockname()[0]
    except OSError:
        return '127.0.0.1'

def read_current_volume():
    try:
        return volume.get_volume()
    except Exception:
        return None

def read_current_brightness():
    try:
        return brightness.get_brightness()
    except Exception:
        return None

def get_status() -> dict:
    now = time.time()
    active_cnt = len([c for c in CONNECTED_CLIENTS.values() if now - c.get('last_seen', 0) < 60])
    lan_ip = get_lan_ip()
    pin = CONFIG.get('pin', '482 917')
    
    # Generate QR Code base64
    qr_b64 = ""
    try:
        import qrcode
        import io
        qr_data = f"REMOTEPC|{SERVER_NAME}|{lan_ip}|{PORT}|{pin}"
        qr = qrcode.QRCode(version=1, box_size=4, border=1)
        qr.add_data(qr_data)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        qr_b64 = base64.b64encode(buf.getvalue()).decode('utf-8')
    except Exception:
        pass

    return {
        'status': 'online',
        'server': SERVER_NAME,
        'name': SERVER_NAME,
        'ip': lan_ip,
        'port': PORT,
        'pin': pin,
        'qr_code': qr_b64,
        'volume': read_current_volume(),
        'brightness': read_current_brightness(),
        'connected_clients': active_cnt,
        'clients': list(CONNECTED_CLIENTS.values()),
        'paired_devices_count': len(CONFIG.get('trusted_devices', [])),
        'is_configured': bool(CONFIG.get('is_configured', False)),
        'strict_auto_connect': bool(CONFIG.get('strict_auto_connect', True)),
        'active_streams': get_active_live_streams()
    }

def get_discovery_response() -> str:
    status = get_status()
    return f"{SERVER_NAME}|{PORT}|{status['volume']}|{status['brightness']}"

def discovery_server() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        sock.bind(('', DISCOVERY_PORT))
        print(f'Discovery server running on UDP {DISCOVERY_PORT}')
        while True:
            try:
                data, address = sock.recvfrom(1024)
                message = data.decode(errors='ignore').strip()
                if message == DISCOVERY_REQUEST:
                    log_server_event(f"Discovery ping received from {address[0]}", "INFO")
                    resp = get_discovery_response().encode()
                    sock.sendto(resp, address)
                    try:
                        sock.sendto(resp, (address[0], DISCOVERY_PORT))
                    except Exception:
                        pass
            except Exception:
                pass

def start_discovery_server() -> None:
    thread = threading.Thread(target=discovery_server, daemon=True)
    thread.start()

def broadcast_presence() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except Exception:
            pass
        while True:
            try:
                status = get_status()
                message = f"REMOTE_SERVER|{SERVER_NAME}|{PORT}|{status['volume']}|{status['brightness']}"
                sock.sendto(message.encode(), ('255.255.255.255', DISCOVERY_PORT))
            except Exception:
                pass
            time.sleep(PRESENCE_INTERVAL_SECONDS)

def start_presence_broadcast() -> None:
    thread = threading.Thread(target=broadcast_presence, daemon=True)
    thread.start()


def touchpad_udp_server() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        sock.bind(('', TOUCHPAD_UDP_PORT))
        print(f'Touchpad UDP server running on UDP {TOUCHPAD_UDP_PORT}')
        while True:
            data, _address = sock.recvfrom(256)
            try:
                text = data.decode('utf-8', errors='ignore').strip()
                if text.startswith('{'):
                    payload = json.loads(text)
                    dx = float(payload.get('dx', 0))
                    dy = float(payload.get('dy', 0))
                else:
                    dx_text, dy_text = text.split(',', 1)
                    dx = float(dx_text)
                    dy = float(dy_text)
                mouse.move_cursor(dx, dy, sensitivity=1.0)
            except Exception:
                pass

def start_touchpad_udp_server() -> None:
    thread = threading.Thread(target=touchpad_udp_server, daemon=True)
    thread.start()

def keyboard_udp_server() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        except Exception:
            pass
        sock.bind(('', KEYBOARD_UDP_PORT))
        print(f'Keyboard UDP server running on UDP {KEYBOARD_UDP_PORT}')
        while True:
            data, _address = sock.recvfrom(1024)
            try:
                keyboard.process_keyboard_command(data.decode('utf-8'))
            except Exception:
                pass

def start_keyboard_udp_server() -> None:
    thread = threading.Thread(target=keyboard_udp_server, daemon=True)
    thread.start()

def slider_udp_server() -> None:
    try:
        from comtypes import CoInitialize
        CoInitialize()
    except Exception:
        pass
    last_values = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65536)
        except Exception:
            pass
        sock.bind(('', SLIDER_UDP_PORT))
        print(f'Slider UDP server running on UDP {SLIDER_UDP_PORT}')
        while True:
            try:
                data, _address = sock.recvfrom(64)
                parts = data.decode('ascii', errors='ignore').split(':', 1)
                if len(parts) != 2:
                    continue
                command, value_text = parts
                value = int(float(value_text))
                if command in ('volume', 'vol'):
                    if last_values.get('vol') != value:
                        last_values['vol'] = value
                        volume.set_volume(value)
                elif command in ('brightness', 'bright'):
                    if last_values.get('bright') != value:
                        last_values['bright'] = value
                        brightness.set_brightness(value)
            except Exception:
                pass

def start_slider_udp_server() -> None:
    thread = threading.Thread(target=slider_udp_server, daemon=True)
    thread.start()

@app.api_route('/', methods=['GET'])
def home() -> HTMLResponse:
    return HTMLResponse(HTML)

@app.get('/ping')
def ping_server() -> dict:
    return {'status': 'online', 'server': SERVER_NAME, 'name': SERVER_NAME, 'port': PORT}

@app.api_route('/phone/heartbeat', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def phone_heartbeat() -> dict:
    return {'status': 'connected', 'server': SERVER_NAME, 'name': SERVER_NAME}

@app.api_route('/status', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def status() -> dict:
    return get_status()

@app.api_route('/play', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/playpause', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media_play_pause', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media-play-pause', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def play() -> dict:
    return run_command(media.play_pause, 'play/pause')

@app.api_route('/forward', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/forward10', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media/forward10', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media-forward-10', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def forward() -> dict:
    return run_command(media.forward_10, 'forward 10s')

@app.api_route('/back', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/backward', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/backward10', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media/backward10', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media-backward-10', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def back() -> dict:
    return run_command(media.backward_10, 'back 10s')

@app.api_route('/next', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media_next', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media-next', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def next_track() -> dict:
    return run_command(media.next_track, 'next track')

@app.api_route('/previous', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/prev', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media_prev', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/media-prev', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def previous_track() -> dict:
    return run_command(media.previous_track, 'previous track')

@app.api_route('/fullscreen', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def fullscreen() -> dict:
    return run_command(media.fullscreen, 'fullscreen')

@app.api_route('/volup', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def volup() -> dict:
    return run_command(volume.volume_up, 'volume up')

@app.api_route('/voldown', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def voldown() -> dict:
    return run_command(volume.volume_down, 'volume down')

@app.api_route('/mute', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mute() -> dict:
    return run_guarded_command('mute', system_controls.mute_toggle, 'mute toggle', 0.35)

@app.api_route('/brightup', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def brightup() -> dict:
    return run_command(brightness.brightness_up, 'brightness up')

@app.api_route('/brightdown', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def brightdown() -> dict:
    return run_command(brightness.brightness_down, 'brightness down')

@app.api_route('/startmenu', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def startmenu() -> dict:
    return run_guarded_command('startmenu', system_controls.open_start_menu, 'start menu', 0.35)

@app.api_route('/desktop', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def desktop() -> dict:
    return run_guarded_command('desktop', system_controls.show_desktop, 'show desktop', 0.5)

@app.api_route('/screenoff', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def screenoff() -> dict:
    return run_guarded_command('screenoff', system_controls.screen_off, 'screen off', 2.0)

@app.api_route('/lock', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def lock() -> dict:
    return run_guarded_command('lock', system_controls.lock_pc, 'lock pc', 2.0)

@app.api_route('/sleep', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def sleep() -> dict:
    return run_guarded_command('sleep', system_controls.sleep_pc, 'sleep pc', 3.0)

@app.api_route('/restart', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def restart() -> dict:
    return run_guarded_command('restart', system_controls.restart_pc, 'restart pc', 5.0)

@app.api_route('/shutdown', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def shutdown() -> dict:
    return run_guarded_command('shutdown', system_controls.shutdown_pc, 'shutdown pc', 5.0)

@app.api_route('/brightness/{level}', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def set_brightness(level: int) -> dict:
    try:
        applied_level = brightness.set_brightness(level)
        return {'msg': f'brightness set to {applied_level}%'}
    except Exception as exc:
        traceback.print_exc()
        raise HTTPException(
            status_code=500,
            detail=f'Could not set brightness directly: {exc}.',
        ) from exc

@app.api_route('/volume/{level}', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def set_volume(level: int) -> dict:
    try:
        applied_level = volume.set_volume(level)
        return {'msg': f'volume set to {applied_level}%'}
    except Exception as exc:
        traceback.print_exc()
        raise HTTPException(
            status_code=500,
            detail=f'Could not set volume directly: {exc}. Run `pip install -r requirements.txt` and restart the server.',
        ) from exc

@app.api_route('/mouse/move', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_move(
    dx: float = Query(...),
    dy: float = Query(...),
    sensitivity: float = Query(default=1.8),
) -> dict:
    return run_command(lambda: mouse.move_cursor(dx, dy, sensitivity), 'mouse moved')

@app.api_route('/mouse/click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_click(button: str = Query(default='left')) -> dict:
    if button == 'right':
        return run_command(mouse.right_click, 'right click')
    elif button == 'double':
        return run_command(mouse.double_click, 'double click')
    return run_command(mouse.left_click, 'left click')

@app.api_route('/mouse/left-click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/mouse/left_click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_left_click() -> dict:
    return run_command(mouse.left_click, 'left click')

@app.api_route('/mouse/double-click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/mouse/double_click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_double_click() -> dict:
    return run_command(mouse.double_click, 'double click')

@app.api_route('/mouse/right-click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/mouse/right_click', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_right_click() -> dict:
    return run_command(mouse.right_click, 'right click')

@app.api_route('/mouse/drag/start', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/mouse/drag_start', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_drag_start() -> dict:
    return run_command(mouse.drag_start, 'drag started')

@app.api_route('/mouse/drag/move', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/mouse/drag_move', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_drag_move(
    dx: float = Query(...),
    dy: float = Query(...),
    sensitivity: float = Query(default=1.4),
) -> dict:
    return run_command(lambda: mouse.drag_move(dx, dy, sensitivity), 'drag moved')

@app.api_route('/mouse/drag/end', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
@app.api_route('/mouse/drag_end', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_drag_end() -> dict:
    return run_command(mouse.drag_end, 'drag ended')

@app.api_route('/mouse/scroll', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_scroll(delta: float = Query(...)) -> dict:
    return run_command(lambda: mouse.scroll_vertical(delta), 'scroll')

@app.api_route('/mouse/hscroll', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_hscroll(delta: float = Query(...)) -> dict:
    return run_command(lambda: mouse.scroll_horizontal(delta), 'horizontal scroll')

@app.api_route('/mouse/zoom', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def mouse_zoom(delta: float = Query(...)) -> dict:
    return run_command(lambda: mouse.zoom(delta), 'zoom')

@app.api_route('/shortcut/{name}', methods=['GET', 'POST'], dependencies=[Depends(require_auth)])
def trigger_shortcut(name: str) -> dict:
    system_shortcuts = {
        'startmenu': system_controls.open_start_menu,
        'win': system_controls.open_start_menu,
        'desktop': system_controls.show_desktop,
        'showdesktop': system_controls.show_desktop,
        'screenoff': system_controls.screen_off,
        'lock': system_controls.lock_pc,
        'sleep': system_controls.sleep_pc,
        'restart': system_controls.restart_pc,
        'shutdown': system_controls.shutdown_pc,
    }
    action = system_shortcuts.get(name)
    if action is not None:
        return run_command(action, f'shortcut {name}')
    return run_command(lambda: mouse.shortcut(name), f'shortcut {name}')

@app.get('/fs/drives', dependencies=[Depends(require_auth)])
def list_drives() -> dict:
    drives = [d["path"] for d in video_stream.get_drives()]
    shortcuts = video_stream.get_shortcuts()
    return {"drives": drives, "shortcuts": shortcuts}

@app.get('/fs/list', dependencies=[Depends(require_auth)])
def list_fs(path: str = Query(default="")) -> dict:
    try:
        data = video_stream.list_folder(path)
        active_streams = get_active_live_streams()
        active_paths = {os.path.abspath(s.get('path', '')) for s in active_streams if s.get('path')}
        active_names = {s.get('filename') for s in active_streams if s.get('filename')}
        for it in data.get('items', []):
            item_path = os.path.abspath(it.get('path', '')) if it.get('path') else ''
            it['is_live'] = (item_path in active_paths) or (it.get('name') in active_names)
        data['active_streams'] = active_streams
        return data
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="Directory not found")
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get('/fs/active-streams')
def get_active_streams_endpoint() -> dict:
    return {"status": "ok", "active_streams": get_active_live_streams()}

@app.post('/fs/stream/stop')
def stop_active_stream(path: str = Query(default=""), id: str = Query(default="")) -> dict:
    to_delete = []
    norm_path = os.path.abspath(path) if path else ""
    for k, v in list(ACTIVE_LIVE_STREAMS.items()):
        if id and (k == id or v.get("id") == id):
            to_delete.append(k)
        elif path and (v.get("path") == path or (norm_path and os.path.abspath(v.get("path", "")) == norm_path) or v.get("filename") == os.path.basename(path)):
            to_delete.append(k)
    for k in to_delete:
        ACTIVE_LIVE_STREAMS.pop(k, None)
    return {"status": "ok", "stopped": len(to_delete)}

@app.get('/fs/stream')
@app.get('/media/stream')
def stream_video(
    request: Request,
    path: str = Query(...),
    password: str | None = Query(default=None),
    x_remote_password: str | None = Header(default=None)
):
    require_auth(request=request, password=password, x_remote_password=x_remote_password)
    if not os.path.exists(path) or not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="Video file not found")

    file_size = os.path.getsize(path)
    range_header = request.headers.get("Range")

    import mimetypes
    content_type, _ = mimetypes.guess_type(path)
    if not content_type:
        content_type = "video/mp4"

    norm_path = os.path.abspath(path)
    client_ip = request.client.host if request.client else "unknown"
    stream_key = f"{norm_path}::{client_ip}"
    ACTIVE_LIVE_STREAMS[stream_key] = {
        "id": stream_key,
        "path": norm_path,
        "filename": os.path.basename(norm_path),
        "client": client_ip,
        "started_at": ACTIVE_LIVE_STREAMS.get(stream_key, {}).get("started_at", time.time()),
        "last_active": time.time(),
        "type": "video"
    }

    def tracked_stream_generator(gen):
        try:
            for chunk in gen:
                if stream_key in ACTIVE_LIVE_STREAMS:
                    ACTIVE_LIVE_STREAMS[stream_key]["last_active"] = time.time()
                yield chunk
        finally:
            if stream_key in ACTIVE_LIVE_STREAMS:
                ACTIVE_LIVE_STREAMS[stream_key]["last_active"] = time.time()

    if range_header:
        start, end = video_stream.parse_range_header(range_header, file_size)
        content_length = end - start + 1
        headers = {
            "Content-Range": f"bytes {start}-{end}/{file_size}",
            "Accept-Ranges": "bytes",
            "Content-Length": str(content_length),
            "Content-Type": content_type,
        }
        return StreamingResponse(
            tracked_stream_generator(video_stream.get_file_range_generator(norm_path, start, end)),
            status_code=206,
            headers=headers,
            media_type=content_type,
        )
    else:
        headers = {
            "Accept-Ranges": "bytes",
            "Content-Length": str(file_size),
            "Content-Type": content_type,
        }
        return StreamingResponse(
            tracked_stream_generator(video_stream.get_file_range_generator(norm_path, 0, file_size - 1)),
            status_code=200,
            headers=headers,
            media_type=content_type,
        )

@app.get('/fs/download')
def download_file(
    request: Request,
    path: str = Query(...),
    password: str | None = Query(default=None),
    x_remote_password: str | None = Header(default=None)
):
    require_auth(request=request, password=password, x_remote_password=x_remote_password)
    if not os.path.exists(path) or not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="File not found")
    filename = os.path.basename(path)
    return FileResponse(
        path,
        filename=filename,
        media_type="application/octet-stream"
    )

@app.get('/fs/thumbnail')
def get_fs_thumbnail(
    request: Request,
    path: str = Query(...),
    password: str | None = Query(default=None),
    x_remote_password: str | None = Header(default=None),
    x_device_token: str | None = Header(default=None),
    token: str | None = Query(default=None)
):
    require_auth(request=request, password=password, x_remote_password=x_remote_password, x_device_token=x_device_token or token)
    if not os.path.exists(path) or not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="File not found")
    try:
        thumb_bytes = thumbnail_generator.get_thumbnail(path)
        return Response(
            content=thumb_bytes,
            media_type="image/png",
            headers={"Cache-Control": "public, max-age=86400"}
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post('/fs/upload', dependencies=[Depends(require_auth)])
async def upload_file(
    request: Request,
    filename: str = Query(default=""),
    target_dir: str = Query(default="")
):
    user_home = os.path.expanduser("~")
    dest_dir = target_dir if (target_dir and os.path.isdir(target_dir)) else os.path.join(user_home, "Downloads", "From_Phone")
    os.makedirs(dest_dir, exist_ok=True)

    clean_name = os.path.basename(filename) if filename else f"upload_{int(time.time())}"
    base, ext = os.path.splitext(clean_name)
    save_path = os.path.join(dest_dir, clean_name)
    counter = 1
    while os.path.exists(save_path):
        save_path = os.path.join(dest_dir, f"{base} ({counter}){ext}")
        counter += 1

    with open(save_path, "wb") as f:
        async for chunk in request.stream():
            f.write(chunk)

    return {"status": "ok", "message": f"Saved as {os.path.basename(save_path)}", "path": save_path}

@app.get('/fs/search', dependencies=[Depends(require_auth)])
def search_fs(path: str = Query(default=""), q: str = Query(default="")) -> dict:
    if not path:
        path = os.path.expanduser("~")
    results = video_stream.search_folder(path, q)
    return {"results": results, "query": q, "path": path}

@app.post('/fs/open-on-pc', dependencies=[Depends(require_auth)])
def open_file_on_pc(path: str = Query(...)) -> dict:
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="File not found")
    success = video_stream.open_file_on_pc(path)
    if success:
        return {"status": "ok", "message": f"Opened {os.path.basename(path)}"}
    else:
        raise HTTPException(status_code=500, detail="Failed to open file on PC")

@app.get('/fs/read-text', dependencies=[Depends(require_auth)])
def read_text_file(path: str = Query(...), max_bytes: int = 500000) -> dict:
    if not os.path.exists(path) or not os.path.isfile(path):
        raise HTTPException(status_code=404, detail="File not found")
    try:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read(max_bytes)
        return {"content": content, "path": path, "name": os.path.basename(path)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# ==============================================================================
# Wireless AnyDesk-Style Remote Desktop & Audio Streaming Endpoints
# ==============================================================================

@app.get('/remote-desktop/monitors', dependencies=[Depends(require_auth)])
def get_desktop_monitors() -> dict:
    mons = remote_desktop.get_monitors_info()
    return {"status": "ok", "monitors": mons}

@app.post('/remote-desktop/action', dependencies=[Depends(require_auth)])
async def execute_desktop_action(request: Request) -> dict:
    try:
        data = await request.json()
        return await asyncio.to_thread(remote_desktop.handle_input_action, data)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.get('/remote-desktop/clipboard', dependencies=[Depends(require_auth)])
def get_desktop_clipboard() -> dict:
    res = remote_desktop.handle_input_action({"type": "clipboard_get"})
    return res

@app.post('/remote-desktop/clipboard', dependencies=[Depends(require_auth)])
async def set_desktop_clipboard(request: Request) -> dict:
    try:
        data = await request.json()
        data["type"] = "clipboard_set"
        return await asyncio.to_thread(remote_desktop.handle_input_action, data)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.get('/audio/stream')
def stream_pc_audio():
    return StreamingResponse(remote_desktop.generate_audio_stream(), media_type="audio/wav")

@app.websocket('/ws/remote-desktop')
async def ws_remote_desktop(websocket: WebSocket, password: str | None = Query(default=None), token: str | None = Query(default=None)):
    client_ip = websocket.client.host if websocket.client else "127.0.0.1"
    if client_ip not in ('127.0.0.1', '::1', 'localhost'):
        authenticated = check_auth(password, token, client_ip) or (token and check_auth(token, token, client_ip)) or (password and check_auth(password, password, client_ip))
        if not authenticated:
            await websocket.close(code=1008)
            return

    await websocket.accept()
    log_server_event(f"Remote Desktop connected from {client_ip}", "SUCCESS")

    settings = {
        "mode": "desktop",
        "monitor": 1,
        "hwnd": None,
        "quality": 55,
        "scale": 0.85,
        "fps": 35,
        "active": True
    }

    # Initial monitor and window handshake
    mons = remote_desktop.get_monitors_info()
    windows = remote_desktop.get_active_windows()
    try:
        await websocket.send_json({"type": "init", "monitors": mons, "windows": windows})
    except Exception:
        return

    async def frame_sender():
        frame_id = 0
        while settings["active"]:
            t0 = time.perf_counter()
            fps = max(5, min(60, settings["fps"]))
            interval = 1.0 / fps
            try:
                mode = settings.get("mode", "desktop")
                quality = settings["quality"]
                scale = settings["scale"]
                mon_id = settings["monitor"]
                hwnd = settings.get("hwnd")

                if mode == "window" and hwnd:
                    try:
                        jpeg_bytes, w, h = await asyncio.to_thread(
                            remote_desktop.capture_window_jpeg, hwnd, quality, scale
                        )
                        packet = remote_desktop.pack_frame(jpeg_bytes, 0, w, h, frame_id)
                    except Exception:
                        # Only fall back to full desktop if the target window was closed/destroyed
                        if not remote_desktop.is_window_valid(hwnd):
                            settings["mode"] = "desktop"
                            settings["hwnd"] = None
                            try:
                                await websocket.send_json({"type": "target_fallback", "mode": "desktop"})
                            except Exception:
                                pass
                        jpeg_bytes, w, h = await asyncio.to_thread(
                            remote_desktop.capture_screen_jpeg, mon_id, quality, scale
                        )
                        packet = remote_desktop.pack_frame(jpeg_bytes, mon_id, w, h, frame_id)
                else:
                    jpeg_bytes, w, h = await asyncio.to_thread(
                        remote_desktop.capture_screen_jpeg, mon_id, quality, scale
                    )
                    packet = remote_desktop.pack_frame(jpeg_bytes, mon_id, w, h, frame_id)

                await websocket.send_bytes(packet)
                frame_id += 1
            except Exception:
                break

            elapsed = time.perf_counter() - t0
            sleep_time = interval - elapsed
            if sleep_time > 0.002:
                await asyncio.sleep(sleep_time)
            else:
                await asyncio.sleep(0)

    sender_task = asyncio.create_task(frame_sender())

    try:
        while True:
            msg = await websocket.receive_text()
            try:
                data = json.loads(msg)
                msg_type = data.get("type")
                if msg_type == "set_monitor":
                    settings["monitor"] = int(data.get("monitor", 1))
                    settings["mode"] = "desktop"
                elif msg_type == "set_target":
                    mode = data.get("mode", "desktop")
                    settings["mode"] = mode
                    if mode == "window":
                        h = int(data.get("hwnd", 0))
                        settings["hwnd"] = h
                        if h and remote_desktop.is_window_valid(h):
                            try:
                                h_val = remote_desktop.wintypes.HWND(h)
                                if remote_desktop.user32.IsIconic(h_val):
                                    remote_desktop.user32.ShowWindow(h_val, remote_desktop.SW_SHOWNOACTIVATE)
                            except Exception:
                                pass
                    else:
                        settings["monitor"] = int(data.get("monitor", 1))
                elif msg_type == "get_windows":
                    windows = await asyncio.to_thread(remote_desktop.get_active_windows)
                    await websocket.send_json({"type": "window_list", "windows": windows})
                elif msg_type == "set_quality":
                    if "quality" in data:
                        settings["quality"] = int(data["quality"])
                    if "scale" in data:
                        settings["scale"] = float(data["scale"])
                    if "fps" in data:
                        settings["fps"] = int(data["fps"])
                elif msg_type == "clipboard_get":
                    res = remote_desktop.handle_input_action(data)
                    await websocket.send_json({"type": "clipboard_data", "text": res.get("text", "")})
                else:
                    if "mode" not in data and "mode" in settings:
                        data["mode"] = settings["mode"]
                    if "hwnd" not in data and "hwnd" in settings:
                        data["hwnd"] = settings["hwnd"]
                    await asyncio.to_thread(remote_desktop.handle_input_action, data)
            except Exception:
                pass
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        settings["active"] = False
        sender_task.cancel()
        log_server_event(f"Remote Desktop disconnected from {client_ip}", "INFO")

# ==============================================================================
# DIRECT-TO-PC CAMERA RECORDING ROUTES
# ==============================================================================

@app.get('/camera/status')
def get_camera_status() -> dict:
    rec = camera_recorder.get_camera_recorder()
    return rec.get_status()

@app.post('/camera/start', dependencies=[Depends(require_auth)])
async def start_camera_recording(request: Request) -> dict:
    resolution = "1920x1080"
    fps = 30
    audio_codec = "AAC 48kHz"
    orientation = 0
    try:
        body = await request.json()
        if isinstance(body, dict):
            resolution = body.get("resolution", resolution)
            fps = int(body.get("fps", fps))
            audio_codec = body.get("audio", audio_codec)
            orientation = int(body.get("orientation", 0))
    except Exception:
        # Fallback to query parameters if any
        q = request.query_params
        resolution = q.get("resolution", resolution)
        if "fps" in q:
            try:
                fps = int(q["fps"])
            except Exception:
                pass
        if "orientation" in q:
            try:
                orientation = int(q["orientation"])
            except Exception:
                pass
        audio_codec = q.get("audio", audio_codec)

    rec = camera_recorder.get_camera_recorder()
    try:
        res = rec.start_recording(resolution=resolution, fps=fps, audio_codec=audio_codec, orientation=orientation)
        log_server_event(f"Direct camera recording started: {res.get('filename')} ({resolution} @ {fps}fps, rot={orientation})", "SUCCESS")
        return res
    except Exception as e:
        log_server_event(f"Failed to start camera recording: {e}", "ERROR")
        raise HTTPException(status_code=400, detail=str(e))

@app.post('/camera/stop', dependencies=[Depends(require_auth)])
def stop_camera_recording() -> dict:
    rec = camera_recorder.get_camera_recorder()
    res = rec.stop_recording()
    if res.get("status") == "ok":
        log_server_event(f"Direct camera recording finalized: {res.get('file_path')} ({round(res.get('file_size_bytes', 0)/(1024*1024), 2)} MB)", "SUCCESS")
    else:
        log_server_event(f"Direct camera recording stop warning: {res.get('message')}", "WARNING")
    return res

@app.post('/camera/destination', dependencies=[Depends(require_auth)])
async def set_camera_destination(request: Request) -> dict:
    target_dir = None
    try:
        body = await request.json()
        if isinstance(body, dict):
            target_dir = body.get("destination_dir") or body.get("dir")
    except Exception:
        target_dir = request.query_params.get("dir") or request.query_params.get("destination_dir")

    if not target_dir:
        raise HTTPException(status_code=400, detail="Missing destination_dir parameter")

    rec = camera_recorder.get_camera_recorder()
    try:
        res = rec.set_destination_dir(target_dir)
        log_server_event(f"Camera destination folder set to: {target_dir}", "INFO")
        return res
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.get('/camera/recordings')
def get_camera_recordings(max_items: int = Query(default=50)) -> dict:
    rec = camera_recorder.get_camera_recorder()
    return {"recordings": rec.get_recent_recordings(max_items)}

@app.post('/camera/open-folder', dependencies=[Depends(require_auth)])
def open_camera_folder() -> dict:
    rec = camera_recorder.get_camera_recorder()
    folder = rec.destination_dir
    if not os.path.exists(folder):
        os.makedirs(folder, exist_ok=True)
    try:
        if sys.platform == 'win32':
            os.startfile(folder)
        else:
            subprocess.Popen(['xdg-open', folder])
        return {"status": "ok", "message": f"Opened folder: {folder}"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post('/camera/open-file', dependencies=[Depends(require_auth)])
async def open_camera_file(request: Request) -> dict:
    file_path = None
    try:
        body = await request.json()
        if isinstance(body, dict):
            file_path = body.get("file_path")
    except Exception:
        file_path = request.query_params.get("file_path")

    if not file_path or not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="File does not exist")

    try:
        if sys.platform == 'win32':
            os.startfile(file_path)
        else:
            subprocess.Popen(['xdg-open', file_path])
        return {"status": "ok", "message": f"Opened file: {file_path}"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post('/camera/preview-frame')
async def post_camera_preview_frame(request: Request) -> dict:
    try:
        frame_bytes = await request.body()
        if frame_bytes and len(frame_bytes) > 100:
            rec = camera_recorder.get_camera_recorder()
            rec.update_preview_frame(frame_bytes)
            return {"status": "ok"}
        return {"status": "error", "message": "Invalid frame bytes"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.get('/camera/preview')
def get_camera_preview():
    rec = camera_recorder.get_camera_recorder()
    frame = rec.get_latest_preview_frame()
    if frame:
        import io
        return StreamingResponse(io.BytesIO(frame), media_type="image/jpeg")
    raise HTTPException(status_code=204, detail="No active preview frame")

@app.get('/camera/mjpeg-stream')
async def get_camera_mjpeg_stream():
    rec = camera_recorder.get_camera_recorder()
    async def frame_streamer():
        while True:
            frame = rec.get_latest_preview_frame()
            if frame:
                yield (b'--frame\r\n'
                       b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
            await asyncio.sleep(0.033) # 30 FPS stream
    return StreamingResponse(frame_streamer(), media_type="multipart/x-mixed-replace; boundary=frame")

@app.post('/camera/client-state')
def set_camera_client_state(state: str = Query(default="open")) -> dict:
    rec = camera_recorder.get_camera_recorder()
    rec.set_client_state(state)
    return {"status": "ok", "state": state}

@app.post('/camera/snapshot')
def take_camera_snapshot() -> dict:
    rec = camera_recorder.get_camera_recorder()
    try:
        res = rec.save_snapshot()
        log_server_event(f"Camera photo captured: {res.get('filename')}", "SUCCESS")
        return res
    except Exception as e:
        log_server_event(f"Failed to capture camera photo: {e}", "ERROR")
        raise HTTPException(status_code=400, detail=str(e))




@app.post('/player/open', dependencies=[Depends(require_auth)])
@app.post('/media/play-on-pc', dependencies=[Depends(require_auth)])
def play_media_on_pc(
    request: Request,
    url: str | None = Query(default=None),
    stream_url: str | None = Query(default=None)
) -> dict:
    target_url = url or stream_url
    if not target_url:
        raise HTTPException(status_code=400, detail="Missing url parameter")

    client_ip = request.client.host if request.client else None
    if client_ip and client_ip not in ('127.0.0.1', '::1'):
        for placeholder in ('0.0.0.0', '127.0.0.1', 'localhost'):
            if placeholder in target_url:
                target_url = target_url.replace(placeholder, client_ip)

    stream_key = f"pc_player::{target_url}"
    name = os.path.basename(target_url.split('?')[0]) if '?' in target_url else os.path.basename(target_url)
    ACTIVE_LIVE_STREAMS[stream_key] = {
        "id": stream_key,
        "path": target_url,
        "filename": name or "Live Stream",
        "client": client_ip or "localhost",
        "started_at": time.time(),
        "last_active": time.time() + 60,
        "type": "pc_player"
    }

    success = video_stream.open_media_on_pc(target_url)
    if success:
        return {"status": "ok", "message": f"Playing {target_url} on PC"}
    else:
        raise HTTPException(status_code=500, detail="Failed to launch media player on PC")

@app.get('/logs')
def get_server_logs() -> dict:
    return {"logs": _LOG_ENTRIES}

@app.post('/server/shutdown')
def shutdown_server() -> dict:
    log_server_event("Server shutdown request received from GUI.", "WARNING")
    def _stop():
        time.sleep(0.5)
        os._exit(0)
    threading.Thread(target=_stop, daemon=True).start()
    return {"status": "ok", "message": "Server shutting down"}

@app.api_route('/gui/show', methods=['GET', 'POST'])
def show_gui_window() -> dict:
    candidates = [
        # Windows GUI binaries
        os.path.join(BASE_DIR, 'desktop-gui', 'dist-electron', 'win-unpacked', 'Remote PC GUI.exe'),
        os.path.join(BASE_DIR, 'Remote PC GUI.exe'),
        os.path.join(BASE_DIR, 'desktop-gui', 'dist-electron', 'Remote PC GUI Setup 0.0.0.exe'),
        # Linux GUI binaries
        os.path.join(BASE_DIR, 'desktop-gui', 'dist-electron', 'linux-unpacked', 'Remote PC GUI'),
        os.path.join(BASE_DIR, 'desktop-gui', 'dist-electron', 'linux-unpacked', 'desktop-gui'),
        os.path.join(BASE_DIR, 'Remote PC GUI.AppImage'),
        os.path.join(BASE_DIR, 'Remote_PC_GUI'),
        os.path.join(os.path.dirname(BASE_DIR), 'desktop-gui', 'dist-electron', 'linux-unpacked', 'Remote PC GUI'),
        os.path.join(os.path.dirname(BASE_DIR), 'desktop-gui', 'dist-electron', 'linux-unpacked', 'desktop-gui'),
        os.path.join(os.path.dirname(BASE_DIR), 'Remote PC GUI.AppImage'),
    ]
    for exe in candidates:
        if os.path.exists(exe):
            subprocess.Popen([exe])
            return {"status": "ok", "message": f"Launched {os.path.basename(exe)}"}
    return {"status": "headless", "message": "Server running in headless mode"}


@app.get('/clients')
def get_connected_clients() -> dict:
    now = time.time()
    active = {}
    for ip, data in list(CONNECTED_CLIENTS.items()):
        if now - data['last_seen'] < 60:
            active[ip] = {
                'ip': ip,
                'name': data.get('name', 'Mobile Device'),
                'id': data.get('id', ip),
                'auto_connect': data.get('auto_connect', False),
                'last_seen_sec': round(now - data['last_seen'], 1)
            }
        else:
            CONNECTED_CLIENTS.pop(ip, None)
    return {'clients': list(active.values()), 'count': len(active)}

@app.post('/pair/request')
def request_pairing(request: Request, name: str = Query(default="Mobile"), device_id: str = Query(default="")) -> dict:
    client_ip = request.client.host if request.client else '127.0.0.1'
    dev_id = device_id or client_ip
    PENDING_PAIRINGS[client_ip] = {
        'ip': client_ip,
        'name': name,
        'id': dev_id,
        'time': time.time()
    }
    log_server_event(f"Pairing request from {name} ({client_ip})", "INFO")
    return {'status': 'pending', 'message': 'Pairing request received, awaiting confirmation on PC'}

@app.get('/pair/requests')
def get_pending_pairings() -> dict:
    now = time.time()
    for ip in list(PENDING_PAIRINGS.keys()):
        if now - PENDING_PAIRINGS[ip]['time'] > 300:
            PENDING_PAIRINGS.pop(ip, None)
    return {'requests': list(PENDING_PAIRINGS.values())}

@app.post('/pair/approve')
def approve_pairing(ip: str = Query(...), auto_connect: bool = Query(default=True)) -> dict:
    req_data = PENDING_PAIRINGS.pop(ip, None)
    device_name = req_data.get('name', 'Mobile Device') if req_data else 'Mobile Device'
    device_id = req_data.get('id', ip) if req_data else ip
    token = secrets.token_hex(24)

    if 'trusted_tokens' not in CONFIG:
        CONFIG['trusted_tokens'] = {}
    if 'trusted_devices' not in CONFIG:
        CONFIG['trusted_devices'] = []

    CONFIG['trusted_tokens'][token] = {
        'name': device_name,
        'id': device_id,
        'ip': ip,
        'created_at': time.time(),
        'auto_connect': auto_connect
    }
    CONFIG['trusted_devices'] = [
        d for d in CONFIG['trusted_devices'] if d.get('id') != device_id and d.get('ip') != ip
    ]
    CONFIG['trusted_devices'].append({
        'name': device_name,
        'id': device_id,
        'ip': ip,
        'token': token,
        'auto_connect': auto_connect
    })
    save_config(CONFIG)
    log_server_event(f"Device '{device_name}' ({ip}) authorized with Auto-Connect={auto_connect}", "SUCCESS")

    # Notify mobile device on port 5056 that auto-connect pairing was approved
    def _notify_mobile():
        try:
            lan = get_lan_ip()
            notify_url = f"http://{ip}:5056/pair/approved?token={token}&name={urllib.parse.quote(SERVER_NAME)}&ip={lan}"
            req_notif = urllib.request.Request(notify_url, method="GET")
            urllib.request.urlopen(req_notif, timeout=2.0)
        except Exception:
            pass
    threading.Thread(target=_notify_mobile, daemon=True).start()

    return {'status': 'ok', 'token': token, 'device_name': device_name}

@app.post('/phone/request-pair')
def request_pair_to_phone(ip: str = Query(...)) -> dict:
    import urllib.parse
    import urllib.request
    try:
        lan_ip = get_lan_ip()
        url = f"http://{ip}:5056/pair/request?server_name={urllib.parse.quote(SERVER_NAME)}&ip={lan_ip}"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=3.0) as resp:
            return {'status': 'ok', 'message': 'Pairing request sent to mobile phone'}
    except Exception as e:
        return {'status': 'error', 'detail': str(e)}

@app.get('/pair/status')
def check_pair_status(ip: str = Query(default=""), device_id: str = Query(default="")) -> dict:
    trusted = CONFIG.get('trusted_devices', [])
    for d in trusted:
        if (ip and d.get('ip') == ip) or (device_id and d.get('id') == device_id):
            return {'paired': True, 'auto_connect': d.get('auto_connect', False), 'name': d.get('name')}
    return {'paired': False, 'auto_connect': False}

@app.post('/pair/revoke')
def revoke_pairing(device_id: str = Query(...)) -> dict:
    if 'trusted_tokens' in CONFIG:
        tokens_to_del = [t for t, d in CONFIG['trusted_tokens'].items() if d.get('id') == device_id or d.get('ip') == device_id]
        for t in tokens_to_del:
            CONFIG['trusted_tokens'].pop(t, None)
    if 'trusted_devices' in CONFIG:
        CONFIG['trusted_devices'] = [d for d in CONFIG['trusted_devices'] if d.get('id') != device_id and d.get('ip') != device_id]
    save_config(CONFIG)
    log_server_event(f"Device '{device_id}' pairing revoked", "INFO")
    return {'status': 'ok', 'message': f'Revoked {device_id}'}

@app.get('/pair/list')
def list_paired_devices() -> dict:
    return {'devices': CONFIG.get('trusted_devices', [])}

@app.post('/phone/send-toast')
def send_toast_to_phone(ip: str = Query(...), message: str = Query(...)) -> dict:
    import urllib.parse
    import urllib.request
    try:
        url = f"http://{ip}:5056/notify?text={urllib.parse.quote(message)}"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            return {'status': 'ok', 'response': resp.status}
    except Exception as e:
        return {'status': 'error', 'detail': str(e)}

@app.post('/phone/action')
def trigger_phone_action(ip: str = Query(default=""), action: str = Query(...), value: str = Query(default="")) -> dict:
    import urllib.parse
    import urllib.request
    _auto_forward_adb()

    targets = []
    if ip and ip.strip():
        targets.append(ip.strip())
    for c in CONNECTED_CLIENTS.values():
        cip = c.get('ip')
        if cip and cip not in targets:
            targets.append(cip)
    targets.extend(["127.0.0.1:15056", "127.0.0.1:5056"])

    last_err = ""
    for target in targets:
        base = target if target.startswith("http://") else (f"http://{target}" if ":" in target else f"http://{target}:5056")
        url = f"{base}/action?type={urllib.parse.quote(action)}&value={urllib.parse.quote(value)}"
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=2.5) as resp:
                data = resp.read().decode('utf-8')
                try:
                    return json.loads(data)
                except Exception:
                    return {'status': 'ok', 'response': resp.status}
        except Exception as e:
            last_err = str(e)
            continue
    return {'status': 'error', 'detail': last_err or 'No phone responded'}

@app.get('/phone/device-info')
def get_phone_device_info(ip: str = Query(...)) -> dict:
    import urllib.request
    try:
        url = f"http://{ip}:5056/device/info"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=2.5) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            return data
    except Exception as e:
        return {
            'status': 'offline',
            'detail': str(e),
            'model': 'Android Phone',
            'android_version': 'Unknown',
            'battery': 100,
            'is_charging': False,
            'storage_free_gb': 0,
            'storage_total_gb': 0,
            'ram_used_mb': 0,
            'ram_total_mb': 0,
            'screen_res': '1080x2400',
            'wifi_ssid': 'Connected Wi-Fi'
        }

@app.post('/phone/send-clipboard')
def send_clipboard_to_phone(ip: str = Query(...), text: str = Query(...)) -> dict:
    import urllib.parse
    import urllib.request
    try:
        url = f"http://{ip}:5056/clipboard?text={urllib.parse.quote(text)}"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            return {'status': 'ok', 'response': resp.status}
    except Exception as e:
        return {'status': 'error', 'detail': str(e)}

_LAST_ADB_FORWARD_TIME = 0.0

def _auto_forward_adb():
    global _LAST_ADB_FORWARD_TIME
    now = time.time()
    if now - _LAST_ADB_FORWARD_TIME < 60.0:
        return
    _LAST_ADB_FORWARD_TIME = now
    try:
        kwargs = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL, "timeout": 1.0}
        if sys.platform == 'win32':
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
        subprocess.run(
            ["adb", "forward", "tcp:15056", "tcp:5056"],
            **kwargs
        )
    except Exception:
        pass

@app.get('/phone/storage/list')
def get_phone_storage_list(ip: str = Query(default=""), category: str = Query(default="all")) -> dict:
    import urllib.parse
    import urllib.request
    _auto_forward_adb()

    targets = []
    if ip and ip.strip():
        targets.append(ip.strip())
    for c in CONNECTED_CLIENTS.values():
        cip = c.get('ip')
        if cip and cip not in targets:
            targets.append(cip)
    targets.extend(["127.0.0.1:15056", "127.0.0.1:5056"])

    last_err = ""
    for target in targets:
        base = target if target.startswith("http://") else (f"http://{target}" if ":" in target else f"http://{target}:5056")
        url = f"{base}/storage/list?type={urllib.parse.quote(category)}"
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=3.0) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                if data.get('status') == 'ok':
                    return data
        except Exception as e:
            last_err = str(e)
            continue
    return {'status': 'error', 'files': [], 'detail': last_err or 'No mobile device reached'}

@app.get('/phone/storage/preview')
def preview_file_from_phone(ip: str = Query(default=""), file_id: str = Query(...), file_type: str = Query(default="image"), filename: str = Query(default="")):
    import urllib.parse
    import urllib.request
    from fastapi.responses import StreamingResponse
    _auto_forward_adb()

    targets = []
    if ip and ip.strip():
        targets.append(ip.strip())
    for c in CONNECTED_CLIENTS.values():
        cip = c.get('ip')
        if cip and cip not in targets:
            targets.append(cip)
    targets.extend(["127.0.0.1:15056", "127.0.0.1:5056"])

    ext = os.path.splitext(filename)[1].lower() if filename else ""
    content_type = "application/octet-stream"
    if file_type == 'image' or ext in ('.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp'):
        content_type = "image/png" if ext == '.png' else "image/jpeg"
    elif file_type == 'video' or ext in ('.mp4', '.mkv', '.webm', '.avi', '.mov'):
        content_type = "video/mp4"
    elif file_type == 'audio' or ext in ('.mp3', '.m4a', '.wav', '.ogg', '.aac'):
        content_type = "audio/mpeg"
    elif ext == '.pdf':
        content_type = "application/pdf"
    elif ext in ('.txt', '.log', '.json', '.xml', '.csv'):
        content_type = "text/plain; charset=utf-8"

    for target in targets:
        base = target if target.startswith("http://") else (f"http://{target}" if ":" in target else f"http://{target}:5056")
        url = f"{base}/storage/file?id={file_id}&type={urllib.parse.quote(file_type)}"
        try:
            req = urllib.request.Request(url, method="GET")
            resp = urllib.request.urlopen(req, timeout=15.0)
            def iterfile():
                try:
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        yield chunk
                finally:
                    resp.close()
            return StreamingResponse(iterfile(), media_type=content_type)
        except Exception:
            continue
    raise HTTPException(status_code=404, detail="File could not be streamed from phone")

@app.get('/phone/storage/thumbnail')
def get_phone_thumbnail(ip: str = Query(default=""), file_id: str = Query(...), file_type: str = Query(default="image"), filename: str = Query(default="")):
    _auto_forward_adb()
    ext = os.path.splitext(filename)[1].lower() if filename else ""
    if ext in thumbnail_generator.APK_EXTENSIONS or ext in thumbnail_generator.EXE_EXTENSIONS or ext in thumbnail_generator.UBT_EXTENSIONS:
        badge_bytes = thumbnail_generator.get_thumbnail(filename)
        return Response(content=badge_bytes, media_type="image/png", headers={"Cache-Control": "public, max-age=86400"})

    targets = []
    if ip and ip.strip():
        targets.append(ip.strip())
    for c in CONNECTED_CLIENTS.values():
        cip = c.get('ip')
        if cip and cip not in targets:
            targets.append(cip)
    if '127.0.0.1' not in targets:
        targets.append('127.0.0.1')

    for tip in targets:
        port = 15056 if tip == '127.0.0.1' else 5056
        url = f"http://{tip}:{port}/storage/thumbnail?id={urllib.parse.quote(file_id)}&type={urllib.parse.quote(file_type)}&filename={urllib.parse.quote(filename)}"
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=2.0) as resp:
                data = resp.read()
                return Response(content=data, media_type="image/png", headers={"Cache-Control": "public, max-age=86400"})
        except Exception:
            pass

    badge_bytes = thumbnail_generator.get_thumbnail(filename)
    return Response(content=badge_bytes, media_type="image/png", headers={"Cache-Control": "public, max-age=86400"})

def _run_phone_download_worker(tid: str, ip: str, file_id: str, file_type: str, filename: str):
    import urllib.parse
    import urllib.request
    _auto_forward_adb()

    targets = []
    if ip and ip.strip():
        targets.append(ip.strip())
    for c in CONNECTED_CLIENTS.values():
        cip = c.get('ip')
        if cip and cip not in targets:
            targets.append(cip)
    targets.extend(["127.0.0.1:15056", "127.0.0.1:5056"])

    user_home = os.path.expanduser("~")
    dest_dir = os.path.join(user_home, "Downloads", "RemotePC")
    os.makedirs(dest_dir, exist_ok=True)
    safe_filename = "".join(c for c in filename if c.isalnum() or c in (' ', '.', '_', '-')).strip() or f"phone_{file_id}"
    save_path = os.path.join(dest_dir, safe_filename)
    part_path = save_path + f".part_{tid[:8]}"

    ACTIVE_FILE_TRANSFERS[tid] = {
        "id": tid,
        "filename": safe_filename,
        "direction": "Phone ➔ PC",
        "transferred": 0,
        "total": 0,
        "percent": 0.0,
        "speed_mb": 0.0,
        "status": "in_progress",
        "message": "Connecting to phone...",
        "saved_path": save_path,
        "cancel_requested": False
    }

    if os.path.exists(save_path) and os.path.getsize(save_path) > 0:
        file_sz = os.path.getsize(save_path)
        ACTIVE_FILE_TRANSFERS[tid].update({
            "transferred": file_sz,
            "total": file_sz,
            "percent": 100.0,
            "status": "completed",
            "message": "File already exists in Downloads",
            "saved_path": save_path
        })
        return

    success = False
    for target in targets:
        if ACTIVE_FILE_TRANSFERS[tid].get("cancel_requested"):
            break
        base = target if target.startswith("http://") else (f"http://{target}" if ":" in target else f"http://{target}:5056")
        url = f"{base}/storage/file?id={file_id}&type={urllib.parse.quote(file_type)}"
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=30.0) as resp:
                total_bytes = int(resp.headers.get("Content-Length", 0))
                ACTIVE_FILE_TRANSFERS[tid]["total"] = total_bytes
                ACTIVE_FILE_TRANSFERS[tid]["message"] = "Transferring..."
                transferred = 0
                last_time = time.time()
                last_bytes = 0
                chunk_size = 64 * 1024

                with open(part_path, 'wb') as out_f:
                    while True:
                        if ACTIVE_FILE_TRANSFERS[tid].get("cancel_requested"):
                            break
                        chunk = resp.read(chunk_size)
                        if not chunk:
                            break
                        out_f.write(chunk)
                        transferred += len(chunk)
                        now = time.time()
                        dt = now - last_time
                        if dt >= 0.2:
                            speed = round(((transferred - last_bytes) / (1024 * 1024)) / dt, 2)
                            last_bytes = transferred
                            last_time = now
                            pct = round((transferred / total_bytes) * 100, 1) if total_bytes > 0 else 0.0
                            ACTIVE_FILE_TRANSFERS[tid].update({
                                "transferred": transferred,
                                "percent": pct,
                                "speed_mb": speed
                            })

                if ACTIVE_FILE_TRANSFERS[tid].get("cancel_requested"):
                    if os.path.exists(part_path):
                        try: os.remove(part_path)
                        except Exception: pass
                    ACTIVE_FILE_TRANSFERS[tid]["status"] = "cancelled"
                    ACTIVE_FILE_TRANSFERS[tid]["message"] = "Transfer cancelled by user"
                    log_server_event(f"Cancelled transfer of '{safe_filename}'", "INFO")
                    return

                if os.path.exists(part_path):
                    if os.path.exists(save_path):
                        try: os.remove(save_path)
                        except Exception: pass
                    os.rename(part_path, save_path)

                ACTIVE_FILE_TRANSFERS[tid].update({
                    "transferred": transferred,
                    "total": total_bytes or transferred,
                    "percent": 100.0,
                    "status": "completed",
                    "message": f"Saved as {safe_filename}",
                    "saved_path": save_path
                })
                log_server_event(f"Downloaded '{safe_filename}' from phone to {save_path}", "SUCCESS")
                success = True
                break
        except Exception:
            continue

    if not success and ACTIVE_FILE_TRANSFERS[tid].get("status") != "cancelled":
        if os.path.exists(part_path):
            try: os.remove(part_path)
            except Exception: pass
        ACTIVE_FILE_TRANSFERS[tid]["status"] = "error"
        ACTIVE_FILE_TRANSFERS[tid]["message"] = "Failed to download file from all mobile endpoints"

def _run_phone_upload_worker(tid: str, ip: str, file_path: str):
    if not os.path.exists(file_path):
        ACTIVE_FILE_TRANSFERS[tid] = {"id": tid, "status": "error", "message": "Local file not found"}
        return

    file_size = os.path.getsize(file_path)
    file_name = os.path.basename(file_path)
    target_host = ip if ip and ip.strip() else "127.0.0.1"
    if ":" not in target_host:
        target_host = f"{target_host}:5056"

    ACTIVE_FILE_TRANSFERS[tid] = {
        "id": tid,
        "filename": file_name,
        "direction": "PC ➔ Phone",
        "transferred": 0,
        "total": file_size,
        "percent": 0.0,
        "speed_mb": 0.0,
        "status": "in_progress",
        "message": "Uploading to Phone Downloads/RemotePC...",
        "saved_path": "",
        "cancel_requested": False
    }

    import urllib.parse
    import urllib.request

    encoded_name = urllib.parse.quote(file_name)
    upload_url = f"http://{target_host}/storage/upload?filename={encoded_name}"

    try:
        class ProgressStream:
            def __init__(self, path, tid_val, total_len):
                self.f = open(path, 'rb')
                self.tid = tid_val
                self.total = total_len
                self.transferred = 0
                self.last_time = time.time()
                self.last_bytes = 0

            def read(self, size=-1):
                if ACTIVE_FILE_TRANSFERS.get(self.tid, {}).get("cancel_requested"):
                    raise Exception("Transfer cancelled by user")
                chunk = self.f.read(64 * 1024 if size < 0 or size > 64 * 1024 else size)
                if chunk:
                    self.transferred += len(chunk)
                    now = time.time()
                    dt = now - self.last_time
                    if dt >= 0.2:
                        speed = round(((self.transferred - self.last_bytes) / (1024 * 1024)) / dt, 2)
                        self.last_bytes = self.transferred
                        self.last_time = now
                        pct = round((self.transferred / self.total) * 100, 1) if self.total > 0 else 0.0
                        ACTIVE_FILE_TRANSFERS[self.tid].update({
                            "transferred": self.transferred,
                            "percent": pct,
                            "speed_mb": speed
                        })
                return chunk

            def __len__(self):
                return self.total

            def close(self):
                self.f.close()

        streamer = ProgressStream(file_path, tid, file_size)
        try:
            req = urllib.request.Request(
                upload_url,
                data=streamer,
                headers={"Content-Length": str(file_size), "Content-Type": "application/octet-stream"},
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=120.0) as resp:
                resp.read()
            ACTIVE_FILE_TRANSFERS[tid].update({
                "transferred": file_size,
                "percent": 100.0,
                "status": "completed",
                "message": f"Sent {file_name} to Phone Downloads/RemotePC"
            })
            log_server_event(f"Sent '{file_name}' to Phone Downloads/RemotePC", "SUCCESS")
        finally:
            streamer.close()
    except Exception as e:
        if ACTIVE_FILE_TRANSFERS.get(tid, {}).get("cancel_requested"):
            ACTIVE_FILE_TRANSFERS[tid]["status"] = "cancelled"
            ACTIVE_FILE_TRANSFERS[tid]["message"] = "Upload cancelled by user"
        else:
            ACTIVE_FILE_TRANSFERS[tid]["status"] = "error"
            ACTIVE_FILE_TRANSFERS[tid]["message"] = f"Upload failed: {str(e)}"

@app.post('/phone/storage/download')
def download_file_from_phone(
    ip: str = Query(default=""),
    file_id: str = Query(...),
    file_type: str = Query(default="image"),
    filename: str = Query(default="file"),
    transfer_id: str | None = Query(default=None)
) -> dict:
    tid = transfer_id or str(uuid.uuid4())
    _run_phone_download_worker(tid, ip, file_id, file_type, filename)
    info = ACTIVE_FILE_TRANSFERS.get(tid, {})
    if info.get("status") == "completed":
        return {'status': 'ok', 'saved_path': info.get('saved_path'), 'filename': info.get('filename'), 'transfer_id': tid}
    elif info.get("status") == "cancelled":
        return {'status': 'cancelled', 'detail': 'Transfer cancelled by user', 'transfer_id': tid}
    else:
        return {'status': 'error', 'detail': info.get('message', 'Failed to download file'), 'transfer_id': tid}

@app.post('/transfer/start-phone-download')
def start_phone_download_async(
    ip: str = Query(default=""),
    file_id: str = Query(...),
    file_type: str = Query(default="image"),
    filename: str = Query(default="file")
) -> dict:
    tid = str(uuid.uuid4())
    threading.Thread(target=_run_phone_download_worker, args=(tid, ip, file_id, file_type, filename), daemon=True).start()
    return {'status': 'ok', 'transfer_id': tid, 'filename': filename}

@app.post('/transfer/start-phone-upload')
def start_phone_upload_async(
    ip: str = Query(default=""),
    file_path: str = Query(...)
) -> dict:
    tid = str(uuid.uuid4())
    threading.Thread(target=_run_phone_upload_worker, args=(tid, ip, file_path), daemon=True).start()
    return {'status': 'ok', 'transfer_id': tid, 'filename': os.path.basename(file_path)}

@app.get('/transfer/status/{transfer_id}')
def get_transfer_status(transfer_id: str) -> dict:
    info = ACTIVE_FILE_TRANSFERS.get(transfer_id)
    if not info:
        return {'status': 'not_found', 'id': transfer_id}
    return info

@app.post('/transfer/cancel/{transfer_id}')
def cancel_transfer(transfer_id: str) -> dict:
    info = ACTIVE_FILE_TRANSFERS.get(transfer_id)
    if info:
        info["cancel_requested"] = True
        return {'status': 'ok', 'message': 'Cancellation requested'}
    return {'status': 'not_found', 'id': transfer_id}

@app.get('/system/stats')
def get_system_stats():
    try:
        cpu = round(psutil.cpu_percent(interval=None), 1)
        vm = psutil.virtual_memory()
        ram = round(vm.percent, 1)
        ram_used = round(vm.used / (1024**3), 1)
        ram_total = round(vm.total / (1024**3), 1)
        net = get_network_speed_mb()
        batt_pct = 100
        batt_plugged = True
        try:
            b = psutil.sensors_battery()
            if b:
                batt_pct = int(b.percent)
                batt_plugged = bool(b.power_plugged)
        except Exception:
            pass
        uptime_sec = int(time.time() - SERVER_START_TIME)
        h = uptime_sec // 3600
        m = (uptime_sec % 3600) // 60
        uptime_str = f"{h:02d}:{m:02d} h"
        pin = CONFIG.get("pin", "482 917")
        return {
            "cpu": cpu,
            "cpu_percent": cpu,
            "ram": ram,
            "memory_percent": ram,
            "memory_used_gb": ram_used,
            "memory_total_gb": ram_total,
            "network": net,
            "battery": {"percent": batt_pct, "plugged": batt_plugged},
            "battery_percent": batt_pct,
            "battery_plugged": batt_plugged,
            "uptime": uptime_str,
            "uptime_sec": uptime_sec,
            "uptime_seconds": uptime_sec,
            "mac": get_mac_address(),
            "pin": pin
        }
    except Exception as e:
        return {
            "error": str(e),
            "cpu": 0, "cpu_percent": 0,
            "ram": 0, "memory_percent": 0,
            "memory_used_gb": 0, "memory_total_gb": 0,
            "network": 0,
            "battery_percent": 100, "battery_plugged": True,
            "uptime": "00:00 h", "uptime_sec": 0, "uptime_seconds": 0,
            "pin": "482 917", "mac": get_mac_address()
        }

@app.post('/pair/pin')
def pair_with_pin(request: Request, pin: str = Query(...), name: str = Query(default="Mobile")) -> dict:
    clean_pin = pin.replace(" ", "").strip()
    expected_pin = CONFIG.get("pin", "482 917").replace(" ", "").strip()
    if clean_pin != expected_pin:
        raise HTTPException(status_code=403, detail="Invalid 6-digit PIN")
    client_ip = request.client.host if request.client else '127.0.0.1'
    return approve_pairing(ip=client_ip, auto_connect=True)

@app.get('/activity/recent')
def get_recent_activity():
    entries = []
    for item in reversed(_LOG_ENTRIES[-15:]):
        entries.append({
            "time": item["time"],
            "message": item["message"],
            "level": item.get("level", "INFO")
        })
    return {"activity": entries}

@app.get('/settings/folders')
def get_allowed_folders():
    default_folders = ["Downloads", "Desktop", "Documents", "Videos", "Drive D:"]
    return {"folders": CONFIG.get("allowed_folders", default_folders)}

@app.post('/settings/folders')
def update_allowed_folders(folders: list[str]):
    CONFIG["allowed_folders"] = folders
    save_config(CONFIG)
    return {"status": "ok", "folders": folders}

@app.post('/system/schedule-shutdown')
def schedule_shutdown(minutes: int = Query(...)):
    global _shutdown_timer
    if _shutdown_timer:
        _shutdown_timer.cancel()
    sec = minutes * 60
    def _do_shutdown():
        system_controls.shutdown()
    _shutdown_timer = threading.Timer(sec, _do_shutdown)
    _shutdown_timer.daemon = True
    _shutdown_timer.start()
    log_server_event(f"PC scheduled to shutdown in {minutes} minutes", "WARNING")
    return {"status": "ok", "minutes": minutes}

@app.post('/system/cancel-shutdown')
def cancel_scheduled_shutdown():
    global _shutdown_timer
    if _shutdown_timer:
        _shutdown_timer.cancel()
        _shutdown_timer = None
        log_server_event("Scheduled shutdown cancelled", "INFO")
        return {"status": "ok", "message": "Shutdown cancelled"}
    return {"status": "idle", "message": "No shutdown scheduled"}

@app.post('/server/settings')
def update_server_settings(name: str = Query(default=""), password: str = Query(default=""), strict_auto_connect: bool = Query(default=True)) -> dict:
    global SERVER_NAME
    if name:
        CONFIG['name'] = name.strip()
        SERVER_NAME = CONFIG['name']
    if password:
        CONFIG.update(hash_password(password.strip()))
    CONFIG['strict_auto_connect'] = bool(strict_auto_connect)
    save_config(CONFIG)
    log_server_event(f"Server settings updated: name='{SERVER_NAME}', strict_auto_connect={strict_auto_connect}", "SUCCESS")
    return {'status': 'ok', 'name': SERVER_NAME}

@app.post('/server/setup')
def setup_server_initial(name: str = Query(...), password: str = Query(...), strict_auto_connect: bool = Query(default=True)) -> dict:
    global SERVER_NAME, CONFIG
    name = name.strip() or get_default_pc_name()
    SERVER_NAME = name
    CONFIG['name'] = name
    CONFIG['strict_auto_connect'] = bool(strict_auto_connect)
    CONFIG['is_configured'] = True
    CONFIG.update(hash_password(password.strip()))
    save_config(CONFIG)
    log_server_event(f"Initial setup complete! PC Name: '{SERVER_NAME}', Strict Auto-Connect: {strict_auto_connect}", "SUCCESS")
    return {'status': 'ok', 'name': SERVER_NAME, 'is_configured': True}

@app.post('/pair/register-device')
def register_device(
    request: Request,
    name: str = Query(default="Mobile Device"),
    device_id: str = Query(default=""),
    password: str | None = Header(default=None, alias="X-Remote-Password"),
    x_password_param: str | None = Query(default=None, alias="password")
) -> dict:
    client_ip = request.client.host if request.client else '127.0.0.1'
    supplied_pass = password or x_password_param
    if not check_auth(supplied_pass, None):
        log_server_event(f"Device registration failed from {client_ip}: Invalid password/PIN", "WARN")
        raise HTTPException(status_code=401, detail="Invalid PC server password or PIN")

    token = secrets.token_hex(32)
    dev_id = device_id or client_ip

    if 'trusted_tokens' not in CONFIG:
        CONFIG['trusted_tokens'] = {}
    if 'trusted_devices' not in CONFIG:
        CONFIG['trusted_devices'] = []

    CONFIG['trusted_tokens'][token] = {
        'name': name,
        'id': dev_id,
        'ip': client_ip,
        'created_at': time.time(),
        'auto_connect': True
    }
    CONFIG['trusted_devices'] = [d for d in CONFIG['trusted_devices'] if d.get('id') != dev_id and d.get('ip') != client_ip]
    CONFIG['trusted_devices'].append({
        'name': name,
        'id': dev_id,
        'ip': client_ip,
        'token': token,
        'auto_connect': True
    })
    save_config(CONFIG)
    log_server_event(f"Device '{name}' ({client_ip}) securely registered with 256-bit token", "SUCCESS")
    return {
        'status': 'ok',
        'token': token,
        'server_name': SERVER_NAME,
        'ip': client_ip,
        'port': PORT
    }

def get_current_server_version() -> str:
    for candidate in [
        os.path.join(BASE_DIR, "config.json"),
        os.path.join(BASE_DIR, "..", "package.json"),
        os.path.join(os.path.dirname(sys.executable), "config.json")
    ]:
        try:
            if os.path.exists(candidate):
                with open(candidate, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if "version" in data and data["version"]:
                        return str(data["version"]).strip()
        except Exception:
            pass
    return "3.0.6"

SERVER_VERSION = get_current_server_version()
GITHUB_REPO = "Priyadharsant/Remote-Pc"
_UPDATE_STATE = {"status": "idle", "progress": 0, "message": "Idle", "error": None}

def parse_semver(v: str) -> tuple:
    import re
    cleaned = re.sub(r'^[^\d]*', '', v.strip())
    parts = []
    for part in cleaned.split('.'):
        digits = re.match(r'^\d+', part)
        parts.append(int(digits.group(0)) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts[:3])

@app.get('/system/version')
def get_system_version() -> dict:
    return {
        'version': SERVER_VERSION,
        'app': 'Remote PC',
        'os': sys.platform,
        'server': SERVER_NAME
    }

@app.get('/system/update/check')
def check_for_updates() -> dict:
    import urllib.request
    try:
        url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": "RemotePC-Server-Updater",
                "Accept": "application/vnd.github+json"
            }
        )
        with urllib.request.urlopen(req, timeout=8.0) as resp:
            if resp.status != 200:
                return {"update_available": False, "current_version": SERVER_VERSION, "error": f"HTTP {resp.status}"}
            data = json.loads(resp.read().decode('utf-8'))

        tag = data.get('tag_name', '').strip()
        latest_ver = parse_semver(tag)
        current_ver = parse_semver(SERVER_VERSION)
        update_available = latest_ver > current_ver

        exe_url = None
        apk_url = None
        exe_size = 0
        apk_size = 0

        for asset in data.get('assets', []):
            name = asset.get('name', '').lower()
            if name.endswith('.exe'):
                exe_url = asset.get('browser_download_url')
                exe_size = asset.get('size', 0)
            elif name.endswith('.apk'):
                apk_url = asset.get('browser_download_url')
                apk_size = asset.get('size', 0)

        return {
            'update_available': update_available,
            'current_version': SERVER_VERSION,
            'latest_version': tag,
            'release_title': data.get('name', tag),
            'release_notes': data.get('body', ''),
            'published_at': data.get('published_at', ''),
            'exe_url': exe_url,
            'exe_size': exe_size,
            'apk_url': apk_url,
            'apk_size': apk_size,
            'html_url': data.get('html_url', f"https://github.com/{GITHUB_REPO}/releases")
        }
    except Exception as exc:
        return {
            'update_available': False,
            'current_version': SERVER_VERSION,
            'error': str(exc)
        }

@app.post('/system/update/apply', dependencies=[Depends(require_auth)])
def apply_update_download(download_url: str = Query(...)) -> dict:
    global _UPDATE_STATE
    if _UPDATE_STATE.get("status") == "downloading":
        return {"status": "busy", "message": "An update download is already in progress."}

    def _download_worker():
        global _UPDATE_STATE
        import urllib.request
        import tempfile
        try:
            _UPDATE_STATE = {"status": "downloading", "progress": 5, "message": "Connecting...", "error": None}
            temp_dir = tempfile.gettempdir()
            dest_file = os.path.join(temp_dir, "Remote_PC_Setup_Update.exe")

            req = urllib.request.Request(download_url, headers={"User-Agent": "RemotePC-Server-Updater"})
            with urllib.request.urlopen(req, timeout=60.0) as resp, open(dest_file, 'wb') as out_f:
                total_len = int(resp.headers.get('content-length', 0))
                downloaded = 0
                chunk_sz = 64 * 1024
                while True:
                    chunk = resp.read(chunk_sz)
                    if not chunk:
                        break
                    out_f.write(chunk)
                    downloaded += len(chunk)
                    if total_len > 0:
                        pct = int((downloaded / total_len) * 90) + 5
                        _UPDATE_STATE["progress"] = min(pct, 95)
                        _UPDATE_STATE["message"] = f"Downloading update ({downloaded // (1024*1024)}MB / {total_len // (1024*1024)}MB)..."

            _UPDATE_STATE = {"status": "ready", "progress": 100, "message": "Update downloaded. Launching installer...", "error": None}
            log_server_event(f"Update downloaded to {dest_file}. Launching installer...", "SUCCESS")
            time.sleep(1.0)
            subprocess.Popen([dest_file, "/SILENT"], shell=True)
            time.sleep(0.5)
            os._exit(0)
        except Exception as e:
            _UPDATE_STATE = {"status": "error", "progress": 0, "message": str(e), "error": str(e)}
            log_server_event(f"Update download failed: {e}", "ERROR")

    t = threading.Thread(target=_download_worker, daemon=True)
    t.start()
    return {"status": "started", "message": "Update download started in background."}

@app.get('/system/update/status')
def get_update_status() -> dict:
    return _UPDATE_STATE

# ==============================================================================
# FOLDER SYNC ROUTES (Mobile -> Specific PC Folder)
# ==============================================================================

def verify_sync_request(request: Request, token: str | None = None) -> None:
    client_ip = request.client.host if request.client else "127.0.0.1"
    if client_ip in ('127.0.0.1', '::1', 'localhost'):
        return
    auth_header = request.headers.get("x-remote-password")
    if not (check_auth(auth_header, None, client_ip) or (token and check_auth(token, None, client_ip))):
        raise HTTPException(status_code=401, detail="Unauthorized")

@app.get('/sync/status')
def get_sync_status(request: Request) -> dict:
    verify_sync_request(request)
    return folder_sync.get_sync_status()

@app.post('/sync/destination')
async def set_sync_destination(request: Request) -> dict:
    verify_sync_request(request)
    body = await request.json()
    new_dir = body.get("destination_dir")
    if not new_dir:
        raise HTTPException(status_code=400, detail="destination_dir is required")
    try:
        return folder_sync.set_destination_dir(new_dir)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

@app.post('/sync/upload')
async def upload_sync_file(
    request: Request,
    relative_path: str = Query(...),
    token: str | None = Query(default=None)
) -> dict:
    verify_sync_request(request, token)

    file_bytes = await request.body()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="No file content received")

    try:
        res = folder_sync.save_uploaded_file(file_bytes, relative_path)
        log_server_event(f"Synced file received: {res['filename']} ({round(res['size_bytes']/1024, 1)} KB)", "SUCCESS")
        return res
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get('/sync/history')
def get_sync_history(request: Request) -> dict:
    verify_sync_request(request)
    return {"status": "ok", "history": folder_sync.get_sync_history()}

if __name__ == '__main__':
    # Single-instance check: if server is already running, exit immediately
    try:
        import urllib.request
        req = urllib.request.Request(f'http://127.0.0.1:{PORT}/status', method='GET')
        with urllib.request.urlopen(req, timeout=0.8) as resp:
            if resp.status == 200:
                print("Remote PC Core Server is already active in background.")
                sys.exit(0)
    except Exception:
        pass

    try:
        import uvicorn
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            'Missing dependency: uvicorn. Install dependencies with `pip install -r requirements.txt` from the remote_server folder.'
        ) from exc

    lan_ip = get_lan_ip()
    print("=" * 60)
    print(" 🚀 REMOTE PC CORE SERVER (Headless Background Service)")
    print(f" Local:   http://127.0.0.1:{PORT}")
    print(f" Network: http://{lan_ip}:{PORT}")
    print("=" * 60)

    # Start background UDP services
    start_discovery_server()
    start_presence_broadcast()
    start_touchpad_udp_server()
    start_keyboard_udp_server()
    start_slider_udp_server()

    log_server_event(f"FastAPI server running at http://{lan_ip}:{PORT}", "SUCCESS")
    log_server_event("UDP input listeners (Touchpad: 6000, Keyboard: 7000, Sliders: 8000) active", "SUCCESS")

    try:
        uvicorn.run(app, host=HOST, port=PORT, log_config=None)
    except KeyboardInterrupt:
        print("Stopping Remote PC Core Server...")
        sys.exit(0)
