# ServerHoster 2.0.0 implementation plan

This document tracks the agreed scope for the next major release. It is a work
plan, not a release announcement. No release is created by publishing this
document.

## Product decisions

- [ ] Support exactly one child server in 2.0.0; remove multi-profile operation
  and profile switching.
- [ ] For a new or explicitly replaced child, install only the selected plain
  server JAR. Do not preinstall mods or plugins.
- [ ] Remove the dashboard's mod/plugin catalogue, install, and toggle feature.
  Keep the general file manager for administrators who manage files themselves.
- [ ] Do not add product-specific references to mods or modpacks. Keep gameplay
  neutral and server-type agnostic.
- [ ] Keep `config.yml` and `server.properties` available as raw text editors.
- [ ] Do not add a local database, preloaded server JARs, or in-game GUIs.
- [ ] Keep dashboard HTTPS support (self-signed certificate, keystore, and PEM).
- [ ] Keep ntfy optional and provide an opportunity to configure it in the
  setup wizard.
- [ ] Preserve the required VoxelDash license notice and describe inspirations
  in release notes: Pterodactyl Wings, Crafty Controller, spark (MSPT display
  only), PaperMC Fill v3, Prism/Modrinth `.jar.disabled`, and Spigot BuildTools.

## Migration and first-run wizard

- [ ] Move the plugin data folder to `ServerhosterPlugin`.
- [ ] Store plugin version, configuration schema version, and migration state
  separately; make migrations repeatable and safe to resume after interruption.
- [ ] On first run, detect supported existing ServerHoster installations from
  1.10.1 onward. Prefer importing the old `FabricLauncher` configuration when
  present.
- [ ] If the old plugin JAR and configuration folder are absent, perform a
  bounded, read-only search for plausible existing child-server directories.
  Do not treat an ambiguous match as certain.
- [ ] When a child is detected, offer **import existing child** or
  **set up/replace child**. Require the user to confirm imported values and ask
  only for settings that are genuinely new.
- [ ] Importing preserves the selected child directory and its files without
  installing or removing server content.
- [ ] If multiple old profiles or child directories exist, require the user to
  select the one child to keep. Offer each other child as either an archive
  backup followed by removal, or deletion without an archive.
- [ ] Warn explicitly whenever an old child is neither selected to keep nor
  selected for an archive backup. Before removing or deleting any child data,
  show the exact path and require a separate confirmation containing its name.
- [ ] Offer removal of old plugin JARs and the old `FabricLauncher`
  configuration folder only after a successful migration. Keep these separate
  from child data and make cleanup optional.
- [ ] For explicit replacement, show the exact affected path and contents
  before proceeding; require a separate name confirmation. Never include the
  Spigot host's files or unrelated backups in the replacement scope.
- [ ] Test fresh setup, supported imports, missing-old-config discovery,
  ambiguous/multiple discoveries, interrupted migration, cancellation, cleanup,
  archive-and-remove, delete, and replacement safeguards.

## Dashboard schedules and tasks

Mirror VoxelDash's schedule-management workflow, using ServerHoster's backend
and storage. Do not add scheduler capabilities beyond VoxelDash.

- [ ] Support recurring hourly, daily, and weekly schedules with the applicable
  minute, time, and weekday controls.
- [ ] Show schedule name, interval, enabled state, action count, and last/next
  run information where ServerHoster can provide it.
- [ ] Create, edit, enable/disable, and delete schedules from the dashboard.
- [ ] Allow one or more actions per schedule; create, edit, delete, and reorder
  them, executing in the displayed order.
- [ ] Include backup as a first-class scheduled action and configure its
  applicable backup selection/name through the same workflow.
- [ ] Implement only action types that map to existing, safe ServerHoster
  behavior. Initially cover backup and supported console-command actions;
  omit VoxelDash actions that do not map safely or meaningfully to this plugin.
- [ ] Persist schedule definitions in ServerHoster's configuration, not a local
  database. Keep the raw `config.yml` editor and make dashboard edits and raw
  edits use one authoritative configuration state.
- [ ] Validate schedule/action inputs server-side, report errors explicitly,
  and ensure scheduler changes take effect immediately without an unnecessary
  child restart.
- [ ] Test schedule/task CRUD, recurrence/time zones, ordering, invalid input,
  manual execution, backup execution, reload/persistence, and concurrent edits.

## Recovery, diagnostics, and host shutdown

- [ ] Preserve read-only diagnostics: `jcmd Thread.print`, `GC.heap_info`,
  `VM.flags`, and the vanilla tick query. Do not introduce gameplay mods.
- [ ] Review existing memory warnings, recovery, restart, and clean-shutdown
  behavior for the single-child design; make warnings and shutdown outcomes
  explicit.
- [ ] Investigate which container/cgroup metrics are actually available and
  trustworthy on supported hosts, including memory limits, current usage,
  pressure/events, swap, and data freshness.
- [ ] Establish whether any sustained critical-state trigger can be supported
  with sufficiently reliable evidence and safe hysteresis. Missing, stale, or
  contradictory metrics must never trigger an automatic host stop.
- [ ] In the wizard, ask whether the Spigot host is configured to come back
  online after a clean stop and point to setup documentation. Keep host-stop
  recovery disabled unless explicitly confirmed.
- [ ] If a reliable trigger is demonstrated, allow the plugin to use a clean
  Spigot-host stop automatically for a sustained critical condition after
  warning and attempting a clean child shutdown. Add cooldown and restart-loop
  protection. If reliability cannot be demonstrated, do not implement the
  automatic host-stop trigger.
- [ ] Test metric availability/freshness, threshold duration and recovery,
  child-first shutdown, host-stop gating, cooldown, and loop prevention.

## Backups, documentation, and release readiness

- [ ] Preserve on-demand backups and configurable scheduled backups, optional
  external JDBC metadata, and local/remote backup options; do not bundle a
  database driver or create a local database.
- [ ] Review dashboard pages against ServerHoster's actual data and single-child
  model; retain useful existing dashboard information and the VoxelDash-inspired
  workflows that remain in scope.
- [ ] Update setup, migration, scheduling, backup, recovery, configuration, and
  rollback documentation. Describe external host restart configuration without
  coupling the product wording to a particular hosting provider.
- [ ] Keep the project build on JDK 25 and Spigot API 26.3.
- [ ] Run focused and full tests/CI, inspect the final plugin JAR and dependency
  contents, and verify an upgrade/rollback path before release.
- [ ] Update version and release notes to 2.0.0 only after the agreed scope is
  implemented and validated.
- [ ] Do not install 2.0.0 on a live host or create a GitHub release without
  separate user authorization.

## Work log

- [x] Agree the 2.0.0 scope and migration safety requirements.
- [x] Inspect VoxelDash's schedule UI and record the applicable workflow.
- [ ] Begin implementation after publishing this plan.
