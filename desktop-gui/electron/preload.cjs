const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  minimizeWindow: () => ipcRenderer.send('window:minimize'),
  maximizeWindow: () => ipcRenderer.send('window:maximize'),
  closeWindow: () => ipcRenderer.send('window:close'),
  hideToTray: () => ipcRenderer.send('window:hide-to-tray'),
  isMaximized: () => ipcRenderer.invoke('window:is-maximized'),
  openExternalFolder: (folderPath) => ipcRenderer.invoke('shell:open-folder', folderPath),
  openFileItem: (filePath) => ipcRenderer.invoke('shell:open-file', filePath),
  launchScrcpy: (phoneIp) => ipcRenderer.invoke('tools:launch-scrcpy', phoneIp),
  startServer: () => ipcRenderer.invoke('server:start'),
  openExternalUrl: (url) => ipcRenderer.invoke('shell:open-url', url)
});
