#!/usr/bin/env bash
# ==============================================================================
# Remote PC Installer for Ubuntu / Debian Linux
# Installs Core Server and Desktop GUI to user space (~/.local/share/remote-pc)
# Adds system app menu shortcut, desktop icon, and 'remote-pc' CLI command.
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="$HOME/.local/share/remote-pc"
BIN_DIR="$HOME/.local/bin"
APPLICATIONS_DIR="$HOME/.local/share/applications"
ICONS_DIR="$HOME/.local/share/icons/hicolor/512x512/apps"
PIXMAPS_DIR="$HOME/.local/share/pixmaps"
DESKTOP_DIR="$HOME/Desktop"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"

echo "=========================================================="
echo " 🚀 Installing Remote PC Suite on Ubuntu"
echo "    Destination: $INSTALL_DIR"
echo "=========================================================="

# 1. Ensure required binaries exist before installing
if [ ! -f "$SCRIPT_DIR/Remote_Server" ]; then
    echo "⚠️  Remote_Server ELF binary not found. Building now..."
    export PATH="$HOME/.local/bin:$PATH"
    python3 "$SCRIPT_DIR/build_linux.py" --server-only
fi

GUI_UNPACKED="$SCRIPT_DIR/desktop-gui/dist-electron/linux-unpacked"
if [ ! -f "$GUI_UNPACKED/desktop-gui" ]; then
    echo "⚠️  Desktop GUI unpacked binary not found. Building now..."
    export PATH="$HOME/.local/bin:$PATH"
    python3 "$SCRIPT_DIR/build_linux.py" --gui-only
fi

# 2. Stop any existing running instances
echo "🛑 Stopping any running instances..."
pkill -f "$INSTALL_DIR/Remote_Server" 2>/dev/null || true
pkill -f "$INSTALL_DIR/desktop-gui/desktop-gui" 2>/dev/null || true

# 3. Create target directories
echo "📁 Creating application directories..."
mkdir -p "$INSTALL_DIR"
mkdir -p "$BIN_DIR"
mkdir -p "$APPLICATIONS_DIR"
mkdir -p "$ICONS_DIR"
mkdir -p "$PIXMAPS_DIR"
mkdir -p "$SYSTEMD_USER_DIR"

# 4. Copy Server & GUI files
echo "📦 Copying files to $INSTALL_DIR..."
cp -f "$SCRIPT_DIR/Remote_Server" "$INSTALL_DIR/Remote_Server"
chmod +x "$INSTALL_DIR/Remote_Server"

# Copy GUI bundle
rm -rf "$INSTALL_DIR/desktop-gui"
cp -r "$GUI_UNPACKED" "$INSTALL_DIR/desktop-gui"
chmod +x "$INSTALL_DIR/desktop-gui/desktop-gui"

# Copy server assets (logo, sound, controllers)
mkdir -p "$INSTALL_DIR/server"
if [ -d "$SCRIPT_DIR/server" ]; then
    cp -r "$SCRIPT_DIR/server"/* "$INSTALL_DIR/server/" 2>/dev/null || true
fi

# Preserve or create unconfigured config.json
if [ ! -f "$INSTALL_DIR/config.json" ]; then
    cat << 'EOF' > "$INSTALL_DIR/config.json"
{
  "name": "",
  "is_configured": false,
  "strict_auto_connect": true,
  "trusted_tokens": {},
  "trusted_devices": []
}
EOF
fi
cp -f "$INSTALL_DIR/config.json" "$INSTALL_DIR/server/config.json" 2>/dev/null || true

# Copy logo icon
ICON_SRC="$SCRIPT_DIR/server/logo.png"
if [ -f "$ICON_SRC" ]; then
    cp "$ICON_SRC" "$ICONS_DIR/remote-pc.png"
    cp "$ICON_SRC" "$PIXMAPS_DIR/remote-pc.png"
    cp "$ICON_SRC" "$INSTALL_DIR/logo.png"
fi

# 5. Create launcher script inside INSTALL_DIR
cat << 'EOF' > "$INSTALL_DIR/launcher.sh"
#!/usr/bin/env bash
set -e
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

# Display & Auth detection
if [ -z "$DISPLAY" ]; then
    export DISPLAY=":0"
fi
if [ -z "$XAUTHORITY" ]; then
    MUTTER_AUTH=$(ls /run/user/$(id -u)/.mutter-Xwaylandauth* 2>/dev/null | head -n 1)
    if [ -n "$MUTTER_AUTH" ]; then
        export XAUTHORITY="$MUTTER_AUTH"
    elif [ -f "$HOME/.Xauthority" ]; then
        export XAUTHORITY="$HOME/.Xauthority"
    fi
fi

CMD="${1:-all}"

start_server() {
    if curl -s http://127.0.0.1:5055/status >/dev/null 2>&1; then
        echo "✓ Remote PC Server is already active on port 5055"
        return 0
    fi
    echo "🚀 Starting Remote PC Core Server..."
    "$APP_DIR/Remote_Server" >/dev/null 2>&1 &
    local SPID=$!
    for i in {1..30}; do
        if curl -s http://127.0.0.1:5055/status >/dev/null 2>&1; then
            echo "✓ Core Server ready (PID: $SPID)"
            return 0
        fi
        sleep 0.2
    done
    echo "⚠️ Core Server running in background."
}

stop_all() {
    echo "🛑 Stopping Remote PC..."
    pkill -f "$APP_DIR/Remote_Server" 2>/dev/null || true
    pkill -f "$APP_DIR/desktop-gui/desktop-gui" 2>/dev/null || true
    echo "✓ Stopped."
}

case "$CMD" in
    stop)
        stop_all
        exit 0
        ;;
    status)
        curl -s http://127.0.0.1:5055/status || echo "Remote PC Server is stopped"
        echo ""
        exit 0
        ;;
    server|--server)
        start_server
        echo "Remote PC Server running. Press Ctrl+C to stop."
        wait
        ;;
    gui|--gui)
        start_server
        exec "$APP_DIR/desktop-gui/desktop-gui" --no-sandbox "${@:2}"
        ;;
    all|*)
        start_server
        exec "$APP_DIR/desktop-gui/desktop-gui" --no-sandbox "${@:2}"
        ;;
esac
EOF
chmod +x "$INSTALL_DIR/launcher.sh"

# 6. Install CLI command into ~/.local/bin/remote-pc
cat << EOF > "$BIN_DIR/remote-pc"
#!/usr/bin/env bash
exec "$INSTALL_DIR/launcher.sh" "\$@"
EOF
chmod +x "$BIN_DIR/remote-pc"

# 7. Create and install .desktop entry for Application Launcher
cat << EOF > "$APPLICATIONS_DIR/remote-pc.desktop"
[Desktop Entry]
Version=1.0
Type=Application
Name=Remote PC
GenericName=Remote PC Suite
Comment=Wireless PC Control, Remote Desktop & Sensor Suite
Exec="$INSTALL_DIR/launcher.sh" gui %U
Icon=$INSTALL_DIR/logo.png
Terminal=false
StartupNotify=true
StartupWMClass=Remote PC GUI
Categories=Utility;RemoteAccess;Network;
Keywords=remote;touchpad;control;mouse;pc;
EOF
chmod +x "$APPLICATIONS_DIR/remote-pc.desktop"

# 8. Create Desktop Shortcut if ~/Desktop exists
if [ -d "$DESKTOP_DIR" ]; then
    cp -f "$APPLICATIONS_DIR/remote-pc.desktop" "$DESKTOP_DIR/Remote PC.desktop"
    chmod +x "$DESKTOP_DIR/Remote PC.desktop"
    # Mark trusted in GNOME if gio is available
    if command -v gio >/dev/null 2>&1; then
        gio set "$DESKTOP_DIR/Remote PC.desktop" metadata::trusted true 2>/dev/null || true
    fi
fi

# 9. Create Systemd User Service (for headless background auto-start if desired)
cat << EOF > "$SYSTEMD_USER_DIR/remote-pc.service"
[Unit]
Description=Remote PC Core Server
After=network.target sound.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/Remote_Server
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

# 10. Create Uninstaller
cat << 'EOF' > "$INSTALL_DIR/uninstall.sh"
#!/usr/bin/env bash
echo "Uninstalling Remote PC from Ubuntu..."
pkill -f "$HOME/.local/share/remote-pc" 2>/dev/null || true
systemctl --user stop remote-pc.service 2>/dev/null || true
systemctl --user disable remote-pc.service 2>/dev/null || true
rm -rf "$HOME/.local/share/remote-pc"
rm -f "$HOME/.local/bin/remote-pc"
rm -f "$HOME/.local/share/applications/remote-pc.desktop"
rm -f "$HOME/Desktop/Remote PC.desktop"
rm -f "$HOME/.local/share/icons/hicolor/512x512/apps/remote-pc.png"
rm -f "$HOME/.local/share/pixmaps/remote-pc.png"
rm -f "$HOME/.config/systemd/user/remote-pc.service"
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
fi
echo "✓ Remote PC has been completely removed."
EOF
chmod +x "$INSTALL_DIR/uninstall.sh"

# Link uninstaller in project root
cp -f "$INSTALL_DIR/uninstall.sh" "$SCRIPT_DIR/uninstall_ubuntu.sh"

# 11. Update system desktop & icon caches
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APPLICATIONS_DIR" 2>/dev/null || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
fi

echo ""
echo "=========================================================="
echo " 🎉 Remote PC Successfully Installed on Ubuntu!"
echo "=========================================================="
echo " • Location:      $INSTALL_DIR"
echo " • CLI Command:   remote-pc (e.g. 'remote-pc' or 'remote-pc status')"
echo " • App Menu:      'Remote PC' is available in Ubuntu Application grid"
echo " • Desktop Icon:  Available on your Desktop"
echo " • Auto-start:    To start server on login: systemctl --user enable --now remote-pc"
echo " • Uninstaller:   $INSTALL_DIR/uninstall.sh"
echo "=========================================================="
