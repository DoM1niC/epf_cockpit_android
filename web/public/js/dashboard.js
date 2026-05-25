// dashboard.js — live dashboard logic.
// - Connects to /ws/live with exponential-backoff reconnect.
// - Updates DOM tiles + status chips on every telemetry frame.
// - Maintains a rolling 5-minute buffer for the two uPlot charts.

(function () {
  'use strict';

  const WINDOW_MS = 5 * 60 * 1000; // 5 minutes (live buffer)
  const buf = { ts: [], speed: [], power: [], batt: [] };
  // Anzeige-Fenster im Live-Mode — wird über Wheel-Zoom verkleinert/vergrößert
  // (15s..300s). Buffer bleibt fix bei WINDOW_MS, sodass Zoom-Out auf 5 min
  // jederzeit ohne Daten-Loch geht.
  const DEFAULT_LIVE_WINDOW_S = WINDOW_MS / 1000;
  const MIN_LIVE_WINDOW_S = 15;
  let liveWindowSeconds = DEFAULT_LIVE_WINDOW_S;

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

  // Mausrad-Zoom-Plugin für uPlot. Zwei Modi:
  // - Live: ändert das anzeigbare Fenster (15..300s), Auto-Pan bleibt aktiv.
  //   Alle drei Charts werden synchron mitgezogen.
  // - History: cursor-zentrierter Zoom über setScale, wirkt nur lokal.
  function wheelZoomPlugin({ factor = 0.85 } = {}) {
    return {
      hooks: {
        ready(u) {
          u.over.addEventListener('wheel', (e) => {
            e.preventDefault();
            const left = u.cursor.left;
            if (left == null || left < 0) return;

            if (isLive()) {
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

            // History: cursor-zentrierter Zoom
            const xVal = u.posToVal(left, 'x');
            const { min, max } = u.scales.x;
            const oxRange = max - min;
            const nxRange = e.deltaY < 0 ? oxRange * factor : oxRange / factor;
            const leftPct = left / u.bbox.width * window.devicePixelRatio;
            const nxMin = xVal - leftPct * nxRange;
            const nxMax = nxMin + nxRange;
            u.batch(() => u.setScale('x', { min: nxMin, max: nxMax }));
          }, { passive: false });

          // Doppelklick im Live-Modus = Fenster auf Default (5 min) zurück.
          u.over.addEventListener('dblclick', () => {
            if (!isLive()) return;
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
    const span = { min: now - liveWindowSeconds, max: now };
    chartSpeed && chartSpeed.setScale('x', span);
    chartPower && chartPower.setScale('x', span);
    chartBatt  && chartBatt.setScale('x',  span);
    if (isLive()) {
      const label = liveWindowSeconds === DEFAULT_LIVE_WINDOW_S
        ? 'Live'
        : `Live · ${liveWindowSeconds < 60 ? liveWindowSeconds.toFixed(0) + 's' : (liveWindowSeconds/60).toFixed(1) + ' min'} · Dblclick = Reset`;
      setRangeStatus(label);
    }
  }

  function makeChart(host, label, color, unit, digits, opts2 = {}) {
    const opts = {
      width: host.clientWidth || 600,
      height: chartHeight(),
      plugins: [wheelZoomPlugin({ factor: 0.85 })],
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
              const now = Date.now() / 1000;
              return [now - liveWindowSeconds, now];
            }
            return [frozenFrom / 1000, frozenTo / 1000];
          },
        },
        // Caller kann y-Achse fix-pinnen (z.B. Akku auf 0..100).
        ...(opts2.yRange ? { y: { range: opts2.yRange } } : {}),
      },
      // Legende EIN — zeigt beim Hover Zeit + aktuellen Wert an.
      // isolate:true heißt: Klick auf eine Serie blendet andere aus (für uns
      // egal, wir haben nur eine). live:true heißt: Werte unter dem Cursor
      // werden in der Legende angezeigt.
      legend: { show: true, live: true },
      // Drag-Zoom auf x-Achse — wirkt nur in History (im Live frisst's range()).
      cursor: { drag: { x: true, y: false } },
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
  // Akku-Chart mit fixer 0..100%-y-Achse, sonst zappelt der Verlauf
  // visuell wenn der Roller von 85→84 fällt.
  const chartBatt  = makeChart($('chart-batt'),  '%',    '#FFB300', '%',    0,
                               { yRange: [0, 100] });

  // Resize charts on viewport change so the cards stay tidy.
  function resizeCharts() {
    const h = chartHeight();
    chartSpeed.setSize({ width: $('chart-speed').clientWidth, height: h });
    chartPower.setSize({ width: $('chart-power').clientWidth, height: h });
    chartBatt.setSize({  width: $('chart-batt').clientWidth,  height: h });
  }
  window.addEventListener('resize', resizeCharts);
  window.addEventListener('orientationchange', resizeCharts);

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
    // Im History-Mode den Chart NICHT mit Live-Punkten überschreiben — der
    // User schaut sich gerade einen festen Zeitraum an, die Tiles sollen
    // aber weiter live laufen (das passiert in applyTelemetry, nicht hier).
    if (isLive()) {
      chartSpeed.setData([buf.ts, buf.speed]);
      chartPower.setData([buf.ts, buf.power]);
      chartBatt.setData([buf.ts, buf.batt]);
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
      // Beim Wechsel auf Live das Zoom-Fenster auf Default zurueck —
      // sonst wirkt die alte Wheel-Position aus der vorigen Live-Session
      // verwirrend (z.B. "Live · 30s" nach Auto-Switch).
      liveWindowSeconds = DEFAULT_LIVE_WINDOW_S;
      setRangeStatus('Live');
      chartSpeed.setData([buf.ts, buf.speed]);
      chartPower.setData([buf.ts, buf.power]);
      chartBatt.setData([buf.ts, buf.batt]);
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
      const batt  = samples.map(s => s.batteryPercent);
      chartSpeed.setData([xs, speed]);
      chartPower.setData([xs, power]);
      chartBatt.setData([xs, batt]);
      // x-scale explizit auf das gefetchte Fenster setzen — fungiert auch als
      // "Zoom-Reset" wenn der User vorher in einen anderen Range reingezoomt
      // hatte und jetzt einen Preset anklickt.
      const xMin = from / 1000, xMax = to / 1000;
      chartSpeed.setScale('x', { min: xMin, max: xMax });
      chartPower.setScale('x', { min: xMin, max: xMax });
      chartBatt.setScale('x',  { min: xMin, max: xMax });
      setRangeStatus(`${samples.length} Punkte · Ziehen/Mausrad = Zoom · Dblclick = Reset`);
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
    pushPoint(t.ts || Date.now(), t.speedKmh, t.powerW, t.batteryPercent);
  }

  // ---------- Live-Map (Leaflet, OSM) ----------
  // Lazy-init beim ersten location-Frame, vorher bleibt die Section hidden.
  // Trail = letzte N Punkte der aktuellen Fahrt; wechselt der tripId, wird
  // der Trail gepurged damit nicht zwei Fahrten zusammenkleben.
  const TRAIL_MAX_POINTS = 500;
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

  function pauseAutoFollow() {
    liveAutoFollow = false;
    clearTimeout(liveAutoFollowTimer);
    liveAutoFollowTimer = setTimeout(() => { liveAutoFollow = true; }, AUTO_FOLLOW_IDLE_MS);
  }

  function initLiveMap() {
    if (liveMap || typeof L === 'undefined') return;
    const section = document.getElementById('live-map-section');
    const host = document.getElementById('live-map');
    if (!section || !host) return;
    L.Icon.Default.imagePath = '/vendor/leaflet/images/';
    section.classList.remove('hidden');
    liveMap = L.map(host, {
      // Default +/- weg — wir bauen eigenen Slider als Overlay.
      zoomControl: false,
      attributionControl: true,
      scrollWheelZoom: true,
    }).setView([51.16, 10.45], 6); // Default: Mitte-Deutschland bis erste Position

    // Tile-Layer-Set (alle frei, kein API-Key).
    const osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '© OpenStreetMap contributors',
    });
    const sat = L.tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      {
        maxZoom: 19,
        attribution: 'Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics',
      }
    );
    const topo = L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
      maxZoom: 17,
      attribution: 'Map data: © OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)',
    });
    osm.addTo(liveMap); // Default
    L.control.layers(
      { 'Straßen': osm, 'Satellit': sat, 'Topographie': topo },
      null,
      { position: 'topright', collapsed: true }
    ).addTo(liveMap);

    liveTrail = L.polyline([], { color: '#4DD0E1', weight: 4, opacity: 0.85 }).addTo(liveMap);

    // Zoom-Slider-Overlay (vertikal, links oben, weil topright von der
    // Layer-Control belegt ist).
    liveZoomSlider = document.getElementById('live-map-zoom');
    if (liveZoomSlider) {
      liveZoomSlider.min = liveMap.getMinZoom();
      liveZoomSlider.max = liveMap.getMaxZoom();
      liveZoomSlider.value = liveMap.getZoom();
      liveZoomSlider.addEventListener('input', () => {
        // setZoom triggert zoomend → wir würden uns selbst overriden;
        // pauseAutoFollow um zu signalisieren dass das eine Geste war.
        liveMap.setZoom(Number(liveZoomSlider.value));
        pauseAutoFollow();
      });
      liveMap.on('zoomend', () => {
        liveZoomSlider.value = liveMap.getZoom();
      });
    }

    // User-Interaktion pausiert auto-follow (10s IDLE-Timer in pauseAutoFollow).
    liveMap.on('dragstart zoomstart', pauseAutoFollow);
    // Doppelklick = expliziter Reset (auto-follow sofort wieder an).
    liveMap.on('dblclick', () => {
      liveAutoFollow = true;
      clearTimeout(liveAutoFollowTimer);
      if (liveMarker) liveMap.panTo(liveMarker.getLatLng());
    });
  }

  function applyLocation(msg) {
    if (typeof msg.lat !== 'number' || typeof msg.lon !== 'number') return;
    initLiveMap();
    if (!liveMap) return; // Leaflet nicht geladen

    // Trip-Wechsel? → Trail purgen.
    if (msg.tripId && msg.tripId !== liveTripId) {
      liveTripId = msg.tripId;
      liveTrailPts = [];
      liveTrail.setLatLngs([]);
    }

    const ll = [msg.lat, msg.lon];
    liveTrailPts.push(ll);
    if (liveTrailPts.length > TRAIL_MAX_POINTS) liveTrailPts.shift();
    liveTrail.setLatLngs(liveTrailPts);

    if (!liveMarker) {
      liveMarker = L.circleMarker(ll, {
        radius: 8, color: '#0e1116', weight: 2,
        fillColor: '#4DD0E1', fillOpacity: 1,
      }).addTo(liveMap);
      // Erste Position: aggressiv zoomen damit User was sieht.
      liveMap.setView(ll, 17);
    } else {
      liveMarker.setLatLng(ll);
      // Smart: wenn Marker ausserhalb sichtbar ist während auto-follow off
      // — User-View ist eh unbrauchbar, also direkt zurückspringen + auto
      // wieder an.
      if (!liveAutoFollow && !liveMap.getBounds().pad(-0.05).contains(ll)) {
        liveAutoFollow = true;
        clearTimeout(liveAutoFollowTimer);
      }
      if (liveAutoFollow) liveMap.panTo(ll, { animate: true, duration: 0.5 });
    }

    const meta = document.getElementById('live-map-meta');
    if (meta) {
      const acc = msg.accuracyM != null ? ` · ±${msg.accuracyM.toFixed(0)}m` : '';
      meta.textContent = `${liveTrailPts.length} Pkt${acc}`;
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
