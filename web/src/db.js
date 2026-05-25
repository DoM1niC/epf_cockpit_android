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

  CREATE TABLE IF NOT EXISTS trip_locations (
    trip_id    TEXT    NOT NULL,
    ts         INTEGER NOT NULL,
    lat        REAL    NOT NULL,
    lon        REAL    NOT NULL,
    accuracy_m REAL,
    speed_mps  REAL,
    PRIMARY KEY (trip_id, ts),
    FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE
  );
  CREATE INDEX IF NOT EXISTS idx_trip_locations_trip ON trip_locations(trip_id);
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
  listTripsInRange: db.prepare(`
    SELECT id, started_at, ended_at, distance_km, energy_wh,
           max_speed_kmh, avg_speed_kmh, duration_s, scooter_name
      FROM trips
     WHERE started_at BETWEEN @from AND @to
     ORDER BY started_at DESC
     LIMIT @limit
  `),

  getTrip: db.prepare(`
    SELECT id, started_at, ended_at, distance_km, energy_wh,
           max_speed_kmh, avg_speed_kmh, duration_s, scooter_name
      FROM trips
     WHERE id = ?
  `),

  getTripSamples: db.prepare(`
    SELECT ts,
           speed_kmh        AS speedKmh,
           power_w          AS powerW,
           battery_percent  AS batteryPercent
      FROM telemetry
     WHERE trip_id = ?
     ORDER BY ts ASC
  `),

  // Retroaktives Linking: Telemetry-Frames die während der Fahrt eingelaufen
  // sind aber noch keinen trip_id haben (weil der Client kein trip_start
  // mehr sendet — trip_end ist self-contained), beim Trip-Finalize über das
  // ts-Fenster nachtragen.
  linkTelemetryToTrip: db.prepare(`
    UPDATE telemetry
       SET trip_id = @tripId
     WHERE trip_id IS NULL
       AND ts BETWEEN @startedAt AND @endedAt
  `),

  // GPS-Waypoints. INSERT OR IGNORE damit der Backfill aus trip_end keine
  // Konflikte mit live gestreamten Punkten gleichen Zeitstempels macht.
  insertLocation: db.prepare(`
    INSERT OR IGNORE INTO trip_locations
      (trip_id, ts, lat, lon, accuracy_m, speed_mps)
    VALUES
      (@tripId, @ts, @lat, @lon, @accuracyM, @speedMps)
  `),

  getTripLocations: db.prepare(`
    SELECT ts,
           lat,
           lon,
           accuracy_m AS accuracyM,
           speed_mps  AS speedMps
      FROM trip_locations
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

export function listTripsInRange(from, to, limit = 1000) {
  return stmts.listTripsInRange.all({
    from, to,
    limit: Math.max(1, Math.min(5000, limit | 0))
  });
}

export function getTrip(id) {
  return stmts.getTrip.get(id);
}

export function getTripSamples(id) {
  return stmts.getTripSamples.all(id);
}

export function linkTelemetryToTrip(args) {
  return stmts.linkTelemetryToTrip.run(args);
}

export function insertLocation(row) {
  stmts.insertLocation.run({
    tripId:    row.tripId,
    ts:        row.ts ?? Date.now(),
    lat:       row.lat,
    lon:       row.lon,
    accuracyM: row.accuracyM ?? null,
    speedMps:  row.speedMps  ?? null,
  });
}

export function getTripLocations(tripId) {
  return stmts.getTripLocations.all(tripId);
}

// Batch-Insert für den trip_end-Backfill. In einer Transaction, weil das bei
// längeren Fahrten schnell ein paar hundert Punkte sind und Einzel-Inserts
// hier WAL-Schreibverkehr unnötig vervielfachen.
export const insertTripLocationsTx = db.transaction((tripId, locations) => {
  for (const loc of locations) {
    if (loc == null || loc.lat == null || loc.lon == null || loc.ts == null) continue;
    stmts.insertLocation.run({
      tripId,
      ts:        loc.ts,
      lat:       loc.lat,
      lon:       loc.lon,
      accuracyM: loc.accuracy_m ?? loc.accuracyM ?? null,
      speedMps:  loc.speed_mps  ?? loc.speedMps  ?? null,
    });
  }
});

export function insertTripLocations(tripId, locations) {
  if (!tripId || !Array.isArray(locations) || locations.length === 0) return;
  insertTripLocationsTx(tripId, locations);
}

/**
 * Telemetry-Samples in [from, to] (in ms). Wenn die Anzahl > `max`, wird
 * per Bucket-Average auf ~max Punkte runtergedampft. Damit funktionieren
 * auch grosse Zeitraeume (7d ~= 3M Rows roh) ohne Browser-OOM.
 */
export function getTelemetryInRange(from, to, max) {
  const count = db.prepare(
    `SELECT COUNT(*) AS c FROM telemetry WHERE ts BETWEEN ? AND ?`
  ).get(from, to).c;
  if (count === 0) return [];
  if (count <= max) {
    return db.prepare(`
      SELECT ts,
             speed_kmh        AS speedKmh,
             power_w          AS powerW,
             battery_percent  AS batteryPercent,
             voltage_v        AS voltageV,
             current_a        AS currentA
        FROM telemetry
       WHERE ts BETWEEN ? AND ?
       ORDER BY ts ASC
    `).all(from, to);
  }
  // Bucket-Avg-Downsample. Bucket-Center als ts zurueckgeben, damit der
  // Chart die x-Werte zentriert ueber dem Bucket sieht.
  const bucketMs = Math.max(1, Math.ceil((to - from) / max));
  return db.prepare(`
    SELECT (CAST(ts/? AS INTEGER) * ? + ?/2) AS ts,
           AVG(speed_kmh)        AS speedKmh,
           AVG(power_w)          AS powerW,
           AVG(battery_percent)  AS batteryPercent,
           AVG(voltage_v)        AS voltageV,
           AVG(current_a)        AS currentA
      FROM telemetry
     WHERE ts BETWEEN ? AND ?
     GROUP BY CAST(ts/? AS INTEGER)
     ORDER BY ts ASC
  `).all(bucketMs, bucketMs, bucketMs, from, to, bucketMs);
}
