import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Video, Play, Square, Folder, HardDrive, RefreshCw, CheckCircle,
  AlertTriangle, Settings as SettingsIcon, X, Smartphone,
  RotateCcw, Zap, ZapOff, Mic, MicOff, ExternalLink, Camera, Grid as GridIcon
} from 'lucide-react';
import { api } from '../api';
import type { CameraRecordingStatus, CameraRecordingItem, ServerStatus } from '../types';

interface CameraRecordingProps {
  status: ServerStatus | null;
}

export const CameraRecording: React.FC<CameraRecordingProps> = ({ status: serverStatus }) => {
  const [camStatus, setCamStatus] = useState<CameraRecordingStatus | null>(null);
  const [recordings, setRecordings] = useState<CameraRecordingItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [takingPhoto, setTakingPhoto] = useState(false);
  const [showGrid, setShowGrid] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);
  const [destInput, setDestInput] = useState('');
  const [isEditingDest, setIsEditingDest] = useState(false);
  const [showSettingsModal, setShowSettingsModal] = useState<boolean>(false);
  const [selectedQuality, setSelectedQuality] = useState<'1080p' | '720p' | '4k'>('1080p');

  // Simultaneous Control Local States (synced with phone)
  const [isTorchOn, setIsTorchOn] = useState(false);
  const [isMuted, setIsMuted] = useState(false);
  const [lensState, setLensState] = useState<'Back' | 'Front'>('Back');

  const pollTimerRef = useRef<number | null>(null);

  const activeClientIp = serverStatus?.clients && serverStatus.clients.length > 0
    ? serverStatus.clients[0].ip
    : '127.0.0.1';

  const phoneName = serverStatus?.clients && serverStatus.clients.length > 0
    ? serverStatus.clients[0].name
    : 'Connected Phone';

  const fetchStatus = useCallback(async () => {
    try {
      const data = await api.getCameraStatus();
      setCamStatus(data);
      if (!isEditingDest && data.destination_dir) {
        setDestInput(data.destination_dir);
      }
    } catch {
      // ignore
    }
  }, [isEditingDest]);

  const fetchRecordings = useCallback(async () => {
    try {
      const list = await api.getCameraRecordings(30);
      setRecordings(list);
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    fetchStatus();
    fetchRecordings();
    pollTimerRef.current = window.setInterval(() => {
      fetchStatus();
    }, 1000);

    return () => {
      if (pollTimerRef.current) clearInterval(pollTimerRef.current);
    };
  }, [fetchStatus, fetchRecordings]);

  // Open Camera on Phone
  const handleOpenPhoneCamera = async () => {
    setActionError(null);
    setActionSuccess('Sent request to open camera on phone...');
    try {
      await api.openPhoneCamera(activeClientIp);
    } catch (e: any) {
      setActionError('Could not reach phone to open camera.');
    }
  };

  // Simultaneous Remote Controls
  const handleToggleTorch = useCallback(async () => {
    const nextState = !isTorchOn;
    setIsTorchOn(nextState);
    try {
      await api.sendPhoneCameraAction('toggle_torch', activeClientIp, nextState ? 'on' : 'off');
    } catch {
      // Non-critical
    }
  }, [isTorchOn, activeClientIp]);

  const handleFlipLens = useCallback(async () => {
    setLensState(prev => prev === 'Back' ? 'Front' : 'Back');
    try {
      await api.sendPhoneCameraAction('flip_camera', activeClientIp);
    } catch {
      // Non-critical
    }
  }, [activeClientIp]);

  const handleToggleMic = useCallback(async () => {
    const nextMuted = !isMuted;
    setIsMuted(nextMuted);
    try {
      await api.sendPhoneCameraAction('toggle_mic', activeClientIp, nextMuted ? 'mute' : 'unmute');
    } catch {
      // Non-critical
    }
  }, [isMuted, activeClientIp]);

  // Start PC & Phone Recording Simultaneously
  const handleStartRecording = useCallback(async () => {
    setLoading(true);
    setActionError(null);
    setActionSuccess(null);
    try {
      api.sendPhoneCameraAction('start_camera_record', activeClientIp).catch(() => {});

      const res = await api.startCameraRecording(
        selectedQuality === '4k' ? '3840x2160' : selectedQuality === '720p' ? '1280x720' : '1920x1080',
        30
      );
      if (res.status === 'ok') {
        setActionSuccess('Recording started! Streaming directly to PC.');
        fetchStatus();
      } else {
        setActionError(res.message || 'Failed to start recording');
      }
    } catch (e: any) {
      setActionError(e.message || 'Error starting recording');
    } finally {
      setLoading(false);
    }
  }, [activeClientIp, selectedQuality, fetchStatus]);

  // Stop Recording Cleanly
  const handleStopRecording = useCallback(async () => {
    setLoading(true);
    setActionError(null);
    setActionSuccess(null);
    try {
      api.sendPhoneCameraAction('stop_camera_record', activeClientIp).catch(() => {});

      const res = await api.stopCameraRecording();
      if (res.status === 'ok') {
        setActionSuccess(`Video saved! (${Math.round((res.file_size_bytes || 0) / (1024 * 1024))} MB)`);
        fetchStatus();
        fetchRecordings();
      } else {
        setActionError(res.message || 'Warning finalizing video');
      }
    } catch (e: any) {
      setActionError(e.message || 'Error saving video');
    } finally {
      setLoading(false);
    }
  }, [activeClientIp, fetchStatus, fetchRecordings]);

  // Take Snapshot Photo
  const handleTakeSnapshot = useCallback(async () => {
    setTakingPhoto(true);
    setActionError(null);
    try {
      const res = await api.takeCameraSnapshot();
      if (res.status === 'ok') {
        setActionSuccess(`Photo saved! (${res.file_path || 'Saved to Photos'})`);
      } else {
        setActionError(res.message || 'Failed to capture photo');
      }
    } catch (e: any) {
      setActionError(e.message || 'Error capturing photo');
    } finally {
      setTakingPhoto(false);
    }
  }, []);

  const isRecording = Boolean(camStatus?.is_recording);
  const isFinalizing = camStatus?.status === 'Finalizing';
  const isPhoneCameraActive = Boolean(camStatus?.phone_camera_active || camStatus?.has_preview);

  // Keyboard Shortcuts (Space/R: Record, P/C: Photo, G: Grid, F: Flash, M: Mic)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;

      if (e.code === 'Space' || e.key === 'r' || e.key === 'R') {
        e.preventDefault();
        if (isRecording) {
          handleStopRecording();
        } else {
          handleStartRecording();
        }
      } else if (e.key === 'p' || e.key === 'P' || e.key === 'c' || e.key === 'C') {
        e.preventDefault();
        handleTakeSnapshot();
      } else if (e.key === 'g' || e.key === 'G') {
        e.preventDefault();
        setShowGrid(prev => !prev);
      } else if (e.key === 'f' || e.key === 'F') {
        e.preventDefault();
        handleToggleTorch();
      } else if (e.key === 'm' || e.key === 'M') {
        e.preventDefault();
        handleToggleMic();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isRecording, handleStopRecording, handleStartRecording, handleTakeSnapshot, handleToggleTorch, handleToggleMic]);

  const handleSaveDestination = async () => {
    if (!destInput.trim()) return;
    setLoading(true);
    try {
      const res = await api.setCameraDestination(destInput.trim());
      setDestInput(res.destination_dir);
      setIsEditingDest(false);
      setActionSuccess('Save location updated.');
      fetchStatus();
    } catch (e: any) {
      setActionError(e.message || 'Failed to update folder');
    } finally {
      setLoading(false);
    }
  };

  const handleOpenFolder = async () => {
    try {
      await api.openCameraFolder();
    } catch (e: any) {
      setActionError('Could not open folder in File Explorer');
    }
  };

  const handleOpenFile = async (path: string) => {
    try {
      await api.openCameraFile(path);
    } catch (e: any) {
      setActionError('Could not play video');
    }
  };

  const formatSeconds = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const formatBytes = (bytes: number) => {
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  };

  return (
    <div className="space-y-5 pb-12 select-none animate-in fade-in duration-300 max-w-6xl mx-auto">
      {/* Top Header Card */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 p-5 rounded-2xl bg-[#0F172A]/85 border border-cyan-500/20 backdrop-blur-md shadow-xl">
        <div className="flex items-center gap-4">
          <div className={`p-3.5 rounded-2xl transition-all ${
            isRecording
              ? 'bg-rose-500/20 text-rose-400 border border-rose-500/40 animate-pulse'
              : 'bg-cyan-500/15 text-cyan-400 border border-cyan-500/30'
          }`}>
            <Video size={26} />
          </div>
          <div>
            <div className="flex items-center gap-2.5">
              <h1 className="text-lg font-black text-white tracking-wide">Live Camera</h1>
              <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider ${
                isRecording
                  ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40 animate-pulse'
                  : isFinalizing
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                  : isPhoneCameraActive
                  ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40'
                  : 'bg-emerald-500/15 text-emerald-300 border border-emerald-500/30'
              }`}>
                {isRecording ? 'Recording...' : isFinalizing ? 'Saving...' : isPhoneCameraActive ? 'Camera Live' : 'Camera Ready'}
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-0.5">
              Stream live video from your phone directly to this computer. Zero phone storage used.
            </p>
          </div>
        </div>

        {/* Action Header Buttons */}
        <div className="flex items-center gap-2.5 self-end sm:self-center">
          {!isPhoneCameraActive && (
            <button
              onClick={handleOpenPhoneCamera}
              className="px-3.5 py-2 rounded-xl bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 text-xs font-bold transition-all flex items-center gap-1.5 active:scale-95"
              title="Open camera on connected phone"
            >
              <Smartphone size={15} />
              <span>Open on Phone</span>
            </button>
          )}

          <button
            onClick={() => setShowSettingsModal(true)}
            className="px-3.5 py-2 rounded-xl bg-slate-800/80 hover:bg-slate-700/80 border border-slate-700 text-slate-200 text-xs font-bold transition-all flex items-center gap-1.5 active:scale-95"
            title="Camera & storage settings"
          >
            <SettingsIcon size={15} className="text-cyan-400" />
            <span>Camera Settings</span>
          </button>
        </div>
      </div>

      {/* Action Banners */}
      {actionSuccess && (
        <div className="p-3.5 rounded-xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-xs font-bold flex items-center justify-between animate-in fade-in">
          <div className="flex items-center gap-2">
            <CheckCircle size={16} />
            <span>{actionSuccess}</span>
          </div>
          <button onClick={() => setActionSuccess(null)} className="text-emerald-400 hover:text-white text-xs">Dismiss</button>
        </div>
      )}
      {actionError && (
        <div className="p-3.5 rounded-xl bg-rose-500/15 border border-rose-500/30 text-rose-300 text-xs font-bold flex items-center justify-between animate-in fade-in">
          <div className="flex items-center gap-2">
            <AlertTriangle size={16} />
            <span>{actionError}</span>
          </div>
          <button onClick={() => setActionError(null)} className="text-rose-400 hover:text-white text-xs">Dismiss</button>
        </div>
      )}

      {/* Main Viewfinder Monitor (Cinema recorder layout with floating inside controls) */}
      <div className="relative w-full h-[520px] max-h-[70vh] rounded-3xl bg-[#050811] border border-cyan-500/20 overflow-hidden shadow-2xl flex items-center justify-center p-2 group">
        {/* Live Preview Viewfinder (Smooth 30 FPS Stream) */}
        {camStatus?.has_preview ? (
          <img
            src={api.getCameraMjpegStreamUrl()}
            alt="Live Camera View"
            className="max-h-full max-w-full object-contain rounded-2xl shadow-2xl transition-all"
          />
        ) : (
          <div className="flex flex-col items-center justify-center p-6 text-center max-w-sm">
            <div className="w-16 h-16 rounded-2xl bg-cyan-500/10 border border-cyan-500/20 flex items-center justify-center text-cyan-400 mb-3 shadow-inner">
              <Video size={30} className={isPhoneCameraActive ? "animate-pulse" : ""} />
            </div>
            <div className="text-base font-bold text-white tracking-wide">
              {isPhoneCameraActive ? 'Connecting live preview...' : 'Camera Standby'}
            </div>
            <p className="text-xs text-slate-400 mt-1.5 leading-relaxed">
              Open the camera in your phone app or click below to launch it automatically.
            </p>
            <button
              onClick={handleOpenPhoneCamera}
              className="mt-4 px-4 py-2 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-bold transition-all flex items-center gap-2 shadow-lg shadow-cyan-600/25 active:scale-95"
            >
              <Smartphone size={14} />
              <span>Launch Phone Camera</span>
            </button>
          </div>
        )}

        {/* Rule of Thirds 3x3 Grid Overlay */}
        {showGrid && (
          <div className="absolute inset-0 pointer-events-none z-10 grid grid-cols-3 grid-rows-3">
            <div className="border-r border-b border-white/20" />
            <div className="border-r border-b border-white/20" />
            <div className="border-b border-white/20" />
            <div className="border-r border-b border-white/20" />
            <div className="border-r border-b border-white/20" />
            <div className="border-b border-white/20" />
            <div className="border-r border-white/20" />
            <div className="border-r border-white/20" />
            <div />
          </div>
        )}

        {/* Viewfinder Corner Overlays */}
        <div className="absolute top-4 left-4 w-5 h-5 border-t-2 border-l-2 border-cyan-400/50 pointer-events-none rounded-tl-sm" />
        <div className="absolute top-4 right-4 w-5 h-5 border-t-2 border-r-2 border-cyan-400/50 pointer-events-none rounded-tr-sm" />
        <div className="absolute bottom-4 left-4 w-5 h-5 border-b-2 border-l-2 border-cyan-400/50 pointer-events-none rounded-bl-sm" />
        <div className="absolute bottom-4 right-4 w-5 h-5 border-b-2 border-r-2 border-cyan-400/50 pointer-events-none rounded-br-sm" />

        {/* Top Badges Inside Viewfinder */}
        <div className="absolute top-4 inset-x-4 flex items-center justify-between pointer-events-none z-10 px-2">
          <div className="flex items-center gap-2 pointer-events-auto">
            {isRecording ? (
              <div className="flex items-center gap-2 px-3 py-1 rounded-full bg-rose-600/90 text-white text-xs font-black tracking-wider shadow-lg shadow-rose-600/40 animate-pulse">
                <span className="w-2 h-2 rounded-full bg-white" />
                REC
              </div>
            ) : (
              <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-slate-900/85 backdrop-blur-md border border-slate-700/60 text-slate-300 text-xs font-bold">
                <span className={`w-2 h-2 rounded-full ${isPhoneCameraActive ? 'bg-cyan-400' : 'bg-emerald-400'}`} />
                {isPhoneCameraActive ? 'LIVE' : 'STANDBY'}
              </div>
            )}
          </div>

          <div className="flex items-center gap-2 pointer-events-auto">
            <button
              onClick={() => setShowGrid(g => !g)}
              className={`px-2.5 py-1 rounded-full text-xs font-bold transition-all border ${
                showGrid
                  ? 'bg-cyan-500/25 border-cyan-400 text-cyan-300'
                  : 'bg-slate-900/85 backdrop-blur-md border-slate-700/60 text-slate-300 hover:text-white'
              }`}
              title="Toggle Rule-of-Thirds Grid (G)"
            >
              Grid {showGrid ? 'On' : 'Off'}
            </button>
            <div className="px-3 py-1 rounded-full bg-slate-900/85 backdrop-blur-md border border-slate-700/60 text-xs font-bold text-slate-300">
              {selectedQuality.toUpperCase()}
            </div>
          </div>
        </div>

        {/* Floating In-Viewfinder Recorder Controls Bar */}
        <div className="absolute bottom-5 inset-x-0 flex justify-center pointer-events-none z-20 px-4">
          <div className="flex items-center gap-2 sm:gap-3 p-2 px-4 rounded-2xl bg-black/70 backdrop-blur-xl border border-white/15 shadow-2xl pointer-events-auto">
            {/* Record / Stop Button */}
            {isRecording ? (
              <button
                onClick={handleStopRecording}
                disabled={loading || isFinalizing}
                className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-rose-600 to-rose-700 hover:from-rose-500 hover:to-rose-600 text-white font-black text-xs sm:text-sm tracking-wide shadow-lg shadow-rose-600/40 transition-all flex items-center gap-2 active:scale-95 disabled:opacity-50"
                title="Stop Recording (Space or R)"
              >
                <Square size={15} fill="white" />
                <span>{isFinalizing ? 'Saving...' : 'Stop'}</span>
              </button>
            ) : (
              <button
                onClick={handleStartRecording}
                disabled={loading}
                className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-rose-600 to-rose-700 hover:from-rose-500 hover:to-rose-600 text-white font-black text-xs sm:text-sm tracking-wide shadow-lg shadow-rose-600/40 transition-all flex items-center gap-2 active:scale-95 disabled:opacity-50 animate-pulse"
                title="Start Recording (Space or R)"
              >
                <Play size={15} fill="white" />
                <span>Record</span>
              </button>
            )}

            {/* Snap Photo Button */}
            <button
              onClick={handleTakeSnapshot}
              disabled={takingPhoto}
              className="p-2.5 sm:px-3 sm:py-2.5 rounded-xl bg-white/10 hover:bg-white/20 border border-white/10 text-white text-xs font-bold transition-all flex items-center gap-1.5 active:scale-95"
              title="Take Snapshot Photo (P or C)"
            >
              <Camera size={16} className={takingPhoto ? "animate-spin text-amber-400" : "text-emerald-400"} />
              <span className="hidden sm:inline">Photo</span>
            </button>

            <div className="h-6 w-px bg-white/15" />

            {/* Grid Toggle */}
            <button
              onClick={() => setShowGrid(g => !g)}
              className={`p-2.5 rounded-xl border text-xs font-bold transition-all flex items-center justify-center active:scale-95 ${
                showGrid
                  ? 'bg-cyan-500/25 border-cyan-400/50 text-cyan-300'
                  : 'bg-white/10 hover:bg-white/20 border-white/10 text-slate-300'
              }`}
              title="Toggle 3x3 Grid (G)"
            >
              <GridIcon size={16} />
            </button>

            {/* Lens Flip */}
            <button
              onClick={handleFlipLens}
              className="p-2.5 rounded-xl bg-white/10 hover:bg-white/20 border border-white/10 text-slate-200 text-xs font-bold transition-all flex items-center justify-center active:scale-95"
              title={`Flip Lens (${lensState})`}
            >
              <RotateCcw size={16} className="text-cyan-400" />
            </button>

            {/* Flashlight */}
            <button
              onClick={handleToggleTorch}
              className={`p-2.5 rounded-xl border text-xs font-bold transition-all flex items-center justify-center active:scale-95 ${
                isTorchOn
                  ? 'bg-amber-500/25 border-amber-400/50 text-amber-300'
                  : 'bg-white/10 hover:bg-white/20 border-white/10 text-slate-300'
              }`}
              title="Toggle Flashlight (F)"
            >
              {isTorchOn ? <Zap size={16} className="text-amber-400" /> : <ZapOff size={16} className="text-slate-400" />}
            </button>

            {/* Mic */}
            <button
              onClick={handleToggleMic}
              className={`p-2.5 rounded-xl border text-xs font-bold transition-all flex items-center justify-center active:scale-95 ${
                isMuted
                  ? 'bg-rose-500/25 border-rose-400/50 text-rose-300'
                  : 'bg-white/10 hover:bg-white/20 border-white/10 text-slate-300'
              }`}
              title="Toggle Microphone (M)"
            >
              {isMuted ? <MicOff size={16} className="text-rose-400" /> : <Mic size={16} className="text-cyan-400" />}
            </button>
          </div>
        </div>
      </div>

      {/* Exterior HUD Strip: Video Stats & Shortcuts outside viewfinder */}
      <div className="p-4 rounded-2xl bg-[#0F172A]/85 border border-slate-800/80 backdrop-blur-md flex flex-wrap items-center justify-between gap-4 shadow-xl">
        <div className="flex flex-wrap items-center gap-5 text-xs">
          {/* Recording Timer */}
          <div className="flex items-center gap-2">
            <span className="text-slate-400 font-medium">Duration:</span>
            <span className="font-mono font-bold text-cyan-300 text-sm">
              {formatSeconds(camStatus?.elapsed_seconds || 0)}
            </span>
          </div>

          {/* Recorded File Size */}
          <div className="flex items-center gap-2">
            <span className="text-slate-400 font-medium">Recorded Size:</span>
            <span className="font-mono font-bold text-emerald-400">
              {formatBytes(camStatus?.file_size_bytes || 0)}
            </span>
          </div>

          {/* Transfer Bitrate */}
          {camStatus?.bitrate_kbps ? (
            <div className="flex items-center gap-2">
              <span className="text-slate-400 font-medium">Transfer Speed:</span>
              <span className="font-mono font-bold text-cyan-400">
                {(camStatus.bitrate_kbps / 1000).toFixed(1)} Mbps
              </span>
            </div>
          ) : null}

          {/* Shortcuts Pill */}
          <div className="hidden lg:flex items-center gap-1.5 px-3 py-1 rounded-xl bg-slate-900/60 border border-slate-800 text-[11px] text-slate-400">
            <span>Shortcuts:</span>
            <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">Space/R</kbd>
            <span>Record</span>
            <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">P</kbd>
            <span>Photo</span>
            <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">G</kbd>
            <span>Grid</span>
            <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">F</kbd>
            <span>Torch</span>
            <kbd className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 font-mono">M</kbd>
            <span>Mic</span>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="text-right text-xs text-slate-400 hidden sm:block">
            <div className="font-semibold text-slate-200">{phoneName}</div>
            <div className="text-[10px] text-emerald-400 font-medium">Saves to Computer Disk</div>
          </div>
          <button
            onClick={handleOpenFolder}
            className="px-3.5 py-2 rounded-xl bg-slate-800/80 hover:bg-slate-700/80 border border-slate-700 text-slate-200 text-xs font-bold transition-all flex items-center gap-1.5 active:scale-95"
            title="Open recordings folder on computer"
          >
            <Folder size={15} className="text-cyan-400" />
            <span>Open Folder</span>
          </button>
        </div>
      </div>

      {/* Saved Videos Section */}
      <div className="p-6 rounded-3xl bg-[#0F172A]/85 border border-slate-800/80 backdrop-blur-md space-y-4 shadow-xl">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-base font-bold text-white">Saved Videos</h2>
            <p className="text-xs text-slate-400">Recorded directly to your computer in MP4 format.</p>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={fetchRecordings}
              className="p-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-semibold transition-all flex items-center gap-1.5"
            >
              <RefreshCw size={14} />
              <span>Refresh</span>
            </button>
            <button
              onClick={handleOpenFolder}
              className="px-3 py-2 rounded-xl bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 text-xs font-bold transition-all flex items-center gap-1.5"
            >
              <Folder size={14} />
              <span>Browse Folder</span>
            </button>
          </div>
        </div>

        {recordings.length === 0 ? (
          <div className="p-8 rounded-2xl bg-slate-900/50 border border-slate-800/80 text-center text-slate-500 text-xs">
            No videos saved yet. Start recording to save MP4 videos to your computer.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-800 text-[10px] font-bold uppercase tracking-wider text-slate-500">
                  <th className="pb-3 pl-2">File Name</th>
                  <th className="pb-3">Date & Time</th>
                  <th className="pb-3">File Size</th>
                  <th className="pb-3 text-right pr-2">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60">
                {recordings.map((item, idx) => (
                  <tr key={idx} className="hover:bg-slate-800/30 transition-colors group">
                    <td className="py-3 pl-2 font-medium text-slate-200 flex items-center gap-2">
                      <Video size={14} className="text-cyan-400 shrink-0" />
                      <span className="truncate max-w-xs">{item.name}</span>
                    </td>
                    <td className="py-3 text-slate-400">
                      {new Date(item.created_timestamp * 1000).toLocaleString()}
                    </td>
                    <td className="py-3 font-semibold text-emerald-400">
                      {item.size_mb > 0 ? `${item.size_mb} MB` : formatBytes(item.size_bytes)}
                    </td>
                    <td className="py-3 pr-2 text-right">
                      <button
                        onClick={() => handleOpenFile(item.path)}
                        className="px-3 py-1 rounded-lg bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 font-bold transition-all inline-flex items-center gap-1"
                      >
                        <Play size={12} fill="currentColor" />
                        <span>Play</span>
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Internal Camera Settings Modal */}
      {showSettingsModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-md animate-in fade-in duration-200">
          <div className="w-full max-w-lg bg-[#0F172A] border border-cyan-500/30 rounded-3xl p-6 shadow-2xl space-y-6">
            {/* Modal Header */}
            <div className="flex items-center justify-between border-b border-slate-800 pb-4">
              <div className="flex items-center gap-3">
                <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400 border border-cyan-500/30">
                  <SettingsIcon size={20} />
                </div>
                <div>
                  <h3 className="text-base font-bold text-white">Camera Settings</h3>
                  <p className="text-xs text-slate-400">Customize storage and recording preferences</p>
                </div>
              </div>
              <button
                onClick={() => setShowSettingsModal(false)}
                className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800 transition-all"
              >
                <X size={18} />
              </button>
            </div>

            {/* Save Location Section */}
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <label className="text-xs font-bold text-slate-300 flex items-center gap-2">
                  <Folder size={15} className="text-cyan-400" />
                  <span>Save Location</span>
                </label>
                {!isEditingDest && (
                  <button
                    onClick={() => setIsEditingDest(true)}
                    className="text-xs text-cyan-400 hover:text-cyan-300 font-semibold"
                  >
                    Change Folder
                  </button>
                )}
              </div>

              {isEditingDest ? (
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    value={destInput}
                    onChange={(e) => setDestInput(e.target.value)}
                    placeholder="e.g. D:\MobileRecordings"
                    className="flex-1 px-3 py-2 rounded-xl bg-slate-900 border border-cyan-500/40 text-white text-xs font-mono focus:outline-none focus:border-cyan-400"
                  />
                  <button
                    onClick={handleSaveDestination}
                    className="px-3.5 py-2 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-bold transition-all"
                  >
                    Save
                  </button>
                  <button
                    onClick={() => setIsEditingDest(false)}
                    className="px-3 py-2 rounded-xl bg-slate-800 text-slate-400 text-xs font-bold"
                  >
                    Cancel
                  </button>
                </div>
              ) : (
                <div className="flex items-center justify-between p-3 rounded-xl bg-slate-900/90 border border-slate-800 text-xs font-mono text-cyan-300 break-all select-text">
                  <span>{camStatus?.destination_dir || 'D:\\MobileRecordings'}</span>
                  <button
                    onClick={handleOpenFolder}
                    className="p-1 text-slate-400 hover:text-cyan-300 shrink-0 ml-2"
                    title="Open in File Explorer"
                  >
                    <ExternalLink size={14} />
                  </button>
                </div>
              )}
            </div>

            {/* Computer Storage Space Gauge */}
            <div className="p-4 rounded-2xl bg-slate-900/60 border border-slate-800/80 space-y-2">
              <div className="flex items-center justify-between text-xs">
                <span className="font-bold text-slate-300 flex items-center gap-1.5">
                  <HardDrive size={15} className="text-cyan-400" />
                  <span>Computer Storage</span>
                </span>
                <span className="font-bold text-emerald-400">{camStatus?.disk_free_gb ?? '--'} GB Free</span>
              </div>
              <div className="w-full h-2 rounded-full bg-slate-800 overflow-hidden">
                <div
                  className="h-full bg-gradient-to-r from-emerald-500 to-cyan-500 rounded-full transition-all duration-500"
                  style={{ width: `${Math.min(100, Math.max(10, ((camStatus?.disk_free_gb || 0) / 500) * 100))}%` }}
                />
              </div>
              <p className="text-[11px] text-slate-500">
                Recording safely stops automatically if computer storage drops below 200 MB.
              </p>
            </div>

            {/* Video Quality Selection */}
            <div className="space-y-2">
              <label className="text-xs font-bold text-slate-300">Video Quality</label>
              <div className="grid grid-cols-3 gap-2.5">
                {[
                  { id: '720p', label: '720p HD', desc: 'Fast & Lightweight' },
                  { id: '1080p', label: '1080p Full HD', desc: 'Recommended' },
                  { id: '4k', label: '4K Ultra HD', desc: 'Highest Detail' },
                ].map((opt) => (
                  <button
                    key={opt.id}
                    onClick={() => setSelectedQuality(opt.id as any)}
                    className={`p-3 rounded-xl border text-left transition-all ${
                      selectedQuality === opt.id
                        ? 'bg-cyan-500/15 border-cyan-500/40 text-cyan-300 shadow-sm shadow-cyan-500/10'
                        : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    <div className="font-bold text-xs">{opt.label}</div>
                    <div className="text-[10px] text-slate-500 mt-0.5">{opt.desc}</div>
                  </button>
                ))}
              </div>
            </div>

            {/* Technical Stream Information */}
            <div className="p-3.5 rounded-2xl bg-slate-900/40 border border-slate-800/80 text-[11px] text-slate-400 space-y-1.5">
              <div className="font-bold text-slate-300">Technical Details</div>
              <div className="flex justify-between">
                <span>Video Format</span>
                <span className="text-slate-200 font-medium">MP4 (H.264 + AAC 48kHz)</span>
              </div>
              <div className="flex justify-between">
                <span>Connection</span>
                <span className="text-slate-200 font-mono">Port TCP {camStatus?.stream_port || 5058}</span>
              </div>
              <div className="flex justify-between">
                <span>Current Bitrate</span>
                <span className="text-cyan-300 font-mono">
                  {camStatus?.bitrate_kbps ? `${(camStatus.bitrate_kbps / 1000).toFixed(1)} Mbps` : 'Idle'}
                </span>
              </div>
            </div>

            {/* Close Button */}
            <div className="flex justify-end pt-2">
              <button
                onClick={() => setShowSettingsModal(false)}
                className="px-5 py-2.5 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-bold transition-all"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
