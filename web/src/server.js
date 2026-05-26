// server.js — Express 5 + ws bootstrap.

import 'dotenv/config';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';

import { api } from './routes.js';
import { scooterNames } from './scooter-names.js';
import { ingestWss } from './ingest.js';
import { liveWss } from './broadcast.js';
import {
  checkIngestAuth, checkViewAuth, requireView,
  ingestAuthEnabled, viewAuthEnabled
} from './auth.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(__dirname, '..', 'public');

const PORT = Number.parseInt(process.env.PORT, 10) || 8787;
const BIND = process.env.BIND_ADDR || '0.0.0.0';

const app = express();
app.disable('x-powered-by');

// Trust the first proxy hop so req.ip / X-Forwarded-* are honoured behind nginx.
app.set('trust proxy', 1);

// Static assets — built Tailwind output, vendor JS/CSS, custom JS.
// Intentionally NOT gated by VIEW_TOKEN — these contain no telemetry, and gating
// them would break the OBS Browser Source caching story. The data behind /api/*
// and /ws/live is what's protected.
//
// Cache-Strategie: eigene Assets (/dist, /css, /js) ohne max-age, nur ETag-
// Revalidation — der Browser sendet If-None-Match, Server antwortet mit 304
// wenn unverändert (kein Body, paar Bytes). Damit landen npm-run-build-
// Updates SOFORT beim nächsten Page-Load, ohne dass User Ctrl+F5 drücken
// muss. Mit dem alten max-age=1h hat selbst Ctrl+F5 in manchen Browsern
// (Firefox, OBS-CEF) die Asset-Revalidation übersprungen.
//
// /vendor (uPlot dist) bleibt mit langer TTL — das ist gepinnter
// Third-Party-Code, ändert sich nicht zwischen Builds.
const ownAssetOpts = { maxAge: 0, etag: true, lastModified: true };
app.use('/dist', express.static(path.join(publicDir, 'dist'),   ownAssetOpts));
app.use('/css',  express.static(path.join(publicDir, 'css'),    ownAssetOpts));
app.use('/js',   express.static(path.join(publicDir, 'js'),     ownAssetOpts));
app.use('/vendor', express.static(path.join(publicDir, 'vendor'), { maxAge: '7d', etag: true, immutable: true }));

// Health probe — public so uptime monitors / k8s / nginx upstream checks work
// without needing a token. Doesn't leak telemetry.
app.get('/healthz', (_req, res) => res.type('text/plain').send('ok'));

// Scooter-Anzeigename-Sync: eigener Mount VOR `/api/*` damit der eigene
// Auth-Pfad (INGEST_TOKEN ODER VIEW_TOKEN) den `requireView`-Block
// bypassen kann — das Smartphone kennt nur das INGEST_TOKEN.
app.use('/api/scooter-name', scooterNames);

// View-gated routes: JSON API + HTML pages.
app.use('/api', requireView, api);

// HTML-Pages bekommen explizit no-cache mit, damit User nach Server-Updates
// nicht mit ge-cachten Asset-Referenzen oder veralteten DOM-Strukturen
// hängenbleibt. Die HTML-Files sind winzig, Re-Fetch kostet quasi nichts.
function sendHtmlNoCache(filePath) {
  return (_req, res) => {
    res.setHeader('Cache-Control', 'no-cache, must-revalidate');
    res.sendFile(filePath);
  };
}
app.get('/',        requireView, sendHtmlNoCache(path.join(publicDir, 'dashboard.html')));
app.get('/obs',     requireView, sendHtmlNoCache(path.join(publicDir, 'obs.html')));
app.get('/history', requireView, sendHtmlNoCache(path.join(publicDir, 'history.html')));

const server = http.createServer(app);

// Route WebSocket upgrades to the matching WSS, gated by the right token.
server.on('upgrade', (req, socket, head) => {
  let pathname;
  try {
    pathname = new URL(req.url, 'http://placeholder').pathname;
  } catch {
    socket.destroy();
    return;
  }
  if (pathname === '/ws/ingest') {
    if (!checkIngestAuth(req)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    ingestWss.handleUpgrade(req, socket, head, (ws) => {
      ingestWss.emit('connection', ws, req);
    });
  } else if (pathname === '/ws/live') {
    if (!checkViewAuth(req)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    liveWss.handleUpgrade(req, socket, head, (ws) => {
      liveWss.emit('connection', ws, req);
    });
  } else {
    socket.destroy();
  }
});

server.listen(PORT, BIND, () => {
  console.log(`[epf-cockpit-web] listening on http://${BIND}:${PORT}`);
  console.log(`  ingest auth: ${ingestAuthEnabled ? 'ON (INGEST_TOKEN set)' : 'OFF (open)'}`);
  console.log(`  view auth:   ${viewAuthEnabled   ? 'ON (VIEW_TOKEN set)'   : 'OFF (open)'}`);
});

// Graceful shutdown — flushes WAL on exit via the natural Database finalizer.
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log(`[epf-cockpit-web] ${sig} received, shutting down`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  });
}
