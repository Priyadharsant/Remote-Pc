#!/usr/bin/env python3
"""
Remote PC Build Script for Linux (Ubuntu / Debian).
Builds the standalone Linux Core Server (ELF) and Desktop GUI (Electron).
"""

import os
import sys
import subprocess
import shutil
import time
import argparse

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(ROOT_DIR, "server")
DESKTOP_GUI_DIR = os.path.join(ROOT_DIR, "desktop-gui")

def safe_remove(file_path):
    if not os.path.exists(file_path):
        return
    for _ in range(5):
        try:
            if os.path.isdir(file_path):
                shutil.rmtree(file_path, ignore_errors=True)
            else:
                os.remove(file_path)
            return
        except Exception:
            time.sleep(0.5)

def get_latest_mtime(directory, extensions=None):
    latest = 0
    for root, _, files in os.walk(directory):
        if any(skip in root for skip in ("node_modules", ".git", "dist", "dist-electron", "__pycache__", ".venv")):
            continue
        for f in files:
            if extensions and not any(f.endswith(ext) for ext in extensions):
                continue
            fp = os.path.join(root, f)
            try:
                mtime = os.path.getmtime(fp)
                if mtime > latest:
                    latest = mtime
            except OSError:
                pass
    return latest

def should_rebuild_server(server_bin):
    if not os.path.exists(server_bin):
        return True, "Executable does not exist"
    bin_mtime = os.path.getmtime(server_bin)
    server_mtime = get_latest_mtime(SERVER_DIR, extensions=(".py", ".json", ".png", ".txt"))
    if server_mtime > bin_mtime:
        return True, "Server source files were modified"
    return False, "Server binary is already up to date"

def should_rebuild_gui(gui_bin):
    if not os.path.exists(gui_bin):
        return True, "Desktop GUI executable does not exist"
    bin_mtime = os.path.getmtime(gui_bin)
    src_dir = os.path.join(DESKTOP_GUI_DIR, "src")
    electron_dir = os.path.join(DESKTOP_GUI_DIR, "electron")
    gui_mtime = max(
        get_latest_mtime(src_dir),
        get_latest_mtime(electron_dir),
        os.path.getmtime(os.path.join(DESKTOP_GUI_DIR, "package.json")) if os.path.exists(os.path.join(DESKTOP_GUI_DIR, "package.json")) else 0
    )
    if gui_mtime > bin_mtime:
        return True, "Desktop GUI source files were modified"
    return False, "Desktop GUI bundle is already up to date"

def create_desktop_shortcut():
    desktop_dir = os.path.expanduser("~/.local/share/applications")
    os.makedirs(desktop_dir, exist_ok=True)
    icon_path = os.path.join(SERVER_DIR, "logo.png")
    gui_bin = os.path.join(DESKTOP_GUI_DIR, "dist-electron", "linux-unpacked", "desktop-gui")
    
    if os.path.exists(gui_bin):
        desktop_content = f"""[Desktop Entry]
Name=Remote PC
Comment=Remote PC Control Suite & Core Server
Exec="{gui_bin}" %U
Icon={icon_path}
Terminal=false
Type=Application
Categories=Utility;RemoteAccess;Network;
StartupWMClass=Remote PC GUI
"""
        desktop_file = os.path.join(desktop_dir, "remote-pc.desktop")
        with open(desktop_file, "w") as f:
            f.write(desktop_content)
        os.chmod(desktop_file, 0o755)
        print(f"✓ Installed desktop entry: {desktop_file}")

def build(args):
    start_total = time.time()

    # Kill running processes
    subprocess.run(["pkill", "-f", "Remote_Server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["pkill", "-f", "desktop-gui"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    server_bin = os.path.join(ROOT_DIR, "Remote_Server")
    gui_bin = os.path.join(DESKTOP_GUI_DIR, "dist-electron", "linux-unpacked", "desktop-gui")

    build_server = not (args.skip_server or args.gui_only)
    build_gui = not (args.skip_gui or args.server_only)

    if args.clean:
        print("🧹 Clean build requested: clearing previous Linux build artifacts...")
        safe_remove(os.path.join(ROOT_DIR, "build", "server_linux"))
        safe_remove(server_bin)
        safe_remove(os.path.join(DESKTOP_GUI_DIR, "dist-electron"))

    # 1. Server Build
    if build_server:
        needs_build, reason = True, "Forced build"
        if not args.clean and not args.force:
            needs_build, reason = should_rebuild_server(server_bin)

        if needs_build:
            print("\n" + "=" * 60)
            print(f" 🚀 Building 1/2: Remote PC Core Server for Linux ({reason})")
            print("=" * 60)
            t0 = time.time()
            safe_remove(server_bin)

            cmd_server = [
                sys.executable, "-m", "PyInstaller",
                "--noconfirm",
                "--onefile",
                "--windowed",
                "--name=Remote_Server",
                f"--icon={os.path.join(SERVER_DIR, 'logo.png')}",
                f"--add-data={os.path.join(SERVER_DIR, 'logo.png')}:.",
                f"--add-data={os.path.join(SERVER_DIR, 'logo.ico')}:.",
                f"--add-data={os.path.join(SERVER_DIR, 'config.json')}:.",
                f"--paths={SERVER_DIR}",
                "--hidden-import=uvicorn.logging",
                "--hidden-import=uvicorn.loops.auto",
                "--hidden-import=uvicorn.protocols.http.auto",
                "--hidden-import=uvicorn.protocols.websockets.auto",
                "--hidden-import=uvicorn.protocols.websockets.websockets_impl",
                "--hidden-import=uvicorn.protocols.websockets.websockets_sansio_impl",
                "--hidden-import=uvicorn.lifespan.on",
                "--hidden-import=pynput.keyboard._xorg",
                "--hidden-import=pynput.mouse._xorg",
                "--collect-all=mss",
                "--collect-all=sounddevice",
                "--collect-all=websockets",
                "--collect-all=platformdirs",
                "--copy-metadata=platformdirs",
                f"--distpath={ROOT_DIR}",
                f"--workpath={os.path.join(ROOT_DIR, 'build', 'server_linux')}",
                os.path.join(SERVER_DIR, "Remote_Server.py")
            ]

            res1 = subprocess.run(cmd_server)
            if res1.returncode != 0:
                print("❌ Core Server PyInstaller build failed!")
                sys.exit(res1.returncode)
            os.chmod(server_bin, 0o755)
            print(f"✓ Core Server ELF binary built in {time.time() - t0:.1f}s -> {server_bin}")
        else:
            print(f"⚡ Skipping Core Server build: {reason}")
    else:
        print("⏩ Skipped Core Server build by option")

    # 2. Desktop GUI Build
    if build_gui:
        needs_gui, gui_reason = True, "Forced build"
        if not args.clean and not args.force:
            needs_gui, gui_reason = should_rebuild_gui(gui_bin)

        if needs_gui:
            print("\n" + "=" * 60)
            print(f" 🎨 Building 2/2: Electron Desktop GUI for Linux ({gui_reason})")
            print("=" * 60)
            t0 = time.time()
            npm_cmd = "npm run pack:appimage" if args.appimage else "npm run build:gui:linux"
            env = dict(os.environ)
            local_bin = os.path.expanduser("~/.local/bin")
            if local_bin not in env.get("PATH", ""):
                env["PATH"] = f"{local_bin}:{env.get('PATH', '')}"

            res2 = subprocess.run(npm_cmd, shell=True, cwd=DESKTOP_GUI_DIR, env=env)
            if res2.returncode != 0:
                print("❌ Desktop GUI build failed!")
                sys.exit(res2.returncode)

            # Ensure execution permissions & convenience symlinks
            if os.path.exists(gui_bin):
                os.chmod(gui_bin, 0o755)
                link_path = os.path.join(ROOT_DIR, "Remote_PC_GUI")
                try:
                    if os.path.exists(link_path):
                        os.remove(link_path)
                    os.symlink(gui_bin, link_path)
                except Exception:
                    pass
            print(f"✓ Desktop GUI built in {time.time() - t0:.1f}s")
        else:
            print(f"⚡ Skipping Desktop GUI build: {gui_reason}")
    else:
        print("⏩ Skipped Desktop GUI build by option")

    create_desktop_shortcut()

    print("\n" + "=" * 60)
    print(f" 🎉 Ubuntu / Linux Build Complete in {time.time() - start_total:.1f}s!")
    print(f"   • Core Server: {server_bin}")
    print(f"   • Desktop GUI: {gui_bin}")
    print("=" * 60)

def main():
    parser = argparse.ArgumentParser(description="Build Remote PC Server and GUI for Ubuntu / Linux")
    parser.add_argument("--clean", action="store_true", help="Perform clean rebuild")
    parser.add_argument("--force", action="store_true", help="Force rebuild all components")
    parser.add_argument("--server-only", action="store_true", help="Build only the Core Server")
    parser.add_argument("--gui-only", action="store_true", help="Build only the Desktop GUI")
    parser.add_argument("--skip-server", action="store_true", help="Skip Core Server build")
    parser.add_argument("--skip-gui", action="store_true", help="Skip Desktop GUI build")
    parser.add_argument("--appimage", action="store_true", help="Package Desktop GUI as AppImage")
    args = parser.parse_args()

    build(args)

if __name__ == "__main__":
    main()
