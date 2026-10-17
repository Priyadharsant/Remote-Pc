import React, { useState, useEffect } from 'react';
import {
  ShieldCheck, Smartphone, Trash2, Power, Wifi, Lock,
  RefreshCw, CheckCircle2, Download, Sparkles
} from 'lucide-react';
import { api } from '../api';
import type { TrustedDevice } from '../types';

interface SettingsProps {
  onRefresh: () => void;
}

export const Settings: React.FC<SettingsProps> = ({ onRefresh }) => {
  const [trustedDevices, setTrustedDevices] = useState<TrustedDevice[]>([]);
  const [autoConnect, setAutoConnect] = useState<boolean>(true);
  const [autoStartWindows, setAutoStartWindows] = useState<boolean>(true);
  const [loadingDevices, setLoadingDevices] = useState<boolean>(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);

  // Update states
  const [checkingUpdate, setCheckingUpdate] = useState<boolean>(false);
  const [updateInfo, setUpdateInfo] = useState<{
    update_available: boolean;
    current_version: string;
    latest_version?: string;
    release_title?: string;
    release_notes?: string;
    exe_url?: string;
    html_url?: string;
    error?: string;
  } | null>(null);
  const [installingUpdate, setInstallingUpdate] = useState<boolean>(false);
  const [updateProgress, setUpdateProgress] = useState<number>(0);
  const [updateStatusMsg, setUpdateStatusMsg] = useState<string>('');

  const fetchDevices = async () => {
    setLoadingDevices(true);
    try {
      const devs = await api.getTrustedDevices();
      setTrustedDevices(devs);
    } catch {
      setTrustedDevices([]);
    } finally {
      setLoadingDevices(false);
    }
  };

  useEffect(() => {
    fetchDevices();
  }, []);

  const handleRevoke = async (id: string, name: string) => {
    const ok = await api.revokeDevice(id);
    if (ok) {
      setActionMsg(`Revoked "${name}"`);
      fetchDevices();
      onRefresh();
    } else {
      setActionMsg(`Failed to revoke device`);
    }
    setTimeout(() => setActionMsg(null), 3000);
  };

  const handleCheckUpdate = async () => {
    setCheckingUpdate(true);
    setUpdateInfo(null);
    try {
      const data = await api.checkUpdate();
      setUpdateInfo(data);
    } catch (e: any) {
      setUpdateInfo({
        update_available: false,
        current_version: '3.0.0',
        error: e.message || 'Failed to check updates'
      });
    } finally {
      setCheckingUpdate(false);
    }
  };

  const handleApplyUpdate = async (exeUrl: string) => {
    setInstallingUpdate(true);
    setUpdateProgress(5);
    setUpdateStatusMsg('Starting download...');
    try {
      await api.applyUpdate(exeUrl);
      const timer = setInterval(async () => {
        try {
          const st = await api.getUpdateStatus();
          setUpdateProgress(st.progress);
          setUpdateStatusMsg(st.message);
          if (st.status === 'error') {
            clearInterval(timer);
            setInstallingUpdate(false);
          }
        } catch {
          clearInterval(timer);
          setUpdateProgress(100);
          setUpdateStatusMsg('Installer launched. Remote PC is restarting...');
        }
      }, 1000);
    } catch (e: any) {
      setInstallingUpdate(false);
      setUpdateStatusMsg(e.message || 'Update failed');
    }
  };

  return (
    <div className="space-y-4">
      {/* Settings Card: General & Auto-Connect */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md space-y-4">
        <div className="flex items-center gap-2">
          <div className="p-2 rounded-xl bg-cyan-500/15 text-cyan-400">
            <ShieldCheck size={18} />
          </div>
          <div>
            <h3 className="font-bold text-sm text-slate-100">Connection & Startup Preferences</h3>
            <p className="text-[11px] text-slate-400">Configure background pairing & Windows startup behavior</p>
          </div>
        </div>

        <div className="space-y-3 pt-2">
          {/* Auto-Connect Toggle */}
          <div className="flex items-center justify-between p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div>
              <div className="font-semibold text-xs text-slate-200">Auto-Connect Paired Devices</div>
              <div className="text-[11px] text-slate-400 mt-0.5">
                Automatically accept connections from previously authorized phones on Wi-Fi without PIN prompt.
              </div>
            </div>
            <button
              onClick={() => setAutoConnect(!autoConnect)}
              className={`w-11 h-6 flex items-center rounded-full p-1 cursor-pointer transition-colors ${
                autoConnect ? 'bg-cyan-500 justify-end' : 'bg-slate-800 justify-start'
              }`}
            >
              <div className="bg-slate-950 w-4 h-4 rounded-full shadow-md transform transition-transform" />
            </button>
          </div>

          {/* Auto-Start on Boot */}
          <div className="flex items-center justify-between p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div>
              <div className="font-semibold text-xs text-slate-200">Start with Windows</div>
              <div className="text-[11px] text-slate-400 mt-0.5">
                Launch Remote PC Server and minimize to System Tray when Windows boots.
              </div>
            </div>
            <button
              onClick={() => setAutoStartWindows(!autoStartWindows)}
              className={`w-11 h-6 flex items-center rounded-full p-1 cursor-pointer transition-colors ${
                autoStartWindows ? 'bg-cyan-500 justify-end' : 'bg-slate-800 justify-start'
              }`}
            >
              <div className="bg-slate-950 w-4 h-4 rounded-full shadow-md transform transition-transform" />
            </button>
          </div>
        </div>
      </div>

      {/* Trusted Devices Management */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="p-2 rounded-xl bg-blue-500/15 text-blue-400">
              <Smartphone size={18} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-slate-100">Authorized & Trusted Devices</h3>
              <p className="text-[11px] text-slate-400">Devices that have been paired and granted control access</p>
            </div>
          </div>
          <button
            onClick={fetchDevices}
            disabled={loadingDevices}
            className="p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 transition"
            title="Refresh Devices"
          >
            <RefreshCw size={14} className={loadingDevices ? 'animate-spin text-cyan-400' : ''} />
          </button>
        </div>

        {actionMsg && (
          <div className="flex items-center gap-1.5 text-xs text-emerald-400 bg-emerald-500/10 px-3 py-2 rounded-xl border border-emerald-500/30">
            <CheckCircle2 size={14} /> {actionMsg}
          </div>
        )}

        <div className="space-y-2">
          {trustedDevices.length === 0 ? (
            <div className="text-center py-6 text-xs text-slate-500 border border-dashed border-slate-800 rounded-xl">
              No trusted devices registered yet. Authorize a phone from the Server Details tab.
            </div>
          ) : (
            trustedDevices.map((device) => (
              <div
                key={device.id || device.name}
                className="flex items-center justify-between p-3 rounded-xl bg-slate-950/60 border border-slate-800 text-xs"
              >
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-lg bg-slate-900 border border-slate-800 text-cyan-400">
                    <Smartphone size={16} />
                  </div>
                  <div>
                    <div className="font-bold text-slate-200">{device.name}</div>
                    <div className="text-[11px] text-slate-400 flex items-center gap-2 mt-0.5">
                      <span>Connection: Wi-Fi Paired</span>
                      <span>•</span>
                      <span className={device.auto_connect ? 'text-emerald-400' : 'text-amber-400'}>
                        {device.auto_connect ? 'Auto-Connect Enabled' : 'Manual Approval'}
                      </span>
                    </div>
                  </div>
                </div>

                <button
                  onClick={() => handleRevoke(device.id, device.name)}
                  className="px-2.5 py-1.5 rounded-lg bg-rose-500/15 hover:bg-rose-500/25 text-rose-300 border border-rose-500/30 flex items-center gap-1.5 transition text-xs font-semibold"
                >
                  <Trash2 size={13} />
                  <span>Revoke</span>
                </button>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Software Updates Card */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="p-2 rounded-xl bg-cyan-500/15 text-cyan-400">
              <Sparkles size={18} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-slate-100">Software Updates</h3>
              <p className="text-[11px] text-slate-400">Current version: v3.0.0 (GitHub Releases)</p>
            </div>
          </div>
          <button
            onClick={handleCheckUpdate}
            disabled={checkingUpdate || installingUpdate}
            className="px-3 py-1.5 rounded-xl bg-cyan-500/20 hover:bg-cyan-500/30 text-cyan-300 border border-cyan-500/30 font-semibold text-xs transition flex items-center gap-1.5 disabled:opacity-50"
          >
            <RefreshCw size={13} className={checkingUpdate ? 'animate-spin' : ''} />
            <span>{checkingUpdate ? 'Checking GitHub...' : 'Check for Updates'}</span>
          </button>
        </div>

        {updateInfo && (
          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800 space-y-2">
            {updateInfo.update_available ? (
              <div>
                <div className="flex items-center justify-between">
                  <span className="text-xs font-bold text-cyan-400">
                    🎉 New version available: {updateInfo.latest_version}
                  </span>
                  {updateInfo.exe_url && (
                    <button
                      onClick={() => handleApplyUpdate(updateInfo.exe_url!)}
                      disabled={installingUpdate}
                      className="px-3 py-1 rounded-lg bg-emerald-500 hover:bg-emerald-600 text-slate-950 font-bold text-xs transition flex items-center gap-1.5"
                    >
                      <Download size={13} />
                      <span>{installingUpdate ? 'Updating...' : 'Download & Install'}</span>
                    </button>
                  )}
                </div>
                {updateInfo.release_notes && (
                  <p className="text-[11px] text-slate-300 mt-1 whitespace-pre-line line-clamp-3">
                    {updateInfo.release_notes}
                  </p>
                )}
                {installingUpdate && (
                  <div className="space-y-1.5 mt-2">
                    <div className="w-full bg-slate-800 rounded-full h-2 overflow-hidden">
                      <div
                        className="bg-cyan-400 h-2 rounded-full transition-all duration-300"
                        style={{ width: `${updateProgress}%` }}
                      />
                    </div>
                    <span className="text-[10px] text-slate-400 block">{updateStatusMsg}</span>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex items-center gap-2 text-xs text-slate-300">
                <CheckCircle2 size={15} className="text-emerald-400" />
                <span>Remote PC is up to date (v{updateInfo.current_version})</span>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Network & Security Protocol Status */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Network & Security Status</h3>
        <p className="text-[11px] text-slate-400 mb-3">Diagnostic status of Remote PC connection layers</p>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5 text-xs">
          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div className="flex items-center gap-2 text-emerald-400 font-semibold mb-1">
              <Wifi size={14} />
              <span>Port 5055 (HTTP)</span>
            </div>
            <div className="text-[11px] text-slate-400">Active & listening for REST calls</div>
          </div>

          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div className="flex items-center gap-2 text-cyan-400 font-semibold mb-1">
              <Power size={14} />
              <span>Port 9999 (UDP)</span>
            </div>
            <div className="text-[11px] text-slate-400">Zero-latency mouse input streaming</div>
          </div>

          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div className="flex items-center gap-2 text-purple-400 font-semibold mb-1">
              <Lock size={14} />
              <span>Local Network</span>
            </div>
            <div className="text-[11px] text-slate-400">Restricted to LAN subnet</div>
          </div>
        </div>
      </div>
    </div>
  );
};
