# Upgrade von ServerHoster 2.4.4 auf 2.5.0

Das Plugin-Jar durch `ServerHoster-2.5.0.jar` ersetzen. Konfiguration,
Dashboard-Passwort, Secrets und der Child-Ordner bleiben liegen. Der Start
schreibt `metadata.pluginVersion` fort.

Vor dem Wechsel prüfen:

- `type` muss `fabric`, `paper` oder `custom` sein. `vanilla` und `spigot`
  starten nicht mehr; den Typ im Dashboard oder in `config.yml` umstellen.
- `backup.database` wird nicht mehr gelesen. Lokale ZIPs und FTP/FTPS bleiben.
- `/child` zeigt nur noch Status und Dashboard-URL. Start, Stopp und Backups
  laufen im Dashboard.
- Die Einrichtung scannt den Server-Root nicht mehr. Neue Ordner liegen unter
  `./child-server`, geprüft gegen das Serververzeichnis.
- `memory-telemetry.log` wird nicht mehr fortgeschrieben. Speicherschutz und
  `memory-incident.log` bleiben.

## Vorheriges Upgrade: 2.4.2 oder 2.4.3 auf 2.4.4

Das Plugin-Jar ersetzen; Konfiguration und Child-Ordner bleiben erhalten.
`/api/setup` liefert `required` und `completed` aus demselben Snapshot.
Tag `v2.4.3` existiert, hat aber kein veröffentlichtes GitHub-Release-Asset;
für den Betrieb `ServerHoster-2.4.4.jar` verwenden.

## Vorheriges Upgrade: 2.4.2 auf 2.4.3

Das Tag `v2.4.3` wurde gesetzt, der Release-Workflow ist jedoch fehlgeschlagen
und hat kein GitHub-Release veröffentlicht. Funktionale Änderungen sind in
2.4.4 enthalten.

## Vorheriges Upgrade: 2.4.1 auf 2.4.2

Das Plugin-Jar ersetzen; Konfiguration und Child-Ordner bleiben erhalten.

## Vorheriges Upgrade: 2.4.0 auf 2.4.1

Das Plugin-Jar ersetzen; Konfiguration und Child-Ordner bleiben erhalten.
Bei `type: custom` werden relative `customJar`-Pfade jetzt wie dokumentiert
vom Child-Ordner aus aufgelöst. Wurde bisher ein relativer Pfad absichtlich
vom Host-Ordner aus angegeben, muss er entsprechend angepasst werden.

## Vorheriges Upgrade: 2.3.2 auf 2.4.0

Das Plugin lädt weiter aus `plugins/ServerhosterPlugin/`. Ein Upgrade von
2.3.2 ist der Austausch des Jars. `config.yml`, Dashboard-Passwort, Secrets
und der Child-Ordner bleiben liegen. Der Start schreibt
`metadata.pluginVersion` fort. `autoRestart.maxAttempts: 0` wird weiterhin
auf 5 gesetzt. Einträge unter `servers:`, `focusServer` und `tasks` werden
entfernt und nicht mehr gelesen. Zeitpläne unter `schedules` bleiben. Es gibt
genau einen Child.

Ältere Datenordner unter `plugins/FabricLauncher/` und Jars mit dem Namen
`FabricLauncher` werden nicht mehr importiert oder aufgeräumt. Wer von so
einer Installation kommt, richtet den Child im Dashboard neu ein oder
übernimmt die Werte von Hand in `plugins/ServerhosterPlugin/config.yml`.

Der Child-Ordner selbst wird beim Upgrade nicht verändert.
