// dashboard.js — live dashboard logic.
// - Connects to /ws/live with exponential-backoff reconnect.
// - Updates DOM tiles + status chips on every telemetry frame.
// - Maintains a rolling 5-minute buffer for the two uPlot charts.

(function () {
  'use strict';

  const WINDOW_MS = 5 * 60 * 1000; // 5 minutes (live buffer)
  const buf = { ts: [], speed: [], power: [] };

  // Time-range presets for the chart selector. "live" = use the rolling
  // in-memory buffer driven by the WS stream; everything else = one-shot
  // fetch from /api/telemetry, charts then stay frozen until the user
  // picks a new range (or "Live").
  const RANGE_PRESETS = [
    { id: 'live5m', label: 'Live 5m', ms: 5 * 60_000,           live: true  },
    { id: 'm15',    label: '15 min',  ms: 15 * 60_000,          live: false },
    { id: 'h1',     label: '1 h',     ms: 60 * 60_000,          live: false },
    { id: 'h6',     label: '6 h',     ms: 6 * 60 * 60_000,      live: false },
    { id: 'h24',    label: '24 h',    ms: 24 * 60 * 60_000,     live: false },
    { id: 'd7',     label: '7 d',     ms: 7 * 24 * 60 * 60_000, live: false },
  ];
  let activeRange = RANGE_PRESETS[0];
  // Wenn nicht-live: gefrorenes from/to-Fenster, damit die Achsen-range()
  // nicht weiterwandert während der User den Chart anschaut.
  let frozenFrom = null;
  let frozenTo = null;
  const isLive = () => activeRange.live;

  // uPlot Default-Werte sind US-locale (12h + M/D/YY). EU-Variante mit 24h-Zeit.
  // Das Tick-Default ist *self-contained* (z.B. "19:52:03"), die Kontext-Zeile
  // (year/month/day-Slot) bringt NUR das Datum dazu — keine Doppelung der Zeit.
  // Vorher hatten wir auf Grenzen "DD.MM.YYYY HH:MM:SS" was zur Tick-Beschriftung
  // ":58" doppelt und chaotisch wirkte.
  const EU_AXIS_VALUES = [
    [3600 * 24 * 365, "{YYYY}",            null,             null, null,           null, null, null, 1],
    [3600 * 24 * 28,  "{MMM}",             "\n{YYYY}",       null, null,           null, null, null, 1],
    [3600 * 24,       "{DD}.{MM}.",        "\n{YYYY}",       null, null,           null, null, null, 1],
    [3600,            "{HH}:{mm}",         "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [60,              "{HH}:{mm}",         "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [1,               "{HH}:{mm}:{ss}",    "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [0.001,           "{HH}:{mm}:{ss}.{fff}", "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
  ];

  // Cursor-Tooltip (Hover) — gleiches 24h/EU-Format wie Achsen.
  const cursorTimeFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  });
  function cursorValue(_self, rawValue) {
    return rawValue == null ? '--' : cursorTimeFmt.format(new Date(rawValue * 1000));
  }

  // ---------- DOM helpers ----------
  const $ = (id) => document.getElementById(id);
  const fmt = (v, digits = 0) =>
    v == null || Number.isNaN(v) ? '--' : Number(v).toFixed(digits);

  function setText(id, text) {
    const el = $(id);
    if (el) el.textContent = text;
  }

  // ---------- Charts ----------
  // Auf schmalen Viewports (Mobile) ist 200 px im Quadrat-Verhältnis zu hoch
  // und drängt die Tile-Reihe nach unten. 160 px gibt mehr Platz für die Werte.
  function chartHeight() {
    return window.innerWidth < 640 ? 160 : 200;
  }

  function makeChart(host, label, color, unit, digits) {
    const opts = {
      width: host.clientWidth || 600,
      height: chartHeight(),
      scales: {
        x: {
          time: true,
          // Im Live-Mode pinnen wir das Fenster auf "letzte 5 Min" und es
          // wandert mit der Zeit mit. Im History-Mode haben wir nach dem
          // Fetch ein gefrorenes from/to gesetzt — das nutzen wir hier dann.
          range: () => {
            if (isLive()) {
              const now = Date.now() / 1000;
              return [now - WINDOW_MS / 1000, now];
            }
            return [frozenFrom / 1000, frozenTo / 1000];
          },
        },
      },
      // Legende EIN — zeigt beim Hover Zeit + aktuellen Wert an.
      // isolate:true heißt: Klick auf eine Serie blendet andere aus (für uns
      // egal, wir haben nur eine). live:true heißt: Werte unter dem Cursor
      // werden in der Legende angezeigt.
      legend: { show: true, live: true },
      cursor: { drag: { x: false, y: false } },
      axes: [
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' }, values: EU_AXIS_VALUES },
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' } }
      ],
      series: [
        { label: 'Zeit', value: cursorValue },
        {
          label,
          stroke: color,
          width: 2,
          fill: color + '22',
          spanGaps: true,
          // Formatter für den Hover-Wert in der Legende.
          value: (_self, v) => v == null ? '--' : `${v.toFixed(digits)} ${unit}`,
        }
      ]
    };
    return new uPlot(opts, [[], []], host);
  }

  const chartSpeed = makeChart($('chart-speed'), 'km/h', '#4DD0E1', 'km/h', 1);
  const chartPower = makeChart($('chart-power'), 'W',    '#E91E63', 'W',    0);

  // Resize charts on viewport change so the cards stay tidy.
  function resizeCharts() {
    const h = chartHeight();
    chartSpeed.setSize({ width: $('chart-speed').clientWidth, height: h });
    chartPower.setSize({ width: $('chart-power').clientWidth, height: h });
  }
  window.addEventListener('resize', resizeCharts);
  window.addEventListener('orientationchange', resizeCharts);

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
    // Im History-Mode den Chart NICHT mit Live-Punkten überschreiben — der
    // User schaut sich gerade einen festen Zeitraum an, die Tiles sollen
    // aber weiter live laufen (das passiert in applyTelemetry, nicht hier).
    if (isLive()) {
      chartSpeed.setData([buf.ts, buf.speed]);
      chartPower.setData([buf.ts, buf.power]);
    }
  }

  // ---------- Range-Chips ----------
  function renderChips() {
    const host = $('range-chips');
    if (!host) return;
    host.innerHTML = RANGE_PRESETS.map(p => {
      const active = p.id === activeRange.id;
      const cls = active
        ? 'bg-[var(--color-brand-teal)] text-black'
        : 'bg-[var(--color-surface-2)] text-[var(--color-on-surface-dim)] hover:text-white';
      return `<button data-range="${p.id}" class="range-chip text-xs px-3 py-1 rounded-full ${cls}">${p.label}</button>`;
    }).join('');
    host.querySelectorAll('.range-chip').forEach(btn => {
      btn.addEventListener('click', () => applyRange(btn.dataset.range));
    });
  }

  function setRangeStatus(text) {
    const el = $('range-status');
    if (el) el.textContent = text || '';
  }

  async function applyRange(id) {
    const next = RANGE_PRESETS.find(p => p.id === id) || RANGE_PRESETS[0];
    activeRange = next;
    renderChips();

    if (next.live) {
      frozenFrom = null;
      frozenTo = null;
      setRangeStatus('Live');
      chartSpeed.setData([buf.ts, buf.speed]);
      chartPower.setData([buf.ts, buf.power]);
      return;
    }

    setRangeStatus('Lade…');
    const to = Date.now();
    const from = to - next.ms;
    try {
      const r = await window.EpfAuth.fetchAuthed(
        `/api/telemetry?from=${from}&to=${to}&max=600`
      );
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const { samples } = await r.json();
      frozenFrom = from;
      frozenTo = to;
      const xs = samples.map(s => s.ts / 1000);
      const speed = samples.map(s => s.speedKmh);
      const power = samples.map(s => s.powerW);
      chartSpeed.setData([xs, speed]);
      chartPower.setData([xs, power]);
      setRangeStatus(`${samples.length} Punkte`);
    } catch (err) {
      console.warn('range fetch failed:', err);
      setRangeStatus('Fehler – siehe Console');
    }
  }

  renderChips();
  setRangeStatus('Live');

  // ---------- Connection indicator ----------
  function applyState(state) {
    const dot = $('conn-dot');
    const text = $('conn-text');
    if (state && state.connected) {
      dot.className = 'inline-block w-2.5 h-2.5 rounded-full bg-green-500';
      dot.title = 'Scooter verbunden';
      text.textContent = state.scooterName || 'verbunden';
    } else {
      dot.className = 'inline-block w-2.5 h-2.5 rounded-full bg-gray-500';
      dot.title = 'Kein Scooter';
      text.textContent = 'Kein Scooter';
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
    setText('chip-parking',  'Parking: '  + (t.parking ? 'an' : 'aus'));
    setText('chip-ridemode', 'Modus: '    + (t.rideModeStand ? 'Stand' : 'Kick'));
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
