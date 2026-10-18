const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  // Window controls
  minimizeWindow: () => ipcRenderer.send('window:minimize'),
  maximizeWindow: () => ipcRenderer.send('window:maximize'),
  closeWindow: () => ipcRenderer.send('window:close'),
  hideToTray: () => ipcRenderer.send('window:hide-to-tray'),
  isMaximized: () => ipcRenderer.invoke('window:is-maximized'),

  // Shell & Files
  openExternalFolder: (folderPath) => ipcRenderer.invoke('shell:open-folder', folderPath),
  openFileItem: (filePath) => ipcRenderer.invoke('shell:open-file', filePath),
  openExternalUrl: (url) => ipcRenderer.invoke('shell:open-url', url),
  openExternal: (url) => ipcRenderer.invoke('shell:open-url', url),

  // Process & Tools
  launchScrcpy: (phoneIp) => ipcRenderer.invoke('tools:launch-scrcpy', phoneIp),
  startServer: () => ipcRenderer.invoke('server:start'),

  // Drag & Drop File Transfer (PC <-> Mobile)
  getPathForFile: (file) => {
    try {
      if (webUtils && typeof webUtils.getPathForFile === 'function') {
        return webUtils.getPathForFile(file);
      }
    } catch (_) {}
    return file?.path || '';
  },
  uploadFileToPhone: (data) => ipcRenderer.invoke('phone:upload-file', data),
  startDragPhoneFile: (data) => ipcRenderer.send('phone:start-drag', data),

  // Auto-Updater (modeled after Finance Application)
  getAppVersion: () => ipcRenderer.invoke('get-app-version'),
  downloadAndInstallUpdate: (url, version) =>
    ipcRenderer.invoke('download-and-install-update', { url, version }),
  onUpdateProgress: (callback) => {
    const handler = (_event, data) => callback(data);
    ipcRenderer.on('update-download-progress', handler);
    return () => ipcRenderer.removeListener('update-download-progress', handler);
  }
});
