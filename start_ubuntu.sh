#!/usr/bin/env bash
# ==============================================================================
# Remote PC Launcher for Ubuntu / Linux
# Launches the Core Server and Electron GUI
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

export PATH="$HOME/.local/bin:$PATH"

# Auto-detect display & xauthority for Wayland / X11
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

MODE="${1:-all}"

echo "=================================================="
echo " 🌐 Launching Remote PC for Ubuntu"
echo "    Display: $DISPLAY"
echo "    Mode:    $MODE"
echo "=================================================="

# 1. Start Server
start_server() {
    # Check if server is already running on port 5055
    if curl -s http://127.0.0.1:5055/status >/dev/null 2>&1; then
        echo "✓ Remote_Server is already running on port 5055"
        return 0
    fi

    echo "🚀 Starting Remote_Server..."
    if [ -f "$SCRIPT_DIR/Remote_Server" ]; then
        chmod +x "$SCRIPT_DIR/Remote_Server"
        "$SCRIPT_DIR/Remote_Server" >/dev/null 2>&1 &
        SERVER_PID=$!
    else
        python3 server/Remote_Server.py >/dev/null 2>&1 &
        SERVER_PID=$!
    fi

    # Wait for server to respond
    for i in {1..30}; do
        if curl -s http://127.0.0.1:5055/status >/dev/null 2>&1; then
            echo "✓ Core Server ready at http://127.0.0.1:5055 (PID: $SERVER_PID)"
            return 0
        fi
        sleep 0.2
    done
    echo "⚠️ Core Server launched in background."
}

# 2. Start GUI
start_gui() {
    echo "🎨 Starting Remote PC GUI..."
    GUI_BIN="$SCRIPT_DIR/desktop-gui/dist-electron/linux-unpacked/desktop-gui"
    if [ -f "$GUI_BIN" ]; then
        chmod +x "$GUI_BIN"
        "$GUI_BIN" --no-sandbox "$@"
    else
        echo "Unpacked GUI binary not found. Launching via Electron..."
        cd "$SCRIPT_DIR/desktop-gui"
        npm run electron:start -- --no-sandbox
    fi
}

case "$MODE" in
    --server-only|server)
        start_server
        echo "Core Server running. Press Ctrl+C to stop."
        wait
        ;;
    --gui-only|gui)
        start_gui "${@:2}"
        ;;
    all|*)
        start_server
        start_gui "${@:2}"
        ;;
esac
