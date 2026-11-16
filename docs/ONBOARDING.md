# Einrichtungswege und Entscheidungen

```mermaid
flowchart TD
    A[Erstzugriff: Log-Code prüfen und Passwort setzen; sonst anmelden] --> B{Einrichtung abgeschlossen?}
    B -- Ja --> Z[Dashboard und Host-Verwaltung]
    B -- Nein --> N[Neuer Child in ./child-server: Fabric, Paper oder Custom]
    N --> B1[Backupziel und Pfade wählen]
    B1 -->|extern| B2[FTP/FTPS-Server und Zugangsdaten]
    B1 -->|lokal| B3[Optionalen täglichen Backup-Zeitplan wählen]
    B2 --> B3
    B3 --> O{ntfy gewünscht?}
    O -- Ja --> P[HTTPS-Server und Thema eingeben]
    O -- Nein --> H
    P --> H[Host-Einstellungen]
    H -->|optional| M[Wiederholbare Host-Minimierung auswählen]
    H -->|Upgrade und manuell verfügbar| G[Einmalige Host-Grundeinrichtung mit Warnung und Bestätigung]
    G --> K[Host im Panel neu starten; Onboarding fortsetzen]
    H --> Q[Zusammenfassung prüfen]
    M --> Q
    Q -->|Host minimieren| X[Warnung mit Bestätigung]
    X --> S[Konfiguration speichern]
    Q --> S
    S -->|Minimierung gewählt| J[Minimierung vorbereiten und Host im Panel neu starten]
    S -->|keine Minimierung| Z
    J --> Z
    Z --> U[Host-Minimierung oder manuelle Grundeinrichtung später verwalten]
```

Bei einer Neuinstallation erfolgt die einmalige Host-Grundeinrichtung vor
dem ersten Dashboard-Aufruf automatisch. Bei Upgrades kann sie nur ausdrücklich
ausgelöst werden; erkannte alte Hostwelten werden dabei unwiderruflich gelöscht.
Ein Neustart muss im Hosting-Panel durchgeführt werden. Wird die Einrichtung
während des Onboardings ausgelöst, gehen noch nicht gespeicherte Eingaben verloren.

Nach einem Versionswechsel prüft der Assistent den Ordner aus `workingDir`.
Ist dort ein Child (`server.properties` oder eine JAR-Datei), zeigt er das an
und fragt nur Schritte, deren Angabe in der Konfiguration fehlt. Werte, die
eine Neuinstallation ohne weitere Eingabe setzt, kommen aus der aktuellen
Vorlage. Der gefundene Ordner bleibt. Fehlt keine Angabe, zeigt derselbe
Assistent **Update 2.5.1**.

Ist kein Child gefunden, fragt er nach dem Ordner oder nach einem neuen
Server. Der eingegebene Ordner wird geprüft. Passt er nicht, steht die
Meldung unter dem Eingabefeld, im gleichen Stil wie ein falsches
Dashboard-Passwort. „Neuen Server erstellen“ zeigt danach die Schritte einer
Neuinstallation, inklusive Versionswahl, und verwendet `./child-server`.

Der Assistent lädt keine Server-JARs herunter und startet den Child nicht.
RAM, JVM-Flags, weitere Backup-Zeitpläne und Child-`server.properties` können nach der
Einrichtung im Dashboard angepasst werden; sie sind keine Voraussetzung für
die Einrichtung. Der Serverordner ist `./child-server`.
