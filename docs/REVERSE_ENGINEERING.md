# Reverse-Engineering des Pulse-Plus-Protokolls

Diese Datei beschreibt, wie ich das BLE-Protokoll für den EPF Pulse Plus
herausgefunden habe — als Anleitung, falls jemand weitere Funktionen oder
ein anderes Pulse-Modell sniffen will.

## Werkzeuge

- **Phone mit der offiziellen App** (`com.zydtech.epowerfun` aus dem Play Store)
- **Android Developer Options** mit aktivem **„Bluetooth-HCI-Snoop-Log"**
  (Settings → System → Entwickleroptionen → Bluetooth-HCI-Snoop-Log → „Aktiviert")
- **`adb`** für Bug-Report-Pull
- **Python 3** für den btsnoop-Parser (`docs/scripts/parse_btsnoop.py` — siehe unten)
- (Optional) **Wireshark** zur visuellen BLE-Frame-Inspektion

## Pipeline

1. **HCI-Snoop aktivieren**, BT aus/an (Snoop-Log startet erst beim BT-Restart)
2. **Offizielle App benutzen**, gezielt einzelne Aktionen klicken — möglichst
   nur EINE Funktion pro Sniff-Run + Pausen zwischen Klicks, damit die Frames
   eindeutig der Aktion zuordenbar sind
3. **Bug-Report ziehen** mit `adb bugreport <name>.zip` — enthält
   `FS/data/misc/bluetooth/logs/btsnoop_hci.log`
4. **Snoop-Log parsen** und die ATT-Writes/Notifications zur Pulse-MAC
   extrahieren, dabei den `A5 02 FD 5A`-Heartbeat ausfiltern

## Mein Pipeline-Skript (Python)

Datei `parse_btsnoop.py` (außerhalb des Repos gehalten, weil unschön & Quick-Hack):

- Liest btsnoop-Format direkt (Header `btsnoop\0` + 8-Byte-Version-Block
  + n × 24-Byte-Record-Header + Payload)
- Demultiplext HCI ACL → L2CAP (Channel 4 = ATT)
- Erkennt ATT-Opcodes `0x12` (Write-Request), `0x52` (Write-Command),
  `0x1B` (Handle-Value-Notification)
- Mapped Handles auf UUIDs aus Read-By-Type-Group-Responses (Service Discovery)
- Faltet alle Heartbeat-Varianten zusammen, gibt nur den Rest zeitlich
  sortiert aus

## Was meine vier Sniff-Runs geklärt haben

### Sniff #1 — „was passiert nach Login"
- App, Login, eine Voice-Toggle-Aktion, dann ruhen lassen
- → Identifizierte: Heartbeat (`A5 02 FD 5A`), Init-Burst (`A5 FF 00 00…`),
  Modell-ID (`L10_C1EPULSE`), CRC-Endianness (little-endian)
- → MissverstandenAB: Hielt `AF 00 0A 02 00 06 0F 14 96 A7` für einen Heartbeat,
  war aber tatsächlich nur ein Read-Poll-Frame; der echte Heartbeat ist die
  4-Byte A5-Sequenz

### Sniff #2 — „Parking ein/aus + Blinkerton"
- App, Login, Parking ON klicken, 5s warten, Parking OFF, Blinkerton ON/OFF
- → Identifizierte: `AF 00 0A 22 …` (Parking ON), `AF 00 0A 82 …` (Parking OFF),
  `AT+TLVOICEOFF[0/1]` für Blinkerton
- → Verifiziert am Roller: Status-Byte [22] Bit 5 = Park-Bit

### Sniff #3 — „nur Modus Stand/Kickstart"
- App, Login, Modus Stand → Kickstart → Stand
- → Identifizierte: zweimal `22`/`82` (das was wir schon kannten), **einmal
  zusätzlich `AF 00 0A A2 …`**
- → Erste Vermutung: A2 + 82 sind Modus-Frames

### Sniff #4 — „Modus Stand Stop + Kick Start" (definitiv)
- App, Login, gezielter Mode-Toggle ohne Parking
- → Bestätigt: **`AF 00 0A A2 …` = Stand Stop**, **`AF 00 0A 82 …` = Kick Start**
- → Diskovery: 82 ist byte-identisch mit Parking OFF — die App nutzt
  bewusst den gleichen Frame, weil „Kick Start" gleichzeitig den Park-Lock
  aufhebt
- → Bei Mode-Wechsel ändert sich Telemetry-Bit 5 (Park-Echo), aber der
  physische Park-Lock am Roller nicht — daher seedet die App den
  Parking-Switch nur einmal und reagiert dann nur auf User-Klicks

## Häufige Fallen beim Sniffen

- **HCI-Snoop läuft erst nach BT-Restart** — Aktionen vor dem Restart sind
  nicht im Log
- **Bug-Report-Pull dauert ~1-2 Min** — währenddessen schreibt der Snooper
  weiter, das Log enthält dann auch Aktionen während des Pulls
- **Parallele BLE-Verbindungen unserer App stören** — vor dem Sniff
  `adb shell am force-stop eu.dns3d.epfcockpit.debug` ausführen
- **Privacy-MAC**: Der Pulse Plus advertised mit rotierender Random-MAC.
  Die HCI-Connection-Complete-Events tragen die zur Connection-Zeit
  gültige MAC — danach für den Verlauf der Session gleich
- **Modbus-FC3-Read-Block** kommt einmal pro Connect (Settings-Discovery),
  bringt für Function-RE nichts — beim Parsen ausfiltern

## Nicht-identifizierte Frames

| Frame                                       | Vorkommen                  |
|---------------------------------------------|----------------------------|
| `AF 00 0A 02 00 06 0F 14 96 A7`             | Read-Poll, vom ePF-1 doku  |
| `01 03 00 …` (FC3)                          | Settings-Read-Block        |

~~Telemetry [21] `04` vs `0C`~~ — aufgeklärt: Bytes 21-22 sind ein
u16-Bitfeld, das Flackern ist `lockSw` (Bit 11). Siehe
[PROTOCOL.md](PROTOCOL.md#status-bitfeld-21-22).

Wer hier mehr findet — Pull-Request willkommen.

## Dekompilierung der offiziellen App

Wenn Sniffen nicht reicht (z.B. um **Skalierungsfaktoren** zu klären, die
man aus Rohframes allein nicht ablesen kann), hilft der Blick in die
offizielle App selbst:

```bash
adb shell pm path com.zydtech.epowerfun          # base.apk-Pfad holen
adb pull <pfad>/base.apk epf_official.apk
jadx -d out --no-res epf_official.apk
```

Der Telemetrie-Parser sitzt in
`com.zydtech.library.core.BleCore#parsingDataBuf` (Monitor-Pfad: Header
`0xAF`/`0xAB`, `buf[1] == 0`). Genau diese Methode zerlegt jadx standardmäßig
nur unvollständig — dann gezielt nachfassen mit:

```bash
jadx -d out2 --no-res --show-bad-code \
     --single-class "com.zydtech.library.core.BleCore" epf_official.apk
```

So wurde die Strom-Skalierung `/ 64` gefunden (unsere App hatte bis dahin
die ZydDash-Skala `* 0.01` — Anzeige ~36 % zu niedrig), außerdem Byte 15 =
Motor-Temperatur und das Status-Bitfeld [21-22].

## Empfohlene Sniff-Diskipline

1. Eine Funktion pro Sniff
2. Mindestens 3 s zwischen Klicks
3. Nie zwei Dinge gleichzeitig anfassen
4. Notieren was geklickt wurde + ungefähre Zeit
5. Mehrere Sniffs derselben Aktion vergleichen, um Rauschen zu trennen
