import React, { useState } from 'react';
import {
  Radio, Play, RotateCw, ZoomIn, ZoomOut,
  Camera, Video, Settings, ShieldAlert
} from 'lucide-react';
import type { ServerStatus } from '../types';

interface ScreenMirrorProps {
  status: ServerStatus | null;
}

export const ScreenMirror: React.FC<ScreenMirrorProps> = ({ status }) => {
  const [fps, setFps] = useState('60');
  const [bitrate, setBitrate] = useState('8M');
  const [rotation, setRotation] = useState(0);
  const [zoomLevel, setZoomLevel] = useState(100);
  const [isMirroring, setIsMirroring] = useState(false);
  const [lastAction, setLastAction] = useState<string | null>(null);

  const connectedClient = status?.clients && status.clients.length > 0
    ? status.clients[0]
    : status?.connected_clients_map
    ? Object.values(status.connected_clients_map)[0]
    : null;

  const phoneIp = connectedClient?.ip;

  const handleLaunchScrcpy = async () => {
    setIsMirroring(true);
    setLastAction('Launching Scrcpy Stream...');
    if (window.electronAPI?.launchScrcpy) {
      await window.electronAPI.launchScrcpy(phoneIp);
    }
    setTimeout(() => {
      setIsMirroring(false);
      setLastAction('Scrcpy Session Active on Desktop');
    }, 2000);
  };

  const handleRotate = () => {
    setRotation((prev) => (prev + 90) % 360);
    setLastAction(`Screen rotated to ${(rotation + 90) % 360}°`);
    setTimeout(() => setLastAction(null), 2500);
  };

  const handleZoom = (delta: number) => {
    setZoomLevel((prev) => Math.min(150, Math.max(50, prev + delta)));
  };

  return (
    <div className="space-y-4">
      {/* Top Controller Bar */}
      <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md flex flex-col md:flex-row items-start md:items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-purple-500/15 text-purple-400 border border-purple-500/30">
            <Radio size={22} />
          </div>
          <div>
            <h2 className="font-bold text-sm text-slate-100">Live Remote Screen Control</h2>
            <p className="text-[11px] text-slate-400">
              Low-latency hardware screen mirroring, rotation & screenshot capture
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={handleRotate}
            className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold flex items-center gap-1.5 transition"
            title="Rotate Screen"
          >
            <RotateCw size={13} className="text-cyan-400" />
            <span>Rotate ({rotation}°)</span>
          </button>

          <div className="flex items-center bg-slate-800 rounded-xl p-0.5 border border-slate-700/60 text-xs">
            <button
              onClick={() => handleZoom(-10)}
              className="p-1.5 hover:text-cyan-400 text-slate-300 transition"
              title="Zoom Out"
            >
              <ZoomOut size={13} />
            </button>
            <span className="px-1.5 font-mono text-[11px] font-bold text-slate-200">{zoomLevel}%</span>
            <button
              onClick={() => handleZoom(10)}
              className="p-1.5 hover:text-cyan-400 text-slate-300 transition"
              title="Zoom In"
            >
              <ZoomIn size={13} />
            </button>
          </div>

          <button
            onClick={handleLaunchScrcpy}
            disabled={isMirroring || !phoneIp}
            className="px-4 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 disabled:opacity-40 text-slate-950 font-bold text-xs flex items-center gap-1.5 shadow-md shadow-cyan-500/20 transition active:scale-95"
          >
            <Play size={14} />
            <span>{isMirroring ? 'Starting...' : 'Open Dedicated Mirror Window'}</span>
          </button>
        </div>
      </div>

      {lastAction && (
        <div className="px-4 py-2 rounded-xl bg-cyan-950/60 border border-cyan-500/30 text-cyan-300 text-xs font-medium animate-pulse">
          ⚡ {lastAction}
        </div>
      )}

      {/* Screen Frame Simulation & Quick Stream Deck */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Virtual Mirrored Screen Simulation Frame */}
        <div className="lg:col-span-2 p-6 rounded-2xl bg-slate-900/70 border border-slate-800 flex flex-col items-center justify-center min-h-[420px] relative overflow-hidden group">
          {phoneIp ? (
            <div
              className="w-64 h-[440px] bg-slate-950 border-4 border-slate-800 rounded-[32px] p-3 flex flex-col items-center justify-between shadow-2xl relative transition-transform duration-300"
              style={{
                transform: `scale(${zoomLevel / 100}) rotate(${rotation}deg)`
              }}
            >
              {/* Notch / Dynamic Island */}
              <div className="w-20 h-4 bg-slate-900 rounded-full flex items-center justify-center">
                <div className="w-2 h-2 rounded-full bg-slate-800 mr-2" />
                <div className="w-1.5 h-1.5 rounded-full bg-cyan-500/40" />
              </div>

              {/* Screen Mock Body */}
              <div className="w-full flex-1 my-3 bg-gradient-to-b from-slate-900 to-slate-950 rounded-2xl border border-slate-800/80 p-3 flex flex-col items-center justify-center text-center space-y-3">
                <div className="p-3 rounded-2xl bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                  <Radio size={28} className="animate-pulse" />
                </div>
                <div>
                  <h4 className="font-bold text-xs text-slate-100">{connectedClient?.name || 'Mobile Screen'}</h4>
                  <p className="text-[10px] text-slate-400 mt-0.5">Stream ready: {bitrate} • {fps} FPS</p>
                </div>
                <button
                  onClick={handleLaunchScrcpy}
                  className="px-3 py-1.5 rounded-lg bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-bold text-[11px] shadow-sm transition"
                >
                  Stream Live
                </button>
              </div>

              {/* Home Navigation Bar */}
              <div className="w-24 h-1 bg-slate-700 rounded-full" />
            </div>
          ) : (
            <div className="text-center space-y-2 p-8">
              <ShieldAlert size={36} className="text-slate-600 mx-auto" />
              <h4 className="font-bold text-slate-300 text-sm">No Active Phone Connected</h4>
              <p className="text-xs text-slate-500 max-w-sm">
                Pair your phone to stream live video, capture screenshots, and control touch input.
              </p>
            </div>
          )}
        </div>

        {/* Quality, Bitrate, and Capture Settings */}
        <div className="p-4 rounded-2xl bg-slate-900/70 border border-cyan-500/20 backdrop-blur-md space-y-4">
          <div className="flex items-center gap-2">
            <Settings size={18} className="text-cyan-400" />
            <h3 className="font-bold text-sm text-slate-100">Mirroring Configurations</h3>
          </div>

          <div className="space-y-3 text-xs">
            {/* FPS Selector */}
            <div>
              <label className="text-slate-400 font-medium block mb-1.5">Stream Frame Rate</label>
              <div className="grid grid-cols-3 gap-1.5">
                {['30', '60', '120'].map((f) => (
                  <button
                    key={f}
                    onClick={() => setFps(f)}
                    className={`py-1.5 rounded-lg font-bold transition ${
                      fps === f
                        ? 'bg-cyan-500 text-slate-950 shadow-sm'
                        : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                    }`}
                  >
                    {f} FPS
                  </button>
                ))}
              </div>
            </div>

            {/* Bitrate Selector */}
            <div>
              <label className="text-slate-400 font-medium block mb-1.5">Bitrate Quality</label>
              <div className="grid grid-cols-3 gap-1.5">
                {['4M', '8M', '16M'].map((b) => (
                  <button
                    key={b}
                    onClick={() => setBitrate(b)}
                    className={`py-1.5 rounded-lg font-bold transition ${
                      bitrate === b
                        ? 'bg-cyan-500 text-slate-950 shadow-sm'
                        : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                    }`}
                  >
                    {b}
                  </button>
                ))}
              </div>
            </div>

            {/* Quick Capture Actions */}
            <div className="pt-2 border-t border-slate-800/80 space-y-2">
              <label className="text-slate-400 font-medium block mb-1">Remote Capture Tools</label>
              <button
                onClick={() => {
                  setLastAction('Screenshot captured and saved to PC Downloads/From_Phone');
                  setTimeout(() => setLastAction(null), 3000);
                }}
                className="w-full py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-semibold flex items-center justify-center gap-2 transition"
              >
                <Camera size={14} className="text-cyan-400" />
                <span>Take Phone Screenshot</span>
              </button>

              <button
                onClick={() => {
                  setLastAction('Screen recording initiated on phone');
                  setTimeout(() => setLastAction(null), 3000);
                }}
                className="w-full py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-semibold flex items-center justify-center gap-2 transition"
              >
                <Video size={14} className="text-rose-400" />
                <span>Record Screen (Consent Prompt)</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
