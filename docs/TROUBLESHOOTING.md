# Troubleshooting

Bekannte Probleme + Lösungen + Tipps zum Debuggen.

## Build

### `kspReleaseKotlin` failed mit `IllegalStateException: unexpected jvm signature V`

Room 2.6.1 vs KSP2-Inkompatibilität bei `suspend fun … : Unit` Methoden.
Workaround: `ksp.useKSP2=false` in `gradle.properties`. Sobald Room 2.7
stable ist, kann der Flag raus.

### `Unable to resolve v4: unsupported object type` für `gradle/actions/setup-gradle@v4`

Gitea's `act`-Runner schluckt die Monorepo-Tag-Struktur von
`gradle/actions` nicht. Fix: Den Step weglassen, stattdessen direkt
`./gradlew assembleRelease` aufrufen (siehe `.gitea/workflows/nightly.yml`).

## Runtime

### „Suche läuft aber findet nix" trotz BT an + Permissions OK

`ScanController: Skipping ScanClient ... for location deny list` im Logcat.
Android verwirft BLE-Scans ohne `ScanFilter` bei `BLUETOOTH_SCAN +
neverForLocation`. Fix: Mindestens einen Wildcard-`ScanFilter`
(`ScanFilter.Builder().build()`) setzen — die App tut das schon.

### Pulse Plus erscheint nicht im Filter-Modus

Der Pulse Plus advertised mit Namen wie `HW_EF043840` oder `ePF-PULSE`,
je nach Firmware-State. Die App matcht auf folgende Prefixes (siehe
`BleConstants.ADVERTISED_NAME_PREFIXES`):

```
ePF, EPF, ePower, ZYD, HW_
```

Falls dein Scooter einen anderen Namen hat: Filter ausschalten (Icon
oben rechts) und manuell suchen. Anschließend kannst du den Filter im
Code erweitern.

### Connection bricht nach ~30 s mit `GATT CONN TIMEOUT` oder `TERMINATE PEER USER` ab

Pulse Plus tötet die Verbindung wenn er nicht regelmäßig gepollt wird.
Die App sendet alle 600 ms `A5 02 FD 5A` als Heartbeat. Wenn der
Heartbeat-Job kaputtgeht, hat `BleRepository.startHeartbeat()` einen Bug.

Logcat-Filter: `adb logcat -s ScooterManager:V BleRepository:V`

### Keine Telemetrie nach erfolgreichem PIN-Login

Pulse Plus startet die Telemetry erst nach dem Wake-Sequence:

1. `AT+DEVICE?` + `AT+TLVOICEOFF?` schicken
2. 5× `A5 FF 00 00 00 00 00 5A` (Init-Burst)
3. Heartbeat starten

Wenn das nicht passiert: `BleRepository`'s `AtResponse.PinOk`-Handler
checken.

### Switches springen visuell zurück nach Tap

Wenn sowohl der `Card`-Container als auch der `Switch` einen
`onClick`/`onCheckedChange` haben, wird `onToggle()` zweimal gefeuert.
Lösung: `Switch.onCheckedChange = null` setzen — Toggle nur via Card-Click.

### Auto-Reconnect klappt nicht zuverlässig

Symptome:
- App-Restart → langer Spinner → schließlich Failure
- „Suchen" funktioniert nicht, App-Killen hilft

Mögliche Ursachen:
- **Privacy-MAC rotiert**: `ble.connect(savedMac)` failed. Phase 2 (Scan
  by Name) fängt das ab, aber dauert bis 15 s
- **`useAutoConnect(true)`**: passiver Scan-Modus, mehrere Sekunden Latenz.
  Lösung: `useAutoConnect(false)` für initial connect
- **`manager.close()` aufgerufen**: Nordic-Manager-Instanz wird nicht-
  reusable. Lösung: nur `disconnect()`, nie `close()` während des
  App-Lebens
- **CancellationException als Fehler angezeigt**: Coroutine cancel ist
  normal, sollte nicht als Failed-State erscheinen — explizit rethrowen

### „Standalone Coroutine canceled" als Fehlermeldung

Anti-Pattern: `runCatching { … }.onFailure { e -> showError(e.message) }`
fängt auch `CancellationException` ab. Fix: `try/catch` mit explizitem
Re-throw von `CancellationException`.

### Telemetrie-Werte komplett 0

CRC-Check verwirft den Frame. Pulse Plus nutzt CRC16-MODBUS
**little-endian**, ZydDash dokumentiert **big-endian**. Unser
`Crc16Modbus.verify` akzeptiert beide. Wenn nichts mehr durchkommt: temporär
permissiv decoden (siehe Telemetry.kt — wir loggen CRC-Mismatch statt zu
verwerfen).

### Scooter geht versehentlich in Parking nach App-Toggle

`AF 00 0A 02 …` (ZydDash „Light off") triggert beim Pulse Plus den
Park-Lock. **Niemals den ZydDash-OP-Byte `0x02` als Steuer-Frame senden!**
Wir nutzen ihn nur als Read-Poll-Variante (vom ePF-1 dokumentiert), nicht
als Action.

## Sniffing

### HCI-Snoop-Log ist leer

- BT war nicht aus/an zwischen Setting-Toggle und Aktionen
- Oder die Aktionen waren vor dem letzten BT-Restart
- Oder das Phone hat die Snoop-Datei rotiert (sehr selten)

### Bug-Report dauert ewig

Normal — 1-2 Minuten. Tipp: parallel zum Pull weiter in der App klicken,
diese Aktionen sind auch im resultierenden Snoop-Log

### Frame-Pattern nicht reproduzierbar

Eine Funktion pro Sniff klicken, mindestens 3-5 s zwischen Klicks lassen,
sonst werden Frames im Log gebündelt und nicht eindeutig zuordenbar.

## Pulse-Plus-spezifisch

### Modus-Toggle ändert Parking-Switch in der App

Beobachtet während des RE: Mode-Frames (A2 = Stand, 82 = Kick) ändern auch
Status-Byte-Bit-5 in der Telemetry — obwohl der physische Park-Lock am
Roller dabei nicht reagiert. Daher seedet die App den Parking-Switch nur
einmal aus Telemetry und reagiert dann nur auf User-Klicks.

### „Kick Start" hebt Parking auf

By design — `AF 00 0A 82 …` ist byte-identisch zwischen Parking-OFF und
Kick-Start in der offiziellen App. Wir replizieren das Verhalten.

### Ride-Mode nach App-Restart wieder „Stand Stop"

War so vor der DataStore-Persistierung. Jetzt fixed: `rideMode` wird
gespeichert und beim DashboardVM-init geladen.

## Live-Streaming (Node-Dashboard)

Vollständige Doku in [STREAMING.md](STREAMING.md) — hier nur die häufigsten
Fehlerbilder als Pointer.

### Status bleibt auf „Fehlgeschlagen – retry läuft"

```
adb logcat -s TelemetryStreamer:V OkHttp:V
```

- `Failed to connect to /…` → Server nicht erreichbar, Port-/Firewall-Issue
- `HTTP/1.1 401` → Token falsch, oder Server erwartet keines (App-Feld leer
  lassen)
- `SSL handshake failed` → Cert-Problem bei `wss://` (self-signed,
  abgelaufen, Hostname mismatch)

### Server logged Reconnect-Loops im Sekundentakt

Das ist der App-Streamer mit Exponential-Backoff nach 401. Token in der
App korrigieren — danach wird's ruhig.

### Browser zeigt 401 obwohl Bookmark `?token=…` hat

`EpfAuth.token` in der DevTools-Console prüfen. `sessionStorage`-Eintrag
`epf_token` schauen. Wenn beide leer: Tippfehler im URL-Parameter.

### OBS-Browser-Source schwarz

Properties → *Refresh cache of current page*. OBS cached agressiv, vor
allem nach Token-Wechsel. Wenn Token im URL fehlt, zeigt OBS die
401-HTML-Seite (dark, fällt im Stream nicht auf) — vorher in einem
normalen Tab gegentesten.

## Foreground-Service + Background-Lifecycle

### Notification erscheint nicht obwohl verbunden

- Notification-Permission steht auf "Verweigert" → in den App-Settings
  manuell erlauben, oder App killen + neu starten → Startup-Dialog kommt
  wieder. Logcat: `NotificationManager: notify: permission denied`.
- `IMPORTANCE_LOW`-Channel ist standardmäßig kollabiert auf manchen
  ROMs (OxygenOS, MIUI) — Pull-down ausklappen, Notification ist da.

### App-Verbindung stirbt nach 5-10 Minuten Standby (geparkt, Handy gesperrt)

Früher musste man danach die App öffnen, damit sie wieder verbindet. Ursache
war nicht Doze (auch mit Akku-Whitelist trat es auf), sondern die App selbst:
der Reconnect war im Hintergrund pauschal pausiert und der Foreground-Service
wurde beim Abriss gestoppt → Android fror den Prozess ein.

Heute startet nach einem Abriss eine **Reconnect-Episode** (siehe unten
„Andere App findet den Roller nicht mehr“): bei gesperrtem Handy wartet ein
Bluetooth-Sentinel bis zu 2 h auf den Roller und verbindet selbst neu, der
Foreground-Service bleibt dabei an („Verbindet automatisch, sobald der Roller
erreichbar ist“).

Warum der Link überhaupt abreißt, zeigt der Disconnect-Reason:
`adb logcat -s ScooterManager:V BleRepository:V` → `onDeviceDisconnected reason=N`

| reason | HCI-Status | Bedeutung |
|---|---|---|
| 2 | 0x13 | Roller hat getrennt (z.B. ~30 s ohne Heartbeat) |
| 10 | 0x08 / 0x00 | Supervision-Timeout — Reichweite, Funk |
| 1 | 0x16 | lokaler Host (Android-Stack) hat getrennt |
| −1 | sonst | z.B. 0x15 Roller ausgeschaltet |

(Mit `useAutoConnect(false)` reicht Nordic den gemappten Status durch,
`REASON_LINK_LOSS` (3) kommt nie. Den rohen Code zeigt Nordics Logzeile
`Connection state changed with status:`.)

Heartbeat im CPU-Suspend: `delay(600)` steht still, solange das SoC schläft.
Misst der Heartbeat eine Lücke > 2 s, loggt er `heartbeat starvation: … ms`
und hält ab dann bei Display aus eine Partial-WakeLock (`EpfCockpit:heartbeat`).

Auf aggressiven Vendor-ROMs zusätzlich Akku → „Uneingeschränkt“ bzw.
„Im Hintergrund starten erlauben“ setzen.

### Trip-Recording stoppt zu früh

Nach dem Refactor (TripCoordinator @Singleton) sollte das nicht mehr
passieren. Falls doch:
- `adb logcat -s TripCoordinator:V BleRepository:V` → schauen ob
  `Disconnected`-State zwischendurch kommt
- Privacy-MAC-Rotation kann eine kurze Disconnect/Reconnect-Spitze
  triggern; das finalisiert den Trip vorzeitig. Workaround: in der App
  Auto-Reconnect ausschalten wenn die Fahrt am Stück recorded werden soll.

### App ist nach App-Swipe weg, BLE bleibt aber an

Sollte nicht passieren — `EpfConnectionService.onTaskRemoved` ruft
`exitProcess(0)`. Falls doch:
- Logcat: `EpfConnectionService: task removed — full shutdown` sollte
  zu sehen sein
- Wenn nicht: Service wurde nicht von unserem Activity-Task gestartet
  (z.B. Hilt-Race beim allerersten Start) — App neu starten reicht

### Andere App (Hersteller-App) findet den Roller nicht mehr

Symptom: Nach Nutzung des EPF Cockpit findet die offizielle App (oder eine
andere Hobbywing-App) den Roller nicht mehr — sie listet zwar alle möglichen
BLE-Geräte aus der Nachbarschaft, aber nicht den Scooter.

Ursache: **Single-Link-BLE.** Der Hobbywing-Controller akzeptiert nur EINEN
Central gleichzeitig und advertised nicht mehr, solange er verbunden ist (oder
ein Central aktiv connecten will). Es ist **kein** Android-Bonding-Leak — die
App ruft nirgends `createBond` auf, hinterlässt also keinen Pairing-Eintrag.
Zwei Quellen halten den Link:

1. Eine **stehende Verbindung** (App im Vordergrund verbunden, oder im
   Hintergrund mit laufendem Foreground-Service).
2. Der **Auto-Reconnect**, der den Roller nach einem Abriss wieder greift.

Was die App dagegen tut (`BleRepository` + `ReconnectPolicy`): Nach einem
unerwarteten Abriss läuft eine **Reconnect-Episode** (max. 2 h), die bei jedem
Durchlauf neu entscheidet:

| Situation | Modus | Verhalten |
|---|---|---|
| App im Vordergrund / Widget-, Tile-, Notification-Tap | `ACTIVE` | 2-Phasen-Loop, Backoff 10/20/30/60 s, Cap 5 Versuche — danach funkstill („Auto-Suche pausiert“), bis das Handy gesperrt, die App neu geöffnet oder „Verbinden“ getippt wird |
| Handy gesperrt / Display aus, oder gerade gefahren | `WAIT` | Sentinel (`connectGatt(autoConnect=true)`) wartet im BT-Controller auf den Roller |
| entsperrt + andere App vorne | `PAUSE` | Funk bleibt frei (Handover), bis gesperrt oder App geöffnet |
| Schalter „Bei gesperrtem Handy neu verbinden“ aus, nie authentifiziert, Flattern | `DEFER` | Episode endet, Reconnect erst beim App-Start (altes Verhalten) |
| Bluetooth aus (Schalter, Flugmodus, Stack-Neustart) | `BLUETOOTH_OFF` | funkstill bis zu 10 min auf Bluetooth warten, danach wie `DEFER` |

- Hat der Roller schon einen fremden GATT-Link (Hersteller-App), armiert die
  App den Sentinel nicht. Meldet der Sentinel einen Link, löst die App ihn
  zuerst und prüft nach 3 s, ob eine andere App den Link hält — dann wird er
  nicht übernommen.
- Laufende Versuche brechen sofort ab, wenn die Policy sie nicht mehr erlaubt
  (z.B. Wechsel in die Hersteller-App mitten im Connect).
- Eine stehende Verbindung bleibt im Hintergrund erhalten (Ride-Telemetrie).
- Endet eine Episode im Hintergrund erfolglos, erscheint ein wegwischbarer
  Hinweis mit „Verbinden“ (auch vom Sperrbildschirm).

Saubere Übergabe an eine andere App:
- In der **Notification auf „Trennen“** tippen, oder im Dashboard auf das
  **Bluetooth-Symbol** — `disconnect()` setzt `userDisconnected`, beendet eine
  Episode, stoppt Heartbeat + Foreground-Service und gibt den GATT-Link frei.
  Danach advertised der Roller sofort wieder.
- Oder die App aus den Recents **wegswipen** (`onTaskRemoved` → `exitProcess`).
- Soll die Hersteller-App grundsätzlich Vorrang haben: in den Einstellungen
  **„Bei gesperrtem Handy neu verbinden“** ausschalten (oder Auto-Verbinden).

Logcat-Filter: `adb logcat -s BleRepository:V ScooterPresence:V DeviceInteractivity:V ForegroundController:V`
(Marker: `link lost (reason=…)`, `reconnect mode → WAIT|PAUSE|ACTIVE|DEFER`,
`presence armed`, `presence status=0 state=2`, `Roller wieder erreichbar`,
`phase 2 übersprungen`, `auto-reconnect: Cap … erreicht`).
