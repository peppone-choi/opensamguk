# Game Admin UI QA Review

## Scope

- `app/game-api` exposes read-only admin1/admin2 data for production QA.
- `web/game` adds `/game/admin1` and `/game/admin2` pages for PHP admin surface comparison.
- `web/game` admin navigation now links admin1/admin2/admin5/admin7/admin8 from the in-game admin menu.

## PHP Source Of Truth

- `legacy/devsam-core/_admin1.php` is the game-management surface for notices, world history text, start time, max generals, max nations, start year, and turn options.
- `legacy/devsam-core/_admin1_submit.php` mutates `game_env`, world log rows, and turn timing through legacy server tooling.
- `legacy/devsam-core/_admin2.php` is the general/member moderation surface for block state, forced death, kill turn, dex bonus, message, wandering, haysay, and disband actions.
- `legacy/devsam-core/_admin2_submit.php` is the mutation source of truth for those admin2 actions.

## Parity Evidence

- The new `app/game-api` read endpoints map current schema-backed data only: `world_state`, `game_kv`, `general`, and reserved `general_turn` state.
- The `game_env/global/msg` read path matches the current `JdbcFlushExecutor` namespace contract and decodes JSON scalars through the shared logic JSON helper.
- Unsupported PHP write actions remain visible in `web/game` but disabled with explicit blocked reasons; this preserves QA discoverability without inventing non-parity mutations.
- Direct game-api writes were intentionally rejected because admin1/admin2 write parity needs explicit game-engine intake handlers, ChangeRecorder deltas, and PHP parity tests.
- `web/game` render parity is structural rather than byte-for-byte CSS parity: the pages expose the same admin groups and controls needed for QA while keeping modern React layout conventions.

## Critical Review

Verdict: cleared

- Risk: admin1 notice text could silently miss production values if the namespace differs from seed or flush writes. Mitigation: the endpoint checks `global`, `game_env`, empty, and `default` namespaces, with a regression test covering `global`.
- Risk: string JSON decoding could corrupt tab/newline-heavy Korean notices. Mitigation: the endpoint uses `opensamguk.logic.util.jsonDecodeAny` and a regression test covers escaped tab decoding.
- Risk: admin2 could issue incomplete or unsafe moderation writes. Mitigation: all write buttons are disabled until explicit engine intake contracts exist.
- Risk: QA could miss missing PHP actions if unsupported actions were hidden. Mitigation: blocked action catalogues are returned by the API and rendered in the UI.
- Risk: read endpoints could leak admin data to non-admin users. Mitigation: both endpoints use the existing `requireAdmin` gate, with regression tests for non-admin rejection.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.controller.AdminReadControllerTest'`
- `pnpm --dir web/game typecheck`
- `pnpm --dir web/gateway typecheck`
- `git diff --check`
- Production authenticated GET smoke for existing admin/game surfaces before deployment.

## Follow-Up Gate

- Wire admin1/admin2 writes only after each PHP submit path has a game-engine intake command, ChangeRecorder flush proof, and parity tests.
- Add browser-rendered production verification for `/game/admin1` and `/game/admin2` after deployment.
