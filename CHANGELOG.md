# Release notes

## ServerHoster 2.5.0

- Supported child types are Fabric, Paper and a local custom JAR. Spigot
  BuildTools and Vanilla downloads are removed. The shipped default is
  `type: fabric`. An existing `vanilla` or `spigot` profile no longer starts.
- Backups stay local ZIPs or FTP/FTPS. External JDBC metadata and the H2 test
  dependency are removed.
- The periodic `memory-telemetry.log` is no longer written. Live `/api/diag`
  memory data, cgroup protection and `memory-incident.log` on a real memory
  emergency stay.
- `/child` only reports child status and the dashboard URL. Start, stop,
  backup and host minimization stay in the web dashboard.
- Setup no longer scans the server root. New directories are checked by
  `PathGuard`; the dashboard uses `./child-server`.
- A scheduled backup that has waited five hours for an empty child logs one
  warning and sends one ntfy message at priority 3, then keeps waiting.
- Upgrade from 2.4.4 by replacing the plugin jar. Config, password, secrets
  and the child directory stay in place. `metadata.pluginVersion` advances on
  startup.

## ServerHoster 2.4.4

- Stabilize the ProcessProbe RSS/threads regression so separate `/proc` reads
  are not required to match lockstep (GitHub Actions run for tag `v2.4.3`
  failed on that flake: https://github.com/Sciddie/minecraft-serverhoster-plugin/actions/runs/42871713549).
- Includes the 2.4.3 functional fixes: consistent `/api/setup` required/completed
  snapshot, incomplete SetupWorkflow safety path, and shared RSS/thread helpers
  with dashboard CPU sampling left separate.
- Upgrade from 2.4.2 or from a local 2.4.3 jar build by replacing the plugin jar.

Note on 2.4.3: git tag `v2.4.3` remains on commit `97181f6` and is not moved.
No GitHub Release asset was published for 2.4.3 because the tag workflow failed
before `gh release create`. Use `ServerHoster-2.4.4.jar` from this release.

Testing limitations: no live Spigot host or browser E2E in the release
environment. Automated regressions cover setup snapshot consistency, wizard
completion notify/reload contracts, ProcessProbe RSS/threads, and the 2.4.2
upgrade fixture. A separate manual Spigot check of 2.4.2/PR #4 is not a
substitute for live verification of 2.4.4.

## ServerHoster 2.4.3

- Make `/api/setup` return a consistent `required`/`completed` pair from one
  snapshot of `setupRequired`, so a concurrent setup finish cannot tear the
  flags.
- Keep the incomplete SetupWorkflow form as a transition/safety path; the
  SetupWizard remains the onboarding UI while setup is required.
- Share RSS and thread reads between the dashboard status path and
  `ProcessProbe`. Child CPU sampling stays in the dashboard (delta state).
- Tag `v2.4.3` was pushed, but the Release workflow failed on a flaky
  ProcessProbe assertion and published no GitHub Release asset. Superseded by
  2.4.4 without rewriting the tag.

## ServerHoster 2.4.2

- Extract the active dashboard markup and script into separate source files.
- Remove obsolete duplicate player, task and backup dashboard implementations
  and fragile runtime script replacements. The existing web dashboard remains
  bundled with the plugin.
- Upgrade from 2.4.1 by replacing the plugin jar.

## ServerHoster 2.4.1

- Restore dashboard visibility of schedule execution, waiting and failure
  states. The last execution time remains stored across host restarts; its
  result is available until the host restarts.
- Resolve relative custom JAR paths from the child working directory for
  startup, diagnostics and launch previews.
- Remove the obsolete dashboard claim that legacy tasks are still displayed.

## ServerHoster 2.4.0

- Keep the 2.3.2 upgrade path: replace the jar. The data folder stays
  `plugins/ServerhosterPlugin/`, and the existing child, config, password and
  secrets stay in place.
- Move the Java package to `dev.aichele.serverhoster`.
- Stop importing or cleaning up FabricLauncher 1.x folders and jars.
- Ignore `servers.*` and `focusServer`, and remove those keys at startup.
- Remove the legacy `tasks` list, `/child tasks`, `/child task` and
  `/api/tasks`. Dashboard schedules still create, edit, toggle, run and delete
  through `/api/schedules`. A startup removes a leftover `tasks` key.
- Read only `memoryProtection.autoStopChild` at runtime. A fresh config sets it
  to true and uses policy version 4. Configs below policy version 4 still copy
  `autoShutdown` once, then drop the old key.

## ServerHoster 2.3.2

- Offer the available child versions as a dropdown in the onboarding wizard and
  the setup page; `custom` keeps a free text field. `/api/versions` is available
  before setup completes and returns the complete catalog.
- Open the setup wizard automatically when setup is still required, and show
  "Noch nicht eingerichtet" instead of a misleading default server type.
- Reload the player lists several times after whitelist, op, ban and kick
  actions, because Minecraft writes the files asynchronously.
- Remove the separate WorldPorter project; the host setup replaces it.

## ServerHoster 2.3.1

- Inherit the host bind address when `serverIp` is empty. Existing nonempty
  overrides, including `0.0.0.0`, remain unchanged.
- Add syntax highlighting and line numbers to supported dashboard editors.
- Show child actions according to its lifecycle and report connection failures.
- Fix player tracking for Minecraft 26.3, refresh visible player lists every
  10 seconds, and query the child when opening or manually refreshing the page.
- Give dashboard pages individual URLs that support reload and browser history.

Testing limitations: the changed IP binding and player refresh have not been
verified end-to-end on an external ZAP-Hosting server.

## ServerHoster 2.3.0

- Set the dashboard password during initial onboarding using a one-time code
  from the host log, with optional secure password generation.
- Fix overlapping schedule controls on narrow cards.
- Configure backup destinations, credentials, paths, retention and an optional
  daily schedule during onboarding and in the dashboard.
- Explain diagnostic readings and actions, keeping advanced data available.
- Replace typed dashboard confirmations with explicit confirmation dialogs.

Testing limitations: a real external FTP/FTPS destination and a full remote
backup upload were not tested end-to-end.

## ServerHoster 2.2.0

- Guide first-time setup through a contextual onboarding flow for new servers,
  existing server directories and supported legacy configurations.
- Exclude outer host worlds from child-server discovery.
- Make dashboard settings accessible, improve charts and mobile layouts, and
  keep the file editor in sync when files are renamed or deleted.
- Exclude Minecraft world lock files from local and remote backups, and remove
  incomplete local archives when backup creation fails.
- Clarify player-command feedback and backup restore requirements.

Testing limitations: real player actions, ntfy delivery, a real FTP/FTPS
destination and Linux container metrics were not tested end-to-end.

## ServerHoster 2.1.0

- On a fresh installation, prepare a void outer lobby automatically on the
  first start, then remove old outer Minecraft worlds only after a panel restart
  has loaded that lobby. On upgrades with
  existing ServerHoster data, require an explicit dashboard confirmation
  before running this destructive one-time setup. Other plugin jars are
  renamed with a `.disabled` suffix so they are not loaded; their data remains
  preserved. Child data, ServerHoster configuration, password, and backups
  remain preserved.
- Host minimization applies resource and access settings without changing the
  selected world or its generator. Rollback restores the previous host files.
  The outer whitelist is emptied; the child whitelist is not changed.
- After memory-pressure recovery, clear the emergency-stop state so protection
  can respond again to a later incident and the dashboard shows recovery ended.
- Update Vite to fix the development-server advisory GHSA-8g5g-mxqh-grgj.

## ServerHoster 2.0.10

- Host minimization turns the outer whitelist on and replaces `whitelist.json`
  with an empty list. Rollback restores the previous list, or removes the file
  if it did not exist. The child whitelist is not changed. There is no extra
  login-event block.

## ServerHoster 2.0.9

- Reject every player login on the outer server while host minimization is
  staged or active, and after a clean setup has prepared the void lobby.
  Whitelist and max-players remain additional settings, not the only gate.
  Rollback removes that join block.
- Mark minimization active only when the lobby world is actually loaded.
- Write clean-setup world properties in `onLoad`, before Spigot loads worlds.
- Keep outer and child `pause-when-empty-seconds` at 0. Clear a leftover 60
  on an outer `serverhoster-lobby-*` world at startup.
- Do not write the dashboard password into the server log.

## ServerHoster 2.0.8

- Finish a clean deletion after Spigot has closed the plugin class loader.
  The shutdown hook linked its file deleter too late, so the deletion threw
  `NoClassDefFoundError` and left the marker `pending`. The deleter is now
  linked at startup. Any failure writes `aborted` and a phase. A later start
  still does not continue the deletion.

## ServerHoster 2.0.7

- Allow a new confirmed clean preview to replace a terminal `aborted` marker.
  The new plan is the one just confirmed. The old deletion list is not replayed.
  A `pending` or `done` marker still blocks another apply.

## ServerHoster 2.0.6

- Finish an accepted clean setup when the outer process actually exits. Spigot
  calls plugin disable before the JVM exits and 2.0.5 removed the shutdown hook
  there, so the deletion never ran. A pending clean now keeps that hook. The
  next startup still does not delete if the marker is pending; it marks the
  attempt aborted and requires a new preview.
- After a successful deletion the next start writes the fresh lobby and
  requires setup again, so the old child does not autostart.

## ServerHoster 2.0.5

- Fix clean-setup apply rejecting an unchanged server. The ticket now stores
  the survivor list as well as the deletion list. Apply checks that stored
  list against a fresh scan. A log, crash report, or lock file may change
  without invalidating the ticket. Any other new or removed path still
  rejects the apply.
- The clean-setup ticket and its temporary file are control files. Writing
  them does not change the plan hash. A ticket that loses its survivor list
  is rejected as incomplete.
- A provider start script inside the server root, such as `.serverstart/run.sh`,
  is deleted with the other non-boot files. The panel rewrites it on the next
  start. The Spigot jar, this plugin jar, `eula.txt`, `bundler`, `libraries`,
  and `versions` stay.

## ServerHoster 2.0.4

- Use Minecraft 26.3's official void flat preset, including `structure_overrides`.
- Choose another lobby name when that folder already exists.
- If the child does not stop, leave its supervision running and do not edit host files.
- If the state file cannot be saved after editing, restore the backed-up host files.
- Remember manageable heap-option values and put them back on rollback. They still do not change `-Xmx` and are not kept after an outer JVM restart unless minimization stays active.
- Add a separate clean-setup action. It stays off, does not run on first install, and has no built-in undo or hidden copy. Preview lists every deletion and survivor. Apply needs `CLEAN <server-identity> <plan-hash>` from a ticket that expires after 10 minutes and is rejected if the tree changed.
- Clean-setup deletes only inside the outer server directory, only after the child has stopped, and only while the outer server is shutting down. A failed stop aborts with no deletions. The Spigot jar, this plugin jar, `eula.txt`, `bundler`, `libraries`, and `versions` stay. Fresh port-preserving config is written on the next start.

## ServerHoster 2.0.3

- Add an explicit outer-host minimization. It is off until an administrator
  confirms `HOST MINIMIEREN` in the setup screen or with `/child host apply`.
  Nothing runs on first start.
- The action backs up `server.properties`, `bukkit.yml`, and `spigot.yml` under
  `plugins/ServerhosterPlugin/host-minimize-backup/` and can restore them with
  `HOST ZURUECK`. It does not delete worlds, plugins, logs, backups, or the
  child directory. A new empty lobby name is used; existing world folders stay.
- Outer view and simulation distance become 2, Nether and End are disabled,
  and mob spawning is turned off for that lobby only. Port, online-mode, and an
  already disabled RCON stay unchanged. The child still forces
  `pause-when-empty-seconds=0`.
- Manageable outer JVM options may be set. They do not change `-Xmx` and do
  not call `System.gc`. Unsupported options are reported. The plugin does not
  restart the outer host; the panel restart remains manual. A running child is
  stopped gracefully before the files are written.

## ServerHoster 2.0.2

- Keep every 2.0.1 feature. Do not change configured `Xms`/`Xmx`, Lithium or
  other gameplay settings, view/simulation distance, worlds, or pause the child
  when it is empty.
- Record child PID, start instant, working directory and jar. A later start
  stops that process only when the live process still matches; a reused PID is
  left alone.
- Reattach a detached console pipe or exit watcher, and schedule the existing
  bounded restart if the watcher missed an exit. Drain and destroy a process
  whose start fails after `ProcessBuilder.start()`.
- Keep command and dashboard stop/restart off the Spigot main thread. Run
  memory sampling and its emergency child stop on a separate scheduler so
  `save-all` and schedules are not blocked by that work. `onDisable` still
  waits for the child stop before the JVM exits.
- Add read-only `memory-telemetry.log`. Heap, RSS, host meminfo, cgroup and
  GC counters are taken on the existing memory sample. Status polling does
  not read them again. Heap and GC counters are the outer Spigot JVM
  (`heapJvm=outer`, `gcJvm=outer`); child heap and child GC are not measured.
  On `/api/diag`, `rssMb` remains the child process RSS. A GC cycle duration
  is observed, not proof about a pause target. Host swap occupancy is not
  treated as a container OOM.
- Invalid memory thresholds disable automatic child stop without rewriting
  stored config. Negative wait values still mean 0 seconds. Waits above 7 days
  are capped only in memory.
- After automatic restarts are exhausted, the status keeps the start failure
  (`start failed: ... , gave up`) instead of replacing it with `preparing`.
- Existing `config.yml` remains valid. A missing `memoryTelemetry` block uses
  the documented defaults. Startup still updates only `metadata.pluginVersion`
  (and the legacy unlimited restart value, if present).

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
