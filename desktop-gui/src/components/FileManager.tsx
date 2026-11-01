import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  FolderOpen, Image, Video, Music, Download, FileText, AppWindow,
  RefreshCw, HardDriveDownload, Search, AlertCircle, Play,
  Laptop, Smartphone, ChevronRight, Home, ArrowLeft, X, Square, Send, Radio
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
  const [, setDrives] = useState<string[]>([]);

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
    setActionMsg({ id: file.path || file.id, text: 'Launching stream / player...' });

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
      // Refresh active streams after launching
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
          // Auto close transfer modal after brief delay on completion
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
      return { text: 'APK', bg: 'bg-emerald-600', color: 'text-emerald-400' };
    }
    if (file.app_type === 'exe' || ext === '.exe' || ext === '.msi') {
      return { text: 'EXE', bg: 'bg-sky-600', color: 'text-sky-400' };
    }
    if (file.app_type === 'ubt' || ext === '.deb' || ext === '.appimage' || ext === '.desktop') {
      return { text: 'UBT', bg: 'bg-orange-600', color: 'text-orange-400' };
    }
    return null;
  };

  const getFileIcon = (file: PhoneFileItem) => {
    if (file.is_dir || file.type === 'folder') return <FolderOpen size={24} className="text-amber-400" />;
    const appBadge = getAppBadge(file);
    if (appBadge) return <AppWindow size={24} className={appBadge.color} />;
    switch (file.type) {
      case 'image': return <Image size={24} className="text-pink-400" />;
      case 'video': return <Video size={24} className="text-cyan-400" />;
      case 'audio': return <Music size={24} className="text-emerald-400" />;
      case 'download': return <Download size={24} className="text-amber-400" />;
      case 'document': return <FileText size={24} className="text-blue-400" />;
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
    <div className="space-y-4">
      {/* Active Live Sharing Persistent Banner */}
      {activeStreams.length > 0 && (
        <div className="p-3 rounded-2xl bg-gradient-to-r from-emerald-950/60 to-slate-900 border border-emerald-500/40 shadow-lg shadow-emerald-500/10 flex items-center justify-between gap-3 animate-fade-in">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-8 h-8 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Radio size={16} className="text-emerald-400 animate-pulse" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping"></span>
                <span className="text-xs font-bold text-emerald-400 uppercase tracking-wider">Live Sharing Active</span>
              </div>
              <p className="text-xs font-semibold text-slate-200 truncate" title={activeStreams[0].filename}>
                {activeStreams[0].filename}
                {activeStreams[0].client && <span className="text-[11px] font-normal text-slate-400 ml-1.5">to {activeStreams[0].client}</span>}
              </p>
            </div>
          </div>
          <button
            onClick={() => handleStopStream(activeStreams[0].id || activeStreams[0].path)}
            className="px-3 py-1.5 rounded-xl bg-emerald-500/15 hover:bg-emerald-500/25 border border-emerald-500/30 text-emerald-300 text-xs font-bold flex items-center gap-1.5 transition shrink-0"
          >
            <Square size={12} className="fill-emerald-400" />
            <span>Stop Stream</span>
          </button>
        </div>
      )}

      {/* Real-time Transfer Modal Dialog */}
      {activeTransfer && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-md bg-slate-900/95 border border-cyan-500/40 rounded-2xl p-5 shadow-2xl shadow-cyan-500/20 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center">
                  <HardDriveDownload size={18} className="text-cyan-400 animate-bounce" />
                </div>
                <div>
                  <h4 className="text-xs font-bold text-cyan-400 uppercase tracking-wider">Device File Transfer</h4>
                  <span className="text-[11px] font-semibold text-slate-400">{activeTransfer.direction}</span>
                </div>
              </div>
              <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider ${
                activeTransfer.status === 'completed'
                  ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                  : activeTransfer.status === 'cancelled'
                  ? 'bg-red-500/20 text-red-400 border border-red-500/30'
                  : 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 animate-pulse'
              }`}>
                {activeTransfer.status}
              </span>
            </div>

            <div>
              <p className="text-sm font-bold text-slate-200 truncate" title={activeTransfer.filename}>
                {activeTransfer.filename}
              </p>
              <p className="text-xs text-slate-400 mt-0.5">
                {activeTransfer.message || 'Transferring...'}
              </p>
            </div>

            {/* Progress Bar */}
            <div className="space-y-1.5">
              <div className="w-full h-2.5 bg-slate-950 rounded-full overflow-hidden border border-slate-800">
                <div
                  className={`h-full transition-all duration-300 rounded-full ${
                    activeTransfer.status === 'completed'
                      ? 'bg-emerald-400'
                      : activeTransfer.status === 'cancelled'
                      ? 'bg-red-400'
                      : 'bg-gradient-to-r from-cyan-500 to-sky-400'
                  }`}
                  style={{ width: `${Math.max(activeTransfer.percent, 3)}%` }}
                />
              </div>
              <div className="flex items-center justify-between text-xs text-slate-400 font-medium">
                <span>{formatSize(activeTransfer.transferred)} / {activeTransfer.total > 0 ? formatSize(activeTransfer.total) : '--'}</span>
                <span className="text-cyan-300 font-bold">{activeTransfer.percent}%</span>
              </div>
            </div>

            {/* Transfer Speed Badge */}
            <div className="flex items-center justify-between pt-1 text-xs">
              <span className="px-2.5 py-1 rounded-lg bg-slate-800/80 border border-slate-700/80 text-cyan-300 font-mono text-xs flex items-center gap-1.5">
                <span>⚡ Speed:</span>
                <span className="font-bold">{activeTransfer.speed_mb > 0 ? `${activeTransfer.speed_mb.toFixed(1)} MB/s` : 'Calculating...'}</span>
              </span>

              {activeTransfer.status === 'in_progress' ? (
                <button
                  onClick={handleCancelTransfer}
                  className="px-3 py-1.5 rounded-xl bg-red-500/15 hover:bg-red-500/25 border border-red-500/30 text-red-400 hover:text-red-300 text-xs font-bold transition flex items-center gap-1"
                >
                  <X size={13} />
                  <span>Cancel Transfer</span>
                </button>
              ) : (
                <button
                  onClick={() => setActiveTransfer(null)}
                  className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-semibold transition"
                >
                  Close
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Main Top Header: Source Selector & Path Bar */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 p-4 rounded-2xl bg-slate-900/60 border border-slate-800/80 backdrop-blur-sm">
        {/* Source Switcher */}
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            {source === 'pc' ? <Laptop size={22} /> : <Smartphone size={22} />}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-base font-bold text-slate-100">
                {source === 'pc' ? 'PC File Explorer' : 'Phone Storage Explorer'}
              </h3>
              {source === 'phone' && (
                <span
                  className={`px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider ${
                    clientIp
                      ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/30'
                      : 'bg-amber-500/10 text-amber-400 border border-amber-500/30'
                  }`}
                >
                  {clientIp ? `Connected: ${clientIp}` : 'ADB (127.0.0.1)'}
                </span>
              )}
            </div>
            <p className="text-xs text-slate-400">
              {source === 'pc'
                ? 'Browse, stream, and manage your computer files and apps'
                : 'Browse phone photos, videos, apps (APK), and documents live'}
            </p>
          </div>
        </div>

        {/* Source Toggle Pill */}
        <div className="flex items-center gap-1.5 bg-slate-950/80 p-1 rounded-xl border border-slate-800 self-start md:self-auto">
          <button
            onClick={() => { setSource('pc'); setActiveCategory('all'); }}
            className={`px-3 py-1.5 rounded-lg text-xs font-bold flex items-center gap-1.5 transition ${
              source === 'pc' ? 'bg-cyan-500 text-slate-950 shadow-sm' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Laptop size={14} />
            <span>PC Storage</span>
          </button>
          <button
            onClick={() => { setSource('phone'); setActiveCategory('all'); }}
            className={`px-3 py-1.5 rounded-lg text-xs font-bold flex items-center gap-1.5 transition ${
              source === 'phone' ? 'bg-cyan-500 text-slate-950 shadow-sm' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Smartphone size={14} />
            <span>Phone Storage</span>
          </button>
        </div>
      </div>

      {/* PC Breadcrumb & Shortcuts Bar (When in PC mode) */}
      {source === 'pc' && (
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 p-2.5 rounded-xl bg-slate-900/40 border border-slate-800/60 text-xs">
          <div className="flex items-center gap-1.5 flex-1 min-w-0">
            <button
              onClick={() => setCurrentPcPath('')}
              className="p-1 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-cyan-300 transition shrink-0"
              title="Drives View"
            >
              <Home size={15} />
            </button>
            {parentPcPath !== null && (
              <button
                onClick={handleGoBack}
                className="p-1 rounded-lg hover:bg-slate-800 text-slate-400 hover:text-cyan-300 transition shrink-0"
                title="Up one folder"
              >
                <ArrowLeft size={15} />
              </button>
            )}
            <div className="font-mono text-slate-300 truncate px-2 py-0.5 rounded bg-slate-950/50 border border-slate-800/80 flex-1">
              {currentPcPath || 'This PC (Drives)'}
            </div>
          </div>

          {/* Quick Shortcuts */}
          <div className="flex items-center gap-1 shrink-0 overflow-x-auto">
            {shortcuts.map((sc) => (
              <button
                key={sc.path}
                onClick={() => setCurrentPcPath(sc.path)}
                className={`px-2 py-1 rounded-lg text-[11px] font-semibold transition ${
                  currentPcPath === sc.path
                    ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30'
                    : 'bg-slate-800/60 text-slate-400 hover:text-slate-200 hover:bg-slate-700/60'
                }`}
              >
                {sc.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Category Pills & Search */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        {/* Category Pills */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0 scrollbar-none">
          {([
            { id: 'all', label: 'All Files' },
            { id: 'app', label: 'Apps (UBT / APK / EXE)' },
            { id: 'image', label: 'Photos' },
            { id: 'video', label: 'Videos' },
            { id: 'audio', label: 'Music' },
            { id: 'document', label: 'Docs' }
          ] as { id: CategoryType; label: string }[]).map((cat) => (
            <button
              key={cat.id}
              onClick={() => setActiveCategory(cat.id)}
              className={`px-3 py-1.5 rounded-xl text-xs font-semibold whitespace-nowrap transition ${
                activeCategory === cat.id
                  ? 'bg-cyan-500 text-slate-950 shadow-sm shadow-cyan-500/20'
                  : 'bg-slate-900/60 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60 border border-slate-800'
              }`}
            >
              {cat.label}
            </button>
          ))}
        </div>

        {/* Search Input */}
        <div className="relative flex-1 sm:w-56">
          <Search size={14} className="absolute left-3 top-2.5 text-slate-400" />
          <input
            type="text"
            placeholder="Search files & apps..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-8 pr-3 py-1.5 bg-slate-900/80 border border-slate-800 rounded-xl text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500/50"
          />
        </div>
      </div>

      {/* Files Grid */}
      {loading ? (
        <div className="p-12 text-center text-slate-400 text-xs space-y-2">
          <RefreshCw size={24} className="animate-spin text-cyan-400 mx-auto" />
          <p>Querying files and generating app thumbnails...</p>
        </div>
      ) : filteredFiles.length === 0 ? (
        <div className="p-8 rounded-2xl bg-slate-900/40 border border-slate-800/80 text-center space-y-2">
          <AlertCircle size={24} className="text-slate-500 mx-auto" />
          <h4 className="font-bold text-sm text-slate-300">No Files Found</h4>
          <p className="text-xs text-slate-500 max-w-sm mx-auto">
            {errorMsg || 'No items matched your current category filter.'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
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
                className={`p-3 rounded-xl bg-slate-900/70 border flex flex-col justify-between group relative overflow-hidden select-none transition ${
                  isLive
                    ? 'border-emerald-500 ring-2 ring-emerald-500/40 shadow-lg shadow-emerald-500/15'
                    : 'border-slate-800/80 hover:border-cyan-500/40 hover:shadow-cyan-500/5'
                } ${isDir ? 'cursor-pointer hover:bg-slate-800/50' : ''}`}
              >
                {/* File Header with Thumbnail */}
                <div className="flex items-start gap-2.5">
                  {/* Thumbnail / App Icon Container */}
                  <div
                    className="relative w-12 h-12 rounded-xl bg-slate-950/90 border border-slate-800 shrink-0 overflow-hidden flex items-center justify-center group-hover:border-cyan-500/50 transition cursor-pointer"
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
                      <span className={`absolute bottom-0 right-0 px-1 py-0.2 text-[8px] font-black rounded-tl-md text-white tracking-wider ${appBadge.bg}`}>
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
                    <h5 className="font-semibold text-xs text-slate-200 truncate group-hover:text-cyan-300 transition" title={file.name}>
                      {file.name}
                    </h5>

                    {/* Live Sharing indicator or regular size */}
                    {isLive ? (
                      <div className="flex items-center gap-1.5 mt-1">
                        <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-emerald-500/20 text-emerald-400 border border-emerald-500/40 text-[9px] font-black tracking-wide animate-pulse">
                          <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                          LIVE SHARING
                        </span>
                        <span className="text-[10px] text-slate-400">{formatSize(file.size)}</span>
                      </div>
                    ) : (
                      <div className="flex items-center gap-1.5 mt-1 text-[11px] text-slate-400">
                        {isDir ? (
                          <span className="text-amber-400 font-medium">Directory</span>
                        ) : (
                          <>
                            <span>{formatSize(file.size)}</span>
                            <span>•</span>
                            <span className="uppercase text-[10px] font-bold text-slate-500">
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
                  <div className="mt-2.5 text-[11px] font-medium text-cyan-300 bg-cyan-950/40 py-1.5 px-2 rounded-lg border border-cyan-500/20 text-center truncate animate-fade-in">
                    {msg}
                  </div>
                ) : (
                  <div className="flex items-center gap-1.5 mt-2.5 pt-2 border-t border-slate-800/60">
                    {isDir ? (
                      <button
                        onClick={() => file.path && handleOpenFolder(file.path)}
                        className="w-full py-1 px-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-300 text-xs font-semibold flex items-center justify-center gap-1 transition"
                      >
                        <span>Open Folder</span>
                        <ChevronRight size={13} />
                      </button>
                    ) : (
                      <>
                        <button
                          onClick={() => handleLiveStream(file)}
                          disabled={isOpening}
                          className="flex-1 py-1 px-2 rounded-lg bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 text-xs font-semibold flex items-center justify-center gap-1 transition"
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
                            className="py-1 px-2.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-medium flex items-center justify-center gap-1 transition"
                            title="Save to Downloads/RemotePC with live progress"
                          >
                            <HardDriveDownload size={12} />
                            <span>Save</span>
                          </button>
                        )}

                        {/* Send to Phone button (when viewing PC files) */}
                        {source === 'pc' && !isDir && (
                          <button
                            onClick={() => handleSendToPhone(file)}
                            className="py-1 px-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-medium flex items-center justify-center gap-1 transition"
                            title="Send to Phone Downloads/RemotePC"
                          >
                            <Send size={11} className="text-cyan-400" />
                            <span>Send</span>
                          </button>
                        )}

                        {/* If Live, allow stopping directly */}
                        {isLive && (
                          <button
                            onClick={() => handleStopStream(file.path || file.name)}
                            className="p-1 rounded-lg bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 transition"
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
