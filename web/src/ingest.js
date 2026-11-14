// ingest.js — /ws/ingest handler. The Android app connects here and streams
// JSON frames (one per text message). Auth (INGEST_TOKEN) happens on upgrade.

import { WebSocketServer } from 'ws';
import { insertTelemetry, insertLocation, upsertScooterName } from './db.js';
import {
  broadcast, setLastState, setLastTelemetry
} from './broadcast.js';
import {
  getCurrentTripId, handleTripStart, handleTripEnd, handleTripCancel
} from './trips.js';

const wss = new WebSocketServer({ noServer: true });

// Track the scooter name from the most recent hello/state for trip rows.
let lastScooterName = null;

// Der zuletzt verbundene Ingest-Socket. Wechselt das Handy das Netz (WLAN →
// Mobilfunk), steht der neue Socket oft schon, bevor der alte sein Timeout
// bemerkt — dessen spätes `close` darf das Dashboard nicht auf "offline" kippen.
let activeSocket = null;

/**
 * Verarbeitet eine bereits geparste Ingest-Nachricht. `reply` schickt eine
 * Antwort an die App zurück (derzeit nur `trip_ack`). Exportiert für Tests.
 */
export function handleIngestMessage(msg, reply = () => {}) {
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
          // Kein trip_start gesehen (ältere App) → FK schlägt fehl. Der Punkt
          // kommt mit dem trip_end-Backfill nach, also kein Log-Spam im Sekundentakt.
          if (err.code !== 'SQLITE_CONSTRAINT_FOREIGNKEY') {
            console.warn('[ingest] insertLocation failed:', err.message);
          }
        }
      }
      broadcast({
        type: 'location',
        tripId, ts: locTs, lat: msg.lat, lon: msg.lon, accuracyM, speedMps,
      });
      break;
    }

    case 'trip_start': {
      try {
        handleTripStart({ tripId: msg.tripId, ts: msg.ts, scooterName: lastScooterName });
      } catch (err) {
        console.warn('[ingest] trip_start failed:', err.message);
      }
      broadcast(msg);
      break;
    }

    case 'trip_end': {
      let ackId = null;
      try {
        ackId = handleTripEnd(msg, lastScooterName);
      } catch (err) {
        console.warn('[ingest] trip_end failed:', err.message);
      }
      // Quittung: die App markiert die Fahrt als synchronisiert und schickt
      // sie beim nächsten Connect nicht noch einmal.
      if (ackId) reply({ type: 'trip_ack', tripId: ackId });
      // Die (ggf. tausenden) Waypoints braucht kein Live-Client.
      const { locations: _locations, ...slim } = msg;
      broadcast(slim);
      break;
    }

    case 'trip_cancel': {
      try {
        handleTripCancel({ tripId: msg.tripId });
      } catch (err) {
        console.warn('[ingest] trip_cancel failed:', err.message);
      }
      broadcast(msg);
      break;
    }

    default:
      // Unknown frame types are forwarded as-is so future fields don't
      // require a server bump.
      broadcast(msg);
  }
}

wss.on('connection', (ws, req) => {
  const peer = req.socket.remoteAddress;
  console.log(`[ingest] client connected from ${peer}`);
  activeSocket = ws;

  ws.on('close', () => {
    console.log(`[ingest] client disconnected from ${peer}`);
    // Nur der aktuelle Socket bestimmt den Online-Zustand.
    if (ws !== activeSocket) return;
    activeSocket = null;
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
    handleIngestMessage(msg, (frame) => {
      if (ws.readyState !== 1 /* OPEN */) return;
      try { ws.send(JSON.stringify(frame)); } catch { /* socket closing */ }
    });
  });
});

export const ingestWss = wss;
