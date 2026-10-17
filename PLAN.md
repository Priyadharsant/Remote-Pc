# Remote PC - Comprehensive Architecture & Implementation Plan

> **Project Root**: `d:\Projects\Remote-Pc`  
> **Target Desktop GUI**: Vite + React 19 + TypeScript + TailwindCSS v4 + Electron (`desktop-gui/`)  
> **Mobile Client**: Native Android Kotlin + Jetpack Compose (`client/`)  
> **Backend Service**: Python FastAPI + Zero-Lag UDP Service (`server/Remote_Server.py`)

---

## 1. Executive Summary & Objective

The objective of this project is to provide a complete, ultra-low latency, modern wireless remote control suite between an Android phone and a Windows PC. The desktop PC application is being upgraded from CustomTkinter (`Remote_GUI.py`) to an **ultra-modern, frameless Electron + React + TailwindCSS application** (`desktop-gui/`), providing:

1. **Mobile Controls** (Volume, Brightness, Play/Pause Media, Scrcpy Screen Mirroring, Power Actions).
2. **File Manager** (Direct Mobile Storage Explorer for Photos/Videos/Downloads, PC Downloads folder access, and drag-and-drop file sharing).
3. **Settings** (Auto-Connect setup, Windows boot startup, theme toggle, firewall and trusted device management).
4. **Server Details** (Live LAN IP, Port 5055, QR Code & PIN Code pairing, live Phone status, and PC hardware metrics).
5. **Smart Extras**:
   - Interactive Media Mini-Player widget.
   - Frameless custom glassmorphic titlebar with minimize to Windows System Tray.
   - Bidirectional real-time clipboard sharing.
   - Drag-and-Drop file transfer directly from Windows into the Electron window.

---

## 2. System Architecture & Communication Flow

```mermaid
graph TD
    subgraph Android Phone
        A1[Jetpack Compose UI] --> A2[VideoStreamServer :5056]
        A1 --> A3[RemoteBackgroundService]
        A3 -->|Auto-Connect Poll & Notifs| B1
    end

    subgraph PC Backend (server/Remote_Server.py)
        B1[FastAPI Server :5055]
        B2[UDP Input Listener :9999]
        B3[PyAutoGUI & ctypes Controller]
        B1 --> B2
        B1 --> B3
    end

    subgraph PC Electron GUI (desktop-gui/)
        C1[Frameless Electron Shell]
        C2[Preload IPC Bridge]
        C3[React 19 + Tailwind v4 UI]
        C1 --> C2
        C2 --> C3
        C3 -->|HTTP REST / WebSocket| B1
        C3 -->|Local Storage Proxy| A2
        C1 -->|Launch Mirroring| D1[scrcpy]
        C1 -->|System Tray| D2[Windows Taskbar Tray]
    end
```

---

## 3. Status of Completed Tasks

| Area | Component | Description | Status |
| :--- | :--- | :--- | :--- |
| **PC Server** | `Remote_Server.py` | Fixed `TypeError: require_auth()` in `/fs/stream` and `/fs/download` by passing `request=request`. | **Completed** |
| **PC Server** | `Remote_Server.py` | Added `/phone/storage/list` and `/phone/storage/download` proxy endpoints. | **Completed** |
| **Android Client** | `UiTheme.kt` & `MainActivity.kt` | Fixed invisible white keyboard keys in Light theme with tactile `KeyBackground`, `KeyBorder`, and `TextPrimary`. | **Completed** |
| **Android Client** | `MainActivity.kt` | Added IP & Hostname deduplication to discovery routine so duplicate PCs do not appear. | **Completed** |
| **Android Client** | `RemoteBackgroundService.kt` | Continuous background heartbeat loop & real-time connected notification in Android Notification Center. | **Completed** |
| **Android Client** | `MainActivity.kt` (`VideoStreamServer`) | Extended port `5056` server to support `/storage/list` and `/storage/file` for PC direct file access. | **Completed** |
| **Electron Setup** | `desktop-gui/` | Scaffolding complete with Vite 8, React 19, TypeScript, TailwindCSS v4, Lucide-React, and Electron. | **Completed** |
| **Electron Shell** | `main.cjs` & `preload.cjs` | Frameless window, System Tray menu, window controls, and IPC bridge. | **Completed** |
| **Core Screens** | `desktop-gui/src/` | `TitleBar.tsx`, `Sidebar.tsx`, `ServerDetails.tsx`, `api.ts`, and `types.ts` implemented. | **Completed** |

---

## 4. Detailed Component Specifications (React + Electron GUI)

### 4.1. Screen 1: Server Details (`ServerDetails.tsx`) - *Implemented*
- **Live Status Badge**: Indicates whether FastAPI is active on `http://127.0.0.1:5055`.
- **Host & IP / Port Display**: Shows PC Hostname, Local IP, and Port.
- **Pairing Credentials**:
  - Base64 QR code rendering for one-tap mobile camera pairing.
  - Quick PIN code pill with one-click copy.
- **Connected Device Card**: Displays phone model (e.g., Vivo V2437), IP address, and instant toast message sender.
- **Hardware Telemetry Gauges**: Real-time progress bars for CPU Load %, RAM Memory %, and Battery %.
- **Pending Pairing Authorizations Banner**: Displays incoming mobile pairing requests with **Authorize & Auto-Connect** and **Deny** buttons.

### 4.2. Screen 2: Mobile Controls (`MobileControls.tsx`) - *In Progress*
- **Audio & Display Level Controls**:
  - Master volume slider (0–100%) with volume percentage feedback and Mute toggle.
  - Brightness adjustment slider (0–100%).
- **Interactive Media Mini-Player**:
  - Playback deck: Previous Track, Play / Pause toggle, Next Track.
  - Volume slider and track info simulation.
- **Mobile Screen Mirroring (Scrcpy)**:
  - 1-click **Launch Mobile Screen Mirror** button calling Electron IPC to invoke `scrcpy --tcpip=<phone_ip>`.
- **System Action Deck**:
  - Show Desktop (`/desktop`).
  - Lock Windows PC (`/lock`).
  - Sleep PC (`/sleep`) with confirmation modal.
  - Restart PC (`/restart`) with confirmation modal.
  - Shutdown PC (`/shutdown`) with safety confirmation.
- **Bidirectional Clipboard Sync**:
  - Input field to type or paste text and send directly to the phone's clipboard.
  - Quick feedback toast on successful transfer.

### 4.3. Screen 3: File Manager (`FileManager.tsx`) - *In Progress*
- **Mobile Storage Explorer**:
  - Categorized tabs: **All**, **Photos**, **Videos**, **Music**, **Downloads**.
  - Fetches files directly from phone port `5056` via `/phone/storage/list`.
  - Item card displays filename, formatted file size (MB/KB), and modification timestamp.
  - Action buttons:
    - `Save to PC`: Downloads file into `Downloads/From_Phone/` on Windows.
    - `Open on PC`: Downloads file and immediately opens with default Windows handler via `shell.openPath`.
- **Drag-and-Drop Dropzone**:
  - Allows dragging any file from Windows Explorer directly into the GUI to send to the phone.
- **Quick Links**:
  - **Open Downloads Folder** button (`os.homedir()/Downloads/From_Phone`).

### 4.4. Screen 4: Settings (`Settings.tsx`) - *In Progress*
- **Auto-Connect Handshake**:
  - Toggle to automatically approve and link known devices on the local Wi-Fi.
- **Windows Boot Startup**:
  - Toggle to auto-start Remote PC Server on system boot.
- **Theme Selection**:
  - Toggle between **Dark Glassmorphic** (`#0B0F19`) and **Light Mode**.
- **Trusted Devices List**:
  - Lists authorized phones with Name, IP, and Auto-Connect status.
  - Includes a `Remove / Revoke` button calling `/pair/revoke`.
- **Network & Firewall Status**:
  - Status indicators for Port 5055 Private Network rule, Local Network Only isolation, and TLS encryption.

---

## 5. Implementation Roadmap & Steps

### Step 1: Finish React GUI Components
- [x] Create `desktop-gui/src/types.ts`
- [x] Create `desktop-gui/src/api.ts`
- [x] Create `desktop-gui/src/components/TitleBar.tsx`
- [x] Create `desktop-gui/src/components/Sidebar.tsx`
- [x] Create `desktop-gui/src/components/ServerDetails.tsx`
- [x] Create `desktop-gui/src/components/MobileControls.tsx`
- [x] Create `desktop-gui/src/components/FileManager.tsx`
- [x] Create `desktop-gui/src/components/Settings.tsx`
- [x] Wire up `desktop-gui/src/App.tsx` with polling and state management

### Step 2: Test & Verify Electron App Locally
- [x] Build React bundle (`npm run build`) in `desktop-gui/`.
- [ ] Run `npm run electron:dev` in `desktop-gui/`.
- [ ] Verify window controls (minimize, maximize, close to tray).
- [ ] Verify communication with `server/Remote_Server.py` on `http://127.0.0.1:5055`.
- [ ] Verify Mobile Controls (sliders, media controls, system actions).
- [ ] Verify Mobile Storage Explorer and file download/open functionality.

### Step 3: Integration with Windows Installer
- [x] Update `Remote_Server.py` `/gui/show` endpoint to launch the built Electron app executable or fall back cleanly.
- [ ] Update Inno Setup script (`installer.iss`) to include the `desktop-gui` production bundle.

---

## 6. Execution Instructions for User

To run the React + Electron Desktop GUI in development mode:
```powershell
cd d:\Projects\Remote-Pc\desktop-gui
npm run electron:dev
```

To build the production installer/bundle:
```powershell
cd d:\Projects\Remote-Pc\desktop-gui
npm run build
```
