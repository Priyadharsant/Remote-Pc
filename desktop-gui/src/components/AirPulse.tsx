import React, { useState, useEffect, useRef } from 'react';
import QRCode from 'qrcode';
import {
  Radio, HardDriveDownload, Send, Smartphone, Laptop, Terminal, Copy, Check,
  RefreshCw, FolderOpen, X, Zap,
  CheckCircle2, Plus, Trash2, Lock
} from 'lucide-react';
import { api } from '../api';
import type {
  ServerStatus, AirPulsePeer, AirPulseStatus, AirPulseIncomingRequest, AirPulseTransferProgress
} from '../types';

interface AirPulseProps {
  status?: ServerStatus | null;
}

export const AirPulse: React.FC<AirPulseProps> = () => {
  // Mode: 'send' or 'receive' (Option A - Dedicated Receive Radar)
  const [mode, setMode] = useState<'send' | 'receive'>('send');

  // Receive Mode State
  const [receiveStatus, setReceiveStatus] = useState<AirPulseStatus | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState<string>('');
  const [copiedPin, setCopiedPin] = useState(false);
  const [incomingReq, setIncomingReq] = useState<AirPulseIncomingRequest | null>(null);
  const [isAccepting, setIsAccepting] = useState(false);

  // Sender Mode State
  const [selectedFiles, setSelectedFiles] = useState<Array<{ name: string; size: number; path?: string; fileObj?: File }>>([]);
  const [peers, setPeers] = useState<AirPulsePeer[]>([]);
  const [isScanningPeers, setIsScanningPeers] = useState(false);
  const [activeTransfer, setActiveTransfer] = useState<AirPulseTransferProgress | null>(null);

  // Manual PIN & IP Modal
  const [pinModalOpen, setPinModalOpen] = useState(false);
  const [targetIpInput, setTargetIpInput] = useState('');
  const [pinInput, setPinInput] = useState('');
  const [pinTargetPeer, setPinTargetPeer] = useState<AirPulsePeer | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const transferPollRef = useRef<any>(null);

  // --- Initial Load & Receive Mode Sync ---
  const loadReceiveStatus = async () => {
    try {
      const data = await api.getAirPulseStatus();
      setReceiveStatus(data);
      if (data.pin) {
        generateQr(data);
      }
    } catch {
      // ignore
    }
  };

  const generateQr = async (st: AirPulseStatus) => {
    try {
      const payload = JSON.stringify({
        airpulse: true,
        dev: st.device_name,
        ip: st.ip,
        port: st.port,
        pin: st.pin,
        os: st.os
      });
      const url = await QRCode.toDataURL(payload, {
        margin: 1.5,
        width: 240,
        color: {
          dark: '#020617',
          light: '#FFFFFF'
        }
      });
      setQrDataUrl(url);
    } catch {
      // ignore
    }
  };

  useEffect(() => {
    loadReceiveStatus();
  }, []);

  // When switching to receive mode, automatically activate receiver mode on server
  useEffect(() => {
    if (mode === 'receive') {
      api.toggleAirPulseReceiveMode(true).then((res) => {
        setReceiveStatus(res);
        generateQr(res);
      });
    } else {
      // Deactivate receive mode when switching to send to save discovery resources
      api.toggleAirPulseReceiveMode(false).then((res) => {
        setReceiveStatus(res);
      });
    }
  }, [mode]);

  // --- Incoming Request Listener (Polls while in receive mode) ---
  useEffect(() => {
    if (mode !== 'receive') return;

    const checkIncoming = async () => {
      try {
        const res = await api.getAirPulsePendingIncoming();
        if (res.has_incoming && res.request) {
          setIncomingReq(res.request);
        } else {
          setIncomingReq(null);
        }
      } catch {
        // ignore
      }
    };

    checkIncoming();
    const interval = setInterval(checkIncoming, 1500);
    return () => clearInterval(interval);
  }, [mode]);

  // --- Scan Peers (Sender Mode) ---
  const scanPeers = async () => {
    setIsScanningPeers(true);
    try {
      const pList = await api.getAirPulsePeers();
      setPeers(pList);
    } catch {
      // ignore
    } finally {
      setIsScanningPeers(false);
    }
  };

  useEffect(() => {
    if (mode === 'send') {
      scanPeers();
      const interval = setInterval(scanPeers, 4000);
      return () => clearInterval(interval);
    }
  }, [mode]);

  // --- Copy PIN to Clipboard ---
  const copyPin = () => {
    if (!receiveStatus?.pin) return;
    navigator.clipboard.writeText(receiveStatus.pin);
    setCopiedPin(true);
    setTimeout(() => setCopiedPin(false), 2000);
  };

  // --- Open Destination Folder ---
  const openSaveDirectory = () => {
    if (receiveStatus?.save_dir && window.electronAPI?.openExternalFolder) {
      window.electronAPI.openExternalFolder(receiveStatus.save_dir);
    }
  };

  // --- Handle Accept Incoming Transfer ---
  const handleAcceptIncoming = async () => {
    if (!incomingReq) return;
    setIsAccepting(true);
    try {
      const res = await api.respondToAirPulseRequest(incomingReq.id, 'accept');
      if (res.transfer_id) {
        monitorTransfer(res.transfer_id, {
          id: res.transfer_id,
          direction: `${incomingReq.sender_name} ➔ This PC`,
          transferred_bytes: 0,
          total_bytes: incomingReq.total_size,
          current_file: incomingReq.files[0]?.name || 'file',
          file_index: 1,
          total_files: incomingReq.total_count,
          speed_mb: 0,
          percent: 0,
          status: 'in_progress',
          message: 'Receiving files...'
        });
      }
    } catch {
      // ignore
    } finally {
      setIsAccepting(false);
      setIncomingReq(null);
    }
  };

  // --- Handle Decline Incoming Transfer ---
  const handleDeclineIncoming = async () => {
    if (!incomingReq) return;
    try {
      await api.respondToAirPulseRequest(incomingReq.id, 'decline');
    } catch {
      // ignore
    } finally {
      setIncomingReq(null);
    }
  };

  // --- File Selection Handlers ---
  const handleFilesAdded = (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return;
    const added: Array<{ name: string; size: number; path?: string; fileObj?: File }> = [];

    for (let i = 0; i < fileList.length; i++) {
      const f = fileList[i];
      let filePath = (f as any).path || '';
      if (!filePath && window.electronAPI?.getPathForFile) {
        try {
          filePath = window.electronAPI.getPathForFile(f);
        } catch {
          // ignore
        }
      }
      added.push({
        name: f.name,
        size: f.size,
        path: filePath,
        fileObj: f
      });
    }

    setSelectedFiles((prev) => [...prev, ...added]);
  };

  const removeFile = (idx: number) => {
    setSelectedFiles((prev) => prev.filter((_, i) => i !== idx));
  };

  const clearFiles = () => {
    setSelectedFiles([]);
  };

  // --- Format Bytes ---
  const formatSize = (bytes: number) => {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
  };

  const totalSelectedBytes = selectedFiles.reduce((acc, f) => acc + f.size, 0);

  // --- Active Transfer Monitor ---
  const monitorTransfer = (transferId: string, initial: AirPulseTransferProgress) => {
    setActiveTransfer(initial);
    if (transferPollRef.current) clearInterval(transferPollRef.current);

    transferPollRef.current = setInterval(async () => {
      try {
        const prog = await api.getAirPulseTransferStatus(transferId);
        if (!prog || prog.status === 'not_found') return;
        setActiveTransfer(prog);

        if (['completed', 'error', 'declined', 'cancelled'].includes(prog.status)) {
          clearInterval(transferPollRef.current);
          transferPollRef.current = null;
          if (prog.status === 'completed') {
            setTimeout(() => {
              setActiveTransfer(null);
              setSelectedFiles([]);
            }, 3000);
          }
        }
      } catch {
        // ignore
      }
    }, 300);
  };

  // --- Send Files to Target Peer ---
  const initiateSendToPeer = async (peer: AirPulsePeer, pinCode: string = '') => {
    if (selectedFiles.length === 0) {
      alert('Please select one or more files to send.');
      return;
    }

    const filePaths = selectedFiles.map((f) => f.path || f.name);

    try {
      const res = await api.sendAirPulseFiles({
        target_ip: peer.ip,
        target_port: peer.port || 5055,
        pin: pinCode || peer.pin || '',
        file_paths: filePaths
      });

      if (res.transfer_id) {
        monitorTransfer(res.transfer_id, {
          id: res.transfer_id,
          direction: `This PC ➔ ${peer.name}`,
          transferred_bytes: 0,
          total_bytes: totalSelectedBytes,
          current_file: selectedFiles[0]?.name || 'file',
          file_index: 1,
          total_files: selectedFiles.length,
          speed_mb: 0,
          percent: 0,
          status: 'requesting',
          message: `Requesting transfer with ${peer.name}...`
        });
      }
    } catch (err: any) {
      alert(`Send failed: ${err.message || err}`);
    }
  };

  const handleManualPinSend = () => {
    if (!pinTargetPeer && !targetIpInput) {
      alert('Please enter receiver IP or select a device');
      return;
    }
    const targetPeer: AirPulsePeer = pinTargetPeer || {
      name: `Device (${targetIpInput})`,
      ip: targetIpInput,
      port: 5055,
      os: 'unknown',
      receive_mode: true
    };
    setPinModalOpen(false);
    initiateSendToPeer(targetPeer, pinInput.trim());
  };

  return (
    <div className="space-y-5 animate-fade-in pb-10">
      {/* Top Header & Mode Switcher */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-5 rounded-3xl bg-gradient-to-r from-slate-900/90 via-slate-900/70 to-slate-950/90 border border-slate-800/80 backdrop-blur-xl shadow-2xl shadow-black/50">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-cyan-500/20 via-emerald-500/15 to-transparent border border-cyan-500/40 flex items-center justify-center text-cyan-400 shadow-[0_0_15px_rgba(6,182,212,0.15)] shrink-0">
            <Radio size={24} className="text-cyan-400 animate-pulse" />
          </div>
          <div>
            <div className="flex items-center gap-2.5">
              <h2 className="text-lg font-black text-slate-100 tracking-tight">AirPulse Drop</h2>
              <span className="px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 flex items-center gap-1.5 shadow-sm">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 shadow-[0_0_6px_#10B981] animate-ping"></span>
                Cross-Device Beam
              </span>
            </div>
            <p className="text-xs text-slate-400 font-medium mt-0.5">
              Zero-setup, ultra-fast peer-to-peer file transfer between Android, Windows & Ubuntu
            </p>
          </div>
        </div>

        {/* Fluid Mode Toggle Pills */}
        <div className="flex items-center p-1 rounded-2xl bg-slate-950/80 border border-slate-800/90 self-start md:self-auto shadow-inner">
          <button
            onClick={() => setMode('send')}
            className={`px-5 py-2 rounded-xl text-xs font-extrabold flex items-center gap-2 transition-all duration-200 active:scale-95 ${
              mode === 'send'
                ? 'bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 shadow-md shadow-cyan-500/20'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/50'
            }`}
          >
            <Send size={14} />
            <span>Send Files</span>
          </button>
          <button
            onClick={() => setMode('receive')}
            className={`px-5 py-2 rounded-xl text-xs font-extrabold flex items-center gap-2 transition-all duration-200 active:scale-95 ${
              mode === 'receive'
                ? 'bg-gradient-to-r from-emerald-500 to-teal-400 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900/50'
            }`}
          >
            <HardDriveDownload size={14} />
            <span>Receive Radar</span>
          </button>
        </div>
      </div>

      {/* ===================================================================== */}
      {/* RECEIVER MODE: OPTION A (Animated Sonar Radar, PIN, QR Code)           */}
      {/* ===================================================================== */}
      {mode === 'receive' && (
        <div className="space-y-5 animate-fade-in">
          {/* Main Sonar Radar & PIN Stage */}
          <div className="relative overflow-hidden rounded-3xl bg-gradient-to-b from-slate-900/90 via-slate-950/90 to-slate-950 border border-emerald-500/30 p-8 shadow-2xl shadow-emerald-500/10">
            {/* Subtle background sonar rings */}
            <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 pointer-events-none -z-0">
              <div className="w-[320px] h-[320px] rounded-full border border-emerald-500/15 animate-ping opacity-30"></div>
              <div className="w-[480px] h-[480px] rounded-full border border-teal-500/10 -translate-x-20 -translate-y-20"></div>
            </div>

            <div className="relative z-10 grid grid-cols-1 lg:grid-cols-12 gap-8 items-center">
              {/* Left Column: Live Radar Visual & PIN */}
              <div className="lg:col-span-7 space-y-6 text-center lg:text-left">
                <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-xs font-bold">
                  <span className="w-2 h-2 rounded-full bg-emerald-400 shadow-[0_0_8px_#10B981] animate-pulse"></span>
                  Radar Listening for Incoming Transfers
                </div>

                <div>
                  <h3 className="text-2xl font-black text-slate-100 tracking-tight">
                    Receiver Mode Active
                  </h3>
                  <p className="text-sm text-slate-400 mt-1 max-w-md">
                    Nearby phones and computers can detect this PC automatically. Or enter the 6-digit PIN below for direct instant pairing.
                  </p>
                </div>

                {/* 6-Digit PIN Display Card */}
                <div className="p-5 rounded-2xl bg-slate-950/90 border border-slate-800/90 shadow-inner max-w-sm">
                  <span className="text-[11px] font-black text-slate-400 uppercase tracking-widest block mb-1">
                    AirPulse Pairing PIN
                  </span>
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-mono text-3xl font-black tracking-widest text-emerald-400 drop-shadow-[0_0_12px_rgba(16,185,129,0.3)]">
                      {receiveStatus?.pin ? `${receiveStatus.pin.slice(0, 3)} ${receiveStatus.pin.slice(3)}` : '------'}
                    </span>
                    <button
                      onClick={copyPin}
                      className="p-2.5 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 hover:text-emerald-400 transition active:scale-95 flex items-center gap-1.5 text-xs font-bold"
                      title="Copy PIN"
                    >
                      {copiedPin ? <Check size={16} className="text-emerald-400" /> : <Copy size={16} />}
                      <span>{copiedPin ? 'Copied' : 'Copy'}</span>
                    </button>
                  </div>
                </div>

                {/* Local Network Info & Destination folder */}
                <div className="flex flex-wrap items-center gap-3 pt-2 text-xs">
                  <span className="px-3 py-1.5 rounded-xl bg-slate-900/90 border border-slate-800 text-slate-300 font-mono flex items-center gap-2">
                    <span className="text-slate-500">Host:</span>
                    <span className="font-bold text-cyan-300">{receiveStatus?.ip || '127.0.0.1'}:{receiveStatus?.port || 5055}</span>
                  </span>
                  <button
                    onClick={openSaveDirectory}
                    className="px-3.5 py-1.5 rounded-xl bg-slate-900/90 hover:bg-slate-800 border border-slate-800 text-slate-300 hover:text-cyan-300 text-xs font-bold flex items-center gap-2 transition active:scale-95"
                    title="Open destination folder on PC"
                  >
                    <FolderOpen size={14} className="text-amber-400" />
                    <span>Downloads/RemotePC_Transfers</span>
                  </button>
                </div>
              </div>

              {/* Right Column: Dynamic QR Code */}
              <div className="lg:col-span-5 flex flex-col items-center justify-center">
                <div className="p-4 rounded-3xl bg-white shadow-2xl shadow-cyan-500/10 border-2 border-emerald-500/40 relative group">
                  {qrDataUrl ? (
                    <img src={qrDataUrl} alt="AirPulse QR Code" className="w-52 h-52 object-contain" />
                  ) : (
                    <div className="w-52 h-52 flex items-center justify-center bg-slate-100 text-slate-500 text-xs font-bold">
                      Generating QR...
                    </div>
                  )}
                  <div className="absolute -bottom-3 left-1/2 -translate-x-1/2 px-3 py-1 rounded-full bg-slate-950 border border-slate-800 text-[10px] font-black uppercase tracking-wider text-emerald-400 whitespace-nowrap shadow-md">
                    Scan with Mobile
                  </div>
                </div>
                <p className="text-xs text-slate-400 font-medium text-center mt-5">
                  Point phone camera at this QR code to beam files instantly
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* SENDER MODE: Multi-file picker & Orbital Device Radar                  */}
      {/* ===================================================================== */}
      {mode === 'send' && (
        <div className="space-y-5 animate-fade-in">
          {/* File Selection Dropzone */}
          <div
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); }}
            onDrop={(e) => {
              e.preventDefault();
              e.stopPropagation();
              handleFilesAdded(e.dataTransfer.files);
            }}
            className="p-8 rounded-3xl bg-gradient-to-b from-slate-900/80 to-slate-950/80 border-2 border-dashed border-slate-800 hover:border-cyan-500/50 hover:bg-slate-900/50 transition-all duration-200 cursor-pointer text-center group relative overflow-hidden"
          >
            <input
              type="file"
              multiple
              ref={fileInputRef}
              onChange={(e) => handleFilesAdded(e.target.files)}
              className="hidden"
            />
            <div className="w-14 h-14 rounded-2xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center mx-auto text-cyan-400 group-hover:scale-110 transition-transform shadow-lg shadow-cyan-500/10">
              <Plus size={28} />
            </div>
            <h4 className="text-base font-black text-slate-100 mt-4 group-hover:text-cyan-300 transition">
              {selectedFiles.length > 0 ? 'Add More Files' : 'Select or Drop Files to Beam'}
            </h4>
            <p className="text-xs text-slate-400 max-w-md mx-auto mt-1">
              Supports photos, 4K videos, documents, APKs, EXEs, and folders with zero compression
            </p>
          </div>

          {/* Selected Files Queue Preview */}
          {selectedFiles.length > 0 && (
            <div className="p-4 rounded-3xl bg-slate-900/80 border border-slate-800/80 space-y-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-black text-slate-200 uppercase tracking-wider">
                    Selected Files ({selectedFiles.length})
                  </span>
                  <span className="text-xs font-bold text-cyan-300">
                    • {formatSize(totalSelectedBytes)} Total
                  </span>
                </div>
                <button
                  onClick={clearFiles}
                  className="text-xs font-bold text-rose-400 hover:text-rose-300 flex items-center gap-1 transition"
                >
                  <Trash2 size={13} />
                  <span>Clear All</span>
                </button>
              </div>

              {/* Files Pills List */}
              <div className="flex flex-wrap gap-2 max-h-40 overflow-y-auto pr-1">
                {selectedFiles.map((f, idx) => (
                  <div
                    key={`${f.name}_${idx}`}
                    className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-slate-950/80 border border-slate-800 text-xs font-semibold text-slate-200"
                  >
                    <span className="truncate max-w-[180px]">{f.name}</span>
                    <span className="text-[11px] text-slate-400 font-mono">({formatSize(f.size)})</span>
                    <button
                      onClick={(e) => { e.stopPropagation(); removeFile(idx); }}
                      className="text-slate-500 hover:text-rose-400 ml-1"
                    >
                      <X size={13} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Nearby Devices Section (Orbital Radar) */}
          <div className="p-6 rounded-3xl bg-gradient-to-b from-slate-900/90 to-slate-950/90 border border-slate-800/80 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
                  <Radio size={16} className={isScanningPeers ? 'animate-spin' : ''} />
                </div>
                <div>
                  <h4 className="text-sm font-black text-slate-100 uppercase tracking-wider">
                    Nearby AirPulse Devices
                  </h4>
                  <p className="text-[11px] text-slate-400">
                    Auto-discovering devices on local Wi-Fi / Hotspot
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={() => { setPinTargetPeer(null); setPinModalOpen(true); }}
                  className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-cyan-300 text-xs font-bold flex items-center gap-1.5 transition active:scale-95"
                >
                  <Lock size={12} />
                  <span>Enter 6-Digit PIN</span>
                </button>
                <button
                  onClick={scanPeers}
                  disabled={isScanningPeers}
                  className="p-2 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 hover:text-cyan-300 transition"
                  title="Refresh Nearby Devices"
                >
                  <RefreshCw size={14} className={isScanningPeers ? 'animate-spin' : ''} />
                </button>
              </div>
            </div>

            {/* Peers Grid */}
            {peers.length === 0 ? (
              <div className="p-10 rounded-2xl bg-slate-950/60 border border-slate-800/80 text-center space-y-3">
                <div className="w-12 h-12 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-center mx-auto text-slate-500">
                  <Radio size={22} className="animate-pulse" />
                </div>
                <h5 className="font-bold text-sm text-slate-300">Searching for Nearby Devices...</h5>
                <p className="text-xs text-slate-500 max-w-sm mx-auto">
                  Ensure the target phone or computer is connected to the same Wi-Fi and has Remote PC open or in Receiver Mode.
                </p>
                <button
                  onClick={() => { setPinTargetPeer(null); setPinModalOpen(true); }}
                  className="px-4 py-2 rounded-xl bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 text-xs font-bold inline-flex items-center gap-1.5 transition active:scale-95 mt-2"
                >
                  <Zap size={13} />
                  <span>Connect with 6-Digit PIN</span>
                </button>
              </div>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3.5">
                {peers.map((peer) => {
                  const isAndroid = peer.os === 'android';
                  const isWin = peer.os === 'windows' || peer.os === 'win32';
                  return (
                    <div
                      key={peer.ip}
                      className="p-4 rounded-2xl bg-slate-950/80 border border-slate-800/90 hover:border-cyan-500/40 hover:shadow-lg hover:shadow-cyan-500/10 transition-all flex flex-col justify-between group"
                    >
                      <div className="flex items-start gap-3">
                        <div className="w-10 h-10 rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center shrink-0">
                          {isAndroid ? (
                            <Smartphone size={20} className="text-emerald-400" />
                          ) : isWin ? (
                            <Laptop size={20} className="text-cyan-400" />
                          ) : (
                            <Terminal size={20} className="text-orange-400" />
                          )}
                        </div>
                        <div className="min-w-0 flex-1">
                          <h5 className="text-xs font-bold text-slate-200 truncate group-hover:text-cyan-300 transition">
                            {peer.name}
                          </h5>
                          <span className="text-[11px] font-mono text-slate-400 block mt-0.5">
                            {peer.ip}
                          </span>
                          {peer.receive_mode && (
                            <span className="inline-flex items-center gap-1 mt-1 px-2 py-0.5 rounded-md bg-emerald-500/20 text-emerald-400 text-[10px] font-black tracking-wider uppercase">
                              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping"></span>
                              Radar Ready
                            </span>
                          )}
                        </div>
                      </div>

                      <div className="mt-4 pt-3 border-t border-slate-800/70 flex items-center justify-between gap-2">
                        <button
                          onClick={() => { setPinTargetPeer(peer); setPinModalOpen(true); }}
                          className="px-2.5 py-1.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-slate-200 text-xs font-bold transition flex items-center gap-1"
                          title="Enter PIN for this device"
                        >
                          <Lock size={12} />
                          <span>PIN</span>
                        </button>
                        <button
                          onClick={() => initiateSendToPeer(peer)}
                          disabled={selectedFiles.length === 0}
                          className={`flex-1 py-1.5 px-3 rounded-xl text-xs font-black flex items-center justify-center gap-1.5 transition active:scale-95 shadow-sm ${
                            selectedFiles.length > 0
                              ? 'bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 shadow-cyan-500/20'
                              : 'bg-slate-800 text-slate-500 cursor-not-allowed'
                          }`}
                        >
                          <Send size={12} />
                          <span>Beam ({selectedFiles.length})</span>
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* INCOMING REQUEST MODAL DIALOG (Receiver side)                          */}
      {/* ===================================================================== */}
      {incomingReq && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-md bg-gradient-to-b from-slate-900 to-slate-950 border border-emerald-500/40 rounded-3xl p-6 shadow-2xl shadow-emerald-500/20 space-y-5">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-2xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center text-emerald-400">
                <Radio size={24} className="animate-pulse" />
              </div>
              <div>
                <h4 className="text-base font-black text-slate-100">Incoming AirPulse Beam</h4>
                <p className="text-xs text-slate-400">{incomingReq.sender_name} wants to send files</p>
              </div>
            </div>

            <div className="p-4 rounded-2xl bg-slate-950/90 border border-slate-800 space-y-2">
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">Files to receive:</span>
                <span className="font-bold text-slate-200">{incomingReq.total_count} files</span>
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">Total payload size:</span>
                <span className="font-bold text-emerald-400">{formatSize(incomingReq.total_size)}</span>
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">Sender IP:</span>
                <span className="font-mono text-slate-400">{incomingReq.sender_ip}</span>
              </div>
            </div>

            <div className="flex items-center gap-3 pt-1">
              <button
                onClick={handleDeclineIncoming}
                className="flex-1 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-bold transition active:scale-95"
              >
                Decline
              </button>
              <button
                onClick={handleAcceptIncoming}
                disabled={isAccepting}
                className="flex-1 py-2.5 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-400 hover:from-emerald-400 hover:to-teal-300 text-slate-950 text-xs font-black shadow-lg shadow-emerald-500/20 transition active:scale-95 flex items-center justify-center gap-2"
              >
                {isAccepting ? <RefreshCw size={14} className="animate-spin text-slate-950" /> : <CheckCircle2 size={15} />}
                <span>Accept & Save</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* 6-DIGIT PIN INPUT MODAL (Sender direct pairing)                       */}
      {/* ===================================================================== */}
      {pinModalOpen && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-sm bg-slate-900 border border-cyan-500/40 rounded-3xl p-6 shadow-2xl shadow-cyan-500/20 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
                  <Lock size={16} />
                </div>
                <h4 className="text-sm font-black text-slate-100">Direct PIN Pairing</h4>
              </div>
              <button onClick={() => setPinModalOpen(false)} className="text-slate-400 hover:text-slate-200">
                <X size={16} />
              </button>
            </div>

            <p className="text-xs text-slate-400">
              Enter the 6-digit PIN displayed on the receiver's AirPulse screen to beam directly.
            </p>

            <div className="space-y-3">
              {!pinTargetPeer && (
                <div>
                  <label className="text-[11px] font-bold text-slate-400 block mb-1">Target Device IP</label>
                  <input
                    type="text"
                    placeholder="e.g. 192.168.1.15"
                    value={targetIpInput}
                    onChange={(e) => setTargetIpInput(e.target.value)}
                    className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-slate-200 focus:outline-none focus:border-cyan-500"
                  />
                </div>
              )}

              <div>
                <label className="text-[11px] font-bold text-slate-400 block mb-1">6-Digit PIN Code</label>
                <input
                  type="text"
                  maxLength={6}
                  placeholder="e.g. 482910"
                  value={pinInput}
                  onChange={(e) => setPinInput(e.target.value)}
                  className="w-full px-3 py-2.5 bg-slate-950 border border-slate-800 rounded-xl text-center font-mono text-lg font-black tracking-widest text-cyan-300 focus:outline-none focus:border-cyan-500"
                />
              </div>
            </div>

            <button
              onClick={handleManualPinSend}
              disabled={pinInput.length < 4}
              className="w-full py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 font-black text-xs shadow-md shadow-cyan-500/20 transition active:scale-95 disabled:opacity-50"
            >
              Connect & Beam Files
            </button>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* ACTIVE TRANSFER PROGRESS HUD                                          */}
      {/* ===================================================================== */}
      {activeTransfer && (
        <div className="fixed bottom-6 right-6 z-50 w-96 bg-gradient-to-b from-slate-900/95 to-slate-950/95 border border-cyan-500/40 rounded-3xl p-5 shadow-2xl shadow-cyan-500/20 space-y-3 backdrop-blur-2xl animate-fade-in">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
                <Zap size={16} className="animate-pulse" />
              </div>
              <div>
                <span className="text-[10px] font-black text-cyan-400 uppercase tracking-widest block">AirPulse Stream</span>
                <span className="text-xs font-bold text-slate-200">{activeTransfer.direction}</span>
              </div>
            </div>
            <span className={`px-2 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider ${
              activeTransfer.status === 'completed'
                ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/40'
                : activeTransfer.status === 'declined' || activeTransfer.status === 'error'
                ? 'bg-rose-500/20 text-rose-400 border border-rose-500/40'
                : 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 animate-pulse'
            }`}>
              {activeTransfer.status}
            </span>
          </div>

          <div className="p-2.5 rounded-xl bg-slate-950/80 border border-slate-800">
            <p className="text-xs font-bold text-slate-200 truncate">
              {activeTransfer.current_file}
            </p>
            <p className="text-[11px] text-slate-400 mt-0.5">
              {activeTransfer.message || `${activeTransfer.file_index} of ${activeTransfer.total_files} files`}
            </p>
          </div>

          {/* Progress Bar */}
          <div className="space-y-1.5">
            <div className="w-full h-2.5 bg-slate-950 rounded-full overflow-hidden border border-slate-800">
              <div
                className={`h-full transition-all duration-300 rounded-full ${
                  activeTransfer.status === 'completed'
                    ? 'bg-emerald-400'
                    : 'bg-gradient-to-r from-cyan-500 via-teal-400 to-emerald-400'
                }`}
                style={{ width: `${Math.max(activeTransfer.percent, 3)}%` }}
              />
            </div>
            <div className="flex items-center justify-between text-xs text-slate-400 font-semibold">
              <span>{formatSize(activeTransfer.transferred_bytes)} / {formatSize(activeTransfer.total_bytes)}</span>
              <span className="text-cyan-300 font-black">{activeTransfer.percent}%</span>
            </div>
          </div>

          <div className="flex items-center justify-between pt-1 text-xs">
            <span className="font-mono text-cyan-300 text-xs font-bold">
              ⚡ {activeTransfer.speed_mb > 0 ? `${activeTransfer.speed_mb} MB/s` : 'Analyzing...'}
            </span>
            {activeTransfer.status === 'in_progress' ? (
              <button
                onClick={() => api.cancelAirPulseTransfer(activeTransfer.id)}
                className="text-xs text-rose-400 hover:text-rose-300 font-bold"
              >
                Cancel
              </button>
            ) : (
              <button
                onClick={() => setActiveTransfer(null)}
                className="text-xs text-slate-400 hover:text-slate-200 font-bold"
              >
                Dismiss
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
