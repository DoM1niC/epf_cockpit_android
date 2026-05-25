// history.js — fetches /api/trips and renders an expandable card list.
// Clicking a card lazily fetches /api/trips/:id and draws a uPlot speed chart.

(function () {
  'use strict';

  const list = document.getElementById('trip-list');
  const emptyMsg = document.getElementById('empty-msg');

  // EU-Datum + 24h-Zeit: "22.05.2026, 14:30". hour12:false forciert 24h
  // auch in en-US-Locales, die sonst AM/PM einsetzen.
  const dateFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
    hour12: false,
  });
  const cursorTimeFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  });
  function fmtDate(ms) {
    if (!ms) return '--';
    return dateFmt.format(new Date(ms));
  }
  function cursorValue(_self, rawValue) {
    return rawValue == null ? '--' : cursorTimeFmt.format(new Date(rawValue * 1000));
  }

  // EU-Achsen-Werte für uPlot, identisch zu dashboard.js — kurze Duplikation
  // hier statt zusätzlichem Shared-Script, damit history.html nicht noch eine
  // <script>-Zeile braucht. Tick-Default ist self-contained (volle Zeit),
  // Kontext-Zeile bringt nur das Datum dazu (keine Zeit-Doppelung).
  const EU_AXIS_VALUES = [
    [3600 * 24 * 365, "{YYYY}",            null,             null, null,           null, null, null, 1],
    [3600 * 24 * 28,  "{MMM}",             "\n{YYYY}",       null, null,           null, null, null, 1],
    [3600 * 24,       "{DD}.{MM}.",        "\n{YYYY}",       null, null,           null, null, null, 1],
    [3600,            "{HH}:{mm}",         "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [60,              "{HH}:{mm}",         "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [1,               "{HH}:{mm}:{ss}",    "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [0.001,           "{HH}:{mm}:{ss}.{fff}", "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
  ];

  function fmtDuration(s) {
    if (s == null) return '--';
    const m = Math.floor(s / 60);
    const sec = s % 60;
    if (m < 60) return `${m} min ${sec} s`;
    const h = Math.floor(m / 60);
    return `${h} h ${m % 60} min`;
  }

  function fmtNum(v, digits = 1) {
    return v == null ? '--' : Number(v).toFixed(digits);
  }

  // Kleines Lucide-Icon-Vokabular. fill=none + stroke=currentColor wird global
  // in tailwind.css über .icon gesetzt, hier nur die <path>-Geometrie.
  const ICONS = {
    route:       '<path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="6" cy="19" r="3"/><circle cx="18" cy="5" r="3"/>',
    clock:       '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    gauge:       '<path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/>',
    trendingUp:  '<polyline points="22 7 13.5 15.5 8.5 10.5 2 17"/><polyline points="16 7 22 7 22 13"/>',
    zap:         '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
    bike:        '<circle cx="18.5" cy="17.5" r="3.5"/><circle cx="5.5" cy="17.5" r="3.5"/><circle cx="15" cy="5" r="1"/><path d="M12 17.5V14l-3-3 4-3 2 3h2"/>',
    calendar:    '<rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/>',
  };
  function svg(key, cls = 'icon w-3.5 h-3.5') {
    return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[key]}</svg>`;
  }

  function statHtml(iconKey, label, value) {
    return `
      <div>
        <dt class="text-[var(--color-on-surface-dim)] inline-flex items-center gap-1.5">
          ${svg(iconKey)} ${label}
        </dt>
        <dd>${value}</dd>
      </div>`;
  }

  function cardHtml(trip) {
    return `
      <article data-trip-id="${trip.id}"
               class="rounded-2xl bg-[var(--color-surface-1)] p-4 cursor-pointer hover:bg-[var(--color-surface-2)] transition">
        <header class="flex items-baseline justify-between gap-3 flex-wrap">
          <div class="font-medium inline-flex items-center gap-2">
            ${svg('calendar', 'icon w-4 h-4 text-[var(--color-on-surface-dim)]')}
            ${fmtDate(trip.started_at)}
          </div>
          <div class="text-sm text-[var(--color-on-surface-dim)] inline-flex items-center gap-1.5">
            ${trip.scooter_name ? svg('bike') + ' ' + trip.scooter_name : ''}
          </div>
        </header>
        <dl class="mt-3 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3 text-sm tabular-nums">
          ${statHtml('route',      'Strecke',   `${fmtNum(trip.distance_km, 2)} km`)}
          ${statHtml('clock',      'Dauer',     fmtDuration(trip.duration_s))}
          ${statHtml('gauge',      'Max.',      `${fmtNum(trip.max_speed_kmh, 1)} km/h`)}
          ${statHtml('trendingUp', 'Durch. Ø',  `${fmtNum(trip.avg_speed_kmh, 1)} km/h`)}
          ${statHtml('zap',        'Energie',   `${fmtNum(trip.energy_wh, 1)} Wh`)}
        </dl>
        <div class="chart-host mt-4 hidden"></div>
        <div class="map-host mt-3 hidden"></div>
      </article>
    `;
  }

  // Mausrad-Zoom-Plugin für uPlot. Standard hat nur Drag-to-Select-Zoom —
  // Wheel-Zoom kommt nicht out-of-the-box. Wir zoomen mittig zum Cursor, das
  // matched die Erwartung von Map/Plot-Apps. Doppelklick reset ist
  // built-in via cursor.bind.dblclick.
  function wheelZoomPlugin({ factor = 0.85 } = {}) {
    return {
      hooks: {
        ready(u) {
          u.over.addEventListener('wheel', (e) => {
            e.preventDefault();
            const left = u.cursor.left;
            if (left == null || left < 0) return;
            const xVal = u.posToVal(left, 'x');
            const { min, max } = u.scales.x;
            const oxRange = max - min;
            // Negative deltaY = wheel up = zoom in (kleinere Range).
            const nxRange = e.deltaY < 0 ? oxRange * factor : oxRange / factor;
            const leftPct = left / u.bbox.width * window.devicePixelRatio;
            const nxMin = xVal - leftPct * nxRange;
            const nxMax = nxMin + nxRange;
            u.batch(() => u.setScale('x', { min: nxMin, max: nxMax }));
          }, { passive: false });
        }
      }
    };
  }

  async function renderTripChart(article, tripId) {
    const host = article.querySelector('.chart-host');
    host.classList.remove('hidden');
    host.innerHTML = '<div class="text-xs text-[var(--color-on-surface-dim)]">Lade…</div>';

    const r = await window.EpfAuth.fetchAuthed(`/api/trips/${encodeURIComponent(tripId)}`);
    if (!r.ok) {
      host.innerHTML = '<div class="text-xs text-red-400">Fahrt konnte nicht geladen werden</div>';
      return;
    }
    const { samples } = await r.json();
    host.innerHTML = '';

    if (!samples || samples.length === 0) {
      host.innerHTML = '<div class="text-xs text-[var(--color-on-surface-dim)]">Keine Telemetrie-Daten für diese Fahrt</div>';
      return;
    }

    const xs = samples.map(s => s.ts / 1000);
    const ys = samples.map(s => s.speedKmh);
    // Akku-Serie nur einbauen wenn auch wirklich Werte da sind (alte Trips
    // vor dem battery_percent-Logging haben hier durchgehend null).
    const batt = samples.map(s => s.batteryPercent);
    const hasBatt = batt.some(v => v != null);

    // Toolbar oben + Canvas-Container + Hilfetext unten. Click-Stop-Propagation
    // damit Button-Klicks nicht den Trip-Card-Collapse triggern.
    host.innerHTML = `
      <div class="flex items-center justify-between gap-2 mb-2 text-xs text-[var(--color-on-surface-dim)]">
        <span>Ziehen = Zoom · Mausrad = Zoom · Doppelklick = Reset</span>
        <button class="zoom-btn px-2 py-1 rounded-md bg-[var(--color-surface-2)] hover:bg-[var(--color-surface-3)] text-[var(--color-on-surface)]" type="button">Vergrößern</button>
      </div>
      <div class="chart-canvas"></div>
    `;
    const canvas = host.querySelector('.chart-canvas');
    const btn = host.querySelector('.zoom-btn');

    const baseHeight = window.innerWidth < 640 ? 180 : 220;
    const largeHeight = window.innerWidth < 640 ? 360 : 460;
    let large = false;

    const chart = new uPlot({
      width: canvas.clientWidth || 600,
      height: baseHeight,
      plugins: [wheelZoomPlugin({ factor: 0.85 })],
      scales: {
        x: { time: true },
        // Akku auf eigener Achse — fix 0..100% damit der Verlauf nicht
        // visuell explodiert wenn der User von 100→95 fährt.
        batt: { range: [0, 100] },
      },
      // Legende mit Live-Werten unter dem Cursor — Hover zeigt Zeit + km/h + %.
      legend: { show: true, live: true },
      cursor: { drag: { x: true, y: false } },
      axes: [
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' }, values: EU_AXIS_VALUES },
        { stroke: '#4DD0E1', grid: { stroke: 'rgba(255,255,255,0.05)' } },
        // Akku-Achse rechts in Amber — visuell als "anderer Wert" erkennbar.
        ...(hasBatt ? [{
          scale: 'batt', stroke: '#FFB300', side: 1, grid: { show: false },
          values: (_u, vals) => vals.map(v => v + '%'),
        }] : []),
      ],
      series: [
        { label: 'Zeit', value: cursorValue },
        {
          label: 'km/h',
          stroke: '#4DD0E1',
          width: 2,
          fill: '#4DD0E122',
          spanGaps: true,
          // points.show ist per Default eine Function die auto-anzeigt sobald
          // die Punkte genug Abstand haben — heißt im Default-Zoom unsichtbar
          // (Dot-Suppe vermieden), aber sobald man reinzoomt erscheinen die
          // Messpunkte automatisch. size sets the dot-radius.
          points: { size: 6 },
          value: (_self, v) => v == null ? '--' : `${v.toFixed(1)} km/h`,
        },
        ...(hasBatt ? [{
          label: 'Akku',
          scale: 'batt',
          stroke: '#FFB300',
          width: 2,
          dash: [4, 3],
          spanGaps: true,
          points: { size: 6 },
          value: (_self, v) => v == null ? '--' : `${v.toFixed(0)} %`,
        }] : []),
      ],
    }, hasBatt ? [xs, ys, batt] : [xs, ys], canvas);

    // Vergrößern-Toggle: setSize() resized den Chart ohne re-init, der Zoom-
    // State bleibt erhalten. stopPropagation damit der Klick nicht zum Trip-
    // Card-Collapse-Handler hochbubblet.
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      large = !large;
      chart.setSize({
        width: canvas.clientWidth || 600,
        height: large ? largeHeight : baseHeight,
      });
      btn.textContent = large ? 'Verkleinern' : 'Vergrößern';
    });
  }

  // Leaflet braucht eine globale Default-Image-URL, sonst sucht es die Marker
  // unter dem aktuellen Pfad (z.B. /history/marker-icon.png → 404). Wir
  // pinnen den vendored Pfad einmalig beim ersten Map-Init.
  let leafletImagePathFixed = false;
  function fixLeafletImagePath() {
    if (leafletImagePathFixed || typeof L === 'undefined') return;
    L.Icon.Default.imagePath = '/vendor/leaflet/images/';
    leafletImagePathFixed = true;
  }

  // Kleines Circle-Marker-Style-Preset — wir nutzen bewusst keine
  // PNG-Default-Marker für Start/Ende, weil CircleMarker auf jedem Zoomlevel
  // gleich groß bleiben und keinen zusätzlichen Asset-Request triggern.
  const startMarkerStyle = {
    radius: 7, color: '#0e1116', weight: 2,
    fillColor: '#22c55e', fillOpacity: 1, // grün
  };
  const endMarkerStyle = {
    radius: 7, color: '#0e1116', weight: 2,
    fillColor: '#E91E63', fillOpacity: 1, // magenta/rot
  };

  async function renderTripMap(article, tripId) {
    const host = article.querySelector('.map-host');
    if (!host) return;
    if (typeof L === 'undefined') {
      // Leaflet konnte nicht geladen werden — Map einfach weglassen statt eine
      // halbtote Card zu zeigen. Konsolen-Warning sagt was passiert ist.
      console.warn('[history] Leaflet not available, skipping map');
      return;
    }
    host.classList.remove('hidden');
    host.innerHTML = '<div class="text-xs text-[var(--color-on-surface-dim)] px-2 py-1">Lade Route…</div>';

    let points;
    try {
      const r = await window.EpfAuth.fetchAuthed(`/api/trips/${encodeURIComponent(tripId)}/locations`);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      ({ points } = await r.json());
    } catch (err) {
      console.warn('[history] could not load trip locations:', err);
      host.classList.add('hidden');
      host.innerHTML = '';
      return;
    }

    if (!points || points.length < 2) {
      // Alte Fahrten ohne Locations: kleines, dezent gefärbtes Hint statt
      // einer leeren Map-Karte. Card-Höhe bleibt klein, kein Tile-Container
      // (height/rounded greift nur wenn .map-host die Klasse .has-map trägt).
      host.innerHTML =
        '<div class="text-xs text-[var(--color-on-surface-dim)] italic px-2 py-1">Keine Standortdaten für diese Fahrt.</div>';
      host.classList.remove('has-map');
      return;
    }

    fixLeafletImagePath();
    host.innerHTML = '';
    host.classList.add('has-map');

    const latlngs = points.map(p => [p.lat, p.lon]);
    const map = L.map(host, {
      zoomControl: true,
      attributionControl: true, // OSM-Lizenz verlangt sichtbare Attribution
      scrollWheelZoom: false,   // Damit Scrollen in der Card nicht hängen bleibt
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(map);

    const poly = L.polyline(latlngs, {
      color: '#4DD0E1',
      weight: 4,
      opacity: 0.9,
      lineJoin: 'round',
      lineCap: 'round',
    }).addTo(map);

    L.circleMarker(latlngs[0], startMarkerStyle)
      .bindTooltip('Start', { direction: 'top', offset: [0, -8] })
      .addTo(map);
    L.circleMarker(latlngs[latlngs.length - 1], endMarkerStyle)
      .bindTooltip('Ende', { direction: 'top', offset: [0, -8] })
      .addTo(map);

    map.fitBounds(poly.getBounds(), { padding: [16, 16] });

    // Falls der Card zwischenzeitlich resized wurde (Card-Expansion lädt Chart
    // erst, dann Map → Höhe steht möglicherweise erst nach Chart-Render), nach
    // dem nächsten Tick invalidateSize aufrufen, damit Tiles korrekt sitzen.
    requestAnimationFrame(() => map.invalidateSize());

    // Map-Referenz festhalten, damit Collapse die Instanz korrekt aufräumt
    // (Leaflet hält sonst Resize-Listener und Tile-Subscriptions am Leben).
    host._leafletMap = map;
  }

  function disposeTripMap(article) {
    const host = article.querySelector('.map-host');
    if (host && host._leafletMap) {
      try { host._leafletMap.remove(); } catch { /* ignore */ }
      host._leafletMap = null;
    }
  }

  function bindCardClicks() {
    list.querySelectorAll('article[data-trip-id]').forEach(article => {
      article.addEventListener('click', () => {
        const chartHost = article.querySelector('.chart-host');
        if (!chartHost.classList.contains('hidden') && chartHost.children.length > 0) {
          // Collapse: Chart + Map ausblenden, Map-Instanz freigeben.
          chartHost.classList.add('hidden');
          const mapHost = article.querySelector('.map-host');
          if (mapHost) {
            disposeTripMap(article);
            mapHost.classList.add('hidden');
            mapHost.classList.remove('has-map');
            mapHost.innerHTML = '';
          }
          return;
        }
        // Chart zuerst (Hauptinhalt), Map asynchron daneben — die beiden
        // fetches sind unabhängig, also nicht awaiten.
        renderTripChart(article, article.dataset.tripId);
        renderTripMap(article, article.dataset.tripId);
      });
    });
  }

  // ---------- Filter ----------
  // Berechnet [from, to] in ms. Wir nutzen kalendarische Grenzen (Tagesbeginn
  // 00:00, Wochenanfang Montag) damit "Heute" wirklich nur heutige Trips
  // zeigt, nicht "letzte 24h".
  const FILTERS = [
    { id: 'today',  label: 'Heute',       range: () => dayRange(0) },
    { id: 'd7',     label: '7 Tage',      range: () => slidingRange(7 * 86_400_000) },
    { id: 'd30',    label: '30 Tage',     range: () => slidingRange(30 * 86_400_000) },
    { id: 'month',  label: 'Diesen Monat',range: () => monthRange(0) },
    { id: 'year',   label: 'Dieses Jahr', range: () => yearRange(0) },
    { id: 'all',    label: 'Alle',        range: () => null },
    // Pseudo-Chip — eigentliche Range wird beim Apply als synthesizedCustom
    // gesetzt. Klick toggelt nur das Eingabe-Panel, ohne sofort zu laden.
    { id: 'custom', label: 'Eigener Zeitraum', range: () => null, isCustomToggle: true },
  ];
  let activeFilter = FILTERS[2]; // Default: 30 Tage

  function dayRange(offsetDays) {
    const d = new Date();
    d.setDate(d.getDate() + offsetDays);
    d.setHours(0, 0, 0, 0);
    const from = d.getTime();
    return [from, from + 86_400_000];
  }
  function slidingRange(ms) {
    const to = Date.now();
    return [to - ms, to];
  }
  function monthRange(offset) {
    const now = new Date();
    const from = new Date(now.getFullYear(), now.getMonth() + offset, 1).getTime();
    const to = new Date(now.getFullYear(), now.getMonth() + offset + 1, 1).getTime();
    return [from, to];
  }
  function yearRange(offset) {
    const now = new Date();
    const from = new Date(now.getFullYear() + offset, 0, 1).getTime();
    const to = new Date(now.getFullYear() + offset + 1, 0, 1).getTime();
    return [from, to];
  }

  function renderChips() {
    const host = document.getElementById('filter-chips');
    if (!host) return;
    host.innerHTML = FILTERS.map(f => {
      const active = f.id === activeFilter.id;
      const cls = active
        ? 'bg-[var(--color-brand-teal)] text-black'
        : 'bg-[var(--color-surface-2)] text-[var(--color-on-surface-dim)] hover:text-white';
      return `<button data-filter="${f.id}" class="filter-chip text-xs px-3 py-1 rounded-full ${cls}">${f.label}</button>`;
    }).join('');
    host.querySelectorAll('.filter-chip').forEach(btn => {
      btn.addEventListener('click', () => {
        const next = FILTERS.find(f => f.id === btn.dataset.filter);
        if (!next) return;
        if (next.isCustomToggle) {
          // Panel sichtbar machen / togglen, ohne sofort zu laden.
          toggleCustomPanel();
          return;
        }
        if (next.id === activeFilter.id) return;
        activeFilter = next;
        hideCustomPanel();
        renderChips();
        load();
      });
    });
  }

  // ---------- Eigener Zeitraum (Custom Range) ----------
  // Date-Inputs liefern "YYYY-MM-DD" in lokaler Zone — wir bauen daraus mit
  // new Date(y, m-1, d) lokale Mitternacht, damit Tagesgrenzen identisch zu
  // dayRange()/monthRange() sind und nicht durch UTC-Offset verrutschen.
  function parseDateInput(value) {
    if (!value) return null;
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3]).getTime();
  }
  function toggleCustomPanel() {
    const panel = document.getElementById('custom-range-panel');
    if (!panel) return;
    panel.classList.toggle('hidden');
    if (!panel.classList.contains('hidden')) {
      // Defaults: Wenn schon ein Custom-Filter aktiv ist, dessen Werte zeigen;
      // sonst die heutige Range vorausfüllen, damit der User nur ein Datum
      // antippen muss.
      const from = document.getElementById('custom-range-from');
      const to = document.getElementById('custom-range-to');
      if (activeFilter.id === 'custom' && activeFilter._fromIso && activeFilter._toIso) {
        from.value = activeFilter._fromIso;
        to.value = activeFilter._toIso;
      } else {
        const today = isoFromMs(Date.now());
        if (!from.value) from.value = today;
        if (!to.value) to.value = today;
      }
      from.focus();
    }
  }
  function hideCustomPanel() {
    const panel = document.getElementById('custom-range-panel');
    if (panel) panel.classList.add('hidden');
    const err = document.getElementById('custom-range-error');
    if (err) err.classList.add('hidden');
  }
  function isoFromMs(ms) {
    const d = new Date(ms);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }
  function bindCustomPanel() {
    const apply = document.getElementById('custom-range-apply');
    if (!apply) return;
    apply.addEventListener('click', () => {
      const fromEl = document.getElementById('custom-range-from');
      const toEl = document.getElementById('custom-range-to');
      const err = document.getElementById('custom-range-error');
      const fromMs = parseDateInput(fromEl.value);
      const toMs = parseDateInput(toEl.value);
      if (fromMs == null || toMs == null || fromMs > toMs) {
        err.classList.remove('hidden');
        return;
      }
      err.classList.add('hidden');
      // "Bis" inklusiv: exklusive Obergrenze ist Tag+1 um 00:00.
      const toExclusive = toMs + 86_400_000;
      // Label: Einzeltag kompakt, sonst "dd.mm.yyyy – dd.mm.yyyy".
      const label = fromMs === toMs
        ? `Eigener Zeitraum: ${formatLabelDate(fromMs)}`
        : `Eigener Zeitraum: ${formatLabelDate(fromMs)} – ${formatLabelDate(toMs)}`;
      // FILTERS-Eintrag in-place mutieren, damit renderChips() den neuen Label
      // sieht und der Chip aktiv bleibt.
      const customEntry = FILTERS.find(f => f.id === 'custom');
      customEntry.label = label;
      customEntry.range = () => [fromMs, toExclusive];
      customEntry._fromIso = fromEl.value;
      customEntry._toIso = toEl.value;
      activeFilter = customEntry;
      hideCustomPanel();
      renderChips();
      load();
    });
    // Enter in einem Input löst Apply aus — UX-Polish für Desktop-User.
    ['custom-range-from', 'custom-range-to'].forEach(id => {
      document.getElementById(id).addEventListener('keydown', (e) => {
        if (e.key === 'Enter') { e.preventDefault(); apply.click(); }
      });
    });
  }
  const labelDateFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
  });
  function formatLabelDate(ms) {
    return labelDateFmt.format(new Date(ms));
  }

  function setStatus(text) {
    const el = document.getElementById('filter-status');
    if (el) el.textContent = text || '';
  }

  async function load() {
    setStatus('Lade…');
    emptyMsg.classList.add('hidden');
    list.innerHTML = '';
    const range = activeFilter.range();
    const url = range
      ? `/api/trips?from=${range[0]}&to=${range[1]}&limit=1000`
      : `/api/trips?limit=1000`;
    try {
      const r = await window.EpfAuth.fetchAuthed(url);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const { trips } = await r.json();
      if (!trips || trips.length === 0) {
        emptyMsg.classList.remove('hidden');
        setStatus('0 Fahrten');
        return;
      }
      list.innerHTML = trips.map(cardHtml).join('');
      bindCardClicks();
      // Aggregat-Status für Quick-Glance: Anzahl + Summenstrecke + Gesamt-
      // Fahrzeit + Gesamt-Energie. Wird oben rechts in der Filter-Leiste
      // dargestellt; bei schmalem Viewport bricht's um.
      const totals = trips.reduce((acc, t) => {
        acc.km += t.distance_km || 0;
        acc.s += t.duration_s || 0;
        acc.wh += t.energy_wh || 0;
        return acc;
      }, { km: 0, s: 0, wh: 0 });
      const h = Math.floor(totals.s / 3600);
      const m = Math.floor((totals.s % 3600) / 60);
      const time = h > 0 ? `${h} h ${m} min` : `${m} min`;
      const energy = totals.wh >= 1000
        ? `${(totals.wh / 1000).toFixed(1)} kWh`
        : `${totals.wh.toFixed(0)} Wh`;
      setStatus(
        `${trips.length} Fahrten · ${totals.km.toFixed(1)} km · ${time} · ${energy}`
      );
    } catch (err) {
      console.error(err);
      list.innerHTML = '<div class="text-red-400">Fehler beim Laden.</div>';
      setStatus('Fehler');
    }
  }

  renderChips();
  bindCustomPanel();
  load();
})();
