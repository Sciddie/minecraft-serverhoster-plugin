# Release notes

## ServerHoster 2.0.1

- Pause automatic child restarts after five consecutive retry attempts by
  default; configure the retry limit and backoff in Dashboard > Einstellungen.
- Migrate the legacy unlimited-retry value (`maxAttempts: 0`, used by 1.x and
  2.0.0 configs, including imported ones) to five attempts; invalid retry
  limits fail closed instead of creating an unbounded restart loop.
- Add a dedicated settings workflow for rarely changed restart and FTP/FTPS
  connection settings. Credentials remain in the local `secrets.yml`.
- Expand recovery, schedule execution, and memory-protection tests.

## ServerHoster 2.0.0

- Replace multi-profile operation with a strict single-child model.
- Add a first-run wizard that blocks automatic child start until setup is
  confirmed; support fresh setup, attaching an existing directory, and
  configuration import from ServerHoster 1.10.1+.
- Discover likely child directories when old plugin configuration is missing;
  show ambiguous matches for explicit administrator selection.
- Move the plugin data directory to `plugins/ServerhosterPlugin/`. Offer
  separately confirmed, optional cleanup of old plugin files while retaining
  local backups and memory incident reports.
- Remove mod/plugin catalogues, automatic mod installation, and mod toggles.
  New setup does not install mods or plugins.
- Add dashboard-managed hourly, daily, and weekly schedules with ordered
  console-command and backup actions. Schedules are persisted in YAML.
- Keep backups on-demand unless a schedule is configured; preserve optional
  local or FTP/FTPS storage, and optional external JDBC metadata.
- Keep ntfy notifications optional and configurable during setup.
- Change sustained critical-memory protection to warn and gracefully stop only
  the child server. The plugin never automatically stops the outer Spigot host.
- Retain raw `config.yml` and `server.properties` editors, read-only
  diagnostics, optional dashboard HTTPS, and the VoxelDash MIT notice.

### Inspiration and attribution

Operational workflows take inspiration from Pterodactyl Wings and Crafty
Controller. The MSPT display is inspired by spark and uses only vanilla `tick
query`; spark is not installed. Paper artifact handling draws on PaperMC Fill
v3. The `.jar.disabled` convention is associated with Prism/Modrinth. Spigot
builds use the official Spigot BuildTools. VoxelDash-derived dashboard elements
retain the required MIT license notice in the plugin JAR and dashboard.

## ServerHoster 1.10.1

- Add a read-only dashboard preview of `server.properties` changes enforced or
  added at the next child start.

## ServerHoster 1.10.0

- Restyle the dashboard with VoxelDash-inspired navigation and browser-session
  charts while retaining ServerHoster-backed data.
- Add remote FTP/FTPS backups, optional external JDBC metadata, and optional
  ntfy notifications.
- Keep backup storage local by default.
- Keep the VoxelDash MIT notice in the plugin JAR and dashboard.
