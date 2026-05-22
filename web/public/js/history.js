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
      </article>
    `;
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

    new uPlot({
      width: host.clientWidth || 600,
      height: window.innerWidth < 640 ? 150 : 180,
      scales: { x: { time: true } },
      // Legende mit Live-Werten unter dem Cursor — Hover zeigt Zeit + km/h.
      legend: { show: true, live: true },
      cursor: { drag: { x: true, y: false } },
      axes: [
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' }, values: EU_AXIS_VALUES },
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' } }
      ],
      series: [
        { label: 'Zeit', value: cursorValue },
        {
          label: 'km/h',
          stroke: '#4DD0E1',
          width: 2,
          fill: '#4DD0E122',
          spanGaps: true,
          value: (_self, v) => v == null ? '--' : `${v.toFixed(1)} km/h`,
        }
      ]
    }, [xs, ys], host);
  }

  function bindCardClicks() {
    list.querySelectorAll('article[data-trip-id]').forEach(article => {
      article.addEventListener('click', () => {
        const host = article.querySelector('.chart-host');
        if (!host.classList.contains('hidden') && host.children.length > 0) {
          host.classList.add('hidden'); // collapse
          return;
        }
        renderTripChart(article, article.dataset.tripId);
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
        if (!next || next.id === activeFilter.id) return;
        activeFilter = next;
        renderChips();
        load();
      });
    });
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
  load();
})();
