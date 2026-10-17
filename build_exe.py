import os
import sys
import subprocess
import shutil
import time

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(ROOT_DIR, "server")
DESKTOP_GUI_DIR = os.path.join(ROOT_DIR, "desktop-gui")
VENV_PYTHON = os.path.join(SERVER_DIR, ".venv", "Scripts", "python.exe")

ISCC_PATH = r"C:\Users\priya\AppData\Local\Programs\Inno Setup 6\ISCC.exe"

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

def build():
    # Ensure any running instances are closed so binaries are not locked
    subprocess.run(["taskkill", "/F", "/IM", "Remote_Server.exe"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["taskkill", "/F", "/IM", "Remote PC GUI.exe"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.0)

    safe_remove(os.path.join(ROOT_DIR, "Remote_Server.exe"))

    # Clean build directories
    build_dir = os.path.join(ROOT_DIR, "build")
    if os.path.exists(build_dir):
        try:
            shutil.rmtree(build_dir, ignore_errors=True)
        except Exception:
            pass

    python_exe = VENV_PYTHON if os.path.exists(VENV_PYTHON) else sys.executable

    print("=" * 60)
    print(" 🚀 Building 1/3: Remote PC Core Server (Headless PyInstaller)")
    print("=" * 60)

    cmd_server = [
        python_exe, "-m", "PyInstaller",
        "--noconfirm",
        "--clean",
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
        os.path.join(SERVER_DIR, "Remote_Server.py")
    ]
    res1 = subprocess.run(cmd_server, cwd=SERVER_DIR)
    if res1.returncode != 0:
        print("❌ Core Server PyInstaller build failed!")
        sys.exit(res1.returncode)

    print("=" * 60)
    print(" 🚀 Building 2/3: Modern React + Electron Desktop GUI")
    print("=" * 60)

    res_npm = subprocess.run("npm run build:gui", cwd=DESKTOP_GUI_DIR, shell=True)
    if res_npm.returncode != 0:
        print("❌ Desktop GUI build failed!")
        sys.exit(res_npm.returncode)

    print("✅ Successfully built Core Server & Desktop GUI binaries")

    # Compile Inno Setup installer into single setup executable
    iss_file = os.path.join(ROOT_DIR, "installer.iss")
    if os.path.exists(ISCC_PATH) and os.path.exists(iss_file):
        print("=" * 60)
        print(" 📦 Building 3/3: Bundling into Single Windows Setup EXE (Inno Setup)")
        print("=" * 60)
        res_iscc = subprocess.run([ISCC_PATH, iss_file], cwd=ROOT_DIR)
        if res_iscc.returncode == 0:
            print("🎉 Single Unified Setup Installer generated successfully: Remote_PC_Setup.exe")
        else:
            print("⚠️ Inno Setup failed with exit code:", res_iscc.returncode)
    else:
        print(f"⚠️ ISCC.exe not found at {ISCC_PATH} or {iss_file} missing.")

if __name__ == "__main__":
    build()
