// db.js — better-sqlite3 init, schema, and prepared-statement helpers.
// Synchronous SQLite is fine for a single-tenant personal dashboard and
// keeps the ingest hot path zero-await.

import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const DB_PATH = process.env.DB_PATH || './data/epf.db';

// Ensure the parent directory exists so first-run on a clean checkout works.
mkdirSync(dirname(resolve(DB_PATH)), { recursive: true });

export const db = new Database(DB_PATH);

// WAL + NORMAL is the standard "fast and safe enough" combo for a local server.
db.pragma('journal_mode = WAL');
db.pragma('synchronous = NORMAL');
db.pragma('foreign_keys = ON');

db.exec(`
  CREATE TABLE IF NOT EXISTS trips (
    id TEXT PRIMARY KEY,
    started_at INTEGER NOT NULL,
    ended_at INTEGER,
    distance_km REAL,
    energy_wh REAL,
    max_speed_kmh REAL,
    avg_speed_kmh REAL,
    duration_s INTEGER,
    scooter_name TEXT
  );

  CREATE TABLE IF NOT EXISTS telemetry (
    ts INTEGER NOT NULL,
    trip_id TEXT,
    speed_kmh REAL,
    battery_percent INTEGER,
    voltage_v REAL,
    current_a REAL,
    power_w REAL,
    controller_temp_c REAL,
    parking INTEGER,
    ride_mode_stand INTEGER
  );
  CREATE INDEX IF NOT EXISTS idx_telemetry_trip ON telemetry(trip_id, ts);
  CREATE INDEX IF NOT EXISTS idx_telemetry_ts ON telemetry(ts);
`);

// Prepared statements — created once, reused on every insert/query.
const stmts = {
  insertTelemetry: db.prepare(`
    INSERT INTO telemetry
      (ts, trip_id, speed_kmh, battery_percent, voltage_v, current_a,
       power_w, controller_temp_c, parking, ride_mode_stand)
    VALUES
      (@ts, @tripId, @speedKmh, @batteryPercent, @voltageV, @currentA,
       @powerW, @controllerTempC, @parking, @rideModeStand)
  `),

  insertTrip: db.prepare(`
    INSERT OR IGNORE INTO trips (id, started_at, scooter_name)
    VALUES (?, ?, ?)
  `),

  finalizeTrip: db.prepare(`
    UPDATE trips
       SET ended_at = @endedAt,
           distance_km = @distanceKm,
           energy_wh = @energyWh,
           max_speed_kmh = @maxSpeedKmh,
           avg_speed_kmh = @avgSpeedKmh,
           duration_s = @durationS
     WHERE id = @id
  `),

  listTrips: db.prepare(`
    SELECT id, started_at, ended_at, distance_km, energy_wh,
           max_speed_kmh, avg_speed_kmh, duration_s, scooter_name
      FROM trips
     ORDER BY started_at DESC
     LIMIT ?
  `),

  getTrip: db.prepare(`
    SELECT id, started_at, ended_at, distance_km, energy_wh,
           max_speed_kmh, avg_speed_kmh, duration_s, scooter_name
      FROM trips
     WHERE id = ?
  `),

  getTripSamples: db.prepare(`
    SELECT ts, speed_kmh AS speedKmh, power_w AS powerW
      FROM telemetry
     WHERE trip_id = ?
     ORDER BY ts ASC
  `)
};

export function insertTelemetry(row) {
  stmts.insertTelemetry.run({
    ts: row.ts ?? Date.now(),
    tripId: row.tripId ?? null,
    speedKmh: row.speedKmh ?? null,
    batteryPercent: row.batteryPercent ?? null,
    voltageV: row.voltageV ?? null,
    currentA: row.currentA ?? null,
    powerW: row.powerW ?? null,
    controllerTempC: row.controllerTempC ?? null,
    parking: row.parking == null ? null : (row.parking ? 1 : 0),
    rideModeStand: row.rideModeStand == null ? null : (row.rideModeStand ? 1 : 0)
  });
}

export function startTrip({ id, startedAt, scooterName }) {
  stmts.insertTrip.run(id, startedAt, scooterName ?? null);
}

// trip_end + (implicit) any final telemetry catch-up wrapped in a tx.
export const finalizeTripTx = db.transaction((finalRow) => {
  stmts.finalizeTrip.run(finalRow);
});

export function listTrips(limit = 100) {
  return stmts.listTrips.all(Math.max(1, Math.min(1000, limit | 0)));
}

export function getTrip(id) {
  return stmts.getTrip.get(id);
}

export function getTripSamples(id) {
  return stmts.getTripSamples.all(id);
}
