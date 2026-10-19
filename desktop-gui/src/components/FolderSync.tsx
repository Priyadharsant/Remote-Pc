import React, { useState, useEffect, useCallback } from 'react';
import {
  FolderSync as FolderSyncIcon, FolderOpen, RefreshCw, CheckCircle2,
  HardDrive, Clock, File, Image, Video, Music, ArrowUpRight, Check, AlertCircle
} from 'lucide-react';
import { api } from '../api';
import type { ServerStatus } from '../types';

interface FolderSyncProps {
  status: ServerStatus | null;
}

export const FolderSync: React.FC<FolderSyncProps> = ({ status }) => {
  const [syncStatus, setSyncStatus] = useState<any>(null);
  const [history, setHistory] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [destInput, setDestInput] = useState('');
  const [isEditingDest, setIsEditingDest] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);

  const fetchSyncData = useCallback(async () => {
    try {
      const data = await api.getSyncStatus();
      setSyncStatus(data);
      if (!isEditingDest && data?.destination_dir) {
        setDestInput(data.destination_dir);
      }
    } catch (err: any) {
      // Non-critical
    }

    try {
      const items = await api.getSyncHistory();
      setHistory(items);
    } catch {
      // Non-critical
    }
  }, [isEditingDest]);

  useEffect(() => {
    fetchSyncData();
    const interval = setInterval(fetchSyncData, 3000);
    return () => clearInterval(interval);
  }, [fetchSyncData]);

  const handleSaveDestination = async () => {
    if (!destInput.trim()) return;
    setLoading(true);
    setActionError(null);
    try {
      await api.setSyncDestination(destInput.trim());
      setActionSuccess('Target folder updated successfully!');
      setIsEditingDest(false);
      fetchSyncData();
      setTimeout(() => setActionSuccess(null), 3000);
    } catch (err: any) {
      setActionError(err.message || 'Failed to update folder');
    } finally {
      setLoading(false);
    }
  };

  const handleBrowseFolder = async () => {
    if (window.electronAPI?.selectDirectory) {
      try {
        const chosen = await window.electronAPI.selectDirectory(destInput);
        if (chosen) {
          setDestInput(chosen);
          setLoading(true);
          setActionError(null);
          await api.setSyncDestination(chosen);
          setActionSuccess(`Target folder set to: ${chosen}`);
          setIsEditingDest(false);
          fetchSyncData();
          setTimeout(() => setActionSuccess(null), 3000);
        }
      } catch (err: any) {
        setActionError(err.message || 'Failed to select directory');
      } finally {
        setLoading(false);
      }
    }
  };

  const handleOpenFolder = (folderPath?: string) => {
    const target = folderPath || syncStatus?.destination_dir;
    if (target && window.electronAPI?.openExternalFolder) {
      window.electronAPI.openExternalFolder(target);
    }
  };

  const formatBytes = (bytes: number) => {
    if (!bytes || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const idx = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${(bytes / Math.pow(1024, idx)).toFixed(1)} ${units[idx]}`;
  };

  const formatTimestamp = (ts: number) => {
    if (!ts) return 'Unknown';
    const date = new Date(ts * 1000);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) +
      ' ' + date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  };

  const getFileIcon = (filename: string) => {
    const ext = filename.split('.').pop()?.toLowerCase() || '';
    if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic'].includes(ext)) {
      return <Image size={18} className="text-cyan-400" />;
    }
    if (['mp4', 'mkv', 'mov', 'avi', 'webm'].includes(ext)) {
      return <Video size={18} className="text-rose-400" />;
    }
    if (['mp3', 'wav', 'aac', 'flac', 'm4a'].includes(ext)) {
      return <Music size={18} className="text-amber-400" />;
    }
    return <File size={18} className="text-slate-400" />;
  };

  const connectedClient = status?.clients && status.clients.length > 0 ? status.clients[0].name : null;

  return (
    <div className="flex flex-col gap-6 max-w-6xl mx-auto pb-12 animate-in fade-in duration-300">
      {/* Top Header Card */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-6 rounded-3xl bg-slate-900/60 border border-slate-800/80 backdrop-blur-xl shadow-xl">
        <div className="flex items-center gap-4">
          <div className="p-3.5 rounded-2xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-400 shadow-lg shadow-emerald-500/10">
            <FolderSyncIcon size={28} />
          </div>
          <div>
            <div className="flex items-center gap-3">
              <h2 className="text-2xl font-black text-white tracking-wide">Real-time Folder Sync</h2>
              <span className="flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold bg-emerald-500/15 border border-emerald-500/30 text-emerald-400">
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                Active
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-1">
              Automatically receives newly created photos, videos, and files from your phone's chosen folders.
            </p>
          </div>
        </div>

        <button
          onClick={fetchSyncData}
          className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-slate-800/60 hover:bg-slate-700/60 border border-slate-700/60 text-slate-200 text-xs font-semibold transition-all active:scale-95"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin text-cyan-400' : ''} />
          <span>Refresh</span>
        </button>
      </div>

      {/* Destination Folder Configuration Card */}
      <div className="p-6 rounded-3xl bg-slate-900/60 border border-slate-800/80 backdrop-blur-xl shadow-xl">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-4">
          <div className="flex items-center gap-2.5">
            <HardDrive size={18} className="text-emerald-400" />
            <h3 className="text-sm font-bold text-slate-200 uppercase tracking-wider">PC Destination Folder</h3>
          </div>
          <div className="flex items-center gap-2">
            <span className="px-3 py-1 rounded-lg text-xs font-mono font-bold bg-slate-800/80 border border-slate-700/60 text-emerald-400">
              {syncStatus?.disk_free_gb ? `${syncStatus.disk_free_gb} GB Free` : 'PC Drive'}
            </span>
          </div>
        </div>

        <div className="flex flex-col sm:flex-row items-center gap-3">
          <div className="relative flex-1 w-full">
            <input
              type="text"
              value={destInput}
              onChange={(e) => {
                setDestInput(e.target.value);
                setIsEditingDest(true);
              }}
              placeholder="D:\SyncedFiles"
              className="w-full px-4 py-3 rounded-2xl bg-slate-950/70 border border-slate-800 text-white font-mono text-xs focus:outline-none focus:border-emerald-500/60 transition-all"
            />
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto">
            {isEditingDest ? (
              <button
                onClick={handleSaveDestination}
                disabled={loading}
                className="flex-1 sm:flex-initial flex items-center justify-center gap-2 px-5 py-3 rounded-2xl bg-emerald-500 hover:bg-emerald-600 text-black font-bold text-xs transition-all active:scale-95 shadow-lg shadow-emerald-500/20"
              >
                <Check size={16} />
                <span>Save</span>
              </button>
            ) : null}

            <button
              onClick={handleBrowseFolder}
              disabled={loading}
              className="flex-1 sm:flex-initial flex items-center justify-center gap-2 px-4 py-3 rounded-2xl bg-emerald-500/20 hover:bg-emerald-500/30 border border-emerald-500/40 text-emerald-300 font-bold text-xs transition-all active:scale-95 shadow-lg shadow-emerald-500/10"
              title="Browse and select a folder on this PC"
            >
              <FolderOpen size={16} className="text-emerald-400" />
              <span>Browse...</span>
            </button>

            <button
              onClick={() => handleOpenFolder()}
              className="flex-1 sm:flex-initial flex items-center justify-center gap-2 px-4 py-3 rounded-2xl bg-slate-800 hover:bg-slate-700 border border-slate-700/80 text-white font-bold text-xs transition-all active:scale-95"
              title="Open target folder in Windows Explorer"
            >
              <ArrowUpRight size={16} className="text-cyan-400" />
              <span>Open in Explorer</span>
            </button>
          </div>
        </div>

        {actionSuccess && (
          <div className="mt-3 flex items-center gap-2 text-xs text-emerald-400 font-medium">
            <CheckCircle2 size={14} />
            <span>{actionSuccess}</span>
          </div>
        )}
        {actionError && (
          <div className="mt-3 flex items-center gap-2 text-xs text-rose-400 font-medium">
            <AlertCircle size={14} />
            <span>{actionError}</span>
          </div>
        )}

        <div className="mt-4 p-3.5 rounded-2xl bg-emerald-500/5 border border-emerald-500/15 text-slate-300 text-xs flex items-center gap-2.5">
          <div className="w-2 h-2 rounded-full bg-emerald-400 flex-shrink-0" />
          <span>
            <strong>Sync Rule Active:</strong> Only files created <em>after</em> enabling this feature on your phone are synced. Existing older photos/files on your phone remain untouched.
          </span>
        </div>
      </div>

      {/* Sync Metrics Bar */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="p-5 rounded-2xl bg-slate-900/50 border border-slate-800/80 flex items-center gap-4">
          <div className="p-3 rounded-xl bg-cyan-500/15 border border-cyan-500/30 text-cyan-400">
            <CheckCircle2 size={22} />
          </div>
          <div>
            <div className="text-xs text-slate-400 font-medium">Total Files Synced</div>
            <div className="text-2xl font-black text-white mt-0.5">{syncStatus?.files_count || history.length}</div>
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-slate-900/50 border border-slate-800/80 flex items-center gap-4">
          <div className="p-3 rounded-xl bg-purple-500/15 border border-purple-500/30 text-purple-400">
            <HardDrive size={22} />
          </div>
          <div>
            <div className="text-xs text-slate-400 font-medium">Transferred Data</div>
            <div className="text-2xl font-black text-white mt-0.5">{syncStatus?.total_mb ? `${syncStatus.total_mb} MB` : '0 MB'}</div>
          </div>
        </div>

        <div className="p-5 rounded-2xl bg-slate-900/50 border border-slate-800/80 flex items-center gap-4">
          <div className="p-3 rounded-xl bg-amber-500/15 border border-amber-500/30 text-amber-400">
            <Clock size={22} />
          </div>
          <div>
            <div className="text-xs text-slate-400 font-medium">Connected Phone</div>
            <div className="text-base font-bold text-white mt-1 truncate max-w-[180px]">{connectedClient || 'Standby (Waiting)'}</div>
          </div>
        </div>
      </div>

      {/* Recently Synced Files List */}
      <div className="p-6 rounded-3xl bg-slate-900/60 border border-slate-800/80 backdrop-blur-xl shadow-xl">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-sm font-bold text-slate-200 uppercase tracking-wider">Recently Synced Mobile Files</h3>
          <span className="text-xs text-slate-500 font-mono">{history.length} items recorded</span>
        </div>

        {history.length === 0 ? (
          <div className="p-12 text-center flex flex-col items-center justify-center gap-3">
            <div className="p-4 rounded-full bg-slate-800/40 text-slate-500 border border-slate-700/40">
              <FolderSyncIcon size={32} />
            </div>
            <div className="text-sm font-bold text-slate-300">No synced files yet</div>
            <p className="text-xs text-slate-500 max-w-sm">
              Open the <strong>Auto Folder Sync</strong> page on your phone, pick a folder (like DCIM or WhatsApp), and newly created files will appear here immediately!
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-800 text-slate-400 font-bold">
                  <th className="pb-3 pl-2">File</th>
                  <th className="pb-3">Subfolder</th>
                  <th className="pb-3">Size</th>
                  <th className="pb-3">Time Synced</th>
                  <th className="pb-3 text-right pr-2">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/50">
                {history.map((item, idx) => (
                  <tr key={idx} className="hover:bg-white/[0.02] transition-colors group">
                    <td className="py-3 pl-2 flex items-center gap-2.5">
                      <div className="p-1.5 rounded-lg bg-slate-800/80 border border-slate-700/60">
                        {getFileIcon(item.filename)}
                      </div>
                      <span className="font-semibold text-slate-200 truncate max-w-xs">{item.filename}</span>
                    </td>
                    <td className="py-3 text-slate-400 font-mono text-[11px] truncate max-w-[150px]">
                      {item.relative_path || '/'}
                    </td>
                    <td className="py-3 text-slate-300 font-mono">{formatBytes(item.size_bytes)}</td>
                    <td className="py-3 text-slate-400">{formatTimestamp(item.synced_at)}</td>
                    <td className="py-3 text-right pr-2">
                      <button
                        onClick={() => handleOpenFolder(item.saved_path ? item.saved_path.substring(0, item.saved_path.lastIndexOf('\\')) : undefined)}
                        className="px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-[11px] font-semibold transition-all inline-flex items-center gap-1 active:scale-95"
                      >
                        <span>Show</span>
                        <ArrowUpRight size={12} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
