#!/usr/bin/env node

/**
 * High-Speed Streaming Release Asset Publisher for Remote PC
 * Modeled after Finance Application's scripts/upload-release.js
 *
 * Uploads:
 *   - Remote_PC_Setup.exe (Windows Installer)
 *   - Remote.PC.apk (Android APK)
 *   - latest.yml (Electron auto-updater metadata)
 *   - checksums.txt (SHA256 integrity verification)
 */

import fs from "fs";
import path from "path";
import https from "https";
import crypto from "crypto";
import dns from "dns";
import { fileURLToPath } from "url";

// Force IPv4 first to avoid IPv6 getaddrinfo ENOTFOUND / ECONNRESET on uploads.github.com
try {
  dns.setDefaultResultOrder("ipv4first");
} catch (_) {}

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

const uploadAgent = new https.Agent({
  keepAlive: true,
  maxSockets: 8,
  maxFreeSockets: 4,
});

function uploadFileStream(uploadUrl, filePath, contentType, token) {
  return new Promise((resolve, reject) => {
    const fileStats = fs.statSync(filePath);
    const totalBytes = fileStats.size;
    const urlObj = new URL(uploadUrl);
    const baseName = path.basename(filePath);
    const startTime = Date.now();
    let lastReportTime = 0;

    const options = {
      hostname: urlObj.hostname,
      port: 443,
      path: urlObj.pathname + urlObj.search,
      method: "POST",
      agent: uploadAgent,
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

    req.on("socket", (socket) => {
      try {
        socket.setNoDelay(true);
      } catch (_) {}
    });

    req.on("error", (err) => reject(err));
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Upload request timed out"));
    });

    let uploadedBytes = 0;
    let lastPercent = -1;
    const stream = fs.createReadStream(filePath, {
      highWaterMark: 4 * 1024 * 1024, // 4MB high-performance buffer
    });

    stream.on("data", (chunk) => {
      uploadedBytes += chunk.length;
      const pct = Math.round((uploadedBytes / totalBytes) * 100);
      const now = Date.now();
      if ((pct !== lastPercent && pct % 2 === 0) || now - lastReportTime > 250 || pct === 100) {
        lastPercent = pct;
        lastReportTime = now;
        const elapsedSec = (now - startTime) / 1000 || 0.001;
        const mbps = (uploadedBytes / (1024 * 1024) / elapsedSec).toFixed(1);
        process.stdout.write(
          `  ⏳ [${baseName}] ${pct}% (${(uploadedBytes / (1024 * 1024)).toFixed(1)} / ${(totalBytes / (1024 * 1024)).toFixed(1)} MB) · ⚡ ${mbps} MB/s\r`
        );
      }
    });

    stream.on("end", () => {
      console.log(`\n  ✓ [${baseName}] Stream finished, awaiting server confirmation...`);
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

  const headers = {
    Authorization: `token ${token}`,
    Accept: "application/vnd.github.v3+json",
    "User-Agent": "Remote-PC-Uploader/3.0",
  };

  await pruneOldReleases(headers, tag, 10);

  console.log(`\n========================================================`);
  console.log(` Uploading Remote PC Release ${tag} to GitHub`);
  console.log(`========================================================`);

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
          body: `### Remote PC ${tag}\n\n- 🌐 **Official Product Landing Site**: Dedicated responsive web showcase and product download page\n- 🎨 **Brand Identity & Modernized UI**: Handcrafted dark obsidian theme, updated typography, refreshed branding and vector icons across Desktop & Android\n- 📡 **AirPulse P2P File Drop Overhaul**: Simplified direct IP connection and real-time animated discovery radar with zero cloud dependencies\n- 🖱️ **Desktop Input Controllers & Low Latency**: Refactored keyboard, media, volume, and remote desktop controllers for smooth responsive control\n- 📱 **Android Client Enhancements**: Streamlined settings, unified theme palette, updated QR scanner and video stream server\n- 🐧 **Linux / Ubuntu Improvements**: Enhanced installer and environment configuration scripts`,
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
  const portableExePath = path.join(ROOT_DIR, `Remote_PC_GUI_${version}.exe`);
  const apkPath = path.join(ROOT_DIR, "Remote.PC.apk");
  const debPath = path.join(ROOT_DIR, "Remote_PC_Ubuntu_amd64.deb");
  const tarPath = path.join(ROOT_DIR, "Remote_PC_Linux_x86_64.tar.gz");
  const serverPath = path.join(ROOT_DIR, "Remote_Server");

  // Always sync latest built APK if available
  const clientApk = path.join(ROOT_DIR, "client", "app", "build", "outputs", "apk", "debug", "app-debug.apk");
  if (fs.existsSync(clientApk)) {
    fs.copyFileSync(clientApk, apkPath);
    fs.copyFileSync(clientApk, path.join(ROOT_DIR, "Remote PC.apk"));
    console.log(`📱 Synced latest Android APK from ${clientApk}`);
  }

  // If portable Windows GUI exists but Setup doesn't or is older, ensure Setup.exe is available
  if (fs.existsSync(portableExePath)) {
    if (!fs.existsSync(exePath) || fs.statSync(portableExePath).mtimeMs > fs.statSync(exePath).mtimeMs) {
      fs.copyFileSync(portableExePath, exePath);
      console.log(`🖥️  Synced Windows executable to ${exePath}`);
    }
  }

  const isLinux = process.argv.includes("--linux");
  const hasExe = !isApkOnly && fs.existsSync(exePath);
  const hasPortableExe = !isApkOnly && fs.existsSync(portableExePath);
  const hasApk = !isExeOnly && fs.existsSync(apkPath);
  const hasDeb = !isExeOnly && !isApkOnly && fs.existsSync(debPath);
  const hasTar = !isExeOnly && !isApkOnly && fs.existsSync(tarPath);
  const hasServer = !isExeOnly && !isApkOnly && fs.existsSync(serverPath);

  if (!hasExe && !hasApk && !hasDeb && !hasTar) {
    throw new Error("No release binaries found.");
  }

  const checksumLines = [];
  let latestYmlPath = null;

  // Generate checksum lines for all available binaries on disk
  if (fs.existsSync(exePath)) {
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
    latestYmlPath = path.join(ROOT_DIR, "latest.yml");
    fs.writeFileSync(latestYmlPath, latestYmlContent, "utf-8");
    checksumLines.push(`${computeSha256(exePath)}  Remote_PC_Setup.exe`);
  }

  if (fs.existsSync(apkPath)) {
    const apkStats = fs.statSync(apkPath);
    console.log(`Found Android APK:       Remote.PC.apk (${(apkStats.size / (1024 * 1024)).toFixed(1)} MB)`);
    checksumLines.push(`${computeSha256(apkPath)}  Remote.PC.apk`);
  }

  if (hasDeb) {
    const debStats = fs.statSync(debPath);
    console.log(`Found Ubuntu / Debian:   Remote_PC_Ubuntu_amd64.deb (${(debStats.size / (1024 * 1024)).toFixed(1)} MB)`);
    checksumLines.push(`${computeSha256(debPath)}  Remote_PC_Ubuntu_amd64.deb`);
  }

  if (hasTar) {
    const tarStats = fs.statSync(tarPath);
    console.log(`Found Linux Generic:     Remote_PC_Linux_x86_64.tar.gz (${(tarStats.size / (1024 * 1024)).toFixed(1)} MB)`);
    checksumLines.push(`${computeSha256(tarPath)}  Remote_PC_Linux_x86_64.tar.gz`);
  }

  // Generate checksums.txt
  checksumLines.push("");
  const checksumPath = path.join(ROOT_DIR, "checksums.txt");
  fs.writeFileSync(checksumPath, checksumLines.join("\n"), "utf-8");

  const filesToUpload = [
    hasExe && {
      localPath: exePath,
      name: "Remote_PC_Setup.exe",
      contentType: "application/vnd.microsoft.portable-executable",
    },
    hasPortableExe && {
      localPath: portableExePath,
      name: `Remote_PC_GUI_${version}.exe`,
      contentType: "application/vnd.microsoft.portable-executable",
    },
    hasApk && {
      localPath: apkPath,
      name: "Remote.PC.apk",
      contentType: "application/vnd.android.package-archive",
    },
    hasDeb && {
      localPath: debPath,
      name: "Remote_PC_Ubuntu_amd64.deb",
      contentType: "application/vnd.debian.binary-package",
    },
    hasTar && {
      localPath: tarPath,
      name: "Remote_PC_Linux_x86_64.tar.gz",
      contentType: "application/gzip",
    },
    latestYmlPath && {
      localPath: latestYmlPath,
      name: "latest.yml",
      contentType: "text/yaml",
    },
    {
      localPath: checksumPath,
      name: "checksums.txt",
      contentType: "text/plain",
    },
  ].filter((f) => f && f.localPath && fs.existsSync(f.localPath));

  const normalizeName = (s) =>
    decodeURIComponent(s || "")
      .toLowerCase()
      .replace(/[\s._-]+/g, "")
      .trim();

  // 3. Skip assets that are already uploaded with matching size, delete outdated or corrupt assets
  const filesPendingUpload = [];
  for (const item of filesToUpload) {
    const fileStats = fs.statSync(item.localPath);
    const existing = (release.assets || []).find(
      (a) => normalizeName(a.name) === normalizeName(item.name)
    );
    if (existing) {
      if (existing.state === "uploaded" && Number(existing.size) === fileStats.size) {
        console.log(`✓ [${item.name}] Already uploaded with identical size (${(fileStats.size / (1024 * 1024)).toFixed(1)} MB). Skipping.`);
        continue;
      }
      console.log(`Removing old/partial asset ${existing.name} (id: ${existing.id})...`);
      await fetch(
        `https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${existing.id}`,
        {
          method: "DELETE",
          headers,
        }
      );
    }
    filesPendingUpload.push(item);
  }

  // 4. Upload all pending assets sequentially using high-speed streaming with retry
  console.log(`\n🚀 Uploading ${filesPendingUpload.length} release assets sequentially...`);
  const baseUploadUrl = release.upload_url
    ? release.upload_url.replace(/\{.*\}/, "")
    : `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets`;

  for (const item of filesPendingUpload) {
    const fileStats = fs.statSync(item.localPath);
    const uploadUrl = `${baseUploadUrl}?name=${encodeURIComponent(item.name)}`;
    console.log(`\nUploading ${item.name} (${(fileStats.size / (1024 * 1024)).toFixed(1)} MB)...`);

    let uploaded = false;
    let attempts = 0;
    while (!uploaded && attempts < 3) {
      attempts++;
      try {
        await uploadFileStream(
          uploadUrl,
          item.localPath,
          item.contentType,
          token
        );
        console.log(
          `\n✓ [${item.name}] Uploaded successfully (${(fileStats.size / (1024 * 1024)).toFixed(1)} MB)`
        );
        uploaded = true;
      } catch (uploadErr) {
        console.warn(`\n⚠️  Attempt ${attempts} failed for ${item.name}: ${uploadErr.message}`);
        // If it already exists, delete and retry
        try {
          const assetsList = await fetch(
            `https://api.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets`,
            { headers }
          ).then((r) => r.json());
          const existing = (Array.isArray(assetsList) ? assetsList : []).find(
            (a) => normalizeName(a.name) === normalizeName(item.name)
          );
          if (existing) {
            console.log(`  Deleting existing/partial asset ${existing.name} (id: ${existing.id})...`);
            await fetch(
              `https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${existing.id}`,
              { method: "DELETE", headers }
            );
          }
        } catch (_) {}

        if (attempts >= 3) {
          throw uploadErr;
        }
        console.log(`  Retrying ${item.name} in 3 seconds...`);
        await new Promise((r) => setTimeout(r, 3000));
      }
    }
  }

  console.log(`\n========================================================`);
  console.log(`🎉 Successfully published and uploaded Remote PC ${tag}!`);
  console.log(`GitHub Release: https://github.com/${OWNER}/${REPO}/releases/tag/${tag}`);
  console.log(`📱 APK Download:     https://github.com/${OWNER}/${REPO}/releases/download/${tag}/Remote.PC.apk`);
  console.log(`🖥️  EXE Download:     https://github.com/${OWNER}/${REPO}/releases/download/${tag}/Remote_PC_Setup.exe`);
  console.log(`========================================================\n`);
}

async function pruneOldReleases(headers, currentTag, keepCount = 10) {
  try {
    const response = await fetch(
      `https://api.github.com/repos/${OWNER}/${REPO}/releases?per_page=100`,
      { headers }
    );
    if (!response.ok) {
      console.warn(`Could not list releases for retention cleanup: HTTP ${response.status}`);
      return;
    }

    const releases = (await response.json())
      .filter((release) => !release.draft && !release.prerelease)
      .sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
    const expired = releases.slice(keepCount);
    if (expired.length === 0) {
      console.log(`✓ Release retention: keeping the latest ${releases.length} stable releases (max ${keepCount})`);
      return;
    }

    console.log(`🧹 Release retention: removing ${expired.length} stable releases older than the latest ${keepCount}`);
    for (const release of expired) {
      const result = await fetch(
        `https://api.github.com/repos/${OWNER}/${REPO}/releases/${release.id}`,
        { method: "DELETE", headers }
      );
      if (!result.ok) {
        console.warn(`⚠️ Failed to delete old release ${release.tag_name}: HTTP ${result.status}`);
      } else {
        console.log(`  ✓ Deleted release ${release.tag_name}`);
      }
    }
  } catch (err) {
    console.warn("⚠️ Release retention notice:", err.message);
  }
}

main().catch((err) => {
  console.error("❌ Release upload error:", err.message, err.cause || "");
  process.exit(1);
});
