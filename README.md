# ServerHoster

Spigot-Plugin, das auf einem Hoster mit nur Spigot einen Child-Server startet (Fabric, Paper, Spigot, Vanilla oder ein eigenes Jar) und das Web-Dashboard bereitstellt. Entwickelt wird diese Linie in diesem Repo.

Der Spigot-Name bleibt **FabricLauncher** (`de.aichele.childlauncher`). Konfiguration und Passwort liegen weiter in `plugins/FabricLauncher/`. Das Release-Jar heißt `ServerHoster-<version>.jar`.

Normalbetrieb ist **ein Child**. `allowParallel` steht in `config.yml` und ist aus. Ein Start stoppt die anderen Profile, Auto-Start gilt nur für `focusServer`. `allowParallel: true` lässt mehrere Profile gleichzeitig laufen.

Gameplay auf dem Fabric-Child bleibt vanilla. Lithium und FerriteCore sind die mitgelieferten Mods. WorldPorter ist optional und nur für den ersten Start.

Die ältere Kernlinie 1.1.0 liegt in [minecraft-fabric-launcher](https://github.com/Sciddie/minecraft-fabric-launcher). Neue Hoster-Änderungen entstehen hier.

## Server einrichten

Schritt für Schritt: **[docs/SETUP.md](docs/SETUP.md)**.

Kurz:

1. `ServerHoster-1.6.0.jar` nach `plugins/` legen.
2. Für den ersten Start zusätzlich `WorldPorter-1.4.0.jar`. Wenn `plugins/WorldPorter/done.marker` existiert, WorldPorter löschen.
3. Spigot starten. Dashboard-Passwort: `plugins/FabricLauncher/dashboard-password.txt`.

## Bauen

Java 25 und Maven.

```bash
mvn package
```

Das Jar liegt unter `target/ServerHoster-1.6.0.jar`. WorldPorter getrennt:

```bash
mvn -f worldporter/pom.xml package
```

`worldporter/target/WorldPorter-1.4.0.jar`. WorldPorter gehört nicht ins ServerHoster-Jar.

## Dokumentation

[docs/Dokumentation.md](docs/Dokumentation.md), HTML-Fassung [docs/Dokumentation.html](docs/Dokumentation.html).

Minimale Außenkonfiguration: `spigot-minimal-config/`.
