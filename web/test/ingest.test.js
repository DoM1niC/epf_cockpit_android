// Läuft gegen eine In-Memory-DB (DB_PATH=:memory:, siehe npm test).
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

import {
  db, getTrip, getTripSamples, getTripLocations, listTrips,
  getTelemetryInRange, insertTelemetry, pruneStaleUnfinishedTrips
} from '../src/db.js';
import { handleIngestMessage } from '../src/ingest.js';
import { getCurrentTripId, resetCurrentTrip } from '../src/trips.js';
import { pruneTelemetry } from '../src/maintenance.js';
import { getSnapshot } from '../src/broadcast.js';

const T0 = 1_700_000_000_000;

function send(msg) {
  const replies = [];
  handleIngestMessage(msg, (r) => replies.push(r));
  return replies;
}

const tele = (ts, speedKmh = 10) => ({
  type: 'telemetry', ts, speedKmh, batteryPercent: 80, voltageV: 50,
  currentA: 2, powerW: 100, controllerTempC: 30, parking: false, rideModeStand: true
});
const loc = (tripId, ts) => ({ type: 'location', tripId, ts, lat: 50.1, lon: 8.6 });
const tripEnd = (tripId, startedAt, ts, extra = {}) => ({
  type: 'trip_end', tripId, startedAt, ts, distanceKm: 1.5, energyWh: 40,
  durationS: Math.round((ts - startedAt) / 1000), maxSpeedKmh: 25, avgSpeedKmh: 18,
  scooterAddress: 'AA:BB', ...extra
});

beforeEach(() => {
  db.exec('DELETE FROM trip_locations; DELETE FROM telemetry; DELETE FROM trips;');
  resetCurrentTrip();
});

test('trip_start: Live-Waypoints und Telemetrie landen sofort bei der Fahrt', () => {
  send({ type: 'trip_start', tripId: 'local-1', ts: T0 });
  assert.equal(getCurrentTripId(), 'local-1');
  send(tele(T0 + 1000));
  send(loc('local-1', T0 + 1000));
  assert.equal(getTripSamples('local-1').length, 1);
  assert.equal(getTripLocations('local-1').length, 1);
  assert.equal(getTrip('local-1').ended_at, null);
});

test('wiederholtes trip_start (Reconnect) ist idempotent', () => {
  send({ type: 'trip_start', tripId: 'local-1', ts: T0 });
  send({ type: 'trip_start', tripId: 'local-1', ts: T0 + 5000 });
  assert.equal(listTrips().length, 1);
  assert.equal(getTrip('local-1').started_at, T0);
});

test('Waypoint ohne bekannte Fahrt wirft nicht und wird nicht gespeichert', () => {
  assert.doesNotThrow(() => send(loc('local-99', T0)));
  assert.equal(getTripLocations('local-99').length, 0);
});

test('trip_end: quittiert, setzt das Fahrt-Fenster und ordnet Telemetrie neu zu', () => {
  send({ type: 'trip_start', tripId: 'local-1', ts: T0 });
  send(tele(T0 + 1_000, 0));    // Standzeit vor dem Losfahren (live getaggt)
  send(tele(T0 + 60_000));      // Fahrt
  send(tele(T0 + 90_000));      // Fahrt
  send(tele(T0 + 200_000, 0));  // Standzeit danach (live getaggt)

  const replies = send(tripEnd('local-1', T0 + 50_000, T0 + 100_000, {
    locations: [
      { ts: T0 + 60_000, lat: 50.1, lon: 8.6, accuracy_m: 4 },
      { ts: T0 + 61_000, lat: 50.2, lon: 8.7 },
      { ts: 'kaputt', lat: 1, lon: 2 }
    ]
  }));

  assert.deepEqual(replies, [{ type: 'trip_ack', tripId: 'local-1' }]);
  const trip = getTrip('local-1');
  assert.equal(trip.started_at, T0 + 50_000);
  assert.equal(trip.ended_at, T0 + 100_000);
  assert.equal(trip.distance_km, 1.5);
  assert.deepEqual(getTripSamples('local-1').map((s) => s.ts), [T0 + 60_000, T0 + 90_000]);
  assert.equal(getTripLocations('local-1').length, 2);
  assert.equal(getCurrentTripId(), null);
});

test('trip_end ohne vorheriges trip_start (Backfill / alte App) funktioniert', () => {
  insertTelemetry(tele(T0 + 10_000));
  const replies = send(tripEnd('local-7', T0, T0 + 20_000));
  assert.equal(replies.length, 1);
  assert.equal(getTrip('local-7').started_at, T0);
  assert.equal(getTripSamples('local-7').length, 1);
});

test('erneutes trip_end ist idempotent', () => {
  const end = tripEnd('local-1', T0, T0 + 20_000, { locations: [{ ts: T0 + 1, lat: 1, lon: 2 }] });
  send(end);
  send(end);
  assert.equal(listTrips().length, 1);
  assert.equal(getTripLocations('local-1').length, 1);
});

test('trip_end ohne tripId wird nicht quittiert', () => {
  assert.deepEqual(send({ type: 'trip_end', ts: T0 }), []);
  assert.equal(listTrips().length, 0);
});

test('trip_cancel verwirft eine laufende Fahrt samt Waypoints', () => {
  send({ type: 'trip_start', tripId: 'local-1', ts: T0 });
  send(tele(T0 + 1000));
  send(loc('local-1', T0 + 1000));
  send({ type: 'trip_cancel', tripId: 'local-1' });
  assert.equal(getTrip('local-1'), undefined);
  assert.equal(getCurrentTripId(), null);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM trip_locations').get().c, 0);
  // Telemetrie bleibt, aber ohne Fahrt-Zuordnung.
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM telemetry WHERE trip_id IS NULL').get().c, 1);
});

test('trip_cancel rührt eine abgeschlossene Fahrt nicht an', () => {
  send(tripEnd('local-1', T0, T0 + 20_000));
  send({ type: 'trip_cancel', tripId: 'local-1' });
  assert.ok(getTrip('local-1'));
});

test('verwaiste, nie beendete Fahrten werden aufgeräumt', () => {
  send({ type: 'trip_start', tripId: 'alt', ts: T0 });
  send({ type: 'trip_start', tripId: 'aktuell', ts: T0 });
  send(tripEnd('fertig', T0, T0 + 1000));
  assert.equal(pruneStaleUnfinishedTrips(T0 + 1, 'aktuell'), 1);
  assert.deepEqual(listTrips().map((t) => t.id).sort(), ['aktuell', 'fertig']);
});

test('Retention löscht nur alte Telemetrie ohne Fahrt', async () => {
  const day = 24 * 60 * 60 * 1000;
  const now = T0 + 100 * day;
  insertTelemetry({ ...tele(now - 40 * day), tripId: null });       // alt, ohne Fahrt → weg
  insertTelemetry({ ...tele(now - 40 * day + 1), tripId: 'x' });    // alt, mit Fahrt → bleibt
  insertTelemetry({ ...tele(now - 5 * day), tripId: null });        // jung → bleibt
  assert.equal(await pruneTelemetry(now, 30), 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM telemetry').get().c, 2);
  assert.equal(await pruneTelemetry(now, 0), 0); // 0 = aus
});

test('getTelemetryInRange dampft große Bereiche auf Buckets ein', () => {
  for (let i = 0; i < 100; i++) insertTelemetry(tele(T0 + i * 1000, i));
  assert.equal(getTelemetryInRange(T0, T0 + 100_000, 1000).length, 100);
  const down = getTelemetryInRange(T0, T0 + 100_000, 10);
  assert.ok(down.length <= 11 && down.length >= 9, `buckets: ${down.length}`);
  assert.ok(down[0].speedKmh < down.at(-1).speedKmh);
});

test('hello/state setzen den Snapshot', () => {
  send({ type: 'hello', scooterName: 'Flitzer', authenticated: true });
  assert.deepEqual(getSnapshot().state, {
    type: 'state', connected: true, authenticated: true, scooterName: 'Flitzer'
  });
});
