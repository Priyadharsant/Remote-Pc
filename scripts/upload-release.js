#!/usr/bin/env node

/**
 * High-Speed Streaming Release Asset Publisher for Remote PC
 * Modeled after Finance Application's scripts/upload-release.js
 *
 * Uploads:
 *   - Remote_PC_Setup.exe (Windows Installer)
 *   - Remote PC.apk (Android APK)
 *   - latest.yml (Electron auto-updater metadata)
 *   - checksums.txt (SHA256 integrity verification)
 */

import fs from "fs";
import path from "path";
import https from "https";
import crypto from "crypto";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, "..");
const OWNER = "Priyadharsant";
const REPO = "Remote-Pc";

function loadToken() {
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN.trim();
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN.trim();

  const candidateFiles = [
    path.join(ROOT_DIR, ".env"),
    "D:/Projects/Finance_Application/.env",
    "D:\\Projects\\Finance_Application\\.env"
  ];

  for (const f of candidateFiles) {
    if (fs.existsSync(f)) {
      try {
        const match = fs
          .readFileSync(f, "utf-8")
          .match(/^(?:GH_TOKEN|GITHUB_TOKEN)=(.*)$/m);
        if (match && match[1].trim()) {
          const t = match[1].trim().replace(/^["']|["']$/g, "");
          if (t) {
            console.log(`🔑 Loaded GitHub token from ${f}`);
            return t;
          }
        }
      } catch (_) {}
    }
  }
  return null;
}

function computeSha512(filePath) {
  const hash = crypto.createHash("sha512");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("base64");
}

function computeSha256(filePath) {
  const hash = crypto.createHash("sha256");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("hex");
}

function uploadFileStream(uploadUrl, filePath, contentType, token) {
  return new Promise((resolve, reject) => {
    const fileStats = fs.statSync(filePath);
    const totalBytes = fileStats.size;
    const urlObj = new URL(uploadUrl);

    const options = {
      hostname: urlObj.hostname,
      port: 443,
      path: urlObj.pathname + urlObj.search,
      method: "POST",
      headers: {
        Authorization: `token ${token}`,
        "Content-Type": contentType,
        "Content-Length": totalBytes,
        "User-Agent": "Remote-PC-Uploader/3.0",
      },
      timeout: 15 * 60 * 1000, // 15 minutes timeout
    };

    const req = https.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try {
            resolve(JSON.parse(data));
          } catch (e) {
            resolve(data);
          }
        } else {
          reject(
            new Error(`Upload failed with status ${res.statusCode}: ${data}`)
          );
        }
      });
    });

    req.on("error", (err) => reject(err));
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Upload request timed out"));
    });

    let uploadedBytes = 0;
    let lastPercent = -1;
    const stream = fs.createReadStream(filePath, {
      highWaterMark: 1024 * 1024,
    });

    stream.on("data", (chunk) => {
      uploadedBytes += chunk.length;
      const pct = Math.round((uploadedBytes / totalBytes) * 100);
      if (pct !== lastPercent && pct % 1 === 0) {
        lastPercent = pct;
        process.stdout.write(
          `  ⏳ ${pct}% (${(uploadedBytes / (1024 * 1024)).toFixed(1)} / ${(totalBytes / (1024 * 1024)).toFixed(1)} MB)\r`
        );
      }
    });

    stream.on("end", () => {
      console.log(`\n  ✓ Stream finished, awaiting server confirmation...`);
    });

    stream.on("error", (err) => {
      req.destroy();
      reject(err);
    });

    stream.pipe(req);
  });
}

async function main() {
  const token = loadToken();
  if (!token) {
    console.error("❌ Error: GH_TOKEN not found in environment or .env");
    process.exit(1);
  }

  // Detect current version
  let version = "3.0.0";
  const pkgPath = path.join(ROOT_DIR, "package.json");
  if (fs.existsSync(pkgPath)) {
    const pkg = JSON.parse(fs.readFileSync(pkgPath, "utf-8"));
    if (pkg.version) version = pkg.version;
  }
  const tag = `v${version}`;

  console.log(`\n========================================================`);
  console.log(` Uploading Remote PC Release ${tag} to GitHub`);
  console.log(`========================================================`);

  const headers = {
    Authorization: `token ${token}`,
    Accept: "application/vnd.github.v3+json",
    "User-Agent": "Remote-PC-Uploader/3.0",
  };

  // 1. Get or create release
  let release = null;
  const getRes = await fetch(
    `https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${tag}`,
    { headers }
  );

  if (getRes.ok) {
    release = await getRes.json();
    console.log(`✓ Found existing release ${tag} (id: ${release.id})`);
  } else {
    console.log(`Creating release for ${tag}...`);
    const createRes = await fetch(
      `https://api.github.com/repos/${OWNER}/${REPO}/releases`,
      {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({
          tag_name: tag,
          target_commitish: "main",
          name: `Remote PC ${tag}`,
          body: `### Remote PC ${tag}\n\n- **Zero-Latency Wireless Trackpad & Keyboard**: Real-time cursor control and keyboard emulation\n- **Live Audio & Brightness Controls**: High-speed slider synchronization between Mobile and PC\n- **1-Tap QR & PIN Handshake**: Instant zero-leak device authorization\n- **10s Media Playback Seeking**: Instant forward/backward skip for YouTube, VLC, and video streaming\n- **In-App & In-EXE Auto-Updater**: High-speed parallel chunk updates for Android APK and Windows Setup EXE\n- **Direct Storage Explorer**: High-speed wireless file browsing, download, and streaming`,
          draft: false,
          prerelease: false,
        }),
      }
    );
    if (!createRes.ok) {
      const errText = await createRes.text();
      throw new Error(`Failed to create release: ${createRes.status} ${errText}`);
    }
    release = await createRes.json();
    console.log(`✓ Created GitHub release ${tag} (id: ${release.id})`);
  }

  // 2. Prepare files to upload
  const isExeOnly = process.argv.includes("--exe-only");
  const isApkOnly = process.argv.includes("--apk-only");

  const exePath = path.join(ROOT_DIR, "Remote_PC_Setup.exe");
  const apkPath = path.join(ROOT_DIR, "Remote PC.apk");

  if (!isApkOnly && !fs.existsSync(exePath)) {
    if (isExeOnly) {
      throw new Error(`Executable installer not found at ${exePath}. Run 'npm run build:exe' first!`);
    }
  }

  if (!isExeOnly && !fs.existsSync(apkPath)) {
    // Check if client build output exists
    const clientApk = path.join(ROOT_DIR, "client", "app", "build", "outputs", "apk", "debug", "app-debug.apk");
    if (fs.existsSync(clientApk)) {
      fs.copyFileSync(clientApk, apkPath);
    } else if (isApkOnly) {
      throw new Error(`Android APK not found at ${apkPath}. Run 'npm run build:apk' first!`);
    }
  }

  const hasExe = !isApkOnly && fs.existsSync(exePath);
  const hasApk = !isExeOnly && fs.existsSync(apkPath);

  if (!hasExe && !hasApk) {
    throw new Error("No release binaries found (neither Remote_PC_Setup.exe nor Remote PC.apk).");
  }

  const checksumLines = [];

  if (hasExe) {
    const exeStats = fs.statSync(exePath);
    console.log(`Found Windows Installer: Remote_PC_Setup.exe (${(exeStats.size / (1024 * 1024)).toFixed(1)} MB)`);
    const sha512 = computeSha512(exePath);
    const latestYmlContent = [
      `version: ${version}`,
      `files:`,
      `  - url: Remote_PC_Setup.exe`,
      `    sha512: ${sha512}`,
      `    size: ${exeStats.size}`,
      `path: Remote_PC_Setup.exe`,
      `sha512: ${sha512}`,
      `releaseDate: '${new Date().toISOString()}'`,
      "",
    ].join("\n");
    const latestYmlPath = path.join(ROOT_DIR, "latest.yml");
    fs.writeFileSync(latestYmlPath, latestYmlContent, "utf-8");
    checksumLines.push(`${computeSha256(exePath)}  Remote_PC_Setup.exe`);
  }

  if (hasApk) {
    const apkStats = fs.statSync(apkPath);
    console.log(`Found Android APK:       Remote PC.apk (${(apkStats.size / (1024 * 1024)).toFixed(1)} MB)`);
    checksumLines.push(`${computeSha256(apkPath)}  Remote PC.apk`);
  }

  // Generate checksums.txt
  checksumLines.push("");
  const checksumPath = path.join(ROOT_DIR, "checksums.txt");
  fs.writeFileSync(checksumPath, checksumLines.join("\n"), "utf-8");

  const filesToUpload = [
    {
      localPath: exePath,
      name: "Remote_PC_Setup.exe",
      contentType: "application/vnd.microsoft.portable-executable",
    },
    {
      localPath: apkPath,
      name: "Remote.PC.apk",
      contentType: "application/vnd.android.package-archive",
    },
    {
      localPath: latestYmlPath,
      name: "latest.yml",
      contentType: "text/yaml",
    },
    {
      localPath: checksumPath,
      name: "checksums.txt",
      contentType: "text/plain",
    },
  ].filter((f) => f.localPath && fs.existsSync(f.localPath));

  const normalizeName = (s) =>
    decodeURIComponent(s || "")
      .toLowerCase()
      .replace(/[\s._-]+/g, "")
      .trim();

  // 3. Delete conflicting assets with identical names
  for (const asset of release.assets || []) {
    if (filesToUpload.some((f) => normalizeName(f.name) === normalizeName(asset.name))) {
      console.log(`Removing old asset ${asset.name} (id: ${asset.id})...`);
      await fetch(
        `https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${asset.id}`,
        {
          method: "DELETE",
          headers,
        }
      );
    }
  }

  // 4. Upload each asset to uploads.github.com using streaming
  for (const item of filesToUpload) {
    const fileStats = fs.statSync(item.localPath);
    console.log(
      `\nUploading ${item.name} (${(fileStats.size / (1024 * 1024)).toFixed(1)} MB)...`
    );

    const uploadUrl = `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets?name=${encodeURIComponent(item.name)}`;
    try {
      const uploadedAsset = await uploadFileStream(
        uploadUrl,
        item.localPath,
        item.contentType,
        token
      );
      console.log(
        `✓ Uploaded ${item.name} (Download: ${uploadedAsset.browser_download_url || "Done"})`
      );
    } catch (uploadErr) {
      if (uploadErr.message && uploadErr.message.includes("already_exists")) {
        console.log(`Asset ${item.name} already exists. Fetching asset list to remove and retry...`);
        const assetsList = await fetch(
          `https://api.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets`,
          { headers }
        ).then((r) => r.json());
        const existing = (Array.isArray(assetsList) ? assetsList : []).find(
          (a) => normalizeName(a.name) === normalizeName(item.name)
        );
        if (existing) {
          await fetch(
            `https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${existing.id}`,
            { method: "DELETE", headers }
          );
          console.log(`✓ Removed existing asset ${existing.name}. Retrying upload...`);
          const uploadedAsset = await uploadFileStream(
            uploadUrl,
            item.localPath,
            item.contentType,
            token
          );
          console.log(
            `✓ Uploaded ${item.name} (Download: ${uploadedAsset.browser_download_url || "Done"})`
          );
          continue;
        }
      }
      throw uploadErr;
    }
  }

  console.log(`\n========================================================`);
  console.log(`🎉 Successfully published and uploaded Remote PC ${tag}!`);
  console.log(`GitHub Release: https://github.com/${OWNER}/${REPO}/releases/tag/${tag}`);
  console.log(`📱 APK Download:     https://github.com/${OWNER}/${REPO}/releases/download/${tag}/Remote%20PC.apk`);
  console.log(`🖥️  EXE Download:     https://github.com/${OWNER}/${REPO}/releases/download/${tag}/Remote_PC_Setup.exe`);
  console.log(`========================================================\n`);
}

main().catch((err) => {
  console.error("❌ Release upload error:", err.message);
  process.exit(1);
});
