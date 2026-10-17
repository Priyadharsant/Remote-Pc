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
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-cyan-500/15 text-cyan-400">
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
          <div className="px-3 py-1.5 rounded-xl bg-cyan-950/80 border border-cyan-500/40 text-cyan-300 text-xs font-semibold animate-pulse">
            {actionFeedback}
          </div>
        )}
      </div>

      {/* Grid: Audio & Brightness Sliders */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Phone Media Volume */}
        <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <div className="p-2 rounded-xl bg-cyan-500/15 text-cyan-400">
                <Volume2 size={18} />
              </div>
              <div>
                <h3 className="font-bold text-sm text-slate-100">Phone Volume Level</h3>
                <p className="text-[11px] text-slate-400">Speaker, media & ringer level</p>
              </div>
            </div>
            <button
              onClick={() => triggerPhoneAction('mute', '', 'Mute Audio')}
              className="px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-semibold transition"
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
              className="flex-1 h-2 bg-slate-950 rounded-lg appearance-none cursor-pointer accent-cyan-400"
            />
            <span className="w-10 text-right font-mono font-bold text-cyan-300 text-xs">
              {phoneVolume}%
            </span>
          </div>
        </div>

        {/* Phone Brightness */}
        <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <div className="p-2 rounded-xl bg-amber-500/15 text-amber-400">
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
              className="flex-1 h-2 bg-slate-950 rounded-lg appearance-none cursor-pointer accent-amber-400"
            />
            <span className="w-10 text-right font-mono font-bold text-amber-300 text-xs">
              {phoneBrightness}%
            </span>
          </div>
        </div>
      </div>

      {/* Touch & Navigation Keys Deck */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Android System Navigation & Gestures</h3>
        <p className="text-[11px] text-slate-400 mb-3">Simulate hardware navigation bar buttons and gestures</p>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
          <button
            onClick={() => triggerPhoneAction('nav_back', '', 'Back Button')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex flex-col items-center justify-center gap-1.5 text-slate-300 hover:text-cyan-300 transition group"
          >
            <ArrowLeft size={18} className="text-cyan-400 group-hover:-translate-x-1 transition" />
            <span className="text-xs font-semibold">Back</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('nav_home', '', 'Home Screen')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex flex-col items-center justify-center gap-1.5 text-slate-300 hover:text-blue-300 transition group"
          >
            <Home size={18} className="text-blue-400 group-hover:scale-110 transition" />
            <span className="text-xs font-semibold">Home</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('nav_recents', '', 'Recent Apps')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex flex-col items-center justify-center gap-1.5 text-slate-300 hover:text-purple-300 transition group"
          >
            <Layers size={18} className="text-purple-400 group-hover:scale-110 transition" />
            <span className="text-xs font-semibold">Recent Apps</span>
          </button>

          <button
            onClick={toggleFlashlight}
            className={`p-3 rounded-xl border flex flex-col items-center justify-center gap-1.5 transition ${
              isFlashlightOn
                ? 'bg-amber-500/20 border-amber-500/40 text-amber-300'
                : 'bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 text-slate-300 hover:text-amber-300'
            }`}
          >
            <Flashlight size={18} className={isFlashlightOn ? 'text-amber-400 animate-pulse' : 'text-slate-400'} />
            <span className="text-xs font-semibold">Flashlight</span>
          </button>
        </div>
      </div>

      {/* Calls, Messaging & Apps Shortcuts */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Calls, Messaging & System Screens</h3>
        <p className="text-[11px] text-slate-400 mb-3">Quick launch system dialer, SMS, contacts, and settings</p>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
          <button
            onClick={() => triggerPhoneAction('open_dialer', '', 'Phone Dialer')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex items-center gap-2.5 text-slate-300 hover:text-emerald-300 transition"
          >
            <PhoneCall size={16} className="text-emerald-400" />
            <span className="text-xs font-medium">Open Dialer</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('open_sms', '', 'Messages App')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex items-center gap-2.5 text-slate-300 hover:text-blue-300 transition"
          >
            <MessageSquare size={16} className="text-blue-400" />
            <span className="text-xs font-medium">Open SMS</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('lock_screen', '', 'Lock Mobile Screen')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex items-center gap-2.5 text-slate-300 hover:text-amber-300 transition"
          >
            <Lock size={16} className="text-amber-400" />
            <span className="text-xs font-medium">Lock Screen</span>
          </button>

          <button
            onClick={() => triggerPhoneAction('screen_off', '', 'Screen Off')}
            className="p-3 rounded-xl bg-slate-950/70 hover:bg-slate-800/80 border border-slate-800 flex items-center gap-2.5 text-slate-300 hover:text-rose-400 transition"
          >
            <Moon size={16} className="text-rose-400" />
            <span className="text-xs font-medium">Turn Screen Off</span>
          </button>
        </div>
      </div>

      {/* Media Playback Deck */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <h3 className="font-bold text-sm text-slate-100 mb-1">Phone Media Playback Deck</h3>
        <p className="text-[11px] text-slate-400 mb-3">Control Spotify, YouTube, or Music player running on phone</p>

        <div className="flex items-center justify-center gap-4 py-2">
          <button
            onClick={() => triggerPhoneAction('media_prev', '', 'Previous Track')}
            className="p-3 rounded-full bg-slate-800 hover:bg-slate-700 text-slate-200 transition active:scale-95"
            title="Previous Track"
          >
            <SkipBack size={18} />
          </button>
          <button
            onClick={() => triggerPhoneAction('media_playpause', '', 'Play / Pause')}
            className="p-4 rounded-full bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-slate-950 font-bold shadow-lg shadow-cyan-500/25 transition active:scale-95"
            title="Play / Pause"
          >
            <Play size={20} className="ml-0.5" />
          </button>
          <button
            onClick={() => triggerPhoneAction('media_next', '', 'Next Track')}
            className="p-3 rounded-full bg-slate-800 hover:bg-slate-700 text-slate-200 transition active:scale-95"
            title="Next Track"
          >
            <SkipForward size={18} />
          </button>
        </div>
      </div>

      {/* Bidirectional Clipboard Sync */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md">
        <div className="flex items-center gap-2 mb-2">
          <div className="p-2 rounded-xl bg-cyan-500/15 text-cyan-400">
            <Clipboard size={18} />
          </div>
          <div>
            <h3 className="font-bold text-sm text-slate-100">Synchronize Clipboard to Mobile</h3>
            <p className="text-[11px] text-slate-400">
              {phoneIp ? `Target: ${connectedClient.name}` : 'Connect your phone to sync clipboards'}
            </p>
          </div>
        </div>

        <div className="flex gap-2 mt-3">
          <input
            type="text"
            placeholder="Type or paste text to inject into phone clipboard..."
            value={clipboardText}
            onChange={(e) => setClipboardText(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSendClipboard()}
            className="flex-1 bg-slate-950/70 border border-slate-800 rounded-xl px-3 py-2 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500/50"
          />
          <button
            onClick={handleSendClipboard}
            disabled={!clipboardText.trim()}
            className="px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 disabled:opacity-40 text-slate-950 font-bold text-xs flex items-center gap-1.5 transition active:scale-95 shadow-md shadow-cyan-500/20"
          >
            <Send size={13} />
            <span>Sync</span>
          </button>
        </div>
      </div>
    </div>
  );
};
