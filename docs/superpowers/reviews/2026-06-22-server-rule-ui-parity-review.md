# Server Rule and UI Parity Critique

Verdict: cleared

## Scope

- `app/game-api`, `app/game-engine`, `logic`, `infra`: command reservation, possession mode, turn phase metadata, NPC founding RNG parity, Testcontainers skip behavior, and flush/test packaging.
- `web/game`: main UI resource/stat rendering, command modal/reservation table, phase labels, message reservation, map flag sizing, and mobile no-cut layout.
- `web/gateway`: admin server lifecycle controls now consume server `scenarioCode`, avoid baked `scenario_1010` defaults, and share reduced map flag sizing.
- `.github/workflows/deploy.yml`, `README.md`: app deployer update flow now follows the docker repository as runtime orchestration source while preserving running server image pins.

## Oracle Evidence

- PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:3217-3266` keeps the non-foundable-city `0.5` draw before filtering occupied BFS candidates. The Kotlin change removes the premature occupied-current-city return and locks this with `GenFoundBodiesTest`.
- The frontend grand-truth cadence is one game year per 36 turns, expressed as `상순/중순/하순`; `web/game/lib/format.ts` and reservation tests now render those labels instead of `1순`.
- Server creation/reset/promotion is runtime admin state. `web/gateway` now reads the server's actual `scenarioCode` and no longer prefers a local hardcoded scenario when the registry exposes another scenario.

## Risk Review

- Command reservation now accepts forecast-reservable known legacy commands even when precheck returns blocked/unknown, so prediction users can fill future slots. The risk is accidental acceptance of truly unknown strings; it is bounded by `GameConst.availableGeneralCommand` plus the existing intake mapper.
- Possession is blocked when `npcmode != 1`, returning a server-mode reason instead of exposing claimable NPCs on non-possession servers.
- The deploy workflow updates the deployer container from the docker repo but does not rewrite existing server pin files, avoiding mid-game image upgrades.
- Mobile UI risk was reviewed for cut-off layouts: reservation rows now wrap command briefs/actions, and the five-stat band keeps five columns while stacking value/bar internals on narrow screens.

## Verification

- `tools/agent-system/check.py --strict --base origin/main` was run before this review and required this artifact; it is expected to pass after this file is included.
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --no-daemon --no-build-cache --no-configuration-cache -Pkotlin.incremental=false :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test :app:gateway-api:test`
- `web/game`: `pnpm test && pnpm typecheck && pnpm build`
- `web/gateway`: `pnpm typecheck && pnpm build`

## Residual Risk

- Local Docker compose smoke was not run because the local Docker socket was unavailable. CI and deploy workflows remain the post-merge production proof path.
