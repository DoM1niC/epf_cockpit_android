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
          <div><dt class="text-[var(--color-on-surface-dim)]">Distance</dt><dd>${fmtNum(trip.distance_km, 2)} km</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Duration</dt><dd>${fmtDuration(trip.duration_s)}</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Max speed</dt><dd>${fmtNum(trip.max_speed_kmh, 1)} km/h</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Avg speed</dt><dd>${fmtNum(trip.avg_speed_kmh, 1)} km/h</dd></div>
          <div><dt class="text-[var(--color-on-surface-dim)]">Energy</dt><dd>${fmtNum(trip.energy_wh, 1)} Wh</dd></div>
        </dl>
        <div class="chart-host mt-4 hidden"></div>
      </article>
    `;
  }

  async function renderTripChart(article, tripId) {
    const host = article.querySelector('.chart-host');
    host.classList.remove('hidden');
    host.innerHTML = '<div class="text-xs text-[var(--color-on-surface-dim)]">loading…</div>';

    const r = await window.EpfAuth.fetchAuthed(`/api/trips/${encodeURIComponent(tripId)}`);
    if (!r.ok) {
      host.innerHTML = '<div class="text-xs text-red-400">failed to load trip</div>';
      return;
    }
    const { samples } = await r.json();
    host.innerHTML = '';

    if (!samples || samples.length === 0) {
      host.innerHTML = '<div class="text-xs text-[var(--color-on-surface-dim)]">no samples</div>';
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

  async function load() {
    const r = await window.EpfAuth.fetchAuthed('/api/trips?limit=100');
    const { trips } = await r.json();
    if (!trips || trips.length === 0) {
      emptyMsg.classList.remove('hidden');
      return;
    }
    list.innerHTML = trips.map(cardHtml).join('');
    bindCardClicks();
  }

  load().catch(err => {
    console.error(err);
    list.innerHTML = '<div class="text-red-400">Failed to load trips.</div>';
  });
})();
