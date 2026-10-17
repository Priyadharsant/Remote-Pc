import React, { useEffect, useState } from 'react';
import { Minus, Square, X, ArrowDownToLine, Monitor, Play, RefreshCw } from 'lucide-react';

interface TitleBarProps {
  isOnline: boolean;
  connectedPhone?: string | null;
  onRefresh?: () => void;
}

declare global {
  interface Window {
    electronAPI?: {
      minimizeWindow: () => void;
      maximizeWindow: () => void;
      closeWindow: () => void;
      hideToTray: () => void;
      isMaximized: () => Promise<boolean>;
      openExternalFolder: (path?: string) => Promise<boolean>;
      openFileItem: (path: string) => Promise<boolean>;
      launchScrcpy: (phoneIp?: string) => Promise<{ success: boolean; error?: string }>;
      startServer?: () => Promise<{ success: boolean; error?: string }>;
      openExternalUrl?: (url: string) => Promise<boolean>;
    };
  }
}

export const TitleBar: React.FC<TitleBarProps> = ({ isOnline, connectedPhone, onRefresh }) => {
  const [isMax, setIsMax] = useState(false);
  const [isStartingServer, setIsStartingServer] = useState(false);

  useEffect(() => {
    const checkMax = async () => {
      if (window.electronAPI?.isMaximized) {
        const val = await window.electronAPI.isMaximized();
        setIsMax(val);
      }
    };
    checkMax();
  }, []);

  const handleMinimize = () => window.electronAPI?.minimizeWindow();
  const handleMaximize = async () => {
    window.electronAPI?.maximizeWindow();
    setIsMax(!isMax);
  };
  const handleClose = () => window.electronAPI?.closeWindow();
  const handleHideTray = () => window.electronAPI?.hideToTray();

  const handleStartServer = async () => {
    setIsStartingServer(true);
    if (window.electronAPI?.startServer) {
      await window.electronAPI.startServer();
    }
    // Poll for status check
    const interval = setInterval(() => {
      onRefresh?.();
    }, 1000);
    setTimeout(() => {
      clearInterval(interval);
      setIsStartingServer(false);
      onRefresh?.();
    }, 4500);
  };

  return (
    <div className="titlebar-drag h-10 w-full bg-[#070C16] border-b border-cyan-500/15 flex items-center justify-between px-3 select-none text-xs z-50">
      {/* Left: Branding & Status */}
      <div className="flex items-center gap-2.5 titlebar-no-drag">
        <div className="w-5 h-5 rounded-md bg-gradient-to-br from-cyan-400 to-blue-600 flex items-center justify-center text-white shadow-sm shadow-cyan-500/20">
          <Monitor size={12} className="stroke-[2.5]" />
        </div>
        <span className="font-bold text-slate-200 tracking-wide text-[13px]">Remote PC</span>
        <div className="h-3 w-px bg-slate-700/60 mx-0.5" />
        <span className="text-[11px] text-slate-400 font-medium hidden sm:inline">Desktop Suite</span>

        {/* Live Status Pill */}
        <div className="flex items-center gap-1.5 ml-2 px-2 py-0.5 rounded-full bg-slate-800/80 border border-slate-700/50">
          <span className={`w-2 h-2 rounded-full ${isOnline ? 'bg-emerald-400 animate-pulse' : 'bg-rose-500'}`} />
          <span className={`text-[10px] font-semibold uppercase tracking-wider ${isOnline ? 'text-emerald-300' : 'text-rose-400'}`}>
            {isOnline ? 'Online' : 'Stopped'}
          </span>
        </div>

        {/* Start Server Button if Offline */}
        {!isOnline && (
          <button
            onClick={handleStartServer}
            disabled={isStartingServer}
            title="Start Core Server"
            className="flex items-center gap-1 ml-1 px-2.5 py-0.5 rounded-full bg-emerald-500/20 hover:bg-emerald-500/35 border border-emerald-500/40 text-emerald-300 text-[10px] font-bold transition shadow-sm hover:scale-105 active:scale-95"
          >
            {isStartingServer ? (
              <>
                <RefreshCw size={10} className="animate-spin text-emerald-400" />
                <span>Starting...</span>
              </>
            ) : (
              <>
                <Play size={10} className="fill-emerald-400 text-emerald-400" />
                <span>Start Server</span>
              </>
            )}
          </button>
        )}

        {connectedPhone && (
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-cyan-950/60 border border-cyan-500/30 text-cyan-300 text-[10px] font-medium">
            <span>📱</span>
            <span>{connectedPhone}</span>
          </div>
        )}
      </div>

      {/* Right: Window Control Buttons */}
      <div className="flex items-center titlebar-no-drag -mr-1">
        <button
          onClick={handleHideTray}
          title="Minimize to System Tray"
          className="w-8 h-8 flex items-center justify-center text-slate-400 hover:text-cyan-400 hover:bg-slate-800/60 transition-colors rounded-sm"
        >
          <ArrowDownToLine size={13} />
        </button>
        <button
          onClick={handleMinimize}
          title="Minimize Window"
          className="w-8 h-8 flex items-center justify-center text-slate-400 hover:text-white hover:bg-slate-800/60 transition-colors rounded-sm"
        >
          <Minus size={13} />
        </button>
        <button
          onClick={handleMaximize}
          title="Maximize Window"
          className="w-8 h-8 flex items-center justify-center text-slate-400 hover:text-white hover:bg-slate-800/60 transition-colors rounded-sm"
        >
          <Square size={11} />
        </button>
        <button
          onClick={handleClose}
          title="Close to Tray"
          className="w-8 h-8 flex items-center justify-center text-slate-400 hover:text-white hover:bg-rose-600 transition-colors rounded-sm"
        >
          <X size={13} />
        </button>
      </div>
    </div>
  );
};
