# Upgrade und Migration auf ServerHoster 2.0.0

ServerHoster 2.0.0 kann Einstellungen ab ServerHoster 1.10.1 importieren.
Ältere Installationen können noch einen Child-Ordner erkennen, ihre
Konfiguration wird jedoch nicht automatisch importiert. Der Assistent
funktioniert auch dann, wenn das alte Plugin-Jar oder dessen Datenordner
bereits gelöscht wurde.

## Vor dem Upgrade

1. Child sauber stoppen und danach Spigot herunterfahren.
2. Eine unabhängige Sicherung des gesamten Child-Ordners, des alten
   `plugins/FabricLauncher/`-Ordners und des alten Plugin-Jars herunterladen.
   Der Child-Ordner enthält unter anderem Welten, Spielerdaten,
   `server.properties` und Server-Jars.
3. Child-Pfad, Typ, Minecraft-Version, Spielport und HTTPS-Einstellungen
   notieren. Das Plugin-Upgrade nicht mit einem Minecraft-Versionsupgrade
   kombinieren.
4. Das alte `FabricLauncher*.jar` aus `plugins/` entfernen oder verschieben
   und `ServerHoster-2.0.0.jar` dorthin laden. Niemals beide Plugin-Versionen
   gleichzeitig aktivieren. Den Child-Ordner nicht löschen.

## Erster Start und Einrichtungsassistent

Der neue Plugin-Datenordner lautet `plugins/ServerhosterPlugin/`. Der
Assistent verhindert den Child-Autostart, bis ein Administrator die
Einrichtung bestätigt. Er durchsucht begrenzt direkte Unterordner des
Spigot-Roots nach üblichen Serverdateien (`server.properties`, `eula.txt`,
`level.dat` oder einem regulären Jar auf oberster Ordnerebene). Symlinks und
bekannte Host-Verzeichnisse werden übersprungen. Bei mehreren Treffern muss
der richtige Ordner ausdrücklich gewählt werden.

Wähle den passenden Ablauf:

- **Konfiguration übernehmen** liest `plugins/FabricLauncher/config.yml` und
  übernimmt Einstellungen des ausgewählten Childs sowie unterstützte
  Zeitpläne. Der Import ist möglich, wenn das alte Plugin-Jar Version 1.10.1
  oder neuer bestätigt. Fehlt das Jar oder die Versionsangabe, ist die
  zusätzliche exakte Bestätigung `IMPORT 1.10.1+` erforderlich. Nur verwenden,
  wenn die Herkunft der alten Konfiguration unabhängig geprüft wurde.
- **Vorhandenen Ordner einbinden** lässt Ordner und Inhalt unverändert.
  Servertyp, Version und Port müssen bestätigt werden; alte Plugin-Einstellungen
  werden dabei nicht importiert.
- **Neuen Server einrichten** erstellt die Konfiguration für einen frischen
  Child. Server-Artefakte werden erst bei einem ausdrücklichen Start geladen
  oder gebaut.
- **Archivieren und neu einrichten** erfordert die vom Assistenten angezeigte
  exakte Bestätigung. Der alte Child-Ordner wird in ein zeitgestempeltes
  Nachbarverzeichnis verschoben und nicht stillschweigend gelöscht. Am
  ursprünglichen Pfad wird ein frischer Ordner vorbereitet.

ServerHoster installiert keine Mods oder Plugins. Vorhandene Dateien in einem
eingebundenen Child bleiben in der Verantwortung des Administrators.

## Übernommene Daten und optionale Bereinigung

Beim unterstützten Import werden unter anderem Typ, Version, Port,
JVM-Einstellungen, Startverhalten, Server-Property-Overrides,
Dashboard-/Backup-/Benachrichtigungseinstellungen und unterstützte
wiederkehrende Tasks übernommen. Nicht unterstützte alte Tasks bleiben in
der alten Konfiguration erhalten, statt stillschweigend verworfen zu werden.
Neue Dashboard-Zeitpläne werden in `config.yml` gespeichert.

Nach der Prüfung bietet der Assistent optional an, alte Plugin-Jars und alte
Plugin-Einstellungen/Secrets zu entfernen. Die Bereinigung durchläuft keine
Child-Ordner. Lokale Backups, Speicher-Notfallberichte und
Recovery-Zustandsdateien im alten Datenordner bleiben erhalten. Daher kann
der alte Ordner mit diesen Daten bestehen bleiben. Die unabhängige Sicherung
erst löschen, wenn der neue Child erfolgreich gestartet wurde und ein neues
Backup nachweislich funktioniert.

Das neue Dashboard-Passwort liegt in
`plugins/ServerhosterPlugin/dashboard-password.txt`. Relative HTTPS-Zertifikat-
und Keystore-Dateien der unterstützten Konfiguration werden in den neuen
Datenordner übernommen; externe absolute Pfade bleiben extern. Dashboard-
Zugang und HTTPS vor dem Öffnen des Dashboards für nicht vertrauenswürdige
Netzwerke prüfen.

## Rollback

1. Child sauber stoppen und Spigot herunterfahren.
2. 2.0.0-Jar entfernen und das gesicherte alte Plugin-Jar wiederherstellen.
3. Den alten `plugins/FabricLauncher/`-Ordner aus der unabhängigen Sicherung
   zurückspielen, falls er bereinigt oder verändert wurde.
4. Die alte Konfiguration nur bei Bedarf zurückspielen. Den Child-Ordner
   unverändert lassen, außer eine Weltwiederherstellung ist ausdrücklich nötig.
5. Spigot starten und anhand des Logs prüfen, dass die alte Version weiterhin
   auf denselben Child-Ordner zeigt.

Weltformate sind im Allgemeinen nicht rückwärtskompatibel. Eine in einer
neueren Minecraft-Version geöffnete Welt nicht ohne separat getestetes
Welt-Backup mit einer älteren Version fortsetzen.
