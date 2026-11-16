# Upgrade auf ServerHoster 2.5.0

Das Plugin-Jar durch `ServerHoster-2.5.0.jar` ersetzen. Der Start schreibt
`config.yml` aus der mitgelieferten Vorlage neu. Werte bleiben, wenn ihr
Schlüssel in dieser Version noch vorkommt. Schlüssel, die es nicht mehr gibt,
fallen weg. Dashboard-Passwort, Secrets und der Child-Ordner bleiben liegen.

Weicht die gespeicherte Plugin-Version ab, öffnet das Dashboard die Einrichtung
erneut. Liegt am konfigurierten Ort bereits ein Child, kann er übernommen oder
neu aufgesetzt werden. Neu aufsetzen verwendet `./child-server` und löscht
vorhandene Dateien nicht. Der Assistent fragt danach nur, was eine
Neuinstallation auch abfragt und was in der alten Konfiguration fehlt:
Servertyp, Minecraft-Version, Spielport, Backup-Ziel, bei einem externen Ziel
den Server, Sicherungs-Pfade, Aufbewahrung, einen täglichen Backup-Zeitplan
und ntfy. Ausgeblendete Standardwerte, etwa der ntfy-Server bei ausgeschaltetem
ntfy, werden nicht erneut gefragt. Alles andere setzt die Einrichtung wie bei
einer Neuinstallation aus der Vorlage, darunter Speicher, Neustart und
Dashboard. Neu aufsetzen leert zusätzlich `serverProperties`.

Vor dem Wechsel prüfen:

- `type` muss `fabric`, `paper` oder `custom` sein. `vanilla` und `spigot`
  starten nicht; den Typ im Dashboard oder in `config.yml` umstellen.
- `/child` zeigt Status und die Dashboard-URL. Start, Stopp und Backups laufen
  im Dashboard.
- Neue Child-Ordner liegen unter `./child-server` und müssen im
  Serververzeichnis bleiben.
- Die Diagnose zeigt die aktuelle Speichermessung. Ein Speicher-Notfall
  schreibt `memory-incident.log`.
- Backups sind lokale ZIP-Dateien oder FTP/FTPS.
