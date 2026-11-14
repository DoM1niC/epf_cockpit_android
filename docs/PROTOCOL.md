# Pulse Plus BLE-Protokoll

Reverse-engineered durch HCI-Sniffing der offiziellen `com.zydtech.epowerfun`
App gegen einen **EPF Pulse Plus** (Modell-ID `L10_C1EPULSE`, Hobbywing-Controller,
advertising als `HW_EF043840`). Stand: Mai 2026, App-Version 4.x.

> ⚠️ Die [ZydDash-Doku](https://github.com/Ennar1991/ZydDash/blob/main/BLE_Telemetry.md)
> für den ePF-1 (Zydtech HW9027) ist nur teilweise auf den Pulse Plus
> anwendbar. Unsere App akzeptiert beide CRC-Endianness und decodiert mit dem
> Pulse-Plus-Layout — sollte für den ePF-1 trotzdem laufen, ungetestet.
> **Wichtigste Abweichung**: Die Strom-Skalierung ist beim Pulse Plus
> **i16 / 64 A** (Hobbywing-Festkomma), nicht i16 × 0.01 A wie beim ePF-1 —
> mit der ZydDash-Skala zeigt man ~36 % zu wenig an.
>
> Die Telemetrie-Feldzuordnung unten ist gegen die **dekompilierte offizielle
> App** verifiziert (jadx, `com.zydtech.library.core.BleCore#parsingDataBuf`,
> Monitor-Pfad für Header `0xAF`, `buf[1] == 0`).

## BLE Services & Characteristics

Identisch zwischen ePF-1 und Pulse Plus:

| Service       | Char (TX → Scooter)    | Char (RX ← Scooter)    | Zweck                         |
|---------------|------------------------|------------------------|-------------------------------|
| `0000f1f0-…`  | `0000f1f1-…`           | `0000f1f2-…`           | Telemetrie + Steuer-Frames    |
| `0000f2f0-…`  | `0000f2f1-…`           | `0000f2f2-…`           | AT-Kommandos (PIN, Device, Voice) |

Im Sniff sieht man außerdem `f000ffc1-…` und `f000ffc2-…` (TI OAD Service) —
**nicht anfassen**, das ist der OTA-Firmware-Update-Kanal.

## AT-Kommandos (High-Level via `f2f1`)

Alle ASCII, Antwort kommt als Notification auf `f2f2`.

| Befehl                  | Antwort                          | Bedeutung                          |
|-------------------------|----------------------------------|------------------------------------|
| `AT+PWD[123456]`        | `OK+PWD:Y` / `OK+PWD:N`          | PIN verifizieren (6 Ziffern)       |
| `AT+PWDM[123456]`       | `OK+PWDM:…`                      | PIN ändern (ohne vorherige Auth!)  |
| `AT+DEVICE?`            | `OK+DEVICE[L10_C1EPULSE]`        | Modell-Identifikation              |
| `AT+TLVOICEOFF?`        | `OK+TLVOICEOFF:0` / `…:1`        | Voice-/Blinkerton-Status abfragen  |
| `AT+TLVOICEOFF[0]`      | `OK+TLVOICEOFF:0`                | Voice setzen (siehe Hinweis)       |
| `AT+TLVOICEOFF[1]`      | `OK+TLVOICEOFF:1`                | Voice setzen                       |

**Wichtig zu TLVOICEOFF**: Trotz Name bedeutet `:1` beim Pulse Plus dass die
Voice **aktiv** ist (Blinkerton AN), `:0` dass sie **aus** ist. Die UI dieser
App invertiert die naive Semantik daher.

**Default-PIN ab Werk**: `888888`.

## Low-Level Frames (via `f1f1` / `f1f2`)

Es gibt drei Frame-Familien:

### A) `A5`-Sync-Frames

Kein Payload, dienen als Init und Heartbeat.

| Bytes                      | Vorkommen                              | Zweck                  |
|----------------------------|----------------------------------------|------------------------|
| `A5 FF 00 00 00 00 00 5A`  | 5× direkt nach `AT+PWD[…]` → `OK+PWD:Y` | Wake-up / Init-Burst   |
| `A5 02 FD 5A`              | alle ~600 ms während aktiver Session   | **Heartbeat** — ohne den kappt der Scooter die Verbindung nach ~30 s mit `GATT CONN TERMINATE PEER USER` |
| `A5 00 FF 00 00 00 00 5A`  | umschließt den Modbus-Block            | Bridge-Frame           |

Ohne den Heartbeat **streamt der Scooter keine Telemetrie**, und nach ~30 s
trennt er die Verbindung. Das war eine der überraschendsten Eigenheiten —
ein triviales 4-Byte-Frame, ohne das nichts funktioniert.

### B) `AF`-Control-Frames

Format: `AF 00 0A <OP> 00 06 0F 14 <CRC_LE>` (10 Bytes inkl. CRC).

Die OP-Byte-Kombinationen kodieren Park-Status und Ride-Mode kombiniert.
Sniff-verifizierte Werte:

| OP     | Bytes (komplett)                | Aktion in der offiziellen App          |
|--------|--------------------------------|----------------------------------------|
| `0x02` | `AF 00 0A 02 00 06 0F 14 96 A7` | Read-Poll (vom ePF-1 dokumentiert)     |
| `0x22` | `AF 00 0A 22 00 06 0F 14 17 60` | **Parking ON**                         |
| `0x82` | `AF 00 0A 82 00 06 0F 14 97 79` | **Parking OFF** = **Kick Start Mode**  |
| `0xA2` | `AF 00 0A A2 00 06 0F 14 16 BE` | **Stand Stop Mode**                    |

**OP-Bit-Layout** laut dekompilierter offizieller App
(`BleCore.setBaseParams` → `sendMonitor`; das Byte wird LSB-first aus
`BaseParams` zusammengesetzt):

| Bit | Maske  | Zydtech-Name        | Bedeutung beim Pulse Plus                                  |
|-----|--------|---------------------|------------------------------------------------------------|
| 0-1 | `0x03` | `gearPosition`      | immer `2` gesendet                                         |
| 2   | `0x04` | `headLightSw`       | **Frontlicht** — live verifiziert, siehe unten            |
| 3   | `0x08` | `atmosphereLightSw` | **Tageskilometer-Reset** (offizielle App „reset_distance") |
| 4   | `0x10` | `cruiseControlSw`   | ungenutzt                                                  |
| 5   | `0x20` | `bootMode`          | Teil der Park/Mode-Kombination                             |
| 6   | `0x40` | `metricInchSw`      | ungenutzt                                                  |
| 7   | `0x80` | `lockSw`            | Teil der Park/Mode-Kombination                             |

Unsere App behandelt Park/Mode als die drei sniff-verifizierten Kombinationen
`0x22` / `0x82` / `0xA2` und setzt Bit 2/3 nur zusätzlich.

**Frontlicht (Bit 2)**, live verifiziert am Pulse Plus (Nov 2026):
- entsperrt: `0xA6` / `0x86` schaltet das Licht ein, `0xA2` / `0x82` aus.
  Telemetrie-Bit 2 (`headLightSw`) folgt nach ~0,5 s.
- geparkt: Bit 2 wird ignoriert (`0x26` lässt das Licht aus), und beim Parken
  schaltet der Controller ein brennendes Licht selbst ab. Unsere Park-Frames
  senden Bit 2 deshalb nie.
- Weil jedes Frame den kompletten Zustand trägt, muss Bit 2 in Mode-/Speed-
  Frames mitgesendet werden — sonst schaltet ein Mode-Wechsel das Licht aus.

**Tageskilometer-Reset (Bit 3)**: Die offizielle App ruft dafür
`setBaseParams(2, true)` auf, setzt also nur Bit 3 im aktuellen Zustand
(ZydDash dokumentiert für den ePF-1 ebenfalls „bit3 = trip reset"). Unsere App
sendet den aktuellen Zustand einmal mit Bit 3 und 600 ms später ohne.
**Noch nicht live am Roller getestet.**

**Wichtig**: `0x82` ist byte-identisch für „Parking OFF" und „Kick Start"
— in der offiziellen App lösen beide Buttons dasselbe Frame aus, weil
„Kick Start" funktional auch den Park-Lock aufhebt. Unsere App mappt das
ebenso (1:1 wie die offizielle App).

**CRC**: 16-bit MODBUS, **little-endian** angehängt. Beispiele:
- `AF 00 0A 02 00 06 0F 14` → CRC `96 A7` (= 0xA796 BE = 0x96A7 LE)
- `AF 00 0A 22 00 06 0F 14` → CRC `17 60`
- `AF 00 0A A2 00 06 0F 14` → CRC `16 BE`

> ZydDash spezifiziert für ePF-1 die CRC als **big-endian**. Wir verifizieren
> daher tolerant: beide Endianness werden akzeptiert (`Crc16Modbus.verify`).

### C) `AF`-Telemetry-Frames

Vom Scooter alle ~120 ms als Notification, 25 Bytes:

```
Off  Bytes                       Bedeutung
 0   AF                          Header
 1   00                          Direction (Phone ← Scooter)
 2   19                          Length (25 bytes)
 3   01                          Frame-Type (1 = Telemetry)
 4   <gear>                      Aktueller Gang (z.B. 02)
 5   <batt%>                     Akku-Stand 0..100
 6-7  <speed_be>                 Geschwindigkeit (u16 BE) * 0.001 km/h
 8-9  <speed2_be>                zweites Speed-Feld — die offizielle App zeigt
                                 max(u16[6], u16[8]); beim Pulse Plus im Sniff
                                 immer 00 00
10-11 <voltage_be>               Spannung (u16 BE) * 0.1 V
12-13 <current_be>               Strom (i16 BE signed) / 64 A
                                 (negativ = Rekuperation; Hobbywing-Festkomma
                                 mit 6 Fraktionsbits — NICHT * 0.01 wie ePF-1)
14   <esc_temp>                  Controller-Temperatur in °C (signed byte)
15   <motor_temp>                Motor-Temperatur in °C (signed byte) — Pulse Plus sendet konstant 0x16 (22), wird nicht angezeigt
16-17 <trip_be>                  Trip-km (u16 BE) * 0.1
18-20 <total_be>                 Gesamt-km (u24 BE) * 0.1
21-22 <status_be>                Status-Bitfeld u16 BE (siehe unten)
23-24 <crc16_be>                 MODBUS-CRC16, big-endian
```

Daneben sendet der Scooter alternierend `AF 01 19 …` (Frame-Type 0) — das ist
ein **Command-Echo** und enthält keine Telemetry. Der `TelemetryDecoder`
ignoriert diese Frames (`frame[3] != 0x01`).

### Status-Bitfeld [21-22]

Die offizielle App liest die Bytes 21-22 als **ein u16 BE Bitfeld**
(intern „registerZero") und mappt laut Dekompilat:

| u16-Bit | Byte/Bit  | Zydtech-Name       | Bemerkung                          |
|---------|-----------|--------------------|-------------------------------------|
| 2       | [22]·2    | `headLightSw`      | Frontlicht an (live verifiziert)    |
| 5       | [22]·5    | `bootMode`         | = unser „Park-Echo", siehe unten    |
| 6       | [22]·6    | `metricInchSw`     |                                     |
| 9       | [21]·1    | `cruiseControlSw`  |                                     |
| 11      | [21]·3    | `lockSw`           | **1 = entsperrt.** Live verifiziert: geparkt `0x0422`, entsperrt `0x0C22`/`0x0C26` — löst die Mehrdeutigkeit von Bit 5 auf (`Telemetry.parked`) |
| 15      | [21]·7    | `atmosphereLightSw`|                                     |

Werte aus dem Sniff (Byte 22):

| Byte | Bit 5 | Bit 1 | Bedeutung              |
|------|-------|-------|------------------------|
| `02` | 0     | 1     | Aktiv, kein Park       |
| `22` | 1     | 1     | Park ON                |

**Bit 5 = Park-Lock aus Sicht unserer App** (verifiziert am Roller: physisch
im Park → `0x22`). Zydtech nennt dasselbe Bit `bootMode` — konsistent damit,
dass „Kick Start"-Frames (`0x82`) es ebenfalls umschalten, obwohl der
physische Park-Lock unverändert bleibt. Ein separater verlässlicher
Ride-Mode-Indikator existiert damit weiterhin nicht; der Mode wird in der
App persistent in DataStore gespeichert.

**Wichtig zur UI-Politik**: Mode-Frames (`A2`, `82`) ändern das Telemetry-Bit-5
(Park-Echo), obwohl der physische Park-Lock am Roller davon nicht beeinflusst
wird. Unsere App seedet daher den Parking-Switch **einmal** beim ersten
Telemetry-Frame nach Connect und reagiert danach nur noch auf User-Klicks
auf den Parking-Switch selbst — ein Mode-Wechsel lässt die Parking-UI in
Ruhe.

## Modbus-RTU Read-Block

Beim Login und beim erneuten Lesen sendet die offizielle App einen Block
mit Modbus-FC-3-Frames (Read Holding Registers):

```
01 03 00 00 00 10 44 06   # READ 16 regs @ 0x0000
01 03 00 20 00 10 45 CC   # READ 16 regs @ 0x0020
01 03 00 40 00 0F 04 1A   # READ 15 regs @ 0x0040
01 03 00 60 00 10 44 18   # READ 16 regs @ 0x0060
01 03 00 70 00 10 45 DD
01 03 00 80 00 10 45 EE
01 03 00 90 00 10 44 2B
```

Format: Slave-ID `01`, FC `03`, Address, Count, CRC16-RTU (LE).

Der Block dient vermutlich Settings-Discovery (Speed-Limits, Kalibrierungen
etc.). **Wird in dieser App nicht genutzt** — die Antworten sind nicht
sicher gemappt und wir können das ohne diese Sachen leben.

## Verbindungs-Flow

Empfohlene Sequenz (matched die offizielle App):

```
1. BLE-Connect (TRANSPORT_LE, autoConnect=false für schnellen Connect)
2. Service-Discovery → handles f1f1/f1f2/f2f1/f2f2 mappen
3. MTU auf 247 setzen (Pulse antwortet 251)
4. CCCD-Notifications für f1f2 und f2f2 enablen
5. AT+PWD[<pin>] schreiben, auf OK+PWD:Y warten
6. AT+DEVICE? + AT+TLVOICEOFF? abfragen (App-Identifikation)
7. 5× A5 FF 00 00 00 00 00 5A schicken (Init-Burst)
8. Heartbeat starten: alle 600 ms A5 02 FD 5A
9. Telemetry strömt jetzt auf f1f2
```

Schritt 7+8 sind **essentiell** — ohne Heartbeat keine Telemetry und nach
~30 s Disconnect.

## Verbindungs-Eigenheiten

- **Privacy MAC**: Der Pulse Plus advertised mit einer **random/private MAC**
  (`62:…` mit Bit 0 = 1, lokal administriert). Diese kann zwischen Sessions
  rotieren — direkter Connect mit gespeicherter MAC schlägt dann fehl. Unsere
  App fängt das ab via Phase-2-Scan nach dem Device-Namen.
- **`BleManager.close()` ist final**: Nach `close()` ist die Nordic-Manager-
  Instanz nicht mehr verwendbar; weitere `connect()`-Aufrufe sind stille
  No-Ops. In unserer App rufen wir nur `disconnect()`, nie `close()`.
- **`useAutoConnect(true)` ist langsam**: passiver Scan-Modus, mehrere
  Sekunden Latenz. Für initial Connect besser `useAutoConnect(false)`
  mit kurzem Timeout. (Für das Warten im Hintergrund nutzt die App einen
  separaten, nackten `connectGatt(autoConnect=true)`-Sentinel — dort ist die
  Latenz egal, dafür braucht er keine App-CPU.)
- **Disconnect-Reason mit `useAutoConnect(false)`**: Nordic setzt intern
  `userDisconnected=true` und reicht `mapDisconnectStatusToReason` durch —
  HCI 0x13 → 2, 0x08/0x00 → 10, 0x16 → 1, sonst −1. `REASON_LINK_LOSS` (3)
  kommt nie. `REASON_TERMINATE_LOCAL_HOST` ist deshalb kein Beleg für ein
  bewusstes Trennen; die App merkt sich eigene Trennungen selbst
  (`ScooterManager.disconnectRequested`).
- **Location-Deny-Guard**: Android (auch stock AOSP) verwirft BLE-Scans
  ohne `ScanFilter`, wenn `BLUETOOTH_SCAN` mit `neverForLocation` deklariert
  ist. Wir setzen daher immer mindestens einen Wildcard-`ScanFilter`.

## Was wir NICHT wissen / nicht implementiert

- **Andere AT-Kommandos** als die oben gelisteten — die App könnte mehr
  unterstützen, wir haben aber nichts weiter aus den Sniffs erkannt.
- **Mode-Indikator in Telemetrie** — Bit 7 von Status-Byte wechselt nicht
  beobachtbar.
- **OTA-Firmware-Update** über `f000ffc1/c2` — absichtlich nicht angefasst.
- **Sondertelemetrie** über den Modbus-Block — die Werte sind interpretierbar
  aber nicht 1:1 dokumentiert.

## Andere bekannte Pulse-Plus-Frames (ungenutzt)

| Frame                              | Vermutung                          |
|------------------------------------|------------------------------------|
| `01 03 00 90 00 10 44 2B`          | Modbus FC3 @ 0x0090 (Settings)     |
| `A5 00 FF 00 00 00 00 5A`          | Bridge-Frame um Modbus-Block       |
| `AF 00 0A 02 …` (immer mit VAL `0x14`) | Read-Poll-Variante              |
