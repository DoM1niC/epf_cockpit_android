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

### App-Verbindung stirbt nach 5-10 Minuten Standby

Akku-Optimierung ist noch aktiv. Doze friert auch Foreground-Services
ein bei aggressiven Vendor-ROMs (Xiaomi, Huawei, OnePlus). Fix:
- Startup-Dialog erneut triggern: App-Daten löschen oder
  `adb shell pm clear eu.dns3d.epfcockpit.debug`
- Manuell: System-Settings → Apps → EPF Cockpit → Akku → "Uneingeschränkt"
- Auf besonders aggressiven ROMs zusätzlich: "Im Hintergrund starten
  erlauben" / "Auto-Start aktivieren"

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

Was die App dagegen tut (`BleRepository`):
- **Reconnect pausiert im Hintergrund.** Sobald die App nicht im Vordergrund
  ist (`ProcessLifecycle` via `ForegroundController` → `onAppForegroundChanged`),
  startet kein neuer Reconnect-Versuch und ein laufender wird abgebrochen. Eine
  bereits stehende Verbindung bleibt erhalten (Ride-Telemetrie bei Screen-off).
- **Reconnect ist gekappt** (`MAX_RECONNECT_ATTEMPTS = 5`, ~3 min mit Backoff).
  Danach gibt die App auf und gibt den Funk frei statt ewig zu greifen.

Saubere Übergabe an eine andere App:
- Im Dashboard auf das **Bluetooth-Symbol (Trennen)** tippen — `disconnect()`
  setzt `userDisconnected`, stoppt Heartbeat + Foreground-Service und gibt den
  GATT-Link frei. Danach advertised der Roller sofort wieder.
- Oder die App aus den Recents **wegswipen** (`onTaskRemoved` → `exitProcess`).
- Optional **Auto-Verbinden** in den Einstellungen ausschalten, wenn man oft
  zwischen den Apps wechselt.

Logcat-Filter: `adb logcat -s BleRepository:V ForegroundController:V`
(Marker: `App im Hintergrund — Reconnect pausiert` /
`auto-reconnect: Cap … erreicht — pausiert, Funk freigegeben`).
