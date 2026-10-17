import React, { useState } from 'react';
import {
  Bell, BellOff, CheckCircle2, Trash2, Smartphone,
  MessageSquare
} from 'lucide-react';
import type { ServerStatus, PhoneNotification } from '../types';

interface NotificationsCenterProps {
  status: ServerStatus | null;
}

export const NotificationsCenter: React.FC<NotificationsCenterProps> = ({ status }) => {
  const [syncEnabled, setSyncEnabled] = useState(true);
  const [actionMsg, setActionMsg] = useState<string | null>(null);

  const connectedClient = status?.clients && status.clients.length > 0
    ? status.clients[0]
    : status?.connected_clients_map
    ? Object.values(status.connected_clients_map)[0]
    : null;

  const phoneIp = connectedClient?.ip;

  const [notifications, setNotifications] = useState<PhoneNotification[]>([
    {
      id: '1',
      packageName: 'com.whatsapp',
      appName: 'WhatsApp',
      title: 'Mom',
      content: 'Call me when you reach home!',
      time: '2 mins ago'
    },
    {
      id: '2',
      packageName: 'com.google.android.gm',
      appName: 'Gmail',
      title: 'Security Alert',
      content: 'New sign-in from Windows PC on LAN network.',
      time: '14 mins ago'
    },
    {
      id: '3',
      packageName: 'com.spotify.music',
      appName: 'Spotify',
      title: 'Now Playing',
      content: 'Starboy • The Weeknd',
      time: 'Just now'
    }
  ]);

  const handleDismiss = (id: string) => {
    setNotifications((prev) => prev.filter((n) => n.id !== id));
    setActionMsg('Notification dismissed on phone');
    setTimeout(() => setActionMsg(null), 2500);
  };

  const handleClearAll = () => {
    setNotifications([]);
    setActionMsg('Cleared all active notifications');
    setTimeout(() => setActionMsg(null), 2500);
  };

  return (
    <div className="space-y-4">
      {/* Header Bar */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400">
            <Bell size={20} />
          </div>
          <div>
            <h2 className="font-bold text-sm text-slate-100">Live Notification Center</h2>
            <p className="text-[11px] text-slate-400">
              {phoneIp ? `Mirroring notifications from ${connectedClient.name}` : 'Connect your phone to view real-time mobile notifications'}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => setSyncEnabled(!syncEnabled)}
            className={`px-3 py-1.5 rounded-xl text-xs font-semibold flex items-center gap-1.5 transition ${
              syncEnabled
                ? 'bg-emerald-500/15 text-emerald-300 border border-emerald-500/30'
                : 'bg-slate-800 text-slate-400'
            }`}
          >
            {syncEnabled ? <Bell size={13} /> : <BellOff size={13} />}
            <span>{syncEnabled ? 'Sync Active' : 'Sync Paused'}</span>
          </button>

          {notifications.length > 0 && (
            <button
              onClick={handleClearAll}
              className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-semibold flex items-center gap-1.5 transition border border-slate-700/60"
            >
              <Trash2 size={13} />
              <span>Dismiss All</span>
            </button>
          )}
        </div>
      </div>

      {actionMsg && (
        <div className="px-4 py-2 rounded-xl bg-cyan-950/80 border border-cyan-500/40 text-cyan-300 text-xs font-semibold animate-pulse">
          ⚡ {actionMsg}
        </div>
      )}

      {/* Notifications List */}
      {!phoneIp ? (
        <div className="p-12 rounded-2xl bg-slate-900/40 border border-slate-800/80 text-center space-y-2">
          <Smartphone size={32} className="text-slate-600 mx-auto" />
          <h4 className="font-bold text-slate-300 text-sm">Phone Offline</h4>
          <p className="text-xs text-slate-500">
            Open the Remote PC app on your mobile phone to synchronize incoming notifications.
          </p>
        </div>
      ) : notifications.length === 0 ? (
        <div className="p-12 rounded-2xl bg-slate-900/40 border border-slate-800/80 text-center space-y-2">
          <CheckCircle2 size={32} className="text-emerald-500/50 mx-auto" />
          <h4 className="font-bold text-slate-300 text-sm">All Caught Up!</h4>
          <p className="text-xs text-slate-500">
            No active unread notifications on your phone right now.
          </p>
        </div>
      ) : (
        <div className="space-y-2.5">
          {notifications.map((notif) => (
            <div
              key={notif.id}
              className="p-3.5 rounded-xl bg-slate-900/70 border border-slate-800/80 hover:border-cyan-500/30 transition flex items-start justify-between gap-3 group"
            >
              <div className="flex items-start gap-3 flex-1 min-w-0">
                <div className="p-2.5 rounded-xl bg-slate-950/80 border border-slate-800 text-cyan-400 shrink-0">
                  <MessageSquare size={16} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-xs text-slate-200">{notif.appName}</span>
                    <span className="text-[10px] text-slate-500">• {notif.time}</span>
                  </div>
                  <h5 className="font-semibold text-xs text-slate-300 mt-0.5 truncate">{notif.title}</h5>
                  <p className="text-xs text-slate-400 mt-0.5 line-clamp-2">{notif.content}</p>
                </div>
              </div>

              <button
                onClick={() => handleDismiss(notif.id)}
                className="p-1.5 rounded-lg bg-slate-800/60 hover:bg-rose-500/20 hover:text-rose-300 text-slate-400 transition"
                title="Dismiss Notification"
              >
                <Trash2 size={13} />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
