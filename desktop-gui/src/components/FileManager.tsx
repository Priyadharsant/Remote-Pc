import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  FolderOpen, Image, Video, Music, Download, FileText, AppWindow,
  RefreshCw, HardDriveDownload, Search, AlertCircle, Play,
  Laptop, Smartphone, ChevronRight, Home, ArrowLeft, X, Square, Send, Radio,
  HardDrive, Activity
} from 'lucide-react';
import { api } from '../api';
import type { ServerStatus, PhoneFileItem } from '../types';

interface FileManagerProps {
  status: ServerStatus | null;
}

type StorageSource = 'pc' | 'phone';
type CategoryType = 'all' | 'app' | 'image' | 'video' | 'audio' | 'download' | 'document';

interface ActiveTransferState {
  id: string;
  filename: string;
  direction: string;
  transferred: number;
  total: number;
  percent: number;
  speed_mb: number;
  status: string;
  message?: string;
}

export const FileManager: React.FC<FileManagerProps> = ({ status }) => {
  const [source, setSource] = useState<StorageSource>('pc');
  const [activeCategory, setActiveCategory] = useState<CategoryType>('all');
  const [files, setFiles] = useState<PhoneFileItem[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [openingId, setOpeningId] = useState<any>(null);
  const [actionMsg, setActionMsg] = useState<{ id: any; text: string } | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Live Streams & Transfers
  const [activeStreams, setActiveStreams] = useState<any[]>([]);
  const [activeTransfer, setActiveTransfer] = useState<ActiveTransferState | null>(null);
  const transferPollRef = useRef<any>(null);

  // PC Navigation state
  const [currentPcPath, setCurrentPcPath] = useState<string>('');
  const [parentPcPath, setParentPcPath] = useState<string | null>(null);
  const [shortcuts, setShortcuts] = useState<{ name: string; path: string }[]>([]);
  const [drives, setDrives] = useState<string[]>([]);

  // Mobile Client connection detection
  const connectedClient = status?.clients && status.clients.length > 0 ? status.clients[0] : null;
  const clientIp = connectedClient?.ip || '';
  const activeIp = clientIp || '127.0.0.1';

  // Load PC Drives & Shortcuts initially
  useEffect(() => {
    api.getPcDrives().then((res) => {
      setDrives(res.drives || []);
      setShortcuts(res.shortcuts || []);
      if (!currentPcPath && res.shortcuts && res.shortcuts.length > 0) {
        setCurrentPcPath(res.shortcuts[0].path);
      }
    });
  }, []);

  // Poll Active Live Streams periodically
  useEffect(() => {
    const checkStreams = async () => {
      try {
        const streams = await api.getActiveLiveStreams();
        setActiveStreams(streams || []);
      } catch {
        // ignore
      }
    };
    checkStreams();
    const interval = setInterval(checkStreams, 3000);
    return () => clearInterval(interval);
  }, []);

  const fetchFiles = useCallback(async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      if (source === 'phone') {
        const items = await api.getPhoneFiles(activeIp, activeCategory);
        setFiles(items);
        if (items.length === 0 && !clientIp && activeIp === '127.0.0.1') {
          setErrorMsg('No files returned. Ensure the Remote PC mobile app is open or running in background.');
        }
      } else {
        // PC File Explorer
        const res = await api.getPcFiles(currentPcPath);
        setCurrentPcPath(res.current_path || currentPcPath);
        setParentPcPath(res.parent_path);

        const mapped: PhoneFileItem[] = (res.items || []).map((it: any, idx: number) => ({
          id: idx + 1,
          name: it.name,
          path: it.path,
          is_dir: Boolean(it.is_dir),
          type: it.file_type || (it.is_dir ? 'folder' : 'file'),
          app_type: it.app_type,
          is_app: Boolean(it.is_app),
          has_thumbnail: Boolean(it.has_thumbnail),
          is_live: Boolean(it.is_live),
          size: it.size || 0
        }));
        setFiles(mapped);
      }
    } catch (err: any) {
      setFiles([]);
      setErrorMsg(err?.message || 'Failed to load files');
    } finally {
      setLoading(false);
    }
  }, [source, activeIp, activeCategory, clientIp, currentPcPath]);

  useEffect(() => {
    fetchFiles();
  }, [fetchFiles]);

  const handleOpenFolder = (folderPath: string) => {
    setCurrentPcPath(folderPath);
  };

  const handleGoBack = () => {
    if (parentPcPath) {
      setCurrentPcPath(parentPcPath);
    } else {
      setCurrentPcPath('');
    }
  };

  const isFileLiveSharing = (file: PhoneFileItem): boolean => {
    if (file.is_live) return true;
    return activeStreams.some((s: any) => {
      if (file.path && s.path === file.path) return true;
      if (s.filename && s.filename === file.name) return true;
      if (s.id && (s.id === file.id || String(s.id).includes(String(file.id)))) return true;
      return false;
    });
  };

  const handleLiveStream = async (file: PhoneFileItem) => {
    setOpeningId(file.path || file.id);
    setActionMsg({ id: file.path || file.id, text: 'Opening stream...' });

    try {
      if (source === 'pc') {
        if (file.path) {
          const ok = await api.streamMediaOnPc(file.path);
          setActionMsg({ id: file.path || file.id, text: ok ? 'Playing on PC' : 'Opened' });
        }
      } else {
        const streamHost = activeIp || clientIp || '127.0.0.1';
        let streamUrl: string;
        if (file.type === 'video' || file.type === 'audio') {
          streamUrl = `http://${streamHost}:5056/video?id=${file.id}`;
        } else {
          streamUrl = `http://${streamHost}:5056/storage/file?id=${file.id}&type=${encodeURIComponent(file.type)}`;
        }
        let ok = await api.streamMediaOnPc(streamUrl);
        if (!ok && window.electronAPI?.openExternalUrl) {
          ok = await window.electronAPI.openExternalUrl(streamUrl);
        }
        setActionMsg({ id: file.id, text: ok ? 'Live streaming on PC' : 'Streaming in browser' });
      }
      setTimeout(async () => {
        const s = await api.getActiveLiveStreams();
        setActiveStreams(s || []);
      }, 800);
    } catch {
      setActionMsg({ id: file.path || file.id, text: 'Failed to open' });
    } finally {
      setOpeningId(null);
      setTimeout(() => setActionMsg(null), 3000);
    }
  };

  const handleStopStream = async (pathOrId: string) => {
    try {
      await api.stopLiveStream(pathOrId);
      const s = await api.getActiveLiveStreams();
      setActiveStreams(s || []);
      fetchFiles();
    } catch {
      // ignore
    }
  };

  // --- Real-time Device Transfer Handler (Phone <-> PC) ---
  const startTransferMonitor = (transferId: string, initialData: ActiveTransferState) => {
    setActiveTransfer(initialData);

    if (transferPollRef.current) clearInterval(transferPollRef.current);

    transferPollRef.current = setInterval(async () => {
      try {
        const status = await api.getTransferStatus(transferId);
        if (!status || status.status === 'not_found') return;

        setActiveTransfer((prev) => {
          if (!prev) return null;
          return {
            ...prev,
            transferred: status.transferred ?? prev.transferred,
            total: status.total > 0 ? status.total : prev.total,
            percent: status.percent ?? prev.percent,
            speed_mb: status.speed_mb ?? prev.speed_mb,
            status: status.status,
            message: status.message || prev.message
          };
        });

        if (status.status === 'completed' || status.status === 'cancelled' || status.status === 'error') {
          clearInterval(transferPollRef.current);
          transferPollRef.current = null;
          setTimeout(() => {
            setActiveTransfer(null);
            fetchFiles();
          }, 1800);
        }
      } catch {
        // ignore
      }
    }, 250);
  };

  const handleSaveToPc = async (file: PhoneFileItem) => {
    try {
      const transferId = await api.startPhoneDownload(activeIp, file.id, file.type, file.name);
      if (transferId) {
        startTransferMonitor(transferId, {
          id: transferId,
          filename: file.name,
          direction: 'Phone ➔ PC',
          transferred: 0,
          total: file.size,
          percent: 0,
          speed_mb: 0,
          status: 'in_progress',
          message: 'Connecting to phone...'
        });
      } else {
        setActionMsg({ id: file.id, text: 'Failed to start download' });
        setTimeout(() => setActionMsg(null), 3000);
      }
    } catch {
      setActionMsg({ id: file.id, text: 'Error starting download' });
      setTimeout(() => setActionMsg(null), 3000);
    }
  };

  const handleSendToPhone = async (file: PhoneFileItem) => {
    if (!file.path) return;
    try {
      const transferId = await api.startPhoneUpload(activeIp, file.path);
      if (transferId) {
        startTransferMonitor(transferId, {
          id: transferId,
          filename: file.name,
          direction: 'PC ➔ Phone',
          transferred: 0,
          total: file.size,
          percent: 0,
          speed_mb: 0,
          status: 'in_progress',
          message: 'Connecting to phone...'
        });
      } else {
        setActionMsg({ id: file.path, text: 'Failed to start upload' });
        setTimeout(() => setActionMsg(null), 3000);
      }
    } catch {
      setActionMsg({ id: file.path, text: 'Error starting upload' });
      setTimeout(() => setActionMsg(null), 3000);
    }
  };

  const handleCancelTransfer = async () => {
    if (!activeTransfer) return;
    try {
      await api.cancelTransfer(activeTransfer.id);
      setActiveTransfer((prev) => prev ? { ...prev, status: 'cancelled', message: 'Transfer cancelled by user' } : null);
    } catch {
      // ignore
    }
  };

  const formatSize = (bytes: number) => {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
  };

  const getAppBadge = (file: PhoneFileItem) => {
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    if (file.app_type === 'apk' || ext === '.apk' || ext === '.xapk') {
      return { text: 'APK', bg: 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40' };
    }
    if (file.app_type === 'exe' || ext === '.exe' || ext === '.msi') {
      return { text: 'EXE', bg: 'bg-cyan-500/20 text-cyan-400 border border-cyan-500/40' };
    }
    if (file.app_type === 'ubt' || ext === '.deb' || ext === '.appimage' || ext === '.desktop') {
      return { text: 'UBT', bg: 'bg-orange-500/20 text-orange-400 border border-orange-500/40' };
    }
    return null;
  };

  const getFileIcon = (file: PhoneFileItem) => {
    if (file.is_dir || file.type === 'folder') return <FolderOpen size={24} className="text-amber-400" />;
    const appBadge = getAppBadge(file);
    if (appBadge) return <AppWindow size={24} className={appBadge.text === 'APK' ? 'text-emerald-400' : appBadge.text === 'EXE' ? 'text-cyan-400' : 'text-orange-400'} />;
    switch (file.type) {
      case 'image': return <Image size={24} className="text-pink-400" />;
      case 'video': return <Video size={24} className="text-cyan-400" />;
      case 'audio': return <Music size={24} className="text-emerald-400" />;
      case 'download': return <Download size={24} className="text-amber-400" />;
      case 'document': return <FileText size={24} className="text-sky-400" />;
      default: return <FileText size={24} className="text-slate-400" />;
    }
  };

  const getThumbnailUrl = (file: PhoneFileItem): string | null => {
    if (source === 'pc') {
      if (file.has_thumbnail && file.path) {
        return api.getPcThumbnailUrl(file.path);
      }
      return null;
    }
    if (source === 'phone' && (file.type === 'image' || file.type === 'app' || getAppBadge(file))) {
      return api.getPhoneThumbnailUrl(activeIp, file.id, file.type, file.name);
    }
    return null;
  };

  const filteredFiles = files.filter((f) => {
    const isDir = Boolean(f.is_dir || f.type === 'folder');
    if (searchQuery && !f.name.toLowerCase().includes(searchQuery.toLowerCase())) {
      return false;
    }
    if (activeCategory === 'all') return true;
    if (isDir) return true;
    if (activeCategory === 'app') {
      return Boolean(f.is_app || f.type === 'app' || getAppBadge(f));
    }
    if (activeCategory === 'image') return f.type === 'image';
    if (activeCategory === 'video') return f.type === 'video';
    if (activeCategory === 'audio') return f.type === 'audio';
    if (activeCategory === 'download') return f.type === 'download';
    if (activeCategory === 'document') return f.type === 'document' || f.type === 'doc';
    return true;
  });

  return (
    <div className="space-y-4 animate-fade-in">
      {/* Live Sharing Persistent Ambient Island */}
      {activeStreams.length > 0 && (
        <div className="p-3.5 rounded-2xl bg-gradient-to-r from-emerald-950/80 via-slate-900/90 to-slate-950/90 border border-emerald-500/40 shadow-xl shadow-emerald-500/10 flex items-center justify-between gap-3 backdrop-blur-xl">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Radio size={18} className="text-emerald-400 animate-pulse" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-emerald-400 shadow-[0_0_8px_#10B981] animate-ping"></span>
                <span className="text-[11px] font-black text-emerald-400 uppercase tracking-widest">Active Stream Channel</span>
              </div>
              <p className="text-xs font-semibold text-slate-100 truncate" title={activeStreams[0].filename}>
                {activeStreams[0].filename}
                {activeStreams[0].client && <span className="text-[11px] font-normal text-slate-400 ml-1.5">• to {activeStreams[0].client}</span>}
              </p>
            </div>
          </div>
          <button
            onClick={() => handleStopStream(activeStreams[0].id || activeStreams[0].path)}
            className="px-3.5 py-1.5 rounded-xl bg-emerald-500/20 hover:bg-emerald-500/30 border border-emerald-500/40 text-emerald-300 text-xs font-bold flex items-center gap-1.5 transition active:scale-95 shrink-0 shadow-sm"
          >
            <Square size={11} className="fill-emerald-400" />
            <span>Stop Stream</span>
          </button>
        </div>
      )}

      {/* Real-time Transfer Modal HUD */}
      {activeTransfer && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-md bg-gradient-to-b from-slate-900/95 to-slate-950/95 border border-cyan-500/40 rounded-3xl p-6 shadow-2xl shadow-cyan-500/20 space-y-4 backdrop-blur-2xl">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-2xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center">
                  <HardDriveDownload size={20} className="text-cyan-400 animate-bounce" />
                </div>
                <div>
                  <h4 className="text-xs font-black text-cyan-400 uppercase tracking-widest">Kinetic File Pipeline</h4>
                  <span className="text-[11px] font-semibold text-slate-400">{activeTransfer.direction}</span>
                </div>
              </div>
              <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-extrabold uppercase tracking-wider ${
                activeTransfer.status === 'completed'
                  ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 shadow-[0_0_10px_rgba(16,185,129,0.2)]'
                  : activeTransfer.status === 'cancelled'
                  ? 'bg-rose-500/20 text-rose-400 border border-rose-500/40'
                  : 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 shadow-[0_0_10px_rgba(6,182,212,0.2)] animate-pulse'
              }`}>
                {activeTransfer.status}
              </span>
            </div>

            <div className="p-3 rounded-2xl bg-slate-950/60 border border-slate-800/80">
              <p className="text-xs font-bold text-slate-200 truncate" title={activeTransfer.filename}>
                {activeTransfer.filename}
              </p>
              <p className="text-[11px] text-slate-400 mt-0.5 flex items-center gap-1.5">
                <Activity size={12} className="text-cyan-400 shrink-0" />
                <span>{activeTransfer.message || 'Streaming packets...'}</span>
              </p>
            </div>

            {/* Glowing Progress Bar */}
            <div className="space-y-2">
              <div className="w-full h-3 bg-slate-950 rounded-full overflow-hidden border border-slate-800/80 p-0.5">
                <div
                  className={`h-full transition-all duration-300 rounded-full shadow-lg ${
                    activeTransfer.status === 'completed'
                      ? 'bg-gradient-to-r from-emerald-500 to-teal-400 shadow-emerald-500/40'
                      : activeTransfer.status === 'cancelled'
                      ? 'bg-gradient-to-r from-rose-500 to-red-600 shadow-rose-500/40'
                      : 'bg-gradient-to-r from-cyan-500 via-teal-400 to-emerald-400 shadow-cyan-500/30'
                  }`}
                  style={{ width: `${Math.max(activeTransfer.percent, 3)}%` }}
                />
              </div>
              <div className="flex items-center justify-between text-xs text-slate-400 font-semibold">
                <span>{formatSize(activeTransfer.transferred)} / {activeTransfer.total > 0 ? formatSize(activeTransfer.total) : '--'}</span>
                <span className="text-cyan-300 font-black tracking-wide">{activeTransfer.percent}%</span>
              </div>
            </div>

            {/* Telemetry Capsule */}
            <div className="flex items-center justify-between pt-1 text-xs">
              <span className="px-3 py-1.5 rounded-xl bg-slate-950/80 border border-slate-800 text-cyan-300 font-mono text-xs flex items-center gap-2">
                <span className="text-slate-400">⚡ Speed:</span>
                <span className="font-extrabold text-cyan-300">{activeTransfer.speed_mb > 0 ? `${activeTransfer.speed_mb.toFixed(1)} MB/s` : 'Analyzing...'}</span>
              </span>

              {activeTransfer.status === 'in_progress' ? (
                <button
                  onClick={handleCancelTransfer}
                  className="px-3.5 py-1.5 rounded-xl bg-rose-500/15 hover:bg-rose-500/25 border border-rose-500/30 text-rose-400 hover:text-rose-300 text-xs font-bold transition flex items-center gap-1.5 active:scale-95"
                >
                  <X size={13} />
                  <span>Cancel Transfer</span>
                </button>
              ) : (
                <button
                  onClick={() => setActiveTransfer(null)}
                  className="px-4 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-bold transition active:scale-95 border border-slate-700"
                >
                  Dismiss
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Main Top Header: Kinetic Source Pill Switcher */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 p-4 rounded-3xl bg-gradient-to-r from-slate-900/90 via-slate-900/70 to-slate-950/90 border border-slate-800/80 backdrop-blur-xl shadow-xl shadow-black/40">
        <div className="flex items-center gap-3.5">
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-cyan-500/20 via-emerald-500/10 to-transparent border border-cyan-500/30 flex items-center justify-center text-cyan-400 shadow-inner">
            {source === 'pc' ? <Laptop size={22} className="text-cyan-400" /> : <Smartphone size={22} className="text-emerald-400" />}
          </div>
          <div>
            <div className="flex items-center gap-2.5">
              <h3 className="text-base font-extrabold text-slate-100 tracking-tight">
                {source === 'pc' ? 'PC Storage Explorer' : 'Phone Storage Explorer'}
              </h3>
              {source === 'phone' ? (
                <span
                  className={`px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider flex items-center gap-1.5 ${
                    clientIp
                      ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/40 shadow-[0_0_8px_rgba(16,185,129,0.2)]'
                      : 'bg-amber-500/15 text-amber-400 border border-amber-500/40'
                  }`}
                >
                  <span className={`w-1.5 h-1.5 rounded-full ${clientIp ? 'bg-emerald-400' : 'bg-amber-400'}`}></span>
                  {clientIp ? `Connected: ${clientIp}` : 'ADB (127.0.0.1)'}
                </span>
              ) : (
                <span className="px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider bg-cyan-500/15 text-cyan-400 border border-cyan-500/30 flex items-center gap-1.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-cyan-400"></span>
                  Local Drives & System
                </span>
              )}
            </div>
            <p className="text-xs text-slate-400 mt-0.5 font-medium">
              {source === 'pc'
                ? 'High-speed remote filesystem exploration, live media streaming & app packaging'
                : 'Browse mobile storage photos, videos, apps (APK), downloads & documents'}
            </p>
          </div>
        </div>

        {/* Source Toggle Pill */}
        <div className="flex items-center p-1 rounded-2xl bg-slate-950/80 border border-slate-800/90 self-start md:self-auto shadow-inner">
          <button
            onClick={() => { setSource('pc'); setActiveCategory('all'); }}
            className={`px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all duration-200 active:scale-95 ${
              source === 'pc'
                ? 'bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 font-black shadow-md shadow-cyan-500/20'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/50'
            }`}
          >
            <Laptop size={14} />
            <span>PC Storage</span>
          </button>
          <button
            onClick={() => { setSource('phone'); setActiveCategory('all'); }}
            className={`px-4 py-2 rounded-xl text-xs font-bold flex items-center gap-2 transition-all duration-200 active:scale-95 ${
              source === 'phone'
                ? 'bg-gradient-to-r from-emerald-500 to-teal-400 text-slate-950 font-black shadow-md shadow-emerald-500/20'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/50'
            }`}
          >
            <Smartphone size={14} />
            <span>Phone Storage</span>
          </button>
        </div>
      </div>

      {/* PC Breadcrumb & Quick Drive Bar */}
      {source === 'pc' && (
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5 p-3 rounded-2xl bg-slate-900/70 border border-slate-800/80 backdrop-blur-md">
          <div className="flex items-center gap-2 flex-1 min-w-0">
            <button
              onClick={() => setCurrentPcPath('')}
              className="p-1.5 rounded-xl hover:bg-slate-800 text-slate-400 hover:text-cyan-300 transition active:scale-90 shrink-0 border border-slate-800/80"
              title="Drives View"
            >
              <Home size={15} />
            </button>
            {parentPcPath !== null && (
              <button
                onClick={handleGoBack}
                className="p-1.5 rounded-xl hover:bg-slate-800 text-slate-400 hover:text-cyan-300 transition active:scale-90 shrink-0 border border-slate-800/80"
                title="Up one folder"
              >
                <ArrowLeft size={15} />
              </button>
            )}
            <div className="font-mono text-xs text-slate-300 truncate px-3 py-1.5 rounded-xl bg-slate-950/70 border border-slate-800/80 flex-1 shadow-inner flex items-center gap-2">
              <FolderOpen size={13} className="text-amber-400 shrink-0" />
              <span className="truncate">{currentPcPath || 'This PC (Storage Partitions)'}</span>
            </div>
          </div>

          {/* Quick Shortcuts & Drives */}
          <div className="flex items-center gap-1.5 shrink-0 overflow-x-auto scrollbar-none">
            {drives.length > 0 && currentPcPath && (
              <div className="flex items-center gap-1 pr-1 border-r border-slate-800/80">
                {drives.map((drv) => (
                  <button
                    key={drv}
                    onClick={() => setCurrentPcPath(drv)}
                    className="px-2 py-1 rounded-lg text-[10px] font-black uppercase tracking-wider bg-slate-950/60 hover:bg-slate-800 text-slate-400 hover:text-cyan-300 border border-slate-800/60 transition"
                  >
                    {drv}
                  </button>
                ))}
              </div>
            )}
            {shortcuts.map((sc) => (
              <button
                key={sc.path}
                onClick={() => setCurrentPcPath(sc.path)}
                className={`px-2.5 py-1 rounded-xl text-[11px] font-bold transition-all active:scale-95 ${
                  currentPcPath === sc.path
                    ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 shadow-sm shadow-cyan-500/10'
                    : 'bg-slate-950/60 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60 border border-slate-800/60'
                }`}
              >
                {sc.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Kinetic Category Pill Strip & Search */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        {/* Category Pills */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0 scrollbar-none">
          {([
            { id: 'all', label: 'All Files', icon: HardDrive },
            { id: 'app', label: 'Apps (UBT/APK/EXE)', icon: AppWindow },
            { id: 'image', label: 'Photos', icon: Image },
            { id: 'video', label: 'Videos', icon: Video },
            { id: 'audio', label: 'Audio', icon: Music },
            { id: 'document', label: 'Docs', icon: FileText }
          ] as { id: CategoryType; label: string; icon: any }[]).map((cat) => {
            const IconComp = cat.icon;
            const isSelected = activeCategory === cat.id;
            return (
              <button
                key={cat.id}
                onClick={() => setActiveCategory(cat.id)}
                className={`px-3.5 py-1.5 rounded-xl text-xs font-bold whitespace-nowrap flex items-center gap-1.5 transition-all duration-200 active:scale-95 ${
                  isSelected
                    ? 'bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 font-black shadow-md shadow-cyan-500/20'
                    : 'bg-slate-900/60 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60 border border-slate-800/80'
                }`}
              >
                <IconComp size={13} className={isSelected ? 'text-slate-950' : 'text-slate-400'} />
                <span>{cat.label}</span>
              </button>
            );
          })}
        </div>

        {/* Search Bar */}
        <div className="relative flex-1 sm:w-64">
          <Search size={14} className="absolute left-3.5 top-2.5 text-slate-400 pointer-events-none" />
          <input
            type="text"
            placeholder="Search items, tags, extensions..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3.5 py-2 bg-slate-900/80 border border-slate-800 rounded-2xl text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500/60 focus:ring-1 focus:ring-cyan-500/30 transition shadow-inner"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
              className="absolute right-3 top-2.5 text-slate-400 hover:text-slate-200"
            >
              <X size={13} />
            </button>
          )}
        </div>
      </div>

      {/* Files Grid / Drives View */}
      {loading ? (
        <div className="p-16 rounded-3xl bg-slate-900/40 border border-slate-800/80 text-center space-y-3">
          <RefreshCw size={28} className="animate-spin text-cyan-400 mx-auto" />
          <p className="text-xs font-semibold text-slate-300">Scanning filesystem and reading metadata...</p>
        </div>
      ) : filteredFiles.length === 0 ? (
        <div className="p-12 rounded-3xl bg-slate-900/40 border border-slate-800/80 text-center space-y-3">
          <div className="w-12 h-12 rounded-2xl bg-slate-800/60 border border-slate-700/60 flex items-center justify-center mx-auto text-slate-400">
            <AlertCircle size={24} />
          </div>
          <h4 className="font-extrabold text-sm text-slate-200">No Items Detected</h4>
          <p className="text-xs text-slate-400 max-w-sm mx-auto">
            {errorMsg || 'No files matched your filter query or the current directory is empty.'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3.5">
          {filteredFiles.map((file) => {
            const isDir = Boolean(file.is_dir || file.type === 'folder');
            const appBadge = getAppBadge(file);
            const thumbUrl = getThumbnailUrl(file);
            const isOpening = openingId === (file.path || file.id);
            const isLive = isFileLiveSharing(file);
            const msg = actionMsg?.id === (file.path || file.id) ? actionMsg.text : null;

            return (
              <div
                key={file.path || file.id}
                onDoubleClick={() => {
                  if (isDir && file.path) handleOpenFolder(file.path);
                  else handleLiveStream(file);
                }}
                className={`p-3.5 rounded-2xl bg-gradient-to-b from-slate-900/80 to-slate-950/90 border flex flex-col justify-between group relative overflow-hidden select-none transition-all duration-200 hover:-translate-y-0.5 ${
                  isLive
                    ? 'border-emerald-500 shadow-[0_8px_25px_rgba(16,185,129,0.18)] ring-1 ring-emerald-500/50'
                    : 'border-slate-800/80 hover:border-cyan-500/40 hover:shadow-[0_8px_25px_rgba(6,182,212,0.12)]'
                } ${isDir ? 'cursor-pointer' : ''}`}
              >
                {/* File Header with Thumbnail */}
                <div className="flex items-start gap-3">
                  {/* Thumbnail / App Icon Container */}
                  <div
                    className="relative w-12 h-12 rounded-xl bg-slate-950/90 border border-slate-800/90 shrink-0 overflow-hidden flex items-center justify-center group-hover:border-cyan-500/40 transition-all cursor-pointer shadow-inner"
                    onClick={() => {
                      if (isDir && file.path) handleOpenFolder(file.path);
                      else handleLiveStream(file);
                    }}
                  >
                    {thumbUrl ? (
                      <img
                        src={thumbUrl}
                        alt=""
                        className="w-full h-full object-contain p-1"
                        onError={(e) => {
                          e.currentTarget.style.display = 'none';
                        }}
                      />
                    ) : null}

                    {/* Default Vector Icon */}
                    <div className="absolute inset-0 flex items-center justify-center pointer-events-none -z-10">
                      {getFileIcon(file)}
                    </div>

                    {/* App Platform Badge */}
                    {appBadge && (
                      <span className={`absolute bottom-0 right-0 px-1 py-0.2 text-[8px] font-black rounded-tl-md ${appBadge.bg}`}>
                        {appBadge.text}
                      </span>
                    )}
                  </div>

                  {/* Title & Details */}
                  <div
                    className="flex-1 min-w-0 cursor-pointer"
                    onClick={() => {
                      if (isDir && file.path) handleOpenFolder(file.path);
                      else handleLiveStream(file);
                    }}
                  >
                    <h5 className="font-bold text-xs text-slate-200 truncate group-hover:text-cyan-300 transition" title={file.name}>
                      {file.name}
                    </h5>

                    {/* Live Sharing indicator or regular size */}
                    {isLive ? (
                      <div className="flex items-center gap-1.5 mt-1">
                        <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 text-[9px] font-black tracking-wide animate-pulse">
                          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                          LIVE STREAM
                        </span>
                        <span className="text-[10px] text-slate-400">{formatSize(file.size)}</span>
                      </div>
                    ) : (
                      <div className="flex items-center gap-1.5 mt-1 text-[11px] text-slate-400">
                        {isDir ? (
                          <span className="text-amber-400 font-semibold flex items-center gap-1">
                            <FolderOpen size={11} /> Directory
                          </span>
                        ) : (
                          <>
                            <span>{formatSize(file.size)}</span>
                            <span>•</span>
                            <span className="uppercase text-[10px] font-black text-slate-500 tracking-wider">
                              {file.name.substring(file.name.lastIndexOf('.') + 1) || file.type}
                            </span>
                          </>
                        )}
                      </div>
                    )}
                  </div>
                </div>

                {/* Bottom Actions Bar */}
                {msg ? (
                  <div className="mt-2.5 text-[11px] font-semibold text-cyan-300 bg-cyan-950/40 py-1.5 px-2 rounded-xl border border-cyan-500/30 text-center truncate animate-fade-in">
                    {msg}
                  </div>
                ) : (
                  <div className="flex items-center gap-1.5 mt-3 pt-2.5 border-t border-slate-800/60">
                    {isDir ? (
                      <button
                        onClick={() => file.path && handleOpenFolder(file.path)}
                        className="w-full py-1.5 px-3 rounded-xl bg-slate-800/80 hover:bg-slate-700/80 text-cyan-300 text-xs font-bold flex items-center justify-center gap-1.5 transition active:scale-95 border border-slate-700/60"
                      >
                        <span>Open Folder</span>
                        <ChevronRight size={13} />
                      </button>
                    ) : (
                      <>
                        <button
                          onClick={() => handleLiveStream(file)}
                          disabled={isOpening}
                          className="flex-1 py-1.5 px-2.5 rounded-xl bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 text-xs font-bold flex items-center justify-center gap-1.5 transition active:scale-95"
                        >
                          {isOpening ? (
                            <RefreshCw size={11} className="animate-spin text-cyan-400" />
                          ) : (
                            <Play size={11} className="text-cyan-400 fill-cyan-400/20" />
                          )}
                          <span>{source === 'pc' ? 'Open / Run' : 'Live Stream'}</span>
                        </button>

                        {/* Save to PC button (when viewing phone files) */}
                        {source === 'phone' && (
                          <button
                            onClick={() => handleSaveToPc(file)}
                            className="py-1.5 px-2.5 rounded-xl bg-slate-800/80 hover:bg-slate-700/80 text-slate-200 text-xs font-bold flex items-center justify-center gap-1 transition active:scale-95 border border-slate-700/60"
                            title="Download to PC"
                          >
                            <HardDriveDownload size={13} className="text-emerald-400" />
                            <span>Save</span>
                          </button>
                        )}

                        {/* Send to Phone button (when viewing PC files) */}
                        {source === 'pc' && !isDir && (
                          <button
                            onClick={() => handleSendToPhone(file)}
                            className="py-1.5 px-2.5 rounded-xl bg-slate-800/80 hover:bg-slate-700/80 text-slate-200 text-xs font-bold flex items-center justify-center gap-1 transition active:scale-95 border border-slate-700/60"
                            title="Send to Phone"
                          >
                            <Send size={12} className="text-cyan-400" />
                            <span>Send</span>
                          </button>
                        )}

                        {/* If Live, allow stopping directly */}
                        {isLive && (
                          <button
                            onClick={() => handleStopStream(file.path || file.name)}
                            className="p-1.5 rounded-xl bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 transition active:scale-90 border border-emerald-500/30"
                            title="Stop Live Sharing"
                          >
                            <Square size={12} className="fill-emerald-400" />
                          </button>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
