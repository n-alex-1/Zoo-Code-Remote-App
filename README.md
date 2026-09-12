# Zoo Remote — Android-App

Native Companion-App für den **Zoo Code Remote Control**-Server (VS-Code-Extension). Zeigt den Live-Status laufender Tasks, streamt die Aktivität in einen Chat-Feed und erlaubt Antworten auf Asks sowie Modus-/Modellwechsel — auch mit geschlossener App per Foreground Service + Notifications.

## Features

- **Live-Status-Screen:** Verbindungsanzeige (grün/gelb/rot), Chips für aktuellen Modus, Modell-Profil und Context-Fenster-Auslastung; Chat-artiger Aktivitätsfeed mit Reasoning (einklappbar), Werkzeug-/Befehlszeilen, Fehlern und Completion-Ergebnissen.
- **Ask-Bearbeitung:** Bestätigungs-Asks per „Genehmigen"/„Ablehnen", Freitext-Antworten und Follow-up-Suggestions direkt aus der App — derselbe Code-Pfad wie die Webview (`yesButtonClicked`/`noButtonClicked`/`messageResponse`).
- **Notifications:** Persistente Verbindungs-Nachricht (Foreground Service, `dataSync`) + „Eingabe erforderlich"-Notification mit Actions für Approve/Deny — funktioniert auch bei geschlossener App. Android 13+ fragt die Notification-Berechtigung einmalig ab.
- **Modus-/Modellwechsel:** Picker-Screens (`GET /api/modes`, `GET /api/models`), optimistische UI mit Rollback, frischer Server-Status wird sofort übernommen.
- **Robuste Verbindung:** TLS-Fingerprint-Pinning (SHA-256), Reconnect mit exponentiellem Backoff (1 s → 30 s), Pull-to-refresh als HTTP-Fallback (`GET /api/status`), klare deutsche Fehlermeldungen für Timeout, falschen Fingerprint und falsches Token (401 springt zurück zum Setup).
- **Akku:** Kein WakeLock; OkHttp-Ping alle 30 s (protokolliertes Keepalive, Pong-Antwort automatisch) — das Foreground Service hält den Prozess am Leben.

## Voraussetzungen

- Android 8.0+ (API 26), getestet mit targetSdk 34.
- VS-Code mit dem Zoo-Code-Fork inkl. Remote-Control-Server aktiviert (Branch `feature/remote-control`).
- Rechner und Handy im **gleichen LAN** (oder Emulator, siehe unten).

## Pairing-Anleitung

1. **Server starten:** In VS-Code Einstellungen → „Remote Control" aktivieren (Port standardmäßig `8999`). Der Server startet nicht-blockierend; der OutputChannel **„Zoo Remote"** zeigt:
   - die erreichbare LAN-IP + Port,
   - den **SHA-256-Fingerprint** des selbstsignierten Zertifikats (hex, mit Doppelpunkten),
   - Hinweise bei Port-Konflikten oder Zertifikat-Regeneration.
2. **Token ablesen:** VS-Code-Einstellungen → „Remote Control" → Token (oder ebenfalls im OutputChannel).
3. **App öffnen:** Setup-Screen erscheint automatisch, wenn noch keine Einstellungen gespeichert sind. Eintragen:
   - **Host / IP:** LAN-IP des Rechners (Emulator: `10.0.2.2`),
   - **Port:** `8999` (oder der konfigurierte Port),
   - **Token:** aus Schritt 2,
   - **Zertifikats-Fingerprint:** aus Schritt 1.
4. **„Verbinden" tippen.** Erfolg → grüner Status mit aktuellem Modus/Modell und automatischer Navigation auf den Status-Screen. Fehlermeldungen sind direkt im Screen eingeblendet (Timeout, Fingerprint-Mismatch mit erwartet/vorgefunden, 401).
5. **Android 13+:** Notification-Berechtigung erlauben, damit Ask-Notifications erscheinen.

### Emulator-Hinweis

Der Android-Emulator erreicht den Host-Rechner über `10.0.2.2` (NAT), nicht über `localhost`. Ein echtes Gerät braucht die LAN-IP des Rechners — und der Rechner muss für das Gerät erreichbar sein (Portfreigabe, s. u.).

## Portfreigabe / Router

- Der Remote-Server lauscht standardmäßig auf **TCP 8999**. Damit ein Handy im WLAN den Rechner erreicht, muss die Firewall/der Router diesen Port zulassen:
  - **Windows-Firewall:** Ausnahmeregel für `node.exe` (oder TCP-Eingangsregel 8999) — am einfachsten für das private Netzwerk.
  - **Router mit Client-Isolation** („AP Isolation"): deaktivieren, sonst sehen sich Geräte im WLAN nicht gegenseitig.
  - **Verschiedene Subnetze/VLANs:** statische Route oder Port-Forwarding auf den Rechner einrichten.
- Schnelltest vom Handy: Browser öffnen → `https://<LAN-IP>:8999/api/health` → erwartet `{"ok":true}` (Zertifikatswarnung ist normal, es geht hier nur um Erreichbarkeit).
- Läuft der Server auf einem anderen Port: in VS-Code „Remote Control" ändern und denselben Port in App + Firewall verwenden.

## Sicherheitshinweise

- **Token geheim halten:** Der Bearer-Token erlaubt vollen Zugriff (Status lesen, Asks beantworten, Modus/Modell wechseln). Er wird im OutputChannel und in den Einstellungen angezeigt — wie ein Passwort behandeln; bei Verlust neu generieren (App dann neu pairen).
- **Self-signed Zertifikat + Pinning:** Die App vertraut exakt dem einen Zertifikat, dessen Fingerprint beim Pairing eingegeben wurde (SHA-256 über das Leaf-Zertifikat, constant-time Vergleich). Ein Man-in-the-Middle mit gültigem öffentlichem Zertifikat scheitert trotzdem, weil der Fingerprint abweicht. **Dafür:** Hostname-Check ist bewusst entspannt (das Zertifikat trägt nur `localhost`/`127.0.0.1` als SAN; die Identität kommt aus dem Pin) — dokumentierter Trade-off.
- **Zertifikat geändert?** → App meldet „Zertifikat geändert — neu pairen?" und erwartet den neuen Fingerprint aus dem OutputChannel (passiert z. B. nach Neugenerierung oder Zertifikat-Reparatur).
- **Kein öffentliches Netz ohne VPN:** Die Verbindung läuft über TLS 1.2+ mit Pinning, aber für unterwegs wird ein VPN empfohlen — besonders weil der Token im Klartext als Header übertragen wird und die App das Zertifikat nur per Fingerprint (nicht per CA) verifiziert.
- **IP-Allowlist optional:** Der Server unterstützt `zoo-code.remote.allowedIps` (leer = alle IPs erlaubt). Für langlaufende Server im geteilten Netz sinnvoll: nur die IP des Handys eintragen.

## Build & Tests

```bat
cd app
gradlew.bat assembleDebug    :: APK → app\build\outputs\apk\debug\
gradlew.bat test             :: JVM-Unit-Tests (API-Parsing)
```

Versionskatalog in `gradle/libs.versions.toml`; `versionName` wird pro Session gebumpt (`0.x.y-sessionN`).

## Fehlerfall-Übersicht (App-Seite)

| Fall | Verhalten |
|------|-----------|
| Host unerreichbar / Timeout (10 s) | „Zeitüberschreitung nach 10 s … Firewall/Portfreigabe prüfen." bzw. „Host unerreichbar" mit Emulator-Hinweis |
| Falscher Fingerprint | „Zertifikat geändert - neu pairen?" mit erwartetem und vorgefundenen Fingerprint |
| Falsches Token (HTTP 401) | Meldung im Screen; bei Aktion auf Status/Modus/Modell springt die App zurück zum Setup **mit** Fehlermeldung |
| Server offline bei offenem Socket | Reconnect-Backoff 1 s → 30 s, Status „Verbinde…"; nach Neustart automatisch verbunden |
| WS-Auth-Schließcode (4002/4003) | Terminaler Fehler: „Falscher Token (WS-Schließcode …)" — Re-Pairing nötig |
