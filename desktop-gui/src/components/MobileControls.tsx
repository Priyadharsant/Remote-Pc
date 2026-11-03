import React, { useState } from 'react';
import {
  Sliders, Volume2, Sun, Play, SkipBack, SkipForward,
  Lock, Moon, Clipboard, Send,
  Flashlight, PhoneCall, MessageSquare, ArrowLeft, Home, Layers
} from 'lucide-react';
import { api } from '../api';
import type { ServerStatus } from '../types';

interface MobileControlsProps {
  status: ServerStatus | null;
  onRefresh: () => void;
}

export const MobileControls: React.FC<MobileControlsProps> = ({ status }) => {
  const [phoneVolume, setPhoneVolume] = useState<number>(65);
  const [phoneBrightness, setPhoneBrightness] = useState<number>(80);
  const [clipboardText, setClipboardText] = useState<string>('');
  const [actionFeedback, setActionFeedback] = useState<string | null>(null);
  const [isFlashlightOn, setIsFlashlightOn] = useState<boolean>(false);

  const connectedClient = status?.clients && status.clients.length > 0
    ? status.clients[0]
    : status?.connected_clients_map
    ? Object.values(status.connected_clients_map)[0]
    : null;

  const phoneIp = connectedClient?.ip;

  const triggerPhoneAction = async (action: string, value = '', label?: string) => {
    if (!phoneIp) {
      setActionFeedback('No phone connected to execute action');
      setTimeout(() => setActionFeedback(null), 2500);
      return;
    }
    setActionFeedback(`Sending "${label || action}" to ${connectedClient.name}...`);
    const res = await api.sendPhoneAction(phoneIp, action, value);
    if (res.status === 'ok') {
      setActionFeedback(`✓ Done: ${label || action}`);
    } else {
      setActionFeedback(`Action requested: ${label || action}`);
    }
    setTimeout(() => setActionFeedback(null), 3000);
  };

  const handleSendClipboard = async () => {
    if (!clipboardText.trim()) return;
    if (phoneIp) {
      const ok = await api.sendClipboardToPhone(phoneIp, clipboardText.trim());
      setActionFeedback(ok ? '✓ Clipboard synced to phone' : 'Failed to send clipboard');
    } else {
      try {
        await navigator.clipboard.writeText(clipboardText.trim());
        setActionFeedback('✓ Copied to clipboard');
      } catch {
        setActionFeedback('Copy error');
      }
    }
    setTimeout(() => setActionFeedback(null), 3000);
  };

  const toggleFlashlight = () => {
    const next = !isFlashlightOn;
    setIsFlashlightOn(next);
    triggerPhoneAction('flashlight', next ? 'on' : 'off', `Flashlight ${next ? 'On' : 'Off'}`);
  };

  return (
    <div className="space-y-4">
      {/* Top Banner & Feedback */}
      <div className="glass-panel p-5 rounded-2xl flex items-center justify-between relative overflow-hidden">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400 border border-cyan-500/25 shadow-sm shadow-cyan-500/20">
            <Sliders size={20} />
          </div>
          <div>
            <h2 className="font-bold text-sm text-slate-100">Phone Remote Control Deck</h2>
            <p className="text-[11px] text-slate-400">
              {phoneIp ? `Controlling ${connectedClient.name} • Connected` : 'Connect mobile device to trigger hardware controls'}
            </p>
          </div>
        </div>

        {actionFeedback && (
          <div className="px-3.5 py-1.5 rounded-xl bg-cyan-950/90 border border-cyan-500/50 text-cyan-300 text-xs font-semibold shadow-lg shadow-cyan-500/20 animate-pulse">
            {actionFeedback}
          </div>
        )}
      </div>

      {/* Grid: Audio & Brightness Sliders */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Phone Media Volume */}
        <div className="glass-panel p-5 rounded-2xl">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2.5">
              <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400 border border-cyan-500/25">
                <Volume2 size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-100">Phone Volume Level</h3>
                <p className="text-[11px] text-slate-400">Speaker, media & ringer level</p>
              </div>
            </div>
            <button
              onClick={() => triggerPhoneAction('mute', '', 'Mute Audio')}
              className="px-3 py-1 rounded-xl bg-slate-800/80 hover:bg-slate-700 text-slate-300 hover:text-cyan-300 text-xs font-semibold transition border border-slate-700/60 active:scale-95"
            >
              Mute
            </button>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <input
              type="range"
              min="0"
              max="100"
              value={phoneVolume}
              onChange={(e) => {
                const val = Number(e.target.value);
                setPhoneVolume(val);
                triggerPhoneAction('volume', String(val), `Volume ${val}%`);
              }}
              className="flex-1 h-2 bg-slate-950/80 rounded-lg appearance-none cursor-pointer accent-cyan-400 border border-slate-800"
            />
            <span className="w-12 text-center py-0.5 rounded-md bg-cyan-950/80 border border-cyan-500/30 font-mono font-bold text-cyan-300 text-xs">
              {phoneVolume}%
            </span>
          </div>
        </div>

        {/* Phone Brightness */}
        <div className="glass-panel p-5 rounded-2xl">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2.5">
              <div className="p-2.5 rounded-xl bg-amber-500/15 text-amber-400 border border-amber-500/25">
                <Sun size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-100">Phone Brightness</h3>
                <p className="text-[11px] text-slate-400">Mobile screen backlight adjustment</p>
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <input
              type="range"
              min="0"
              max="100"
              value={phoneBrightness}
              onChange={(e) => {
                const val = Number(e.target.value);
                setPhoneBrightness(val);
                triggerPhoneAction('brightness', String(val), `Brightness ${val}%`);
              }}
              className="flex-1 h-2 bg-slate-950/80 rounded-lg appearance-none cursor-pointer accent-amber-400 border border-slate-800"
            />
            <span className="w-12 text-center py-0.5 rounded-md bg-amber-950/80 border border-amber-500/30 font-mono font-bold text-amber-300 text-xs">
              {phoneBrightness}%
            </span>
          </div>
        </div>
      </div>

      {/* Touch & Navigation Keys Deck */}
      <div className="glass-panel p-5 rounded-2xl">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Android System Navigation & Gestures</h3>
        <p className="text-[11px] text-slate-400 mb-3.5">Simulate hardware navigation bar buttons and gestures</p>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <button
            onClick={() => triggerPhoneAction('nav_back', '', 'Back Button')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex flex-col items-center justify-center gap-1.5 text-slate-300 hover:text-cyan-300 transition-all active:scale-95 group shadow-sm"
          >
            <ArrowLeft size={18} className="text-cyan-400 group-hover:-translate-x-1 transition" />
            <span className="text-xs font-semibold">Back</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('nav_home', '', 'Home Screen')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex flex-col items-center justify-center gap-1.5 text-slate-300 hover:text-blue-300 transition-all active:scale-95 group shadow-sm"
          >
            <Home size={18} className="text-blue-400 group-hover:scale-110 transition" />
            <span className="text-xs font-semibold">Home</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('nav_recents', '', 'Recent Apps')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex flex-col items-center justify-center gap-1.5 text-slate-300 hover:text-purple-300 transition-all active:scale-95 group shadow-sm"
          >
            <Layers size={18} className="text-purple-400 group-hover:scale-110 transition" />
            <span className="text-xs font-semibold">Recent Apps</span>
          </button>

          <button
            onClick={toggleFlashlight}
            className={`p-3.5 rounded-xl border flex flex-col items-center justify-center gap-1.5 transition-all active:scale-95 shadow-sm ${
              isFlashlightOn
                ? 'bg-amber-500/20 border-amber-500/40 text-amber-300 shadow-amber-500/20 shadow-md'
                : 'bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 text-slate-300 hover:text-amber-300'
            }`}
          >
            <Flashlight size={18} className={isFlashlightOn ? 'text-amber-400 animate-pulse' : 'text-slate-400'} />
            <span className="text-xs font-semibold">Flashlight</span>
          </button>
        </div>
      </div>

      {/* Calls, Messaging & Apps Shortcuts */}
      <div className="glass-panel p-5 rounded-2xl">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Calls, Messaging & System Screens</h3>
        <p className="text-[11px] text-slate-400 mb-3.5">Quick launch system dialer, SMS, contacts, and settings</p>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          <button
            onClick={() => triggerPhoneAction('open_dialer', '', 'Phone Dialer')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex items-center gap-2.5 text-slate-300 hover:text-emerald-300 transition-all active:scale-95 shadow-sm"
          >
            <PhoneCall size={16} className="text-emerald-400" />
            <span className="text-xs font-medium">Open Dialer</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('open_sms', '', 'Messages App')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex items-center gap-2.5 text-slate-300 hover:text-blue-300 transition-all active:scale-95 shadow-sm"
          >
            <MessageSquare size={16} className="text-blue-400" />
            <span className="text-xs font-medium">Open SMS</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('lock_screen', '', 'Lock Mobile Screen')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex items-center gap-2.5 text-slate-300 hover:text-amber-300 transition-all active:scale-95 shadow-sm"
          >
            <Lock size={16} className="text-amber-400" />
            <span className="text-xs font-medium">Lock Screen</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('screen_off', '', 'Screen Off')}
            className="p-3.5 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800/80 flex items-center gap-2.5 text-slate-300 hover:text-rose-400 transition-all active:scale-95 shadow-sm"
          >
            <Moon size={16} className="text-rose-400" />
            <span className="text-xs font-medium">Turn Screen Off</span>
          </button>
        </div>
      </div>

      {/* Media Playback Deck */}
      <div className="glass-panel p-5 rounded-2xl">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Phone Media Playback Deck</h3>
        <p className="text-[11px] text-slate-400 mb-3.5">Control Spotify, YouTube, or Music player running on phone</p>

        <div className="flex items-center justify-center gap-5 py-3">
          <button
            onClick={() => triggerPhoneAction('media_prev', '', 'Previous Track')}
            className="p-3.5 rounded-full bg-slate-800/80 hover:bg-slate-700 text-slate-200 transition-all active:scale-90 border border-slate-700/60 shadow-sm"
            title="Previous Track"
          >
            <SkipBack size={18} />
          </button>
          <button
            onClick={() => triggerPhoneAction('media_playpause', '', 'Play / Pause')}
            className="p-4 rounded-full bg-gradient-to-r from-cyan-400 to-blue-500 hover:from-cyan-300 hover:to-blue-400 text-slate-950 font-bold shadow-lg shadow-cyan-500/30 transition-all active:scale-95"
            title="Play / Pause"
          >
            <Play size={20} className="ml-0.5 fill-slate-950" />
          </button>
          <button
            onClick={() => triggerPhoneAction('media_next', '', 'Next Track')}
            className="p-3.5 rounded-full bg-slate-800/80 hover:bg-slate-700 text-slate-200 transition-all active:scale-90 border border-slate-700/60 shadow-sm"
            title="Next Track"
          >
            <SkipForward size={18} />
          </button>
        </div>
      </div>

      {/* Bidirectional Clipboard Sync */}
      <div className="glass-panel p-5 rounded-2xl">
        <div className="flex items-center gap-3 mb-2">
          <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400 border border-cyan-500/25 shadow-sm shadow-cyan-500/20">
            <Clipboard size={18} />
          </div>
          <div>
            <h3 className="font-bold text-sm text-slate-100">Synchronize Clipboard to Mobile</h3>
            <p className="text-[11px] text-slate-400">
              {phoneIp ? `Target: ${connectedClient.name}` : 'Connect your phone to sync clipboards'}
            </p>
          </div>
        </div>

        <div className="flex gap-2.5 mt-3.5">
          <input
            type="text"
            placeholder="Type or paste text to inject into phone clipboard..."
            value={clipboardText}
            onChange={(e) => setClipboardText(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSendClipboard()}
            className="flex-1 bg-slate-950/80 border border-slate-800 rounded-xl px-3.5 py-2.5 text-xs text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500/60 transition-all shadow-inner"
          />
          <button
            onClick={handleSendClipboard}
            disabled={!clipboardText.trim()}
            className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-cyan-400 to-blue-500 hover:from-cyan-300 hover:to-blue-400 disabled:opacity-40 text-slate-950 font-bold text-xs flex items-center gap-2 transition-all active:scale-95 shadow-md shadow-cyan-500/20"
          >
            <Send size={13} />
            <span>Sync</span>
          </button>
        </div>
      </div>
    </div>
  );
};
