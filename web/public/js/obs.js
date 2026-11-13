// obs.js — minimal WebSocket client for the OBS Browser Source overlay.

(function () {
  'use strict';

  // ---------- Query-param theming ----------
  const params = new URLSearchParams(location.search);
  const theme   = params.get('theme')   || 'dark';
  const accent  = params.get('accent')  || 'teal';
  const compact = params.get('compact') === '1';

  const root = document.getElementById('obs-root');
  if (compact) {
    root.classList.remove('inline-flex');
    root.classList.add('flex', 'flex-col', 'items-start', 'gap-2');
  }
  if (theme === 'light') {
    // .obs-light schaltet die Farb-Variablen in obs.css um (dunkler Text,
    // heller Schatten); text-black bleibt für Abwärtskompatibilität.
    document.body.classList.add('obs-light', 'text-black');
  }
  // Swap accent class on the speed number (and its gauge icon) based on
  // ?accent. Unbekannte Werte fallen auf teal zurück.
  const accentCls = ['teal', 'amber', 'magenta'].includes(accent) ? `accent-${accent}` : 'accent-teal';
  root.querySelectorAll('.accent-teal').forEach(el => {
    el.classList.remove('accent-teal');
    el.classList.add(accentCls);
  });

  // ---------- DOM update ----------
  const fmt = (v, digits = 0) =>
    v == null || Number.isNaN(v) ? '--' : Number(v).toFixed(digits);

  function applyTelemetry(t) {
    document.getElementById('obs-speed').textContent = fmt(t.speedKmh, 0);
    document.getElementById('obs-batt').textContent  = fmt(t.batteryPercent, 0);
    document.getElementById('obs-power').textContent = fmt(t.powerW, 0);
  }

  // ---------- WebSocket with exponential backoff ----------
  let ws = null;
  let backoff = 500;
  const MAX_BACKOFF = 15000;

  function connect() {
    ws = new WebSocket(window.EpfAuth.wsUrl('/ws/live'));

    ws.addEventListener('open', () => { backoff = 500; });

    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg && msg.type === 'telemetry') applyTelemetry(msg);
    });

    ws.addEventListener('close', () => {
      setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, MAX_BACKOFF);
    });

    ws.addEventListener('error', () => { try { ws.close(); } catch {} });
  }

  // Snapshot for first paint
  window.EpfAuth.fetchAuthed('/api/state').then(r => r.json()).then(snap => {
    if (snap.telemetry) applyTelemetry(snap.telemetry);
  }).catch(() => {});

  connect();
})();
