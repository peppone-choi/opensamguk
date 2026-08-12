# OPENSAM-9 city defence-train review

Scope: app/
Verdict: quarantined-with-proof
Proof: Focused JDK 21 MockMvc XML is green (7 tests, 0 failures, 0 errors); independently reviewed source has no findings, while bounded full and narrow game-api retries stalled during Kotlin compilation before fresh XML.

## Verdict

- Independent reviewer verdict: **APPROVE / WATCH**.
- Code findings: none at critical, high, medium, or low severity.
- WATCH condition: a fresh completed full `:app:game-api:test` result was unavailable because
  each bounded module-suite attempt stalled during Kotlin compilation before test execution.

## Scope checked

- `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CityDetailController.kt`
- `app/game-api/src/test/kotlin/opensamguk/gameapi/web/CityDetailControllerTest.kt`
- Task-local PHP evidence in `docs/loops/opensam-9-city-defence-2026-08-11/`.

## Requirement evidence

| Requirement | Evidence |
| --- | --- |
| Own metadata threshold, default `80` | `CityDetailController.kt` uses `metaInt(g.meta, "defence_train", 80)` only after the own-nation branch. |
| Boundary comparison | `minOf(g.train, g.atmos) >= defenceTrain`; MockMvc test covers 89 excluded and 90 included for threshold 90. |
| Crew gate and `999` opt-out | Existing PHP-equivalent zero-crew continue is retained; the regression fixture verifies zero crew and 999 do not add defence totals. |
| Foreign hidden/not counted | Foreign rows continue before metadata access; response train stays `-1`; the fixture verifies only enemy counters change. |
| World scope | Controller retains `GeneralReadRepository.findByCityIdOrderByTurnTimeAsc`, whose repository wrapper binds the process `WorldId`. |
| Read-only parity | Diff adds only a pure metadata read and MockMvc assertions; no write, RNG, log, schema, or raw query is added. |

## Test evidence

- RED: focused `CityDetailControllerTest` XML had 7 tests, 2 expected failures. The boundary
  assertion observed 300 instead of 200; default+999 observed 700 instead of 300.
- GREEN: focused JDK 21 XML at
  `app/game-api/build/test-results/test/TEST-opensamguk.gameapi.web.CityDetailControllerTest.xml`
  reports 7 tests, 0 failures, 0 errors (XML timestamp `2026-08-11T08:00:45`; local file
  update time `2026-08-11T17:00:57`).
- `git diff --check` completed cleanly.
- Full-module and narrow world-scope follow-up runs are explicitly blocked by Kotlin compilation
  stalls before test XML. See the task loop ledger for commands, process isolation, and recovery.

## Residual risk

The source/test behavior is independently approved, but the CI JVM run is the deciding gate:
the full game-api task and the two world-scope tests must pass there before merge. This artifact
records the local compilation quarantine for PR reviewers.
