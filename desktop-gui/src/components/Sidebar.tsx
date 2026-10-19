import React from 'react';
import type { LucideIcon } from 'lucide-react';
import {
  Server, Smartphone, Sliders, FolderSync, FolderOpen, Shield, Settings as SettingsIcon,
  Video
} from 'lucide-react';

export type TabKey =
  | 'server'
  | 'mobile-info'
  | 'camera-record'
  | 'folder-sync'
  | 'controls'
  | 'files'
  | 'security'
  | 'settings';

interface SidebarItem {
  key: TabKey;
  label: string;
  icon: LucideIcon;
  badge?: string | null;
  highlight?: boolean;
}

interface SidebarSection {
  title: string;
  items: SidebarItem[];
}

interface SidebarProps {
  activeTab: TabKey;
  onTabChange: (tab: TabKey) => void;
  pendingPairingsCount?: number;
  hasPhoneConnected?: boolean;
}

export const Sidebar: React.FC<SidebarProps> = ({
  activeTab,
  onTabChange,
  pendingPairingsCount = 0,
  hasPhoneConnected = false
}) => {
  const sections: SidebarSection[] = [
    {
      title: 'COMPUTER & STATUS',
      items: [
        { key: 'server', label: 'Status & Pairing', icon: Server, badge: pendingPairingsCount > 0 ? `${pendingPairingsCount}` : null },
      ]
    },
    {
      title: 'PHONE CONTROL',
      items: [
        { key: 'mobile-info', label: 'Phone Status', icon: Smartphone, highlight: hasPhoneConnected },
        { key: 'camera-record', label: 'Live Camera', icon: Video },
        { key: 'folder-sync', label: 'Folder Sync', icon: FolderSync },
        { key: 'controls', label: 'Quick Controls', icon: Sliders },
        { key: 'files', label: 'Files & Media', icon: FolderOpen },
      ]
    },
    {
      title: 'SYSTEM & SETTINGS',
      items: [
        { key: 'security', label: 'Security & Devices', icon: Shield },
        { key: 'settings', label: 'Settings', icon: SettingsIcon },
      ]
    }
  ];

  return (
    <aside className="w-60 bg-[#070C16] border-r border-cyan-500/15 flex flex-col justify-between p-3 select-none overflow-y-auto">
      <div className="space-y-4">
        {sections.map((sec, idx) => (
          <div key={idx} className="space-y-1">
            <div className="px-3 py-1 text-[9px] font-bold uppercase tracking-wider text-slate-500">
              {sec.title}
            </div>
            {sec.items.map((item) => {
              const Icon = item.icon;
              const isActive = activeTab === item.key;
              return (
                <button
                  key={item.key}
                  onClick={() => onTabChange(item.key)}
                  className={`w-full flex items-center justify-between px-3 py-2 rounded-xl font-medium text-xs transition-all ${
                    isActive
                      ? 'bg-cyan-500/15 text-cyan-300 border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
                  }`}
                >
                  <div className="flex items-center gap-2.5">
                    <Icon size={15} className={isActive ? 'text-cyan-400' : 'text-slate-400'} />
                    <span>{item.label}</span>
                  </div>
                  {item.badge && (
                    <span className="px-1.5 py-0.5 rounded-full text-[10px] font-bold bg-amber-500 text-slate-950 animate-bounce">
                      {item.badge}
                    </span>
                  )}
                  {item.highlight && !item.badge && (
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
                  )}
                </button>
              );
            })}
          </div>
        ))}
      </div>

      {/* Bottom Info badge */}
      <div className="p-3 rounded-xl bg-slate-900/60 border border-slate-800/80 text-[11px] text-slate-400 mt-3">
        <div className="font-semibold text-slate-300">Remote PC v3.2</div>
        <div className="text-[10px] text-slate-500 mt-0.5">Secure Local Connection</div>
      </div>
    </aside>
  );
};

