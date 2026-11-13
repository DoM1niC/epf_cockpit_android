// copy-vendor.js — vendors third-party JS/CSS into public/vendor/ so the
// browser never hits a CDN at runtime.
//
// Currently mirrored:
//   - uPlot     -> public/vendor/uplot/
//   - Leaflet   -> public/vendor/leaflet/ (incl. images/ for default markers)
//   - Inter     -> public/vendor/inter/ (Variable-Font, latin + latin-ext)

import { mkdir, copyFile, readdir, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');

async function copyFiles(srcDir, dstDir, files, label) {
  if (!existsSync(srcDir)) {
    console.error(`[copy-vendor] ${label} not installed at ${srcDir}. Run \`npm install\` first.`);
    process.exit(1);
  }
  await mkdir(dstDir, { recursive: true });
  for (const f of files) {
    const from = path.join(srcDir, f);
    const to   = path.join(dstDir, f);
    if (!existsSync(from)) {
      console.error(`[copy-vendor] missing ${from}`);
      process.exit(1);
    }
    await copyFile(from, to);
    console.log(`[copy-vendor] ${label}: ${f} -> ${path.relative(root, dstDir)}/`);
  }
}

// Rekursive flache Kopie eines Verzeichnisses — wird nur für Leaflets
// `images/` gebraucht (Marker-PNGs + Schatten). Wir bleiben absichtlich
// simpel und brauchen keine externen Abhängigkeiten.
async function copyDir(srcDir, dstDir, label) {
  if (!existsSync(srcDir)) {
    console.warn(`[copy-vendor] ${label}: dir missing at ${srcDir}, skipping`);
    return;
  }
  await mkdir(dstDir, { recursive: true });
  const entries = await readdir(srcDir);
  for (const name of entries) {
    const from = path.join(srcDir, name);
    const to   = path.join(dstDir, name);
    const st = await stat(from);
    if (st.isDirectory()) {
      await copyDir(from, to, label);
    } else {
      await copyFile(from, to);
      console.log(`[copy-vendor] ${label}: ${path.relative(srcDir, from)} -> ${path.relative(root, dstDir)}/`);
    }
  }
}

// --- uPlot ---
await copyFiles(
  path.join(root, 'node_modules', 'uplot', 'dist'),
  path.join(root, 'public', 'vendor', 'uplot'),
  ['uPlot.min.css', 'uPlot.iife.min.js'],
  'uplot'
);

// --- Leaflet ---
// leaflet npm-Package legt die Files unter dist/ ab. Wir nehmen die minified
// Varianten und kopieren zusätzlich das images/-Verzeichnis (default-Marker
// + Schatten), das die leaflet.css via `url(images/...)` relativ referenziert.
const leafletSrc = path.join(root, 'node_modules', 'leaflet', 'dist');
const leafletDst = path.join(root, 'public', 'vendor', 'leaflet');
await copyFiles(leafletSrc, leafletDst, ['leaflet.js', 'leaflet.css'], 'leaflet');
await copyDir(
  path.join(leafletSrc, 'images'),
  path.join(leafletDst, 'images'),
  'leaflet'
);

// --- Inter Variable (Schrift) ---
// Self-hosted statt Google-Fonts-CDN. Nur die latin- und latin-ext-Subsets
// (deutsche Umlaute stecken in latin) mit opsz+wght-Achse — die opsz-Achse
// schaltet bei großen Ziffern automatisch auf den "Display"-Schnitt um.
// Die @font-face-Regeln stehen in public/css/tailwind.css.
await copyFiles(
  path.join(root, 'node_modules', '@fontsource-variable', 'inter', 'files'),
  path.join(root, 'public', 'vendor', 'inter'),
  ['inter-latin-opsz-normal.woff2', 'inter-latin-ext-opsz-normal.woff2'],
  'inter'
);
await copyFiles(
  path.join(root, 'node_modules', '@fontsource-variable', 'inter'),
  path.join(root, 'public', 'vendor', 'inter'),
  ['LICENSE'],
  'inter'
);

console.log('[copy-vendor] done');
