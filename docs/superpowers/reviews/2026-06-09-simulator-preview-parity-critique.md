# Simulator preview parity critique (D3-01)

Date: 2026-06-09
Branch: `codex/simulator-preview-parity`
Verdict: cleared for hardcoding removal; full raw simulator parity remains a follow-up

## Scope

Close the hardcoding inventory item `D3-01(app)`: `SimulatorController` previously returned fabricated battle state with Kotlin `.random()` and a fixed Korean log. The fix must not invent combat data, must reuse the parity battle engine, and must keep the endpoint read-only.

## PHP oracle

- `legacy/devsam-core/hwe/j_simulate_battle.php:243-249`: a posted `warSeed` forces `repeatCnt=1`.
- `legacy/devsam-core/hwe/j_simulate_battle.php:366-476`: `simulateBattle` builds DB-free battle units and runs the same war process, then rolls battle logs back.
- `legacy/devsam-core/hwe/j_simulate_battle.php:500-503`: absent fixed seed Monte-Carlo uses fresh random bytes for later repeats.
- `legacy/devsam-core/hwe/j_simulate_battle.php:565-582`: response shape is aggregate fields (`avgWar`, `phase`, `killed`, `dead`, skill maps), not winner/damage/turn/log fabrication.

## Implementation review

- `app/game-api/.../SimulatorController.kt` now resolves live `GeneralReadEntity`, `CityReadEntity`, `NationReadEntity`, and `WorldStateReadEntity` rows before simulation. Missing rows return 4xx/409 instead of constructing imaginary combatants.
- The controller delegates to `logic/war/BattleSimPreview`, which reuses `processWar` and the single `RandUtil(LiteHashDrbg(warSeed))` path already covered by `BattleSimPreviewTest`.
- `web/game/app/game/simulator/page.tsx` now sends the existing `attackerGeneralId`/`defenderGeneralId` pair and consumes aggregate fields (`phase`, `killed`, `dead`, `attackerSkills`, `defendersSkills`) instead of `attackerWon`, fake damage, or fake logs.
- The page consumes the shared `PublicGeneral` contract via `api.generalsList()` and no longer asserts phantom `train`/`atmos` fields from `/api/generals`.
- `web/gateway/components/ServerBoard.tsx` was rechecked for the latest empty-server requirement: with `servers.json.servers=[]`, it returns `null`, so login/lobby render no map, log, or server tabs.

## Adversarial checks

- Does this still fabricate a winner? No. The UI only labels `conquerCity` as `공성 성공`; otherwise it says `교전 종료`, avoiding a false defender victory claim.
- Does this consume raw Kotlin randomness? No controller randomness remains. Randomness is owned by `BattleSimPreview`; fixed `warSeed`/PHP `seed` forces one deterministic replay.
- Does this write game state? No. The endpoint reads JPA read repositories and returns the preview result; `BattleSimPreview` does not flush deltas.
- Could a missing server still render a map? No in current code: `ServerBoard` has no selected server and returns `null`.

## Residual gaps

- This closes the fake-result controller path, not the full legacy simulator surface. `j_export_simulator_object.php`, PHP `query` raw-payload import, multi-defender raw editing, and the raw manual simulator form remain missing.
- `BattleSimResult` currently lacks PHP response aggregates for `datetime`, `lastWarLog`, `attackerRice`, and `defenderRice`; those are tracked as simulator parity follow-up rather than hardcoded fake state.
- The UI uses the live-general pair flow only; full PHP raw side-by-side stat editing is still out of scope for this slice.
- `lastWarLog` is not a safe controller-only add: the current Kotlin `WarUnit` base does not expose the PHP logger rollback payload, so byte-log parity needs a real battle-log/golden slice.

## Verification

- Red evidence: before the controller implementation, `SimulatorControllerTest` failed to compile because the old controller had no repository dependencies and returned a plain `Map`.
- Green evidence:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.SimulatorControllerTest --rerun-tasks`
  - `cd web/game && pnpm typecheck`
