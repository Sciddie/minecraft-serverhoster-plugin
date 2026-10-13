# ServerHoster

Spigot-Plugin **FabricLauncher** (`de.aichele.childlauncher`, Datenordner `plugins/FabricLauncher`). Diese Linie startet und überwacht Child-Server (Paper, Fabric, Spigot, Vanilla) und stellt das Web-Dashboard bereit. Minimale Spigot-Konfiguration für den äußeren Server liegt unter `spigot-minimal-config/`.

Der Kern 1.1.0 (ein Fabric-Child, Dashboard-TLS, Mods, lesende Diagnose) liegt in [minecraft-fabric-launcher](https://github.com/Sciddie/minecraft-fabric-launcher), Release [v1.1.0](https://github.com/Sciddie/minecraft-fabric-launcher/releases/tag/v1.1.0).

## Server einrichten

Schritt für Schritt auf einem neuen Spigot-Server (nur Spigot beim Hoster, FTP, ein freier Zusatzport für Fabric und einer für das Dashboard): **[docs/SETUP.md](docs/SETUP.md)**.

## Bauen

Voraussetzung: **Java 25** (Minecraft 26.x) und Maven.

```bash
mvn package
```

Das Plugin-Jar liegt danach unter `target/FabricLauncher-1.4.0.jar`. WorldPorter getrennt:

```bash
mvn -f worldporter/pom.xml package
```

`worldporter/target/WorldPorter-1.4.0.jar` nur für den ersten Start nach `plugins/` legen. Es setzt die Void-Lobby, drosselt den äußeren Spigot und verschiebt die alte Welt. Wenn `plugins/WorldPorter/done.marker` existiert, WorldPorter löschen. Danach reicht FabricLauncher.

## Dokumentation

Ausführliche Beschreibung: [docs/Dokumentation.md](docs/Dokumentation.md) (HTML-Fassung: [docs/Dokumentation.html](docs/Dokumentation.html)).

WorldPorter liegt unter `worldporter/` und gehört nicht ins FabricLauncher-Jar.
