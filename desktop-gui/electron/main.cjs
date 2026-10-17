const { app, BrowserWindow, ipcMain, Tray, Menu, shell } = require('electron');
const path = require('path');
const os = require('os');
const { spawn, exec } = require('child_process');

let mainWindow = null;
let tray = null;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1060,
    height: 740,
    minWidth: 920,
    minHeight: 620,
    frame: false,
    backgroundColor: '#0B0F19',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: false
    }
  });

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
  }

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  mainWindow.on('close', (event) => {
    if (!app.isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

function createTray() {
  const iconPath = path.join(__dirname, '../../server/logo.ico');
  try {
    tray = new Tray(iconPath);
  } catch (e) {
    console.error('Tray icon error:', e);
    return;
  }

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Open Remote PC',
      click: () => {
        if (mainWindow) {
          mainWindow.show();
          mainWindow.focus();
        }
      }
    },
    {
      label: 'Downloads Folder',
      click: () => {
        const dlFolder = path.join(os.homedir(), 'Downloads', 'From_Phone');
        shell.openPath(dlFolder);
      }
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        app.isQuitting = true;
        app.quit();
      }
    }
  ]);

  tray.setToolTip('Remote PC Control Suite');
  tray.setContextMenu(contextMenu);
  tray.on('double-click', () => {
    if (mainWindow) {
      mainWindow.show();
      mainWindow.focus();
    }
  });
}

// Window control IPCs
ipcMain.on('window:minimize', () => {
  if (mainWindow) mainWindow.minimize();
});

ipcMain.on('window:maximize', () => {
  if (mainWindow) {
    if (mainWindow.isMaximized()) {
      mainWindow.unmaximize();
    } else {
      mainWindow.maximize();
    }
  }
});

ipcMain.on('window:close', () => {
  if (mainWindow) mainWindow.hide();
});

ipcMain.on('window:hide-to-tray', () => {
  if (mainWindow) mainWindow.hide();
});

ipcMain.handle('window:is-maximized', () => {
  return mainWindow ? mainWindow.isMaximized() : false;
});

ipcMain.handle('shell:open-folder', async (_, folderPath) => {
  const target = folderPath || path.join(os.homedir(), 'Downloads', 'From_Phone');
  await shell.openPath(target);
  return true;
});

ipcMain.handle('shell:open-file', async (_, filePath) => {
  if (filePath) {
    await shell.openPath(filePath);
    return true;
  }
  return false;
});

ipcMain.handle('shell:open-url', async (_, url) => {
  if (url) {
    await shell.openExternal(url);
    return true;
  }
  return false;
});

ipcMain.handle('server:start', async () => {
  try {
    const candidatePaths = [
      path.join(app.getAppPath(), '..', 'Remote_Server.exe'),
      path.join(process.resourcesPath, 'Remote_Server.exe'),
      path.join(__dirname, '../../Remote_Server.exe'),
      path.join(__dirname, '../../server/Remote_Server.exe'),
      path.join(process.cwd(), 'Remote_Server.exe'),
      path.join(process.cwd(), 'server', 'Remote_Server.exe')
    ];
    for (const sPath of candidatePaths) {
      if (require('fs').existsSync(sPath)) {
        console.log('Launching Core Server exe:', sPath);
        spawn(sPath, [], { detached: true, stdio: 'ignore' }).unref();
        return { success: true };
      }
    }
    // Fallback: spawn python script
    const scriptCandidates = [
      path.join(__dirname, '../../server/Remote_Server.py'),
      path.join(process.cwd(), 'server', 'Remote_Server.py'),
      path.join(app.getAppPath(), 'server', 'Remote_Server.py')
    ];
    for (const pyPath of scriptCandidates) {
      if (require('fs').existsSync(pyPath)) {
        console.log('Launching Core Server via python:', pyPath);
        spawn('python', [pyPath], { detached: true, stdio: 'ignore' }).unref();
        return { success: true };
      }
    }
    return { success: false, error: 'Could not find Remote_Server.exe or Remote_Server.py' };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

function ensureServerRunning() {
  const http = require('http');
  const req = http.get('http://127.0.0.1:5055/status', (res) => {
    if (res.statusCode === 200) {
      console.log('Remote_Server is already running');
    }
  });
  req.on('error', () => {
    console.log('Remote_Server is not running. Launching...');
    const candidatePaths = [
      path.join(app.getAppPath(), '..', 'Remote_Server.exe'),
      path.join(process.resourcesPath, 'Remote_Server.exe'),
      path.join(__dirname, '../../Remote_Server.exe'),
      path.join(__dirname, '../../server/Remote_Server.exe')
    ];
    for (const sPath of candidatePaths) {
      if (require('fs').existsSync(sPath)) {
        spawn(sPath, [], { detached: true, stdio: 'ignore' });
        break;
      }
    }
  });
}

app.whenReady().then(() => {
  ensureServerRunning();
  createWindow();
  createTray();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('before-quit', () => {
  app.isQuitting = true;
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
