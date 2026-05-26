// ingest.js — /ws/ingest handler. The Android app connects here and streams
// JSON frames (one per text message). No auth — single-user personal dashboard.

import { WebSocketServer } from 'ws';
import { insertTelemetry, insertLocation, upsertScooterName } from './db.js';
import {
  broadcast, setLastState, setLastTelemetry
} from './broadcast.js';
import {
  getCurrentTripId, handleTripStart, handleTripEnd
} from './trips.js';

const wss = new WebSocketServer({ noServer: true });

// Track the scooter name from the most recent hello/state for trip rows.
let lastScooterName = null;

wss.on('connection', (ws, req) => {
  const peer = req.socket.remoteAddress;
  console.log(`[ingest] client connected from ${peer}`);

  // If the app disconnects we should reflect that on the live channel too,
  // unless a fresh ingest socket reconnects within a heartbeat.
  ws.on('close', () => {
    console.log(`[ingest] client disconnected from ${peer}`);
    const downFrame = {
      type: 'state',
      connected: false,
      authenticated: false,
      scooterName: null
    };
    setLastState(downFrame);
    broadcast(downFrame);
  });

  ws.on('error', (err) => {
    console.warn(`[ingest] socket error from ${peer}:`, err.message);
  });

  ws.on('message', (data, isBinary) => {
    if (isBinary) return; // contract is text JSON only
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch (err) {
      console.warn('[ingest] bad JSON:', err.message);
      return;
    }
    if (!msg || typeof msg !== 'object' || typeof msg.type !== 'string') return;

    switch (msg.type) {
      case 'hello': {
        lastScooterName = msg.scooterName ?? null;
        // Wenn das Smartphone Adresse + non-empty Name liefert, in
        // scooter_names auto-upserten — damit ein neues Smartphone den
        // Namen beim ersten Connect via GET /api/scooter-name abholen
        // kann, ohne dass der User ihn re-tippt. Leerer Name darf NICHT
        // ueberschreiben, sonst loescht ein neues Geraet ohne lokalen
        // Namen den serverseitigen.
        if (typeof msg.scooterAddress === 'string' && msg.scooterAddress.trim()
            && typeof msg.scooterName === 'string' && msg.scooterName.trim()) {
          try {
            upsertScooterName(msg.scooterAddress.trim(), msg.scooterName.trim());
          } catch (err) {
            console.warn('[ingest] upsertScooterName (hello) failed:', err.message);
          }
        }
        const stateFrame = {
          type: 'state',
          connected: true,
          authenticated: !!msg.authenticated,
          scooterName: lastScooterName
        };
        setLastState(stateFrame);
        broadcast(stateFrame);
        break;
      }

      case 'state': {
        if (msg.scooterName) lastScooterName = msg.scooterName;
        setLastState(msg);
        broadcast(msg);
        break;
      }

      case 'telemetry': {
        const tripId = getCurrentTripId();
        try {
          insertTelemetry({ ...msg, tripId });
        } catch (err) {
          console.warn('[ingest] insertTelemetry failed:', err.message);
        }
        setLastTelemetry(msg);
        broadcast(msg);
        break;
      }

      case 'location': {
        // GPS-Waypoint live. Wenn der Client keine tripId mitschickt, fallen
        // wir auf den aktuell aktiven Trip zurueck. Beides null → Idle-Fix:
        // nur broadcasten (Dashboard-Marker), kein DB-Insert weil es keinen
        // Trip-Bucket fuer den Punkt gaebe.
        if (typeof msg.lat !== 'number' || typeof msg.lon !== 'number') break;
        const tripId = msg.tripId ?? getCurrentTripId() ?? null;
        const locTs = msg.ts ?? Date.now();
        const accuracyM = msg.accuracy_m ?? msg.accuracyM ?? null;
        const speedMps  = msg.speed_mps  ?? msg.speedMps  ?? null;
        if (tripId) {
          try {
            insertLocation({ tripId, ts: locTs, lat: msg.lat, lon: msg.lon, accuracyM, speedMps });
          } catch (err) {
            console.warn('[ingest] insertLocation failed:', err.message);
          }
        }
        broadcast({
          type: 'location',
          tripId, ts: locTs, lat: msg.lat, lon: msg.lon, accuracyM, speedMps,
        });
        break;
      }

      case 'trip_start': {
        handleTripStart({
          tripId: msg.tripId,
          ts: msg.ts,
          scooterName: lastScooterName
        });
        broadcast(msg);
        break;
      }

      case 'trip_end': {
        handleTripEnd(msg, lastScooterName);
        broadcast(msg);
        break;
      }

      default:
        // Unknown frame types are forwarded as-is so future fields don't
        // require a server bump.
        broadcast(msg);
    }
  });
});

export const ingestWss = wss;
