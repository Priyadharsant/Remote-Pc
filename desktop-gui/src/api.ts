import type {
  ServerStatus, SystemStats, TrustedDevice, PhoneFileItem, PairingRequest,
  PhoneDeviceInfo, CameraRecordingStatus, CameraRecordingItem
} from './types';

const BASE_URL = 'http://127.0.0.1:5055';

export const api = {
  async getStatus(): Promise<ServerStatus> {
    const res = await fetch(`${BASE_URL}/status`);
    if (!res.ok) throw new Error('Failed to fetch status');
    return res.json();
  },

  async getSystemStats(): Promise<SystemStats> {
    const res = await fetch(`${BASE_URL}/system/stats`);
    if (!res.ok) throw new Error('Failed to fetch system stats');
    return res.json();
  },

  async getPairingRequests(): Promise<PairingRequest[]> {
    const res = await fetch(`${BASE_URL}/pair/requests`);
    if (!res.ok) return [];
    const data = await res.json();
    return data.requests || [];
  },

  async approvePairing(ip: string, autoConnect = true): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/pair/approve?ip=${encodeURIComponent(ip)}&auto_connect=${autoConnect}`, {
      method: 'POST'
    });
    return res.ok;
  },

  async getTrustedDevices(): Promise<TrustedDevice[]> {
    const res = await fetch(`${BASE_URL}/pair/list`);
    if (!res.ok) return [];
    const data = await res.json();
    return data.devices || [];
  },

  async revokeDevice(deviceId: string): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/pair/revoke?device_id=${encodeURIComponent(deviceId)}`, {
      method: 'POST'
    });
    return res.ok;
  },

  async requestPairToPhone(phoneIp: string): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/phone/request-pair?ip=${encodeURIComponent(phoneIp)}`, {
      method: 'POST'
    });
    return res.ok;
  },

  // Media & Controls on PC
  async sendMediaAction(action: 'playpause' | 'next' | 'prev' | 'mute' | 'desktop' | 'lock' | 'sleep' | 'restart' | 'shutdown'): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/${action}`, { method: 'POST' });
    return res.ok;
  },

  async setVolume(vol: number): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/volume/${vol}`, { method: 'POST' });
    return res.ok;
  },

  async setBrightness(level: number): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/brightness/${level}`, { method: 'POST' });
    return res.ok;
  },

  // Remote Mobile Action Controls
  async sendPhoneAction(phoneIp: string, action: string, value = ''): Promise<{ status: string; detail?: string }> {
    try {
      const res = await fetch(`${BASE_URL}/phone/action?ip=${encodeURIComponent(phoneIp)}&action=${encodeURIComponent(action)}&value=${encodeURIComponent(value)}`, {
        method: 'POST'
      });
      if (!res.ok) return { status: 'error' };
      return res.json();
    } catch (e: any) {
      return { status: 'error', detail: e.message };
    }
  },

  async getPhoneDeviceInfo(phoneIp: string): Promise<PhoneDeviceInfo | null> {
    try {
      const res = await fetch(`${BASE_URL}/phone/device-info?ip=${encodeURIComponent(phoneIp)}`);
      if (!res.ok) return null;
      return res.json();
    } catch {
      return null;
    }
  },

  // Clipboard & Toast
  async sendClipboardToPhone(phoneIp: string, text: string): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/phone/send-clipboard?ip=${encodeURIComponent(phoneIp)}&text=${encodeURIComponent(text)}`, {
      method: 'POST'
    });
    return res.ok;
  },

  async sendToastToPhone(phoneIp: string, msg: string): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/phone/send-toast?ip=${encodeURIComponent(phoneIp)}&message=${encodeURIComponent(msg)}`, {
      method: 'POST'
    });
    return res.ok;
  },

  // Mobile Storage Explorer
  async getPhoneFiles(phoneIp: string, category = 'all'): Promise<PhoneFileItem[]> {
    try {
      const res = await fetch(`${BASE_URL}/phone/storage/list?ip=${encodeURIComponent(phoneIp)}&category=${encodeURIComponent(category)}`);
      if (!res.ok) return [];
      const data = await res.json();
      return data.files || [];
    } catch {
      return [];
    }
  },

  async downloadPhoneFile(phoneIp: string, fileId: number, fileType: string, filename: string): Promise<string | null> {
    const res = await fetch(`${BASE_URL}/phone/storage/download?ip=${encodeURIComponent(phoneIp)}&file_id=${fileId}&file_type=${encodeURIComponent(fileType)}&filename=${encodeURIComponent(filename)}`, {
      method: 'POST'
    });
    if (!res.ok) return null;
    const data = await res.json();
    return data.saved_path || null;
  },

  getFilePreviewUrl(phoneIp: string, fileId: number, fileType: string, filename: string): string {
    return `${BASE_URL}/phone/storage/preview?ip=${encodeURIComponent(phoneIp)}&file_id=${fileId}&file_type=${encodeURIComponent(fileType)}&filename=${encodeURIComponent(filename)}`;
  },

  async streamMediaOnPc(streamUrl: string): Promise<boolean> {
    try {
      const res = await fetch(`${BASE_URL}/player/open?url=${encodeURIComponent(streamUrl)}`, {
        method: 'POST'
      });
      return res.ok;
    } catch {
      return false;
    }
  },

  async setupServer(name: string, password: string, strictAutoConnect: boolean = true): Promise<boolean> {
    try {
      const res = await fetch(`${BASE_URL}/server/setup?name=${encodeURIComponent(name)}&password=${encodeURIComponent(password)}&strict_auto_connect=${strictAutoConnect}`, {
        method: 'POST'
      });
      return res.ok;
    } catch {
      return false;
    }
  },

  async updateServerSettings(name: string, password?: string, strictAutoConnect: boolean = true): Promise<boolean> {
    try {
      const passParam = password ? `&password=${encodeURIComponent(password)}` : '';
      const res = await fetch(`${BASE_URL}/server/settings?name=${encodeURIComponent(name)}${passParam}&strict_auto_connect=${strictAutoConnect}`, {
        method: 'POST'
      });
      return res.ok;
    } catch {
      return false;
    }
  },

  async checkUpdate(): Promise<{
    update_available: boolean;
    current_version: string;
    latest_version?: string;
    release_title?: string;
    release_notes?: string;
    exe_url?: string;
    apk_url?: string;
    html_url?: string;
    error?: string;
  }> {
    const res = await fetch(`${BASE_URL}/system/update/check`);
    return res.json();
  },

  async applyUpdate(downloadUrl: string): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/system/update/apply?download_url=${encodeURIComponent(downloadUrl)}`, {
      method: 'POST'
    });
    return res.ok;
  },

  async getUpdateStatus(): Promise<{
    status: string;
    progress: number;
    message: string;
    error?: string | null;
  }> {
    const res = await fetch(`${BASE_URL}/system/update/status`);
    return res.json();
  },

  // Direct-to-PC Camera Recording API
  async getCameraStatus(): Promise<CameraRecordingStatus> {
    const res = await fetch(`${BASE_URL}/camera/status`);
    if (!res.ok) throw new Error('Failed to fetch camera recording status');
    return res.json();
  },

  async startCameraRecording(resolution = '1920x1080', fps = 30): Promise<{ status: string; message?: string; file_path?: string; filename?: string }> {
    const res = await fetch(`${BASE_URL}/camera/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolution, fps })
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: 'Failed to start recording' }));
      throw new Error(err.detail || 'Failed to start recording');
    }
    return res.json();
  },

  async stopCameraRecording(): Promise<{ status: string; message?: string; file_path?: string; file_size_bytes?: number; elapsed_seconds?: number }> {
    const res = await fetch(`${BASE_URL}/camera/stop`, {
      method: 'POST'
    });
    return res.json();
  },

  async setCameraDestination(destinationDir: string): Promise<{ status: string; destination_dir: string; disk_free_gb: number }> {
    const res = await fetch(`${BASE_URL}/camera/destination`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ destination_dir: destinationDir })
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: 'Failed to update destination folder' }));
      throw new Error(err.detail || 'Failed to update destination folder');
    }
    return res.json();
  },

  async getCameraRecordings(maxItems = 50): Promise<CameraRecordingItem[]> {
    try {
      const res = await fetch(`${BASE_URL}/camera/recordings?max_items=${maxItems}`);
      if (!res.ok) return [];
      const data = await res.json();
      return data.recordings || [];
    } catch {
      return [];
    }
  },

  async openCameraFolder(): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/camera/open-folder`, { method: 'POST' });
    return res.ok;
  },

  async openCameraFile(filePath: string): Promise<boolean> {
    const res = await fetch(`${BASE_URL}/camera/open-file`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ file_path: filePath })
    });
    return res.ok;
  },

  getCameraPreviewUrl(): string {
    return `${BASE_URL}/camera/preview?_t=${Date.now()}`;
  },

  getCameraMjpegStreamUrl(): string {
    return `${BASE_URL}/camera/mjpeg-stream`;
  },

  async takeCameraSnapshot(): Promise<{ status: string; file_path?: string; filename?: string; size_mb?: number; message?: string }> {
    const res = await fetch(`${BASE_URL}/camera/snapshot`, {
      method: 'POST'
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: 'Failed to capture photo' }));
      throw new Error(err.detail || 'Failed to capture photo');
    }
    return res.json();
  },

  async openPhoneCamera(phoneIp: string = ''): Promise<boolean> {
    try {
      const res = await fetch(`${BASE_URL}/phone/action?ip=${encodeURIComponent(phoneIp)}&action=open_camera`, {
        method: 'POST'
      });
      return res.ok;
    } catch {
      return false;
    }
  },

  async sendPhoneCameraAction(action: string, phoneIp: string = '', value: string = ''): Promise<boolean> {
    try {
      const res = await fetch(`${BASE_URL}/phone/action?ip=${encodeURIComponent(phoneIp)}&action=${encodeURIComponent(action)}&value=${encodeURIComponent(value)}`, {
        method: 'POST'
      });
      return res.ok;
    } catch {
      return false;
    }
  },

  async getSyncStatus(): Promise<{ status: string; destination_dir: string; disk_free_gb: number; files_count: number; total_mb: number; last_sync_time: number }> {
    const res = await fetch(`${BASE_URL}/sync/status`);
    if (!res.ok) throw new Error('Failed to fetch sync status');
    return res.json();
  },

  async setSyncDestination(destination_dir: string): Promise<any> {
    const res = await fetch(`${BASE_URL}/sync/destination`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ destination_dir })
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: 'Failed to update folder' }));
      throw new Error(err.detail || 'Failed to update folder');
    }
    return res.json();
  },

  async getSyncHistory(): Promise<any[]> {
    try {
      const res = await fetch(`${BASE_URL}/sync/history`);
      if (!res.ok) return [];
      const data = await res.json();
      return data.history || [];
    } catch {
      return [];
    }
  }
};
