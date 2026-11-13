# Live-Streaming an das Web-Dashboard

Die App kann Live-Telemetrie an einen kleinen Node-Server pushen
(`web/` im selben Repo). Im Browser läuft daraus:

- ein **Live-Dashboard** mit Gauges + uPlot-Charts (`/`),
- ein **OBS-Widget** mit transparentem Hintergrund (`/obs?theme=dark`),
- eine **Trip-Historie** (`/history`).

## Setup

1. Server starten (siehe [`web/README.md`](../web/README.md) für Dev,
   [`web/deploy/README.md`](../web/deploy/README.md) für Produktion):
   ```bash
   cd web && npm install && npm run build && npm start
   ```
2. In der App: *Einstellungen → Live-Streaming an Dashboard*
   - **URL**: z.B. `ws://192.168.1.50:8787/ws/ingest` (LAN) oder
     `wss://cockpit.3dns.eu/ws/ingest` (produktiv, hinter nginx)
   - **Token** (optional, leer wenn Server ohne `INGEST_TOKEN` läuft)
   - **Switch** auf an

Statuszeile unter dem Switch:
- *Verbinde…* — Socket wird gerade aufgebaut
- *Verbunden – sendet Live-Daten* — alles ok
- *Fehlgeschlagen – retry läuft* — Exponential-Backoff bis 30 s

## Architektur

```
DashboardVM / BleRepository      TelemetryStreamer          Node-Server
       │ telemetry.collect             │   OkHttp WS              │
       └──────────────────────────────▶│ ─────JSON ──────────────▶│ ws/ingest  (gated: INGEST_TOKEN)
                                       │                          │
                                       │                          ├─ SQLite (WAL)
                                       │                          │
       UI in Browser  ◀────────────────│ ◀──── broadcast ─────────┤ ws/live   (gated: VIEW_TOKEN)
                                                                  │
                                                                  ├─ /api/state, /api/trips/*  (gated: VIEW_TOKEN)
                                                                  ├─ /, /obs, /history          (gated: VIEW_TOKEN)
                                                                  └─ /healthz                   (immer offen)
```

`TelemetryStreamer` ist ein `@Singleton`, der direkt aus
`BleRepository.telemetry` und `.connection` liest. Er reagiert auf
Änderungen in `SettingsStore.flow` (`streamingUrl`, `streamingEnabled`,
`streamingToken`) und (re)connected den OkHttp-WebSocket entsprechend.

Telemetrie wird **auf ~5 Hz** gethrottled, damit das Netzwerk-Volumen
auch über Mobilfunk niedrig bleibt (typisch <2 kB/s).

## Authentifizierung

Zwei unabhängige Shared-Tokens, beide *optional*. Sind sie leer, läuft
der Server offen (Dev-Modus). Sind sie gesetzt, wird strikt geprüft —
Vergleich konstant-zeitig via `crypto.timingSafeEqual`.

| Env-Var         | Schützt                                              | Übergabe                                                                                       |
|-----------------|------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `INGEST_TOKEN`  | `/ws/ingest`                                         | `Authorization: Bearer …` (die App nutzt das) **oder** `?token=…`                              |
| `VIEW_TOKEN`    | `/`, `/obs`, `/history`, `/api/*`, `/ws/live`        | `?token=…` (Browser stasht's in `sessionStorage`, in-Tab-Navigation bleibt authentifiziert)    |

`/healthz` ist absichtlich nicht gated — sonst funktionieren
nginx-Upstream-Checks und Uptime-Monitore nur mit Token-Wissen.

### Wie die App das Token überträgt

Der `TelemetryStreamer` setzt den Token als **Authorization-Bearer-Header**
am WS-Upgrade-Request. Das hat zwei Vorteile gegenüber `?token=…` im URL:

- Nginx-Default-`access.log` enthält den `$request`-Pfad → Token landet in
  Logs / Backups / Log-Aggregation. Header sind dort nicht drin.
- OkHttp logged bei Fehlern manchmal die URL als Teil der Exception → mit
  Header bleibt der Token weg.

Die `deploy/nginx.conf` definiert zusätzlich ein eigenes `log_format`, das
auch das URL-Pfad-`$request` durch ein konservativeres `$method $uri`
ersetzt — damit ist auch `?token=…` aus den Browser-Bookmarks safe.

### Token-Rotation

Beide Tokens lassen sich jederzeit drehen:

1. Server: neuen Wert in `/etc/epf-cockpit-web.env` setzen,
   `systemctl restart epf-cockpit-web`.
2. App: *Einstellungen → Token* aktualisieren — der `TelemetryStreamer`
   hört auf `distinctUntilChanged()` über `Triple(url, enabled, token)`
   und rebuildet den Socket bei jedem Wechsel.
3. Browser-Bookmark: neue `?token=…` setzen. `sessionStorage` lebt nur
   im jeweiligen Tab und wird beim Schließen entsorgt.

### Was, wenn beide Tokens leer bleiben (Dev / privates LAN)

Der Server logged beim Start:

```
[epf-cockpit-web] listening on http://127.0.0.1:8787
  ingest auth: OFF (open)
  view auth:   OFF (open)
```

Auf dem LAN reicht das, wenn dein Router den Port nicht nach außen
forwarded. Für ein öffentliches Deployment auf 3dns.eu → siehe
[`web/deploy/README.md`](../web/deploy/README.md).

## Wire-Format

Strikt definiert in [`StreamMessage.kt`](../app/src/main/kotlin/eu/dns3d/epfcockpit/streaming/StreamMessage.kt)
und [`web/src/ingest.js`](../web/src/ingest.js). Eine Änderung am Format
**muss in beiden Repos in Lockstep** passieren — der Discriminator-Tag
`type` macht alte Clients sonst silent invalid.

```json
{"type":"hello","scooterName":"HW_EF043840","scooterAddress":"AA:BB:..","authenticated":true,"app":"epf_cockpit_android","appVersion":"0.1.0"}
{"type":"telemetry","ts":1700000000000,"speedKmh":12.3,"batteryPercent":85,"voltageV":36.2,"currentA":4.1,"powerW":148,"controllerTempC":32,"tripKm":1.23,"totalKm":412.5,"parking":false,"rideModeStand":true,"lightOn":true,"motorTempC":29}
{"type":"state","connected":true,"authenticated":true,"scooterName":"HW_EF043840"}
```

- `parking` = Park-Zustand wie in der App (Intent-Latch aus dem `ParkController`),
  **nicht** das rohe Telemetrie-Bit 5 — das ist auch im entsperrten
  Stand-Stop-Modus gesetzt (daher zeigte das Web früher „Parking: an").
- `rideModeStand` = gewählter Modus (`true` Stand-Stop, `false` Kick-Start);
  ältere App-Versionen schicken `null` → Web zeigt „–".
- `lightOn` (Frontlicht, aus Telemetrie-Bit `headLightSw`) und `motorTempC` sind
  optional und fehlen bei älteren App-Versionen.

## Cleartext-Traffic

`AndroidManifest.xml` setzt `android:usesCleartextTraffic="true"`, damit
`ws://`-URLs (LAN-Setup ohne TLS) funktionieren. Für die öffentliche
Variante hinter nginx (`wss://cockpit.3dns.eu/...`) ist das irrelevant.

## Troubleshooting

### App-Status bleibt auf *Fehlgeschlagen – retry läuft*

Erste Anlaufstelle: `adb logcat -s TelemetryStreamer:V OkHttp:V`.

- `openSocket failed: Failed to connect to /a.b.c.d:p` →
  Server nicht erreichbar (falsche IP, Router-Firewall, Port belegt)
- `onFailure: HTTP/1.1 401` → Token falsch oder Server erwartet keines.
  Auf der Server-Seite: `journalctl -u epf-cockpit-web -f` zeigt den
  Verbindungsversuch (Peer-IP wird geloggt, Token nicht).
- `onFailure: SSL handshake failed` → bei `wss://` Cert-Probleme
  (selbst-signiert, abgelaufen, Wildcard-Mismatch)

### Server logged „[ingest] socket error from … : EBADF / ECONNRESET" im Sekundentakt

Das ist meistens der **Backoff-Reconnect** des App-Streamers — er versucht
nach gescheitertem 401 weiter (exponentiell bis 30 s). Token in der App
korrigieren, dann wird's ruhig. Falls nicht: schau in der App unter
Settings ob `streamingEnabled` wirklich auf an steht.

### Browser zeigt 401-Seite, Bookmark hatte aber Token

Der Token in `sessionStorage` wird beim Tab-Schließen entsorgt — das ist
gewollt. Wenn der Bookmark `?token=…` enthält, sollte der eine Lookup
ihn wieder einsetzen. Wenn nicht: F12 Console öffnen, `EpfAuth.token`
auswerten — wenn `null`, dann hat das URL-Parsing nichts gefunden
(Tippfehler im `?token=`-Param?).

### OBS-Browser-Source bleibt schwarz

Browser-Source cached aggressiv. Right-click → *Properties* → *Refresh
cache of current page*. Wenn das Token im URL fehlt, zeigt OBS die
401-HTML-Seite — die ist allerdings dark, fällt im Stream nicht auf.
Daher: vorher in einem normalen Tab testen.

### Daten kommen, aber `controllerTempC` zeigt `--`

Das Pulse-Plus-Telemetrie-Format trägt die Controller-Temp im Status-Bit-
Layout — siehe [`PROTOCOL.md`](PROTOCOL.md). Wenn die Live-Werte sonst
plausibel aussehen (Speed/Battery/Voltage), aber Temp fehlt: vermutlich
schickst du eine sehr alte Firmware-Variante. Nicht streaming-spezifisch.

## Sicherheit (TL;DR)

Was schützt **nicht**:
- HTTPS allein — TLS verschlüsselt nur Transport, nicht Zugriff
- Token allein in URL — landet ohne Sondermaßnahmen in nginx-Logs (mit
  der mitgelieferten `nginx.conf` aber nicht)
- Plaintext-SQLite — wer Filesystem-Zugriff hat, kann Telemetrie lesen

Was schützt:
- `INGEST_TOKEN` strikt enforced → niemand kann Fake-Daten reinpushen
- `VIEW_TOKEN` strikt enforced → keine anonymen Live-Beobachter
- nginx-Reverse-Proxy → Node bindet auf `127.0.0.1`, ist nicht direkt
  öffentlich
- Optional: IP-Allowlist auf `/ws/ingest`, Basic-Auth zusätzlich zum
  Token, Rate-Limit gegen Token-Bruteforce — alles in
  [`web/deploy/nginx.conf`](../web/deploy/nginx.conf) als Hooks vorbereitet
- Systemd-Hardening (ProtectSystem, SystemCallFilter, MemoryDenyWriteExecute,
  RestrictAddressFamilies) → wenn der Node-Prozess kompromittiert wird,
  bleibt der Schaden auf `/var/lib/epf-cockpit-web` begrenzt
