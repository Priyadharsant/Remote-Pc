import React, { useState, useEffect, useCallback } from 'react';
import {
  FolderOpen, Image, Video, Music, Download, FileText,
  RefreshCw, HardDriveDownload, Search, AlertCircle, Play
} from 'lucide-react';
import { api } from '../api';
import type { ServerStatus, PhoneFileItem } from '../types';

interface FileManagerProps {
  status: ServerStatus | null;
}

type CategoryType = 'all' | 'image' | 'video' | 'audio' | 'download' | 'document';

export const FileManager: React.FC<FileManagerProps> = ({ status }) => {
  const [activeCategory, setActiveCategory] = useState<CategoryType>('all');
  const [files, setFiles] = useState<PhoneFileItem[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [openingId, setOpeningId] = useState<number | null>(null);
  const [savingId, setSavingId] = useState<number | null>(null);
  const [actionMsg, setActionMsg] = useState<{ id: number; text: string } | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Correctly extract connected client IP
  const connectedClient = status?.clients && status.clients.length > 0
    ? status.clients[0]
    : null;
  const clientIp = connectedClient?.ip || '';

  // Auto-detect IP or fallback to 127.0.0.1 (ADB forwarded connection)
  const activeIp = clientIp || '127.0.0.1';

  const fetchFiles = useCallback(async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const items = await api.getPhoneFiles(activeIp, activeCategory);
      setFiles(items);
      if (items.length === 0 && !clientIp && activeIp === '127.0.0.1') {
        setErrorMsg('No files returned. Ensure the Remote PC mobile app is open or running in background.');
      }
    } catch (err: any) {
      setFiles([]);
      setErrorMsg(err?.message || 'Failed to connect to mobile storage');
    } finally {
      setLoading(false);
    }
  }, [activeIp, activeCategory, clientIp]);

  // Only fetch when activeCategory or activeIp changes, NOT on every status poll
  useEffect(() => {
    fetchFiles();
  }, [fetchFiles]);

  // Live Stream on PC (zero disk save - streams directly over HTTP into VLC / player, exactly like mobile app)
  const handleLiveStream = async (file: PhoneFileItem) => {
    setOpeningId(file.id);
    setActionMsg({ id: file.id, text: 'Starting live stream...' });

    try {
      const streamHost = activeIp || clientIp || '127.0.0.1';
      // Build stream URL matching mobile app implementation (MainActivity.kt line 3936)
      // For video & audio: http://<ip>:5056/video?id=<id>
      // For images & docs: http://<ip>:5056/storage/file?id=<id>&type=<type>
      let streamUrl: string;
      if (file.type === 'video' || file.type === 'audio') {
        streamUrl = `http://${streamHost}:5056/video?id=${file.id}`;
      } else {
        streamUrl = `http://${streamHost}:5056/storage/file?id=${file.id}&type=${encodeURIComponent(file.type)}`;
      }

      // Also construct proxy URL in case direct route needs backend forwarding
      const proxyUrl = api.getFilePreviewUrl(streamHost, file.id, file.type, file.name);

      // 1. Tell backend server to launch live media player (VLC / default player) without saving to disk
      let ok = await api.streamMediaOnPc(streamUrl);
      if (!ok) {
        ok = await api.streamMediaOnPc(proxyUrl);
      }

      // 2. Fallback: Electron shell openExternalUrl (opens in default player/browser live)
      if (!ok && window.electronAPI?.openExternalUrl) {
        ok = await window.electronAPI.openExternalUrl(streamUrl);
      }

      if (ok) {
        setActionMsg({ id: file.id, text: 'Live streaming on PC (No save)' });
      } else {
        // Fallback: open stream in browser
        window.open(proxyUrl, '_blank');
        setActionMsg({ id: file.id, text: 'Streaming in browser' });
      }
    } catch {
      setActionMsg({ id: file.id, text: 'Stream failed' });
    } finally {
      setOpeningId(null);
      setTimeout(() => setActionMsg(null), 3000);
    }
  };

  // Save file to PC Downloads folder without opening
  const handleSaveToPc = async (file: PhoneFileItem) => {
    setSavingId(file.id);
    setActionMsg({ id: file.id, text: 'Saving to Downloads...' });

    try {
      const savedPath = await api.downloadPhoneFile(activeIp, file.id, file.type, file.name);
      if (savedPath) {
        setActionMsg({ id: file.id, text: 'Saved to Downloads/From_Phone' });
      } else {
        setActionMsg({ id: file.id, text: 'Download failed' });
      }
    } catch {
      setActionMsg({ id: file.id, text: 'Error occurred' });
    } finally {
      setSavingId(null);
      setTimeout(() => setActionMsg(null), 3000);
    }
  };

  const handleOpenDownloadsFolder = () => {
    if (window.electronAPI?.openExternalFolder) {
      window.electronAPI.openExternalFolder();
    }
  };

  const formatSize = (bytes: number) => {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
  };

  const getFileIcon = (type: string) => {
    switch (type) {
      case 'image': return <Image size={18} className="text-cyan-400" />;
      case 'video': return <Video size={18} className="text-purple-400" />;
      case 'audio': return <Music size={18} className="text-emerald-400" />;
      case 'download': return <Download size={18} className="text-amber-400" />;
      case 'document': return <FileText size={18} className="text-blue-400" />;
      default: return <FileText size={18} className="text-slate-400" />;
    }
  };

  const filteredFiles = files.filter(f =>
    f.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-4">
      {/* Header & Quick Action */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400">
            <FolderOpen size={20} />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-bold text-sm text-slate-100">Mobile Storage Explorer</h3>
              <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-cyan-500/20 text-cyan-400 border border-cyan-500/30">
                {files.length} items
              </span>
            </div>
            <p className="text-[11px] text-slate-400">
              Click any file to live stream directly on PC without saving (VLC / Media Player)
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={fetchFiles}
            disabled={loading}
            className="p-2 rounded-xl bg-slate-800/80 hover:bg-slate-700 text-slate-300 hover:text-cyan-400 transition"
            title="Refresh Files"
          >
            <RefreshCw size={15} className={loading ? 'animate-spin text-cyan-400' : ''} />
          </button>
          <button
            onClick={handleOpenDownloadsFolder}
            className="px-3 py-1.5 rounded-xl bg-cyan-500/10 hover:bg-cyan-500/20 border border-cyan-500/30 text-cyan-300 text-xs font-semibold flex items-center gap-1.5 transition"
          >
            <HardDriveDownload size={13} />
            <span>Open PC Downloads</span>
          </button>
        </div>
      </div>

      {/* Categories Bar & Search */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        {/* Category Tabs */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-1 sm:pb-0 scrollbar-none">
          {(['all', 'image', 'video', 'audio', 'download', 'document'] as CategoryType[]).map((cat) => (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              className={`px-3 py-1.5 rounded-xl text-xs font-semibold capitalize transition ${
                activeCategory === cat
                  ? 'bg-cyan-500 text-slate-950 shadow-sm shadow-cyan-500/20'
                  : 'bg-slate-900/60 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60 border border-slate-800'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>

        {/* Search Input & Custom IP */}
        <div className="flex items-center gap-2">
          <div className="relative flex-1 sm:w-56">
            <Search size={14} className="absolute left-3 top-2.5 text-slate-400" />
            <input
              type="text"
              placeholder="Search files..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-8 pr-3 py-1.5 bg-slate-900/80 border border-slate-800 rounded-xl text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500/50"
            />
          </div>
        </div>
      </div>

      {/* Files Grid / Empty State */}
      {loading ? (
        <div className="p-12 text-center text-slate-400 text-xs space-y-2">
          <RefreshCw size={24} className="animate-spin text-cyan-400 mx-auto" />
          <p>Querying files from phone...</p>
        </div>
      ) : filteredFiles.length === 0 ? (
        <div className="p-8 rounded-2xl bg-slate-900/40 border border-slate-800/80 text-center space-y-2">
          <AlertCircle size={24} className="text-slate-500 mx-auto" />
          <h4 className="font-bold text-sm text-slate-300">No Files Found</h4>
          <p className="text-xs text-slate-500 max-w-sm mx-auto">
            {errorMsg || 'No items matched your current filter or phone storage category is empty. Ensure the Remote PC app is active on your phone.'}
          </p>
          <button
            onClick={fetchFiles}
            className="mt-2 px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-cyan-400 text-xs font-semibold transition"
          >
            Retry Connection
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {filteredFiles.map((file) => {
            const isOpening = openingId === file.id;
            const isSaving = savingId === file.id;
            const msg = actionMsg?.id === file.id ? actionMsg.text : null;

            return (
              <div
                key={file.id}
                className="p-3.5 rounded-xl bg-slate-900/70 border border-slate-800/80 hover:border-cyan-500/35 transition flex flex-col justify-between group relative overflow-hidden"
              >
                {/* Clickable Header: Click card to live stream without saving */}
                <div
                  className="flex items-start gap-3 cursor-pointer"
                  onClick={() => handleLiveStream(file)}
                  title="Click to live stream without saving in PC"
                >
                  <div className="p-2.5 rounded-xl bg-slate-950/80 border border-slate-800 shrink-0 group-hover:border-cyan-500/40 transition">
                    {getFileIcon(file.type)}
                  </div>
                  <div className="flex-1 min-w-0">
                    <h5 className="font-semibold text-xs text-slate-200 truncate group-hover:text-cyan-300 transition" title={file.name}>
                      {file.name}
                    </h5>
                    <div className="flex items-center gap-2 mt-1 text-[11px] text-slate-400">
                      <span>{formatSize(file.size)}</span>
                      <span>•</span>
                      <span className="capitalize">{file.type}</span>
                    </div>
                  </div>
                </div>

                {/* Status Message or Buttons */}
                {msg ? (
                  <div className="mt-3 text-[11px] font-medium text-cyan-300 bg-cyan-950/40 py-1.5 px-2.5 rounded-lg border border-cyan-500/20 text-center truncate animate-fade-in">
                    {msg}
                  </div>
                ) : (
                  <div className="flex items-center gap-2 mt-3 pt-2 border-t border-slate-800/60">
                    {/* Primary Button: Live Stream on PC */}
                    <button
                      onClick={() => handleLiveStream(file)}
                      disabled={isOpening || isSaving}
                      className="flex-1 py-1.5 px-2.5 rounded-lg bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 hover:text-cyan-200 text-xs font-semibold flex items-center justify-center gap-1.5 transition active:scale-98"
                      title="Stream live on PC without saving (VLC / Media Player)"
                    >
                      {isOpening ? (
                        <>
                          <RefreshCw size={12} className="animate-spin text-cyan-400" />
                          <span>Connecting...</span>
                        </>
                      ) : (
                        <>
                          <Play size={13} className="text-cyan-400 fill-cyan-400/20" />
                          <span>Live Stream</span>
                        </>
                      )}
                    </button>

                    {/* Secondary Button: Save to PC */}
                    <button
                      onClick={() => handleSaveToPc(file)}
                      disabled={isOpening || isSaving}
                      className="py-1.5 px-2.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white text-xs font-medium flex items-center justify-center gap-1.5 transition active:scale-98"
                      title="Save file to Downloads/From_Phone without opening"
                    >
                      {isSaving ? (
                        <RefreshCw size={12} className="animate-spin text-slate-300" />
                      ) : (
                        <HardDriveDownload size={13} className="text-slate-400" />
                      )}
                      <span>Save</span>
                    </button>
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
