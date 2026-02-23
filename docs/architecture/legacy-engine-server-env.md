# Legacy Server Environment

This document captures the legacy server-level environment settings and
utilities. References include `legacy/hwe/sammo/ServerDefaultEnv.php`,
`legacy/hwe/sammo/ServerEnv.php`, and `legacy/hwe/sammo/ServerTool.php`.

## Server Environment

- `ServerDefaultEnv` holds server-wide constants (e.g.,
  `maxGeneralsPerMinute`).
- `ServerEnv` extends `ServerDefaultEnv` and is overridden by
  `d_setting/ServerEnv.php` when present.

## Turn Term Management

`ServerTool::changeServerTerm($turnterm)` adjusts the global turn length:

1. Validates that `turnterm` divides 120 minutes.
2. Acquires the game lock (`tryLock()`), unless `ignoreLock` is set.
3. Recomputes each general's `turntime` based on the new interval.
4. Updates `game_env.turnterm` and `game_env.starttime`.
5. Broadcasts a global history notice about the change.

This is a direct mutation of runtime schedule and should be treated as an
administrative operation.
