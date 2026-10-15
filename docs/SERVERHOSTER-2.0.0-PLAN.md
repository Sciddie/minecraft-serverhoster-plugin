# ServerHoster 2.0.0 implementation plan

This is the agreed scope and current completion checklist. Publishing this
document does not itself create a release.

## Product and operating rules

- [x] Manage exactly one child; remove profile switching and multi-profile
  operation.
- [x] Keep gameplay neutral. Do not preinstall mods or plugins.
- [x] Remove dashboard mod/plugin catalogues, installation, and toggles.
- [x] Keep raw `config.yml` and `server.properties` editors.
- [x] Do not add a local database, preloaded server JARs, or in-game GUIs.
- [x] Keep optional dashboard HTTPS (self-signed, keystore, and PEM).
- [x] Keep ntfy optional and offer setup in the wizard.
- [x] Retain the VoxelDash MIT notice and document inspirations in release
  notes: Pterodactyl Wings, Crafty Controller, spark (MSPT display only),
  PaperMC Fill v3, Prism/Modrinth `.jar.disabled`, and Spigot BuildTools.

## Migration and first-run wizard

- [x] Rename the plugin data folder to `ServerhosterPlugin`.
- [x] Store plugin version, config schema version, and migration state in
  metadata.
- [x] Detect old ServerHoster installations from 1.10.1 when the old JAR
  confirms its version; require explicit confirmation when it is missing.
- [x] When old plugin data is absent, perform a bounded read-only scan for
  likely direct child directories and make ambiguity explicit.
- [x] Provide import, attach, new setup, and separately confirmed archive-and-
  replace workflows. A fresh child is not auto-started before setup confirms.
- [x] Import the selected child settings and supported old scheduled tasks
  without installing or changing child server content.
- [x] Offer optional old plugin file cleanup; preserve local backups, memory
  reports, and recovery state.
- [x] Make confirmed archive/delete operations recoverable after interruption:
  failed setup writes restore the original child, committed archive operations
  finish into a dated archive, and committed deletes finish only the explicitly
  confirmed deletion. Recovery preserves collisions and unsafe paths.
- [x] When multiple child directories are discovered, show them all and make
  clear that only the selected child is changed; unselected children remain
  untouched for separate admin handling.
- [ ] Cover fresh setup, supported/unknown-version imports, missing-config
  discovery, ambiguous results, interruption, cancellation, cleanup, archive,
  and path-safety cases with integration tests.

## Dashboard schedules

- [x] Implement hourly, daily, and weekly schedules with minute/time/weekday
  controls.
- [x] Show schedule name, interval, enabled state, action count, and available
  last/next-run information.
- [x] Add dashboard create, edit, enable/disable, delete, and manual-run flows.
- [x] Support ordered backup and console-command actions; do not add action
  reordering or actions that do not map safely to ServerHoster behavior.
- [x] Store schedules in `config.yml`, sharing the authoritative configuration
  with its raw text editor.
- [x] Validate schedule/action values on the server and apply updates without
  requiring a child restart.
- [ ] Add broader execution/integration tests for recurrence, ordering,
  manual execution, backup failure/recovery, reload, and concurrent edits.

## Recovery, diagnostics, and shutdown

- [x] Keep diagnostics read-only: `jcmd Thread.print`, `GC.heap_info`,
  `VM.flags`, and vanilla `tick query`; add no diagnostic or gameplay mod.
- [x] Review memory warning, recovery, restart, and clean-shutdown behavior
  for the one-child model.
- [x] Keep the critical memory trigger dependent on a known finite cgroup
  limit and repeated critical readings; missing/unknown limits do not trigger
  a stop.
- [x] Critical memory protection can warn and gracefully stop only the child.
  Automatic outer-Spigot shutdown is excluded; configure host restarts in the
  hosting provider's own scheduler if desired.
- [ ] Expand tests for metric availability/freshness, threshold duration,
  recovery hysteresis, child-only shutdown, cooldown, and restart-loop safety.

## Backups, docs, and release

- [x] Keep on-demand and configurable scheduled backups, local/FTP/FTPS
  storage, and optional external JDBC metadata; bundle no database driver and
  create no local database.
- [x] Align dashboard workflows with the single-child model and retain the
  applicable VoxelDash-inspired schedule workflow.
- [x] Update setup, migration, operations, recovery, configuration, and
  rollback documentation.
- [x] Keep the build target at JDK 25 and Spigot API 26.3.
- [x] Set release metadata to 2.0.0, run full verification, inspect the final
  JAR/dependencies, and test interrupted child-directory recovery.
- [ ] Publish the GitHub 2.0.0 release after release-readiness checks. Do not
  install on a live host without separate explicit approval.

## Work log

- [x] Agree the 2.0.0 scope and migration safety requirements.
- [x] Inspect VoxelDash's schedule UI and record the applicable workflow.
- [x] Publish the plan and begin implementation.
- [x] Implement schedule CRUD, recurrence execution, ordered actions, and the
  dashboard editor.
- [x] Complete migration recovery tests and verify release metadata, build,
  and packaged dependencies.
