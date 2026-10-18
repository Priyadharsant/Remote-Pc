import React, { useState, useEffect, useRef } from "react";
import {
  X,
  PackageCheck,
  RefreshCw,
  ExternalLink,
  ShieldCheck,
  AlertTriangle,
  Lock,
  Loader2,
  Download,
  Zap,
} from "lucide-react";
import { APP_VERSION, checkForAppUpdates } from "../version";
import type { AppUpdateData } from "../version";
import type { UpdateProgress } from "../types";

interface VersionUpdateModalProps {
  isOpen: boolean;
  onClose: () => void;
  isMandatory?: boolean;
  initialUpdateData?: AppUpdateData | null;
}

export const VersionUpdateModal: React.FC<VersionUpdateModalProps> = ({
  isOpen,
  onClose,
  isMandatory: propMandatory,
  initialUpdateData,
}) => {
  const [checking, setChecking] = useState<boolean>(!initialUpdateData);
  const [updateData, setUpdateData] = useState<AppUpdateData | null>(initialUpdateData || null);
  const [downloading, setDownloading] = useState<boolean>(false);
  const [downloadProgress, setDownloadProgress] = useState<UpdateProgress>({
    percent: 0,
    transferred: 0,
    total: 0,
  });
  const [readyToInstall, setReadyToInstall] = useState<boolean>(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const autoStartedRef = useRef<boolean>(false);

  const hasUpdate = Boolean(updateData?.hasUpdate);
  const isMandatory = hasUpdate || propMandatory || Boolean(updateData?.isMandatory);
  const latestVersion = updateData?.latestVersion || APP_VERSION;
  const releaseNotes = updateData?.releaseNotes && updateData.releaseNotes.length > 0
    ? updateData.releaseNotes
    : [
        "Zero-latency wireless trackpad and real-time audio/brightness controls",
        "Instant 1-tap QR Code pairing with cryptographic authentication",
        "Instant 10s playback forward and backward seeking",
        "High-speed 4-stream concurrent auto-updater engine",
      ];

  const formatBytes = (bytes: number): string => {
    if (!bytes || isNaN(bytes)) return "0 MB";
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(1)} MB`;
  };

  const fetchUpdateInfo = async () => {
    setChecking(true);
    try {
      const data = await checkForAppUpdates();
      setUpdateData(data);
    } finally {
      setChecking(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      if (initialUpdateData) {
        setUpdateData(initialUpdateData);
        setChecking(false);
      } else {
        fetchUpdateInfo();
      }
    }
  }, [isOpen, initialUpdateData]);

  // Listen to IPC download progress events from Electron multi-stream downloader
  useEffect(() => {
    if (window.electronAPI?.onUpdateProgress) {
      const cleanup = window.electronAPI.onUpdateProgress((data) => {
        setDownloadProgress(data);
        if (data.percent >= 100) {
          setReadyToInstall(true);
        }
      });
      return cleanup;
    }
  }, []);

  const handleAutoUpdate = async () => {
    const url =
      updateData?.downloadUrl ||
      `https://github.com/Priyadharsant/Remote-Pc/releases/download/v${latestVersion}/Remote_PC_Setup.exe`;

    if (window.electronAPI?.downloadAndInstallUpdate) {
      setDownloading(true);
      setDownloadError(null);
      setDownloadProgress({ percent: 0, transferred: 0, total: 0 });
      setReadyToInstall(false);

      try {
        const result = await window.electronAPI.downloadAndInstallUpdate(url, latestVersion);
        if (!result.success) {
          setDownloadError(result.error || "Update download failed");
          setDownloading(false);
          autoStartedRef.current = false;
        } else {
          setReadyToInstall(true);
        }
      } catch (err: any) {
        setDownloadError(err?.message || "Failed to download update");
        setDownloading(false);
        autoStartedRef.current = false;
      }
    } else {
      // In web browser: trigger direct download
      const a = document.createElement("a");
      a.href = url;
      a.download = `Remote_PC_Setup.exe`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    }
  };

  const handleRetryAutoUpdate = () => {
    setDownloadError(null);
    autoStartedRef.current = false;
    handleAutoUpdate();
  };

  // Automatically start download and install as soon as an update is detected
  useEffect(() => {
    if (isOpen && hasUpdate && !downloading && !readyToInstall && !downloadError && !autoStartedRef.current) {
      autoStartedRef.current = true;
      handleAutoUpdate();
    }
  }, [isOpen, hasUpdate, downloading, readyToInstall, downloadError]);

  const handleManualBrowserDownload = () => {
    const url =
      updateData?.downloadUrl ||
      `https://github.com/Priyadharsant/Remote-Pc/releases/download/v${latestVersion}/Remote_PC_Setup.exe`;
    if (window.electronAPI?.openExternal) {
      window.electronAPI.openExternal(url);
    } else {
      window.open(url, "_blank", "noopener,noreferrer");
    }
  };

  const handleModalClose = () => {
    if ((isMandatory && hasUpdate && !downloadError) || downloading) return;
    if (onClose) onClose();
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200"
      onClick={(e) => {
        if (e.target === e.currentTarget && (!isMandatory || !hasUpdate) && !downloading) {
          handleModalClose();
        }
      }}
    >
      <div
        className="w-full max-w-lg rounded-2xl bg-[#0E1526] border border-slate-700/80 p-6 shadow-2xl text-slate-100 flex flex-col gap-4 relative overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Glow accent in background */}
        <div className="absolute -top-24 -right-24 w-48 h-48 bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />

        {/* Header */}
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div
              className={`w-12 h-12 rounded-xl flex items-center justify-center ${
                hasUpdate
                  ? "bg-rose-500/10 text-rose-400 border border-rose-500/20"
                  : "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
              }`}
            >
              {hasUpdate ? <Lock size={24} /> : <PackageCheck size={24} />}
            </div>
            <div>
              <h3 className="text-lg font-bold text-white tracking-wide">
                {hasUpdate ? "New Version Available" : "Remote PC Up to Date"}
              </h3>
              <p className="text-xs text-slate-400 mt-0.5">
                Installed:{" "}
                <span className={`font-semibold ${hasUpdate ? "text-rose-400" : "text-emerald-400"}`}>
                  v{APP_VERSION}
                </span>{" "}
                · Latest:{" "}
                <span className="font-semibold text-cyan-400">v{latestVersion}</span>
              </p>
            </div>
          </div>

          {(!hasUpdate || !isMandatory) && !downloading && (
            <button
              onClick={handleModalClose}
              className="p-1.5 rounded-lg bg-slate-800 text-slate-400 hover:text-white hover:bg-slate-700 transition"
            >
              <X size={18} />
            </button>
          )}
        </div>

        {/* STATUS BANNER */}
        <div
          className={`p-3.5 rounded-xl border flex items-center justify-between text-xs ${
            hasUpdate
              ? downloadError
                ? "bg-rose-950/40 border-rose-800/60 text-rose-300"
                : "bg-emerald-950/40 border-emerald-800/60 text-emerald-300"
              : "bg-emerald-950/40 border-emerald-800/60 text-emerald-300"
          }`}
        >
          <div className="flex items-center gap-2.5">
            {hasUpdate ? (
              downloadError ? (
                <AlertTriangle size={20} className="text-rose-400 shrink-0" />
              ) : (
                <Loader2 size={20} className="text-emerald-400 animate-spin shrink-0" />
              )
            ) : (
              <PackageCheck size={20} className="text-emerald-400 shrink-0" />
            )}
            <div>
              <div className="font-semibold text-sm">
                {hasUpdate
                  ? downloadError
                    ? `Update Download Issue (v${latestVersion})`
                    : readyToInstall
                    ? `Update Downloaded — Restarting & Installing v${latestVersion}...`
                    : `Updating to v${latestVersion} Automatically...`
                  : "You are running the latest official version"}
              </div>
              <div className="text-[11px] opacity-80 mt-0.5">
                {hasUpdate
                  ? downloadError
                    ? "Automatic download encountered an issue. You can retry or download manually below."
                    : readyToInstall
                    ? "Installer will update files silently without prompts."
                    : "High-speed 4-stream concurrent download in progress. No clicks needed."
                  : "All PC control tools, trackpad, and media services are up to date."}
              </div>
            </div>
          </div>

          {!downloading && !hasUpdate && (
            <button
              onClick={fetchUpdateInfo}
              disabled={checking}
              className="px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 font-medium flex items-center gap-1.5 shrink-0 transition"
            >
              <RefreshCw size={12} className={checking ? "animate-spin" : ""} />
              {checking ? "Checking..." : "Re-check"}
            </button>
          )}
        </div>

        {/* LIVE MULTI-STREAM PROGRESS TRACK */}
        {hasUpdate && !downloadError && (
          <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800 flex flex-col gap-2.5">
            <div className="flex justify-between items-center text-xs">
              <span className="font-semibold text-emerald-400 flex items-center gap-1.5">
                <Zap size={14} className="text-emerald-400 fill-emerald-400" />
                {readyToInstall
                  ? "Download complete! Applying update & restarting..."
                  : downloadProgress.percent > 0
                  ? `Downloading update v${latestVersion}...`
                  : "Connecting to server & allocating streams..."}
              </span>
              <span className="font-bold text-sm text-emerald-400">
                {downloadProgress.percent}%
              </span>
            </div>

            {/* Progress Bar */}
            <div className="w-full h-2.5 bg-slate-800 rounded-full overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-emerald-500 via-teal-400 to-cyan-400 transition-all duration-200 rounded-full"
                style={{ width: `${Math.max(4, downloadProgress.percent)}%` }}
              />
            </div>

            <div className="flex justify-between items-center text-[11px] text-slate-400">
              <span>
                {downloadProgress.total > 0
                  ? `${formatBytes(downloadProgress.transferred)} / ${formatBytes(downloadProgress.total)}`
                  : "Connecting..."}
                {downloadProgress.speed && ` · ⚡ ${downloadProgress.speed}`}
                {downloadProgress.streams && ` · 🚀 ${downloadProgress.streams} Multi-Streams`}
              </span>
              <span>
                {readyToInstall ? "Closing app to run installer..." : "Installer runs silently when done"}
              </span>
            </div>
          </div>
        )}

        {/* ERROR BANNER */}
        {downloadError && (
          <div className="p-3.5 rounded-xl bg-rose-950/60 border border-rose-800 text-rose-300 text-xs flex flex-col gap-1">
            <div className="flex items-center gap-2 font-bold text-rose-200">
              <AlertTriangle size={15} />
              <span>Auto-download failed: {downloadError}</span>
            </div>
            <p className="opacity-90 leading-relaxed text-[11px]">
              Could not complete automatic update. You can retry with the high-speed engine or download the setup installer directly via your browser.
            </p>
          </div>
        )}

        {/* RELEASE HIGHLIGHTS */}
        <div className="flex flex-col gap-2">
          <h4 className="text-xs font-semibold text-slate-300 flex items-center gap-1.5 uppercase tracking-wider">
            <ShieldCheck size={14} className="text-emerald-400" />
            Release Highlights (v{latestVersion}):
          </h4>
          <ul className="text-xs text-slate-400 space-y-1.5 pl-4 list-disc marker:text-emerald-500">
            {releaseNotes.map((note, idx) => (
              <li key={idx} className="leading-relaxed">
                {note}
              </li>
            ))}
          </ul>
        </div>

        {/* MODAL ACTIONS */}
        <div className="flex items-center justify-between pt-3 border-t border-slate-800">
          {hasUpdate ? (
            downloadError ? (
              <div className="flex items-center justify-between w-full gap-2">
                <button
                  type="button"
                  onClick={handleRetryAutoUpdate}
                  className="px-3.5 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold flex items-center gap-1.5 border border-slate-700 transition"
                >
                  <RefreshCw size={13} />
                  Retry Multi-Stream Download
                </button>
                <button
                  type="button"
                  onClick={handleManualBrowserDownload}
                  className="px-4 py-2 rounded-xl bg-rose-600 hover:bg-rose-500 text-white text-xs font-bold flex items-center gap-1.5 shadow-lg shadow-rose-900/30 transition"
                >
                  <Download size={14} />
                  Download Setup Exe Manually
                </button>
              </div>
            ) : (
              <div className="flex items-center justify-between w-full text-xs text-slate-400">
                <span className="flex items-center gap-1.5 text-emerald-400 font-semibold">
                  <Loader2 size={13} className="animate-spin" />
                  {readyToInstall
                    ? "Applying update & launching installer..."
                    : `Multi-stream downloading update (${downloadProgress.percent}%)...`}
                </span>
                <span className="text-[11px] opacity-75">
                  Automated update · Zero prompt required
                </span>
              </div>
            )
          ) : (
            <div className="flex justify-end gap-2.5 w-full">
              <button
                type="button"
                onClick={handleModalClose}
                className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-semibold transition"
              >
                Close
              </button>
              <button
                type="button"
                onClick={handleManualBrowserDownload}
                className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold flex items-center gap-1.5 border border-slate-700 transition"
              >
                <ExternalLink size={13} />
                View Release on GitHub
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
