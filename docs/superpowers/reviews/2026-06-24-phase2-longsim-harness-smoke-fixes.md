# Review: Phase 2 long-sim harness smoke fixes

**Scope:** `tools/php-golden/run_longsim.sh`, `tools/php-golden/capture_longsim.php`, `tools/php-golden/Dockerfile`, `tools/php-golden/manifest_longsim.json`, `logic/src/test/resources/golden/longsim/.gitignore`

**Reviewer:** self + adversarial workflow (2026-06-24)

**Status:** cleared

Verdict: cleared

## What changed after the initial review

1. `run_longsim.sh`
   - MariaDB readiness check now uses `mariadb-admin ping -h127.0.0.1 --protocol=tcp` instead of socket ping.
     - Socket ping reports "alive" before TCP/3306 is actually accepting connections, causing `install_scenario.php` to fail with `Connection refused`.
   - Host `OUT_DIR` is translated to the container-mounted `/work/...` path before being passed to `capture_longsim.php`.
     - Passing the macOS absolute path into the container wrote captures into a non-existent directory and left the host output empty.
   - Output directory is cleaned of stale `capture-*.json` / `manifest_longsim.json` at the start of each run.

2. `capture_longsim.php`
   - `turntime` is a MySQL datetime string, not a Unix timestamp.
   - The monotonic-advance assertion now compares `DateTimeImmutable` instances instead of casting to int (which truncated to the year and falsely failed).

3. `logic/src/test/resources/golden/longsim/.gitignore`
   - Generated capture files are ignored by default; committed fixtures must be force-added intentionally.

4. `Dockerfile` / `manifest_longsim.json`
   - No functional changes; committed with the harness.

## Verification

```bash
tools/php-golden/run_longsim.sh --months-max=12   # green
tools/php-golden/run_longsim.sh --months-max=36   # green
python3 tools/agent-system/check.py --strict      # pending after docs update
```

Captures are written to the host at `logic/src/test/resources/golden/longsim/` and the manifest is readable.

## Risks / follow-up

- `executeGeneralCommandUntil($nextTurn, $farFuture, ...)` drains all per-general actions before each month tick. This matches the `TurnExecutionHelper::executeAllCommand` inner block, but the long-sim is still NPC-only (no player commands). Player-command injection is a future Phase 4 concern.
- `capture_longsim.php` captures state snapshots every 12 months. Per-turn draw-stream capture is left for Phase 3 (`LongSimReplayGateTest`).
- The default output path is the same directory future Kotlin gates will read. The `.gitignore` prevents accidental commits of smoke output; real fixtures must be added with `git add -f`.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
