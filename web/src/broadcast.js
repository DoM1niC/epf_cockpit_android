// broadcast.js — /ws/live handler. Browsers subscribe here; we forward
// telemetry / state / trip_* messages from the ingest side.
// Also tracks a tiny in-memory "last state" for new connections and for /api/state.

import { WebSocketServer } from 'ws';

const wss = new WebSocketServer({ noServer: true });

// Last known state from the Android app (or null if nothing seen yet).
let lastState = {
  type: 'state',
  connected: false,
  authenticated: false,
  scooterName: null
};

// Last telemetry frame — handy for /api/state and for new browser clients
// so the dashboard tiles aren't blank for the first second after page load.
let lastTelemetry = null;

export function getSnapshot() {
  return {
    state: lastState,
    telemetry: lastTelemetry
  };
}

export function setLastState(stateFrame) {
  lastState = {
    type: 'state',
    connected: !!stateFrame.connected,
    authenticated: !!stateFrame.authenticated,
    scooterName: stateFrame.scooterName ?? null
  };
}

export function setLastTelemetry(telemetryFrame) {
  lastTelemetry = telemetryFrame;
}

export function broadcast(frame) {
  const json = JSON.stringify(frame);
  for (const client of wss.clients) {
    if (client.readyState === 1 /* OPEN */) {
      try { client.send(json); } catch { /* ignore broken pipe */ }
    }
  }
}

wss.on('connection', (ws) => {
  // Send a snapshot so the new client gets a usable view immediately.
  try {
    ws.send(JSON.stringify(lastState));
    if (lastTelemetry) ws.send(JSON.stringify(lastTelemetry));
  } catch { /* socket may already be closing */ }

  // Browser → server frames are not expected; drop them quietly.
  ws.on('message', () => { /* no-op */ });
});

export const liveWss = wss;
