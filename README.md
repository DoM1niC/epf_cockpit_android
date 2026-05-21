# EPF Cockpit

Alternative Open-Source-App für ePowerFun-Scooter — insbesondere den **EPF Pulse**
und **EPF Pulse Plus**. Verbindet sich per Bluetooth Low Energy mit dem Scooter,
authentifiziert sich mit der PIN und zeigt Live-Telemetrie sowie Steuer-Funktionen
in einem Material-3-Expressive-Dashboard mit dynamischem Farbschema.

> ⚠️ **Pulse-Plus-Status**: Das BLE-Protokoll ist nur für die **Standard-ePF-Modelle
> mit Zydtech-Controller** öffentlich dokumentiert (siehe [Quellen](#quellen)). Der
> Pulse Plus nutzt einen **Hobbywing-Controller** — UUIDs und Frame-Layout dürften
> identisch sein, sind aber **nicht öffentlich verifiziert**. Wenn der Decoder bei
> dir Müll liefert, einmal mit nRF Connect mitsniffen und [`Telemetry.kt`](app/src/main/kotlin/eu/dns3d/epfcockpit/ble/Telemetry.kt)
> anpassen — die Stelle ist gekapselt und gut kommentiert.

## Features

### Dashboard
- Großer Tacho-Ring mit animiertem Sweep-Gradient
- Akku-Ring mit Farbwechsel (rot → orange → grün)
- Live-Leistung (Watt = U·I), Rekuperations-Anzeige
- Spannung, Strom, Controller-Temperatur, Trip- und Gesamt-Kilometer
- Status-Chips: Gang, gesperrt, Licht, Rekuperation

### Steuerung
- Gang 1/2/3 wechseln, mit konfigurierbaren Max-Geschwindigkeiten
- Licht an/aus
- Lenker-Sperre
- Trip-Reset (One-Shot)

### Auth & Sicherheit
- 6-stellige PIN, gesendet via `AT+PWD[…]` an die High-Level-Characteristic
- PIN-Änderung via `AT+PWDM[…]`
- Optional gespeicherte PIN (Auto-Unlock beim nächsten Connect)
- Werks-PIN-Shortcut für `888888`

### Persistenz
- Letzter Scooter wird gemerkt (DataStore), Auto-Reconnect beim App-Start
- Fahrten-Logging in Room: Strecke, Ø/Max-Geschwindigkeit, Energie, Akku-Verbrauch
- Fahrten-Historie mit Einzel-Löschen und „Alle löschen"

### Design
- **Material 3 Expressive** mit dem neuen Type-Scale und größeren Corner-Radii
- **Dynamic Color** (Monet) ab Android 12 — App-Farben folgen dem Wallpaper
- Edge-to-Edge, animierte Übergänge, M3-Wavy-Indicators

## Download

📦 **Aktuelle Nightly:** [epf-cockpit-nightly-0.1.0.apk](https://git.3dns.eu/dom1nic/epf_cockpit_android/releases/download/nightly/epf-cockpit-nightly-0.1.0.apk)
🗂 **Alle Releases:** [git.3dns.eu/dom1nic/epf_cockpit_android/releases](https://git.3dns.eu/dom1nic/epf_cockpit_android/releases)

Nightlies werden automatisch nach jedem Push auf `main` gebaut, sind mit dem
Release-Keystore signiert und ersetzen eine bestehende Installation in-place.
Installation aus unbekannter Quelle muss in den System-Einstellungen erlaubt
werden (Apps → Spezieller Zugriff → Apps aus unbekannten Quellen installieren).

## Architektur

```
ui/             — Compose-Screens (scan, auth, dashboard, settings, history)
ble/            — Protokoll-Layer + Nordic-BLE-Manager
   BleConstants     UUIDs, PIN-Default
   Crc16Modbus      MODBUS-CRC mit Big-Endian-Append
   Telemetry        TelemetryDecoder.decode(frame) → Telemetry?
   ControlFrames    Builder für Lock/Licht/Gang/Trip-Reset-Frame
   ScooterManager   Nordic BleManager-Subklasse, Notify-Callbacks
   BleRepository    Singleton, Hilt-injizierbar, Connection-State-Flow
data/           — DataStore (Settings), Room (Trip-Historie)
di/             — Hilt-AppModule (Room-DB-Provider)
```

- **Compose** + **Material 3 Expressive** (`androidx.compose.material3:1.4.0-alpha`)
- **MVVM** mit Hilt-injizierten ViewModels, `StateFlow` als Observable State
- **Nordic Android BLE Library** kapselt GATT-Queue, Retries und Bonding-Fallen
- **Room** für Fahrten-Historie, **DataStore Preferences** für App-Settings

## BLE-Protokoll (Zydtech HW9027)

Reverse-engineered von [ZydDash](https://github.com/Ennar1991/ZydDash); hier nur
das Allernötigste — für Details siehe [`Telemetry.kt`](app/src/main/kotlin/eu/dns3d/epfcockpit/ble/Telemetry.kt)
und [`ControlFrames.kt`](app/src/main/kotlin/eu/dns3d/epfcockpit/ble/ControlFrames.kt).

| Service          | Char (TX → Scooter)  | Char (RX ← Scooter)  | Zweck                          |
|------------------|----------------------|----------------------|--------------------------------|
| `0000f1f0-…`     | `0000f1f1-…`         | `0000f1f2-…`         | Telemetrie + Steuer-Frames     |
| `0000f2f0-…`     | `0000f2f1-…`         | `0000f2f2-…`         | AT-Kommandos (PIN, Name)       |

**Telemetrie-Frame** (Typ 2): Header `0xAF`, Längenbyte, Payload, MODBUS-CRC16
big-endian. Felder: Gang [4], Akku% [5], Speed [6-7]·0.001 km/h, Spannung [10-11]·0.1 V,
Strom [12-13]·0.01 A signed, Temp [14] °C, Trip [16-17]·0.1 km, Total [18-20]·0.1 km.

**Steuer-Frame**: Bit-Flags-Byte (Bit7 Lock, Bit3 Trip-Reset, Bit2 Light, Bit1-0 Gang),
gefolgt von Max-km/h für Gänge 1/2/3.

**PIN**: ASCII `AT+PWD[123456]` → Antwort `OK+PWD:Y` oder `OK+PWD:N`.
PIN-Änderung via `AT+PWDM[…]` — **keine Auth nötig** vor der Änderung (das ist ein
Design-Mangel des Scooter-Firmware, nicht dieser App).

## Build

Voraussetzungen:
- JDK 17 (lokal) / JDK 21 (CI, wegen AGP 8.x core-for-system-modules)
- Android SDK 35 (`compileSdk = 35`, `minSdk = 31`)
- Gradle 8.11 via Wrapper

Erster Build erzeugt den Wrapper:

```bash
gradle wrapper --gradle-version 8.11.1
```

Danach:

```bash
./gradlew assembleDebug                 # Debug-APK
./release.sh release                    # Release-APK (macOS/Linux/WSL)
release.bat release                     # Release-APK (Windows)
```

Die Release-Skripte legen die fertige APK unter `release/epf-cockpit-<version>-release.apk` ab.

### Signing (optional)

Lege `keystore.properties` (NICHT committen) an, basierend auf
[keystore.properties.example](keystore.properties.example):

```properties
storeFile=../keystores/epf-cockpit-release.jks
storePassword=…
keyAlias=epf
keyPassword=…
```

Ohne `keystore.properties` wird der Release-Build mit dem Debug-Key signiert.

## Permissions

| Permission                   | Zweck                                              |
|------------------------------|----------------------------------------------------|
| `BLUETOOTH_SCAN` (`neverForLocation`) | BLE-Scan ohne Location-Pflicht           |
| `BLUETOOTH_CONNECT`          | GATT-Connect, Notify-Subscriptions                  |
| `POST_NOTIFICATIONS` (Android 13+) | (Reserviert für künftige Foreground-Service-Notifications) |
| `INTERNET`                   | (Reserviert — aktuell ungenutzt, für künftige Cloud-Logs) |

## Was bewusst nicht geht

- **OTA-Firmware-Update**: Die offizielle App lädt Firmware-Blobs aus drei SQLite-
  DBs und überträgt sie teil-verschleiert. Das ist absichtlich nicht implementiert —
  ein verkorkster OTA-Vorgang macht den Scooter unbrauchbar, und ohne offizielle
  Signaturen besteht keine Möglichkeit, einen Roll-back-fähigen Update-Pfad
  sauber zu testen.
- **Tuning / Speed-Cap-Override**: Die Max-Speed-Felder im Steuer-Frame setzen
  die App-seitigen Grenzen, lassen sich aber durch die Controller-Firmware
  cappen. Echtes Tuning bräuchte einen Eingriff in den Controller selbst und
  hat in DE keine StVZO-konforme Variante.
- **Cloud-Sync / Fahrten-Upload**: Bewusst weggelassen, weil die App offline und
  ohne Account funktionieren soll.

## Quellen

- ZydDash BLE-Telemetry-Doku: https://github.com/Ennar1991/ZydDash/blob/main/BLE_Telemetry.md
- Original Reverse-Engineering-Blog: https://www.irmo.de/2023/11/08/e-scooter-bluetooth-hacking/
- escooter-treff.de Forenthread (Zydtech-Protokoll): https://www.escooter-treff.de/threads/zydtech-controller-bluetooth-protokoll-reverse-engineering.12207/

## Autor

DoM!niC — [git.3dns.eu/dom1nic/epf_cockpit_android](https://git.3dns.eu/dom1nic/epf_cockpit_android)
