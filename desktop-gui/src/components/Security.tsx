import React, { useState, useEffect } from 'react';
import {
  ShieldCheck, Smartphone, Trash2, Power, Wifi, Lock,
  RefreshCw, CheckCircle2, ShieldAlert
} from 'lucide-react';
import { api } from '../api';
import type { TrustedDevice, PairingRequest } from '../types';

interface SecurityProps {
  pairRequests: PairingRequest[];
  onRefresh: () => void;
}

export const Security: React.FC<SecurityProps> = ({ pairRequests, onRefresh }) => {
  const [trustedDevices, setTrustedDevices] = useState<TrustedDevice[]>([]);
  const [loadingDevices, setLoadingDevices] = useState<boolean>(false);
  const [actionMsg, setActionMsg] = useState<string | null>(null);

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

  const handleApprove = async (ip: string) => {
    await api.approvePairing(ip, true);
    setActionMsg(`Authorized connection from ${ip}`);
    fetchDevices();
    onRefresh();
    setTimeout(() => setActionMsg(null), 3000);
  };

  const handleRevoke = async (id: string, name: string) => {
    const ok = await api.revokeDevice(id);
    if (ok) {
      setActionMsg(`Revoked permissions for "${name}"`);
      fetchDevices();
      onRefresh();
    } else {
      setActionMsg(`Failed to revoke device`);
    }
    setTimeout(() => setActionMsg(null), 3000);
  };

  return (
    <div className="space-y-4">
      {/* Pending Authorization Requests Alert */}
      {pairRequests.length > 0 && (
        <div className="p-4 rounded-2xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-between animate-pulse">
          <div className="flex items-center gap-3">
            <ShieldAlert className="text-amber-400" size={24} />
            <div>
              <div className="font-bold text-amber-200 text-sm">New Device Pairing Request</div>
              <div className="text-xs text-amber-300/80">
                {pairRequests[0].name} requested permission to control this PC.
              </div>
            </div>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => handleApprove(pairRequests[0].ip)}
              className="px-3 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-600 text-slate-950 font-bold text-xs shadow-sm transition"
            >
              Approve & Trust
            </button>
            <button
              onClick={onRefresh}
              className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs transition"
            >
              Deny
            </button>
          </div>
        </div>
      )}

      {/* Trusted Devices Management Card */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="p-2 rounded-xl bg-blue-500/15 text-blue-400">
              <ShieldCheck size={18} />
            </div>
            <div>
              <h3 className="font-bold text-sm text-slate-100">Authorized & Trusted Mobile Devices</h3>
              <p className="text-[11px] text-slate-400">Manage smartphones granted remote access and file privileges</p>
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
              No trusted devices registered yet. Authorize an incoming phone request or pair via QR Code.
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
                        {device.auto_connect ? 'Auto-Connect Enabled' : 'Manual Approval Required'}
                      </span>
                    </div>
                  </div>
                </div>

                <button
                  onClick={() => handleRevoke(device.id, device.name)}
                  className="px-2.5 py-1.5 rounded-lg bg-rose-500/15 hover:bg-rose-500/25 text-rose-300 border border-rose-500/30 flex items-center gap-1.5 transition text-xs font-semibold"
                >
                  <Trash2 size={13} />
                  <span>Revoke Access</span>
                </button>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Security & Access Policies */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Security & Encryption Policies</h3>
        <p className="text-[11px] text-slate-400 mb-3">Enforced boundaries and device isolation settings</p>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5 text-xs">
          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div className="flex items-center gap-2 text-emerald-400 font-semibold mb-1">
              <Lock size={14} />
              <span>TLS / AES-256</span>
            </div>
            <div className="text-[11px] text-slate-400">Restricted session token encryption active</div>
          </div>

          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div className="flex items-center gap-2 text-cyan-400 font-semibold mb-1">
              <Wifi size={14} />
              <span>Local LAN Only</span>
            </div>
            <div className="text-[11px] text-slate-400">Blocked to WAN internet and external subnets</div>
          </div>

          <div className="p-3 rounded-xl bg-slate-950/60 border border-slate-800">
            <div className="flex items-center gap-2 text-purple-400 font-semibold mb-1">
              <Power size={14} />
              <span>Zero-Lag UDP</span>
            </div>
            <div className="text-[11px] text-slate-400">Port 9999 protected packet validation</div>
          </div>
        </div>
      </div>
    </div>
  );
};
