# Release notes

## ServerHoster 1.10.1

- Add a read-only dashboard preview of `server.properties` changes enforced or added at the next child start.

## ServerHoster 1.10.0

- Restyle the existing ServerHoster dashboard with VoxelDash-inspired navigation/cards while preserving all existing ServerHoster screens and API-backed values.
- Add browser-session-only CPU, process RSS, cgroup, and child MSPT charts; expose the bundled VoxelDash MIT notice from the dashboard.
- Lower the default child heap to 2G initial / 5G maximum and migrate the root profile to this memory policy on upgrade.
- Stop forcing gameplay properties such as view distance, difficulty, and player cap; new child installs use Minecraft defaults.
- Always force `pause-when-empty-seconds=0` so farms keep running while no players are online.
- Add remote FTP/FTPS backup storage with automatic TLS detection, temporary-upload finalization, dashboard list/download/restore/delete, and credentials kept in the local `plugins/FabricLauncher/secrets.yml`. Scheduled backup tasks wait for an empty child, stop it cleanly, and restart it after the backup attempt.
- Enable a daily 05:00 server-local backup task by default, including a one-time migration for existing configurations without tasks. The default target remains local; FTP/FTPS is used only when explicitly configured.
- Add optional HTTPS-only ntfy notifications for memory pressure, emergency shutdown, child restart/recovery, scheduled task failures, backup failures, and external JDBC metadata failures. Notifications remain disabled until enabled with a topic.
- Keep JDBC metadata external and optional; no local database or preloaded server jars are added.

## ServerHoster 1.9.0

- Migrate the existing root child memory settings once to the current profile: `Xms2G`, `Xmx8G`, conservative G1 flags, and the current cgroup recovery thresholds. Other config keys and server properties are left intact.
- Before an emergency host restart, send a warning into each running child server's chat, request `save-all`, and allow a short grace period. The warning/save request is best-effort if the child cannot process console commands.
- Save a detailed, timestamped incident log under `plugins/FabricLauncher/memory-reports/` with the recent cgroup/host memory timeline, JVM/process facts, and bounded read-only `jcmd` diagnostics. The dashboard shows the latest report path.
- Keep reports local and text-based; no database, upload, or external telemetry is introduced.

## ServerHoster 1.8.0

- Reduce the default child JVM initial heap from 8G to 2G while retaining an 8G maximum heap; replace fixed young-generation tuning with conservative Java 25 G1 defaults.
- Add read-only host/cgroup memory sampling every 15 seconds and dashboard reporting. Three consecutive critical samples against a known finite cgroup limit can cleanly stop the child and Spigot; after the hoster restarts Spigot, child auto-start waits for a cooldown and stable memory.
- Keep vanilla gameplay, child server properties, Lithium/Ferrite defaults, and on-demand behavior unchanged. Unknown or unlimited cgroup limits never trigger automatic host shutdown.
- Correctly distinguish cgroup v2 memory-limit events and OOM events; cgroup v1 `memory.failcnt` is reported as limit-hit events, not OOM.

## ServerHoster 1.7.0

- A stop requested while the child is starting waits for the vanilla `Done (...)` readiness line before sending `stop` (configurable with `startupStopTimeoutSeconds`, default 180 seconds).
- The launcher retries `stop` every five seconds until the child exits or `stopTimeoutSeconds` expires; only then does it warn and force termination.
- Cancelling a child start before the process launches now prevents that pending start from launching afterward.
- Vanilla gameplay, tick behavior, mods, and server properties are unchanged.

### Inspiration and attribution

The dashboard and operational workflows take inspiration from Pterodactyl Wings and Crafty Controller. The MSPT display is inspired by spark and uses only the vanilla `tick query` command; spark is not installed. PaperMC Fill v3 informs Paper artifact handling. The `.jar.disabled` convention follows Prism/Modrinth. Spigot server builds use the official Spigot BuildTools.
