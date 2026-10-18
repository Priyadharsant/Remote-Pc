#!/usr/bin/env node

/**
 * Unified Version Bumping & Release Automation Script for Remote PC
 * Modeled after Finance Application release architecture.
 *
 * Usage:
 *   node scripts/bump-version.js patch                  (Bump patch: 3.0.0 -> 3.0.1)
 *   node scripts/bump-version.js minor                  (Bump minor: 3.0.0 -> 3.1.0)
 *   node scripts/bump-version.js major                  (Bump major: 3.0.0 -> 4.0.0)
 *   node scripts/bump-version.js patch --release        (Bump, commit, tag, push, build fast, upload)
 *   node scripts/bump-version.js patch --release --skip (Instant release: upload existing binaries in 10s)
 *   node scripts/bump-version.js patch --release --cloud(Zero local CPU: let GitHub Actions build in cloud)
 *   node scripts/bump-version.js patch --release --exe-only (Build & upload Windows EXE only)
 *   node scripts/bump-version.js patch --release --apk-only (Build & upload Android APK only)
 */

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import readline from "readline";
import { execSync } from "child_process";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, "..");
const FINANCE_ENV_PATH = "D:/Projects/Finance_Application/.env";

function loadEnvToken() {
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN.trim();
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN.trim();

  const candidateEnvs = [
    path.join(ROOT_DIR, ".env"),
    "D:/Projects/Finance_Application/.env",
    "D:\\Projects\\Finance_Application\\.env"
  ];

  for (const envFile of candidateEnvs) {
    if (fs.existsSync(envFile)) {
      try {
        const content = fs.readFileSync(envFile, "utf-8");
        const match = content.match(/^(?:GH_TOKEN|GITHUB_TOKEN)=(.*)$/m);
        if (match && match[1].trim()) {
          const t = match[1].trim().replace(/^["']|["']$/g, "");
          if (t) return t;
        }
      } catch (_) {}
    }
  }
  return null;
}

const FILES = {
  rootPackage: path.join(ROOT_DIR, "package.json"),
  desktopPackage: path.join(ROOT_DIR, "desktop-gui", "package.json"),
  desktopVersionTs: path.join(ROOT_DIR, "desktop-gui", "src", "version.ts"),
  androidGradle: path.join(ROOT_DIR, "client", "app", "build.gradle.kts"),
  innoSetup: path.join(ROOT_DIR, "installer.iss"),
  serverConfig: path.join(ROOT_DIR, "server", "config.json"),
  rootConfig: path.join(ROOT_DIR, "config.json")
};

function readJson(filePath) {
  if (!fs.existsSync(filePath)) return null;
  return JSON.parse(fs.readFileSync(filePath, "utf-8"));
}

function writeJson(filePath, data, indent = 2) {
  fs.writeFileSync(filePath, JSON.stringify(data, null, indent) + "\n", "utf-8");
}

function getCurrentVersion() {
  const rootPkg = readJson(FILES.rootPackage);
  if (rootPkg && rootPkg.version) return rootPkg.version;
  const deskPkg = readJson(FILES.desktopPackage);
  if (deskPkg && deskPkg.version && deskPkg.version !== "0.0.0") return deskPkg.version;
  return "3.0.0";
}

function computeNextVersion(current, bumpType) {
  const match = current.match(/^(\d+)\.(\d+)\.(\d+)(.*)$/);
  if (!match) {
    throw new Error(`Current version "${current}" does not conform to semantic versioning (X.Y.Z)`);
  }
  let major = parseInt(match[1], 10);
  let minor = parseInt(match[2], 10);
  let patch = parseInt(match[3], 10);

  const type = bumpType.toLowerCase();
  if (type === "patch") {
    patch += 1;
    return `${major}.${minor}.${patch}`;
  } else if (type === "minor") {
    minor += 1;
    patch = 0;
    return `${major}.${minor}.${patch}`;
  } else if (type === "major") {
    major += 1;
    minor = 0;
    patch = 0;
    return `${major}.${minor}.${patch}`;
  } else if (/^\d+\.\d+\.\d+/.test(bumpType)) {
    return bumpType.trim().replace(/^v/i, "");
  } else {
    throw new Error(`Invalid bump type or version: "${bumpType}". Use 'patch', 'minor', 'major', or a valid semver string like '3.0.1'.`);
  }
}

function getTodayDateString() {
  const now = new Date();
  const yyyy = now.getFullYear();
  const mm = String(now.getMonth() + 1).padStart(2, "0");
  const dd = String(now.getDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function updateFile(filePath, updater) {
  if (!fs.existsSync(filePath)) return false;
  const content = fs.readFileSync(filePath, "utf-8");
  const updated = updater(content);
  if (content !== updated) {
    fs.writeFileSync(filePath, updated, "utf-8");
    return true;
  }
  return false;
}

async function askYesNo(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => {
    rl.question(question, (ans) => {
      rl.close();
      const lower = ans.trim().toLowerCase();
      resolve(lower === "y" || lower === "yes");
    });
  });
}

async function promptForVersion(current) {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  const nextPatch = computeNextVersion(current, "patch");
  const nextMinor = computeNextVersion(current, "minor");
  const nextMajor = computeNextVersion(current, "major");

  console.log("\n========================================================");
  console.log(` Remote PC Version Manager (Current: v${current})`);
  console.log("========================================================");
  console.log(` 1) patch -> v${nextPatch}`);
  console.log(` 2) minor -> v${nextMinor}`);
  console.log(` 3) major -> v${nextMajor}`);
  console.log(` 4) Enter custom version`);
  console.log("========================================================\n");

  return new Promise((resolve) => {
    rl.question("Select an option [1-4] or enter version: ", (answer) => {
      rl.close();
      const trimmed = answer.trim();
      if (trimmed === "1" || trimmed.toLowerCase() === "patch") resolve(nextPatch);
      else if (trimmed === "2" || trimmed.toLowerCase() === "minor") resolve(nextMinor);
      else if (trimmed === "3" || trimmed.toLowerCase() === "major") resolve(nextMajor);
      else if (trimmed === "4") {
        const rlCustom = readline.createInterface({ input: process.stdin, output: process.stdout });
        rlCustom.question("Enter custom semver (e.g. 3.0.1): ", (customVer) => {
          rlCustom.close();
          resolve(customVer.trim().replace(/^v/i, ""));
        });
      } else if (/^\d+\.\d+\.\d+/.test(trimmed)) {
        resolve(trimmed.replace(/^v/i, ""));
      } else {
        console.error("Cancelled or invalid input.");
        process.exit(1);
      }
    });
  });
}

async function promptForBuildMode() {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  console.log("\n========================================================");
  console.log(" Choose Release Strategy:");
  console.log("========================================================");
  console.log(" 1) Fast Incremental (Smart cache: ~30-60s) [Recommended]");
  console.log(" 2) Instant Release (Skip compilation, upload existing binaries) [~10s]");
  console.log(" 3) Cloud CI/CD (GitHub Actions in cloud - 0s local CPU)");
  console.log(" 4) Windows Setup EXE only");
  console.log(" 5) Android APK only");
  console.log(" 6) Full Clean Rebuild (Rebuild everything from scratch) [~3-4 mins]");
  console.log("========================================================\n");

  return new Promise((resolve) => {
    rl.question("Select option [1-6] (default: 1): ", (answer) => {
      rl.close();
      const trimmed = answer.trim();
      if (!trimmed || trimmed === "1") resolve("fast");
      else if (trimmed === "2") resolve("skip");
      else if (trimmed === "3") resolve("cloud");
      else if (trimmed === "4") resolve("exe-only");
      else if (trimmed === "5") resolve("apk-only");
      else if (trimmed === "6") resolve("clean");
      else resolve("fast");
    });
  });
}

async function main() {
  const currentVersion = getCurrentVersion();
  const isReleaseFlag = process.argv.includes("--release") || process.argv.includes("-r");
  const isSkipBuildFlag = process.argv.includes("--skip-build") || process.argv.includes("--no-build") || process.argv.includes("--skip");
  const isCloudFlag = process.argv.includes("--cloud") || process.argv.includes("--ci");
  const isFastFlag = process.argv.includes("--fast");
  const isExeOnlyFlag = process.argv.includes("--exe-only");
  const isApkOnlyFlag = process.argv.includes("--apk-only");
  const isCleanFlag = process.argv.includes("--clean");

  let buildMode = "fast";
  if (isSkipBuildFlag) buildMode = "skip";
  else if (isCloudFlag) buildMode = "cloud";
  else if (isExeOnlyFlag) buildMode = "exe-only";
  else if (isApkOnlyFlag) buildMode = "apk-only";
  else if (isCleanFlag) buildMode = "clean";
  else if (isFastFlag) buildMode = "fast";

  const nonFlagArgs = process.argv.slice(2).filter((a) => !a.startsWith("-"));
  let targetArg = nonFlagArgs[0];

  let nextVersion = "";
  let shouldRelease = isReleaseFlag;

  if (!targetArg) {
    if (!process.stdin.isTTY) {
      console.log(`Current version: ${currentVersion}`);
      console.log("Usage: node scripts/bump-version.js [patch | minor | major | <version>] [--release] [--fast|--skip|--cloud|--exe-only|--apk-only]");
      process.exit(0);
    }
    nextVersion = await promptForVersion(currentVersion);
    if (!isReleaseFlag) {
      shouldRelease = await askYesNo("Would you like to publish release to GitHub now? (y/N): ");
    }
    if (shouldRelease && !isSkipBuildFlag && !isCloudFlag && !isExeOnlyFlag && !isApkOnlyFlag && !isCleanFlag) {
      buildMode = await promptForBuildMode();
    }
  } else {
    nextVersion = computeNextVersion(currentVersion, targetArg);
  }

  if (!/^\d+\.\d+\.\d+/.test(nextVersion)) {
    console.error(`Error: "${nextVersion}" is not a valid semantic version.`);
    process.exit(1);
  }

  const today = getTodayDateString();
  const modifiedFiles = [];

  // 1. Root package.json
  if (fs.existsSync(FILES.rootPackage)) {
    const pkg = readJson(FILES.rootPackage);
    pkg.version = nextVersion;
    writeJson(FILES.rootPackage, pkg, 2);
    modifiedFiles.push("package.json (root)");
  }

  // 2. desktop-gui/package.json
  if (fs.existsSync(FILES.desktopPackage)) {
    const pkg = readJson(FILES.desktopPackage);
    pkg.version = nextVersion;
    writeJson(FILES.desktopPackage, pkg, 2);
    modifiedFiles.push("desktop-gui/package.json");
  }

  // 3. desktop-gui/src/version.ts
  if (fs.existsSync(FILES.desktopVersionTs)) {
    updateFile(FILES.desktopVersionTs, (content) => {
      let res = content;
      res = res.replace(/export const APP_VERSION = "[^"]*";/, `export const APP_VERSION = "${nextVersion}";`);
      res = res.replace(/export const APP_BUILD_DATE = "[^"]*";/, `export const APP_BUILD_DATE = "${today}";`);
      return res;
    });
    modifiedFiles.push("desktop-gui/src/version.ts");
  } else {
    const versionTsContent = `export const APP_VERSION = "${nextVersion}";\nexport const APP_BUILD_DATE = "${today}";\nexport const APP_NAME = "Remote PC";\n`;
    fs.writeFileSync(FILES.desktopVersionTs, versionTsContent, "utf-8");
    modifiedFiles.push("desktop-gui/src/version.ts (created)");
  }

  // 4. Inno Setup installer.iss
  if (fs.existsSync(FILES.innoSetup)) {
    updateFile(FILES.innoSetup, (content) => {
      return content.replace(/#define\s+MyAppVersion\s+"[^"]*"/, `#define MyAppVersion "${nextVersion}"`);
    });
    modifiedFiles.push("installer.iss");
  }

  // 5. Android build.gradle.kts
  if (fs.existsSync(FILES.androidGradle)) {
    updateFile(FILES.androidGradle, (content) => {
      let res = content;
      res = res.replace(/versionName\s*=\s*"[^"]*"/, `versionName = "${nextVersion}"`);
      res = res.replace(/versionCode\s*=\s*(\d+)/, (_, code) => {
        const nextCode = parseInt(code, 10) + 1;
        return `versionCode = ${nextCode}`;
      });
      return res;
    });
    modifiedFiles.push("client/app/build.gradle.kts");
  }

  // 6. Server configs
  for (const cfg of [FILES.serverConfig, FILES.rootConfig]) {
    if (fs.existsSync(cfg)) {
      try {
        const data = readJson(cfg);
        if (data && typeof data === "object") {
          data.version = nextVersion;
          writeJson(cfg, data, 2);
          modifiedFiles.push(path.relative(ROOT_DIR, cfg));
        }
      } catch (_) {}
    }
  }

  console.log("\n========================================================");
  console.log(` Successfully bumped version: v${currentVersion} -> v${nextVersion}`);
  console.log(` Release Date: ${today}`);
  console.log("========================================================");
  console.log("Updated files:");
  modifiedFiles.forEach((file) => console.log(`  ✓ ${file}`));

  if (shouldRelease) {
    console.log("\n========================================================");
    console.log(` Executing Automated Release Pipeline for v${nextVersion} (Mode: ${buildMode.toUpperCase()})`);
    console.log("========================================================");

    const token = loadEnvToken();
    const repo = "Priyadharsant/Remote-Pc";
    const remoteUrl = token
      ? `https://${token}@github.com/${repo}.git`
      : "origin";

    // 1. Stage and commit code-only
    try {
      console.log("\n📦 Staging and committing release version files...");
      execSync("git add -A", { stdio: "inherit", cwd: ROOT_DIR });
      execSync(`git commit -m "chore(release): v${nextVersion}"`, {
        stdio: "inherit",
        cwd: ROOT_DIR,
      });
      console.log(`✓ Committed release commit for v${nextVersion}`);
    } catch (gitErr) {
      console.warn("⚠️ Git commit notice:", gitErr.message);
    }

    // 2. Create git tag
    try {
      execSync(`git tag -a v${nextVersion} -m "Remote PC Release v${nextVersion}"`, {
        stdio: "inherit",
        cwd: ROOT_DIR,
      });
      console.log(`✓ Created git tag v${nextVersion}`);
    } catch (tagErr) {
      console.warn("⚠️ Git tag notice:", tagErr.message);
    }

    // 3. Push commit & tags
    try {
      console.log("\n🚀 Pushing release commit and tags to GitHub...");
      execSync(`git push ${remoteUrl} main && git push ${remoteUrl} --tags`, {
        stdio: "inherit",
        cwd: ROOT_DIR,
      });
      console.log("✓ Pushed commit and tags to GitHub");
    } catch (pushErr) {
      console.warn("⚠️ Git push notice:", pushErr.message);
    }

    // If Cloud build mode, GitHub Actions handles compilation in the cloud!
    if (buildMode === "cloud") {
      console.log("\n========================================================");
      console.log("☁️  Cloud CI/CD Triggered!");
      console.log(`GitHub Actions will now build Windows Setup EXE and Android APK in parallel.`);
      console.log(`Local compilation time: 0 seconds!`);
      console.log(`Track workflow progress at: https://github.com/${repo}/actions`);
      console.log("========================================================\n");
      return;
    }

    // 4. Build Android APK
    const needApk = buildMode === "fast" || buildMode === "apk-only" || buildMode === "clean";
    if (needApk) {
      console.log(`\n📱 Compiling Android APK with Gradle (--build-cache)...`);
      const clientDir = path.join(ROOT_DIR, "client");
      const gradlewBat = path.join(clientDir, "gradlew.bat");
      if (fs.existsSync(gradlewBat)) {
        const gradleArgs = buildMode === "clean" ? "clean assembleDebug --build-cache" : "assembleDebug --build-cache";
        execSync(`"${gradlewBat}" ${gradleArgs}`, { stdio: "inherit", cwd: clientDir });
        const builtApk = path.join(clientDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
        const rootApk = path.join(ROOT_DIR, "Remote PC.apk");
        if (fs.existsSync(builtApk)) {
          fs.copyFileSync(builtApk, rootApk);
          const stats = fs.statSync(rootApk);
          console.log(`✓ Android APK compiled: Remote PC.apk (${(stats.size / (1024 * 1024)).toFixed(1)} MB)`);
        }
      }
    } else if (buildMode === "skip") {
      console.log("⚡ Skipping Android build (using existing Remote PC.apk)");
    }

    // 5. Build Windows Setup EXE
    const needExe = buildMode === "fast" || buildMode === "exe-only" || buildMode === "clean";
    if (needExe) {
      console.log(`\n🖥️  Compiling Windows Executable & Inno Setup Installer...`);
      const venvPython = path.join(ROOT_DIR, "server", ".venv", "Scripts", "python.exe");
      const pythonExe = fs.existsSync(venvPython) ? `"${venvPython}"` : "python";
      const exeFlag = buildMode === "clean" ? "--clean" : "--fast";
      execSync(`${pythonExe} build_exe.py ${exeFlag}`, { stdio: "inherit", cwd: ROOT_DIR });
      console.log("✓ Windows Setup installer ready!");
    } else if (buildMode === "skip") {
      console.log("⚡ Skipping Windows build (using existing Remote_PC_Setup.exe)");
    }

    // 6. Upload release assets
    console.log(`\n🚀 Uploading release assets to GitHub Releases...`);
    let uploadFlag = "";
    if (buildMode === "exe-only") uploadFlag = " --exe-only";
    if (buildMode === "apk-only") uploadFlag = " --apk-only";
    execSync(`node scripts/upload-release.js${uploadFlag}`, { stdio: "inherit", cwd: ROOT_DIR });
  } else {
    console.log("--------------------------------------------------------");
    console.log("Next steps (manual):");
    console.log(`  1. Build Fast      : npm run build:fast`);
    console.log(`  2. Instant Release : npm run release:quick`);
    console.log(`  3. Cloud Release   : npm run release:cloud`);
    console.log(`  (Or run 'npm run release' to choose interactively)`);
    console.log("========================================================\n");
  }
}

main().catch((err) => {
  console.error("Error updating version:", err);
  process.exit(1);
});
