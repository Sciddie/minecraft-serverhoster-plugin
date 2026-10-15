# ServerHoster 2.0.1 hardening and readiness checklist

This patch release hardens restart behavior and fills test gaps identified
after 2.0.0. A successful unit/build pipeline is not, by itself, proof of
production readiness on a hosting provider.

## Implemented in this worktree

- [x] Pause repeated automatic child restart attempts after five failures by
  default; allow administrators to configure the cap and backoff in a
  dedicated settings tab.
- [x] Reset the retry counter after a manual start and migrate the 2.0.0
  unlimited-retry default to the bounded value.
- [x] Fail closed on invalid restart limits and validate typed settings before
  saving; keep FTP/FTPS credentials in `secrets.yml`.
- [x] Exercise retry caps, migration, ordered schedule actions, memory
  thresholds/recovery hysteresis, ambiguous discovery, and interrupted setup
  recovery in unit tests.
- [x] Update 2.0.1 version metadata and operator documentation.

## Staging results (Zap Spigot 26.3 / Java 25, copy of real 1.10.1 data)

Verified on 2026-10-15 with a whitelist-locked, loopback-bound child and no
players; the host was rolled back afterwards from a provider backup.

- [x] Import of a real 1.10.1 install; this exposed an imported
  `maxAttempts: 0` and led to the version-independent migration to five.
- [x] Dashboard over self-signed HTTPS; settings save/reload; invalid values
  rejected without changing the stored config.
- [x] Child start, stop during startup (clean exit 0, no restart), failing
  child stops after the configured retry cap.
- [x] Real FTP backup, SHA-256-verified download, restore (guarded while the
  child runs, confirmation required), delete of the test backup only.
- [x] Read-only diagnostics (`Thread.print`, `GC.heap_info`, `VM.flags`) and
  vanilla tick query; clean child and host stop.
- [x] Provider-side backup restore as rollback.

Not tested: controlled memory-pressure run (covered by unit tests only).

## Required before calling it production-ready

- [ ] Run the release CI workflow and inspect the exact published JAR,
  signature/checksum, and GitHub release asset.
- [ ] Observe a representative server for at least 24 hours and verify that
  child restart counters reset only after the configured healthy uptime.
  This is confirmed by the administrator after installing the release.
- [ ] If feasible, conduct a controlled memory-pressure test on staging and
  verify warnings, child-only graceful stop, recovery cooldown, and no outer
  Spigot shutdown.

Do not install this patch on the live Zap instance until the administrator
explicitly approves that installation.
