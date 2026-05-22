# EPF Cockpit

Alternative Open-Source-App für ePowerFun-Scooter — verifiziert auf dem **EPF
Pulse Plus** (Hobbywing-Controller, `L10_C1EPULSE`). Verbindet sich per Bluetooth
Low Energy mit dem Scooter, authentifiziert sich mit der PIN und zeigt
Live-Telemetrie sowie sniff-verifizierte Steuer-Funktionen in einem
Material-3-Expressive-Dashboard mit dynamischem Farbschema.

## Features

### Dashboard
- Großer Tacho-Ring mit animiertem Sweep-Gradient
- Akku-Ring mit Farbwechsel (rot → orange → grün)
- Live-Leistung (Watt = U·I), Rekuperations-Anzeige
- Spannung, Strom, Controller-Temperatur, Trip- und Gesamt-Kilometer
- Bildschirm bleibt im Cockpit an (kein versehentliches Dimmen während der Fahrt)

### Steuerung (sniff-verifiziert für Pulse Plus)
- **Parking** ein/aus — Material-3-Switch, sniff-verifizierte Frames
- **Modus Stand Stop ↔ Kick Start** — Segmented Button, persistent über
  App-Neustart
- **Blinkerton** ein/aus — Sprach-/Ton-Hinweise, Status wird live vom Scooter
  gelesen

### Verbindung
- BLE-Scan mit Filter auf `HW_*` / `ePF*` / `EPF*` / `ZYD*`, sortiert nach RSSI
- 6-stellige PIN-Authentifizierung via `AT+PWD[…]`, optional gespeichert
- **Zweiphasiger Auto-Reconnect**: erst direkter Connect auf gespeicherte MAC
  (≤6 s), dann Scan-by-Name (≤15 s, robust gegen Privacy-MAC-Rotation),
  danach manueller Scan-View
- Nach manuellem „Trennen" kein Auto-Reconnect mehr in der gleichen
  App-Session (kollidiert sonst mit dem gerade freigewordenen BLE-Radio)
- Friendly Error-Wording für GATT-Status-Codes
- Connection bleibt stabil dank des sniff-verifizierten `A5 02 FD 5A`
  Heartbeat alle 600 ms

### Persistenz
- Letzter Scooter wird gemerkt (DataStore), Auto-Reconnect beim App-Start
- Fahrten-Logging in Room: Strecke, Ø/Max-Geschwindigkeit, Energie, Akku-Verbrauch
- Letzter Ride-Modus wird gespeichert und beim Start wiederhergestellt

### Design
- **Material 3 Expressive** mit dem neuen Type-Scale und größeren Corner-Radii
- **Dynamic Color** (Monet) ab Android 12 — App-Farben folgen dem Wallpaper
- Edge-to-Edge, animierte Übergänge, M3-Wavy-Indicators

## Download

📦 **Aktuelle Nightly:** [epf-cockpit-nightly-0.1.0.apk](https://git.3dns.eu/dom1nic/epf_cockpit_android/releases/download/nightly/epf-cockpit-nightly-0.1.0.apk)
🗂 **Alle Releases:** [git.3dns.eu/dom1nic/epf_cockpit_android/releases](https://git.3dns.eu/dom1nic/epf_cockpit_android/releases)

Nightlies werden automatisch nach jedem Push auf `main` gebaut, sind mit dem
Release-Keystore signiert und ersetzen eine bestehende Installation in-place.

## Technische Dokumentation

Das BLE-Protokoll für den Pulse Plus wurde reverse-engineered, weil die
ZydDash-Doku (ePF-1) nur teilweise passt. Details in [docs/](docs/):

- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — Pulse-Plus-BLE-Protokoll im Detail:
  Services/Characteristics, Frame-Formate (AT, A5-Sync, AF-Control,
  AF-Telemetry), Heartbeat, Init-Burst, Status-Byte-Mapping, gefundene CRC-Variante
- [`docs/REVERSE_ENGINEERING.md`](docs/REVERSE_ENGINEERING.md) — Wie ich das
  rausgefunden habe: HCI-Snoop-Setup, Bug-Report-Pipeline, die vier Sniff-Runs,
  was jedes Mal geklärt wurde
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — App-Architektur:
  Module, ViewModels, BLE-Layer, State-Flow, Auto-Reconnect-Logik
- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) — Bekannte Probleme,
  Workarounds, Tipps zum Sniffen weiterer Funktionen

## Architektur (Kurzform)

```
ui/             — Compose-Screens (scan, auth, dashboard, settings, history)
ble/            — Protokoll-Layer + Nordic-BLE-Manager
   BleConstants     UUIDs, PIN-Default, advertising-Name-Prefixes
   Crc16Modbus      MODBUS-CRC mit BE/LE-Auto-Detect
   Telemetry        TelemetryDecoder.decode(frame) → Telemetry?
   ControlFrames    Frame-Konstanten + AT-Kommando-Builder
   ScooterManager   Nordic BleManager-Subklasse mit fire-and-forget Writes
   BleRepository   Singleton, Hilt-injizierbar, Connection-State-Flow,
                   Heartbeat-Loop, friendly error mapping
data/           — DataStore (Settings), Room (Trip-Historie)
di/             — Hilt-AppModule (Room-DB-Provider)
```

- **Compose** + **Material 3 Expressive** (`androidx.compose.material3:1.4.0-alpha`)
- **MVVM** mit Hilt-injizierten ViewModels, `StateFlow` als Observable State
- **Nordic Android BLE Library** kapselt GATT-Queue, Retries, Bonding-Fallen
- **Room** für Fahrten-Historie, **DataStore Preferences** für App-Settings

## Build

Voraussetzungen:
- JDK 17 (lokal) / JDK 21 (CI, wegen AGP 8.x core-for-system-modules)
- Android SDK 35 (`compileSdk = 35`, `minSdk = 31`)
- Gradle 8.11 via Wrapper

```bash
./gradlew assembleDebug                 # Debug-APK
./release.sh release                    # Release-APK (macOS/Linux/WSL)
release.bat release                     # Release-APK (Windows)
```

Die Release-Skripte legen die fertige APK unter
`release/epf-cockpit-<version>-release.apk` ab.

### Signing (optional)

Lege `keystore.properties` (NICHT committen) an, basierend auf
[keystore.properties.example](keystore.properties.example). Ohne wird der
Release-Build mit dem Debug-Key signiert.

## Permissions

| Permission                            | Zweck                                  |
|---------------------------------------|----------------------------------------|
| `BLUETOOTH_SCAN` (`neverForLocation`) | BLE-Scan ohne Location-Pflicht         |
| `BLUETOOTH_CONNECT`                   | GATT-Connect, Notify-Subscriptions     |
| `POST_NOTIFICATIONS` (Android 13+)    | (Reserviert für künftige Foreground-Service-Notifications) |

## Was bewusst nicht geht

- **OTA-Firmware-Update**: Zu riskant ohne offizielle Signaturen.
- **Tuning / Speed-Cap-Override**: Geht über die App nicht. Wäre Eingriff
  in den Controller und in DE rechtlich problematisch.
- **Cloud-Sync / Account**: Bewusst weggelassen, offline-only.

## Quellen

- ZydDash BLE-Telemetry-Doku (ePF-1, Zydtech): https://github.com/Ennar1991/ZydDash/blob/main/BLE_Telemetry.md
- Original Reverse-Engineering-Blog: https://www.irmo.de/2023/11/08/e-scooter-bluetooth-hacking/
- escooter-treff.de Forenthread: https://www.escooter-treff.de/threads/zydtech-controller-bluetooth-protokoll-reverse-engineering.12207/
- Offizielle ePowerFun-App (analysiert via Android HCI Snoop): `com.zydtech.epowerfun`

## Autor

DoM!niC — [git.3dns.eu/dom1nic/epf_cockpit_android](https://git.3dns.eu/dom1nic/epf_cockpit_android)
