import React, { useState } from 'react';
import type { ServerStatus, SystemStats, PairingRequest } from '../types';
import {
  Wifi, Cpu, HardDrive, Battery, QrCode, KeyRound, ShieldAlert,
  Check, X, Smartphone, RefreshCw, Send, Play, PowerOff
} from 'lucide-react';
import { api } from '../api';

interface ServerDetailsProps {
  status: ServerStatus | null;
  stats: SystemStats | null;
  pairRequests: PairingRequest[];
  onRefresh: () => void;
}

export const ServerDetails: React.FC<ServerDetailsProps> = ({ status, stats, pairRequests, onRefresh }) => {
  const [toastMsg, setToastMsg] = useState('');
  const [isSendingToast, setIsSendingToast] = useState(false);
  const [isStartingServer, setIsStartingServer] = useState(false);

  const connectedClient = status?.connected_clients
    ? Object.values(status.connected_clients)[0]
    : null;

  const handleStartServer = async () => {
    setIsStartingServer(true);
    if (window.electronAPI?.startServer) {
      await window.electronAPI.startServer();
    }
    const interval = setInterval(() => {
      onRefresh();
    }, 1000);
    setTimeout(() => {
      clearInterval(interval);
      setIsStartingServer(false);
      onRefresh();
    }, 4500);
  };

  const handleApprove = async (ip: string) => {
    await api.approvePairing(ip, true);
    onRefresh();
  };

  const handleSendToast = async () => {
    if (!connectedClient?.ip || !toastMsg.trim()) return;
    setIsSendingToast(true);
    await api.sendToastToPhone(connectedClient.ip, toastMsg.trim());
    setToastMsg('');
    setIsSendingToast(false);
  };

  const formatUptime = (sec: number) => {
    const hrs = Math.floor(sec / 3600);
    const mins = Math.floor((sec % 3600) / 60);
    return `${hrs.toString().padStart(2, '0')}:${mins.toString().padStart(2, '0')} h`;
  };

  return (
    <div className="space-y-4">
      {/* Pending Authorization Alerts */}
      {pairRequests.length > 0 && (
        <div className="p-4 rounded-2xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-between animate-pulse">
          <div className="flex items-center gap-3">
            <ShieldAlert className="text-amber-400" size={24} />
            <div>
              <div className="font-bold text-amber-200 text-sm">New Phone Connection Request</div>
              <div className="text-xs text-amber-300/80">
                {pairRequests[0].name} requested permission to pair and auto-connect.
              </div>
            </div>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => handleApprove(pairRequests[0].ip)}
              className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-600 text-slate-950 font-bold text-xs shadow-sm transition"
            >
              <Check size={14} /> Authorize & Pair
            </button>
            <button
              onClick={onRefresh}
              className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs transition"
            >
              <X size={14} /> Ignore
            </button>
          </div>
        </div>
      )}

      {/* Offline Core Server Callout & Launch Button */}
      {!status && (
        <div className="p-4 rounded-2xl bg-gradient-to-r from-rose-950/40 via-slate-900/80 to-slate-900/80 border border-rose-500/30 backdrop-blur-md flex flex-col sm:flex-row sm:items-center justify-between gap-3 shadow-lg">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-rose-500/20 text-rose-400 shrink-0">
              <PowerOff size={22} />
            </div>
            <div>
              <h4 className="font-bold text-sm text-rose-200">Core FastAPI Server is Stopped</h4>
              <p className="text-xs text-slate-300">
                The desktop service backend is offline. Start the server to connect with your phone.
              </p>
            </div>
          </div>
          <button
            onClick={handleStartServer}
            disabled={isStartingServer}
            className="px-4 py-2 rounded-xl bg-emerald-500 hover:bg-emerald-600 disabled:opacity-60 text-slate-950 font-bold text-xs flex items-center justify-center gap-2 shadow-lg shadow-emerald-500/20 transition hover:scale-105 active:scale-95 shrink-0"
          >
            {isStartingServer ? (
              <>
                <RefreshCw size={14} className="animate-spin text-slate-950" />
                <span>Starting Core Server...</span>
              </>
            ) : (
              <>
                <Play size={14} className="fill-slate-950 text-slate-950" />
                <span>Start Core Server</span>
              </>
            )}
          </button>
        </div>
      )}

      {/* Top Row: Server Status & Connected Phone */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Card 1: Server Info */}
        <div className="glass-panel p-5 rounded-2xl relative overflow-hidden group">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400 border border-cyan-500/25 shadow-sm shadow-cyan-500/20">
                <Wifi size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-100">Server Status</h3>
                <p className="text-[11px] text-slate-400">FastAPI HTTP & Zero-Latency UDP</p>
              </div>
            </div>
            <button
              onClick={onRefresh}
              title="Refresh Status"
              className="p-2 rounded-xl bg-slate-800/80 hover:bg-slate-700 text-slate-300 hover:text-cyan-300 transition-all active:scale-95 border border-slate-700/60"
            >
              <RefreshCw size={14} />
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2 mt-4 text-xs">
            <div className="p-3 rounded-xl bg-slate-950/70 border border-slate-800/80 shadow-inner">
              <div className="text-slate-400 text-[10px] font-semibold uppercase tracking-wider">HOST / NAME</div>
              <div className="font-bold text-slate-100 truncate mt-1 text-[13px]">{status?.name || 'Local PC'}</div>
            </div>
            <div className="p-3 rounded-xl bg-slate-950/70 border border-slate-800/80 shadow-inner">
              <div className="text-slate-400 text-[10px] font-semibold uppercase tracking-wider">NETWORK STATUS</div>
              <div className="font-bold text-cyan-300 truncate mt-1 text-[13px] flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-live-pulse" />
                <span>Wi-Fi Network</span>
              </div>
            </div>
          </div>
        </div>

        {/* Card 2: Connected Phone Card */}
        <div className="glass-panel p-5 rounded-2xl relative">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-xl bg-blue-500/15 text-blue-400 border border-blue-500/25 shadow-sm shadow-blue-500/20">
                <Smartphone size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-100">Connected Device</h3>
                <p className="text-[11px] text-slate-400">
                  {connectedClient ? 'Active Link Established' : 'No Phone Connected'}
                </p>
              </div>
            </div>
            {connectedClient && (
              <span className="px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-live-pulse" />
                <span>Live</span>
              </span>
            )}
          </div>

          {connectedClient ? (
            <div className="space-y-2.5 mt-2">
              <div className="flex items-center justify-between text-xs p-3 rounded-xl bg-slate-950/70 border border-slate-800/80 shadow-inner">
                <span className="font-bold text-slate-100 text-[13px]">{connectedClient.name}</span>
                <span className="text-emerald-400 text-[11px] font-semibold px-2 py-0.5 rounded-md bg-emerald-950/60 border border-emerald-500/30">
                  Wi-Fi Connected
                </span>
              </div>
              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="Send instant notification to phone..."
                  value={toastMsg}
                  onChange={(e) => setToastMsg(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSendToast()}
                  className="flex-1 bg-slate-950/80 border border-slate-800 rounded-xl px-3 py-1.5 text-xs text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500/60 transition-all shadow-inner"
                />
                <button
                  onClick={handleSendToast}
                  disabled={isSendingToast || !toastMsg.trim()}
                  className="px-3 py-1.5 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-500 hover:from-cyan-400 hover:to-blue-400 disabled:opacity-40 text-slate-950 font-bold text-xs flex items-center gap-1.5 shadow-md shadow-cyan-500/20 transition-all active:scale-95"
                >
                  <Send size={12} />
                  <span>Send</span>
                </button>
              </div>
            </div>
          ) : (
            <div className="text-center py-5 text-xs text-slate-400 flex flex-col items-center justify-center gap-1">
              <div className="text-slate-300 font-medium">Waiting for Connection</div>
              <div className="text-[11px] text-slate-500">Launch the Remote PC app on your phone to link</div>
            </div>
          )}
        </div>
      </div>

      {/* Row 2: QR Code & PIN Code Pairing Card */}
      <div className="glass-panel p-5 rounded-2xl">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-amber-500/15 text-amber-400 border border-amber-500/25 shadow-sm shadow-amber-500/20">
              <KeyRound size={18} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-slate-100">Wireless Pairing Credentials</h3>
              <p className="text-[11px] text-slate-400">Scan QR or enter PIN in mobile app</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-slate-400">Quick PIN:</span>
            <span className="px-3.5 py-1 rounded-xl bg-cyan-950/80 border border-cyan-500/40 font-mono font-bold text-cyan-300 text-sm tracking-wider shadow-sm shadow-cyan-500/15">
              {status?.pin || '8888'}
            </span>
          </div>
        </div>

        <div className="flex flex-col sm:flex-row items-center gap-5 mt-4 p-4 rounded-xl bg-slate-950/70 border border-slate-800/80 shadow-inner">
          {status?.qr_code ? (
            <img
              src={`data:image/png;base64,${status.qr_code}`}
              alt="Pairing QR Code"
              className="w-28 h-28 rounded-xl bg-white p-1.5 shadow-md shadow-cyan-500/20 border border-cyan-500/30"
            />
          ) : (
            <div className="w-28 h-28 rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center text-slate-600">
              <QrCode size={40} />
            </div>
          )}
          <div className="text-xs space-y-2 flex-1">
            <div className="font-semibold text-slate-200 text-[13px]">How to Pair with Your Phone:</div>
            <ol className="list-decimal list-inside text-slate-400 space-y-1.5 text-[11px] leading-relaxed">
              <li>Connect both this PC and your mobile phone to the same Wi-Fi.</li>
              <li>Open the Remote PC app on your phone.</li>
              <li>Scan the QR code above or select this PC from the discovered devices list.</li>
              <li>Once paired, Auto-Connect will seamlessly reconnect anytime both are online!</li>
            </ol>
          </div>
        </div>
      </div>

      {/* Row 3: PC System Gauges */}
      {stats && (
        <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
          <div className="glass-panel p-4 rounded-xl">
            <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
              <span className="flex items-center gap-1.5 font-medium"><Cpu size={14} className="text-cyan-400" /> CPU Load</span>
              <span className="font-bold text-slate-100">{stats.cpu_percent}%</span>
            </div>
            <div className="w-full bg-slate-950/80 h-2 rounded-full overflow-hidden mt-2 p-0.5 border border-slate-800">
              <div className="bg-gradient-to-r from-cyan-500 to-blue-500 h-full rounded-full transition-all duration-500 shadow-[0_0_8px_rgba(6,182,212,0.6)]" style={{ width: `${stats.cpu_percent}%` }} />
            </div>
          </div>

          <div className="glass-panel p-4 rounded-xl">
            <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
              <span className="flex items-center gap-1.5 font-medium"><HardDrive size={14} className="text-blue-400" /> RAM Memory</span>
              <span className="font-bold text-slate-100">{stats.memory_percent}%</span>
            </div>
            <div className="w-full bg-slate-950/80 h-2 rounded-full overflow-hidden mt-2 p-0.5 border border-slate-800">
              <div className="bg-gradient-to-r from-blue-500 to-indigo-500 h-full rounded-full transition-all duration-500 shadow-[0_0_8px_rgba(59,130,246,0.6)]" style={{ width: `${stats.memory_percent}%` }} />
            </div>
          </div>

          <div className="glass-panel p-4 rounded-xl">
            <div className="flex items-center justify-between text-slate-400 text-xs mb-1.5">
              <span className="flex items-center gap-1.5 font-medium"><Battery size={14} className="text-emerald-400" /> Battery</span>
              <span className="font-bold text-slate-100">{stats.battery_percent}%</span>
            </div>
            <div className="w-full bg-slate-950/80 h-2 rounded-full overflow-hidden mt-2 p-0.5 border border-slate-800">
              <div className="bg-gradient-to-r from-emerald-500 to-teal-400 h-full rounded-full transition-all duration-500 shadow-[0_0_8px_rgba(16,185,129,0.6)]" style={{ width: `${stats.battery_percent}%` }} />
            </div>
          </div>

          <div className="glass-panel p-4 rounded-xl flex flex-col justify-between">
            <div className="text-slate-400 text-xs font-medium">Uptime</div>
            <div className="font-bold text-slate-100 text-[15px] mt-1 tracking-tight">{formatUptime(stats.uptime_seconds)}</div>
          </div>
        </div>
      )}
    </div>
  );
};
