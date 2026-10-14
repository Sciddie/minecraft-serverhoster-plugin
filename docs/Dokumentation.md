# ServerHoster – Dokumentation

*ServerHoster 1.10.0 · Spigot 26.3 / Java 25 · Zap-Hosting-Server `g266459`*

Diese Linie wird in [minecraft-serverhoster-plugin](https://github.com/Sciddie/minecraft-serverhoster-plugin) entwickelt. Das Release-Jar heißt `ServerHoster-1.10.0.jar`. Der Spigot-Name bleibt FabricLauncher, der Datenordner `plugins/FabricLauncher/`.

**Neu aufsetzen:** Schritt für Schritt auf einem neuen Spigot-Server steht in [SETUP.md](SETUP.md).

## 1. Zweck und Architektur

Zap-Hosting bietet für dieses Paket nur Spigot an. **ServerHoster** ist das Spigot-Plugin, das daraus einen Wirt macht. Normal ist ein Child gleichzeitig (`allowParallel: false`).

- Der **äußere Spigot-Server** (Port 2060) ist eine leere, minimierte Lobby (`lobby_empty`, Void-Flachwelt, Whitelist an und leer). Er existiert nur, damit Zap etwas starten kann.
- FabricLauncher startet beim Enable als **eigenen Prozess (Child)** einen **Fabric-Server** im Ordner `child-server/` auf **Port 2063**. Dort wird tatsächlich gespielt (Welt `child-server/world`).
- Ausgabe des Child landet mit Präfix `[Child]` in der Spigot-Konsole/-Log; Befehle gehen per stdin an den Child.
- Ein eingebautes **Web-Dashboard** (Port 2064) zeigt Status, Ressourcen, Konsole, Spieler, Dateien, Logs und Konfiguration.
- Die Dashboard-Oberfläche ist VoxelDash-inspiriert; alle Messreihen und Aktionen kommen weiterhin aus ServerHoster. Kurzzeitdiagramme werden nur im Browser gehalten. Der MIT-Hinweis ist im Jar enthalten und im Dashboard verlinkt.
- Ein täglicher Backup-Task um 05:00 Uhr lokaler Serverzeit ist aktiv. Standardziel ist lokal; FTP/FTPS-Remote-Speicherung muss ausdrücklich konfiguriert werden. ntfy-Benachrichtigungen sind optional und standardmäßig deaktiviert.
- **WorldPorter** (optional, nur der erste Start) drosselt den äußeren Spigot, setzt die Void-Lobby und verschiebt die Welt. Danach löschen.

```
Zap-Container (4 Kerne)
└─ Spigot-JVM (Lobby, ~0,5 GB RSS)            Port 2060 (Spiel), 2061 (Query), 2062 (RCON)
   └─ FabricLauncher-Plugin
      ├─ Dashboard (HTTP)                       Port 2064
      └─ Child-Prozess: java -Xmx5G -jar fabric-server-launch-26.3.jar   Port 2063
```

## 2. Ports

| Port | Dienst | Hinweis |
|---|---|---|
| 2060 | Spigot (Lobby) | Whitelist an, niemand soll hier joinen |
| 2061 | Query des Spigot | für Zap-Statusanzeige, aktiv gelassen |
| 2062 | RCON des Spigot | Passwort in `server.properties` / `deploy\rcon.txt` auf FlosPC |
| **2063** | **Fabric-Child – hier spielen** | `<SERVER-IP>:2063` |
| 2064 | Web-Dashboard | `http://<SERVER-IP>:2064/` |

## 3. Installation

Die ausführliche Ersteinrichtung (Voraussetzungen, FTP, erster Start, `config.yml`, Weltumzug, Dashboard) steht in [SETUP.md](SETUP.md). Kurzfassung:

1. `ServerHoster-1.10.0.jar` und für den ersten Start `WorldPorter-1.4.0.jar` nach `plugins/` hochladen (FTP).
2. Spigot starten. Beim ersten Start:
   - `plugins/FabricLauncher/config.yml` wird erzeugt,
   - Dashboard-Passwort wird erzeugt (`plugins/FabricLauncher/dashboard-password.txt`, einmalig im Log),
   - Fabric-Server-Jar und Mods (Modrinth) werden nach `child-server/` geladen, `eula.txt` wird gesetzt.
3. Sind beim ersten Anlegen von `child-server/` im Spigot-Root `ops.json`, `whitelist.json`, `banned-*.json`, `usercache.json` vorhanden, werden sie kopiert.

## 4. config.yml (Schlüssel und Standardwerte)

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `type` | `fabric` | `fabric` \| `paper` \| `vanilla` \| `custom` |
| `mcVersion` | `"26.3"` | Minecraft-Version des Child |
| `port` | `2063` | Port des Child (wird in dessen server.properties erzwungen) |
| `serverIp` | `"<SERVER-IP>"` | Bind-Adresse des Child (`""` = alle) |
| `customJar` | `"server.jar"` | nur bei `type: custom` |
| `fabricLoaderVersion` / `fabricInstallerVersion` | `latest` | neueste stabile Version |
| `workingDir` | `./child-server` | Ordner des Child |
| `memory.xms` / `memory.xmx` | `2G` / `5G` | Startheap / Maximalheap; bei engem cgroup-Limit weiter senken |
| `jvmFlags` | G1-Defaults | Konservative JVM-Flags; bestehende eigene Flags bleiben erhalten |
| `serverArgs` | `[]` | Argumente nach `nogui` |
| `autoStart` | `true` | Child beim Plugin-Start starten |
| `autoRestart.enabled` | `true` | Child nach Absturz neu starten |
| `autoRestart.initialDelaySeconds` | `5` | erste Wartezeit, verdoppelt sich je Versuch |
| `autoRestart.maxDelaySeconds` | `300` | Obergrenze der Wartezeit |
| `autoRestart.resetAfterSeconds` | `600` | nach so langer Laufzeit wird der Zähler zurückgesetzt |
| `autoRestart.maxAttempts` | `0` | 0 = unbegrenzt |
| `stopTimeoutSeconds` | `60` | Nach gesendetem `stop`: warten, alle 5 s erneut senden, danach Warnung und Kill |
| `startupStopTimeoutSeconds` | `180` | Bei Stopp während des Starts bis zur Vanilla-Meldung `Done (...)` warten |
| `memoryProtection.policyVersion` | `3` | Beim ersten Start mit dieser Version werden Root-Heap, JVM-Flags und Speicherschutz einmalig auf 2G/5G und das aktuelle Profil gesetzt; Overrides in zusätzlichen Profilen bleiben erhalten. |
| `memoryProtection.*` | siehe `config.yml` | cgroup-Werte werden nur lesend alle 15 s erfasst. Drei Messungen bei mindestens 95% bekannter cgroup-Grenze senden Chatwarnung und `save-all`, warten 15 s, erstellen einen Diagnosebericht und lösen dann sauberen Child-Stopp und Spigot-Shutdown aus. Zap muss Spigot automatisch neu starten; der Child-Autostart wartet 180 s und drei Messungen unter 80%. |
| Speicher-Notfallbericht | `plugins/FabricLauncher/memory-reports/` | Zeitgestempelte `.log`-Datei mit Speicherverlauf, Prozess-/JVM-Werten und lesenden `jcmd`-Diagnosen zum Herunterladen per FTP |
| `shutdownHookTimeoutSeconds` | `25` | dito beim JVM-Shutdown (SIGTERM/Zap) |
| `saveAllIntervalMinutes` | `5` | periodisches `save-all` (0 = aus) |
| `logPrefix` | `"[Child] "` | Präfix im Spigot-Log |
| `mods.lithium` / `mods.ferrite-core` / `mods.c2me-fabric` | `true` / `true` / `false` | Modrinth-Slugs, automatisch geladen |
| `serverProperties.*` | `{}` | Nur explizite Overrides; übrige Gameplay-Einstellungen bleiben bei Minecraft-Defaults |
| `dashboard.enabled` | `true` | Dashboard an/aus |
| `dashboard.port` | `2064` | Port |
| `dashboard.bind` | `""` | `""` = `serverIp`, `0.0.0.0` = alle |
| WorldPorter | optional | Einmalig: Void-Lobby, Drossel, Weltverschiebung. Danach löschen. |

Child-Port und Bind-Adresse werden aus den Netzwerkoptionen erzwungen. `pause-when-empty-seconds=0` wird ebenfalls bei jedem Start erzwungen, damit der Server ohne Spieler nicht pausiert. Alle anderen `server.properties`-Werte bleiben erhalten bzw. folgen bei neuen Installationen den Minecraft-Defaults.

> **Wichtig:** Im Child müssen `white-list=false` und `pause-when-empty-seconds=0` bleiben.

Beim ersten Start mit Speicherrichtlinie 3 setzt das Plugin einmalig die Root-Heap- und JVM-Defaults auf 2G/5G; andere Konfigurationsschlüssel bleiben erhalten. Die Chatwarnung und `save-all`-Anfrage sind best-effort: Wenn der Child Befehle nicht mehr verarbeitet, wird das protokolliert und der Neustart trotzdem ausgeführt. Berichte liegen unter `plugins/FabricLauncher/memory-reports/`, enthalten keine Konsolenhistorie oder Passwörter, aber Thread-Dumps können interne Thread-/Stackdetails enthalten.

## 5. Befehle (`/child`, Permission `fabriclauncher.admin`, Standard: OP/Konsole/RCON)

| Befehl | Wirkung |
|---|---|
| `/child start` | Child starten |
| `/child stop` | Während des Starts auf `Done (...)` warten, dann sauber stoppen |
| `/child restart` | Stoppen, config neu laden, starten |
| `/child status` | Läuft? PID, Uptime, Typ, Port, Ordner |
| `/child cmd <befehl>` | Befehl an die Child-Konsole, z. B. `/child cmd whitelist add Name` |
| `/child config set <pfad> <wert>` | Wert in config.yml setzen (`true/false/Zahl/Text`, Listen: `[list]a,b`) |
| `/child prop set <key> <wert>` | Wert in der Child-server.properties setzen |
| `/child mods` | Jars unter `mods/` und `plugins/` |
| `/child mod install <slug> [version]` | Modrinth-Download, erst auf diesen Befehl |
| `/child mod toggle <pfad>` | `.jar` ↔ `.jar.disabled` |
| `/child diag` | Prozess- und Plattenwerte |
| `/child threaddump` / `heap` / `flags` | lesende `jcmd`-Ausgabe |
| `/child servers` | Profile und Laufzustand |
| `/child focus <id>` | Fokusprofil, schreibt `focusServer` |
| `/child tasks` / `task run <id>` | Cron-Aufgaben |
| `/child backup` | Zip des Fokusprofils, optional JDBC-Metadaten |
| `/child backup list` | Zips des Fokusprofils |
| `/child backup delete <datei>` | Zip löschen |
| `/child backup restore <datei>` | Zurückspielen, nur bei gestopptem Child |
| `/child versions <typ>` | Katalog `fabric`, `paper`, `vanilla` oder `spigot`. Es wird nichts geladen. |
| `/child plan` | JVM-argv des Fokusprofils. Lädt nichts und startet nichts. |
| `/child jar` | Ob das Jar aus dem argv lokal liegt: Name, Bytes, Pfad. Symlinks werden nicht gelesen. |
| `/child crashes` | Vorhandene `crash-reports/*.txt`. `/child crashes <datei>` liest eine Datei. |

Per RCON (Port 2062) funktionieren dieselben Befehle ohne Schrägstrich.

## 6. Dashboard (`http://<SERVER-IP>:2064/`)

- **Login** mit Passwort aus `plugins/FabricLauncher/dashboard-password.txt` (Datei ändern + Spigot-Neustart = neues Passwort). Sitzungen 12 h; nach 5 Fehlversuchen Sperre pro IP (30 s, steigend bis 1 h).
- **Status:** Start/Neustart/Stopp, Uptime, PID, Spieler.
- **Ressourcen Spigot vs. Fabric** (Aktualisierung alle 3 s): RSS, Heap, CPU (% eines Kerns), Threads, TPS/MSPT, geladene Chunks/Entities der Lobby. Child-TPS/MSPT kommen aus `tick query` (höchstens alle 10 s, nur solange das Dashboard offen ist; die Antwortzeilen werden aus Konsole/Log herausgefiltert). Spigot-„MSPT“ ist das gemessene Tick-Intervall (~50 ms = gesund). Child-Heap ist von außen nicht messbar (angezeigt: Xmx).
- **Konsole** live (SSE) mit Befehlseingabe, **Spieler** (Whitelist/OP/Kick/Ban), Rohtext für **server.properties** und **config.yml**, **Dateimanager** (nur innerhalb `child-server/`, Upload bis 1 GB, Löschen nur einzelner Dateien/leerer Ordner mit Bestätigung), **Logs** (Level, Filter, auch `.gz`, Fenster vom Dateiende), **Mods** (Liste, Toggle, Modrinth on demand), **Diagnose** (RSS, FDs, Platte, MSPT-Verlauf, `jcmd`, argv-Vorschau, lesende `crash-reports`).
- **TLS:** `dashboard.https.enabled` mit `mode` `self-signed`, `keystore` oder `pem`. Aus bleibt HTTP. Paper- und Vanilla-Jars werden beim Download per SHA-256 bzw. SHA-1 geprüft, Modrinth-Dateien per SHA-1.
- **Server, Aufgaben, Backup, Versionen:** Profilwechsel, Start/Stopp, Cron-Lauf mit nächster Zeit, Zip-Backup, Liste, Download, Löschen, Restore, JDBC-Test, Versionsliste. Gleiche Ports stehen als `clash`. Restore nur bei gestopptem Child. Die Versionsliste schreibt nichts in die Config. `config.yml` und `server.properties` bleiben Rohtext. `?server=<id>` wählt das Profil; der Schalter im Kopf setzt `focusServer`.
- **Ein Child:** `allowParallel` ist aus. Start und Auto-Neustart teilen sich eine Sperre. Ein zweiter Start stoppt den laufenden Child, ein Neustart eines anderen Profils lässt nach, sobald schon ein Child läuft. Auto-Start gilt nur für `focusServer`. `allowParallel: true` hebt das auf. Weitere Profile stehen unter `servers.<id>`. Spigot-Jars entstehen erst beim Start über BuildTools. Backup-Zips und die JDBC-Verbindung entstehen erst beim Backup. Es wird keine lokale Datenbank installiert.

Aufsicht, Dateibaum, Konsole und Archiv mit Prüfsumme folgen Pterodactyl Wings. Zeitpläne, Zeitzonen, mehrere Versionen und das Zurückspielen eines Archivs folgen Crafty Controller. Der MSPT-Verlauf folgt der Darstellung von spark, die Werte kommen nur aus dem Vanilla-Befehl `tick query`. Paper-Prüfsummen folgen Fill v3. Mods kommen von der Modrinth-API und werden wie in Prism/Modrinth mit `.jar.disabled` abgeschaltet. Spigot-Jars kommen aus dem offiziellen BuildTools. Nichts davon lädt ein Mod in den Child, solange es nicht ausdrücklich installiert wird, und nichts davon ändert Spielregeln.

> **Sicherheitshinweis:** Ohne `dashboard.https.enabled` läuft das Dashboard über **unverschlüsseltes HTTP**. Passwort und Sitzungscookie können in fremden Netzen mitgelesen werden. Nur aus vertrauenswürdigen Netzen nutzen, starkes Passwort verwenden, bei Verdacht Passwort-Datei ändern und Spigot neu starten. Mit TLS setzt das Cookie `Secure`. Das Dashboard hat Vollzugriff auf den Child-Server (Konsole, Dateien).

## 7. WorldPorter (einmalig)

Jar `WorldPorter-1.4.0.jar` nur zusammen mit dem ersten Start nach `plugins/` legen. Es patcht die äußeren `server.properties` (`level-name=lobby_empty`, Void-Flat, Whitelist, kleine Distanzen), `bukkit.yml` und `spigot.yml`, wendet Lobby-Gamerules nur auf Welten an, die nicht die Quellwelt sind, und verschiebt `world`, Nether und End per `ATOMIC_MOVE` nach `child-server/world`. Eine geladene Quellwelt wird nicht angefasst: danach Spigot neu starten und `/worldporter run` oder im Dashboard **Ausführen**. `plugins/WorldPorter/done.marker` heißt fertig. Plugin löschen. FabricLauncher startet danach keine Drossel mehr.

Dashboard: Karte **WorldPorter** mit **Plan** und **Ausführen**, nur solange das Plugin geladen ist. `/worldporter status|plan|run`.

## 8. Herunterfahren und Speichern

- Alle 5 min `save-all` an den Child.
- Stop während des Starts: wartet bis zu `startupStopTimeoutSeconds` auf die Vanilla-Bereitschaftsmeldung `Done (...)` und sendet erst dann `stop`.
- Child-Stopp: `stop` wird alle 5 s erneut gesendet, bis der Child beendet ist oder `stopTimeoutSeconds` abläuft; danach folgen Warnung und Kill. Nicht angenommene frühe Stop-Befehle werden so erneut versucht.
- Spigot-Stopp (Konsole, RCON `stop`, Zap-Panel): `onDisable` wartet ebenso auf einen sauberen Child-Stopp; bei SIGTERM gilt die getrennte `shutdownHookTimeoutSeconds`-Wartezeit.
- Zusätzlich JVM-Shutdown-Hook (25 s) für SIGTERM / Zap-Neustart, falls `onDisable` nicht läuft.
- Container-Notfall: nur bei bekanntem endlichem cgroup-Limit und anhaltend kritischem Druck fährt das Plugin den Host herunter. Ein Zeitstempel in `memory-recovery.txt` verhindert den sofortigen Child-Neustart; bei nicht verfügbarem cgroup-Limit gibt es keine automatische Abschaltung.
- Empfohlen: Server per RCON `stop` oder Panel-Stopp beenden, **nicht** „Kill“.

## 9. Fabric / Mods aktualisieren

- **Neue MC-Version:** `mcVersion` ändern → `/child restart`. Fehlt `fabric-server-launch-<mc>.jar`, wird es mit neuestem Loader geladen.
- **Neuer Fabric-Loader bei gleicher Version:** `child-server/fabric-server-launch-<mc>.jar` löschen → `/child restart`.
- **Mods:** neue Slugs unter `mods:` mit `true` eintragen. Geladen wird nur, wenn keine Markerdatei `mods/.fl-<slug>-*.marker` existiert. Update: Mod-Jar **und** Marker löschen → Neustart. Eigene Mods einfach nach `child-server/mods/` legen.
- Vor Versionssprüngen immer ein Backup der Welt machen.

## 10. Wechsel auf Paper / Vanilla / eigenes Jar

`type: paper` (lädt Paper über fill.papermc.io, bevorzugt STABLE), `type: vanilla` (Mojang-Manifest) oder `type: custom` + `customJar`. Danach `/child restart`. Der Weltordner `child-server/world` bleibt; Paper legt Nether/End allerdings in eigene Ordner (`world_nether`, `world_the_end`) – vorher sichern und ggf. umziehen.

## 11. Fehlerbehebung

| Problem | Lösung |
|---|---|
| Child startet nicht, „Unrecognized VM option“ | Java 25 kennt einige alte Aikar-Flags nicht. `-XX:+IgnoreUnrecognizedVMOptions` wird automatisch gesetzt, `G1RSetUpdatingPauseIntervalMillis` gefiltert. Weitere problematische Flags aus `jvmFlags` entfernen. |
| FTP-Upload liefert **550** | Datei/Ordner gehört dem Server-Prozess bzw. Root-Ordner ist für FTP nicht beschreibbar. Jars lassen sich überschreiben. Konfiguration über Dashboard bzw. `/child config set` ändern; Root-Ordner kann nicht per FTP gelöscht werden (OuterTuner räumt leere Ordner). |
| Zap-Neustart / Panel-Stopp | Shutdown-Hook stoppt den Child (25 s). Bei Datenverlust-Sorge vorher `/child cmd save-all` bzw. RCON `stop` nutzen. |
| Child stürzt in Schleife ab | Log ansehen; Auto-Restart mit wachsender Pause bis 300 s. `/child stop` beendet die Schleife. |
| Dashboard nicht erreichbar | Port 2064, `dashboard.enabled`, Log „Dashboard listening…“. Gesperrt? 30 s bis 1 h warten. |
| Spieler kommen nicht rein | Port **2063** benutzen (nicht 2060). `/child status`, Child-Whitelist ist absichtlich aus. |
| TPS im Dashboard leer | Child noch < 30 s online oder Dashboard gerade erst geöffnet – ~10 s warten. |

## 12. Backup

- Vollbackup: `C:\Users\aiche\MinecraftBackup\2026-10-12\g266459` (auf FlosPC).
- Plugin-Jars + diese Doku: `C:\Users\aiche\MinecraftBackup\plugins\`.
- Deploy-Hilfen (RCON-Skript, Konfig-Kopien): `C:\Users\aiche\MinecraftBackup\deploy\`.

## 13. Nach dem ersten Start

WorldPorter entfernen, sobald `done.marker` existiert. FabricLauncher übernimmt Start, Dashboard, Backup, Cron und Diagnose. Die Lobby-Dateien bleiben, wie WorldPorter sie geschrieben hat.
