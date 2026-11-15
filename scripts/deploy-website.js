#!/usr/bin/env node

/**
 * Deploy website directory directly to GitHub Pages (gh-pages branch)
 */

import fs from 'fs';
import path from 'path';
import os from 'os';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, '..');

function loadToken() {
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN.trim();
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN.trim();

  const envFile = path.join(ROOT_DIR, '.env');
  if (fs.existsSync(envFile)) {
    const content = fs.readFileSync(envFile, 'utf-8');
    const match = content.match(/^(?:GH_TOKEN|GITHUB_TOKEN)=(.*)$/m);
    if (match && match[1].trim()) {
      return match[1].trim().replace(/^["']|["']$/g, '');
    }
  }
  return null;
}

const token = loadToken();
const repo = 'Priyadharsant/Remote-Pc';
const repoUrl = token
  ? `https://${token}@github.com/${repo}.git`
  : `https://github.com/${repo}.git`;

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'gh-pages-'));
console.log(`\n🌐 Deploying Remote PC website to GitHub Pages (${repo})...`);

try {
  let branchExists = false;
  try {
    const lsRemote = execSync(`git ls-remote --heads ${repoUrl} gh-pages`, { encoding: 'utf-8' });
    branchExists = lsRemote.includes('refs/heads/gh-pages');
  } catch (_) {}

  if (branchExists) {
    console.log('Fetching gh-pages branch...');
    execSync(`git clone --depth 1 --branch gh-pages ${repoUrl} "${tempDir}"`, { stdio: 'inherit' });
  } else {
    execSync(`git init "${tempDir}"`, { stdio: 'inherit' });
    execSync(`git checkout -b gh-pages`, { cwd: tempDir, stdio: 'inherit' });
    execSync(`git remote add origin ${repoUrl}`, { cwd: tempDir, stdio: 'inherit' });
  }

  const websiteDir = path.join(ROOT_DIR, 'website');
  fs.cpSync(websiteDir, tempDir, { recursive: true });
  fs.writeFileSync(path.join(tempDir, '.nojekyll'), '', 'utf-8');

  execSync(`git add -A`, { cwd: tempDir, stdio: 'inherit' });
  try {
    execSync(`git commit -m "deploy: update GitHub Pages website"`, { cwd: tempDir, stdio: 'inherit' });
    execSync(`git push -u origin gh-pages`, { cwd: tempDir, stdio: 'inherit' });
    console.log('\n🎉 Successfully deployed website to GitHub Pages!');
    console.log('Live URL: https://priyadharsant.github.io/Remote-Pc/');
  } catch (err) {
    console.log('Site is already up-to-date with remote gh-pages.');
  }
} finally {
  try {
    fs.rmSync(tempDir, { recursive: true, force: true });
  } catch (_) {}
}
