# Legacy Logging and Versioning

This document summarizes logging utilities and version helpers. References
include `legacy/hwe/sammo/ActionLogger.php`, `legacy/hwe/sammo/UserLogger.php`,
and `legacy/hwe/sammo/VersionGitDynamic.php`.

## ActionLogger

`ActionLogger` aggregates logs for a general, nation, and global context:

- Buffers multiple log channels:
  - General history/action/battle logs
  - National history logs
  - Global history/action logs
- Supports formatted prefixes (`PLAIN`, `YEAR_MONTH`, `NOTICE`, etc.).
- `flush()` emits buffered logs via helper functions
  (`pushGeneralHistoryLog`, `pushGlobalHistoryLog`, etc.).

It is used throughout command execution, battle resolution, and event actions.

## UserLogger

`UserLogger` writes user-scoped logs to `user_record`:

- Used for inheritance point spending/awards and other user-level events.
- `push($text, $type)` buffers entries; `flush()` inserts into DB with
  `{user_id, server_id, year, month, date, text}`.

## Version Helpers

`VersionGitDynamic` exposes runtime Git metadata:

- `getVersion()` calls `git describe --long --tags` and `git branch`.
- `getHash()` calls `git rev-parse HEAD`.

These are used for diagnostic display and do not affect gameplay logic.
