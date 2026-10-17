#!/usr/bin/env python3
"""
Remote PC - Automated GitHub Release & Update Publisher
======================================================
Builds (optional) and pushes new releases for Remote PC (both APK and Windows Setup EXE)
directly to GitHub Releases with release notes and SHA256 checksums.

Token Discovery Order:
  1. CLI argument: --token <TOKEN>
  2. Environment variables: GH_TOKEN or GITHUB_TOKEN
  3. D:\\Projects\\Finance_Application\\.env (GH_TOKEN)
  4. Local .env in Remote-Pc root directory

Usage:
  python publish_release.py --version v3.0.0 --build
  python publish_release.py --version v3.0.0 --notes "Bug fixes and 10s skip"
  python publish_release.py --check-token
"""

import os
import sys
import re
import json
import hashlib
import argparse
import subprocess
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

DEFAULT_REPO = "Priyadharsant/Remote-Pc"
FINANCE_ENV_PATH = r"D:\Projects\Finance_Application\.env"
ROOT_DIR = Path(__file__).resolve().parent

def find_github_token(explicit_token: str | None = None) -> str | None:
    """Finds GitHub authentication token from arguments, environment, or Finance_Application."""
    if explicit_token and explicit_token.strip():
        return explicit_token.strip()

    # Check environment variables
    for var in ("GH_TOKEN", "GITHUB_TOKEN"):
        val = os.environ.get(var)
        if val and val.strip():
            return val.strip()

    # Check Finance Application .env
    if os.path.exists(FINANCE_ENV_PATH):
        try:
            with open(FINANCE_ENV_PATH, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("GH_TOKEN=") or line.startswith("GITHUB_TOKEN="):
                        val = line.split("=", 1)[1].strip().strip('"').strip("'")
                        if val:
                            print(f"🔑 Detected GitHub token from {FINANCE_ENV_PATH}")
                            return val
        except Exception as e:
            print(f"⚠️  Could not read Finance_Application .env: {e}")

    # Check local .env file
    local_env = ROOT_DIR / ".env"
    if local_env.exists():
        try:
            with open(local_env, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("GH_TOKEN=") or line.startswith("GITHUB_TOKEN="):
                        val = line.split("=", 1)[1].strip().strip('"').strip("'")
                        if val:
                            print(f"🔑 Detected GitHub token from local .env")
                            return val
        except Exception:
            pass

    return None

def compute_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def detect_version() -> str:
    """Detects version from installer.iss or client build.gradle.kts."""
    iss_file = ROOT_DIR / "installer.iss"
    if iss_file.exists():
        text = iss_file.read_text(encoding="utf-8", errors="ignore")
        m = re.search(r'#define\s+MyAppVersion\s+"([^"]+)"', text)
        if m:
            ver = m.group(1).strip()
            return f"v{ver}" if not ver.startswith("v") else ver

    gradle_file = ROOT_DIR / "client" / "app" / "build.gradle.kts"
    if gradle_file.exists():
        text = gradle_file.read_text(encoding="utf-8", errors="ignore")
        m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
        if m:
            ver = m.group(1).strip()
            return f"v{ver}" if not ver.startswith("v") else ver

    return "v3.0.0"

def build_artifacts():
    print("\n" + "=" * 65)
    print(" 🛠️  Building Android APK and Windows Setup Binaries...")
    print("=" * 65)

    # 1. Build Android APK
    client_dir = ROOT_DIR / "client"
    gradlew = client_dir / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if gradlew.exists():
        print("📱 Compiling Android APK with Gradle...")
        res = subprocess.run([str(gradlew), "assembleDebug"], cwd=client_dir)
        if res.returncode != 0:
            print("❌ Gradle APK build failed!")
            sys.exit(res.returncode)
        
        apk_src = client_dir / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
        apk_dest = ROOT_DIR / "Remote PC.apk"
        if apk_src.exists():
            import shutil
            shutil.copy2(apk_src, apk_dest)
            print(f"✅ Generated APK: {apk_dest} ({apk_dest.stat().st_size // 1024} KB)")

    # 2. Build Windows Setup EXE
    build_script = ROOT_DIR / "build_exe.py"
    if build_script.exists():
        print("🖥️  Compiling Windows Executable & Inno Setup Installer...")
        res = subprocess.run([sys.executable, str(build_script)], cwd=ROOT_DIR)
        if res.returncode != 0:
            print("❌ Windows build_exe.py failed!")
            sys.exit(res.returncode)
        print("✅ Windows Setup generated successfully!")

def github_api_request(url: str, token: str, method: str = "GET", data: dict | bytes | None = None, content_type: str = "application/json", timeout: float = 600.0) -> dict:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "RemotePC-Release-Publisher",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    if content_type:
        headers["Content-Type"] = content_type

    req_data = None
    if isinstance(data, dict):
        req_data = json.dumps(data).encode("utf-8")
    elif isinstance(data, (bytes, bytearray)):
        req_data = data

    req = urllib.request.Request(url, data=req_data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp_body = resp.read()
            if resp_body:
                return json.loads(resp_body.decode("utf-8"))
            return {}
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8", errors="replace")
        try:
            err_json = json.loads(err_msg)
            message = err_json.get("message", err_msg)
        except Exception:
            message = err_msg
        raise RuntimeError(f"GitHub API Error [{e.code}]: {message}")

def publish_release(token: str, repo: str, tag: str, title: str, notes: str, draft: bool = False, prerelease: bool = False):
    print("\n" + "=" * 65)
    print(f" 🚀 Publishing GitHub Release: {tag} ({repo})")
    print("=" * 65)

    # 1. Locate release assets
    apk_file = ROOT_DIR / "Remote PC.apk"
    exe_file = ROOT_DIR / "Remote_PC_Setup.exe"

    # Also check client output if root apk is absent
    if not apk_file.exists():
        candidate_apk = ROOT_DIR / "client" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
        if candidate_apk.exists():
            import shutil
            shutil.copy2(candidate_apk, apk_file)

    missing = []
    if not apk_file.exists():
        missing.append("Remote PC.apk")
    if not exe_file.exists():
        missing.append("Remote_PC_Setup.exe")

    if missing:
        print(f"❌ Missing required build assets: {', '.join(missing)}")
        print("💡 Run with --build flag to compile them automatically first.")
        sys.exit(1)

    # Create checksums
    checksum_lines = [
        f"{compute_sha256(apk_file)}  Remote PC.apk",
        f"{compute_sha256(exe_file)}  Remote_PC_Setup.exe"
    ]
    checksum_file = ROOT_DIR / "checksums.txt"
    checksum_file.write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")

    assets_to_upload = [
        (exe_file, "Remote_PC_Setup.exe", "application/vnd.microsoft.portable-executable"),
        (apk_file, "Remote PC.apk", "application/vnd.android.package-archive"),
        (checksum_file, "checksums.txt", "text/plain")
    ]

    # 2. Check if release already exists for this tag
    base_url = f"https://api.github.com/repos/{repo}/releases"
    existing_release = None
    try:
        existing_release = github_api_request(f"{base_url}/tags/{tag}", token)
    except RuntimeError:
        pass

    if existing_release and "id" in existing_release:
        release_id = existing_release["id"]
        upload_url = existing_release["upload_url"].split("{")[0]
        html_url = existing_release.get("html_url", "")
        print(f"ℹ️  Existing release found for {tag} (ID: {release_id})")

        # Delete existing assets with same names to replace with new builds
        for asset in existing_release.get("assets", []):
            asset_name = asset.get("name")
            if any(asset_name == name for _, name, _ in assets_to_upload):
                asset_id = asset["id"]
                print(f"🗑️  Removing outdated release asset: {asset_name} (ID: {asset_id})")
                github_api_request(f"https://api.github.com/repos/{repo}/releases/assets/{asset_id}", token, method="DELETE")
    else:
        print(f"📦 Creating new GitHub Release for tag: {tag}...")
        release_payload = {
            "tag_name": tag,
            "target_commitish": "main",
            "name": title or f"Remote PC {tag}",
            "body": notes or f"### Remote PC Release {tag}\n\n- Zero-latency touchpad and real-time audio/brightness controls\n- Seamless 1-tap QR Code pairing\n- Instant 10s playback seek for YouTube, VLC, and streaming\n- Integrated In-App and In-EXE Auto-Updater\n- High-speed wireless file streaming and transfer",
            "draft": draft,
            "prerelease": prerelease
        }
        created = github_api_request(base_url, token, method="POST", data=release_payload)
        upload_url = created["upload_url"].split("{")[0]
        html_url = created.get("html_url", "")
        print(f"✅ Created release: {html_url}")

    # 3. Upload assets
    for filepath, filename, content_type in assets_to_upload:
        size_mb = filepath.stat().st_size / (1024 * 1024)
        print(f"⬆️  Uploading {filename} ({size_mb:.2f} MB)...")
        file_bytes = filepath.read_bytes()
        upload_target = f"{upload_url}?name={urllib.parse.quote(filename)}"
        try:
            github_api_request(upload_target, token, method="POST", data=file_bytes, content_type=content_type)
            print(f"   ✅ Successfully uploaded {filename}")
        except Exception as e:
            print(f"   ❌ Upload failed for {filename}: {e}")
            raise

    print("\n" + "=" * 65)
    print(" 🎉 RELEASE DEPLOYED SUCCESSFULLY TO GITHUB!")
    print("=" * 65)
    print(f" 🌐 Release Page: {html_url}")
    print(f" 📱 Mobile APK:   https://github.com/{repo}/releases/download/{tag}/Remote%20PC.apk")
    print(f" 🖥️  Windows EXE:  https://github.com/{repo}/releases/download/{tag}/Remote_PC_Setup.exe")
def push_code(token: str, repo: str):
    print("\n" + "=" * 65)
    print(f" 🚀 Pushing Source Code Only to GitHub ({repo})...")
    print("=" * 65)
    auth_url = f"https://{token}@github.com/{repo}.git"
    res = subprocess.run(["git", "push", auth_url, "main"], capture_output=True, text=True)
    if res.returncode == 0:
        print("✅ Git push successful! Remote branch 'main' is up to date.")
        if res.stdout:
            print(res.stdout.strip())
    else:
        clean_err = res.stderr.replace(token, "***TOKEN***")
        print(f"❌ Git push failed:\n{clean_err}")
        sys.exit(res.returncode)

def main():
    parser = argparse.ArgumentParser(description="Publish Remote PC updates to GitHub Releases")
    parser.add_argument("--version", "-v", help="Release version tag (e.g. v3.0.0). Defaults to detected version.")
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"GitHub repository owner/repo (default: {DEFAULT_REPO})")
    parser.add_argument("--token", help="GitHub Personal Access Token (defaults to Finance_Application env or GH_TOKEN)")
    parser.add_argument("--title", help="Release title")
    parser.add_argument("--notes", help="Release notes markdown body")
    parser.add_argument("--build", action="store_true", help="Compile APK and Setup EXE before publishing")
    parser.add_argument("--draft", action="store_true", help="Create as draft release")
    parser.add_argument("--prerelease", action="store_true", help="Mark as prerelease")
    parser.add_argument("--check-token", action="store_true", help="Verify if token is detected and test connection")
    parser.add_argument("--push-code", action="store_true", help="Push source code to GitHub remote main branch")
    parser.add_argument("--code-only", action="store_true", help="Only push code to remote without publishing release")

    args = parser.parse_args()

    token = find_github_token(args.token)

    if args.check_token:
        if not token:
            print("❌ No GitHub token found.")
            print(f"   Provide via --token, set GH_TOKEN environment variable, or configure in {FINANCE_ENV_PATH}")
            sys.exit(1)
        print("🔍 Testing GitHub Token with GitHub API...")
        try:
            user = github_api_request("https://api.github.com/user", token)
            print(f"✅ Token is valid! Authenticated as: @{user.get('login')} ({user.get('name', '')})")
            sys.exit(0)
        except Exception as e:
            print(f"❌ Token validation failed: {e}")
            sys.exit(1)

    if not token:
        print("\n❌ GITHUB TOKEN NOT FOUND!")
        print("=================================================================")
        print("Please provide a GitHub Personal Access Token using one of:")
        print("  1. --token <YOUR_TOKEN>")
        print("  2. set GH_TOKEN=<YOUR_TOKEN>")
        print(f"  3. Save GH_TOKEN=<YOUR_TOKEN> in {FINANCE_ENV_PATH}")
        print("  4. Save GH_TOKEN=<YOUR_TOKEN> in .env in the project root")
        print("=================================================================\n")
        sys.exit(1)

    if args.push_code or args.code_only:
        push_code(token, args.repo)
        if args.code_only:
            print("✨ Code push complete. Exiting (--code-only specified).")
            sys.exit(0)

    tag = args.version or detect_version()
    if not tag.startswith("v"):
        tag = f"v{tag}"

    if args.build:
        build_artifacts()

    publish_release(
        token=token,
        repo=args.repo,
        tag=tag,
        title=args.title or f"Remote PC {tag}",
        notes=args.notes or "",
        draft=args.draft,
        prerelease=args.prerelease
    )

if __name__ == "__main__":
    main()
