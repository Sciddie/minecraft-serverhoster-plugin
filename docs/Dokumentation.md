# ServerHoster 2.0.1 – Betrieb und Konfiguration

ServerHoster ist ein Spigot-26.3-Plugin für Java 25. Es verwaltet genau einen
separaten Child-Serverprozess und stellt ein Web-Dashboard bereit. Das Plugin
installiert keine Mods oder Plugins, enthält keine Server-Jars und besitzt
keine Ingame-GUI. Das Dashboard ist die administrative Oberfläche.

Für Installation und Upgrade siehe [SETUP.md](SETUP.md) und
[MIGRATION-FABRICLAUNCHER-1.0.0.md](MIGRATION-FABRICLAUNCHER-1.0.0.md).

## Lebenszyklus und Einrichtung

Beim ersten Start legt das Plugin `plugins/ServerhosterPlugin/` an und
verlangt eine Bestätigung im Einrichtungsassistenten, bevor ein Child
automatisch gestartet werden darf. Der Assistent kann einen neuen Server
konfigurieren, einen vorhandenen Child-Ordner einbinden oder unterstützte
Einstellungen ab 1.10.1 importieren. Fehlen alte Plugin-Daten, durchsucht
eine begrenzte, lesende Erkennung direkte Ordner unter dem Spigot-Root nach
üblichen Serverdateien. Mehrdeutige Treffer müssen explizit ausgewählt werden.

Neue Child-Installationen verwenden standardmäßig Vanilla. Unterstützt werden
Vanilla, Fabric, Paper, Spigot und ein eigenes Server-Jar. Typabhängige
Artefakte werden erst bei einem ausdrücklichen Child-Start geladen oder gebaut.
Ein eingebundener Child wird nicht automatisch verändert. Zeitpläne werden
ebenfalls im Dashboard verwaltet und in `config.yml` gespeichert.

## Konfiguration

Der rohe `config.yml`-Editor bleibt verfügbar. Änderungen an Zeitplänen aus
dem Dashboard verwenden denselben YAML-Konfigurationsstand. Die Datei vor
manuellen Änderungen sichern. Fehlerhaftes YAML oder ungültige Werte werden
gemeldet, nicht stillschweigend ersetzt.

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `setup.completed` | Vorlage: `false` | Assistent setzt diesen Wert nach bestätigter Einrichtung. |
| `type` | `vanilla` | `vanilla`, `fabric`, `paper`, `spigot` oder `custom`. |
| `mcVersion` | `26.3` | Minecraft-Version des Childs. |
| `workingDir` | `./child-server` | Child-Ordner, relativ zum Spigot-Arbeitsverzeichnis oder absolut. |
| `port` | `2063` | Spielport des Childs; muss vom Hoster freigegeben sein. |
| `serverIp` | `0.0.0.0` | Bind-Adresse des Childs. |
| `customJar` | `server.jar` | Jar-Pfad bei `type: custom`. |
| `memory.xms` / `memory.xmx` | `2G` / `5G` | Initialer und maximaler Child-Heap. Native Speicheranteile und der äußere Server brauchen zusätzliche Reserve. |
| `jvmFlags` | `-XX:+UseG1GC` | Zusätzliche JVM-Optionen. |
| `serverArgs` | `[]` | Zusätzliche Server-Argumente nach `nogui`. |
| `autoStart` | `true` | Child mit Spigot starten, sobald Einrichtung und Recovery-Schutz es erlauben. |
| `autoRestart.enabled` | `true` | Child nach unerwartetem Prozessende neu starten. |
| `autoRestart.maxAttempts` | `5` | Automatische Wiederholungen pro Fehlerfolge, danach Pause und Warnung. Im Dashboard unter Einstellungen änderbar. |
| `stopTimeoutSeconds` | `60` | Frist für den sauberen Child-Stopp, bevor Fehler gemeldet und der Prozess beendet wird. |
| `startupStopTimeoutSeconds` | `180` | Beim Stop während des Starts auf die Vanilla-Bereitschaftsmeldung warten. |
| `memoryProtection.*` | siehe `config.yml` | Lesendes cgroup-Sampling und Schutz, der nur den Child stoppen kann. |
| `serverProperties` | `{}` | Explizite Child-Overrides; alle übrigen Werte bleiben Minecraft-Defaults. |
| `schedules` | `[]` | Dashboard-Zeitpläne mit Stunde, Tag oder Woche und geordneten Aktionen. |
| `backup.storage` | `local` | Lokale ZIP-Dateien als Standard, FTP/FTPS optional. |
| `backup.paths` | `world` | Zu sichernde Pfade innerhalb des Child-Ordners. |
| `backup.database.enabled` | `false` | Optionale externe JDBC-Metadaten; keine lokale Datenbank. |
| `dashboard.enabled` | `true` | Dashboard aktivieren. |
| `dashboard.port` | `2064` | Dashboard-Port. |
| `dashboard.bind` | `""` | Leer verwendet die Child-Bind-Adresse; bei Bedarf Interface explizit setzen. |
| `dashboard.https.enabled` | `false` | HTTPS optional; Modi: selbstsigniert, Keystore oder PEM. |
| `notifications.ntfy.enabled` | `false` | Optionale ntfy-Benachrichtigungen; Thema muss gesetzt sein. |

Child-`config.yml` und `server.properties` bleiben rohe Textareas. Das Plugin
setzt Child-Port und Bind-Adresse und deaktiviert
`pause-when-empty-seconds`. Es nimmt keine Gameplay-Optimierungen vor und
installiert keine Gameplay-Erweiterungen.

## Dashboard

Das Dashboard-Passwort liegt in
`plugins/ServerhosterPlugin/dashboard-password.txt`. Das Dashboard bietet
Child-Status, Start/Stop/Neustart, Konsole, Spielerverwaltung, Datei- und
Logzugriff, rohe Konfigurationseditoren, Diagnose, Backup-Verwaltung,
Zeitpläne, selten geänderte Einstellungen und Versionsinformationen. Der
Einstellungen-Tab bündelt Restart-Grenzen sowie FTP/FTPS-Verbindungsdaten;
Zugangsdaten bleiben in der lokalen `secrets.yml`.

In nicht vertrauenswürdigen Netzwerken HTTPS verwenden. Unterstützt werden
selbstsignierte Zertifikate, vorhandene PKCS12-/JKS-Keystores und PEM-Dateien.
Bei einem selbstsignierten Zertifikat muss das Zertifikat auf den Clients
vertrauenswürdig gemacht werden. HTTPS beschränkt nicht automatisch den
Dashboard-Zugriff. Das Dashboard ist geschützt zu betreiben, da es
Konsolenbefehle und Child-Dateiverwaltung ermöglicht.

`/child` ist der Befehlsstamm (Berechtigung `serverhoster.admin`, standardmäßig
OP). Verfügbar sind u.a. Start, Stop, Neustart, Status, Konsolenbefehle,
Konfigurations-/Property-Änderungen, Diagnose, Backup, Aufgabenliste und
manuelles Ausführen eines Tasks. Zeitpläne werden primär über das Dashboard
erstellt und bearbeitet.

## Zeitpläne und Backups

Das Dashboard kann Stunden-, Tages- und Wochenpläne erstellen, bearbeiten,
aktivieren/deaktivieren, löschen und manuell ausführen. Jeder Plan enthält
eine oder mehrere geordnete Aktionen: unterstützte Child-Konsolenbefehle und
Backup. Der Scheduler wertet die konfigurierte Zeitzone aus. Die Pläne liegen
in YAML und benötigen keinen Child-Neustart.

Ein geplanter Backup-Task wartet, solange Spieler online sind. Ist der Child
leer, wird er gespeichert und sauber gestoppt, die konfigurierten Pfade aus
`backup.paths` werden archiviert und ein zuvor laufender Child danach wieder
gestartet. Fehler erscheinen im Spigot-Log und zusätzlich über ntfy, wenn
konfiguriert.

Backups laufen nur auf Anforderung, solange kein Zeitplan angelegt wurde.
Lokale ZIP-Dateien sind Standard; FTP/FTPS ist optional. JDBC-Einstellungen
konfigurieren ein externes Metadatenziel. H2 ist nur für Tests vorgesehen und
nicht im Plugin-Jar enthalten.

## Diagnose und Speicherschutz

Diagnose ist lesend. JVM-Informationen werden mit `jcmd` über `Thread.print`,
`GC.heap_info` und `VM.flags` gesammelt. Minecraft-Tick-/MSPT-Daten kommen
vom Vanilla-Kommando `tick query`. Es wird kein Diagnose-Mod installiert.

Der Speicher-Monitor liest Host-/Container- und cgroup-Messwerte, ändert diese
aber nicht. Ein automatischer Child-Notstopp setzt eine bekannte endliche
cgroup-Grenze und mehrere aufeinanderfolgende kritische Messungen voraus.
Fehlende oder unbekannte Grenzen lösen keinen automatischen Stopp aus. Das
Plugin kann warnen, Speichern anfordern, einen Incident-Bericht schreiben und
nur den Child sauber herunterfahren. Der äußere Spigot-Host wird niemals
automatisch beendet. Warnungen und Bericht prüfen; einen Host-Neustart bei
Bedarf im Hosting-Panel des Providers planen.

## Inspiration

Betriebsabläufe sind inspiriert von Pterodactyl Wings und Crafty Controller.
Die MSPT-Anzeige ist von spark inspiriert, nutzt jedoch nur Vanilla `tick
query`; spark wird nicht installiert. Die Paper-Artefaktbehandlung lehnt sich
an PaperMC Fill v3 an. Die `.jar.disabled`-Konvention ist von Prism/Modrinth
bekannt. Spigot-Builds verwenden die offiziellen Spigot BuildTools.
VoxelDash-inspirierte Dashboard-Elemente behalten den nötigen MIT-
Lizenzhinweis im Plugin-Jar und Dashboard.
