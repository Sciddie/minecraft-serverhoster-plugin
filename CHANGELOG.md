# ServerHoster 2.5.2

- `/child` ist entfernt. Die Child-Konsole wird nicht mehr ins Spigot-Log gespiegelt.
- jcmd-Knöpfe, `spigot-minimal-config` und das Kopieren der Host-Spielerdateien sind entfernt.
- Der Start nutzt fest den neuesten Fabric-Loader und `-XX:+UseG1GC`.
- server.properties-Spielwerte, RAM, HTTPS und der Dashboard-Port haben Felder.
- Host-Grundeinrichtung und Minimierung lassen sich in einem Lauf vorbereiten.
- Der Backup-Zieltest speichert nicht. AUTO sagt im Test, wenn die Verbindung FTP und nicht FTPS ist.
- Neue Einrichtungen sichern `world`, `config` und `mods`. Der Dateimanager kann Ordner mit Inhalt löschen.
