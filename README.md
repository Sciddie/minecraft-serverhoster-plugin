# ServerHoster

ServerHoster ist ein Spigot-Plugin, das genau einen separaten Minecraft-
Child-Server startet und ein Web-Dashboard für Einrichtung, Betrieb,
Diagnose, Backups und Zeitpläne bereitstellt. Das Plugin installiert keine
Mods oder Plugins.

## ServerHoster 2.5.0

- Child-Typen sind Fabric, Paper und ein eigenes JAR. Spigot- und
  Vanilla-Downloads entfallen. Der Standard ist `fabric`.
- Backups bleiben lokale ZIPs oder FTP/FTPS. Externe JDBC-Metadaten entfallen.
- `/child` zeigt nur Status und Dashboard-URL. Betrieb läuft über das Dashboard.
- Die Einrichtung scannt den Server-Root nicht mehr und nutzt `./child-server`.
- Upgrade von 2.4.4 durch Austausch des Plugin-Jars. Ein bestehendes
  `type: vanilla` oder `type: spigot` startet danach nicht mehr.

## ServerHoster 2.4.4

- Stabilisierte ProcessProbe-Regression; enthält die Setup-Snapshot- und
  RSS/Thread-Korrekturen aus 2.4.3.
- Tag `v2.4.3` bleibt bestehen, wurde aber nicht als GitHub-Release veröffentlicht.
- Upgrade von 2.4.2 (oder lokalem 2.4.3-Jar) durch Austausch des Plugin-Jars.

## ServerHoster 2.4.3

- Konsistenter Setup-Status (`required`/`completed`) bei gleichzeitigem
  Abschluss der Einrichtung.
- Dashboard-Status nutzt gemeinsame RSS-/Thread-Messung; CPU bleibt separat.
- Tag vorhanden, Release-Workflow fehlgeschlagen; bitte 2.4.4 verwenden.

## ServerHoster 2.4.2

- Dashboard intern bereinigt; die bestehenden Web-Funktionen bleiben erhalten.
- Upgrade von 2.4.1 durch Austausch des Plugin-Jars.

## ServerHoster 2.4.1

- Ausführungsstatus von Zeitplänen (laufen, warten, Fehler) bleibt im
  Dashboard sichtbar.
- Relative Custom-JAR-Pfade werden aus dem Child-Arbeitsverzeichnis aufgelöst.
- Upgrade von 2.4.0 durch Austausch des Plugin-Jars.

## ServerHoster 2.4.0

- Upgrade von 2.3.2 bleibt der Austausch des Jars im Ordner
  `plugins/ServerhosterPlugin/`.
- Die alte Aufgabenliste entfällt. Zeitpläne im Dashboard bleiben.
- Ein neues Setup liest nur `memoryProtection.autoStopChild`.

## ServerHoster 2.3.2

- Dashboard-Passwort beim ersten Zugriff mit einmaligem Log-Code festlegen.
- Backupziel, FTP/FTPS-Zugangsdaten und Zeitplan direkt im Onboarding einrichten.
- Verständlichere Diagnose und verbessertes Zeitplan-Layout.

## ServerHoster 2.2.0

- Situationsabhängiges Onboarding für neue und vorhandene Child-Server.
- Verbesserte Dashboard-Anzeige und zuverlässigere lokale und externe Backups.

## ServerHoster 2.1.0

- Einmalige Host-Grundeinrichtung mit Void-Lobby: automatisch bei einer
  Neuinstallation, bei bestehenden Installationen nur nach Bestätigung.
  Der Host muss einmal neu gestartet werden, bevor alte Hostwelten entfernt
  werden. Child-Daten, Konfiguration und Backups bleiben erhalten.
- Der Speicherschutz kann nach abgeschlossener Wiederherstellung erneut
  eingreifen.

## ServerHoster 2.0.3

- Optionale Minimierung des äußeren Spigots mit Vorschau, Dateisicherung und
  Rückrollen. Die wiederholbare Minimierung verändert keine Welten.

## ServerHoster 2.0.2

- Child-Prozessidentität mit PID und Startzeitpunkt; ein wiederverwendeter PID
  wird nicht beendet.
- Überwachung von Konsole und Exit-Watcher, Aufräumen nach fehlgeschlagenem
  Start, und Speichermessung, die Heap, RSS, Host und cgroup getrennt hält.
- Konfigurierte Heap-Werte, Welten, Sichtweiten und `pause-when-empty-seconds: 0`
  bleiben unverändert.

## ServerHoster 2.0.1

- Genau ein Child-Server; keine Profile und kein Profilwechsel.
- Einrichtungsassistent für neue Server, vorhandene Child-Ordner und
  unterstützte Migrationen von ServerHoster 1.10.1+.
- Dashboard-Zeitpläne mit Stunden-, Tages- oder Wochenintervall, geordneten
  Befehls- und Backup-Aktionen; Speicherung in `config.yml`, nicht in einer DB.
- Optionale lokale oder FTP/FTPS-Backups, externe JDBC-Metadaten und ntfy.
- Lesende Prozess- und Hostdiagnose, darunter `jcmd` und das Vanilla-Kommando
  `tick query`. Kritischer Speicherschutz kann den Child sauber stoppen, nie
  automatisch den äußeren Spigot-Host.
- Rohe Texteditoren für `config.yml` und die Child-`server.properties`.
- Optionales Dashboard-HTTPS mit selbstsigniertem Zertifikat, Keystore oder
  PEM-Dateien.
- Automatische Child-Neustarts pausieren standardmäßig nach fünf erfolglosen
  Wiederholungen; Grenzwert und Backoff sind im Einstellungen-Tab änderbar.
- Der Einstellungen-Tab bündelt selten geänderte Restart- und FTP/FTPS-Daten.

Siehe [Einrichtung](docs/SETUP.md), [Migration](docs/MIGRATION-FABRICLAUNCHER-1.0.0.md)
und [Betriebsdokumentation](docs/Dokumentation.md).

## Build

Build mit JDK 25 und Maven. Das React-/TypeScript-Dashboard wird im Maven-
Build mit Node.js 22 erzeugt.

```powershell
mvn clean verify
```

Das Plugin-Jar ist `target/ServerHoster-2.5.0.jar`. Spigot API 26.3 ist eine
`provided`-Abhängigkeit des äußeren Hosts. Es gibt keine lokale Datenbank und
keine mitgelieferten Server-Jars. Fabric- und Paper-Artefakte werden beim
Child-Start geladen; ein eigenes JAR bleibt lokal.

## Inspiration und Lizenzen

Die Betriebsabläufe sind inspiriert von Pterodactyl Wings und Crafty
Controller. Die MSPT-Anzeige ist von spark inspiriert, nutzt aber ausschließlich
das Vanilla-Kommando `tick query`; spark wird nicht installiert. Die
Paper-Artefaktbehandlung orientiert sich an PaperMC Fill v3. Die
`.jar.disabled`-Konvention ist von Prism/Modrinth bekannt. Fabric nutzt die
Meta-API, Paper die Fill-API v3.

Dashboard-Elemente sind an VoxelDash angelehnt. Der erforderliche MIT-
Lizenzhinweis ist im Plugin-Jar enthalten und über das Dashboard erreichbar.

**Minecraft-EULA:** Betreiber müssen die jeweils gültigen Minecraft-
EULA-Bedingungen und sonstigen anwendbaren Bedingungen selbst beachten.
