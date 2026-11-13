# Changelog

Alle nennenswerten Änderungen an EPF Cockpit (App + Web-Dashboard).
Neueste Einträge oben.

## 2026-11-13

### Neu in der App

- **Frontlicht schalten** — neuer Schalter „Licht“ im Dashboard. Der Zustand
  wird live vom Roller gelesen, die Anzeige stimmt also auch, wenn das Licht am
  Roller selbst geschaltet wird. Bedienbar, solange der Roller entsperrt ist
  und steht (wie die übrigen Schalter während der Fahrt gesperrt).
- **Licht beim Entsperren einschalten** — neue Option unter
  *Einstellungen → Roller*, standardmäßig **aus**. Ist sie aktiv, geht das
  Frontlicht automatisch mit an, sobald der Roller entsperrt wird (Parking aus,
  auch über Widget, Quick-Tile und Benachrichtigung). Kein Lichtzwang:
  ausschalten geht jederzeit über den Licht-Schalter.
- **Tageskilometer zurücksetzen** — neuer Knopf unter
  *Einstellungen → Roller* (mit Rückfrage, nur im Stand). Setzt den
  Trip-Zähler am Roller auf 0; Gesamtkilometer und gespeicherte Fahrten bleiben
  erhalten.

### Verhalten des Rollers (live am Pulse Plus verifiziert)

- Das Licht lässt sich nur im entsperrten Zustand schalten. Geparkt ignoriert
  der Controller das Licht, und beim Parken schaltet er ein brennendes Licht
  selbst ab. Der Licht-Schalter ist deshalb im geparkten Zustand ausgegraut.

### Behoben

- **Web-Dashboard zeigte „Parking: an · Modus: Kick“**, obwohl der Roller
  entsperrt bzw. im Stand-Stop-Modus war. Die App schickt jetzt denselben
  Park-Zustand und Modus, den sie selbst anzeigt (statt eines mehrdeutigen
  Roh-Bits bzw. gar keines Modus). Ältere App-Versionen zeigen im Web „–“
  statt eines falschen Werts.
- Ein Modus-Wechsel schaltet ein eingeschaltetes Licht nicht mehr aus.

### Web-Dashboard

- **Neues Design** für Live-Ansicht, Fahrtverlauf und OBS-Overlay: großer
  Tacho, Akku- und Leistungs-Kacheln mit Verlauf, farbige Status-Anzeigen
  (Parken, Modus, Licht), Dunkel/Hell/Auto-Umschalter, selbst gehostete Schrift
  (Inter), deutsches Zahlenformat.
- **Neu angezeigt**: Tageskilometer, Gesamtkilometer, Motor-Temperatur,
  Lichtstatus.
- Live-Charts zeigen beim Öffnen sofort die letzten 5 Minuten.
- Live-Karte mit „Folgen“-Knopf.
- Fahrtverlauf nach Tagen gruppiert, mit Summen (Fahrten, Strecke, Fahrzeit,
  Energie, Wh/km).
- Klare Hinweise, wenn kein Roller verbunden oder der Server nicht erreichbar
  ist.
- Behoben: Navigation mit Zugangs-Token landete auf einer Fehlerseite;
  eigener Zeitraum im Verlauf ging nach dem Neuladen verloren; Doppelklick-Reset
  im Chart stellte das Zeitfenster nicht wieder her; `?theme=light` im
  OBS-Overlay hatte keine Wirkung.

### Intern

- Steuer-Bits des Statusframes aus der dekompilierten offiziellen App
  dokumentiert (Bit 2 = Licht, Bit 3 = Trip-Reset), siehe
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md). Unit-Tests sichern die
  sniff-verifizierten Frames ab.
- Telemetrie-Stream ans Web um `lightOn` und `motorTempC` erweitert
  (optional, abwärtskompatibel).
- Neues Deploy-Skript [`web/deploy/deploy.sh`](web/deploy/deploy.sh):
  Code-Snapshot und SQLite-Backup vor jedem Deploy, Neustart mit
  Healthcheck und automatischem Rollback, `--dry-run` zeigt die Änderungen.
