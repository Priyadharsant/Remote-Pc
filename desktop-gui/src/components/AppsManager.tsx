import React, { useState } from 'react';
import {
  Grid, Search, Play, Settings, Smartphone
} from 'lucide-react';
import { api } from '../api';
import type { ServerStatus, InstalledApp } from '../types';

interface AppsManagerProps {
  status: ServerStatus | null;
}

export const AppsManager: React.FC<AppsManagerProps> = ({ status }) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [actionMsg, setActionMsg] = useState<string | null>(null);

  const connectedClient = status?.clients && status.clients.length > 0
    ? status.clients[0]
    : status?.connected_clients_map
    ? Object.values(status.connected_clients_map)[0]
    : null;

  const phoneIp = connectedClient?.ip;

  // Curated common apps and quick launchers
  const commonApps: InstalledApp[] = [
    { name: 'YouTube', packageName: 'com.google.android.youtube', versionName: '19.24', isSystem: false },
    { name: 'WhatsApp', packageName: 'com.whatsapp', versionName: '2.24.12', isSystem: false },
    { name: 'Google Chrome', packageName: 'com.android.chrome', versionName: '126.0', isSystem: false },
    { name: 'Spotify', packageName: 'com.spotify.music', versionName: '8.9.44', isSystem: false },
    { name: 'Camera', packageName: 'com.android.camera', versionName: 'System', isSystem: true },
    { name: 'Gallery / Photos', packageName: 'com.google.android.apps.photos', versionName: '6.88', isSystem: false },
    { name: 'Settings', packageName: 'com.android.settings', versionName: 'System', isSystem: true },
    { name: 'Google Maps', packageName: 'com.google.android.apps.maps', versionName: '11.133', isSystem: false },
    { name: 'Gmail', packageName: 'com.google.android.gm', versionName: '2024.06', isSystem: false },
    { name: 'Files / Downloads', packageName: 'com.google.android.documentsui', versionName: 'System', isSystem: true },
  ];

  const handleLaunchApp = async (app: InstalledApp) => {
    if (!phoneIp) {
      setActionMsg('No phone connected');
      setTimeout(() => setActionMsg(null), 2500);
      return;
    }
    setActionMsg(`Launching ${app.name} on phone...`);
    await api.sendPhoneAction(phoneIp, 'launch_app', app.packageName);
    setTimeout(() => {
      setActionMsg(`Launched ${app.name}`);
      setTimeout(() => setActionMsg(null), 2500);
    }, 1000);
  };

  const handleOpenAppSettings = async (app: InstalledApp) => {
    if (!phoneIp) return;
    setActionMsg(`Opening settings for ${app.name}...`);
    await api.sendPhoneAction(phoneIp, 'app_settings', app.packageName);
    setTimeout(() => setActionMsg(null), 2500);
  };

  const filteredApps = commonApps.filter(
    (a) =>
      a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      a.packageName.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="space-y-4">
      {/* Header & Search */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400">
            <Grid size={20} />
          </div>
          <div>
            <h2 className="font-bold text-sm text-slate-100">Application Management</h2>
            <p className="text-[11px] text-slate-400">
              {phoneIp ? `Target: ${connectedClient.name} • Connected` : 'Connect mobile phone to inspect & launch apps'}
            </p>
          </div>
        </div>

        <div className="relative w-full sm:w-64">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
          <input
            type="text"
            placeholder="Search installed apps..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3 py-1.5 rounded-xl bg-slate-950/80 border border-slate-800 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500/50"
          />
        </div>
      </div>

      {actionMsg && (
        <div className="px-4 py-2 rounded-xl bg-cyan-950/80 border border-cyan-500/40 text-cyan-300 text-xs font-semibold animate-pulse">
          ⚡ {actionMsg}
        </div>
      )}

      {/* Grid of Apps */}
      {!phoneIp ? (
        <div className="p-12 rounded-2xl bg-slate-900/40 border border-slate-800/80 text-center space-y-2">
          <Smartphone size={32} className="text-slate-600 mx-auto" />
          <h4 className="font-bold text-slate-300 text-sm">Phone Offline</h4>
          <p className="text-xs text-slate-500">
            Connect your phone to view and trigger installed applications remotely.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {filteredApps.map((app) => (
            <div
              key={app.packageName}
              className="p-3.5 rounded-xl bg-slate-900/70 border border-slate-800/80 hover:border-cyan-500/30 transition flex flex-col justify-between group"
            >
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-slate-950/80 border border-slate-800 flex items-center justify-center font-bold text-sm text-cyan-400 shrink-0">
                  {app.name.charAt(0)}
                </div>
                <div className="flex-1 min-w-0">
                  <h4 className="font-bold text-xs text-slate-200 truncate">{app.name}</h4>
                  <p className="text-[10px] text-slate-500 font-mono truncate mt-0.5">
                    {app.packageName}
                  </p>
                  <div className="flex items-center gap-1.5 mt-1 text-[10px] text-slate-400">
                    <span>v{app.versionName}</span>
                    <span>•</span>
                    <span className={app.isSystem ? 'text-amber-400' : 'text-emerald-400'}>
                      {app.isSystem ? 'System' : 'User App'}
                    </span>
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2 mt-3 pt-2 border-t border-slate-800/60">
                <button
                  onClick={() => handleLaunchApp(app)}
                  className="flex-1 py-1.5 rounded-lg bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-xs flex items-center justify-center gap-1.5 transition active:scale-98"
                >
                  <Play size={12} />
                  <span>Launch on Phone</span>
                </button>
                <button
                  onClick={() => handleOpenAppSettings(app)}
                  className="p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-slate-200 transition"
                  title="App Info & Permissions"
                >
                  <Settings size={14} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
