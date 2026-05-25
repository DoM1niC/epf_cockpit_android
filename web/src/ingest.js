// ingest.js — /ws/ingest handler. The Android app connects here and streams
// JSON frames (one per text message). No auth — single-user personal dashboard.

import { WebSocketServer } from 'ws';
import { insertTelemetry, insertLocation } from './db.js';
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
        // GPS-Waypoint live während der Fahrt. Wir tag-en auf den jeweils
        // aktuellen Trip wenn der Client keinen tripId mitschickt — analog
        // zu telemetry.
        const tripId = msg.tripId ?? getCurrentTripId();
        if (!tripId || typeof msg.lat !== 'number' || typeof msg.lon !== 'number') {
          // Kein Trip aktiv oder Koordinaten fehlen — verwerfen statt zu
          // raten. Trip_end-Backfill räumt das später nach.
          break;
        }
        try {
          insertLocation({
            tripId,
            ts:        msg.ts ?? Date.now(),
            lat:       msg.lat,
            lon:       msg.lon,
            accuracyM: msg.accuracy_m ?? msg.accuracyM ?? null,
            speedMps:  msg.speed_mps  ?? msg.speedMps  ?? null,
          });
        } catch (err) {
          console.warn('[ingest] insertLocation failed:', err.message);
        }
        // Live-Locations forwarden wir nicht aufs Live-Channel — der Dashboard
        // braucht das (noch) nicht und es würde nur Lärm erzeugen.
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
