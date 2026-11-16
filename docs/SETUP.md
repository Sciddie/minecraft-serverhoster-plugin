# ServerHoster einrichten

Diese Anleitung beschreibt eine neue Installation. Der Einrichtungsassistent
legt den Child in `./child-server` an und durchsucht den Server-Root nicht.
Hinweise zum Upgrade stehen in
[UPGRADE.md](UPGRADE.md).

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
geladen, wenn sie noch fehlen.

## Installation

1. `ServerHoster-2.5.0.jar` in das `plugins`-Verzeichnis des Hosters laden.
   Keine Projektquellen, Server-Jars oder Mods hochladen.
2. Spigot starten. Bei einer Neuinstallation ohne vorhandene
   ServerHoster-Daten führt das Plugin die einmalige Host-Grundeinrichtung
   automatisch in mehreren Schritten aus: Es bereitet eine neue Void-Lobby vor.
   Andere Plugin-JARs
   werden in-place mit der Endung `.disabled` umbenannt, damit sie nicht
   geladen werden. Ihre Dateien und Konfigurationen bleiben erhalten; nach
   dem anschließenden Panel-Neustart wird nur ServerHoster geladen. Erst wenn
   die Void-Lobby tatsächlich geladen ist, entfernt das Plugin erkannte alte
   äußere Minecraft-Weltordner. Der Host fährt nach der Vorbereitung herunter;
   starte ihn im Hosting-Panel erneut. Child-Server,
   ServerHoster-Konfiguration, Dashboard-Passwort und Backups bleiben erhalten.
   Bei einem Fehler wird der Schritt beim nächsten Start wiederholt. Das Plugin
   legt außerdem `plugins/ServerhosterPlugin/` an und startet das Dashboard.
3. Das Dashboard über den konfigurierten Port öffnen. Beim ersten Zugriff den
   einmaligen Einrichtungscode aus dem aktuellen Host-Log eingeben und ein
   Passwort festlegen oder sicher erzeugen lassen. Der Code ändert sich bei
   jedem Host-Neustart und erlischt nach erfolgreicher Passwortvergabe. Das
   Passwort wird lokal in `dashboard-password.txt` gespeichert und nicht
   im Log ausgegeben. Vor der Passwortvergabe sind Verwaltungsfunktionen
   gesperrt. Bestehende Dashboard-Passwörter bleiben unverändert. Solange
   die Einrichtung nicht abgeschlossen ist, öffnet sich das Onboarding
   automatisch. Der Child-Ordner ist `./child-server`. Der Assistent fragt
   nach Backupziel, zu sichernden Pfaden, optionalem Backup-Zeitplan, ntfy
   und der Host-Minimierung und zeigt vor dem Speichern
   eine Zusammenfassung. Vor der Bestätigung startet kein Child automatisch.
   Eine Übersicht enthält [ONBOARDING.md](ONBOARDING.md).
4. Typ, Minecraft-Version und Spielport wählen. Unterstützt werden Fabric,
   Paper und ein eigenes Server-Jar. Für Fabric und Paper lädt die Auswahl die
   verfügbaren Versionen vom jeweiligen Anbieter; bei einem Verbindungsfehler
   kann sie erneut geladen werden. Für eigene JARs wird die Version manuell
   angegeben. Beim Bestätigen des Assistenten wird noch kein Server-Jar
   heruntergeladen.
5. Den Child ausdrücklich über das Dashboard starten. Die Konsole beobachten
   und die Bereitschaftsmeldung abwarten. `/child` zeigt nur Status und
   Dashboard-URL.

Bei einem Upgrade mit vorhandenen ServerHoster-Daten wird diese aggressive
Host-Grundeinrichtung **nicht automatisch** ausgeführt. Sie kann im Dashboard
unter „Einmalige Host-Grundeinrichtung“ ausdrücklich gestartet werden. Dabei
werden die erkannten äußeren Weltordner unwiderruflich gelöscht; bestätige sie
nur, wenn das gewünscht ist. Der Host fährt nach der Bestätigung herunter.
Starte ihn im Panel: Er bereitet die neue Lobby vor und fährt erneut herunter.
Nach einem weiteren Panel-Start wird die geladene Lobby geprüft und erst dann
werden die alten äußeren Welten entfernt.

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
- Lokale ZIP-Dateien sind Standard. FTP/FTPS ist optional. Nach fünf Stunden
  Wartezeit auf einen leeren Server kommt eine einmalige ntfy-Warnung; das
  Backup wartet weiter.
  Die temporäre Minecraft-Datei `session.lock` einer Welt wird nicht archiviert;
  sie wird beim nächsten Serverstart neu angelegt.
- Backupziel, FTP-Benutzername und Passwort, Pfade und Aufbewahrung lassen
  sich im Onboarding und unter „Einstellungen“ ändern. Ein gespeichertes
  Passwort wird nicht erneut angezeigt; ein leeres Passwortfeld behält es.
  Zugangsdaten werden in `secrets.yml` gespeichert. Dashboard-HTTP und
  unverschlüsseltes FTP übertragen Zugangsdaten ungeschützt; FTPS und
  Dashboard-HTTPS sind empfohlen. AUTO kann auf FTP zurückfallen.
- Das Testen des Backupziels im Onboarding speichert die Backup-Einstellungen
  bereits vor Abschluss der übrigen Einrichtung. Danach ist der Zieltest unter
  „Backups“ verfügbar.
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
sauber stoppen. Der Speicherschutz beendet den äußeren Spigot-Host nicht.
Unbekannte oder unzuverlässige Grenzwerte lösen keinen automatischen Stopp
aus. Wenn der Child nicht sauber endet, wird der Fehler protokolliert und nach
Ablauf des konfigurierten Timeouts kann der Prozess beendet werden.

Den äußeren Server über das Hosting-Panel oder die Spigot-Konsole stoppen.
Periodische Host-Neustarts gehören in den Zeitplan des Hosting-Providers,
nicht in ServerHoster.

## Host minimieren

Die einmalige Void-Lobby-Einrichtung und das Umbenennen anderer Plugin-JARs
geschehen separat beim ersten Start dieser Plugin-Version. Die
wiederholbare Minimierung verändert keine Weltordner, keine Plugin-Dateien,
keinen `level-name` und keinen Weltgenerator. Sie reduziert
Sicht-/Simulationsdistanz und Spawns, deaktiviert Nether/End und aktiviert eine
leere Host-Whitelist. Child, Welten, Plugin-Dateien und Backups bleiben
erhalten; Port, Online-Mode und ein ausgeschaltetes RCON bleiben unverändert.
`-Xmx` wird nicht gesenkt. Die Child-Whitelist wird nicht geändert.

Vorschau, Anwenden und Zurückholen laufen im Dashboard unter Einrichtung.
Der äußere Server wird dabei nicht heruntergefahren; den nötigen Neustart
startest du im Hosting-Panel. Zurückholen stellt die gesicherten Host-Dateien
einschließlich der vorherigen `whitelist.json` wieder her. Das Dashboard bleibt
bei HTTP, bis HTTPS in der Konfiguration eingeschaltet wird. HTTP überträgt
das Dashboard-Passwort unverschlüsselt. Port und Zertifikat werden durch die
Minimierung nicht geändert.

## Aus dem Quellcode bauen

```powershell
mvn clean verify
```

Erforderlich sind JDK 25 und Maven. Maven baut das Dashboard und packt es in
das Plugin-Jar.
