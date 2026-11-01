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
