# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-10-30

### Added

- **`TrackingRuntime` (Android)** — process-wide singleton on `applicationContext` that owns all
  engines and lifecycle state, decoupled from the React context. Tracking now survives RN teardown,
  process death, and reboot.
- **`StatusWatcher` (Android)** — native RTDB `ValueEventListener` armed while tracking is active.
  When the watched `statusPath` equals `stopValue`, tracking stops natively — works even while the
  JS layer is dead. The first snapshot after `arm()` is also evaluated, so a stop value written
  while the app was dead is still honored.
- **Self-healing foreground service (Android)** — on `START_STICKY` restart and `BOOT_COMPLETED`,
  engines are rebuilt from the persisted config and tracking resumes. If resume is impossible
  (permission revoked, missing config, `resumeOnRestart: false`), the service stops itself —
  no more "zombie notification" where the notification survives but tracking is dead.
- **Observability markers** — `getStatus()` now returns `nativeWatching`, and one-shot
  `lastRestartReason` (`sticky_restart`, `boot`, `service_destroyed_while_tracking`) and
  `lastStopReason` (`manual`, `remote_status`, `permission_revoked`, `resume_failed`,
  `resume_disabled`). Markers are consumed (cleared) on first read — intended for interruption
  reporting at app launch.
- **Optional `control` config** — `control.statusPath`, `control.stopValue` (default `2`),
  `control.resumeOnRestart` (default `true`). When `statusPath` is omitted it is derived from the
  first RTDB target's parent path + `/status`. Absent `control` = identical behavior to 0.1.x.
- **Unit tests** — `StatusWatcherTest`, `BootReceiverTest` updates, validation tests for the
  `control` config.

### Changed

- **`LiveTrackingModuleImpl` (Android)** is now a thin delegate over `TrackingRuntime` — only
  promise mapping and JS event emission remain. `invalidate()` (both architectures) detaches the
  event listener so a dead `ReactApplicationContext` is not retained by the runtime.
- **`TrackingForegroundService` (Android)** reports its start reason to the runtime and stops
  itself when resume fails.
- **`TrackingStateStore` (Android)** persists the full config JSON for native engine rebuilds.
- **`getStatus()` (iOS)** returns `nativeWatching`, `lastRestartReason`, `lastStopReason` as inert
  values (`false`/`null`) for full parity with the `TrackingStatus` contract.

### Fixed

- Pre-existing broken unit tests: `QueueEngineTest` referenced a removed Room DAO (converted to
  `SQLiteOpenHelper`); `SyncEngineControllerTest` failed on unstubbed `org.json` — resolved via
  `org.json:json` test dependency.

### Notes

- `stop()` is idempotent — safe when both the JS path and the native watcher fire.
- The first stop reason wins: a later idempotent `stop()` does not overwrite a specific reason
  such as `remote_status`.

## [0.1.0]

- Initial public release: background location tracking, per-target Firebase sync with batching,
  retry with exponential backoff, per-target offline queue, motion sleep mode, GPS pause/resume,
  boot restart (Android), significant location monitoring (iOS).
