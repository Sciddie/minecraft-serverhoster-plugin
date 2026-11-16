# Upgrade auf ServerHoster 2.5.0

Das Plugin-Jar durch `ServerHoster-2.5.0.jar` ersetzen. Der Start schreibt
`config.yml` aus der mitgelieferten Vorlage neu. Werte bleiben, wenn ihr
Schlüssel in dieser Version noch vorkommt. Schlüssel, die es nicht mehr gibt,
fallen weg. Dashboard-Passwort, Secrets und der Child-Ordner bleiben liegen.

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
