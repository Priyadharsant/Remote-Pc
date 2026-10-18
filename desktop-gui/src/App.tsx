import { useState, useEffect, useCallback } from 'react';
import { TitleBar } from './components/TitleBar';
import { Sidebar } from './components/Sidebar';
import type { TabKey } from './components/Sidebar';
import { ServerDetails } from './components/ServerDetails';
import { DeviceInfo } from './components/DeviceInfo';
import { ScreenMirror } from './components/ScreenMirror';
import { MobileControls } from './components/MobileControls';
import { FileManager } from './components/FileManager';
import { AppsManager } from './components/AppsManager';
import { NotificationsCenter } from './components/NotificationsCenter';
import { Security } from './components/Security';
import { Settings } from './components/Settings';
import { SetupWizard } from './components/SetupWizard';
import { VersionUpdateModal } from './components/VersionUpdateModal';
import { checkForAppUpdates } from './version';
import type { AppUpdateData } from './version';
import { api } from './api';
import type { ServerStatus, SystemStats, PairingRequest } from './types';

export function App() {
  const [activeTab, setActiveTab] = useState<TabKey>('server');
  const [isOnline, setIsOnline] = useState<boolean>(false);
  const [status, setStatus] = useState<ServerStatus | null>(null);
  const [stats, setStats] = useState<SystemStats | null>(null);
  const [pairRequests, setPairRequests] = useState<PairingRequest[]>([]);
  const [updateModalOpen, setUpdateModalOpen] = useState<boolean>(false);
  const [updateData, setUpdateData] = useState<AppUpdateData | null>(null);

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

  return (
    <div className="flex flex-col h-screen w-screen overflow-hidden bg-[#0B0F19] text-slate-100 select-none">
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

          {activeTab === 'screen' && (
            <ScreenMirror
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

          {activeTab === 'apps' && (
            <AppsManager
              status={status}
            />
          )}

          {activeTab === 'notifications' && (
            <NotificationsCenter
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
    </div>
  );
}

export default App;
