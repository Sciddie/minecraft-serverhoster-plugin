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

Das Plugin-Jar liegt danach unter `target/FabricLauncher-1.3.0.jar`.

## Dokumentation

Ausführliche Beschreibung: [docs/Dokumentation.md](docs/Dokumentation.md) (HTML-Fassung: [docs/Dokumentation.html](docs/Dokumentation.html)).

WorldPorter gehört nicht zu diesem Repo und wird nicht gebaut.
