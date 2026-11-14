// maintenance.js — periodisches Aufräumen der SQLite-DB.
//
//  - Roh-Telemetrie OHNE Fahrt-Zuordnung (Standzeiten, Laden, Idle) wird nach
//    TELEMETRY_RETENTION_DAYS gelöscht (Default 30, 0 = nie löschen).
//    Telemetrie, die zu einer Fahrt gehört, bleibt dauerhaft erhalten.
//  - Nie finalisierte Fahrten, die älter als 24 h sind, werden verworfen.

import { pruneUnlinkedTelemetryBatch, pruneStaleUnfinishedTrips } from './db.js';
import { getCurrentTripId } from './trips.js';

const DAY_MS = 24 * 60 * 60 * 1000;
const RUN_EVERY_MS = 6 * 60 * 60 * 1000;
const BATCH = 20000;

function retentionDays() {
  const raw = process.env.TELEMETRY_RETENTION_DAYS;
  if (raw == null || raw.trim() === '') return 30;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : 30;
}

/** Löscht häppchenweise und gibt zwischen den Häppchen den Event-Loop frei. */
export async function pruneTelemetry(now = Date.now(), days = retentionDays()) {
  if (!(days > 0)) return 0;
  const cutoff = now - days * DAY_MS;
  let total = 0;
  for (;;) {
    const n = pruneUnlinkedTelemetryBatch(cutoff, BATCH);
    total += n;
    if (n < BATCH) break;
    await new Promise((r) => setImmediate(r));
  }
  return total;
}

export async function runMaintenance(now = Date.now()) {
  try {
    const trips = pruneStaleUnfinishedTrips(now - DAY_MS, getCurrentTripId());
    if (trips > 0) console.log(`[maintenance] ${trips} nie beendete Fahrt(en) verworfen`);
    const rows = await pruneTelemetry(now);
    if (rows > 0) console.log(`[maintenance] ${rows} alte Telemetrie-Zeilen ohne Fahrt gelöscht`);
  } catch (err) {
    console.warn('[maintenance] failed:', err.message);
  }
}

export function startMaintenance() {
  // Nicht im Startpfad: erst kurz nach dem Hochfahren, dann alle 6 h.
  setTimeout(() => runMaintenance(), 30_000).unref();
  setInterval(() => runMaintenance(), RUN_EVERY_MS).unref();
}
