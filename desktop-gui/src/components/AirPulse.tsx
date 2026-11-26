import React, { useState, useEffect, useRef } from 'react';
import QRCode from 'qrcode';
import {
  Radio, Send, Smartphone, Laptop, Terminal, Copy, Check,
  RefreshCw, FolderOpen, X, Zap, CheckCircle2, Plus, Trash2, Lock, QrCode as QrCodeIcon,
  Wifi
} from 'lucide-react';
import { api } from '../api';
import type {
  ServerStatus, AirPulsePeer, AirPulseStatus, AirPulseIncomingRequest, AirPulseTransferProgress
} from '../types';

interface AirPulseProps {
  status?: ServerStatus | null;
}

export const AirPulse: React.FC<AirPulseProps> = () => {
  // Receive Mode State (Always active in Quick Share)
  const [receiveStatus, setReceiveStatus] = useState<AirPulseStatus | null>(null);
  const [copiedAddress, setCopiedAddress] = useState(false);
  const [incomingReq, setIncomingReq] = useState<AirPulseIncomingRequest | null>(null);
  const [isAccepting, setIsAccepting] = useState(false);
  const [receiveError, setReceiveError] = useState<string | null>(null);

  // Sender & Peer State
  const [selectedFiles, setSelectedFiles] = useState<Array<{ name: string; size: number; path?: string; fileObj?: File }>>([]);
  const [peers, setPeers] = useState<AirPulsePeer[]>([]);
  const [isScanningPeers, setIsScanningPeers] = useState(false);
  const [activeTransfer, setActiveTransfer] = useState<AirPulseTransferProgress | null>(null);

  // Dialogs State
  const [pinModalOpen, setPinModalOpen] = useState(false);
  const [targetIpInput, setTargetIpInput] = useState('');
  const [qrModalOpen, setQrModalOpen] = useState(false);
  const [qrDataUrl, setQrDataUrl] = useState<string>('');

  const fileInputRef = useRef<HTMLInputElement>(null);
  const transferPollRef = useRef<any>(null);
  const pendingPeerRef = useRef<AirPulsePeer | null>(null);

  // Generate QR Code for this device
  const generateQr = async (st: AirPulseStatus) => {
    try {
      const ssid = st.wifi_ssid || '';
      const payload = `REMOTEPC|${st.device_name || 'Remote PC'}|${st.ip}|${st.port || 5055}||${ssid}`;
      const url = await QRCode.toDataURL(payload, {
        margin: 1.5,
        width: 260,
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

  // --- Scan Peers ---
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

  // --- Initial Mount: Always Advertise Receive Mode & Discover Nearby Peers Concurrently ---
  useEffect(() => {
    let isMounted = true;
    setReceiveError(null);

    // Turn on receiver beacon & load status
    api.toggleAirPulseReceiveMode(true)
      .then((res) => {
        if (!isMounted) return;
        setReceiveStatus(res);
        if (res.ip) generateQr(res);
      })
      .catch(() => {
        if (isMounted) setReceiveError('Start the Remote PC server to enable receiving.');
      });

    // Check incoming transfer requests
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
    const incomingInterval = setInterval(checkIncoming, 1500);

    // Continuous peer discovery
    scanPeers();
    const peerInterval = setInterval(scanPeers, 4000);

    return () => {
      isMounted = false;
      clearInterval(incomingInterval);
      clearInterval(peerInterval);
      api.toggleAirPulseReceiveMode(false).catch(() => {});
    };
  }, []);

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

  // --- Send Files to Target Peer ---
  const initiateSendToPeer = async (peer: AirPulsePeer, filesOverride?: Array<{ name: string; size: number; path?: string }>) => {
    const filesToUse = filesOverride || selectedFiles;
    if (filesToUse.length === 0) {
      pendingPeerRef.current = peer;
      fileInputRef.current?.click();
      return;
    }

    const filePaths = filesToUse.map((f) => f.path || f.name);
    const totalBytes = filesToUse.reduce((acc, f) => acc + f.size, 0);

    try {
      const res = await api.sendAirPulseFiles({
        target_ip: peer.ip,
        target_port: peer.port || 5055,
        file_paths: filePaths
      });

      if (res.transfer_id) {
        monitorTransfer(res.transfer_id, {
          id: res.transfer_id,
          direction: `This PC ➔ ${peer.name}`,
          transferred_bytes: 0,
          total_bytes: totalBytes,
          current_file: filesToUse[0]?.name || 'file',
          file_index: 1,
          total_files: filesToUse.length,
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

    setSelectedFiles((prev) => {
      const updated = [...prev, ...added];
      const targetPeer = pendingPeerRef.current;
      if (targetPeer) {
        pendingPeerRef.current = null;
        setTimeout(() => {
          initiateSendToPeer(targetPeer, updated);
        }, 150);
      }
      return updated;
    });
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

  // --- Manual IP Connection Handlers ---
  const handleManualPinSend = () => {
    if (!targetIpInput) {
      alert('Please enter receiver IP or select a device');
      return;
    }
    const entered = targetIpInput.trim();
    const hasPort = /^\[[^\]]+\]:\d+$/.test(entered) || /^[^:]+:\d+$/.test(entered);
    const address = hasPort && entered.startsWith('[') ? entered.slice(1, entered.lastIndexOf(']')) : hasPort ? entered.slice(0, entered.lastIndexOf(':')) : entered;
    const port = hasPort ? Number(entered.slice(entered.lastIndexOf(':') + 1)) : 5055;
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      alert('Enter a valid receiver port between 1 and 65535.');
      return;
    }
    const targetPeer: AirPulsePeer = {
      name: `Device (${entered})`,
      ip: address,
      port,
      os: 'unknown',
      receive_mode: true
    };
    setPinModalOpen(false);
    initiateSendToPeer(targetPeer);
  };

  return (
    <div className="space-y-6 animate-fade-in pb-12">
      {/* Top Header Bar */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-5 rounded-3xl bg-gradient-to-r from-slate-900/90 via-slate-900/70 to-slate-950/90 border border-slate-800/80 backdrop-blur-xl shadow-2xl shadow-black/50">
        <div className="flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-cyan-500/20 via-emerald-500/15 to-transparent border border-cyan-500/40 flex items-center justify-center text-cyan-400 shadow-[0_0_15px_rgba(6,182,212,0.15)] shrink-0">
            <Zap size={24} className="text-cyan-400 animate-pulse" />
          </div>
          <div>
            <div className="flex items-center gap-2.5">
              <h2 className="text-lg font-black text-slate-100 tracking-tight">AirPulse Quick Share</h2>
              <span className="px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 flex items-center gap-1.5 shadow-sm">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 shadow-[0_0_6px_#10B981] animate-ping"></span>
                Ready to Share & Receive
              </span>
            </div>
            <p className="text-xs text-slate-400 font-medium mt-0.5">
              Zero-setup Wi-Fi P2P transfer with phones & PCs. Tap any device node to beam instantly.
            </p>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex items-center gap-2 self-start md:self-auto">
          <button
            onClick={() => setQrModalOpen(true)}
            className="px-3.5 py-2 rounded-xl bg-slate-800/90 hover:bg-slate-700/90 border border-slate-700 text-cyan-300 text-xs font-bold flex items-center gap-1.5 transition active:scale-95"
            title="Show Quick Share QR Code"
          >
            <QrCodeIcon size={14} />
            <span>Show QR</span>
          </button>
          <button
            onClick={openSaveDirectory}
            className="px-3.5 py-2 rounded-xl bg-slate-800/90 hover:bg-slate-700/90 border border-slate-700 text-slate-300 hover:text-amber-300 text-xs font-bold flex items-center gap-1.5 transition active:scale-95"
            title="Open Received Files Folder"
          >
            <FolderOpen size={14} className="text-amber-400" />
            <span>Received Files</span>
          </button>
          <button
            onClick={() => setPinModalOpen(true)}
            className="px-3.5 py-2 rounded-xl bg-slate-800/90 hover:bg-slate-700/90 border border-slate-700 text-slate-300 hover:text-cyan-300 text-xs font-bold flex items-center gap-1.5 transition active:scale-95"
            title="Connect by IP Address"
          >
            <Lock size={13} />
            <span>IP Direct</span>
          </button>
          <button
            onClick={scanPeers}
            disabled={isScanningPeers}
            className="p-2 rounded-xl bg-slate-800/90 hover:bg-slate-700/90 border border-slate-700 text-slate-300 hover:text-cyan-300 transition"
            title="Scan Nearby Peers"
          >
            <RefreshCw size={15} className={isScanningPeers ? 'animate-spin text-cyan-400' : ''} />
          </button>
        </div>
      </div>

      {receiveError && (
        <div className="p-3.5 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center gap-2">
          <span>⚠️ {receiveError}</span>
        </div>
      )}

      {/* ===================================================================== */}
      {/* 1. QUICK SHARE RADAR & LOCAL IDENTITY CARD                             */}
      {/* ===================================================================== */}
      <div className="relative overflow-hidden rounded-3xl bg-gradient-to-b from-slate-900/90 via-slate-950/90 to-slate-950 border border-cyan-500/30 p-6 shadow-2xl shadow-cyan-500/10">
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 pointer-events-none -z-0">
          <div className="w-[340px] h-[340px] rounded-full border border-cyan-500/15 animate-ping opacity-25"></div>
          <div className="w-[480px] h-[480px] rounded-full border border-emerald-500/10 -translate-x-16 -translate-y-16"></div>
        </div>

        <div className="relative z-10 flex flex-col md:flex-row items-center justify-between gap-6">
          <div className="flex items-center gap-5 text-center md:text-left">
            <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-cyan-500/20 via-slate-800 to-slate-950 border border-cyan-500/40 flex items-center justify-center text-cyan-400 shadow-xl shadow-cyan-500/10 shrink-0">
              <Laptop size={32} className="text-cyan-400" />
            </div>
            <div>
              <div className="flex items-center gap-2 justify-center md:justify-start">
                <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 shadow-[0_0_8px_#10B981] animate-pulse"></span>
                <h3 className="text-xl font-black text-slate-100 tracking-tight">
                  {receiveStatus?.device_name || 'This PC'}
                </h3>
                <span className="px-2 py-0.5 rounded-md bg-emerald-500/20 border border-emerald-500/30 text-emerald-300 text-[10px] font-black uppercase tracking-wider">
                  Quick Share Active
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Visible to nearby phones and PCs on local Wi-Fi. Incoming files prompt for your approval.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div className="px-4 py-2.5 rounded-2xl bg-slate-950/90 border border-slate-800 flex items-center gap-3 shadow-inner">
              <Wifi size={16} className="text-cyan-400" />
              <div className="text-left font-mono text-xs">
                <span className="text-slate-500 block text-[9px] uppercase font-sans font-bold">Network Address</span>
                <span className="font-bold text-slate-200">{receiveStatus?.ip || '127.0.0.1'}:{receiveStatus?.port || 5055}</span>
              </div>
              <button
                onClick={() => {
                  navigator.clipboard.writeText(`${receiveStatus?.ip || ''}:${receiveStatus?.port || 5055}`);
                  setCopiedAddress(true);
                  setTimeout(() => setCopiedAddress(false), 2000);
                }}
                className="p-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-cyan-300 transition"
                title="Copy Address"
              >
                {copiedAddress ? <Check size={14} className="text-emerald-400" /> : <Copy size={14} />}
              </button>
            </div>
          </div>
        </div>
      </div>

      {receiveError && (
        <div className="p-3.5 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center gap-2">
          <span>⚠️ {receiveError}</span>
        </div>
      )}

      {/* ===================================================================== */}
      {/* 2. FILE SELECTION & DROPZONE                                          */}
      {/* ===================================================================== */}
      <div className="space-y-4">
        <div
          onClick={() => fileInputRef.current?.click()}
          onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); }}
          onDrop={(e) => {
            e.preventDefault();
            e.stopPropagation();
            handleFilesAdded(e.dataTransfer.files);
          }}
          className="p-7 rounded-3xl bg-gradient-to-b from-slate-900/80 to-slate-950/80 border-2 border-dashed border-slate-800 hover:border-cyan-500/50 hover:bg-slate-900/50 transition-all duration-200 cursor-pointer text-center group relative overflow-hidden"
        >
          <input
            type="file"
            multiple
            ref={fileInputRef}
            onChange={(e) => handleFilesAdded(e.target.files)}
            className="hidden"
          />
          <div className="w-12 h-12 rounded-2xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center mx-auto text-cyan-400 group-hover:scale-110 transition-transform shadow-lg shadow-cyan-500/10">
            <Plus size={24} />
          </div>
          <h4 className="text-base font-black text-slate-100 mt-3 group-hover:text-cyan-300 transition">
            {selectedFiles.length > 0 ? 'Add More Files' : 'Select or Drop Files to Beam'}
          </h4>
          <p className="text-xs text-slate-400 max-w-md mx-auto mt-1">
            Pick photos, 4K videos, documents, or apps to share instantly with nearby devices
          </p>
        </div>

        {/* Selected Files Tray */}
        {selectedFiles.length > 0 && (
          <div className="p-4 rounded-3xl bg-slate-900/80 border border-slate-800/80 space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="text-xs font-black text-slate-200 uppercase tracking-wider">
                  Files Ready to Beam ({selectedFiles.length})
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
                <span>Clear</span>
              </button>
            </div>

            <div className="flex flex-wrap gap-2 max-h-36 overflow-y-auto pr-1">
              {selectedFiles.map((f, idx) => (
                <div
                  key={`${f.name}_${idx}`}
                  className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-slate-950/80 border border-slate-800 text-xs font-semibold text-slate-200"
                >
                  <span className="truncate max-w-[190px]">{f.name}</span>
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
      </div>

      {/* ===================================================================== */}
      {/* 3. DISCOVERED PEER DEVICES (1-Tap Share Nodes)                         */}
      {/* ===================================================================== */}
      <div className="p-6 rounded-3xl bg-gradient-to-b from-slate-900/90 to-slate-950/90 border border-slate-800/80 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
              <Radio size={16} className={isScanningPeers ? 'animate-spin' : ''} />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h4 className="text-sm font-black text-slate-100 uppercase tracking-wider">
                  Nearby Devices
                </h4>
                <span className="px-2 py-0.5 rounded-md bg-cyan-500/20 text-cyan-300 text-[10px] font-bold">
                  {peers.length} Found
                </span>
              </div>
              <p className="text-[11px] text-slate-400">
                Tap any detected peer below to beam files immediately
              </p>
            </div>
          </div>

          <button
            onClick={scanPeers}
            disabled={isScanningPeers}
            className="px-3 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 hover:text-cyan-300 text-xs font-bold flex items-center gap-1.5 transition active:scale-95"
          >
            <RefreshCw size={13} className={isScanningPeers ? 'animate-spin text-cyan-400' : ''} />
            <span>Rescan</span>
          </button>
        </div>

        {/* Peers Grid */}
        {peers.length === 0 ? (
          <div className="p-10 rounded-2xl bg-slate-950/60 border border-slate-800/80 text-center space-y-3">
            <div className="w-12 h-12 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-center mx-auto text-slate-500">
              <Radio size={22} className="animate-pulse" />
            </div>
            <h5 className="font-bold text-sm text-slate-300">Searching for Nearby Devices...</h5>
            <p className="text-xs text-slate-500 max-w-sm mx-auto">
              Ensure the target phone or PC is connected to the same Wi-Fi and has Remote PC open or in AirPulse screen.
            </p>
            <button
              onClick={() => setPinModalOpen(true)}
              className="px-4 py-2 rounded-xl bg-cyan-500/15 hover:bg-cyan-500/25 border border-cyan-500/30 text-cyan-300 text-xs font-bold inline-flex items-center gap-1.5 transition active:scale-95 mt-2"
            >
              <Zap size={13} />
              <span>Connect by IP address</span>
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
                  onClick={() => initiateSendToPeer(peer)}
                  className="p-4 rounded-2xl bg-slate-950/80 border border-slate-800/90 hover:border-cyan-500/40 hover:shadow-lg hover:shadow-cyan-500/10 transition-all flex flex-col justify-between group cursor-pointer"
                >
                  <div className="flex items-start gap-3">
                    <div className="w-11 h-11 rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center shrink-0">
                      {isAndroid ? (
                        <Smartphone size={22} className="text-emerald-400" />
                      ) : isWin ? (
                        <Laptop size={22} className="text-cyan-400" />
                      ) : (
                        <Terminal size={22} className="text-orange-400" />
                      )}
                    </div>
                    <div className="min-w-0 flex-1">
                      <h5 className="text-xs font-bold text-slate-200 truncate group-hover:text-cyan-300 transition">
                        {peer.name}
                      </h5>
                      <span className="text-[11px] font-mono text-slate-400 block mt-0.5">
                        {peer.ip}
                      </span>
                      <span className="inline-flex items-center gap-1 mt-1 px-2 py-0.5 rounded-md bg-emerald-500/20 text-emerald-400 text-[10px] font-black tracking-wider uppercase">
                        <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping"></span>
                        Quick Share Ready
                      </span>
                    </div>
                  </div>

                  <div className="mt-4 pt-3 border-t border-slate-800/70 flex items-center justify-end">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        initiateSendToPeer(peer);
                      }}
                      className="w-full py-2 px-3 rounded-xl text-xs font-black flex items-center justify-center gap-1.5 transition active:scale-95 shadow-sm bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 shadow-cyan-500/20"
                    >
                      <Send size={12} />
                      <span>{selectedFiles.length > 0 ? `Beam ${selectedFiles.length} File${selectedFiles.length > 1 ? 's' : ''}` : 'Tap to Pick & Beam'}</span>
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

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
                <p className="text-xs text-slate-400">{incomingReq.sender_name} wants to beam files</p>
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
      {/* MANUAL IP CONNECTION MODAL                                            */}
      {/* ===================================================================== */}
      {pinModalOpen && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-sm bg-slate-900 border border-cyan-500/40 rounded-3xl p-6 shadow-2xl shadow-cyan-500/20 space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
                  <Lock size={16} />
                </div>
                <h4 className="text-sm font-black text-slate-100">Connect by IP address</h4>
              </div>
              <button onClick={() => setPinModalOpen(false)} className="text-slate-400 hover:text-slate-200">
                <X size={16} />
              </button>
            </div>

            <p className="text-xs text-slate-400">
              Enter the receiver’s local IP address. Receiver must be on the same network.
            </p>

            <div className="space-y-3">
              <div>
                <label className="text-[11px] font-bold text-slate-400 block mb-1">Target Device IP</label>
                <input
                  type="text"
                  placeholder="e.g. 192.168.1.15 or 192.168.1.15:5056"
                  value={targetIpInput}
                  onChange={(e) => setTargetIpInput(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-slate-200 focus:outline-none focus:border-cyan-500"
                />
              </div>
            </div>

            <div className="flex items-center gap-2 pt-2">
              <button
                onClick={() => setPinModalOpen(false)}
                className="flex-1 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs"
              >
                Cancel
              </button>
              <button
                onClick={handleManualPinSend}
                className="flex-1 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-teal-400 text-slate-950 font-black text-xs shadow-md shadow-cyan-500/20"
              >
                Send Beam
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* QUICK SHARE QR MODAL                                                  */}
      {/* ===================================================================== */}
      {qrModalOpen && (
        <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-sm bg-slate-900 border border-cyan-500/40 rounded-3xl p-6 shadow-2xl shadow-cyan-500/20 space-y-4 text-center">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-9 h-9 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400">
                  <QrCodeIcon size={16} />
                </div>
                <h4 className="text-sm font-black text-slate-100">Quick Share QR</h4>
              </div>
              <button onClick={() => setQrModalOpen(false)} className="text-slate-400 hover:text-slate-200">
                <X size={16} />
              </button>
            </div>

            <p className="text-xs text-slate-400">
              Scan this QR code from your phone's AirPulse screen to beam files directly.
            </p>

            <div className="p-3 bg-white rounded-2xl mx-auto w-fit shadow-lg shadow-cyan-500/10">
              {qrDataUrl ? (
                <img src={qrDataUrl} alt="Quick Share QR" className="w-56 h-56 object-contain" />
              ) : (
                <div className="w-56 h-56 flex items-center justify-center text-slate-500 text-xs">
                  Generating QR...
                </div>
              )}
            </div>

            <div className="text-[11px] font-mono text-cyan-400 bg-slate-950/80 p-2 rounded-xl border border-slate-800">
              {receiveStatus?.ip || '127.0.0.1'}:{receiveStatus?.port || 5055}
            </div>

            <button
              onClick={() => setQrModalOpen(false)}
              className="w-full py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs transition active:scale-95"
            >
              Close
            </button>
          </div>
        </div>
      )}

      {/* ===================================================================== */}
      {/* ACTIVE TRANSFER PROGRESS HUD WITH CANCEL BUTTON                       */}
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
                : activeTransfer.status === 'declined' || activeTransfer.status === 'error' || activeTransfer.status === 'cancelled'
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
                    : activeTransfer.status === 'cancelled'
                    ? 'bg-rose-500'
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
            {['in_progress', 'requesting'].includes(activeTransfer.status) ? (
              <button
                onClick={async () => {
                  try {
                    await api.cancelAirPulseTransfer(activeTransfer.id);
                  } catch {
                    // ignore
                  }
                  setActiveTransfer((prev) => prev ? { ...prev, status: 'cancelled', message: 'Cancelled by user' } : null);
                }}
                className="px-3 py-1 rounded-lg bg-rose-500/15 hover:bg-rose-500/25 border border-rose-500/30 text-xs text-rose-300 font-bold transition active:scale-95 flex items-center gap-1"
              >
                <X size={13} />
                <span>Cancel</span>
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
