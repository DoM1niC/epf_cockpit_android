// trips.js — trip lifecycle state.
//
// Ablauf aus Sicht der App:
//   trip_start   sobald die App eine Fahrt-Zeile angelegt hat (und erneut nach
//                jedem WebSocket-Reconnect, solange die Fahrt läuft). Ab dann
//                werden Telemetrie und Live-Waypoints dieser Fahrt zugeordnet.
//   trip_end     self-contained Abschluss (Stammdaten + alle Waypoints). Wird
//                auch für den Backfill alter Fahrten benutzt.
//   trip_cancel  die Fahrt wurde verworfen (nie losgefahren / < 10 m).
//
// trip_start ist optional: ältere App-Versionen schicken nur trip_end.

import { startTrip, finalizeTripTx, cancelTripTx } from './db.js';

let currentTripId = null;

export function getCurrentTripId() {
  return currentTripId;
}

/** Nur für Tests. */
export function resetCurrentTrip() {
  currentTripId = null;
}

const isTripId = (v) => typeof v === 'string' && v.length > 0 && v.length <= 128;
const numOrNull = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

export function handleTripStart({ tripId, ts, scooterName }) {
  if (!isTripId(tripId)) return false;
  currentTripId = tripId;
  // INSERT OR IGNORE — ein wiederholtes trip_start (Reconnect) ist harmlos.
  startTrip({
    id: tripId,
    startedAt: numOrNull(ts) ?? Date.now(),
    scooterName: scooterName ?? null
  });
  return true;
}

/**
 * Fahrt abschließen. Liefert die Trip-ID, wenn alles (Stammdaten,
 * Telemetrie-Zuordnung, Waypoints) in der DB steht — nur dann darf der
 * Aufrufer ein trip_ack schicken. Wirft bei DB-Fehlern.
 */
export function handleTripEnd(payload, fallbackScooterName = null) {
  const id = payload.tripId ?? currentTripId;
  if (!isTripId(id)) return null;
  const endedAt = numOrNull(payload.ts) ?? Date.now();
  // startedAt aus dem trip_end gewinnt über das trip_start: die App meldet
  // dort den Moment des Losfahrens, nicht den des Verbindens.
  const startedAt = numOrNull(payload.startedAt);
  finalizeTripTx({
    id,
    startedAt: startedAt != null ? Math.min(startedAt, endedAt) : null,
    endedAt,
    scooterName: payload.scooterAddress ?? fallbackScooterName,
    distanceKm: numOrNull(payload.distanceKm),
    energyWh: numOrNull(payload.energyWh),
    maxSpeedKmh: numOrNull(payload.maxSpeedKmh),
    avgSpeedKmh: numOrNull(payload.avgSpeedKmh),
    durationS: numOrNull(payload.durationS)
  }, Array.isArray(payload.locations) ? payload.locations : null);
  if (id === currentTripId) currentTripId = null;
  return id;
}

/** Nie finalisierte Fahrt verwerfen. Finalisierte Fahrten bleiben erhalten. */
export function handleTripCancel({ tripId }) {
  if (!isTripId(tripId)) return false;
  if (tripId === currentTripId) currentTripId = null;
  return cancelTripTx(tripId);
}
