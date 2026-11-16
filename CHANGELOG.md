# Unreleased

- `/child` ist entfernt. Die Child-Konsole wird nicht mehr ins Spigot-Log gespiegelt.
- jcmd-Knöpfe, `spigot-minimal-config` und das Kopieren der Host-Spielerdateien sind entfernt.
- Der Start nutzt fest den neuesten Fabric-Loader und `-XX:+UseG1GC`.
- server.properties-Spielwerte, RAM, HTTPS und der Dashboard-Port haben Felder.
- Host-Grundeinrichtung und Minimierung lassen sich in einem Lauf vorbereiten.
- Der Backup-Zieltest speichert nicht. AUTO sagt im Test, wenn die Verbindung FTP und nicht FTPS ist.
- Neue Einrichtungen sichern `world`, `config` und `mods`. Der Dateimanager kann Ordner mit Inhalt löschen.

# ServerHoster 2.5.1

- After a version change, setup checks the folder in `workingDir` and says whether a child was found (`server.properties` or a JAR).
- When a child is found, that folder stays. The wizard still asks only for onboarding answers that were missing. When none are missing, it shows Update 2.5.1.
- When no child is found, the wizard asks for the folder or a new server. The entered folder is checked before continuing, and a rejection is shown under the field. Creating a new server follows first-time setup, including version selection, in `./child-server`.
