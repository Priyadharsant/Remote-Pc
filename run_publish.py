import subprocess
import sys

notes = """### 🔋 Comprehensive Battery Optimization
- **Zero Idle Drain**: Removed permanent Android CPU `PARTIAL_WAKE_LOCK` and high-perf Wi-Fi lock from background services. The mobile device can now enter deep Doze mode and low-power sleep states undisturbed.
- **Adaptive Background Heartbeat**: Smart heartbeat interval adapts dynamically (15s on-screen, 30s screen-off, and exponential backoff up to 60s when PC is unreachable).
- **Throttled Background Sync Crawling**: Fallback SAF folder scanner throttled to 45s; instant media detection remains event-driven via `ContentObserver`.
- **PC Wi-Fi Multicast Throttling**: Increased UDP presence broadcast interval from 2s to 10s on the PC server, eliminating constant 2-second Wi-Fi DTIM radio wakeups across all local phones.
- **Desktop GUI Background Throttling**: Automatically drops polling interval from 3s to 12s when the desktop application is minimized or hidden.

### ⚡ Master Power Controls & Android Quick Settings Tile
- **Android Control Center Quick Settings Tile**: Direct 1-tap toggle from Android's notification shade / Control Center with live status feedback ("Connected • [PC Name]" / "Service OFF").
- **In-App Master Power Card**: Glowing status indicator and animated switch on Discovery and Remote Control screens.
- **Deep Power Down (0% Battery)**: Turning off master power completely terminates background services, releases sockets, and stops sync for total battery conservation."""

title = "Remote PC v3.3.0 - Battery Optimization & Master Power Controls"

cmd = [
    sys.executable,
    "publish_release.py",
    "--version", "v3.3.0",
    "--title", title,
    "--notes", notes,
    "--push-code"
]

print("Executing publish_release...")
res = subprocess.run(cmd)
sys.exit(res.returncode)
