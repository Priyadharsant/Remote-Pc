import os
import sys
import subprocess
import shutil
import time
import argparse

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(ROOT_DIR, "server")
DESKTOP_GUI_DIR = os.path.join(ROOT_DIR, "desktop-gui")
VENV_PYTHON = os.path.join(SERVER_DIR, ".venv", "Scripts", "python.exe")

def find_iscc():
    on_path = shutil.which("ISCC.exe") or shutil.which("ISCC")
    if on_path:
        return on_path
    candidates = [
        r"C:\Users\priya\AppData\Local\Programs\Inno Setup 6\ISCC.exe",
        r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        r"C:\Program Files\Inno Setup 6\ISCC.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"),
        os.path.expandvars(r"%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"),
        os.path.expandvars(r"%ProgramFiles%\Inno Setup 6\ISCC.exe"),
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None

ISCC_PATH = find_iscc()

def safe_remove(file_path):
    if not os.path.exists(file_path):
        return
    for _ in range(5):
        try:
            os.remove(file_path)
            return
        except PermissionError:
            subprocess.run(["taskkill", "/F", "/IM", os.path.basename(file_path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1.0)
    try:
        old_path = file_path + f".old.{int(time.time())}"
        os.rename(file_path, old_path)
    except Exception:
        pass

def get_latest_mtime(directory, extensions=None):
    latest = 0
    for root, _, files in os.walk(directory):
        if "node_modules" in root or ".git" in root or "dist" in root or "dist-electron" in root or "__pycache__" in root or ".venv" in root:
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

def should_rebuild_server(server_exe):
    if not os.path.exists(server_exe):
        return True, "Executable does not exist"
    exe_mtime = os.path.getmtime(server_exe)
    server_mtime = get_latest_mtime(SERVER_DIR, extensions=(".py", ".json", ".ico", ".png", ".txt"))
    if server_mtime > exe_mtime:
        return True, "Server source files were modified"
    return False, "Server binary is already up to date"

def should_rebuild_gui(gui_exe):
    if not os.path.exists(gui_exe):
        return True, "Desktop GUI executable does not exist"
    exe_mtime = os.path.getmtime(gui_exe)
    src_dir = os.path.join(DESKTOP_GUI_DIR, "src")
    electron_dir = os.path.join(DESKTOP_GUI_DIR, "electron")
    gui_mtime = max(
        get_latest_mtime(src_dir),
        get_latest_mtime(electron_dir),
        os.path.getmtime(os.path.join(DESKTOP_GUI_DIR, "package.json")) if os.path.exists(os.path.join(DESKTOP_GUI_DIR, "package.json")) else 0
    )
    if gui_mtime > exe_mtime:
        return True, "Desktop GUI source files were modified"
    return False, "Desktop GUI bundle is already up to date"

def build(args):
    start_total = time.time()

    # Kill any running binaries
    subprocess.run(["taskkill", "/F", "/IM", "Remote_Server.exe"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["taskkill", "/F", "/IM", "Remote PC GUI.exe"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.5)

    python_exe = VENV_PYTHON if os.path.exists(VENV_PYTHON) else sys.executable
    server_exe = os.path.join(ROOT_DIR, "Remote_Server.exe")
    gui_exe = os.path.join(DESKTOP_GUI_DIR, "dist-electron", "win-unpacked", "Remote PC GUI.exe")

    # Determine what to build
    build_server = not (args.skip_server or args.gui_only or args.installer_only)
    build_gui = not (args.skip_gui or args.server_only or args.installer_only)
    build_installer = not (args.server_only or args.gui_only)

    if args.clean:
        print("🧹 Clean build requested: clearing previous build cache...")
        build_dir = os.path.join(ROOT_DIR, "build")
        if os.path.exists(build_dir):
            shutil.rmtree(build_dir, ignore_errors=True)
        safe_remove(server_exe)

    # 1. Server Build
    if build_server:
        needs_build, reason = True, "Forced build"
        if not args.clean and not args.force:
            needs_build, reason = should_rebuild_server(server_exe)

        if needs_build:
            print("\n" + "=" * 60)
            print(f" 🚀 Building 1/3: Remote PC Core Server ({reason})")
            print("=" * 60)
            t0 = time.time()
            safe_remove(server_exe)
            cmd_server = [
                python_exe, "-m", "PyInstaller",
                "--noconfirm",
                "--onefile",
                "--windowed",
                "--name=Remote_Server",
                f"--icon={os.path.join(SERVER_DIR, 'logo.ico')}",
                f"--add-data={os.path.join(SERVER_DIR, 'logo.ico')};.",
                f"--add-data={os.path.join(SERVER_DIR, 'logo.png')};.",
                f"--add-data={os.path.join(SERVER_DIR, 'config.json')};.",
                f"--paths={SERVER_DIR}",
                "--hidden-import=uvicorn.logging",
                "--hidden-import=uvicorn.loops.auto",
                "--hidden-import=uvicorn.protocols.http.auto",
                "--hidden-import=uvicorn.protocols.websockets.auto",
                "--hidden-import=uvicorn.lifespan.on",
                "--hidden-import=pynput.keyboard._win32",
                "--hidden-import=pynput.mouse._win32",
                f"--distpath={ROOT_DIR}",
                f"--workpath={os.path.join(ROOT_DIR, 'build', 'server')}",
            ]

            py_base = r"C:\Users\priya\AppData\Local\Programs\Python\Python312"
            for dll in ["python312.dll", "vcruntime140.dll", "vcruntime140_1.dll"]:
                dll_path = os.path.join(py_base, dll)
                if os.path.exists(dll_path):
                    cmd_server.append(f"--add-binary={dll_path};.")
            if args.clean:
                cmd_server.append("--clean")
            cmd_server.append(os.path.join(SERVER_DIR, "Remote_Server.py"))

            res1 = subprocess.run(cmd_server, cwd=SERVER_DIR)
            if res1.returncode != 0:
                print("❌ Core Server PyInstaller build failed!")
                sys.exit(res1.returncode)
            print(f"✓ Core Server built in {time.time() - t0:.1f}s")
        else:
            print(f"⚡ Skipping Core Server build: {reason}")
    else:
        print("⏩ Skipped Core Server build by option")

    # 2. Desktop GUI Build
    if build_gui:
        needs_gui, gui_reason = True, "Forced build"
        if not args.clean and not args.force:
            needs_gui, gui_reason = should_rebuild_gui(gui_exe)

        if needs_gui:
            print("\n" + "=" * 60)
            print(f" 🚀 Building 2/3: Modern React + Electron Desktop GUI ({gui_reason})")
            print("=" * 60)
            t0 = time.time()
            res_npm = subprocess.run("npm run build:gui", cwd=DESKTOP_GUI_DIR, shell=True)
            if res_npm.returncode != 0:
                print("❌ Desktop GUI build failed!")
                sys.exit(res_npm.returncode)
            print(f"✓ Desktop GUI built in {time.time() - t0:.1f}s")
        else:
            print(f"⚡ Skipping Desktop GUI build: {gui_reason}")
    else:
        print("⏩ Skipped Desktop GUI build by option")

    # 3. Inno Setup Bundle
    if build_installer:
        iss_file = os.path.join(ROOT_DIR, "installer.iss")
        if os.path.exists(ISCC_PATH) and os.path.exists(iss_file):
            compression = "lzma2/max" if args.max_compression else "lzma2/fast"
            print("\n" + "=" * 60)
            print(f" 📦 Building 3/3: Single Windows Setup EXE (Inno Setup: {compression})")
            print("=" * 60)
            t0 = time.time()
            cmd_iscc = [ISCC_PATH, f"/DAppCompression={compression}", iss_file]
            res_iscc = subprocess.run(cmd_iscc, cwd=ROOT_DIR)
            if res_iscc.returncode == 0:
                setup_exe = os.path.join(ROOT_DIR, "Remote_PC_Setup.exe")
                size_mb = os.path.getsize(setup_exe) / (1024 * 1024) if os.path.exists(setup_exe) else 0
                print(f"🎉 Remote_PC_Setup.exe generated in {time.time() - t0:.1f}s ({size_mb:.1f} MB)")
            else:
                print("⚠️ Inno Setup failed with exit code:", res_iscc.returncode)
        else:
            print(f"⚠️ ISCC.exe not found at {ISCC_PATH} or {iss_file} missing.")
    else:
        print("⏩ Skipped Inno Setup installer packaging by option")

    print("\n" + "=" * 60)
    print(f"✨ Windows build completed in {time.time() - start_total:.1f}s total!")
    print("=" * 60)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Fast & Incremental Build Pipeline for Remote PC Windows Application")
    parser.add_argument("--fast", action="store_true", default=True, help="Use fast compression & incremental build (default)")
    parser.add_argument("--max-compression", action="store_true", help="Use maximum lzma2 compression (slower build, ~3% smaller)")
    parser.add_argument("--clean", action="store_true", help="Force complete clean build from scratch")
    parser.add_argument("--force", action="store_true", help="Force rebuild even if source files have not changed")
    parser.add_argument("--skip-server", action="store_true", help="Skip rebuilding Core Server exe")
    parser.add_argument("--skip-gui", action="store_true", help="Skip rebuilding Electron Desktop GUI")
    parser.add_argument("--server-only", action="store_true", help="Build only Core Server exe")
    parser.add_argument("--gui-only", action="store_true", help="Build only Electron Desktop GUI")
    parser.add_argument("--installer-only", action="store_true", help="Only run Inno Setup on existing binaries")

    args = parser.parse_args()
    build(args)
