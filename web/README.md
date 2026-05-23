# EPF Cockpit Web

A small Node.js telemetry dashboard for the **EPF Cockpit Android** e-scooter
app. The Android app opens a WebSocket to this server and streams live BLE
telemetry; the server stores everything in SQLite (WAL) and serves three
browser views:

| Route       | Purpose                                                       |
|-------------|---------------------------------------------------------------|
| `/`         | Live dashboard — speed/battery/power tiles + uPlot charts     |
| `/obs`      | Transparent OBS Browser Source widget                         |
| `/history`  | Trip list with per-trip expandable speed chart                |

## Prerequisites

- **Node.js 22+** (uses native `--watch` and Express 5)
- npm 10+

## Quick start

```bash
cd web
cp .env.example .env          # optional — defaults work fine
npm install
npm run build                  # copies uPlot dist + builds Tailwind
npm start                      # http://localhost:8787
```

For development with auto-reload (Node 22 built-in watch mode):

```bash
npm run dev
```

Tailwind is only rebuilt by `npm run build` / `npm run build:css`. Run it
again whenever you change HTML or `public/css/tailwind.css`.

### Docker / Compose

```bash
cp .env.example .env           # tokens setzen (oder leer = no-auth)
docker compose up -d --build   # http://localhost:8787
docker compose logs -f
```

Persistente SQLite-DB landet im bind-gemounteten `./data/`. Details
(Multi-Stage-Build, Healthcheck, Update-Flow, nginx-Integration) im
[deploy-README](deploy/README.md#alternative-docker--compose).

## Environment variables

| Var       | Default            | Notes                              |
|-----------|--------------------|------------------------------------|
| `PORT`    | `8787`             | HTTP + WebSocket port              |
| `DB_PATH` | `./data/epf.db`    | SQLite file (WAL mode is enabled)  |

## Pointing the Android app at it

The Android app should connect to:

```
ws://<lan-ip>:8787/ws/ingest        # on your LAN
wss://cockpit.3dns.eu/ws/ingest     # public, behind nginx (see below)
```

The contract is JSON-per-text-frame:

```jsonc
{"type":"hello","scooterName":"HW_EF043840","scooterAddress":"AA:BB:..","authenticated":true,"app":"epf_cockpit_android","appVersion":"0.1.0"}
{"type":"telemetry","ts":1700000000000,"speedKmh":12.3,"batteryPercent":85,"voltageV":36.2,"currentA":4.1,"powerW":148,"controllerTempC":32,"tripKm":1.23,"totalKm":412.5,"parking":false,"rideModeStand":false}
{"type":"state","connected":true,"authenticated":true,"scooterName":"HW_EF043840"}
{"type":"trip_start","ts":1700000000000,"tripId":"<uuid-v4>"}
{"type":"trip_end","tripId":"<uuid-v4>","ts":1700000300000,"distanceKm":1.5,"energyWh":42.3,"durationS":300,"maxSpeedKmh":22.1,"avgSpeedKmh":18.0}
```

Browsers consume the same shape on `/ws/live`.

## Security — shared-token auth

Two independent tokens, beide in `.env`/`EnvironmentFile` setzbar:

| Variable        | Schützt                                      | Wie übergeben                                   |
|-----------------|----------------------------------------------|-------------------------------------------------|
| `INGEST_TOKEN`  | `/ws/ingest` (Android → Server)              | `?token=…` im URL **oder** `Authorization: Bearer …` |
| `VIEW_TOKEN`    | `/`, `/obs`, `/history`, `/api/*`, `/ws/live` | `?token=…` (Browser merkt's in sessionStorage)  |

Sind beide leer, läuft alles offen (Dev-Setup). Sind sie gesetzt, gibt's
ohne Token `401`. Vergleich ist konstant-zeitig (`crypto.timingSafeEqual`).

Token generieren:

```bash
openssl rand -hex 32   # 256 Bit, URL-safe
```

`/healthz` ist **immer** offen, damit nginx-Upstream-Checks / Uptime-Monitore
funktionieren ohne den Token zu kennen.

> Token in URL landen ohne Sondermaßnahmen im nginx-`access.log`. Die
> mitgelieferte [`deploy/nginx.conf`](deploy/nginx.conf) nutzt ein
> `log_format`, das `$request` nicht enthält, damit der Token nicht
> permanent auf Disk geschrieben wird.

## Deploying to 3dns.eu

Komplette Schritt-für-Schritt-Anleitung mit nginx + systemd:
**[`deploy/README.md`](deploy/README.md)**.

Kurzform:

- `deploy/epf-cockpit-web.service` — systemd-Unit, läuft als `epfcockpit`-User,
  bindet auf `127.0.0.1:8787`, gehärtet mit ProtectSystem/SystemCallFilter/MemoryDenyWriteExecute
- `deploy/nginx.conf` — TLS-Vhost, WebSocket-Upgrade-Headers, Log-Format ohne Token, Hooks für IP-Allowlist + Basic-Auth + Rate-Limit

## Using the OBS widget

In OBS Studio, add a **Browser Source** with:

- URL: `http://localhost:8787/obs` (or your public URL)
- Width / Height: `800` × `160` (or whatever fits your overlay)
- **Custom CSS:** leave blank — the page already sets a transparent body

Query parameters:

| Param      | Values                       | Default | Effect                       |
|------------|------------------------------|---------|------------------------------|
| `theme`    | `light`, `dark`              | `dark`  | Text color (drop-shadow is on either way) |
| `accent`   | `teal`, `amber`, `magenta`   | `teal`  | Color of the speed number    |
| `compact`  | `1`                          | off     | Stack vertically             |

Example: `http://localhost:8787/obs?theme=dark&accent=amber&compact=1`

## Project layout

```
web/
  src/
    server.js     Express 5 + ws bootstrap, upgrade routing
    db.js         better-sqlite3 init (WAL), schema, prepared statements
    ingest.js     /ws/ingest — Android app -> server
    broadcast.js  /ws/live   — server -> browsers, snapshot tracking
    trips.js     trip lifecycle (start/end, current-trip pointer)
    routes.js    /api/state, /api/trips, /api/trips/:id
  public/
    dashboard.html / obs.html / history.html
    css/tailwind.css   Tailwind v4 source (@import + @theme)
    css/obs.css        Transparent-background extras for OBS overlay
    js/dashboard.js, obs.js, history.js
    vendor/uplot/      Vendored uPlot dist (no CDN at runtime)
    dist/app.css       Built Tailwind output (created by `npm run build`)
  scripts/
    copy-vendor.js     Copies uPlot dist from node_modules into public/vendor
  data/                SQLite db lives here at runtime
```
