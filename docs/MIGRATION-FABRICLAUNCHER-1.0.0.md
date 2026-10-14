# Migration von FabricLauncher 1.0.0 zu ServerHoster

Diese Anleitung beschreibt ein datenbewahrendes Upgrade eines laufenden Servers mit dem alten Plugin **FabricLauncher 1.0.0** aus [Sciddie/minecraft-fabric-launcher](https://github.com/Sciddie/minecraft-fabric-launcher) auf eine stabile Version von **ServerHoster** aus [Sciddie/minecraft-serverhoster-plugin](https://github.com/Sciddie/minecraft-serverhoster-plugin/releases/latest).

Der wichtigste Punkt: Das Plugin wurde unter demselben Namen und Java-Paket weiterentwickelt. Der Datenordner bleibt daher normalerweise `plugins/FabricLauncher/`. Das Child-Verzeichnis mit der Welt ist separat und darf beim Plugin-Upgrade nicht gelöscht oder neu angelegt werden.

> **Stand dieses Leitfadens:** Die neueste veröffentlichte stabile Version ist ServerHoster 1.8.0. Lade auf der Releases-Seite immer das neueste **nicht als Pre-release markierte** Jar herunter. Das Beispiel `ServerHoster-X.Y.Z.jar` steht für den konkreten Versionsnamen des Downloads.

## Was erhalten bleiben muss

Vor und nach dem Upgrade müssen diese Daten an ihrem bisherigen Ort bleiben:

| Daten | Typischer Pfad | Wichtig |
|---|---|---|
| Child-Welt(en) | `child-server/world/`, `child-server/world_nether/`, `child-server/world_the_end/` | Nicht löschen, verschieben oder durch eine leere Welt ersetzen. Weitere Weltordner ebenfalls behalten. |
| Child-Konfiguration | `child-server/server.properties`, `child-server/eula.txt` | Nicht durch eine Beispieldatei ersetzen. ServerHoster verwaltet einzelne erzwungene Properties aus der Plugin-Konfiguration. |
| Spieler- und Bannlisten | `child-server/ops.json`, `whitelist.json`, `banned-players.json`, `banned-ips.json`, `usercache.json` | Bestehende Child-Dateien behalten. |
| Mods und Marker | `child-server/mods/` und vorhandene `.fl-*`-Marker | Nicht löschen; Marker steuern, ob Standard-Mods erneut geladen werden. |
| Launcher-Konfiguration | `plugins/FabricLauncher/config.yml` | Behalten und gezielt prüfen/anpassen; nicht durch die Datei aus einem neuen Jar ersetzen. |
| Dashboard-Zugang und TLS | `plugins/FabricLauncher/dashboard-password.txt`, `dashboard.p12`, `dashboard.crt`, `dashboard.key` (falls vorhanden) | Behalten. Dadurch bleiben Zugang und konfiguriertes HTTPS-Material erhalten. |
| Äußerer Spigot und Lobby | Spigot-Root, insbesondere `server.properties`, `bukkit.yml`, `spigot.yml`, Lobby-Welt und `.fl-bak`-Dateien | Nicht zurücksetzen. Der äußere Server ist nicht die Child-Spielwelt. |

Bei `workingDir: ./child-server` liegt das Child-Verzeichnis relativ zum Spigot-Arbeitsverzeichnis im Root. Wenn deine alte `config.yml` einen anderen `workingDir` enthält, ist **dieser tatsächliche Pfad** maßgeblich.

## 1. Vor dem Upgrade sichern

1. Kündige den Wartungsstopp an und notiere:
   - die Spieladresse und den bisherigen Child-Port (`port`);
   - den Dashboard-Port und die Dashboard-Adresse;
   - den Wert von `workingDir`, `mcVersion`, `serverIp` und die verwendeten Mods;
   - ob Spieler zuletzt im Nether/End oder in zusätzlichen Welten waren.
2. Erstelle über den bisherigen Backup-Weg ein aktuelles Backup. Verlasse dich nicht ausschließlich auf eine Sicherung auf derselben Festplatte.
3. Beende den Child sauber über `/child stop` oder die Plugin-Konsole. Warte im Spigot-Log auf die Bestätigung, dass der Child sauber beendet wurde. Bei Dashboard-Steuerung zunächst verifizieren, dass der Prozess wirklich aus ist.
4. Beende anschließend den äußeren Spigot sauber über dessen Konsole/Panel (`stop`) und warte, bis er vollständig heruntergefahren ist. Lade keine Plugin-Dateien hoch, während Spigot läuft.
5. Lade per FTP/SFTP mindestens das gesamte Child-Verzeichnis, `plugins/FabricLauncher/`, die äußeren Spigot-Konfigurationsdateien und die Lobby-Welt auf deinen Rechner herunter. Prüfe, dass die Übertragung abgeschlossen ist und die Dateien/Verzeichnisse sichtbar sind. Bewahre eine unveränderte Kopie dieses Backups auf.

**Nicht fortfahren**, wenn der tatsächliche Child-Weltpfad unklar ist oder die externe Sicherung unvollständig ist.

## 2. Nur das Plugin-Jar austauschen

1. Öffne `<Spigot-Root>/plugins/`.
2. Entferne oder verschiebe ausschließlich das alte `FabricLauncher-1.0.0.jar` aus diesem Plugin-Ordner. Bewahre es außerhalb von `plugins/` auf, damit Bukkit nicht gleichzeitig das alte und das neue Jar lädt.
3. Lade das neueste stabile `ServerHoster-X.Y.Z.jar` in `<Spigot-Root>/plugins/`.
4. **Lösche nicht** `plugins/FabricLauncher/`, `child-server/`, Weltordner, Mods, `.fl-*`-Marker oder äußere Spigot-Dateien. Lege auch kein zweites leeres `child-server/` an.
5. Installiere WorldPorter nicht erneut und starte keine einmalige Weltmigration erneut. Eine bereits eingerichtete Lobby soll unverändert bleiben. Falls WorldPorter noch installiert ist, ändere dessen Dateien nicht während dieses Updates; entferne das WorldPorter-Jar erst nach Prüfung der vorhandenen `done.marker` und der bereits eingerichteten Spigot-Dateien.

## 3. Bestehende Konfiguration prüfen

Behalte `plugins/FabricLauncher/config.yml`. Prüfe besonders, dass die Werte weiterhin auf die vorhandene Installation zeigen:

- `workingDir`: bisheriger Child-Ordner;
- `type: fabric` und `mcVersion`: dieselbe Serverversion wie vor dem Upgrade;
- `port` und `serverIp`: Child-Port und Bind-Adresse, die im Hosting-Panel freigegeben sind;
- `mods`: die gewünschten bisherigen Standard-Mods. Lithium und FerriteCore bleiben die vorgesehenen Defaults; das Upgrade fügt keine Gameplay-Mods hinzu;
- `serverProperties`: nur zusätzliche explizite Overrides; neue Child-Installationen behalten sonst die Minecraft-Defaults;
- `dashboard.enabled`, `dashboard.port`, `dashboard.bind` und HTTPS-Einstellungen.

Die Child-`server.properties` bleibt eine normale Textdatei. Der Launcher erzwingt beim Start Child-Port, IP und `pause-when-empty-seconds=0`; zusätzlich schreibt er nur explizit in `serverProperties` konfigurierte Schlüssel. Andere vorhandene Schlüssel bleiben bestehen. Falls du einzelne Einstellungen bisher direkt in `child-server/server.properties` geändert hast, prüfe, dass sie nicht zugleich durch abweichende Werte in `config.yml` überschrieben werden.

### Speichereinstellungen und Versionsunterschiede

Die Konfiguration des alten Plugins verwendet standardmäßig `memory.xms: 8G` und `memory.xmx: 8G` sowie eine längere Liste an G1-Tuning-Flags. Die aktuellen ServerHoster-Defaults sind konservativer (`Xms2G`, `Xmx5G`, `-XX:+UseG1GC`).

- Bei der aktuell veröffentlichten **1.8.0** wird eine bereits vorhandene `config.yml` nicht automatisch auf die neuen Defaults umgeschrieben. Nach der Sicherung kannst du gezielt `memory.xms` auf `2G` ändern und `memory.xmx` auf `8G` belassen. Prüfe `jvmFlags` und entferne alte manuelle G1-Tuning-Flags, wenn du die konservativen Java-25-Defaults verwenden möchtest.
- Beim ersten Start mit Speicherrichtlinie 3 werden die Root-Werte für Heap, JVM-Flags und Speicherschutz einmalig auf das aktuelle Profil (2G/5G) gesetzt. Andere Konfigurationsschlüssel und `servers.<id>`-Profil-Overrides bleiben erhalten. Die YAML-Datei kann beim Speichern neu formatiert werden; sichere daher vorher unbedingt die Originaldatei.
- Die Änderung von `Xms`/`Xmx` verändert keine Weltdateien. `Xmx` ist eine Heap-Obergrenze und kein sofort belegter Speicher. Ändere `mcVersion` nicht im Rahmen des Plugin-Upgrades.

Die Dateien `dashboard-password.txt` und vorhandene Keystore-/PEM-Dateien bleiben außerhalb von `config.yml` erhalten. Setze bei der Migration kein neues Dashboard-Passwort und überschreibe kein TLS-Material, solange du Zugang und HTTPS beibehalten möchtest.

## 4. Erster Start und Funktionsprüfung

1. Starte den äußeren Spigot über das Hosting-Panel. Beobachte das Spigot-Log ab dem Plugin-Enable.
2. Prüfe, dass nur das neue Plugin geladen wird und das Log keine YAML-, Port-, Berechtigungs- oder Child-Startfehler meldet.
3. Warte, bis der Child dieselbe bisherige Minecraft-Version meldet und `Done (...)` ausgibt. Beim vorhandenen Fabric-Jar und vorhandenen Mods sollte kein Neuaufbau der Welt erforderlich sein. Unterbrich den ersten Start nicht während Datei- oder Weltzugriffen.
4. Prüfe im Dashboard Status, Child-PID, Port und Speicheranzeige. Verbinde dich mit derselben Child-Adresse und demselben Port wie zuvor.
5. Verifiziere mit einem Testspieler:
   - erwartete Welt und Position;
   - Inventar, Enderchest, XP und Fortschritte;
   - Nether und End;
   - Whitelist/OP-Status und vorhandene Mods.
6. Prüfe, dass der äußere Spigot weiterhin nur die Lobby hostet und niemand versehentlich auf dessen Port statt auf den Child-Port spielt.
7. Behalte das externe Backup mindestens so lange, bis mehrere saubere Starts und Backups erfolgt sind.

### Speicher-Notfallschutz in Releases mit `memoryProtection`

ServerHoster ab 1.8.0 kann cgroup-Speicherwerte nur lesend überwachen. Bei anhaltend kritischem Druck auf ein bekanntes endliches cgroup-Limit kann das Plugin Spieler warnen, den Child sauber stoppen und anschließend Spigot herunterfahren. Das setzt voraus, dass der Hoster den äußeren Server nach dem Stopp automatisch wieder startet. Nach dem Neustart wartet der Child auf Cooldown und stabile Speicherwerte. Unbekannte oder unbegrenzte cgroup-Limits lösen keinen automatischen Host-Shutdown aus.

Ein späteres Release mit Incident-Berichten speichert diese lokal unter `plugins/FabricLauncher/memory-reports/`; sie werden nicht automatisch hochgeladen. Das Dashboard zeigt den zuletzt verfügbaren Berichtspfad an. Lade Berichte bei Bedarf selbst per FTP herunter.

## 5. Rückweg bei Problemen

1. Stoppe zuerst den Child sauber und dann den äußeren Spigot.
2. Wenn nur das Plugin inkompatibel ist, entferne das neue Jar aus `plugins/` und lege das aufbewahrte `FabricLauncher-1.0.0.jar` zurück. Lass `child-server/` und alle Weltdateien unangetastet.
3. Wenn du eine Konfigurationsänderung rückgängig machen musst, stelle ausschließlich die gesicherte `plugins/FabricLauncher/config.yml` wieder her. Passwortdatei und HTTPS-Dateien nicht durch leere Dateien ersetzen.
4. Stelle ein Welt-Backup nur wieder her, wenn die Welt nachweislich beschädigt wurde. Niemals ein älteres Welt-Backup mit einem neueren `mcVersion` öffnen und dann mit der älteren Version weiterspielen; Minecraft-Weltformate sind nicht zuverlässig rückwärtskompatibel.
5. Starte den Server erst wieder, nachdem du anhand des Logs bestätigt hast, dass der Child denselben Weltordner verwendet wie vor dem Upgrade.

## Häufige Fehler vermeiden

- Das alte und das neue Plugin-Jar gleichzeitig in `plugins/` lassen.
- Den Plugin-Datenordner löschen, weil sein Name weiterhin `FabricLauncher` lautet.
- `child-server/` aus einem neuen Setup kopieren oder neu anlegen und damit auf die leere Welt zeigen.
- Die neue Standard-`config.yml` über die alte kopieren, ohne `workingDir`, Port, HTTPS und `serverProperties` zu prüfen.
- Für das Plugin-Upgrade gleichzeitig Minecraft-, Fabric-Loader- oder Mod-Versionen aktualisieren.
- `.fl-*`-Marker oder Dateien unter `child-server/mods/` löschen, obwohl kein Mod-Update beabsichtigt ist.
- Die Lobby-Konfiguration erneut migrieren oder den äußeren Spigot-Port auf den Child-Port ändern.
