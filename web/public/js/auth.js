// auth.js — picks up ?token=… from the current page URL (or a sessionStorage
// fallback) and exposes helpers for the other page scripts to use when hitting
// /api/* and /ws/live. If no VIEW_TOKEN is configured server-side the helpers
// are no-ops and the page works as before.
//
// Storing in sessionStorage means once the user opens /dashboard?token=xxx, all
// subsequent in-tab navigations (e.g. /history) work even if the user removes
// the query string. We DON'T persist across tabs (localStorage) — better that
// the URL bookmark stays the source of truth.

(function () {
  'use strict';
  const params = new URLSearchParams(location.search);
  let token = params.get('token');
  if (token) {
    try { sessionStorage.setItem('epf_token', token); } catch { /* private mode */ }
  } else {
    try { token = sessionStorage.getItem('epf_token'); } catch { /* private mode */ }
  }

  function appendToken(url) {
    if (!token) return url;
    const sep = url.includes('?') ? '&' : '?';
    return `${url}${sep}token=${encodeURIComponent(token)}`;
  }

  function fetchAuthed(url, opts) {
    return fetch(appendToken(url), opts);
  }

  function wsUrl(path) {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    return appendToken(`${proto}://${location.host}${path}`);
  }

  window.EpfAuth = { token, appendToken, fetchAuthed, wsUrl };
})();
