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
