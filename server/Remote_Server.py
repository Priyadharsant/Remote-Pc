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
import tkinter as tk
from tkinter import messagebox
import traceback

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import HTMLResponse, StreamingResponse, FileResponse

from controllers import brightness, keyboard, media, mouse, system_controls, volume, video_stream, remote_desktop


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
CONFIG_FILE = os.path.join(BASE_DIR, 'config.json')
ICON_FILE = os.path.join(RESOURCE_DIR, 'logo.ico')
LOG_FILE = os.path.join(BASE_DIR, 'Remote_Server.log')

HOST = '0.0.0.0'
PORT = 5055
DISCOVERY_PORT = 9999
TOUCHPAD_UDP_PORT = 6000
KEYBOARD_UDP_PORT = 7000
SLIDER_UDP_PORT = 8000
DISCOVERY_REQUEST = 'DISCOVER_PC_REMOTE'
PRESENCE_INTERVAL_SECONDS = 2
PASSWORD_HASH_ITERATIONS = 260000

def setup_crash_logging() -> None:
    logging.basicConfig(
        filename=LOG_FILE,
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
            'name': data.get('name', get_default_pc_name()),
            'is_configured': bool(data.get('is_configured', True)),
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
            enc_bytes = base64.b64decode(data['encrypted_vault'])
            dec_bytes = dpapi_decrypt(enc_bytes)
            if dec_bytes:
                vault = json.loads(dec_bytes.decode('utf-8'))
                data.update(vault)
                data.pop('encrypted_vault', None)
            else:
                log_server_event("DPAPI vault decryption error; running in restricted mode", "ERROR")

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
        return config

    default_name = get_default_pc_name()
    default_config = {
        'name': default_name,
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
SERVER_NAME = CONFIG['name']

HTML = """
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>PC Remote</title>
    <style>
        body {
            align-items: center;
            background: #101820;
            color: #f7f3e8;
            display: flex;
            font-family: "Segoe UI", sans-serif;
            justify-content: center;
            margin: 0;
            min-height: 100vh;
        }

        main {
            background: #1e2d38;
            border-radius: 24px;
            box-shadow: 0 24px 70px rgba(0, 0, 0, 0.35);
            padding: 28px;
            text-align: center;
            width: min(360px, calc(100vw - 32px));
        }

        h1 {
            font-size: 28px;
            margin: 0 0 22px;
        }

        .grid {
            display: none;
            gap: 12px;
            grid-template-columns: repeat(3, 1fr);
        }

        .grid.unlocked {
            display: grid;
        }

        button {
            background: #f2aa4c;
            border: 0;
            border-radius: 16px;
            color: #101820;
            cursor: pointer;
            font-size: 18px;
            font-weight: 700;
            min-height: 64px;
        }

        button:active {
            transform: scale(0.97);
        }

        .wide {
            grid-column: span 3;
        }

        .auth {
            display: grid;
            gap: 10px;
            margin-bottom: 18px;
        }

        input {
            border: 0;
            border-radius: 12px;
            font-size: 16px;
            padding: 14px;
        }

        .message {
            min-height: 22px;
        }
    </style>
</head>
<body>
    <main>
        <h1>PC Remote</h1>
        <section class="auth">
            <input id="password" type="password" placeholder="Password">
            <button onclick="connect()">Connect</button>
            <div id="message" class="message">Enter password to unlock controls.</div>
        </section>
        <section id="controls" class="grid">
            <button onclick="send('voldown')">Vol -</button>
            <button onclick="send('mute')">Mute</button>
            <button onclick="send('volup')">Vol +</button>
            <button onclick="send('brightdown')">Dim</button>
            <button onclick="send('brightup')">Bright +</button>
            <button onclick="send('startmenu')">Start</button>
            <button onclick="send('desktop')">Desktop</button>
            <button onclick="send('screenoff')">Screen Off</button>
            <button onclick="send('lock')">Lock</button>
            <button onclick="send('sleep')">Sleep</button>
            <button onclick="send('restart')">Restart</button>
            <button class="wide" onclick="send('shutdown')">Shutdown</button>
        </section>
    </main>

    <script>
        const passwordInput = document.getElementById('password');
        const controls = document.getElementById('controls');
        const message = document.getElementById('message');
        passwordInput.value = localStorage.getItem('remotePassword') || '';
        let authenticated = false;

        async function connect() {
            const password = passwordInput.value;
            const response = await fetch('/status', {
                headers: {'X-Remote-Password': password}
            });

            if (!response.ok) {
                authenticated = false;
                controls.classList.remove('unlocked');
                localStorage.removeItem('remotePassword');
                message.textContent = response.status === 401 ? 'Wrong password. Access denied.' : 'Could not connect.';
                return;
            }

            authenticated = true;
            localStorage.setItem('remotePassword', password);
            controls.classList.add('unlocked');
            message.textContent = 'Connected. Controls unlocked.';
        }

        async function send(command) {
            if (!authenticated) {
                message.textContent = 'Enter correct password first.';
                return;
            }

            const password = passwordInput.value;
            const response = await fetch(`/${command}`, {
                headers: {'X-Remote-Password': password}
            });
            if (!response.ok) {
                if (response.status === 401) {
                    authenticated = false;
                    controls.classList.remove('unlocked');
                    localStorage.removeItem('remotePassword');
                    message.textContent = 'Wrong password. Access denied.';
                    return;
                }
                alert(`Command failed: ${command}`);
            }
        }

        if (passwordInput.value) {
            connect();
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

    if not CONFIG.get('is_configured', True):
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
        'is_configured': bool(CONFIG.get('is_configured', True)),
        'strict_auto_connect': bool(CONFIG.get('strict_auto_connect', True))
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
        return video_stream.list_folder(path)
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="Directory not found")
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

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
            video_stream.get_file_range_generator(path, start, end),
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
            video_stream.get_file_range_generator(path, 0, file_size - 1),
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
                                    remote_desktop.user32.ShowWindow(h_val, 9)
                                remote_desktop.user32.SetForegroundWindow(h_val)
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
        os.path.join(BASE_DIR, 'desktop-gui', 'dist-electron', 'win-unpacked', 'Remote PC GUI.exe'),
        os.path.join(BASE_DIR, 'Remote PC GUI.exe'),
        os.path.join(BASE_DIR, 'desktop-gui', 'dist-electron', 'Remote PC GUI Setup 0.0.0.exe'),
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
def trigger_phone_action(ip: str = Query(...), action: str = Query(...), value: str = Query(default="")) -> dict:
    import urllib.parse
    import urllib.request
    try:
        url = f"http://{ip}:5056/action?type={urllib.parse.quote(action)}&value={urllib.parse.quote(value)}"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=2.5) as resp:
            data = resp.read().decode('utf-8')
            try:
                return json.loads(data)
            except Exception:
                return {'status': 'ok', 'response': resp.status}
    except Exception as e:
        return {'status': 'error', 'detail': str(e)}

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
        creation_flags = 0
        if sys.platform == 'win32':
            creation_flags = subprocess.CREATE_NO_WINDOW
        subprocess.run(
            ["adb", "forward", "tcp:15056", "tcp:5056"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=1.0,
            creationflags=creation_flags
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

@app.post('/phone/storage/download')
def download_file_from_phone(ip: str = Query(default=""), file_id: str = Query(...), file_type: str = Query(default="image"), filename: str = Query(default="file")) -> dict:
    import urllib.parse
    import urllib.request
    import shutil
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

    if os.path.exists(save_path) and os.path.getsize(save_path) > 0:
        return {'status': 'ok', 'saved_path': save_path, 'filename': safe_filename, 'cached': True}

    for target in targets:
        base = target if target.startswith("http://") else (f"http://{target}" if ":" in target else f"http://{target}:5056")
        url = f"{base}/storage/file?id={file_id}&type={urllib.parse.quote(file_type)}"
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=30.0) as resp, open(save_path, 'wb') as out_f:
                shutil.copyfileobj(resp, out_f)
            log_server_event(f"Downloaded '{safe_filename}' from phone to {save_path}", "SUCCESS")
            return {'status': 'ok', 'saved_path': save_path, 'filename': safe_filename}
        except Exception:
            continue
    return {'status': 'error', 'detail': 'Failed to download file from all mobile endpoints'}

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
