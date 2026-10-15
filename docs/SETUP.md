# ServerHoster einrichten

Diese Anleitung beschreibt eine neue Installation. Erkennt ServerHoster
vorhandene Child-Daten oder alte Plugin-Dateien, verwende den
[Einrichtungsassistenten](MIGRATION-FABRICLAUNCHER-1.0.0.md).

## Voraussetzungen

- Spigot API 26.3 mit Java 25.
- Je ein freier, von außen erreichbarer Port für Child und Dashboard. Die
  Beispiele sind `2063` und `2064`; verwende die Ports deines Hosters.
- Ausgehendes HTTPS für Server-Artefakte und Versionsinformationen.
- Genug Speicher für beide JVMs, nativen Speicher und andere Container-
  Prozesse. Der Child-Standard ist `Xms2G` / `Xmx5G`. Ein konfiguriertes
  Spigot-`Xmx` ist nur eine Obergrenze; beide JVMs teilen sich das Container-
  Limit.

ServerHoster verwaltet genau einen Child. Mods und Plugins werden nicht
vorinstalliert. Server-Artefakte werden nur bei einem expliziten Child-Start
geladen oder gebaut, wenn sie noch fehlen.

## Installation

1. `ServerHoster-2.0.1.jar` in das `plugins`-Verzeichnis des Hosters laden.
   Keine Projektquellen, Server-Jars, Mods oder Datenbankdateien hochladen.
2. Spigot starten. Das Plugin legt `plugins/ServerhosterPlugin/` an und
   startet das Dashboard. Beim ersten Start wird das Dashboard-Passwort in
   `dashboard-password.txt` gespeichert und im Log ausgegeben.
3. Das Dashboard über den konfigurierten Port öffnen und den Assistenten
   abschließen. Vor der Bestätigung startet kein Child automatisch.
4. Für einen neuen Server Typ, Minecraft-Version, Child-Ordner und Spielport
   wählen. Unterstützt werden Vanilla, Fabric, Paper, Spigot und ein eigenes
   Server-Jar. Beim Bestätigen des Assistenten wird noch kein Server-Jar
   heruntergeladen.
5. Den Child ausdrücklich über das Dashboard oder `/child start` starten.
   Konsole beobachten und die Vanilla-Bereitschaftsmeldung abwarten.

Ein vorhandener Child-Ordner kann eingebunden werden, ohne seine Dateien zu
ändern. Typ, Version und Port müssen zur Installation passen. **Archivieren
und neu einrichten** verschiebt den bisherigen Ordner in ein zeitgestempeltes
Nachbarverzeichnis und bereitet am ursprünglichen Pfad einen neuen Ordner vor.
Der archivierte Child wird nicht gelöscht.

## Dashboard und HTTPS

Das Dashboard verwendet HTTP, bis HTTPS konfiguriert wurde. Zum Aktivieren
`dashboard.https` in `plugins/ServerhosterPlugin/config.yml` setzen und
Spigot neu starten:

- `self-signed`: lokales, selbstsigniertes Zertifikat erzeugen.
- `keystore`: vorhandenen PKCS12-/JKS-Keystore verwenden.
- `pem`: Zertifikat und privaten PEM-Schlüssel verwenden.

Auf nicht vertrauenswürdigen Netzwerken HTTPS verwenden. Das Dashboard kann
Konsolenbefehle ausführen und Child-Dateien verwalten. Keystores, private
Schlüssel, Secrets und Passwortdateien gehören nicht in die Versionsverwaltung.

## Backups, Zeitpläne und Benachrichtigungen

- Backups laufen nur auf Anforderung oder über einen eingerichteten
  Dashboard-Zeitplan. Geplante Backups warten, bis keine Spieler online sind,
  stoppen den Child sauber, archivieren die konfigurierten Pfade und starten
  ihn danach wieder, falls er vorher lief.
- Lokale ZIP-Dateien sind Standard. FTP/FTPS ist optional. JDBC bezeichnet
  ausschließlich externe Backup-Metadaten; es wird keine Datenbank installiert.
- Stunden-, Tages- und Wochenpläne unterstützen geordnete Befehls- und
  Backup-Aktionen. Die Pläne werden in `config.yml` gespeichert.
- ntfy ist optional und bleibt deaktiviert, solange es nicht ausdrücklich
  eingerichtet wird.

`config.yml` und die Child-`server.properties` bleiben im Dashboard rohe
Texteditoren. Zugangsdaten sollten nicht unbedacht in diesen Dateien abgelegt
werden.

## Speicherschutz und Herunterfahren

Host-/cgroup-Messwerte werden nur gelesen. Bei anhaltend kritischen Messwerten
mit bekannter, endlicher cgroup-Grenze kann ServerHoster warnen und den Child
sauber stoppen. Der äußere Spigot-Host wird niemals automatisch beendet.
Unbekannte oder unzuverlässige Grenzwerte lösen keinen automatischen Stopp
aus. Wenn der Child nicht sauber endet, wird der Fehler protokolliert und nach
Ablauf des konfigurierten Timeouts kann der Prozess beendet werden.

Den äußeren Server über das Hosting-Panel oder die Spigot-Konsole stoppen.
Periodische Host-Neustarts gehören in den Zeitplan des Hosting-Providers,
nicht in ServerHoster.

## Aus dem Quellcode bauen

```powershell
mvn clean verify
```

Erforderlich sind JDK 25 und Maven. Maven baut das Dashboard und packt es in
das Plugin-Jar. H2 ist nur für Tests vorgesehen und wird nicht mitgeliefert.
