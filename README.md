# ServerHoster

ServerHoster ist ein Spigot-Plugin, das genau einen separaten Minecraft-
Child-Server startet und ein Web-Dashboard für Einrichtung, Betrieb,
Diagnose, Backups und Zeitpläne bereitstellt. Das Plugin installiert keine
Mods oder Plugins.

## ServerHoster 2.5.1

- Ein Child: Fabric, Paper oder ein eigenes JAR. Der Standard ist `fabric`.
- Das Web-Dashboard übernimmt Einrichtung, Start, Stopp, Backups und Zeitpläne.
  `/child` zeigt Status und die Dashboard-URL.
- Backups sind lokale ZIP-Dateien oder FTP/FTPS.
- Die Einrichtung legt den Child in `./child-server` an.
- Die Diagnose zeigt die aktuelle Speichermessung. Ein Speicher-Notfall schreibt
  `memory-incident.log`.
- Ein geplantes Backup, das fünf Stunden auf einen leeren Child wartet, warnt
  einmal und wartet danach weiter.
- Upgrade durch Austausch des Plugin-Jars. `config.yml` wird aus der aktuellen
  Vorlage neu geschrieben: gesetzte Werte bleiben, unbekannte Schlüssel fallen
  weg. Ein Versionswechsel öffnet die Einrichtung erneut und prüft den
  Ordner aus `workingDir`. Liegt dort ein Child, bleibt der Ordner; fehlen
  keine Angaben, zeigt der Assistent „Update 2.5.1“. Liegt dort keiner, fragt
  er nach dem Ordner oder nach einem neuen Server. Der eingegebene Ordner wird
  geprüft, eine Ablehnung steht unter dem Feld. Ein neuer Server läuft wie die
  Ersteinrichtung mit Versionswahl nach `./child-server`. `type` muss
  `fabric`, `paper` oder `custom` sein.

Siehe [Einrichtung](docs/SETUP.md), [Upgrade](docs/UPGRADE.md)
und [Betriebsdokumentation](docs/Dokumentation.md).

## Build

Build mit JDK 25 und Maven. Das React-/TypeScript-Dashboard wird im Maven-
Build mit Node.js 22 erzeugt.

```powershell
mvn clean verify
```

Das Plugin-Jar ist `target/ServerHoster-2.5.1.jar`. Spigot API 26.3 ist eine
`provided`-Abhängigkeit des äußeren Hosts. Es gibt keine mitgelieferten
Server-Jars. Fabric- und Paper-Artefakte werden beim Child-Start geladen; ein
eigenes JAR bleibt lokal.

## Inspiration und Lizenzen

Die Betriebsabläufe sind inspiriert von Pterodactyl Wings und Crafty
Controller. Die MSPT-Anzeige ist von spark inspiriert, nutzt aber ausschließlich
das Minecraft-Kommando `tick query`; spark wird nicht installiert. Die
Paper-Artefaktbehandlung orientiert sich an PaperMC Fill v3. Die
`.jar.disabled`-Konvention ist von Prism/Modrinth bekannt. Fabric nutzt die
Meta-API, Paper die Fill-API v3.

Dashboard-Elemente sind an VoxelDash angelehnt. Der erforderliche MIT-
Lizenzhinweis ist im Plugin-Jar enthalten und über das Dashboard erreichbar.

**Minecraft-EULA:** Betreiber müssen die jeweils gültigen Minecraft-
EULA-Bedingungen und sonstigen anwendbaren Bedingungen selbst beachten.
