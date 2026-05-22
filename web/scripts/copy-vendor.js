// copy-vendor.js — vendors uPlot's built dist into public/vendor/uplot/
// so the browser never hits a CDN at runtime.

import { mkdir, copyFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

const src = path.join(root, 'node_modules', 'uplot', 'dist');
const dst = path.join(root, 'public', 'vendor', 'uplot');

const files = ['uPlot.min.css', 'uPlot.iife.min.js'];

if (!existsSync(src)) {
  console.error(`[copy-vendor] uplot not installed at ${src}. Run \`npm install\` first.`);
  process.exit(1);
}

await mkdir(dst, { recursive: true });

for (const f of files) {
  const from = path.join(src, f);
  const to = path.join(dst, f);
  if (!existsSync(from)) {
    console.error(`[copy-vendor] missing ${from}`);
    process.exit(1);
  }
  await copyFile(from, to);
  console.log(`[copy-vendor] ${f} -> public/vendor/uplot/`);
}

console.log('[copy-vendor] done');
