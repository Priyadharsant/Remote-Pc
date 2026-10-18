const { app, BrowserWindow, ipcMain, Tray, Menu, shell } = require('electron');
const path = require('path');
const os = require('os');
const https = require('https');
const http = require('http');
const fs = require('fs');
const { spawn, exec } = require('child_process');

let mainWindow = null;
let tray = null;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

// ==========================================
// Multi-Stream High-Speed Range Downloader
// Modeled after Finance Application
// ==========================================
function resolveUrlAndMetadata(url, maxRedirects = 8) {
  return new Promise((resolve, reject) => {
    function follow(currentUrl, depth) {
      if (depth > maxRedirects) return reject(new Error('Too many redirects'));
      const parsed = new URL(currentUrl);
      const client = parsed.protocol === 'https:' ? https : http;

      const req = client.request(
        currentUrl,
        {
          method: 'HEAD',
          headers: {
            'User-Agent': 'Remote-PC-AutoUpdater/3.0',
            Accept: '*/*',
          },
        },
        (res) => {
          if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
            const nextUrl = new URL(res.headers.location, currentUrl).toString();
            res.resume();
            return follow(nextUrl, depth + 1);
          }

          const contentLength = parseInt(res.headers['content-length'] || '0', 10);
          const acceptRanges = String(res.headers['accept-ranges'] || '').toLowerCase().includes('bytes');
          res.resume();
          resolve({
            finalUrl: currentUrl,
            contentLength,
            acceptRanges,
          });
        }
      );

      req.on('error', reject);
      req.setTimeout(10000, () => {
        req.destroy();
        reject(new Error('Metadata request timed out'));
      });
      req.end();
    }

    follow(url, 0);
  });
}

function downloadMultiStream(finalUrl, destPath, totalBytes, concurrency = 4, onProgress) {
  return new Promise((resolve, reject) => {
    const fd = fs.openSync(destPath, 'w');
    try {
      fs.ftruncateSync(fd, totalBytes);
    } catch (_) {}

    const chunkSize = Math.ceil(totalBytes / concurrency);
    let transferred = 0;
    let lastReportTime = 0;
    let lastPercent = -1;
    const startTime = Date.now();
    let isAborted = false;
    const activeReqs = [];

    const cleanup = (err) => {
      if (isAborted) return;
      isAborted = true;
      activeReqs.forEach((r) => {
        try { r.destroy(); } catch (_) {}
      });
      try { fs.closeSync(fd); } catch (_) {}
      if (err) {
        fs.unlink(destPath, () => {});
        reject(err);
      }
    };

    const tasks = [];
    for (let i = 0; i < concurrency; i++) {
      const start = i * chunkSize;
      const end = i === concurrency - 1 ? totalBytes - 1 : (i + 1) * chunkSize - 1;

      tasks.push(
        new Promise((taskResolve, taskReject) => {
          let currentOffset = start;
          const parsed = new URL(finalUrl);
          const client = parsed.protocol === 'https:' ? https : http;

          const req = client.get(
            finalUrl,
            {
              headers: {
                Range: `bytes=${start}-${end}`,
                'User-Agent': 'Remote-PC-AutoUpdater/3.0',
                Accept: 'application/octet-stream, */*',
              },
            },
            (res) => {
              if (res.statusCode !== 206 && res.statusCode !== 200) {
                return taskReject(new Error(`Chunk stream ${i} failed: HTTP ${res.statusCode}`));
              }

              res.on('data', (chunk) => {
                if (isAborted) return;
                const writePos = currentOffset;
                currentOffset += chunk.length;
                transferred += chunk.length;

                fs.write(fd, chunk, 0, chunk.length, writePos, (writeErr) => {
                  if (writeErr) {
                    cleanup(writeErr);
                    return taskReject(writeErr);
                  }
                });

                const now = Date.now();
                const percent = Math.round((transferred / totalBytes) * 100);
                if (onProgress && (percent !== lastPercent || now - lastReportTime > 150 || percent >= 100)) {
                  lastPercent = percent;
                  lastReportTime = now;
                  const elapsedSec = (now - startTime) / 1000 || 0.001;
                  const bytesPerSec = transferred / elapsedSec;
                  const mbps = (bytesPerSec / (1024 * 1024)).toFixed(1);
                  onProgress({
                    percent,
                    transferred,
                    total: totalBytes,
                    speed: `${mbps} MB/s`,
                    streams: concurrency,
                  });
                }
              });

              res.on('end', () => taskResolve());
              res.on('error', (err) => {
                cleanup(err);
                taskReject(err);
              });
            }
          );

          req.on('error', (err) => {
            cleanup(err);
            taskReject(err);
          });
          req.setTimeout(60000, () => {
            req.destroy();
            taskReject(new Error(`Stream ${i} timed out`));
          });
          activeReqs.push(req);
        })
      );
    }

    Promise.all(tasks)
      .then(() => {
        try {
          fs.closeSync(fd);
        } catch (_) {}
        resolve(destPath);
      })
      .catch((err) => cleanup(err));
  });
}

function downloadSingleStream(url, destPath, onProgress) {
  return new Promise((resolve, reject) => {
    function requestWithRedirect(currentUrl, redirectCount = 0) {
      if (redirectCount > 8) {
        return reject(new Error('Too many redirects'));
      }

      const client = currentUrl.startsWith('https://') ? https : http;
      const startTime = Date.now();
      const req = client.get(
        currentUrl,
        {
          headers: {
            'User-Agent': 'Remote-PC-AutoUpdater/3.0',
            Accept: 'application/octet-stream, */*',
          },
        },
        (res) => {
          if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
            res.resume();
            return requestWithRedirect(res.headers.location, redirectCount + 1);
          }

          if (res.statusCode !== 200) {
            res.resume();
            return reject(new Error(`Download failed with HTTP ${res.statusCode}`));
          }

          const totalBytes = parseInt(res.headers['content-length'] || '0', 10);
          let receivedBytes = 0;
          const fileStream = fs.createWriteStream(destPath, { highWaterMark: 2 * 1024 * 1024 });
          let lastReportTime = 0;
          let lastPercent = -1;

          res.on('data', (chunk) => {
            receivedBytes += chunk.length;
            const percent = totalBytes > 0 ? Math.round((receivedBytes / totalBytes) * 100) : 0;
            const now = Date.now();
            if (onProgress && (percent !== lastPercent || now - lastReportTime > 150 || percent === 100)) {
              lastPercent = percent;
              lastReportTime = now;
              const elapsedSec = (now - startTime) / 1000 || 0.001;
              const mbps = (receivedBytes / (1024 * 1024) / elapsedSec).toFixed(1);
              onProgress({
                percent,
                transferred: receivedBytes,
                total: totalBytes,
                speed: `${mbps} MB/s`,
                streams: 1,
              });
            }
          });

          res.pipe(fileStream);

          fileStream.on('finish', () => {
            fileStream.close(() => resolve(destPath));
          });

          fileStream.on('error', (err) => {
            fs.unlink(destPath, () => {});
            reject(err);
          });
        }
      );

      req.on('error', (err) => {
        fs.unlink(destPath, () => {});
        reject(err);
      });
    }

    requestWithRedirect(url);
  });
}

async function downloadFile(url, destPath, onProgress) {
  try {
    const meta = await resolveUrlAndMetadata(url);
    if (meta.acceptRanges && meta.contentLength > 5 * 1024 * 1024) {
      console.log(`[AutoUpdater] Fast Multi-Stream active (4 streams, total: ${(meta.contentLength / (1024 * 1024)).toFixed(1)} MB)`);
      return await downloadMultiStream(meta.finalUrl, destPath, meta.contentLength, 4, onProgress);
    }
  } catch (err) {
    console.warn('[AutoUpdater] Multi-stream check notice:', err.message, 'Falling back to single stream...');
  }
  return await downloadSingleStream(url, destPath, onProgress);
}

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
  if (url && typeof url === 'string' && (url.startsWith('http://') || url.startsWith('https://'))) {
    await shell.openExternal(url);
    return true;
  }
  return false;
});

ipcMain.handle('get-app-version', () => {
  return app.getVersion();
});

// IPC handler for 1-click in-app auto update download & silent/direct execution
ipcMain.handle('download-and-install-update', async (event, { url, version }) => {
  try {
    if (!url) throw new Error('No download URL provided');
    const safeVersion = String(version || 'latest').replace(/[^a-zA-Z0-9._-]/g, '');
    const tempDir = app.getPath('temp');
    const installerFilename = `Remote_PC_Setup_${safeVersion}.exe`;
    const destPath = path.join(tempDir, installerFilename);

    console.log(`[AutoUpdater] Starting multi-stream download: ${url} -> ${destPath}`);

    // Stream download with live progress events
    await downloadFile(url, destPath, (progress) => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('update-download-progress', progress);
      }
    });

    const stats = fs.statSync(destPath);
    if (stats.size === 0) {
      throw new Error('Downloaded installer file is empty');
    }

    console.log(`[AutoUpdater] Download completed (${(stats.size / (1024 * 1024)).toFixed(1)} MB). Launching Inno Setup...`);

    // Launch Inno Setup installer (/SILENT /FORCECLOSEAPPLICATIONS /CLOSEAPPLICATIONS) so user doesn't need to manually click Next, Next
    const updaterScript = path.join(tempDir, `remotepc_update_${Date.now()}.bat`);
    const scriptContent = [
      `@echo off`,
      `taskkill /F /IM "Remote_Server.exe" /T >nul 2>&1`,
      `taskkill /F /IM "Remote PC GUI.exe" /T >nul 2>&1`,
      `taskkill /F /IM "electron.exe" /T >nul 2>&1`,
      `timeout /t 1 /nobreak >nul`,
      `start "" "${destPath}" /SILENT /FORCECLOSEAPPLICATIONS /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS=no`,
      `del "%~f0"`,
    ].join('\r\n');

    fs.writeFileSync(updaterScript, scriptContent, 'utf-8');

    const updaterProc = spawn('cmd.exe', ['/c', updaterScript], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
    });
    updaterProc.unref();

    // Close current Electron application immediately so installer can overwrite files without lock
    setTimeout(() => {
      app.isQuitting = true;
      app.exit(0);
    }, 300);

    return { success: true };
  } catch (err) {
    console.error('[AutoUpdater] Download/install error:', err);
    return { success: false, error: err.message };
  }
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
      if (fs.existsSync(sPath)) {
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
      if (fs.existsSync(pyPath)) {
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
      if (fs.existsSync(sPath)) {
        spawn(sPath, [], { detached: true, stdio: 'ignore' });
        break;
      }
    }
  });
}

app.name = 'Remote PC GUI';

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
