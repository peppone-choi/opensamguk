# PR44 Cross-Agent Critique

Scope: PR #44, `parity-final` against `origin/main`.

Reviewers:
- Descartes (`ce-project-standards-reviewer`): provider-agnostic working-system and tooling.
- Banach (`parity-reviewer`): parity and production invariants.

## Descartes Findings

Verdict: fix-required

1. HIGH: mandatory `parity-close` and `parity-ship` routing was not reproducible from the committed `skills-lock.json`.
2. HIGH: `tools/agent-system/check.py` false-greened the cross-agent critique rule by checking only static phrases.
3. MEDIUM: production and quick-start docs mixed local auto-seed and production empty-server startup, while the checker only enforced the empty server list.

## Resolution

Verdict: cleared

- Added provider-agnostic fallback steps for `parity-close` and `parity-ship` to `docs/superpowers/WORKING_SYSTEM.md`.
- Changed strict `tools/agent-system/check.py` to require a changed `docs/superpowers/reviews/*.md` critique artifact with an explicit verdict.
- Changed `docker-compose.production.yml` to default `SCENARIO_SEED_ENABLED` to `false`.
- Extended `tools/agent-system/check.py` to fail if production seed defaults to enabled.
- Made empty server startup explicit in `web/gateway/components/ServerBoard.tsx`: no servers means no map, no log, and no server tabs on login/lobby.

## Banach Findings

Verdict: fix-required

P0 blockers:

1. `app/game-api/src/main/kotlin/opensamguk/gameapi/web/JoinController.kt`: Join publishes `MakeGeneral` without the full PHP precheck: `block_general_create`, `maxgeneral`, sanitized-name, and exact deny-string order.
2. `app/game-engine/src/main/kotlin/opensamguk/engine/intake/MakeGeneralHandler.kt`: city pool lacks PHP fallback from neutral level 5-6 cities to all level 5-6 cities.
3. `app/game-engine/src/main/kotlin/opensamguk/engine/intake/MakeGeneralHandler.kt`: uses daemon `Instant.now()`/UTC instead of PHP-equivalent request/server timestamp and `game_env.turntime`.
4. `app/game-engine/src/main/kotlin/opensamguk/engine/intake/MakeGeneralHandler.kt`: MakeGeneral create path omits drawn `affinity` from `meta`, so flush cannot persist PHP affinity.
5. `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/PossessionController.kt`: B2 possession claim publishes `ClaimNpc` without PHP member penalty payload and uses guessed nickname instead of root `member.name`.
6. `app/game-api/src/main/kotlin/opensamguk/gameapi/owner/GeneralPossessionService.kt`: B2 claim lacks PHP `npcmode == 1` and `gencount < maxgeneral` guards.
7. `app/game-api/src/main/kotlin/opensamguk/gameapi/owner/SelectNpcTokenService.kt`: B2 select NPC token response stores/replays static `__pickMoreSeconds` instead of PHP dynamic `pick_more_from - now`, refresh, and keep semantics.
8. `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt` and `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt`: common wire changes are covered by wire tests in prior verification but still inherit the MakeGeneral/B2 semantic blockers above.

P1 blockers:

1. `logic/src/main/kotlin/opensamguk/logic/world/GeneralBuilder.kt`: `GeneralBuilder.build()` hard-codes `isFictionMode = false` while PHP derives it from env.
2. `web/game/app/game/join/page.tsx`: Join UI hardcodes stat/personality options and uses client-side `Math.random()` presets.
3. `tools/agent-system/check.py`: behavior evidence check is too coarse because any evidence file can clear the whole behavior diff.

## Banach Resolution

Verdict: fix-required

- The agent-system false-green is being tightened in this PR by mapping evidence to each changed behavior area.
- The Join/B2/GeneralBuilder findings are real parity blockers and must not be merged/deployed as “cleared” until fixed or quarantined with proof.
- This PR may continue as an open PR with CI green, but latest critique verdict remains `fix-required`; merge/deploy is blocked.
