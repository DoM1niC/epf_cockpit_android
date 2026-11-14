// dashboard.js — live dashboard logic.
// - Connects to /ws/live with exponential-backoff reconnect.
// - Updates Tacho, Kennzahlen + Status-Pills on every telemetry frame.
// - Maintains a rolling 5-minute buffer for the three uPlot charts.
// - Gemeinsame Helfer (Theme, Zahlenformat, Chart-Theme, Tooltip) kommen aus
//   /js/ui.js (window.EpfUi).

(function () {
  'use strict';

  const UI = window.EpfUi;

  const WINDOW_MS = 5 * 60 * 1000; // 5 minutes (live buffer)
  const buf = { ts: [], speed: [], power: [], batt: [] };
  // Anzeige-Fenster im Live-Mode — wird über Wheel-Zoom verkleinert/vergrößert
  // (15s..300s). Buffer bleibt fix bei WINDOW_MS, sodass Zoom-Out auf 5 min
  // jederzeit ohne Daten-Loch geht.
  const DEFAULT_LIVE_WINDOW_S = WINDOW_MS / 1000;
  const MIN_LIVE_WINDOW_S = 15;
  let liveWindowSeconds = DEFAULT_LIVE_WINDOW_S;

  // Drag im Live-Modus friert den Chart auf den aktuellen Bereich ein.
  // range() liefert dann die frozen-Werte statt des rolling Fensters, sodass
  // nachfolgende setData()-Aufrufe (1s-Frames) den Zoom nicht wegspülen.
  // Doppelklick = Reset zurück auf Live-Rolling.
  let liveDragFrozen = null;

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
  // nicht weiterwandert während der User den Chart anschaut. rangeFrom/To
  // halten das ursprünglich geladene Fenster für den Doppelklick-Reset.
  let frozenFrom = null;
  let frozenTo = null;
  let rangeFrom = null;
  let rangeTo = null;
  const isLive = () => activeRange.live;

  // ---------- DOM helpers ----------
  const $ = (id) => document.getElementById(id);
  const num = UI.num;
  const isNum = UI.isNum;

  function setText(id, text) {
    const el = $(id);
    if (el) el.textContent = text;
  }

  // Pill = <span class="pill" data-tone>…<span class="pill-label"/></span>.
  // Nur das Label wird ersetzt, das Icon bleibt stehen.
  function setPill(id, tone, label, title) {
    const el = $(id);
    if (!el) return;
    el.dataset.tone = tone;
    const lab = el.querySelector('.pill-label');
    if (lab) lab.textContent = label;
    else el.textContent = label;
    if (title != null) el.title = title;
  }

  // ---------- Charts ----------
  // Auf schmalen Viewports (Mobile) etwas flacher, damit mehr Platz für die
  // Werte bleibt.
  function chartHeight() {
    return window.innerWidth < 640 ? 170 : 210;
  }

  function allCharts() {
    return [chartSpeed, chartPower, chartBatt].filter(Boolean);
  }
  function setAllX(min, max) {
    allCharts().forEach(u => u.setScale('x', { min, max }));
  }

  // Mausrad-Zoom + Drag-Pan-Plugin für uPlot. Zwei Modi:
  // - Live: ändert das anzeigbare Fenster (15..300s), Auto-Pan bleibt aktiv.
  //   Alle drei Charts werden synchron mitgezogen.
  // - History: cursor-zentrierter Zoom über setScale, alle Charts synchron.
  function wheelZoomPlugin({ factor = 0.85 } = {}) {
    return {
      hooks: {
        // setSelect feuert nachdem eine Drag-Box gezogen wurde (nur relevant,
        // falls cursor.drag wieder aktiviert wird). Im Live-Modus alle drei
        // Charts synchron einfrieren, sonst zieht der nächste Frame den Zoom
        // sofort zurück auf das rolling Fenster.
        setSelect(u) {
          if (!isLive()) return;
          const w = u.select.width;
          if (!w || w < 5) return; // Klick / sehr kleiner Drag → ignorieren
          const minSec = u.posToVal(u.select.left, 'x');
          const maxSec = u.posToVal(u.select.left + w, 'x');
          if (!(maxSec > minSec)) return;
          liveDragFrozen = { from: minSec * 1000, to: maxSec * 1000 };
          setAllX(minSec, maxSec);
          setRangeStatus('Live · pausiert · Doppelklick = zurück');
        },
        ready(u) {
          u.over.addEventListener('wheel', (e) => {
            e.preventDefault();

            if (isLive()) {
              // Wheel im eingefrorenen Live-Modus → erst auftauen, dann
              // Standard-Live-Wheel-Verhalten (Fenster-Resize).
              if (liveDragFrozen) {
                liveDragFrozen = null;
                syncLiveWindow();
                return;
              }
              const oldS = liveWindowSeconds;
              const newS = Math.max(
                MIN_LIVE_WINDOW_S,
                Math.min(DEFAULT_LIVE_WINDOW_S,
                  e.deltaY < 0 ? oldS * factor : oldS / factor)
              );
              if (Math.abs(newS - oldS) < 0.5) return;
              liveWindowSeconds = newS;
              syncLiveWindow();
              return;
            }

            // History: cursor-zentrierter Zoom. frozenFrom/To muessen mit-
            // wandern, sonst zieht unsere range()-Funktion den setScale
            // sofort wieder auf den alten Range zurueck. Fallback auf
            // Chart-Mitte falls der Cursor noch nie ueber dem Chart war.
            const widthCss = u.bbox.width / window.devicePixelRatio;
            let left = u.cursor.left;
            if (left == null || left < 0) left = widthCss / 2;
            const xVal = u.posToVal(left, 'x');
            const { min, max } = u.scales.x;
            const oxRange = max - min;
            const nxRange = e.deltaY < 0 ? oxRange * factor : oxRange / factor;
            const leftPct = left / widthCss;
            const nxMin = xVal - leftPct * nxRange;
            const nxMax = nxMin + nxRange;
            frozenFrom = nxMin * 1000;
            frozenTo   = nxMax * 1000;
            setAllX(nxMin, nxMax);
          }, { passive: false });

          // ---- Drag = Pan (horizontal) ----
          // Im Live frieren wir den aktuellen Range ein (sonst pannt das
          // rolling Fenster sofort zurueck). Im History verschieben wir
          // frozenFrom/To. mouseup/move auf window, damit der Drag nicht
          // abreisst sobald die Maus den Chart verlaesst.
          let panActive = false;
          let panStartX = 0;
          let panStartMin = 0;
          let panStartMax = 0;
          let panCssWidth = 0;
          u.over.addEventListener('mousedown', (e) => {
            if (e.button !== 0) return;
            if (isLive() && !liveDragFrozen) {
              const now = Date.now() / 1000;
              liveDragFrozen = {
                from: (now - liveWindowSeconds) * 1000,
                to: now * 1000,
              };
              setRangeStatus('Live · pausiert · Doppelklick = zurück');
            }
            panActive = true;
            panStartX = e.clientX;
            panStartMin = u.scales.x.min;
            panStartMax = u.scales.x.max;
            panCssWidth = u.bbox.width / window.devicePixelRatio;
            u.over.style.cursor = 'grabbing';
            e.preventDefault();
          });
          const onMove = (e) => {
            if (!panActive) return;
            const dxPx = e.clientX - panStartX;
            const valPerPx = (panStartMax - panStartMin) / panCssWidth;
            const newMin = panStartMin - dxPx * valPerPx;
            const newMax = panStartMax - dxPx * valPerPx;
            setAllX(newMin, newMax);
            if (isLive()) {
              liveDragFrozen = { from: newMin * 1000, to: newMax * 1000 };
            } else {
              frozenFrom = newMin * 1000;
              frozenTo = newMax * 1000;
            }
          };
          const onUp = () => {
            if (!panActive) return;
            panActive = false;
            u.over.style.cursor = '';
          };
          window.addEventListener('mousemove', onMove);
          window.addEventListener('mouseup', onUp);

          // Doppelklick:
          //  - Live eingefroren (Drag) → auftauen + Fenster auf Default
          //  - Live gezoomt → Fenster auf Default (5 min)
          //  - Zeitraum-Ansicht → zurück auf das geladene Fenster
          u.over.addEventListener('dblclick', () => {
            if (!isLive()) {
              if (rangeFrom == null || rangeTo == null) return;
              frozenFrom = rangeFrom;
              frozenTo = rangeTo;
              setAllX(rangeFrom / 1000, rangeTo / 1000);
              return;
            }
            if (liveDragFrozen) {
              liveDragFrozen = null;
              liveWindowSeconds = DEFAULT_LIVE_WINDOW_S;
              syncLiveWindow();
              return;
            }
            if (liveWindowSeconds === DEFAULT_LIVE_WINDOW_S) return;
            liveWindowSeconds = DEFAULT_LIVE_WINDOW_S;
            syncLiveWindow();
          });
        }
      }
    };
  }

  // Synct alle drei Live-Charts auf das aktuelle liveWindowSeconds-Fenster
  // und aktualisiert die Range-Status-Zeile.
  function syncLiveWindow() {
    const now = Date.now() / 1000;
    setAllX(now - liveWindowSeconds, now);
    if (isLive()) {
      const label = liveWindowSeconds === DEFAULT_LIVE_WINDOW_S
        ? 'Live · Mausrad = Zoom · Ziehen = Pause'
        : `Live · ${liveWindowSeconds < 60 ? liveWindowSeconds.toFixed(0) + ' s' : num(liveWindowSeconds / 60, 1) + ' min'} · Doppelklick = zurück`;
      setRangeStatus(label);
    }
  }

  function makeChart(host, label, colorKey, unit, digits, opts2 = {}) {
    const opts = {
      width: host.clientWidth || 600,
      height: chartHeight(),
      plugins: [wheelZoomPlugin({ factor: 0.85 }), UI.tooltipPlugin()],
      padding: [10, 18, 0, 0],
      scales: {
        x: {
          time: true,
          // Live: rolling-Fenster (liveWindowSeconds, default 300s, via
          // Wheel-Zoom verkleinerbar).
          // History: frozenFrom/frozenTo (gesetzt in applyRange). uPlot
          // ruft range() nur bei setData auf — User-Drag/Wheel-Zoom via
          // setScale triggert das NICHT, der Zoom bleibt also stabil.
          range: () => {
            if (isLive()) {
              if (liveDragFrozen) {
                return [liveDragFrozen.from / 1000, liveDragFrozen.to / 1000];
              }
              const now = Date.now() / 1000;
              return [now - liveWindowSeconds, now];
            }
            return [frozenFrom / 1000, frozenTo / 1000];
          },
        },
        // Caller kann y-Achse fix-pinnen (z.B. Akku auf 0..100).
        ...(opts2.yRange ? { y: { range: opts2.yRange } } : {}),
      },
      // Statt der Legende unter dem Chart zeigt ein schwebender Tooltip
      // (UI.tooltipPlugin) Zeit + Wert unter dem Cursor.
      legend: { show: false },
      // Cursor-Drag deaktiviert — wir handhaben mousedown/move/up selbst, um
      // Pan (links/rechts) statt Zoom-Box zu liefern. Zoom rein/raus laeuft
      // ueber das Mausrad, Reset via Doppelklick.
      cursor: { drag: { x: false, y: false }, points: { size: 9 } },
      axes: [
        UI.axisOpts({ values: UI.EU_AXIS_VALUES, space: () => (window.innerWidth < 640 ? 46 : 70) }),
        UI.axisOpts({ size: 46, space: 32, values: UI.yValues(opts2.ySuffix || '') }),
      ],
      series: [
        { label: 'Zeit', value: (_u, v) => UI.timeLabel(v) },
        {
          label: opts2.seriesLabel || label,
          stroke: UI.seriesStroke(colorKey),
          width: 2,
          fill: UI.areaFill(colorKey),
          spanGaps: true,
          points: { show: false },
          // Formatter für den Hover-Wert im Tooltip.
          value: (_self, v) => v == null ? UI.DASH : `${num(v, digits)} ${unit}`,
        }
      ]
    };
    return new uPlot(opts, [[], []], host);
  }

  const chartSpeed = makeChart($('chart-speed'), 'Tempo', 'teal', 'km/h', 1);
  const chartPower = makeChart($('chart-power'), 'Leistung', 'magenta', 'W', 0);
  // Akku-Chart mit fixer 0..100%-y-Achse, sonst zappelt der Verlauf
  // visuell wenn der Roller von 85→84 fällt.
  const chartBatt  = makeChart($('chart-batt'), 'Akku', 'amber', '%', 0,
                               { yRange: [0, 100], ySuffix: ' %' });

  // Resize charts on viewport/Layout change so the cards stay tidy.
  function resizeCharts() {
    const h = chartHeight();
    [[chartSpeed, 'chart-speed'], [chartPower, 'chart-power'], [chartBatt, 'chart-batt']]
      .forEach(([u, id]) => {
        const w = $(id).clientWidth;
        if (w > 0 && (w !== u.width || h !== u.height)) u.setSize({ width: w, height: h });
      });
  }
  window.addEventListener('resize', () => { resizeCharts(); renderSparks(); });
  window.addEventListener('orientationchange', resizeCharts);
  if (window.ResizeObserver) {
    const ro = new ResizeObserver(() => resizeCharts());
    ['chart-speed', 'chart-power', 'chart-batt'].forEach(id => ro.observe($(id)));
  }
  UI.onThemeChange(() => allCharts().forEach(u => u.redraw()));
  UI.whenFontsReady(() => allCharts().forEach(u => u.redraw(false, true)));

  // ---------- Sparklines (Akku/Leistung, letzte 5 min aus dem Live-Puffer) ----------
  // Nur auf breiten Screens sichtbar (dort haben die Karten Platz); versteckte
  // SVGs werden übersprungen.
  function renderSpark(id, values, { includeZero = false, minSpan = 1 } = {}) {
    const svg = $(id);
    if (!svg || svg.getClientRects().length === 0) return;
    const line = svg.querySelector('.spark-line');
    const area = svg.querySelector('.spark-area');
    const pts = [];
    for (let i = 0; i < buf.ts.length; i++) {
      const v = values[i];
      if (isNum(v)) pts.push([buf.ts[i], Number(v)]);
    }
    if (pts.length < 2) { line.setAttribute('d', ''); area.setAttribute('d', ''); return; }
    let lo = Infinity, hi = -Infinity;
    pts.forEach(([, v]) => { if (v < lo) lo = v; if (v > hi) hi = v; });
    if (includeZero) { lo = Math.min(lo, 0); hi = Math.max(hi, 0); }
    if (hi - lo < minSpan) { const mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = mid + minSpan / 2; }
    const W = 300, H = 60, PAD = 4;
    const x0 = pts[0][0], x1 = pts[pts.length - 1][0];
    const sx = (t) => ((t - x0) / (x1 - x0 || 1)) * W;
    const sy = (v) => H - PAD - ((v - lo) / (hi - lo)) * (H - 2 * PAD);
    let d = '';
    pts.forEach(([t, v], i) => { d += `${i ? 'L' : 'M'}${sx(t).toFixed(1)} ${sy(v).toFixed(1)}`; });
    line.setAttribute('d', d);
    area.setAttribute('d', `${d}L${W} ${H}L0 ${H}Z`);
  }
  function renderSparks() {
    renderSpark('spark-batt', buf.batt, { minSpan: 10 });
    renderSpark('spark-power', buf.power, { includeZero: true, minSpan: 100 });
  }

  function pushPoint(ts, speedKmh, powerW, batteryPercent) {
    // uPlot uses seconds for time scales.
    buf.ts.push(ts / 1000);
    buf.speed.push(speedKmh ?? null);
    buf.power.push(powerW ?? null);
    buf.batt.push(batteryPercent ?? null);

    // Drop points older than the window.
    const cutoff = (ts - WINDOW_MS) / 1000;
    while (buf.ts.length && buf.ts[0] < cutoff) {
      buf.ts.shift();
      buf.speed.shift();
      buf.power.shift();
      buf.batt.shift();
    }
    renderSparks();
    // Im History-Mode den Chart NICHT mit Live-Punkten überschreiben — der
    // User schaut sich gerade einen festen Zeitraum an, die Kennzahlen laufen
    // aber weiter live (das passiert in applyTelemetry, nicht hier).
    if (isLive()) {
      chartSpeed.setData([buf.ts, buf.speed]);
      chartPower.setData([buf.ts, buf.power]);
      chartBatt.setData([buf.ts, buf.batt]);
    }
  }

  // Beim Seitenaufruf die letzten 5 min aus der DB in den Live-Puffer holen,
  // damit die Live-Charts nicht leer starten. Inzwischen per WS eingetroffene
  // Punkte bleiben erhalten (Merge nach Zeitstempel).
  async function backfillLiveBuffer() {
    const to = Date.now();
    const from = to - WINDOW_MS;
    try {
      const r = await window.EpfAuth.fetchAuthed(`/api/telemetry?from=${from}&to=${to}&max=600`);
      if (!r.ok) return;
      const { samples } = await r.json();
      if (!samples || samples.length === 0) return;
      const merged = new Map();
      samples.forEach(s => merged.set(s.ts / 1000, [s.speedKmh, s.powerW, s.batteryPercent]));
      buf.ts.forEach((t, i) => merged.set(t, [buf.speed[i], buf.power[i], buf.batt[i]]));
      const cutoff = (Date.now() - WINDOW_MS) / 1000;
      const keys = [...merged.keys()].filter(t => t >= cutoff).sort((a, b) => a - b);
      buf.ts = keys;
      buf.speed = keys.map(k => merged.get(k)[0] ?? null);
      buf.power = keys.map(k => merged.get(k)[1] ?? null);
      buf.batt  = keys.map(k => merged.get(k)[2] ?? null);
      renderSparks();
      if (isLive()) {
        chartSpeed.setData([buf.ts, buf.speed]);
        chartPower.setData([buf.ts, buf.power]);
        chartBatt.setData([buf.ts, buf.batt]);
      }
    } catch (err) {
      console.warn('[live] buffer backfill failed:', err);
    }
  }

  // ---------- Range-Chips ----------
  function renderChips() {
    const host = $('range-chips');
    if (!host) return;
    host.innerHTML = RANGE_PRESETS.map(p => {
      const active = p.id === activeRange.id;
      return `<button type="button" data-range="${p.id}" class="range-chip" aria-pressed="${active}">${p.label}</button>`;
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
      rangeFrom = null;
      rangeTo = null;
      liveDragFrozen = null;
      // Beim Wechsel auf Live das Zoom-Fenster auf Default zurueck —
      // sonst wirkt die alte Wheel-Position aus der vorigen Live-Session
      // verwirrend (z.B. "Live · 30s" nach Auto-Switch).
      liveWindowSeconds = DEFAULT_LIVE_WINDOW_S;
      chartSpeed.setData([buf.ts, buf.speed]);
      chartPower.setData([buf.ts, buf.power]);
      chartBatt.setData([buf.ts, buf.batt]);
      // x-Scale explizit auf Live-Fenster zuruecksetzen. Sonst bleibt die
      // per setScale gesetzte History-Range "user-set" und der Wheel-Zoom
      // im Live-Mode hat keinen sichtbaren Effekt.
      syncLiveWindow();
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
      // Während des Fetches auf einen anderen Zeitraum gewechselt → verwerfen.
      if (activeRange !== next) return;
      frozenFrom = from;
      frozenTo = to;
      rangeFrom = from;
      rangeTo = to;
      const xs = samples.map(s => s.ts / 1000);
      const speed = samples.map(s => s.speedKmh);
      const power = samples.map(s => s.powerW);
      const batt  = samples.map(s => s.batteryPercent);
      chartSpeed.setData([xs, speed]);
      chartPower.setData([xs, power]);
      chartBatt.setData([xs, batt]);
      // x-scale explizit auf das gefetchte Fenster setzen — fungiert auch als
      // "Zoom-Reset" wenn der User vorher in einen anderen Range reingezoomt
      // hatte und jetzt einen Preset anklickt.
      setAllX(from / 1000, to / 1000);
      setRangeStatus(samples.length
        ? `${num(samples.length)} Punkte · Mausrad = Zoom · Ziehen = Verschieben · Doppelklick = Reset`
        : 'Keine Daten in diesem Zeitraum');
    } catch (err) {
      console.warn('range fetch failed:', err);
      setRangeStatus('Fehler beim Laden – siehe Konsole');
    }
  }

  renderChips();
  syncLiveWindow();

  // ---------- Tacho (SVG-Gauge) ----------
  // 270°-Bogen: 0 km/h bei 135° (unten links), Maximum bei 405° (unten rechts).
  // Skala startet bei 30 km/h und wächst in 10er-Schritten, falls schneller
  // gefahren wird.
  const GAUGE_CX = 120, GAUGE_CY = 120;
  let gaugeMax = 30;

  function renderGaugeTicks() {
    const g = $('gauge-ticks');
    if (!g) return;
    const minorStep = gaugeMax <= 40 ? 1 : 2;
    const majorStep = gaugeMax <= 40 ? 5 : 10;
    let html = '';
    for (let v = 0; v <= gaugeMax + 1e-9; v += minorStep) {
      const major = Math.abs(v % majorStep) < 1e-9;
      const a = (135 + 270 * (v / gaugeMax)) * Math.PI / 180;
      const r1 = major ? 77 : 81;
      const r2 = 86;
      const x1 = GAUGE_CX + r1 * Math.cos(a), y1 = GAUGE_CY + r1 * Math.sin(a);
      const x2 = GAUGE_CX + r2 * Math.cos(a), y2 = GAUGE_CY + r2 * Math.sin(a);
      html += `<line class="gauge-tick${major ? ' is-major' : ''}" data-v="${v}" ` +
              `x1="${x1.toFixed(2)}" y1="${y1.toFixed(2)}" x2="${x2.toFixed(2)}" y2="${y2.toFixed(2)}"/>`;
    }
    g.innerHTML = html;
    setText('gauge-max', String(gaugeMax));
  }

  let gaugeLitUpTo = -1;
  function setGauge(speed) {
    const v = isNum(speed) ? Math.max(0, Number(speed)) : 0;
    if (v > gaugeMax) {
      gaugeMax = Math.ceil(v / 10) * 10;
      renderGaugeTicks();
      gaugeLitUpTo = -1;
    }
    const pct = Math.min(1, v / gaugeMax);
    const arc = $('gauge-value');
    if (arc) {
      arc.setAttribute('stroke-dashoffset', String(100 - pct * 100));
      arc.style.opacity = pct > 0.002 ? '1' : '0';
    }
    const knob = $('gauge-knob');
    if (knob) knob.style.transform = `rotate(${135 + 270 * pct}deg)`;
    // Ticks bis zum aktuellen Wert einfärben (nur wenn sich was ändert).
    const lit = Math.floor(v);
    if (lit !== gaugeLitUpTo) {
      gaugeLitUpTo = lit;
      document.querySelectorAll('#gauge-ticks .gauge-tick').forEach(t => {
        t.classList.toggle('is-lit', Number(t.dataset.v) <= v && v > 0);
      });
    }
  }
  renderGaugeTicks();
  setGauge(null);

  // ---------- Verbindungsstatus + Offline-Hinweis ----------
  // App schickt scooterName = vom User vergebener Anzeigename (oder null wenn
  // nichts konfiguriert). Alte Builds schickten die MAC mit — die filtern wir
  // raus, damit "AA:BB:CC:..." nicht ans UI durchschlaegt.
  const MAC_RE = /^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}$/;
  function displayName(name) {
    if (!name || MAC_RE.test(name)) return 'E-Scooter';
    return name;
  }

  let stateKnown = false;
  let scooterConnected = false;
  let scooterLabel = 'E-Scooter';
  let wsOpen = false;
  let wsEverClosed = false;
  let wsRetryAt = 0;
  let lastTelemetryTs = null;       // ts aus dem letzten Frame (Scooter-Zeit)
  let lastTelemetryReceived = 0;    // Empfangszeit (Browser-Uhr), 0 = Snapshot

  const lastSeenFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
  const timeOnlyFmt = new Intl.DateTimeFormat('de-DE', {
    hour: '2-digit', minute: '2-digit', hour12: false,
  });

  function applyState(state) {
    stateKnown = true;
    scooterConnected = !!(state && state.connected);
    scooterLabel = displayName(state && state.scooterName);
    updateConnectionUi();
    updateLiveBadge();
  }

  function updateConnectionUi() {
    const pill = $('conn-pill');
    const text = $('conn-text');
    let tone, label, title;
    if (wsEverClosed && !wsOpen) {
      tone = 'danger'; label = 'Server getrennt'; title = 'Keine Verbindung zum Server';
    } else if (scooterConnected) {
      tone = 'ok'; label = `${scooterLabel} verbunden`; title = 'Scooter verbunden';
    } else {
      tone = 'muted'; label = 'Kein Scooter'; title = 'Kein Scooter verbunden';
    }
    if (pill) { pill.dataset.tone = tone; pill.title = title; }
    if (text) text.textContent = label;

    setPill('chip-scooter',
      scooterConnected ? 'ok' : 'muted',
      scooterConnected ? scooterLabel : 'Nicht verbunden',
      scooterConnected ? 'Scooter verbunden' : 'Kein Scooter verbunden');

    updateBanner();
  }

  function updateBanner() {
    const banner = $('offline-banner');
    if (!banner) return;
    let kind = null;
    if (wsEverClosed && !wsOpen) kind = 'server';
    else if (stateKnown && !scooterConnected) kind = 'scooter';

    document.body.classList.toggle('is-offline', kind != null);
    if (!kind) { banner.hidden = true; return; }

    banner.hidden = false;
    banner.dataset.kind = kind;
    $('offline-icon-scooter').classList.toggle('hidden', kind !== 'scooter');
    $('offline-icon-server').classList.toggle('hidden', kind !== 'server');
    if (kind === 'server') {
      const s = Math.max(0, Math.ceil((wsRetryAt - Date.now()) / 1000));
      setText('offline-title', 'Verbindung zum Server unterbrochen');
      setText('offline-text', s > 0 ? `Neuer Versuch in ${s} s …` : 'Verbinde neu …');
    } else {
      setText('offline-title', 'Kein Scooter verbunden');
      setText('offline-text', lastTelemetryTs
        ? `Letzte Daten vom ${lastSeenFmt.format(new Date(lastTelemetryTs))} Uhr. Sobald die App wieder verbunden ist, laufen die Werte live weiter.`
        : 'Sobald die EPF-Cockpit-App mit dem Scooter verbunden ist, erscheinen hier die Live-Werte.');
    }
  }

  // Live-Badge im Tacho: "Live" solange Frames frisch reinkommen.
  function updateLiveBadge() {
    const badge = $('live-badge');
    if (!badge) return;
    const fresh = scooterConnected && lastTelemetryReceived > 0 && Date.now() - lastTelemetryReceived < 5000;
    badge.dataset.state = fresh ? 'live' : 'idle';
    setText('live-badge-text', fresh
      ? 'Live'
      : lastTelemetryTs ? `Stand ${timeOnlyFmt.format(new Date(lastTelemetryTs))}` : 'Keine Daten');
  }
  setInterval(() => {
    updateLiveBadge();
    if (wsEverClosed && !wsOpen) updateBanner();
  }, 1000);

  // ---------- Telemetry → DOM ----------
  const POWER_SCALE_MIN = 500; // W — Balken-Skala, wächst mit der Spitze
  // Spitze = größter Betrag im 5-min-Live-Puffer (inkl. DB-Backfill).
  function bufferPeakPower() {
    let peak = 0;
    for (const v of buf.power) if (isNum(v)) peak = Math.max(peak, Math.abs(Number(v)));
    return peak;
  }

  function applyTelemetry(t, { snapshot = false } = {}) {
    const ts = isNum(t.ts) ? Number(t.ts) : Date.now();
    lastTelemetryTs = ts;
    // Frames, deren Zeitstempel weit in der Vergangenheit liegen (Snapshot
    // beim Verbindungsaufbau), zählen nicht als "live".
    if (!snapshot && Math.abs(Date.now() - ts) < 120_000) lastTelemetryReceived = Date.now();

    // Tacho + Kennzahlen (weich animiert, respektiert prefers-reduced-motion)
    UI.animateNumber($('t-speed'), t.speedKmh, 1);
    setGauge(t.speedKmh);
    UI.animateNumber($('t-batt'),  t.batteryPercent, 0);
    UI.animateNumber($('t-power'), t.powerW, 0);
    setText('t-volt', num(t.voltageV, 1));
    setText('t-amp',  num(t.currentA, 1));
    setText('t-temp', num(t.controllerTempC, 0));

    // Kilometerzähler (tripKm = Tageskilometer, totalKm = Gesamt)
    setText('t-trip-km',  num(t.tripKm, 2));
    setText('t-total-km', num(t.totalKm, 1));

    // Akku-Balken (10 Segmente) + Zustand
    const battCard = $('metric-batt');
    if (isNum(t.batteryPercent)) {
      const b = Math.max(0, Math.min(100, Number(t.batteryPercent)));
      $('batt-bar').style.width = `${b}%`;
      if (battCard) battCard.dataset.level = b <= 20 ? 'low' : 'ok';
      setText('batt-caption', b <= 10 ? 'Kritisch – bitte laden' : b <= 20 ? 'Niedrig' : b <= 50 ? 'Mittel' : 'Gut');
    } else {
      $('batt-bar').style.width = '0%';
      if (battCard) battCard.dataset.level = 'ok';
      setText('batt-caption', ' ');
    }

    // Punkt zuerst in den Puffer, damit Spitze/Sparklines ihn schon kennen.
    pushPoint(ts, t.speedKmh, t.powerW, t.batteryPercent);

    // Leistungs-Balken relativ zur Spitze der letzten 5 min (min. 500 W)
    if (isNum(t.powerW)) {
      const p = Number(t.powerW);
      const peak = Math.max(bufferPeakPower(), Math.abs(p));
      const scale = Math.max(POWER_SCALE_MIN, peak);
      $('power-bar').style.width = `${Math.min(100, Math.abs(p) / scale * 100)}%`;
      $('power-bar-wrap').dataset.regen = p < 0 ? '1' : '0';
      setText('power-caption', p < 0 ? 'Rekuperation' : `Spitze 5 min: ${num(peak, 0)} W`);
    } else {
      $('power-bar').style.width = '0%';
      setText('power-caption', ' ');
    }

    // Status-Pills. Neuer Vertrag: parking ist der verlässliche Park-Zustand;
    // rideModeStand true = Stand-Stop, false = Kick-Start, null/undefined
    // (ältere App-Versionen) = unbekannt — NICHT als "Kick" anzeigen.
    if (t.parking === true) {
      setPill('chip-parking', 'warn', 'Geparkt', 'Parkmodus aktiv');
    } else if (t.parking === false) {
      setPill('chip-parking', 'info', 'Fahrbereit', 'Parkmodus aus');
    } else {
      setPill('chip-parking', 'muted', 'Parken: –', 'Park-Zustand unbekannt');
    }

    if (t.rideModeStand === true) {
      setPill('chip-ridemode', 'neutral', 'Stand-Stop', 'Fahrmodus: Stand-Stop');
    } else if (t.rideModeStand === false) {
      setPill('chip-ridemode', 'neutral', 'Kick-Start', 'Fahrmodus: Kick-Start');
    } else {
      setPill('chip-ridemode', 'muted', 'Modus: –', 'Fahrmodus unbekannt');
    }

    const light = $('chip-light');
    if (light) {
      if (t.lightOn === true || t.lightOn === false) {
        light.hidden = false;
        setPill('chip-light', t.lightOn ? 'warn' : 'muted', t.lightOn ? 'Licht an' : 'Licht aus', 'Frontlicht');
      } else {
        light.hidden = true;
      }
    }

    updateLiveBadge();
    if (!scooterConnected) updateBanner();
  }

  // ---------- Live-Map (Leaflet, OSM) ----------
  // Lazy-init beim ersten location-Frame, vorher bleibt die Section hidden.
  // Trail = letzte N Punkte der aktuellen Fahrt; wechselt der tripId, wird
  // der Trail gepurged damit nicht zwei Fahrten zusammenkleben.
  // Trail-Cap nur als Notbremse — eine ueberlange Polyline killt sonst die
  // Map. Bei 1s-Intervall reichen 20k Punkte fuer ~5.5h Fahrt.
  const TRAIL_MAX_POINTS = 20_000;
  // Smart auto-refocus: pausiert nach User-Interaktion, kommt nach IDLE_MS
  // Stille zurück. Ausserdem direkt wieder an, sobald der Marker ausserhalb
  // des sichtbaren Bereichs landet — dann ist die User-Aussicht eh unbrauchbar.
  const AUTO_FOLLOW_IDLE_MS = 10_000;
  let liveMap = null;
  let liveMarker = null;
  let liveTrail = null;
  let liveTrailPts = []; // [[lat,lon], ...]
  let liveTripId = null;
  let liveAutoFollow = true;
  let liveAutoFollowTimer = null;
  let liveZoomSlider = null;

  function updateFollowUi() {
    const btn = $('live-map-follow');
    if (!btn) return;
    btn.dataset.follow = liveAutoFollow ? '1' : '0';
    setText('live-map-follow-text', liveAutoFollow ? 'Folgt' : 'Zentrieren');
  }
  function setAutoFollow(on) {
    liveAutoFollow = on;
    clearTimeout(liveAutoFollowTimer);
    updateFollowUi();
  }
  function pauseAutoFollow() {
    liveAutoFollow = false;
    clearTimeout(liveAutoFollowTimer);
    liveAutoFollowTimer = setTimeout(() => setAutoFollow(true), AUTO_FOLLOW_IDLE_MS);
    updateFollowUi();
  }

  function initLiveMap() {
    if (liveMap || typeof L === 'undefined') return;
    const section = $('live-map-section');
    const host = $('live-map');
    if (!section || !host) return;
    L.Icon.Default.imagePath = '/vendor/leaflet/images/';
    section.classList.remove('hidden');
    liveMap = L.map(host, {
      // Default +/- weg — wir haben einen eigenen Slider als Overlay.
      zoomControl: false,
      attributionControl: true,
      scrollWheelZoom: true,
    }).setView([51.16, 10.45], 6); // Default: Mitte-Deutschland bis erste Position

    // Tile-Layer-Set (alle frei, kein API-Key). Satellit default — der User
    // erkennt seine Strecke darin deutlich besser als auf flacher Strasse.
    // "Straße" wird im dunklen Theme per CSS-Filter abgedunkelt (.tiles-themed).
    const layers = {
      osm: L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        className: 'tiles-themed',
        attribution: '© OpenStreetMap contributors',
      }),
      sat: L.tileLayer(
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        {
          maxZoom: 19,
          attribution: 'Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics',
        }
      ),
      topo: L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
        maxZoom: 17,
        attribution: 'Map data: © OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)',
      }),
    };
    let activeLayerKey = 'sat';
    layers.sat.addTo(liveMap);

    // Custom Layer-Switcher (HTML in dashboard.html).
    const switcherHost = $('live-map-layers');
    function setActiveLayer(key) {
      if (!layers[key] || key === activeLayerKey) return;
      liveMap.removeLayer(layers[activeLayerKey]);
      layers[key].addTo(liveMap);
      activeLayerKey = key;
      switcherHost?.querySelectorAll('.map-layer-btn').forEach(b => {
        b.classList.toggle('is-active', b.dataset.layer === key);
        b.setAttribute('aria-pressed', String(b.dataset.layer === key));
      });
    }
    if (switcherHost) {
      switcherHost.querySelectorAll('.map-layer-btn').forEach(btn => {
        btn.setAttribute('aria-pressed', String(btn.dataset.layer === activeLayerKey));
        btn.addEventListener('click', () => setActiveLayer(btn.dataset.layer));
      });
      switcherHost.querySelector(`.map-layer-btn[data-layer="${activeLayerKey}"]`)
        ?.classList.add('is-active');
    }

    liveTrail = L.polyline([], {
      color: '#4DD0E1', weight: 4, opacity: 0.95,
      lineJoin: 'round', lineCap: 'round', className: 'live-trail',
    }).addTo(liveMap);

    // Zoom-Slider-Overlay (vertikal, links oben).
    liveZoomSlider = $('live-map-zoom');
    if (liveZoomSlider) {
      liveZoomSlider.min = liveMap.getMinZoom();
      liveZoomSlider.max = liveMap.getMaxZoom();
      liveZoomSlider.value = liveMap.getZoom();
      liveZoomSlider.addEventListener('input', () => {
        liveMap.setZoom(Number(liveZoomSlider.value));
        pauseAutoFollow();
      });
      liveMap.on('zoomend', () => {
        liveZoomSlider.value = liveMap.getZoom();
      });
    }

    // User-Interaktion pausiert auto-follow (10s IDLE-Timer in pauseAutoFollow).
    liveMap.on('dragstart zoomstart', pauseAutoFollow);
    // Doppelklick / Folgen-Button = expliziter Reset (auto-follow sofort an).
    const recenter = () => {
      setAutoFollow(true);
      if (liveMarker) liveMap.panTo(liveMarker.getLatLng());
    };
    liveMap.on('dblclick', recenter);
    $('live-map-follow')?.addEventListener('click', recenter);
    updateFollowUi();
  }

  // Holt die kompletten Waypoints eines Trips vom Server und prependet sie in
  // den Live-Trail — wichtig wenn der Browser-Tab spaeter geoeffnet wird oder
  // einen Hard-Refresh hatte, sonst sieht der User nur die letzten paar
  // Sekunden statt der ganzen Fahrt.
  async function backfillTrailFromServer(tripId) {
    try {
      const r = await window.EpfAuth.fetchAuthed(
        `/api/trips/${encodeURIComponent(tripId)}/locations`
      );
      if (!r.ok) return;
      const { points } = await r.json();
      if (!points || points.length === 0) return;
      // Trip koennte sich zwischenzeitlich geaendert haben — ignorieren.
      if (tripId !== liveTripId) return;
      const histPts = points.map(p => [p.lat, p.lon]);
      // Backfill vorne, Live-Punkte (waehrend des Fetches reingekommen) hinten.
      liveTrailPts = histPts.concat(liveTrailPts);
      if (liveTrailPts.length > TRAIL_MAX_POINTS) {
        liveTrailPts = liveTrailPts.slice(-TRAIL_MAX_POINTS);
      }
      liveTrail.setLatLngs(liveTrailPts);
    } catch (err) {
      console.warn('[live] trail backfill failed:', err);
    }
  }

  // Billige Distanz-Naeherung in Meter — Haversine ist Overkill fuer die
  // kurzen Distanzen zwischen aufeinanderfolgenden GPS-Samples.
  function distMeters(a, b) {
    const dLat = (b[0] - a[0]) * 111_000;
    const dLon = (b[1] - a[1]) * 111_000 * Math.cos(a[0] * Math.PI / 180);
    return Math.hypot(dLat, dLon);
  }
  // Im Idle nur Punkte mit > 3m Bewegung anhaengen — sonst zappelt der Trail
  // wegen GPS-Jitter im Stand. Bei aktivem Trip wird gar nicht gefiltert.
  const IDLE_MIN_MOVE_M = 3;

  function applyLocation(msg) {
    if (typeof msg.lat !== 'number' || typeof msg.lon !== 'number') return;
    initLiveMap();
    if (!liveMap) return; // Leaflet nicht geladen

    // Echter Trip-Wechsel (z.B. epf_2 → epf_3): Trail purgen + Backfill.
    // Trip↔Idle ist KEIN Purge — sonst verschwindet die soeben gefahrene
    // Strecke sobald der TripCoordinator den Trip beendet (Parking an).
    const wasTrip = !!liveTripId;
    const newTrip = !!msg.tripId;
    if (newTrip && msg.tripId !== liveTripId) {
      liveTripId = msg.tripId;
      liveTrailPts = [];
      liveTrail.setLatLngs([]);
      backfillTrailFromServer(liveTripId);
    } else if (!newTrip && wasTrip) {
      // Trip ist soeben geendet → Trail-Snapshot stehen lassen, aber liveTripId
      // resetten damit der naechste Trip-Start einen sauberen Backfill triggert.
      liveTripId = null;
    }

    const ll = [msg.lat, msg.lon];
    const last = liveTrailPts[liveTrailPts.length - 1];
    const moved = !last || distMeters(last, ll) >= (liveTripId ? 0 : IDLE_MIN_MOVE_M);
    if (moved) {
      liveTrailPts.push(ll);
      if (liveTrailPts.length > TRAIL_MAX_POINTS) liveTrailPts.shift();
      liveTrail.setLatLngs(liveTrailPts);
    }

    if (!liveMarker) {
      liveMarker = L.marker(ll, {
        icon: L.divIcon({
          className: '',
          html: '<div class="live-dot"></div>',
          iconSize: [18, 18],
          iconAnchor: [9, 9],
        }),
        keyboard: false,
        interactive: false,
      }).addTo(liveMap);
      // Erste Position: aggressiv zoomen damit User was sieht. Das zoomstart
      // dabei ist keine User-Geste → Auto-Follow direkt wieder an.
      liveMap.setView(ll, 17);
      setAutoFollow(true);
    } else {
      liveMarker.setLatLng(ll);
      // Marker ausserhalb des sichtbaren Bereichs während auto-follow off
      // — User-View ist eh unbrauchbar, also zurückspringen + auto wieder an.
      if (!liveAutoFollow && !liveMap.getBounds().pad(-0.05).contains(ll)) {
        setAutoFollow(true);
      }
      if (liveAutoFollow) liveMap.panTo(ll, { animate: true, duration: 0.5 });
    }

    const meta = $('live-map-meta');
    if (meta) {
      const acc = isNum(msg.accuracyM) ? ` · ±${num(msg.accuracyM, 0)} m` : '';
      const status = liveTripId
        ? `Fahrt · ${num(liveTrailPts.length)} Punkte`
        : (liveTrailPts.length > 0 ? `${num(liveTrailPts.length)} Punkte · Idle` : 'Idle');
      meta.textContent = `${status}${acc}`;
    }
  }

  // ---------- WebSocket with exponential backoff ----------
  let ws = null;
  let backoff = 500; // ms
  const MAX_BACKOFF = 15000;

  function connect() {
    ws = new WebSocket(window.EpfAuth.wsUrl('/ws/live'));

    ws.addEventListener('open', () => {
      backoff = 500;
      wsOpen = true;
      updateConnectionUi();
      console.log('[live] connected');
    });

    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (!msg || !msg.type) return;
      switch (msg.type) {
        case 'state':     applyState(msg); break;
        case 'telemetry': applyTelemetry(msg); break;
        case 'location':  applyLocation(msg); break;
        // trip_start / trip_end are not surfaced on the live dashboard yet.
      }
    });

    ws.addEventListener('close', () => {
      console.warn(`[live] disconnected, retry in ${backoff}ms`);
      wsOpen = false;
      wsEverClosed = true;
      wsRetryAt = Date.now() + backoff;
      updateConnectionUi();
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
    if (snap.telemetry) applyTelemetry(snap.telemetry, { snapshot: true });
  }).catch(() => { /* server might still be starting */ });

  backfillLiveBuffer();
  connect();
})();
