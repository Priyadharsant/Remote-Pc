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
        <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md relative overflow-hidden">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <div className="p-2 rounded-xl bg-cyan-500/15 text-cyan-400">
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
              className="p-1.5 rounded-lg bg-slate-800/80 hover:bg-slate-700 text-slate-300 hover:text-cyan-300 transition"
            >
              <RefreshCw size={14} />
            </button>
          </div>

          <div className="grid grid-cols-2 gap-2 mt-4 text-xs">
            <div className="p-2.5 rounded-xl bg-slate-950/60 border border-slate-800">
              <div className="text-slate-400 text-[10px]">HOST / NAME</div>
              <div className="font-bold text-slate-200 truncate mt-0.5">{status?.name || 'Local PC'}</div>
            </div>
            <div className="p-2.5 rounded-xl bg-slate-950/60 border border-slate-800">
              <div className="text-slate-400 text-[10px]">NETWORK STATUS</div>
              <div className="font-bold text-cyan-300 truncate mt-0.5">Local Wi-Fi Network</div>
            </div>
          </div>
        </div>

        {/* Card 2: Connected Phone Card */}
        <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md relative">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <div className="p-2 rounded-xl bg-blue-500/15 text-blue-400">
                <Smartphone size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-100">Connected Device</h3>
                <p className="text-[11px] text-slate-400">
                  {connectedClient ? 'Active Connection' : 'No Phone Connected'}
                </p>
              </div>
            </div>
            {connectedClient && (
              <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                ● Live
              </span>
            )}
          </div>

          {connectedClient ? (
            <div className="space-y-2 mt-2">
              <div className="flex items-center justify-between text-xs p-2.5 rounded-xl bg-slate-950/60 border border-slate-800">
                <span className="font-bold text-slate-200">{connectedClient.name}</span>
                <span className="text-emerald-400 text-[11px] font-medium">Wi-Fi Connected</span>
              </div>
              <div className="flex gap-2">
                <input
                  type="text"
                  placeholder="Send instant toast to phone..."
                  value={toastMsg}
                  onChange={(e) => setToastMsg(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSendToast()}
                  className="flex-1 bg-slate-950/70 border border-slate-800 rounded-lg px-2.5 py-1 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500/50"
                />
                <button
                  onClick={handleSendToast}
                  disabled={isSendingToast || !toastMsg.trim()}
                  className="px-2.5 py-1 rounded-lg bg-cyan-500 hover:bg-cyan-600 disabled:opacity-40 text-slate-950 font-bold text-xs flex items-center gap-1 transition"
                >
                  <Send size={11} />
                </button>
              </div>
            </div>
          ) : (
            <div className="text-center py-4 text-xs text-slate-400">
              Launch the Remote PC mobile app on your phone to automatically link.
            </div>
          )}
        </div>
      </div>

      {/* Row 2: QR Code & PIN Code Pairing Card */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <div className="p-2 rounded-xl bg-amber-500/15 text-amber-400">
              <KeyRound size={18} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-slate-100">Wireless Pairing Credentials</h3>
              <p className="text-[11px] text-slate-400">Scan QR or enter PIN in mobile app</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-slate-400">Quick PIN:</span>
            <span className="px-3 py-1 rounded-lg bg-cyan-950/80 border border-cyan-500/40 font-mono font-bold text-cyan-300 text-sm tracking-wider">
              {status?.pin || '8888'}
            </span>
          </div>
        </div>

        <div className="flex flex-col sm:flex-row items-center gap-4 mt-4 p-3 rounded-xl bg-slate-950/60 border border-slate-800">
          {status?.qr_code ? (
            <img
              src={`data:image/png;base64,${status.qr_code}`}
              alt="Pairing QR Code"
              className="w-28 h-28 rounded-lg bg-white p-1 shadow-md shadow-cyan-500/10"
            />
          ) : (
            <div className="w-28 h-28 rounded-lg bg-slate-900 flex items-center justify-center text-slate-600">
              <QrCode size={40} />
            </div>
          )}
          <div className="text-xs space-y-1.5 flex-1">
            <div className="font-semibold text-slate-200">How to Pair with Your Phone:</div>
            <ol className="list-decimal list-inside text-slate-400 space-y-1 text-[11px]">
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
          <div className="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800/80">
            <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
              <span className="flex items-center gap-1.5"><Cpu size={14} className="text-cyan-400" /> CPU Load</span>
              <span className="font-bold text-slate-200">{stats.cpu_percent}%</span>
            </div>
            <div className="w-full bg-slate-950 h-1.5 rounded-full overflow-hidden mt-2">
              <div className="bg-cyan-400 h-full rounded-full transition-all" style={{ width: `${stats.cpu_percent}%` }} />
            </div>
          </div>

          <div className="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800/80">
            <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
              <span className="flex items-center gap-1.5"><HardDrive size={14} className="text-blue-400" /> RAM Memory</span>
              <span className="font-bold text-slate-200">{stats.memory_percent}%</span>
            </div>
            <div className="w-full bg-slate-950 h-1.5 rounded-full overflow-hidden mt-2">
              <div className="bg-blue-400 h-full rounded-full transition-all" style={{ width: `${stats.memory_percent}%` }} />
            </div>
          </div>

          <div className="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800/80">
            <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
              <span className="flex items-center gap-1.5"><Battery size={14} className="text-emerald-400" /> Battery</span>
              <span className="font-bold text-slate-200">{stats.battery_percent}%</span>
            </div>
            <div className="w-full bg-slate-950 h-1.5 rounded-full overflow-hidden mt-2">
              <div className="bg-emerald-400 h-full rounded-full transition-all" style={{ width: `${stats.battery_percent}%` }} />
            </div>
          </div>

          <div className="p-3.5 rounded-xl bg-slate-900/60 border border-slate-800/80 flex flex-col justify-between">
            <div className="text-slate-400 text-xs">Uptime</div>
            <div className="font-bold text-slate-200 text-sm mt-1">{formatUptime(stats.uptime_seconds)}</div>
          </div>
        </div>
      )}
    </div>
  );
};
