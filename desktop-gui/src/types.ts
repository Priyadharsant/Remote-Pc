export interface ServerStatus {
  status: string;
  name: string;
  ip: string;
  port: number;
  pin: string;
  qr_code: string;
  connected_clients: number;
  clients_connected?: number;
  clients?: Array<{
    name: string;
    id: string;
    ip: string;
    last_seen?: number;
    auto_connect?: boolean;
  }>;
  connected_clients_map?: Record<string, {
    name: string;
    id: string;
    ip: string;
    last_seen: number;
    auto_connect?: boolean;
  }>;
  is_configured?: boolean;
  strict_auto_connect?: boolean;
}

export interface SystemStats {
  cpu_percent: number;
  memory_percent: number;
  memory_used_gb: number;
  memory_total_gb: number;
  battery_percent: number;
  battery_plugged: boolean;
  uptime_seconds: number;
  network?: number;
  mac?: string;
}

export interface TrustedDevice {
  name: string;
  id: string;
  ip: string;
  token?: string;
  auto_connect: boolean;
}

export interface PhoneFileItem {
  id: number;
  name: string;
  type: 'image' | 'video' | 'audio' | 'download' | 'file';
  size: number;
  date: number;
}

export interface PairingRequest {
  ip: string;
  name: string;
  id: string;
  time: number;
}

export interface PhoneDeviceInfo {
  status: string;
  model: string;
  manufacturer?: string;
  android_version: string;
  battery: number;
  battery_health?: string;
  is_charging: boolean;
  storage_free_gb: number;
  storage_total_gb: number;
  ram_used_mb: number;
  ram_total_mb: number;
  screen_res: string;
  screen_orientation?: string;
  wifi_ssid: string;
  ip?: string;
}

export interface InstalledApp {
  name: string;
  packageName: string;
  versionName: string;
  isSystem: boolean;
}

export interface PhoneNotification {
  id: string;
  packageName: string;
  appName: string;
  title: string;
  content: string;
  time: string;
}

export interface UpdateProgress {
  percent: number;
  transferred: number;
  total: number;
  speed?: string;
  streams?: number;
}

export interface CameraRecordingStatus {
  status: 'Disconnected' | 'Ready' | 'Recording' | 'Finalizing' | 'Saved' | 'Error';
  is_recording: boolean;
  is_connected: boolean;
  client_ip: string | null;
  stream_port: number;
  destination_dir: string;
  disk_free_gb: number;
  active_resolution: string;
  active_fps: number;
  bitrate_kbps: number;
  audio_status: string;
  elapsed_seconds: number;
  file_size_bytes: number;
  current_file: string | null;
  current_file_path: string | null;
  has_preview?: boolean;
  phone_camera_active?: boolean;
  error_message: string | null;
}

export interface CameraRecordingItem {
  name: string;
  path: string;
  size_bytes: number;
  size_mb: number;
  created_at: string;
  created_timestamp: number;
}

declare global {
  interface Window {
    electronAPI?: {
      minimizeWindow: () => void;
      maximizeWindow: () => void;
      closeWindow: () => void;
      hideToTray: () => void;
      isMaximized: () => Promise<boolean>;
      openExternalFolder: (folderPath?: string) => Promise<boolean>;
      selectDirectory?: (defaultPath?: string) => Promise<string | null>;
      openFileItem: (filePath: string) => Promise<boolean>;
      openExternalUrl: (url: string) => Promise<boolean>;
      openExternal: (url: string) => Promise<boolean>;
      launchScrcpy: (phoneIp?: string) => Promise<{ success: boolean; message?: string; error?: string }>;
      startServer: () => Promise<{ success: boolean; error?: string }>;
      getAppVersion: () => Promise<string>;
      downloadAndInstallUpdate: (url: string, version: string) => Promise<{ success: boolean; error?: string }>;
      onUpdateProgress?: (callback: (progress: UpdateProgress) => void) => () => void;
      getPathForFile?: (file: File) => string;
      uploadFileToPhone?: (data: { phoneIp: string; filePath?: string; fileName?: string; fileBuffer?: ArrayBuffer }) => Promise<{ status: string; filename?: string }>;
      startDragPhoneFile?: (data: { phoneIp: string; fileId: number; fileType: string; fileName: string }) => void;
    };
  }
}

