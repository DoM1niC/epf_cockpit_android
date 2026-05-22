# Architektur

Kurzdoku für Mitlesende — wer Code anpassen will, findet hier die Landkarte.

## Tech-Stack

- **Kotlin 2.1**, **Jetpack Compose** (`compose-bom 2024.12`)
- **Material 3 Expressive** (`material3:1.4.0-alpha`) mit Dynamic Color
- **Hilt** für DI, **Navigation Compose** für Routing
- **Nordic Android BLE Library** für GATT-Stack-Komfort
- **Room** für Trip-Historie, **DataStore Preferences** für Settings
- **Min SDK 31** (Android 12), **Target/Compile SDK 35**

## Modul-Übersicht

```
app/src/main/kotlin/eu/dns3d/epfcockpit/
├── EpfApplication.kt            // @HiltAndroidApp
├── MainActivity.kt              // @AndroidEntryPoint, Splash, Theme, AppNav,
│                                //   injiziert TelemetryStreamer eagerly
├── ble/
│   ├── BleConstants.kt          // UUIDs, default-PIN, name prefixes
│   ├── Crc16Modbus.kt           // CRC16-MODBUS mit BE/LE-Verify
│   ├── Telemetry.kt             // Decoder für AF 00 19 01 … Frames
│   ├── ControlFrames.kt         // Frame-Konstanten + AT-Kommandos
│   ├── ScooterManager.kt        // BleManager-Subklasse (Nordic)
│   └── BleRepository.kt         // @Singleton, einziger BLE-Entry-Point
├── data/
│   ├── SettingsStore.kt         // DataStore-wrapped Preferences inkl.
│   │                            //   themeMode + streaming{Url,Token,Enabled}
│   ├── Trip*.kt                 // Room: Trip-Entity + DAO + DB
│   └── TripRecorder.kt          // Aggregiert Telemetry → Trip-Aggregat
├── streaming/
│   ├── StreamMessage.kt         // Sealed JSON-Wire-Format (kotlinx.serialization)
│   └── TelemetryStreamer.kt     // @Singleton, OkHttp-WebSocket-Push an web/-Server
├── di/
│   └── AppModule.kt             // Hilt-Provider für Room
├── ui/
│   ├── theme/                   // Color, Type, Shape, Theme (M3 Expressive)
│   ├── nav/AppNav.kt            // NavHost + Routes
│   ├── scan/                    // ScanScreen + ViewModel
│   ├── auth/                    // PinScreen + ViewModel
│   ├── dashboard/               // DashboardScreen + ViewModel + components
│   ├── settings/                // SettingsScreen + ViewModel (Theme/Streaming/About)
│   └── history/                 // HistoryScreen + ViewModel
└── util/
    └── Permissions.kt           // BLUETOOTH_SCAN/CONNECT request list

web/                              // Optional: Node-Dashboard, Details: web/README.md
├── src/                          // Express 5 + ws + better-sqlite3 (WAL)
├── public/                       // dashboard.html + obs.html + history.html
└── deploy/                       // nginx.conf + systemd unit + Step-by-Step
```

## State-Flow

```
                ┌────────────────┐
                │ BleRepository  │   @Singleton
                │  (lebensdauer) │
                └────┬───────────┘
                     │ exposes
       ┌─────────────┼────────────────┐
       │             │                │
       ▼             ▼                ▼
  connection    telemetry        voiceOff
  StateFlow     StateFlow        StateFlow
       │             │                │
       └─────────────┼────────────────┘
                     │ collected by
                     ▼
        ScanVM / PinVM / DashboardVM / SettingsVM
                     │ exposes UI state
                     ▼
            Compose screens (collectAsStateWithLifecycle)
```

`BleRepository` ist der einzige Singleton, der mit dem Nordic-Manager redet.
Alle ViewModels lesen aus dem Repository — kein direkter `ScooterManager`-Zugriff
aus der UI.

## Navigation

```
Routes.SCAN   (Start)
  └── onConnected
      → Routes.PIN
          └── onAuthenticated
              → Routes.DASHBOARD     (popUpTo SCAN inclusive)
                  ├── onOpenSettings → Routes.SETTINGS
                  ├── onOpenHistory  → Routes.HISTORY
                  └── onDisconnect   → Routes.SCAN
```

`SettingsStore.autoConnect` + `BleRepository.userDisconnected` bestimmen
beim ScanScreen-Mount, ob direkt ein Auto-Reconnect läuft oder erst manuell
gescannt wird.

## Auto-Reconnect-Logik (`ScanViewModel.startAutoReconnect`)

Zweiphasiges Reconnect-Pattern:

1. **Phase 1 (≤6 s)** — direkter `ble.connect(savedAddress)` mit
   `useAutoConnect(false)`. Funktioniert wenn die Privacy-MAC seit dem letzten
   Connect nicht rotiert hat (häufiger Fall innerhalb derselben Session)
2. **Phase 2 (≤15 s)** — `ble.scan()` mit Name-Filter auf den gespeicherten
   `rememberedName` (oder Address als Tiebreaker). Sobald gefunden:
   `settings.rememberScooter(neueMac, name)` + `ble.connect(neueMac)`
3. **Fallback** — `startScan()` (sichtbarer manueller Scan)

`userDisconnected: Boolean` im Repository ist eine sticky-flag für die aktuelle
Process-Session — nach manuellem „Trennen" wird Auto-Reconnect übersprungen
bis zum nächsten App-Restart.

## BLE-Manager-Eigenheiten (warum wir die Library so verwenden)

- **`useAutoConnect(false)`** statt `true`: active connect ist viel schneller
  (≈1 s) als der passive scan-Mode (mehrere Sek bis zur ersten Connection-Try)
- **`requestDisconnect()`** statt `disconnectAndRelease()`:
  Nordic-`close()` invalidiert die Manager-Instanz für die ganze Lebensdauer.
  Wir rufen `close()` daher nie — der Manager bleibt für die App-Session
  wiederverwendbar
- **Fire-and-forget Writes** mit `enqueue()`: Nordic serialisiert intern,
  zusätzliche Mutex-Locks bringen nichts (haben wir probiert, machte es
  schlimmer). `.suspend()` kann bei nicht-acknowledgenden Writes die Queue
  blockieren — daher nur für AT-Reqests mit erwarteter Response
- **CancellationException explizit rethrowen** (nicht in `runCatching`
  schlucken!), sonst landet die Cancel-Message als „Fehler" in der UI
- **ScanFilter immer setzen** (mindestens Wildcard), sonst kickt Androids
  Location-Deny-Guard den Scan-Client raus

## ViewModel-State-Patterns

- **Optimistic State**: Bei Toggles wird der State sofort im VM auf den Soll-Wert
  gesetzt, kein Warten auf Server-Echo. Korrektur über Telemetry, wenn nötig.
- **Persistent State**: Was nach App-Restart erhalten bleiben soll, liegt in
  `SettingsStore` (DataStore). Aktuell: gespeicherter Scooter (MAC + Name +
  PIN), `autoConnect`, `rideMode`.
- **Decoupled State**: `parking` im DashboardViewModel wird **einmal** aus
  dem ersten Telemetry-Frame gelesen und ist danach rein User-getrieben —
  weil Mode-Frames das Telemetry-Bit-5 mitziehen, ohne dass der physische
  Park-Lock am Roller umschaltet (siehe [PROTOCOL.md](PROTOCOL.md)).

## Heartbeat-Loop

`BleRepository.startHeartbeat()` startet eine Coroutine, die alle 600 ms
`ControlFrames.PULSE_HEARTBEAT` (`A5 02 FD 5A`) an `lowTx` schreibt. Ohne
diesen Heartbeat:
- Pulse Plus sendet keine Telemetry-Notifications
- Pulse Plus kappt die Connection nach ~30 s mit `GATT CONN TERMINATE PEER USER`

Heartbeat wird in `disconnect()` gestoppt und vor Beginn jeweils neu gestartet,
sodass kein Doppel-Job läuft.

## Trip-Recording

`TripRecorder` läuft innerhalb der DashboardViewModel-Lifecycle: jeder
Telemetry-Frame nach Connect wird aggregiert (Strecke aus Trip-km-Delta,
Energy als Integral über |U·I|·Δt, Max/Ø-Speed). Beim Disconnect oder
ViewModel-OnCleared wird der finale Trip in Room geschrieben.

## Sicherheit / UX-Regeln

- **Controls-Lock während Fahrt**: `DashboardViewModel.controlsLocked` =
  `speedKmh > 1.0`. Parking/Mode/Blinkerton sind dann disabled.
- **Bildschirm bleibt an**: `FLAG_KEEP_SCREEN_ON` per `DisposableEffect`
  auf dem DashboardScreen (cleared beim Wegnavigieren)
- **PIN wird in DataStore (Klartext)** gespeichert — dieselbe PIN geht
  auch unverschlüsselt über BLE (per ZydDash-Analyse), daher hätte
  EncryptedSharedPreferences hier nur Symbolwert
- **Streaming-Token** ebenfalls in DataStore Klartext — wird per
  `Authorization: Bearer …` an den WS-Upgrade gehängt, nicht via
  Query-Param. Begründung + Server-seitige Auth-Architektur siehe
  [STREAMING.md](STREAMING.md).

## Streaming-Layer

`TelemetryStreamer` ist ein `@Singleton`, der von `MainActivity` eagerly
injiziert wird (damit er ab App-Start läuft, unabhängig davon ob der User
gerade die Settings öffnet). Drei Coroutines auf einem eigenen
`SupervisorJob`:

1. Watcher auf `SettingsStore.flow` — `distinctUntilChanged()` über
   `Triple(url, enabled, token)` → reconnected bei jedem Wechsel.
2. Forward von `BleRepository.connection` → `state`-JSON-Frames.
3. Forward von `BleRepository.telemetry` → `telemetry`-JSON-Frames,
   throttled auf 5 Hz (200 ms min-interval).

Reconnect-Loop mit Exponential-Backoff bis 30 s. `CancellationException`
explizit erlaubt (Job-Cancel ist normal). Status wird als `StateFlow`
exposed und im Settings-Screen live angezeigt.
