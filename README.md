# ServerHoster

ServerHoster ist ein Spigot-Plugin, das genau einen separaten Minecraft-
Child-Server startet und ein Web-Dashboard für Einrichtung, Betrieb,
Diagnose, Backups und Zeitpläne bereitstellt. Das Plugin installiert keine
Mods oder Plugins.

## ServerHoster 2.0.0

- Genau ein Child-Server; keine Profile und kein Profilwechsel.
- Einrichtungsassistent für neue Server, vorhandene Child-Ordner und
  unterstützte Migrationen von ServerHoster 1.10.1+.
- Dashboard-Zeitpläne mit Stunden-, Tages- oder Wochenintervall, geordneten
  Befehls- und Backup-Aktionen; Speicherung in `config.yml`, nicht in einer DB.
- Optionale lokale oder FTP/FTPS-Backups, externe JDBC-Metadaten und ntfy.
- Lesende Prozess- und Hostdiagnose, darunter `jcmd` und das Vanilla-Kommando
  `tick query`. Kritischer Speicherschutz kann den Child sauber stoppen, nie
  automatisch den äußeren Spigot-Host.
- Rohe Texteditoren für `config.yml` und die Child-`server.properties`.
- Optionales Dashboard-HTTPS mit selbstsigniertem Zertifikat, Keystore oder
  PEM-Dateien.

Siehe [Einrichtung](docs/SETUP.md), [Migration](docs/MIGRATION-FABRICLAUNCHER-1.0.0.md)
und [Betriebsdokumentation](docs/Dokumentation.md).

## Build

Build mit JDK 25 und Maven. Das React-/TypeScript-Dashboard wird im Maven-
Build mit Node.js 22 erzeugt.

```powershell
mvn clean verify
```

Das Plugin-Jar ist `target/ServerHoster-2.0.0.jar`. Spigot API 26.3 ist eine
`provided`-Abhängigkeit. H2 ist ausschließlich eine Testabhängigkeit und
befindet sich nicht im Plugin-Jar. Es gibt keine lokale Datenbank und keine
mitgelieferten Server-Jars.

## Inspiration und Lizenzen

Die Betriebsabläufe sind inspiriert von Pterodactyl Wings und Crafty
Controller. Die MSPT-Anzeige ist von spark inspiriert, nutzt aber ausschließlich
das Vanilla-Kommando `tick query`; spark wird nicht installiert. Die
Paper-Artefaktbehandlung orientiert sich an PaperMC Fill v3. Die
`.jar.disabled`-Konvention ist von Prism/Modrinth bekannt. Spigot-Builds
verwenden die offiziellen Spigot BuildTools.

Dashboard-Elemente sind an VoxelDash angelehnt. Der erforderliche MIT-
Lizenzhinweis ist im Plugin-Jar enthalten und über das Dashboard erreichbar.

**Minecraft-EULA:** Betreiber müssen die jeweils gültigen Minecraft-
EULA-Bedingungen und sonstigen anwendbaren Bedingungen selbst beachten.
