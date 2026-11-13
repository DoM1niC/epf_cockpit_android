// history.js — fetches /api/trips and renders an expandable card list,
// gruppiert nach Tag, plus Summen-Kacheln für den gewählten Zeitraum.
// Clicking a card lazily fetches /api/trips/:id (Chart) und
// /api/trips/:id/locations (Route-Karte).
// Gemeinsame Helfer (Theme, Zahlenformat, Chart-Theme, Tooltip) kommen aus
// /js/ui.js (window.EpfUi).

(function () {
  'use strict';

  const UI = window.EpfUi;
  const num = UI.num;

  const list = document.getElementById('trip-list');
  const emptyMsg = document.getElementById('empty-msg');
  const aggHost = document.getElementById('agg-stats');

  // EU-Datum + 24h-Zeit: "22.05.2026, 14:30". hour12:false forciert 24h
  // auch in en-US-Locales, die sonst AM/PM einsetzen.
  const dateFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
    hour12: false,
  });
  const timeFmt = new Intl.DateTimeFormat('de-DE', {
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
  const dayFmt = new Intl.DateTimeFormat('de-DE', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
  });
  function fmtDate(ms) {
    if (!ms) return UI.DASH;
    return dateFmt.format(new Date(ms));
  }

  function fmtDuration(s) {
    if (s == null) return UI.DASH;
    const m = Math.floor(s / 60);
    const sec = s % 60;
    if (m < 60) return `${m} min ${sec} s`;
    const h = Math.floor(m / 60);
    return `${h} h ${m % 60} min`;
  }

  function fmtNum(v, digits = 1) {
    return num(v, digits);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
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
    chevron:     '<path d="m6 9 6 6 6-6"/>',
    hash:        '<line x1="4" x2="20" y1="9" y2="9"/><line x1="4" x2="20" y1="15" y2="15"/><line x1="10" x2="8" y1="3" y2="21"/><line x1="16" x2="14" y1="3" y2="21"/>',
    maximize:    '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" x2="14" y1="3" y2="10"/><line x1="3" x2="10" y1="21" y2="14"/>',
    minimize:    '<polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" x2="21" y1="10" y2="3"/><line x1="3" x2="10" y1="21" y2="14"/>',
  };
  function svg(key, cls = 'icon w-3.5 h-3.5') {
    return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[key]}</svg>`;
  }

  function statHtml(iconKey, label, value) {
    return `
      <div>
        <dt>${svg(iconKey)} ${label}</dt>
        <dd>${value}</dd>
      </div>`;
  }

  // Alte Trips haben teilweise die BLE-MAC als scooter_name gespeichert,
  // bevor der App-seitige Anzeigename eingefuehrt wurde. Wir filtern das im
  // Frontend raus damit "AA:BB:CC:..." nicht in der Karten-Liste auftaucht.
  const MAC_RE = /^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}$/;
  function displayScooterName(name) {
    if (!name || MAC_RE.test(name)) return 'E-Scooter';
    return name;
  }

  function cardHtml(trip) {
    const running = !trip.ended_at;
    const timeRange = running
      ? `${timeFmt.format(new Date(trip.started_at))} – läuft`
      : `${timeFmt.format(new Date(trip.started_at))} – ${timeFmt.format(new Date(trip.ended_at))}`;
    return `
      <article data-trip-id="${escapeHtml(trip.id)}" class="card trip-card" tabindex="0"
               aria-expanded="false" title="${fmtDate(trip.started_at)}">
        <header class="trip-head">
          <span class="trip-badge">${svg('route', 'icon w-5 h-5')}</span>
          <div class="min-w-0">
            <div class="trip-title">${timeRange}</div>
            <div class="trip-sub">
              ${running ? '<span class="pill" data-tone="ok" style="padding:0.05rem 0.45rem;font-size:0.6875rem"><span class="pill-dot"></span>Laufend</span>' : ''}
              <span>${escapeHtml(displayScooterName(trip.scooter_name))}</span>
            </div>
          </div>
          <div class="trip-distance">${trip.distance_km != null
            ? `${fmtNum(trip.distance_km, 2)}<small>km</small>`
            : `<small>${running ? 'unterwegs' : 'k. A.'}</small>`}</div>
          ${svg('chevron', 'icon w-5 h-5 trip-chevron')}
        </header>
        <dl class="trip-stats">
          ${statHtml('clock',      'Dauer',     running ? 'läuft' : fmtDuration(trip.duration_s))}
          ${statHtml('gauge',      'Max.',      `${fmtNum(trip.max_speed_kmh, 1)} km/h`)}
          ${statHtml('trendingUp', 'Ø Tempo',   `${fmtNum(trip.avg_speed_kmh, 1)} km/h`)}
          ${statHtml('zap',        'Energie',   `${fmtNum(trip.energy_wh, 1)} Wh`)}
        </dl>
        <div class="chart-host mt-4 hidden"></div>
        <div class="map-host mt-3 hidden"></div>
      </article>
    `;
  }

  // Tages-Gruppen: "Heute", "Gestern", sonst "Mittwoch, 12. November 2026".
  function dayKey(ms) {
    const d = new Date(ms);
    return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
  }
  function dayLabel(ms) {
    const d = new Date(ms); d.setHours(0, 0, 0, 0);
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const diff = Math.round((today - d) / 86_400_000);
    if (diff === 0) return 'Heute';
    if (diff === 1) return 'Gestern';
    return dayFmt.format(d);
  }
  function groupedHtml(trips) {
    const groups = [];
    let cur = null;
    for (const t of trips) {
      const k = dayKey(t.started_at);
      if (!cur || cur.key !== k) { cur = { key: k, ts: t.started_at, trips: [] }; groups.push(cur); }
      cur.trips.push(t);
    }
    return groups.map(g => {
      const km = g.trips.reduce((a, t) => a + (t.distance_km || 0), 0);
      const n = g.trips.length;
      return `
        <section class="day-group">
          <div class="day-head">
            <span class="day-head-title">${dayLabel(g.ts)}</span>
            <span class="day-head-meta">${n} ${n === 1 ? 'Fahrt' : 'Fahrten'} · ${num(km, 1)} km</span>
          </div>
          ${g.trips.map(cardHtml).join('')}
        </section>`;
    }).join('');
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
            // Wenn der Cursor noch nicht ueber dem Chart war (cursor.left
            // == null oder negativ), faellt der Anker auf die Chart-Mitte
            // zurueck — sonst tut Wheel-Zoom nichts beim ersten Spin.
            const widthCss = u.bbox.width / window.devicePixelRatio;
            let left = u.cursor.left;
            if (left == null || left < 0) left = widthCss / 2;
            const xVal = u.posToVal(left, 'x');
            const { min, max } = u.scales.x;
            const oxRange = max - min;
            // Negative deltaY = wheel up = zoom in (kleinere Range).
            const nxRange = e.deltaY < 0 ? oxRange * factor : oxRange / factor;
            const leftPct = left / widthCss;
            const nxMin = xVal - leftPct * nxRange;
            const nxMax = nxMin + nxRange;
            u.batch(() => u.setScale('x', { min: nxMin, max: nxMax }));
          }, { passive: false });
        }
      }
    };
  }

  // Offene Trip-Charts, damit ein Theme-Wechsel sie neu einfärbt.
  const openCharts = new Set();
  UI.onThemeChange(() => openCharts.forEach(u => u.redraw()));

  function disposeTripChart(article) {
    const host = article.querySelector('.chart-host');
    if (host && host._uplot) {
      openCharts.delete(host._uplot);
      try { host._uplot.destroy(); } catch { /* ignore */ }
      host._uplot = null;
    }
    if (host && host._ro) { host._ro.disconnect(); host._ro = null; }
  }

  async function renderTripChart(article, tripId) {
    const host = article.querySelector('.chart-host');
    disposeTripChart(article);
    host.classList.remove('hidden');
    host.innerHTML = '<div class="notice">Lade Telemetrie…</div>';

    let samples;
    try {
      const r = await window.EpfAuth.fetchAuthed(`/api/trips/${encodeURIComponent(tripId)}`);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      ({ samples } = await r.json());
    } catch (err) {
      console.warn('[history] could not load trip:', err);
      host.innerHTML = '<div class="notice notice-error">Fahrt konnte nicht geladen werden.</div>';
      return;
    }
    // Inzwischen wieder zugeklappt → nichts rendern.
    if (!article.classList.contains('is-open')) return;
    host.innerHTML = '';

    if (!samples || samples.length === 0) {
      host.innerHTML = '<div class="notice">Keine Telemetrie-Daten für diese Fahrt.</div>';
      return;
    }

    const xs = samples.map(s => s.ts / 1000);
    const ys = samples.map(s => s.speedKmh);
    // Akku-Serie nur einbauen wenn auch wirklich Werte da sind (alte Trips
    // vor dem battery_percent-Logging haben hier durchgehend null).
    const batt = samples.map(s => s.batteryPercent);
    const hasBatt = batt.some(v => v != null);

    // Toolbar oben (Legende + Hinweis + Vergrößern) + Canvas-Container.
    // Click-Stop-Propagation damit Button-Klicks nicht den Card-Collapse
    // triggern.
    host.innerHTML = `
      <div class="trip-detail-toolbar">
        <span class="legend-item"><span class="swatch swatch-line" style="--sw: var(--chart-teal)"></span>Tempo</span>
        ${hasBatt ? '<span class="legend-item"><span class="swatch swatch-dash" style="--sw: var(--chart-amber)"></span>Akku</span>' : ''}
        <span class="hint hidden sm:inline">Ziehen/Mausrad = Zoom · Doppelklick = Reset</span>
        <button class="zoom-btn btn-ghost ml-auto" type="button">${svg('maximize')}<span>Vergrößern</span></button>
      </div>
      <div class="chart-canvas"></div>
    `;
    const canvas = host.querySelector('.chart-canvas');
    const btn = host.querySelector('.zoom-btn');

    const baseHeight = window.innerWidth < 640 ? 190 : 230;
    const largeHeight = window.innerWidth < 640 ? 360 : 460;
    let large = false;

    const C = UI.chartColors;
    const chart = new uPlot({
      width: canvas.clientWidth || 600,
      height: baseHeight,
      plugins: [wheelZoomPlugin({ factor: 0.85 }), UI.tooltipPlugin()],
      padding: [10, hasBatt ? 4 : 18, 0, 0],
      scales: {
        x: { time: true },
        // Akku auf eigener Achse — fix 0..100% damit der Verlauf nicht
        // visuell explodiert wenn der User von 100→95 fährt.
        batt: { range: [0, 100] },
      },
      // Statt Legende: schwebender Tooltip mit Zeit + km/h + %.
      legend: { show: false },
      cursor: { drag: { x: true, y: false }, points: { size: 9 } },
      axes: [
        UI.axisOpts({ values: UI.EU_AXIS_VALUES, space: () => (window.innerWidth < 640 ? 46 : 70) }),
        UI.axisOpts({
          size: 46, space: 32,
          stroke: () => hasBatt ? C().teal : C().axis,
          values: UI.yValues(),
        }),
        // Akku-Achse rechts in Amber — visuell als "anderer Wert" erkennbar.
        ...(hasBatt ? [UI.axisOpts({
          scale: 'batt', side: 1, size: 46, space: 32, grid: { show: false },
          stroke: () => C().amber,
          values: UI.yValues(' %'),
        })] : []),
      ],
      series: [
        { label: 'Zeit', value: (_u, v) => UI.timeLabel(v) },
        {
          label: 'Tempo',
          stroke: UI.seriesStroke('teal'),
          width: 2,
          fill: UI.areaFill('teal'),
          spanGaps: true,
          // points.show ist per Default eine Function die auto-anzeigt sobald
          // die Punkte genug Abstand haben — im Default-Zoom unsichtbar,
          // beim Reinzoomen erscheinen die Messpunkte automatisch.
          points: { size: 6, fill: () => C().surface },
          value: (_self, v) => v == null ? UI.DASH : `${num(v, 1)} km/h`,
        },
        ...(hasBatt ? [{
          label: 'Akku',
          scale: 'batt',
          stroke: UI.seriesStroke('amber'),
          width: 2,
          dash: [5, 4],
          spanGaps: true,
          points: { size: 6, fill: () => C().surface },
          value: (_self, v) => v == null ? UI.DASH : `${num(v, 0)} %`,
        }] : []),
      ],
    }, hasBatt ? [xs, ys, batt] : [xs, ys], canvas);
    host._uplot = chart;
    openCharts.add(chart);
    UI.whenFontsReady(() => { if (host._uplot === chart) chart.redraw(false, true); });

    // Breite mitführen (Rotation, Fenstergröße).
    if (window.ResizeObserver) {
      host._ro = new ResizeObserver(() => {
        const w = canvas.clientWidth;
        if (w > 0 && w !== chart.width) chart.setSize({ width: w, height: chart.height });
      });
      host._ro.observe(canvas);
    }

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
      btn.innerHTML = large
        ? `${svg('minimize')}<span>Verkleinern</span>`
        : `${svg('maximize')}<span>Vergrößern</span>`;
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

  // Circle-Marker für Start/Ende — bleiben auf jedem Zoomlevel gleich groß
  // und brauchen keinen zusätzlichen Asset-Request.
  const startMarkerStyle = {
    radius: 7, color: '#ffffff', weight: 2.5,
    fillColor: '#22c55e', fillOpacity: 1, // grün
  };
  const endMarkerStyle = {
    radius: 7, color: '#ffffff', weight: 2.5,
    fillColor: '#E91E63', fillOpacity: 1, // magenta
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
    host.innerHTML = '<div class="notice">Lade Route…</div>';

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
    // Inzwischen wieder zugeklappt → nichts rendern.
    if (!article.classList.contains('is-open')) return;

    if (!points || points.length < 2) {
      // Alte Fahrten ohne Locations: dezenter Hinweis statt leerer Karte.
      // (height/rounded greift nur wenn .map-host die Klasse .has-map trägt).
      host.innerHTML = '<div class="notice">Keine Standortdaten für diese Fahrt.</div>';
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

    // Im dunklen Theme per CSS-Filter abgedunkelt (.tiles-themed).
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors',
      maxZoom: 19,
      className: 'tiles-themed',
    }).addTo(map);

    const poly = L.polyline(latlngs, {
      color: '#4DD0E1',
      weight: 4,
      opacity: 0.95,
      lineJoin: 'round',
      lineCap: 'round',
      className: 'live-trail',
    }).addTo(map);

    // Wegpunkte als kleine Dots sichtbar machen — sonst sieht ein Trip mit
    // wenigen, weit auseinanderliegenden Fixes wie pure Luftlinie aus. Bei
    // sehr langen Trips (>500 Punkte) jeden N-ten Punkt nehmen, sonst
    // ruckelt die Map beim Schwenken.
    const stride = latlngs.length > 500 ? Math.ceil(latlngs.length / 500) : 1;
    for (let i = 1; i < latlngs.length - 1; i += stride) {
      L.circleMarker(latlngs[i], {
        radius: 2.5, color: '#4DD0E1', weight: 1,
        fillColor: '#4DD0E1', fillOpacity: 0.9, interactive: false,
      }).addTo(map);
    }

    L.circleMarker(latlngs[0], startMarkerStyle)
      .bindTooltip('Start', { direction: 'top', offset: [0, -8] })
      .addTo(map);
    L.circleMarker(latlngs[latlngs.length - 1], endMarkerStyle)
      .bindTooltip('Ende', { direction: 'top', offset: [0, -8] })
      .addTo(map);

    map.fitBounds(poly.getBounds(), { padding: [20, 20] });

    // Falls die Card zwischenzeitlich resized wurde (Chart rendert zuerst),
    // nach dem nächsten Tick invalidateSize aufrufen, damit Tiles sitzen.
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

  function collapseCard(article) {
    article.classList.remove('is-open');
    article.setAttribute('aria-expanded', 'false');
    const chartHost = article.querySelector('.chart-host');
    disposeTripChart(article);
    chartHost.classList.add('hidden');
    chartHost.innerHTML = '';
    const mapHost = article.querySelector('.map-host');
    if (mapHost) {
      disposeTripMap(article);
      mapHost.classList.add('hidden');
      mapHost.classList.remove('has-map');
      mapHost.innerHTML = '';
    }
  }

  function expandCard(article) {
    article.classList.add('is-open');
    article.setAttribute('aria-expanded', 'true');
    // Chart zuerst (Hauptinhalt), Map asynchron daneben — die beiden
    // fetches sind unabhängig, also nicht awaiten.
    renderTripChart(article, article.dataset.tripId);
    renderTripMap(article, article.dataset.tripId);
  }

  function toggleCard(article) {
    if (article.classList.contains('is-open')) collapseCard(article);
    else expandCard(article);
  }

  function bindCardClicks() {
    list.querySelectorAll('article[data-trip-id]').forEach(article => {
      // Beim Wisch/Pan endet der Mouseup haeufig AUSSERHALB der Karte (z.B.
      // auf dem Card-Body), wenn der User schwungvoll zieht. e.target im
      // anschliessenden Click waere dann nicht mehr im .map-host und der
      // Card-Collapse wuerde feuern. Wir merken uns deshalb wo der Drag
      // STARTET (pointerdown) und ignorieren den Click wenn er aus dem
      // Chart-/Map-Bereich kommt.
      let downInProtected = false;
      article.addEventListener('pointerdown', (e) => {
        downInProtected = !!e.target.closest('.chart-host, .map-host');
      });
      article.addEventListener('click', (e) => {
        if (downInProtected || e.target.closest('.chart-host, .map-host')) {
          downInProtected = false;
          return;
        }
        toggleCard(article);
      });
      // Tastatur: Enter/Leertaste auf der fokussierten Karte.
      article.addEventListener('keydown', (e) => {
        if (e.target !== article) return;
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          toggleCard(article);
        }
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
  let activeFilter = FILTERS[2]; // Default: 30 Tage (vor Restore)

  // Filter-Auswahl über Refresh hinweg merken. Bei Custom-Range zusätzlich
  // die ISO-Daten persistieren, damit der gleiche Zeitraum exakt wieder
  // hergestellt wird.
  const FILTER_STORAGE_KEY = 'epf.history.filter';
  function saveActiveFilter() {
    try {
      const payload = { id: activeFilter.id };
      if (activeFilter.id === 'custom' && activeFilter._fromIso && activeFilter._toIso) {
        payload.fromIso = activeFilter._fromIso;
        payload.toIso = activeFilter._toIso;
      }
      localStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(payload));
    } catch { /* localStorage disabled / private mode — silently ignore */ }
  }
  function restoreActiveFilter() {
    let saved;
    try {
      const raw = localStorage.getItem(FILTER_STORAGE_KEY);
      if (!raw) return;
      saved = JSON.parse(raw);
    } catch { return; }
    if (!saved || typeof saved.id !== 'string') return;
    const entry = FILTERS.find(f => f.id === saved.id);
    if (!entry) return;
    if (entry.id === 'custom') {
      // Custom-Range nur restoren wenn beide Daten valide sind. Sonst auf
      // den Default-Filter zurueckfallen.
      const fromMs = parseDateInput(saved.fromIso);
      const toMs = parseDateInput(saved.toIso);
      if (fromMs == null || toMs == null || fromMs > toMs) return;
      applyCustomEntry(entry, fromMs, toMs, saved.fromIso, saved.toIso);
    }
    activeFilter = entry;
  }

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
      const icon = f.isCustomToggle ? svg('calendar') : '';
      return `<button type="button" data-filter="${f.id}" class="filter-chip chip" aria-pressed="${active}">${icon}${f.label}</button>`;
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
        saveActiveFilter();
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
  // FILTERS-Eintrag in-place mutieren, damit renderChips() den neuen Label
  // sieht und der Chip aktiv bleibt. "Bis" inklusiv: exklusive Obergrenze ist
  // Tag+1 um 00:00.
  function applyCustomEntry(entry, fromMs, toMs, fromIso, toIso) {
    const toExclusive = toMs + 86_400_000;
    entry.label = fromMs === toMs
      ? `Eigener Zeitraum: ${formatLabelDate(fromMs)}`
      : `Eigener Zeitraum: ${formatLabelDate(fromMs)} – ${formatLabelDate(toMs)}`;
    entry.range = () => [fromMs, toExclusive];
    entry._fromIso = fromIso;
    entry._toIso = toIso;
  }
  function toggleCustomPanel() {
    const panel = document.getElementById('custom-range-panel');
    if (!panel) return;
    panel.classList.toggle('hidden');
    if (!panel.classList.contains('hidden')) {
      // Defaults: Wenn schon ein Custom-Filter aktiv ist, dessen Werte zeigen;
      // sonst die heutige Range vorausfüllen.
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
      const customEntry = FILTERS.find(f => f.id === 'custom');
      applyCustomEntry(customEntry, fromMs, toMs, fromEl.value, toEl.value);
      activeFilter = customEntry;
      saveActiveFilter();
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

  // ---------- Summen-Kacheln ----------
  function aggTile(accent, iconKey, label, valueHtml, caption) {
    return `
      <div class="card stat-tile" data-accent="${accent}">
        <span class="label">${svg(iconKey)} ${label}</span>
        <div class="stat-value">${valueHtml}</div>
        <div class="stat-caption">${caption || '&nbsp;'}</div>
      </div>`;
  }
  function renderAggregates(trips) {
    if (!aggHost) return;
    // Aggregat für Quick-Glance: Anzahl + Summenstrecke + Gesamt-Fahrzeit +
    // Gesamt-Energie (+ Ø Verbrauch, wenn Strecke > 0).
    const totals = trips.reduce((acc, t) => {
      acc.km += t.distance_km || 0;
      acc.s += t.duration_s || 0;
      acc.wh += t.energy_wh || 0;
      acc.max = Math.max(acc.max, t.max_speed_kmh || 0);
      return acc;
    }, { km: 0, s: 0, wh: 0, max: 0 });
    const h = Math.floor(totals.s / 3600);
    const m = Math.floor((totals.s % 3600) / 60);
    const timeHtml = h > 0 ? `${h}<small>h</small> ${m}<small>min</small>` : `${m}<small>min</small>`;
    const energyHtml = totals.wh >= 1000
      ? `${num(totals.wh / 1000, 1)}<small>kWh</small>`
      : `${num(totals.wh, 0)}<small>Wh</small>`;
    const perKm = totals.km > 0 ? `Ø ${num(totals.wh / totals.km, 1)} Wh/km` : '';
    const avgKm = trips.length ? `Ø ${num(totals.km / trips.length, 1)} km pro Fahrt` : '';
    aggHost.innerHTML =
      aggTile('teal',    'hash',  'Fahrten',  `${num(trips.length)}`, totals.max ? `Spitze ${num(totals.max, 1)} km/h` : '') +
      aggTile('teal',    'route', 'Strecke',  `${num(totals.km, 1)}<small>km</small>`, avgKm) +
      aggTile('amber',   'clock', 'Fahrzeit', timeHtml, '') +
      aggTile('magenta', 'zap',   'Energie',  energyHtml, perKm);
    aggHost.hidden = false;

  }

  // Statuszeile oben rechts: welcher Zeitraum gerade angezeigt wird.
  function rangeLabel() {
    const range = activeFilter.range();
    if (!range) return 'Alle Fahrten';
    const from = formatLabelDate(range[0]);
    const to = formatLabelDate(Math.min(range[1], Date.now()) - 1);
    return from === to ? from : `${from} – ${to}`;
  }

  async function load() {
    setStatus('Lade…');
    emptyMsg.classList.add('hidden');
    list.querySelectorAll('article[data-trip-id]').forEach(a => { disposeTripChart(a); disposeTripMap(a); });
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
        if (aggHost) aggHost.hidden = true;
        emptyMsg.classList.remove('hidden');
        setStatus(rangeLabel());
        return;
      }
      list.innerHTML = groupedHtml(trips);
      bindCardClicks();
      renderAggregates(trips);
      setStatus(rangeLabel());
    } catch (err) {
      console.error(err);
      if (aggHost) aggHost.hidden = true;
      list.innerHTML = '<div class="card notice notice-error">Fehler beim Laden der Fahrten. Bitte Seite neu laden.</div>';
      setStatus('Fehler');
    }
  }

  restoreActiveFilter();
  renderChips();
  bindCustomPanel();
  load();
})();
