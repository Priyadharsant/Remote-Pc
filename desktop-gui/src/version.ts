/**
 * Remote PC Application Version & Auto-Update Engine
 * Modeled after Finance Application's version.js architecture
 */

export const APP_VERSION = "3.1.0";
export const APP_BUILD_DATE = "2026-10-19";
export const APP_NAME = "Remote PC";
export const GITHUB_REPO = "Priyadharsant/Remote-Pc";
export const DEFAULT_RAW_GITHUB_DOWNLOAD_URL = `https://github.com/${GITHUB_REPO}/releases/download/v${APP_VERSION}/Remote_PC_Setup.exe`;

export interface AppUpdateData {
  success: boolean;
  hasUpdate: boolean;
  isMandatory: boolean;
  currentVersion: string;
  latestVersion: string;
  releaseDate?: string;
  downloadUrl?: string;
  releaseNotes: string[];
  error?: string;
}

/**
 * Semver comparator: returns 1 if vA > vB, -1 if vA < vB, 0 if equal
 */
export function compareSemver(vA = "0.0.0", vB = "0.0.0"): number {
  const cleanA = String(vA).replace(/^v/i, "").split(".").map((n) => parseInt(n, 10) || 0);
  const cleanB = String(vB).replace(/^v/i, "").split(".").map((n) => parseInt(n, 10) || 0);

  for (let i = 0; i < Math.max(cleanA.length, cleanB.length); i++) {
    const a = cleanA[i] || 0;
    const b = cleanB[i] || 0;
    if (a > b) return 1;
    if (a < b) return -1;
  }
  return 0;
}

/**
 * Checks for available updates.
 * High-speed direct GitHub releases check (~150ms) with fallback to local server endpoint.
 */
export async function checkForAppUpdates(): Promise<AppUpdateData> {
  // 1. Fast Path: Direct GitHub Releases API
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 4000);

    const ghRes = await fetch(
      `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`,
      {
        signal: controller.signal,
        headers: { Accept: "application/vnd.github.v3+json" },
      }
    );
    clearTimeout(timeoutId);

    if (ghRes.ok) {
      const ghData = await ghRes.json();
      const rawTag = ghData.tag_name || ghData.name || "";
      const latestVer = rawTag.replace(/^v/i, "").trim();

      const exeAsset = ghData.assets?.find(
        (a: any) =>
          typeof a.name === "string" &&
          (a.name.endsWith(".exe") || a.name.includes("Setup") || a.name.includes("Remote_PC"))
      );

      const downloadUrl =
        exeAsset?.browser_download_url ||
        `https://github.com/${GITHUB_REPO}/releases/download/${rawTag}/Remote_PC_Setup.exe`;

      const hasUpdate = compareSemver(latestVer, APP_VERSION) > 0;
      return {
        success: true,
        latestVersion: latestVer,
        currentVersion: APP_VERSION,
        hasUpdate,
        isMandatory: hasUpdate,
        releaseDate: ghData.published_at ? ghData.published_at.slice(0, 10) : "",
        downloadUrl,
        releaseNotes: ghData.body
          ? ghData.body
              .split("\n")
              .map((l: string) => l.trim().replace(/^[-*]\s*/, ""))
              .filter(Boolean)
          : [
              "Zero-latency wireless trackpad & keyboard control",
              "Instant 10s playback forward and backward seek",
              "Direct storage file streaming and fast mobile uploads",
              "Automated multi-stream in-app updates",
            ],
      };
    }
  } catch (ghErr: any) {
    console.warn("[VersionCheck] GitHub fast check notice:", ghErr?.message);
  }

  // 2. Fallback Path: Local Remote Server endpoint
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 3000);
    const res = await fetch("http://127.0.0.1:5055/system/update/check", {
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });
    clearTimeout(timeoutId);
    if (!res.ok) throw new Error("Could not check server updates");
    const data = await res.json();
    const latestVer = data.latest_version ? String(data.latest_version).replace(/^v/i, "") : APP_VERSION;
    const hasUpdate = Boolean(data.update_available) || compareSemver(latestVer, APP_VERSION) > 0;
    return {
      success: true,
      hasUpdate,
      isMandatory: hasUpdate,
      currentVersion: APP_VERSION,
      latestVersion: latestVer,
      downloadUrl: data.exe_url || DEFAULT_RAW_GITHUB_DOWNLOAD_URL,
      releaseNotes: data.release_notes
        ? data.release_notes
            .split("\n")
            .map((l: string) => l.trim().replace(/^[-*]\s*/, ""))
            .filter(Boolean)
        : ["Latest stability and performance improvements"],
    };
  } catch (err: any) {
    return {
      success: false,
      hasUpdate: false,
      isMandatory: false,
      currentVersion: APP_VERSION,
      latestVersion: APP_VERSION,
      releaseNotes: [],
      error: err?.message || "Failed to check for updates",
    };
  }
}
