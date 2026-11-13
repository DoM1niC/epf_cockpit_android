// ui.js — gemeinsame UI-Helfer für Live-Dashboard und Fahrtverlauf.
//
// Wird synchron im <head> geladen, damit das Theme (data-theme auf <html>)
// schon vor dem ersten Paint steht und nichts aufblitzt. Alles, was den DOM
// braucht, läuft erst nach DOMContentLoaded.
//
// Exportiert window.EpfUi mit:
//   - Theme:   mode(), theme(), cycleTheme(), onThemeChange(cb)
//   - Format:  num(v, digits), animateNumber(el, value, digits)
//   - Charts:  EU_AXIS_VALUES, timeLabel(sec), chartColors(), axisOpts(),
//              areaFill(colorKey), tooltipPlugin()

(function () {
  'use strict';

  const root = document.documentElement;

  // ---------- Theme ----------
  // Dunkel ist Default. "auto" folgt prefers-color-scheme.
  const THEME_KEY = 'epf.theme';
  const MODES = ['dark', 'light', 'auto'];
  const MODE_LABEL = { dark: 'Dunkel', light: 'Hell', auto: 'Automatisch (System)' };
  let mode = 'dark';
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (MODES.includes(saved)) mode = saved;
  } catch { /* private mode */ }

  const mqlLight = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;
  const listeners = [];

  function resolvedTheme() {
    if (mode === 'auto') return mqlLight && mqlLight.matches ? 'light' : 'dark';
    return mode;
  }

  let colorCache = null;
  function applyTheme() {
    const t = resolvedTheme();
    root.dataset.theme = t;
    root.dataset.themeMode = mode;
    colorCache = null;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', t === 'light' ? '#eef2f7' : '#07090d');
    updateToggle();
    listeners.forEach(cb => { try { cb(t); } catch (e) { console.warn(e); } });
  }

  function updateToggle() {
    const btn = document.getElementById('theme-toggle');
    if (!btn) return;
    const label = `Design: ${MODE_LABEL[mode]} – klicken zum Wechseln`;
    btn.title = label;
    btn.setAttribute('aria-label', label);
  }

  function cycleTheme() {
    mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
    try { localStorage.setItem(THEME_KEY, mode); } catch { /* ignore */ }
    applyTheme();
  }

  if (mqlLight) {
    const onSys = () => { if (mode === 'auto') applyTheme(); };
    if (mqlLight.addEventListener) mqlLight.addEventListener('change', onSys);
    else if (mqlLight.addListener) mqlLight.addListener(onSys);
  }

  applyTheme();

  document.addEventListener('DOMContentLoaded', () => {
    applyTheme(); // meta theme-color + Toggle-Label jetzt, wo der DOM steht
    const btn = document.getElementById('theme-toggle');
    if (btn) btn.addEventListener('click', cycleTheme);
  });

  // ---------- Bewegung ----------
  const mqlReduce = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  const reducedMotion = () => !!(mqlReduce && mqlReduce.matches);

  // ---------- Zahlen ----------
  // Deutsches Zahlenformat (Dezimalkomma). Formatter werden pro
  // Nachkommastellen-Anzahl gecacht.
  const numFmts = new Map();
  function numFmt(digits) {
    let f = numFmts.get(digits);
    if (!f) {
      f = new Intl.NumberFormat('de-DE', {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      });
      numFmts.set(digits, f);
    }
    return f;
  }
  function isNum(v) {
    return v != null && v !== '' && Number.isFinite(Number(v));
  }
  // Platzhalter für fehlende Werte (Live + Verlauf)
  const DASH = '–';
  function num(v, digits = 0) {
    if (!isNum(v)) return DASH;
    // -0 nach dem Runden nicht als "-0" anzeigen
    const n = Number(v);
    const out = numFmt(digits).format(n);
    return /^-0([,.]0*)?$/.test(out) ? out.slice(1) : out;
  }

  // Weiches Hochzählen bei Wertänderungen. Startet vom aktuell angezeigten
  // Zwischenwert, damit schnelle Folgeframes nicht springen.
  function animateNumber(el, value, digits = 0, duration = 600) {
    if (!el) return;
    if (!isNum(value)) {
      if (el._epfRaf) cancelAnimationFrame(el._epfRaf);
      el._epfRaf = null;
      el._epfVal = null;
      el.textContent = DASH;
      return;
    }
    const to = Number(value);
    const from = el._epfVal;
    if (from == null || reducedMotion() || document.hidden || from === to) {
      if (el._epfRaf) cancelAnimationFrame(el._epfRaf);
      el._epfRaf = null;
      el._epfVal = to;
      el.textContent = num(to, digits);
      return;
    }
    if (el._epfRaf) cancelAnimationFrame(el._epfRaf);
    const t0 = performance.now();
    const ease = (t) => 1 - Math.pow(1 - t, 3);
    const step = (now) => {
      const p = Math.min(1, (now - t0) / duration);
      const v = from + (to - from) * ease(p);
      el._epfVal = v;
      el.textContent = num(v, digits);
      if (p < 1) el._epfRaf = requestAnimationFrame(step);
      else { el._epfRaf = null; el._epfVal = to; el.textContent = num(to, digits); }
    };
    el._epfRaf = requestAnimationFrame(step);
  }

  // ---------- Charts (uPlot) ----------
  // uPlot-Defaults sind US-Locale (12h + M/D/YY). EU-Variante mit 24h-Zeit.
  // Tick-Default ist self-contained (z.B. "19:52:03"), die Kontext-Zeile bringt
  // NUR das Datum dazu — keine Doppelung der Zeit.
  const EU_AXIS_VALUES = [
    [3600 * 24 * 365, "{YYYY}",            null,             null, null,           null, null, null, 1],
    [3600 * 24 * 28,  "{MMM}",             "\n{YYYY}",       null, null,           null, null, null, 1],
    [3600 * 24,       "{DD}.{MM}.",        "\n{YYYY}",       null, null,           null, null, null, 1],
    [3600,            "{HH}:{mm}",         "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [60,              "{HH}:{mm}",         "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [1,               "{HH}:{mm}:{ss}",    "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
    [0.001,           "{HH}:{mm}:{ss}.{fff}", "\n{DD}.{MM}.{YYYY}", null, "\n{DD}.{MM}.", null, null, null, 1],
  ];

  const tipTimeFmt = new Intl.DateTimeFormat('de-DE', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  });
  function timeLabel(sec) {
    return sec == null ? DASH : tipTimeFmt.format(new Date(sec * 1000));
  }

  function cssVar(name) {
    return getComputedStyle(root).getPropertyValue(name).trim();
  }
  // Farben aus den CSS-Tokens (tailwind.css), gecacht bis zum Theme-Wechsel.
  function chartColors() {
    if (!colorCache) {
      colorCache = {
        axis:    cssVar('--chart-axis')    || '#7d879a',
        grid:    cssVar('--chart-grid')    || 'rgba(255,255,255,0.06)',
        teal:    cssVar('--chart-teal')    || '#4DD0E1',
        amber:   cssVar('--chart-amber')   || '#FFB300',
        magenta: cssVar('--chart-magenta') || '#EC407A',
        fillAlpha: Number(cssVar('--chart-fill-alpha')) || 0.3,
        surface: cssVar('--card-solid')    || '#11151c',
      };
    }
    return colorCache;
  }

  function withAlpha(color, alpha) {
    const m = /^#([0-9a-f]{6})$/i.exec(color);
    if (!m) return color;
    const n = parseInt(m[1], 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
  }

  const AXIS_FONT = '500 11px "Inter Variable", ui-sans-serif, system-ui, sans-serif';

  // Achsen-Optionen mit Theme-abhängigen Farben (als Funktionen, damit ein
  // Theme-Wechsel per u.redraw() ohne Neuaufbau greift).
  function axisOpts(extra = {}) {
    return Object.assign({
      stroke: () => chartColors().axis,
      font: AXIS_FONT,
      grid: { stroke: () => chartColors().grid, width: 1, dash: [3, 4] },
      ticks: { show: false },
      gap: 6,
    }, extra);
  }

  // Serie: Linie in Theme-Farbe, Fläche als vertikaler Verlauf nach unten.
  function seriesStroke(colorKey) {
    return () => chartColors()[colorKey];
  }
  function areaFill(colorKey) {
    return (u) => {
      const c = chartColors();
      const top = u.bbox.top;
      const h = u.bbox.height || 1;
      const g = u.ctx.createLinearGradient(0, top, 0, top + h);
      g.addColorStop(0, withAlpha(c[colorKey], c.fillAlpha));
      g.addColorStop(1, withAlpha(c[colorKey], 0));
      return g;
    };
  }

  // y-Achsen-Beschriftung im deutschen Zahlenformat; Nachkommastellen
  // richten sich nach dem Tick-Abstand.
  function yValues(suffix = '') {
    return (_u, splits) => {
      const step = splits.length > 1 ? Math.abs(splits[1] - splits[0]) : 1;
      const digits = step >= 1 || step === 0 ? 0 : step >= 0.1 ? 1 : 2;
      return splits.map(v => v == null ? '' : num(v, digits) + suffix);
    };
  }

  // Schwebender Tooltip statt der Legende unter dem Chart: zeigt Zeit + alle
  // sichtbaren Serien am Cursor. Formatierung kommt aus series[i].value.
  function tooltipPlugin() {
    let tip = null;
    return {
      hooks: {
        init(u) {
          tip = document.createElement('div');
          tip.className = 'chart-tip';
          tip.hidden = true;
          u.over.appendChild(tip);
          u.over.addEventListener('mouseleave', () => { tip.hidden = true; });
        },
        setCursor(u) {
          if (!tip) return;
          const { left, top, idx } = u.cursor;
          if (idx == null || left == null || left < 0) { tip.hidden = true; return; }
          const xs = u.data[0];
          if (!xs || xs[idx] == null) { tip.hidden = true; return; }
          let html = `<div class="chart-tip-time">${timeLabel(xs[idx])}</div>`;
          for (let i = 1; i < u.series.length; i++) {
            const s = u.series[i];
            if (!s.show) continue;
            const v = u.data[i] ? u.data[i][idx] : null;
            const color = typeof s.stroke === 'function' ? s.stroke(u, i) : s.stroke;
            const val = typeof s.value === 'function' ? s.value(u, v, i, idx) : (v ?? DASH);
            html += `<div class="chart-tip-row"><span class="chart-tip-dot" style="background:${color}"></span>` +
                    `<span>${s.label}</span><b>${val}</b></div>`;
          }
          tip.innerHTML = html;
          tip.hidden = false;
          const w = u.over.clientWidth;
          const h = u.over.clientHeight;
          const tw = tip.offsetWidth;
          const th = tip.offsetHeight;
          let x = left + 14;
          if (x + tw > w) x = left - tw - 14;
          if (x < 0) x = Math.max(0, Math.min(w - tw, left - tw / 2));
          let y = (top == null || top < 0 ? h / 2 : top) - th / 2;
          y = Math.max(0, Math.min(h - th, y));
          tip.style.transform = `translate(${Math.round(x)}px, ${Math.round(y)}px)`;
        },
      },
    };
  }

  // Nach dem Laden der Schrift einmal neu zeichnen, damit die Achsenbeschriftung
  // nicht in der Fallback-Schrift hängen bleibt.
  function whenFontsReady(cb) {
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(cb).catch(() => {});
  }

  window.EpfUi = {
    // Theme
    mode: () => mode,
    theme: resolvedTheme,
    cycleTheme,
    onThemeChange(cb) { listeners.push(cb); },
    reducedMotion,
    // Format
    DASH,
    num,
    isNum,
    animateNumber,
    // Charts
    EU_AXIS_VALUES,
    timeLabel,
    chartColors,
    withAlpha,
    axisOpts,
    seriesStroke,
    areaFill,
    yValues,
    tooltipPlugin,
    whenFontsReady,
  };
})();
