// auth.js — shared-token authentication.
//
// Two independent tokens, both optional:
//   INGEST_TOKEN  — required on /ws/ingest (Android app side)
//   VIEW_TOKEN    — required on /, /obs, /history, /api/*, /ws/live
//
// If a token env var is unset/empty the corresponding side is open (legacy
// behaviour). If it's set we enforce strictly. Tokens are compared with
// crypto.timingSafeEqual to keep token-extraction attacks impractical.

import { timingSafeEqual } from 'node:crypto';

const INGEST_TOKEN = (process.env.INGEST_TOKEN || '').trim();
const VIEW_TOKEN   = (process.env.VIEW_TOKEN   || '').trim();

export const ingestAuthEnabled = INGEST_TOKEN.length > 0;
export const viewAuthEnabled   = VIEW_TOKEN.length   > 0;

/** Constant-time equality check. Returns false for any length mismatch. */
function tokenEq(actual, expected) {
  if (typeof actual !== 'string' || typeof expected !== 'string') return false;
  if (actual.length !== expected.length) return false;
  // timingSafeEqual throws if Buffer lengths differ, but we just verified equal length.
  return timingSafeEqual(Buffer.from(actual, 'utf8'), Buffer.from(expected, 'utf8'));
}

/**
 * Extract a presented token from the request. Order:
 *   1. Authorization: Bearer <token>
 *   2. ?token=<token>  query string
 *   3. Cookie: epf_token=<token>
 */
function extractToken(req) {
  const auth = req.headers?.authorization;
  if (auth && auth.toLowerCase().startsWith('bearer ')) {
    return auth.slice(7).trim();
  }
  // req.url has the path + query in the raw HTTP upgrade case too.
  try {
    const u = new URL(req.url, 'http://placeholder');
    const q = u.searchParams.get('token');
    if (q) return q;
  } catch { /* ignore malformed URL */ }
  const cookie = req.headers?.cookie;
  if (cookie) {
    for (const part of cookie.split(';')) {
      const [k, v] = part.trim().split('=');
      if (k === 'epf_token' && v) return decodeURIComponent(v);
    }
  }
  return null;
}

export function checkIngestAuth(req) {
  if (!ingestAuthEnabled) return true;
  const presented = extractToken(req);
  return presented != null && tokenEq(presented, INGEST_TOKEN);
}

export function checkViewAuth(req) {
  if (!viewAuthEnabled) return true;
  const presented = extractToken(req);
  return presented != null && tokenEq(presented, VIEW_TOKEN);
}

/** Express middleware enforcing VIEW_TOKEN. Use for HTML pages and /api/*. */
export function requireView(req, res, next) {
  if (checkViewAuth(req)) return next();
  // Respond with a tiny HTML page if it's a browser, otherwise JSON.
  // We don't redirect to a login page — the token belongs in the URL/header,
  // and surfacing that explicitly is better than a fake-login UX.
  const wantsHtml = (req.headers.accept || '').includes('text/html');
  res.status(401);
  if (wantsHtml) {
    res.type('html').send(
      `<!doctype html><html lang="de"><head><meta charset="utf-8"><title>401 — Token erforderlich</title>` +
      `<style>body{font:14px/1.4 ui-sans-serif,system-ui;background:#0E1B16;color:#e9ecef;` +
      `padding:48px;max-width:560px;margin:0 auto}h1{color:#6FDBC2;margin:0 0 12px}` +
      `code{background:#1A3D33;color:#FFB300;padding:2px 6px;border-radius:4px}</style></head>` +
      `<body><h1>Anmeldung erforderlich</h1>` +
      `<p>Dieses Dashboard ist zugriffsgeschützt. Hänge <code>?token=&lt;dein-token&gt;</code> ` +
      `an die URL an oder sende einen <code>Authorization: Bearer &lt;dein-token&gt;</code>-Header.</p>` +
      `</body></html>`
    );
  } else {
    res.json({ error: 'view_token_required' });
  }
}
