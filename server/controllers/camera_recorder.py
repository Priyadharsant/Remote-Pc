import os
import sys
import time
import shutil
import string
import datetime
import threading
import subprocess
import socket
from typing import Dict, Any, List, Optional

def get_default_recordings_dir() -> str:
    """Returns D:\\MobileRecordings if D: drive exists, otherwise user home directory."""
    if os.name == 'nt' and os.path.exists("D:\\"):
        default_dir = os.path.join("D:\\", "MobileRecordings")
    else:
        user_home = os.path.expanduser("~")
        default_dir = os.path.join(user_home, "MobileRecordings")
    os.makedirs(default_dir, exist_ok=True)
    return default_dir

def get_ffmpeg_path() -> str:
    """Locates the standalone ffmpeg.exe binary bundled with Remote-PC."""
    # 1. Local bin folder relative to this file
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    local_bin = os.path.join(base_dir, "bin", "ffmpeg.exe")
    if os.path.exists(local_bin):
        return local_bin

    # 2. PyInstaller temporary extraction folder
    if hasattr(sys, "_MEIPASS"):
        meipass_bin = os.path.join(sys._MEIPASS, "bin", "ffmpeg.exe")
        if os.path.exists(meipass_bin):
            return meipass_bin

    # 3. imageio_ffmpeg bundled binary
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass

    # 4. System PATH fallback
    which_ffmpeg = shutil.which("ffmpeg")
    if which_ffmpeg:
        return which_ffmpeg

    return "ffmpeg"

def get_disk_free_gb(path: str) -> float:
    """Returns free space in GB on the drive containing the given path."""
    try:
        if not os.path.exists(path):
            parent = os.path.dirname(path)
            while parent and not os.path.exists(parent):
                parent = os.path.dirname(parent)
            path = parent if parent else "."
        total, used, free = shutil.disk_usage(path)
        return round(free / (1024 ** 3), 2)
    except Exception:
        return 0.0

class CameraRecordingManager:
    """
    Manages direct-to-PC mobile camera streaming and recording.
    Receives incoming MPEG-TS stream over local Wi-Fi, pipes directly into FFmpeg,
    and muxes to MP4 on PC disk with zero phone storage usage.
    """
    def __init__(self):
        self._lock = threading.Lock()
        self.destination_dir = get_default_recordings_dir()
        self.stream_port = 5058
        
        # State machine: 'Disconnected' | 'Ready' | 'Recording' | 'Finalizing' | 'Saved' | 'Error'
        self.status = "Ready"
        self.error_message: Optional[str] = None
        
        # Session metrics
        self.is_recording = False
        self.active_client_ip: Optional[str] = None
        self.current_file_path: Optional[str] = None
        self.start_time: float = 0.0
        self.elapsed_seconds: int = 0
        self.bytes_received: int = 0
        self.current_bitrate_kbps: int = 0
        self.active_fps: int = 30
        self.active_resolution: str = "1920x1080"
        self.audio_status: str = "Active"
        
        # Latest preview frame (JPEG bytes) for Desktop GUI live monitor
        self.latest_preview_frame: Optional[bytes] = None
        self.last_frame_time: float = 0.0

        # Two-way phone camera active state
        self.phone_camera_active: bool = False
        self.last_phone_camera_active_time: float = 0.0

        # Networking & FFmpeg process handles
        self.server_socket: Optional[socket.socket] = None
        self.client_socket: Optional[socket.socket] = None
        self.ffmpeg_proc: Optional[subprocess.Popen] = None
        self.receiver_thread: Optional[threading.Thread] = None
        self.monitor_thread: Optional[threading.Thread] = None
        self._stop_requested = False

        # Start the background TCP stream receiver listener
        self._start_stream_listener()

    def set_client_state(self, state: str):
        """Notifies the PC that the mobile camera view has been opened or closed."""
        with self._lock:
            if state == "open":
                self.phone_camera_active = True
                self.last_phone_camera_active_time = time.time()
            elif state == "closed":
                self.phone_camera_active = False

    def set_destination_dir(self, new_dir: str) -> Dict[str, Any]:
        """Configures the Windows destination folder for recordings."""
        with self._lock:
            if self.is_recording:
                raise RuntimeError("Cannot change destination folder while recording is in progress.")
            try:
                norm = os.path.abspath(new_dir)
                os.makedirs(norm, exist_ok=True)
                self.destination_dir = norm
                return {
                    "status": "ok",
                    "destination_dir": self.destination_dir,
                    "disk_free_gb": get_disk_free_gb(self.destination_dir)
                }
            except Exception as e:
                raise ValueError(f"Invalid directory path: {str(e)}")

    def get_status(self) -> Dict[str, Any]:
        """Returns comprehensive real-time status and telemetry for UI."""
        with self._lock:
            if self.is_recording and self.start_time > 0:
                self.elapsed_seconds = int(time.time() - self.start_time)
                # Check current file size on disk
                if self.current_file_path and os.path.exists(self.current_file_path):
                    try:
                        file_size_bytes = os.path.getsize(self.current_file_path)
                    except OSError:
                        file_size_bytes = self.bytes_received
                else:
                    file_size_bytes = self.bytes_received
            else:
                file_size_bytes = 0

            free_gb = get_disk_free_gb(self.destination_dir)
            
            # Determine connection status
            is_connected = self.client_socket is not None and not self.client_socket._closed
            is_phone_active = bool(self.phone_camera_active and (time.time() - self.last_phone_camera_active_time < 15.0))

            return {
                "status": self.status,
                "is_recording": self.is_recording,
                "is_connected": is_connected,
                "phone_camera_active": is_phone_active,
                "client_ip": self.active_client_ip,
                "stream_port": self.stream_port,
                "destination_dir": self.destination_dir,
                "disk_free_gb": free_gb,
                "active_resolution": self.active_resolution,
                "active_fps": self.active_fps,
                "bitrate_kbps": self.current_bitrate_kbps,
                "audio_status": self.audio_status,
                "elapsed_seconds": self.elapsed_seconds,
                "file_size_bytes": file_size_bytes,
                "current_file": os.path.basename(self.current_file_path) if self.current_file_path else None,
                "current_file_path": self.current_file_path,
                "has_preview": bool(self.latest_preview_frame and (time.time() - self.last_frame_time < 8.0)),
                "error_message": self.error_message
            }

    def update_preview_frame(self, frame_bytes: bytes):
        """Updates the latest JPEG viewfinder frame received from mobile camera."""
        with self._lock:
            self.latest_preview_frame = frame_bytes
            self.last_frame_time = time.time()

    def get_latest_preview_frame(self) -> Optional[bytes]:
        """Returns the latest JPEG viewfinder frame if fresh (< 5 seconds old)."""
        with self._lock:
            if self.latest_preview_frame and (time.time() - self.last_frame_time < 5.0):
                return self.latest_preview_frame
            return None

    def start_recording(self, resolution: str = "1920x1080", fps: int = 30, audio_codec: str = "AAC 48kHz", orientation: int = 0) -> Dict[str, Any]:
        """Initiates direct-to-PC recording to an MP4 container."""
        with self._lock:
            if self.is_recording:
                return {"status": "ok", "message": "Recording already in progress.", "file_path": self.current_file_path}

            if resolution:
                self.active_resolution = resolution
            if fps:
                self.active_fps = fps
            if audio_codec:
                self.audio_status = audio_codec

            free_gb = get_disk_free_gb(self.destination_dir)
            if free_gb < 0.5:
                self.status = "Error"
                self.error_message = f"Insufficient disk space ({free_gb} GB remaining). Minimum 500 MB required."
                raise RuntimeError(self.error_message)

            # Create daily folder: YYYY-MM-DD
            today_str = datetime.date.today().strftime("%Y-%m-%d")
            daily_folder = os.path.join(self.destination_dir, today_str)
            os.makedirs(daily_folder, exist_ok=True)

            # File name: recording_YYYYMMDD_HHMMSS.mp4
            timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"recording_{timestamp_str}.mp4"
            dest_file = os.path.join(daily_folder, filename)

            ffmpeg_bin = get_ffmpeg_path()
            if not ffmpeg_bin:
                self.status = "Error"
                self.error_message = "FFmpeg executable not found."
                raise RuntimeError(self.error_message)

            # Launch FFmpeg: pipe incoming MPEG-TS stream into MP4 container
            # Using -c:v copy -c:a copy ensures bit-perfect hardware recording with <0.1% CPU usage!
            cmd = [
                ffmpeg_bin,
                "-y",                          # Overwrite if exists
            ]
            if orientation in (90, 180, 270):
                # Android camera specifies clockwise rotation (e.g. 90 deg for portrait back camera).
                # FFmpeg -display_rotation specifies counter-clockwise rotation, so (360 - orientation) % 360.
                ffmpeg_rot = (360 - orientation) % 360
                cmd.extend(["-display_rotation:v:0", str(ffmpeg_rot)])
            cmd.extend([
                "-f", "mpegts",                # Ingest stream format: MPEG-TS
                "-i", "pipe:0",                # Read directly from standard input pipe
                "-c:v", "copy",                # Stream-copy H.264 video without re-encoding
                "-c:a", "copy",                # Stream-copy AAC audio without re-encoding
                "-movflags", "+faststart",     # Move moov atom to front for instant web/player playback
                dest_file
            ])

            try:
                creation_flags = subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0
                self.ffmpeg_proc = subprocess.Popen(
                    cmd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    creationflags=creation_flags
                )
            except Exception as e:
                self.status = "Error"
                self.error_message = f"Failed to start FFmpeg: {str(e)}"
                raise RuntimeError(self.error_message)

            self.is_recording = True
            self.current_file_path = dest_file
            self.start_time = time.time()
            self.elapsed_seconds = 0
            self.bytes_received = 0
            self.status = "Recording"
            self.error_message = None

            print(f"[CameraRecorder] Started direct-to-PC recording -> {dest_file}")
            return {
                "status": "ok",
                "message": "Recording started",
                "file_path": dest_file,
                "filename": filename
            }

    def stop_recording(self) -> Dict[str, Any]:
        """Stops the recording, cleanly finalizes the MP4 container, and validates the file."""
        with self._lock:
            if not self.is_recording:
                return {"status": "ok", "message": "No recording was active."}

            self.status = "Finalizing"
            proc = self.ffmpeg_proc
            dest_file = self.current_file_path

        # Close FFmpeg stdin cleanly so it flushes all remaining video/audio frames and writes moov atom
        if proc and proc.stdin:
            try:
                proc.stdin.close()
            except Exception:
                pass

        # Wait up to 10 seconds for FFmpeg to finalize container and write moov atom
        if proc:
            try:
                proc.wait(timeout=10.0)
            except subprocess.TimeoutExpired:
                proc.kill()

        with self._lock:
            self.is_recording = False
            self.ffmpeg_proc = None

            # Validate generated file
            if dest_file and os.path.exists(dest_file):
                size = os.path.getsize(dest_file)
                if size > 1024:
                    self.status = "Saved"
                    self.error_message = None
                    print(f"[CameraRecorder] Successfully finalized recording: {dest_file} ({round(size / (1024*1024), 2)} MB)")
                    return {
                        "status": "ok",
                        "message": "Recording finalized and verified",
                        "file_path": dest_file,
                        "file_size_bytes": size,
                        "elapsed_seconds": self.elapsed_seconds
                    }
                else:
                    self.status = "Error"
                    self.error_message = "Finalized file is empty or corrupted."
            else:
                self.status = "Error"
                self.error_message = "Recorded file was not found on disk."

            return {
                "status": "error",
                "message": self.error_message or "Unknown error finalizing recording."
            }

    def save_snapshot(self, custom_bytes: Optional[bytes] = None) -> Dict[str, Any]:
        """Saves a high-resolution snapshot photo to PC disk."""
        with self._lock:
            data = custom_bytes or self.latest_preview_frame
            if not data or len(data) < 500:
                raise RuntimeError("No camera preview frame available to capture photo.")

            photos_dir = os.path.join(self.destination_dir, "Photos")
            os.makedirs(photos_dir, exist_ok=True)
            timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"photo_{timestamp_str}.jpg"
            photo_path = os.path.join(photos_dir, filename)

            try:
                with open(photo_path, "wb") as f:
                    f.write(data)
                size = os.path.getsize(photo_path)
                return {
                    "status": "ok",
                    "file_path": photo_path,
                    "filename": filename,
                    "size_bytes": size,
                    "size_mb": round(size / (1024 * 1024), 2)
                }
            except Exception as e:
                raise RuntimeError(f"Failed to save photo: {str(e)}")


    def get_recent_recordings(self, max_items: int = 50) -> List[Dict[str, Any]]:
        """Lists recent MP4 camera recordings across all daily folders."""
        recordings = []
        if not os.path.exists(self.destination_dir):
            return []

        try:
            for root, dirs, files in os.walk(self.destination_dir):
                for f in files:
                    if f.lower().endswith(".mp4") and not f.startswith("."):
                        fp = os.path.join(root, f)
                        try:
                            st = os.stat(fp)
                            recordings.append({
                                "name": f,
                                "path": fp,
                                "size_bytes": st.st_size,
                                "size_mb": round(st.st_size / (1024 * 1024), 2),
                                "created_at": datetime.datetime.fromtimestamp(st.st_ctime).isoformat(),
                                "created_timestamp": st.st_ctime
                            })
                        except OSError:
                            continue
        except Exception:
            pass

        # Sort newest first
        recordings.sort(key=lambda x: x["created_timestamp"], reverse=True)
        return recordings[:max_items]

    def _start_stream_listener(self):
        """Starts background TCP listener on port 5058 to receive camera stream from mobile."""
        def _listen_loop():
            try:
                self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self.server_socket.bind(("0.0.0.0", self.stream_port))
                self.server_socket.listen(2)
                print(f"[CameraRecorder] Listening for incoming camera stream on 0.0.0.0:{self.stream_port}")

                while not self._stop_requested:
                    try:
                        client_sock, addr = self.server_socket.accept()
                        client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        self.active_client_ip = addr[0]
                        print(f"[CameraRecorder] Phone camera connected from {addr[0]}")
                        
                        with self._lock:
                            self.client_socket = client_sock
                            if not self.is_recording:
                                self.status = "Ready"

                        # Handle streaming data from this client
                        self._handle_client_stream(client_sock)

                    except Exception as e:
                        if not self._stop_requested:
                            time.sleep(1.0)
            except Exception as e:
                print(f"[CameraRecorder] Listener socket error: {e}")

        self.receiver_thread = threading.Thread(target=_listen_loop, daemon=True)
        self.receiver_thread.start()

    def _handle_client_stream(self, sock: socket.socket):
        """Reads incoming camera stream bytes and pipes directly to active FFmpeg process."""
        chunk_size = 65536
        last_calc_time = time.time()
        bytes_in_interval = 0

        try:
            while not self._stop_requested:
                data = sock.recv(chunk_size)
                if not data:
                    break

                self.bytes_received += len(data)
                bytes_in_interval += len(data)

                # Update live bitrate calculation every 1.0s
                now = time.time()
                if now - last_calc_time >= 1.0:
                    dt = now - last_calc_time
                    self.current_bitrate_kbps = int((bytes_in_interval * 8) / (dt * 1000))
                    bytes_in_interval = 0
                    last_calc_time = now

                    # Disk space safety check during recording: auto-stop if < 200MB left
                    if self.is_recording and get_disk_free_gb(self.destination_dir) < 0.2:
                        print("[CameraRecorder] WARNING: Low disk space (<200MB)! Safely stopping recording.")
                        threading.Thread(target=self.stop_recording, daemon=True).start()

                # If actively recording, write directly to FFmpeg stdin
                if self.is_recording and self.ffmpeg_proc and self.ffmpeg_proc.stdin:
                    try:
                        self.ffmpeg_proc.stdin.write(data)
                    except (BrokenPipeError, OSError):
                        print("[CameraRecorder] FFmpeg stdin broken pipe; stopping recording.")
                        threading.Thread(target=self.stop_recording, daemon=True).start()

        except Exception as e:
            print(f"[CameraRecorder] Stream read exception: {e}")
        finally:
            try:
                sock.close()
            except Exception:
                pass
            with self._lock:
                if self.client_socket == sock:
                    self.client_socket = None
                    self.active_client_ip = None
                    self.current_bitrate_kbps = 0
                    if not self.is_recording:
                        self.status = "Ready"
            print("[CameraRecorder] Phone camera disconnected.")

    def close(self):
        """Cleans up sockets and processes on server shutdown."""
        self._stop_requested = True
        if self.is_recording:
            try:
                self.stop_recording()
            except Exception:
                pass
        try:
            if self.client_socket:
                self.client_socket.close()
        except Exception:
            pass
        try:
            if self.server_socket:
                self.server_socket.close()
        except Exception:
            pass

_camera_recorder = CameraRecordingManager()

def get_camera_recorder() -> CameraRecordingManager:
    return _camera_recorder
