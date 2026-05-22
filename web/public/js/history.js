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
    if (m < 60) return `${m}m ${sec}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  }

  function fmtNum(v, digits = 1) {
    return v == null ? '--' : Number(v).toFixed(digits);
  }

  function cardHtml(trip) {
    return `
      <article data-trip-id="${trip.id}"
               class="rounded-2xl bg-[var(--color-surface-1)] p-4 cursor-pointer hover:bg-[var(--color-surface-2)] transition">
        <header class="flex items-baseline justify-between gap-3 flex-wrap">
          <div class="font-medium">${fmtDate(trip.started_at)}</div>
          <div class="text-sm text-[var(--color-on-surface-dim)]">${trip.scooter_name || ''}</div>
        </header>
        <dl class="mt-3 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3 text-sm tabular-nums">
          <div><dt class="text-[var(--color-on-surface-dim)]">Strecke</dt><dd>${fmtNum(trip.distance_km, 2)} km</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Dauer</dt><dd>${fmtDuration(trip.duration_s)}</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Max.</dt><dd>${fmtNum(trip.max_speed_kmh, 1)} km/h</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Ø</dt><dd>${fmtNum(trip.avg_speed_kmh, 1)} km/h</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Energie</dt><dd>${fmtNum(trip.energy_wh, 1)} Wh</dd></div>
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
      legend: { show: false },
      cursor: { drag: { x: true, y: false } },
      axes: [
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' }, values: EU_AXIS_VALUES },
        { stroke: '#9aa3b2', grid: { stroke: 'rgba(255,255,255,0.05)' } }
      ],
      series: [
        { value: cursorValue },
        { label: 'km/h', stroke: '#4DD0E1', width: 2, fill: '#4DD0E122', spanGaps: true }
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
