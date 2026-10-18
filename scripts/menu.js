#!/usr/bin/env node

/**
 * Interactive Command Center & Menu for Remote PC
 * Provides a 1-click interactive terminal interface for:
 *   - Releases (Fast, Instant/Quick, Cloud CI/CD, Platform-Specific, Clean)
 *   - Builds (Windows EXE, Android APK, Fast Bundling)
 *   - GitHub Release Asset Uploads
 *   - Version Bumping
 *   - Local Development & Testing
 *
 * Usage:
 *   npm start
 *   npm run menu
 *   node scripts/menu.js
 */

import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import readline from "readline";
import { spawn } from "child_process";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, "..");

function getCurrentVersion() {
  try {
    const pkgPath = path.join(ROOT_DIR, "package.json");
    if (fs.existsSync(pkgPath)) {
      const data = JSON.parse(fs.readFileSync(pkgPath, "utf-8"));
      if (data && data.version) return data.version;
    }
  } catch (_) {}
  return "3.0.1";
}

function runCommand(command, args = [], cwd = ROOT_DIR) {
  return new Promise((resolve) => {
    console.log(`\n▶ Running: ${command} ${args.join(" ")}\n`);
    const proc = spawn(command, args, {
      cwd,
      stdio: "inherit",
      shell: true,
    });

    proc.on("close", (code) => {
      console.log(`\n✔ Process completed with exit code: ${code}\n`);
      resolve(code);
    });

    proc.on("error", (err) => {
      console.error(`\n❌ Error running process:`, err.message);
      resolve(1);
    });
  });
}

function askQuestion(rl, query) {
  return new Promise((resolve) => {
    rl.question(query, (ans) => resolve(ans.trim()));
  });
}

async function showMenu() {
  const version = getCurrentVersion();
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  console.clear();
  console.log("==================================================================");
  console.log(` 🖥️   REMOTE PC — DEVELOPER & RELEASE COMMAND CENTER (v${version})`);
  console.log("==================================================================");
  console.log(" --- RELEASE PIPELINES (Bump, Tag, Push, Upload) ---");
  console.log("  [1]  🚀 Release Fast         Incremental build + upload (~30-60s) [Recommended]");
  console.log("  [2]  ⚡ Quick Release        Instant release with existing binaries (~10s)");
  console.log("  [3]  ☁️  Cloud Release        Push tag to GitHub Actions CI (0s local CPU)");
  console.log("  [4]  🖥️  Release Windows Only Fast EXE bump, build & upload");
  console.log("  [5]  📱 Release Android Only Fast APK bump, build & upload");
  console.log("  [6]  🧼 Clean Release        Full rebuild from scratch + upload");
  console.log("");
  console.log(" --- BUILDS (Local Compilation Only) ---");
  console.log("  [7]  🔨 Build All Binaries   Compile both Android APK and Windows EXE");
  console.log("  [8]  🖥️  Build Windows EXE    Fast incremental Inno Setup (Remote_PC_Setup.exe)");
  console.log("  [9]  📱 Build Android APK    Gradle build-cached APK (Remote PC.apk)");
  console.log("  [10] 📦 Bundle Installer     Inno Setup only on existing binaries (~12s)");
  console.log("");
  console.log(" --- GITHUB & METADATA ---");
  console.log("  [11] 🚀 Upload Assets Only   Upload existing root .exe & .apk to GitHub Release");
  console.log("  [12] ⚙️  Bump Version Only    Interactive semver bump (patch / minor / major)");
  console.log("");
  console.log(" --- LOCAL DEVELOPMENT & RUNNERS ---");
  console.log("  [13] 💻 Run Desktop GUI Dev  Start Vite + React frontend (hot reload)");
  console.log("  [14] 🐍 Run Python Server    Start headless backend with Python .venv");
  console.log("");
  console.log("  [0]  ❌ Exit");
  console.log("==================================================================");

  const choice = await askQuestion(rl, " Select option [0-14]: ");
  rl.close();

  switch (choice) {
    case "1":
      await runCommand("node", ["scripts/bump-version.js", "patch", "--release", "--fast"]);
      break;

    case "2":
      await runCommand("node", ["scripts/bump-version.js", "patch", "--release", "--skip"]);
      break;

    case "3":
      await runCommand("node", ["scripts/bump-version.js", "patch", "--release", "--cloud"]);
      break;

    case "4":
      await runCommand("node", ["scripts/bump-version.js", "patch", "--release", "--exe-only"]);
      break;

    case "5":
      await runCommand("node", ["scripts/bump-version.js", "patch", "--release", "--apk-only"]);
      break;

    case "6":
      await runCommand("node", ["scripts/bump-version.js", "patch", "--release", "--clean"]);
      break;

    case "7":
      console.log("\nBuilding Android APK and Windows Setup EXE...\n");
      await runCommand("npm", ["run", "build:apk"]);
      await runCommand("python", ["build_exe.py", "--fast"]);
      break;

    case "8":
      await runCommand("python", ["build_exe.py", "--fast"]);
      break;

    case "9":
      await runCommand("npm", ["run", "build:apk"]);
      break;

    case "10":
      await runCommand("python", ["build_exe.py", "--installer-only"]);
      break;

    case "11":
      await runCommand("node", ["scripts/upload-release.js"]);
      break;

    case "12":
      await runCommand("node", ["scripts/bump-version.js"]);
      break;

    case "13":
      await runCommand("npm", ["run", "dev"], path.join(ROOT_DIR, "desktop-gui"));
      break;

    case "14": {
      const venvPython = path.join(ROOT_DIR, "server", ".venv", "Scripts", "python.exe");
      const pythonExe = fs.existsSync(venvPython) ? `"${venvPython}"` : "python";
      await runCommand(pythonExe, ["Remote_Server.py"], path.join(ROOT_DIR, "server"));
      break;
    }

    case "0":
    case "exit":
    case "q":
      console.log("\nGoodbye! 👋\n");
      process.exit(0);
      return;

    default:
      console.log("\n⚠️ Invalid option selected.");
      break;
  }

  // Ask if user wants to return to the menu
  const rlAgain = readline.createInterface({ input: process.stdin, output: process.stdout });
  const again = await askQuestion(rlAgain, "\nReturn to Menu? (Y/n): ");
  rlAgain.close();

  if (again.toLowerCase() !== "n" && again.toLowerCase() !== "no") {
    await showMenu();
  } else {
    console.log("\nGoodbye! 👋\n");
  }
}

showMenu().catch((err) => {
  console.error("Menu encountered an error:", err);
  process.exit(1);
});
