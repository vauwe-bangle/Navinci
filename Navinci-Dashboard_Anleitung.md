# Navinci

Android-App für Fahrradtouren.  
Erfasst Geschwindigkeit und Distanz via GPS, Trittfrequenz und Geschwindigkeit via BLE-Sensor (CSC-Profil) und Höhenmeter via Barometer oder GPS-Fallback mit Kalman-Filter.

© 2026 softopus | GPL v3 | Nicht für kommerzielle Nutzung ohne Genehmigung

## Leistungsmerkmale

**3 Screens per Navigation:**

- **Live** — Echtzeit-Anzeige von Geschwindigkeit (BLE-Sensor oder GPS-Fallback), Distanz, Höhe und Fahrzeit
- **Stats** — Durchschnittsgeschwindigkeit, Gesamtkilometer, Höchstgeschwindigkeit, Uhrzeit, Trittfrequenz, Höhenmeter ↑ und Radumfang-Einstellung
- **Verlauf** — Übersicht der letzten Fahrten mit Distanz, Fahrzeit, Ø-Tempo, Max-Tempo, Ø-Trittfrequenz, Höhenmeter ↑ und Notiz; CSV-Export und -Import

**Datenbeschaffung:**

- GPS-basierte Geschwindigkeit und Distanz (Haversine-Formel, Rauschunterdrückung unter 2 km/h)
- BLE-Verbindung zum CSC-Sensor (Cycling Speed and Cadence, Bluetooth SIG Standard 0x1816):
  - Geschwindigkeit aus Radumdrehungen (Wheel Revolution Data, Bit 0)
  - Trittfrequenz aus Kurbelumdrehungen (Crank Revolution Data, Bit 1)
  - Automatischer GPS-Fallback bei nicht verbundenem Sensor
- Höhenmessung:
  - Primär: Barometrischer Drucksensor (`Sensor.TYPE_PRESSURE`) mit 5-Wert-Glättung, Schwellenwert 2 m
  - Fallback: GPS-Höhe mit 1D-Kalman-Filter (Q = 0,5 / R = 225), Schwellenwert 5 m
- Fahrzeit-Timer startet automatisch bei Bewegung (TrackingService)
- Echtzeit-Uhr

**Bedienung:**

- Manueller BLE-Verbindungsaufbau per Button mit Scan-Animation
- OsmAnd-Navigation per Button; unterstützt `FLAG_ACTIVITY_LAUNCH_ADJACENT` für Split-Screen-Betrieb
- 3 Farbthemen (Teal, Amber, Coral) — persistent gespeichert
- Deutsch/Englisch-Umschaltung
- Wake-Lock-Umschalter (Display bleibt aktiv)
- Radumfang konfigurierbar (Standardwert 2,105 m für 700c × 25 mm)

---

## Programmiertechniken

**Android (Kotlin):**

- `AppCompatActivity` mit `WebView` als UI-Container
- `BluetoothGatt` / `BluetoothLeScanner` für native BLE-Kommunikation (CSC-Profil)
- CSC Measurement Parser nach Bluetooth SIG Spec (0x2A5B): Wheel- und Crank-Revolution-Daten mit 16-Bit-Rollover-Behandlung
- GPS via nativer `LocationManager` — Google-frei
- Barometersensor via `SensorManager` (`Sensor.TYPE_PRESSURE`) im `TrackingService`
- `JavascriptInterface` als Bridge zwischen Kotlin und JavaScript
- `evaluateJavascript()` für Echtzeit-Datenübertragung Kotlin → WebView
- `TrackingService` als Foreground-Service: GPS, Barometer und Timer laufen im Hintergrund (auch bei gesperrtem Display)
- Lifecycle-Management (`onResume`, `onPause`, `onDestroy`)
- Laufzeit-Berechtigungsabfrage (BLE + GPS)
- `Locale.US` für locale-unabhängige JSON-Formatierung

**Web-Frontend (Vanilla JS / HTML / CSS):**

- Dashboard als Single-Page-App im `assets`-Ordner
- CSS Custom Properties für Theming mit 3 Farbvarianten
- `localStorage` für persistente Einstellungen (Theme, Sprache, Radumfang) und Fahrtdaten
- `window.updateGps()` — GPS-Geschwindigkeit und -Distanz
- `window.updateCscSpeed()` — BLE-Sensorgeschwindigkeit mit automatischem GPS-Fallback
- `window.updateCadence()` — Trittfrequenz vom BLE-Sensor
- `window.updateCscStatus()` — Verbindungsstatus des CSC-Sensors
- `window.updateFromService()` — Sync mit TrackingService (Geschwindigkeit, Distanz, Zeit, Höhe, Höhenmeter)
- 1D-Kalman-Filter in JavaScript für GPS-Höhenglättung
- Haversine-Formel für Distanzberechnung
- CSV-Export (UTF-8) und -Import mit Duplikat-Erkennung
- Simulationsfunktion für Testdaten (01.01.2025 – heute)

**Architektur:**

- Strikte Trennung: Kotlin übernimmt Hardware (BLE, GPS, Barometer), JavaScript übernimmt UI
- Geschwindigkeitsquelle wird zur Laufzeit dynamisch gewählt (BLE-Sensor → GPS-Fallback)
- Höhenmessung zur Laufzeit dynamisch gewählt (Barometer → GPS + Kalman-Fallback)
- Eine gemeinsame HTML-Codebasis — wiederverwendbar für spätere iOS-Version via Capacitor
- Abgeleitet vom Projekt [ADD-E Dashboard](https://github.com/vauwe-digital/dashboard-add-e)

---

## Unterschiede zum ADD-E Dashboard

| Merkmal | ADD-E Dashboard | Navinci |
|---|---|---|
| BLE-Verbindung | ADD-E Akku (Nordic UART) | CSC-Sensor (Bluetooth SIG Standard) |
| Geschwindigkeit | ADD-E proprietär | CSC Wheel Revolution Data |
| Trittfrequenz | CSC Crank Data | CSC Crank Data (unverändert) |
| Akkuanzeige | Ladestand, Spannung, Strom, Ah | entfällt |
| Höhenmessung | nicht vorhanden | Barometer + GPS/Kalman-Fallback |
| Höhenmeter ↑ | nicht vorhanden | Live + Verlauf + CSV |
| Navigation | nicht vorhanden | OsmAnd-Button mit Split-Screen |
| Hintergrundbetrieb | Foreground Service (GPS) | Foreground Service (GPS + Barometer) |
