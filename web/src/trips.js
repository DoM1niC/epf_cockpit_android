// trips.js — trip lifecycle state.
// The Android app may emit trip_start / trip_end as hints, but we keep
// our own current-trip pointer so telemetry inserts can always be tagged
// with a trip_id (or null when no trip is active).

import { startTrip, finalizeTripTx, linkTelemetryToTrip } from './db.js';

let currentTripId = null;

export function getCurrentTripId() {
  return currentTripId;
}

export function handleTripStart({ tripId, ts, scooterName }) {
  if (!tripId) return;
  currentTripId = tripId;
  startTrip({
    id: tripId,
    startedAt: ts ?? Date.now(),
    scooterName: scooterName ?? null
  });
}

export function handleTripEnd(payload, fallbackScooterName = null) {
  const id = payload.tripId ?? currentTripId;
  if (!id) return;
  // Insert-or-ignore so trip_end is self-contained — the client no longer
  // needs to send trip_start first. This is robust against the very common
  // case where a short trip is finalized in one go without ever streaming a
  // separate trip_start (e.g. when the app pushes everything from
  // TripCoordinator.finalizeAndClear).
  startTrip({
    id,
    startedAt: payload.startedAt ?? payload.ts ?? Date.now(),
    scooterName: payload.scooterAddress ?? fallbackScooterName
  });
  const endedAt = payload.ts ?? Date.now();
  const startedAt = payload.startedAt ?? endedAt;
  finalizeTripTx({
    id,
    endedAt,
    distanceKm: payload.distanceKm ?? null,
    energyWh: payload.energyWh ?? null,
    maxSpeedKmh: payload.maxSpeedKmh ?? null,
    avgSpeedKmh: payload.avgSpeedKmh ?? null,
    durationS: payload.durationS ?? null
  });
  // Retroaktiv alle Telemetry-Frames die zwischen startedAt und endedAt
  // eingelaufen sind aber noch keinen trip_id haben, an diese Fahrt
  // hängen. Damit die Detail-Ansicht "no samples" nicht mehr zeigt für
  // Fahrten die live gestreamt wurden.
  linkTelemetryToTrip({ tripId: id, startedAt, endedAt });
  if (id === currentTripId) currentTripId = null;
}
