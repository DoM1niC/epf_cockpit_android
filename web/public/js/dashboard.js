// dashboard.js — live dashboard logic.
// - Connects to /ws/live with exponential-backoff reconnect.
// - Updates DOM tiles + status chips on every telemetry frame.
// - Maintains a rolling 5-minute buffer for the two uPlot charts.

(function () {
  'use strict';

  const WINDOW_MS = 5 * 60 * 1000; // 5 minutes
  const buf = { ts: [], speed: [], power: [] };

  // ---------- DOM helpers ----------
  const $ = (id) => document.getElementById(id);
  const fmt = (v, digits = 0) =>
    v == null || Number.isNaN(v) ? '--' : Number(v).toFixed(digits);

  function setText(id, text) {
    const el = $(id);
    if (el) el.textContent = text;
  }

  // ---------- Charts ----------
  function makeChart(host, label, color) {
    const opts = {
      width: host.clientWidth || 600,
      height: 200,
      scales: { x: { time: true } },
      legend: { show: false },
      cursor: { drag: { x: false, y: false } },
      axes: [
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' } },
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' } }
      ],
      series: [
        {},
        {
          label,
          stroke: color,
          width: 2,
          fill: color + '22',
          spanGaps: true
        }
      ]
    };
    return new uPlot(opts, [[], []], host);
  }

  const chartSpeed = makeChart($('chart-speed'), 'km/h', '#4DD0E1');
  const chartPower = makeChart($('chart-power'), 'W',    '#E91E63');

  // Resize charts on viewport change so the cards stay tidy.
  function resizeCharts() {
    chartSpeed.setSize({ width: $('chart-speed').clientWidth, height: 200 });
    chartPower.setSize({ width: $('chart-power').clientWidth, height: 200 });
  }
  window.addEventListener('resize', resizeCharts);

  function pushPoint(ts, speedKmh, powerW) {
    // uPlot uses seconds for time scales.
    buf.ts.push(ts / 1000);
    buf.speed.push(speedKmh ?? null);
    buf.power.push(powerW ?? null);

    // Drop points older than the window.
    const cutoff = (ts - WINDOW_MS) / 1000;
    while (buf.ts.length && buf.ts[0] < cutoff) {
      buf.ts.shift();
      buf.speed.shift();
      buf.power.shift();
    }
    chartSpeed.setData([buf.ts, buf.speed]);
    chartPower.setData([buf.ts, buf.power]);
  }

  // ---------- Connection indicator ----------
  function applyState(state) {
    const dot = $('conn-dot');
    const text = $('conn-text');
    if (state && state.connected) {
      dot.className = 'inline-block w-2.5 h-2.5 rounded-full bg-green-500';
      dot.title = 'Scooter connected';
      text.textContent = state.scooterName || 'connected';
    } else {
      dot.className = 'inline-block w-2.5 h-2.5 rounded-full bg-gray-500';
      dot.title = 'No scooter';
      text.textContent = 'no scooter';
    }
    setText('chip-scooter', 'Scooter: ' + (state && state.scooterName ? state.scooterName : '--'));
  }

  // ---------- Telemetry → DOM ----------
  function applyTelemetry(t) {
    setText('t-speed', fmt(t.speedKmh, 1));
    setText('t-batt',  fmt(t.batteryPercent, 0));
    setText('t-power', fmt(t.powerW, 0));
    setText('t-volt',  fmt(t.voltageV, 1));
    setText('t-amp',   fmt(t.currentA, 1));
    setText('t-temp',  fmt(t.controllerTempC, 0));
    setText('chip-parking',  'Parking: '  + (t.parking ? 'on' : 'off'));
    setText('chip-ridemode', 'Mode: '     + (t.rideModeStand ? 'Stand' : 'Kick'));
    pushPoint(t.ts || Date.now(), t.speedKmh, t.powerW);
  }

  // ---------- WebSocket with exponential backoff ----------
  let ws = null;
  let backoff = 500; // ms
  const MAX_BACKOFF = 15000;

  function connect() {
    ws = new WebSocket(window.EpfAuth.wsUrl('/ws/live'));

    ws.addEventListener('open', () => {
      backoff = 500;
      console.log('[live] connected');
    });

    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (!msg || !msg.type) return;
      switch (msg.type) {
        case 'state':     applyState(msg); break;
        case 'telemetry': applyTelemetry(msg); break;
        // trip_start / trip_end are not surfaced on the live dashboard yet.
      }
    });

    ws.addEventListener('close', () => {
      console.warn(`[live] disconnected, retry in ${backoff}ms`);
      setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, MAX_BACKOFF);
    });

    ws.addEventListener('error', () => {
      // close handler will manage the reconnect
      try { ws.close(); } catch {}
    });
  }

  // Initial snapshot fetch so the page isn't blank before the first WS frame.
  window.EpfAuth.fetchAuthed('/api/state').then(r => r.json()).then(snap => {
    if (snap.state)     applyState(snap.state);
    if (snap.telemetry) applyTelemetry(snap.telemetry);
  }).catch(() => { /* server might still be starting */ });

  connect();
})();
