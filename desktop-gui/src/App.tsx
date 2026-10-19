import { useState, useEffect, useCallback, useRef } from 'react';
import { TitleBar } from './components/TitleBar';
import { Sidebar } from './components/Sidebar';
import type { TabKey } from './components/Sidebar';
import { ServerDetails } from './components/ServerDetails';
import { DeviceInfo } from './components/DeviceInfo';
import { CameraRecording } from './components/CameraRecording';
import { FolderSync } from './components/FolderSync';
import { MobileControls } from './components/MobileControls';
import { FileManager } from './components/FileManager';
import { Security } from './components/Security';
import { Settings } from './components/Settings';
import { SetupWizard } from './components/SetupWizard';
import { VersionUpdateModal } from './components/VersionUpdateModal';
import { checkForAppUpdates } from './version';
import type { AppUpdateData } from './version';
import { api } from './api';
import type { ServerStatus, SystemStats, PairingRequest } from './types';
import { Download, RefreshCw, CheckCircle, AlertCircle } from 'lucide-react';

export function App() {
  const [activeTab, setActiveTab] = useState<TabKey>('server');
  const [isOnline, setIsOnline] = useState<boolean>(false);
  const [status, setStatus] = useState<ServerStatus | null>(null);
  const [stats, setStats] = useState<SystemStats | null>(null);
  const [pairRequests, setPairRequests] = useState<PairingRequest[]>([]);
  const [updateModalOpen, setUpdateModalOpen] = useState<boolean>(false);
  const [updateData, setUpdateData] = useState<AppUpdateData | null>(null);
  const prevPhoneCamActiveRef = useRef<boolean>(false);

  const fetchAllData = useCallback(async () => {
    try {
      const serverStatus = await api.getStatus();
      setStatus(serverStatus);
      setIsOnline(true);
    } catch {
      setIsOnline(false);
      setStatus(null);
    }

    try {
      const systemStats = await api.getSystemStats();
      setStats(systemStats);
    } catch {
      // Non-critical
    }

    try {
      const requests = await api.getPairingRequests();
      setPairRequests(requests);
    } catch {
      // Non-critical
    }

    try {
      const cam = await api.getCameraStatus();
      if (cam?.phone_camera_active && !prevPhoneCamActiveRef.current) {
        setActiveTab('camera-record');
      }
      prevPhoneCamActiveRef.current = Boolean(cam?.phone_camera_active);
    } catch {
      // Non-critical
    }
  }, []);

  useEffect(() => {
    fetchAllData();
    const interval = setInterval(fetchAllData, 3000);
    return () => clearInterval(interval);
  }, [fetchAllData]);

  // Check for updates immediately on open
  useEffect(() => {
    const timer = setTimeout(async () => {
      try {
        const data = await checkForAppUpdates();
        setUpdateData(data);
        if (data.hasUpdate) {
          setUpdateModalOpen(true);
        }
      } catch (_) {}
    }, 300);
    return () => clearTimeout(timer);
  }, []);

  const connectedClient = status?.clients && status.clients.length > 0
    ? status.clients[0]?.name
    : status?.connected_clients_map
    ? Object.values(status.connected_clients_map)[0]?.name
    : null;

  const isConfigured = status?.is_configured !== false;

  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const [transferStatus, setTransferStatus] = useState<{ active: boolean; message: string; type: 'info' | 'success' | 'error' } | null>(null);

  const activeClientIp = status?.clients && status.clients.length > 0 ? status.clients[0].ip : '127.0.0.1';
  const hasConnectedPhone = Boolean(connectedClient);

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.dataTransfer.types.includes('Files')) {
      setIsDraggingOver(true);
    }
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.currentTarget.contains(e.relatedTarget as Node)) return;
    setIsDraggingOver(false);
  };

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDraggingOver(false);

    const files = Array.from(e.dataTransfer.files);
    if (files.length === 0) return;

    if (!hasConnectedPhone) {
      setTransferStatus({
        active: true,
        message: 'No mobile device connected. Connect your phone first to transfer files.',
        type: 'error'
      });
      setTimeout(() => setTransferStatus(null), 4000);
      return;
    }

    setTransferStatus({
      active: true,
      message: `Sending ${files.length} file${files.length > 1 ? 's' : ''} to ${connectedClient}...`,
      type: 'info'
    });

    let successCount = 0;
    for (const file of files) {
      try {
        let filePath = '';
        if (window.electronAPI?.getPathForFile) {
          filePath = window.electronAPI.getPathForFile(file);
        }
        if (!filePath) {
          filePath = (file as any).path || '';
        }

        if (filePath && window.electronAPI?.uploadFileToPhone) {
          await window.electronAPI.uploadFileToPhone({
            phoneIp: activeClientIp,
            filePath,
            fileName: file.name
          });
          successCount++;
        } else if (window.electronAPI?.uploadFileToPhone) {
          const buffer = await file.arrayBuffer();
          await window.electronAPI.uploadFileToPhone({
            phoneIp: activeClientIp,
            fileName: file.name,
            fileBuffer: buffer
          });
          successCount++;
        }
      } catch (err: any) {
        console.error('File transfer failed:', err);
      }
    }

    if (successCount > 0) {
      setTransferStatus({
        active: true,
        message: `✓ Saved ${successCount} file${successCount > 1 ? 's' : ''} to ${connectedClient}'s Downloads/RemotePC`,
        type: 'success'
      });
    } else {
      setTransferStatus({
        active: true,
        message: `File transfer failed. Ensure Remote PC app is running on phone.`,
        type: 'error'
      });
    }
    setTimeout(() => setTransferStatus(null), 5000);
  };

  return (
    <div
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      className="relative flex flex-col h-screen w-screen overflow-hidden bg-[#0B0F19] text-slate-100 select-none"
    >
      {/* PC Setup Wizard for Fresh Install / Unconfigured Server */}
      {isOnline && !isConfigured && (
        <SetupWizard defaultName={status?.name} onComplete={fetchAllData} />
      )}

      {/* Frameless Glassmorphic Title Bar with Live Update Pill */}
      <TitleBar
        isOnline={isOnline}
        connectedPhone={connectedClient}
        onRefresh={fetchAllData}
        hasUpdate={Boolean(updateData?.hasUpdate)}
        latestVersion={updateData?.latestVersion}
        onOpenUpdateModal={() => setUpdateModalOpen(true)}
      />

      {/* Main App Body */}
      <div className="flex flex-1 overflow-hidden">
        {/* Navigation Sidebar */}
        <Sidebar
          activeTab={activeTab}
          onTabChange={setActiveTab}
          pendingPairingsCount={pairRequests.length}
          hasPhoneConnected={Boolean(connectedClient)}
        />

        {/* Content Pane */}
        <main className="flex-1 overflow-y-auto p-5 bg-gradient-to-br from-[#0B0F19] via-[#0E1526] to-[#070C16]">
          {activeTab === 'server' && (
            <ServerDetails
              status={status}
              stats={stats}
              pairRequests={pairRequests}
              onRefresh={fetchAllData}
            />
          )}

          {activeTab === 'mobile-info' && (
            <DeviceInfo
              status={status}
              onRefresh={fetchAllData}
            />
          )}

          {activeTab === 'camera-record' && (
            <CameraRecording
              status={status}
            />
          )}

          {activeTab === 'folder-sync' && (
            <FolderSync
              status={status}
            />
          )}

          {activeTab === 'controls' && (
            <MobileControls
              status={status}
              onRefresh={fetchAllData}
            />
          )}

          {activeTab === 'files' && (
            <FileManager
              status={status}
            />
          )}

          {activeTab === 'security' && (
            <Security
              pairRequests={pairRequests}
              onRefresh={fetchAllData}
            />
          )}

          {activeTab === 'settings' && (
            <Settings
              onRefresh={fetchAllData}
              onOpenUpdateModal={() => setUpdateModalOpen(true)}
            />
          )}
        </main>
      </div>

      {/* In-App Auto-Update Modal (modeled after Finance Application) */}
      <VersionUpdateModal
        isOpen={updateModalOpen}
        onClose={() => setUpdateModalOpen(false)}
        initialUpdateData={updateData}
      />

      {/* Full-Window Smooth Drag-and-Drop Overlay */}
      {isDraggingOver && (
        <div className="absolute inset-0 z-50 flex flex-col items-center justify-center bg-slate-950/85 backdrop-blur-md border-4 border-dashed border-cyan-400 pointer-events-none animate-in fade-in zoom-in-95 duration-200">
          <div className="p-8 rounded-3xl bg-cyan-500/15 border border-cyan-400/40 text-cyan-300 shadow-2xl flex flex-col items-center gap-4 text-center max-w-md mx-4">
            <div className="p-4 rounded-2xl bg-cyan-500/20 text-cyan-400 animate-bounce">
              <Download size={48} />
            </div>
            <div className="text-2xl font-black text-white tracking-wide">Drop to Save on Mobile</div>
            <p className="text-sm text-cyan-200/90 leading-relaxed">
              Files will be saved automatically into {connectedClient ? <span className="font-bold text-white">{connectedClient}'s</span> : "phone's"} <span className="font-semibold text-cyan-300 underline decoration-cyan-500/50">Downloads/RemotePC</span>
            </p>
          </div>
        </div>
      )}

      {/* Floating Transfer Status Toast Banner */}
      {transferStatus && (
        <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5 rounded-2xl shadow-2xl border backdrop-blur-xl animate-in slide-in-from-bottom-5 duration-200 ${
          transferStatus.type === 'success'
            ? 'bg-emerald-950/95 border-emerald-500/40 text-emerald-200 shadow-emerald-500/20'
            : transferStatus.type === 'error'
            ? 'bg-rose-950/95 border-rose-500/40 text-rose-200 shadow-rose-500/20'
            : 'bg-slate-900/95 border-cyan-500/40 text-cyan-200 shadow-cyan-500/20'
        }`}>
          {transferStatus.type === 'info' && <RefreshCw size={18} className="animate-spin text-cyan-400" />}
          {transferStatus.type === 'success' && <CheckCircle size={18} className="text-emerald-400" />}
          {transferStatus.type === 'error' && <AlertCircle size={18} className="text-rose-400" />}
          <span className="text-xs font-bold">{transferStatus.message}</span>
        </div>
      )}
    </div>
  );
}

export default App;
