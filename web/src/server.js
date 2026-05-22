// server.js — Express 5 + ws bootstrap.

import 'dotenv/config';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';

import { api } from './routes.js';
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
app.use('/dist', express.static(path.join(publicDir, 'dist'), { maxAge: '1h' }));
app.use('/css',  express.static(path.join(publicDir, 'css'),  { maxAge: '1h' }));
app.use('/js',   express.static(path.join(publicDir, 'js'),   { maxAge: '1h' }));
app.use('/vendor', express.static(path.join(publicDir, 'vendor'), { maxAge: '1d' }));

// Health probe — public so uptime monitors / k8s / nginx upstream checks work
// without needing a token. Doesn't leak telemetry.
app.get('/healthz', (_req, res) => res.type('text/plain').send('ok'));

// View-gated routes: JSON API + HTML pages.
app.use('/api', requireView, api);
app.get('/',        requireView, (_req, res) => res.sendFile(path.join(publicDir, 'dashboard.html')));
app.get('/obs',     requireView, (_req, res) => res.sendFile(path.join(publicDir, 'obs.html')));
app.get('/history', requireView, (_req, res) => res.sendFile(path.join(publicDir, 'history.html')));

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
