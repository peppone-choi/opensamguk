# NpcPolicy defaultPolicy parity critique

Date: 2026-06-09
Slice: `D3-02(app)` NpcPolicyController hardcoding removal
Verdict: cleared

## Source of truth

- PHP grand truth: `legacy/devsam-core/hwe/sammo/AutorunNationPolicy.php:152-180`
- Required shape: `AutorunNationPolicy::$defaultPolicy`
- Required values include `reqNationGold=10000`, `reqNationRice=12000`, empty force lists, `minNPCWarLeadership=40`, and `minNPCRecruitCityPopulation=50000`.
- Rejected keys: `reqHumanWarUprising` and `autorun_user` are not PHP nation policy keys.

## Adversarial checks

- The controller no longer owns a private policy map; it serializes `opensamguk.logic.ai.AutorunNationPolicy.DEFAULT_POLICY`.
- The logic-layer test asserts the full ordered PHP default-policy shape so future controller edits cannot silently reintroduce a partial map.
- The game-api controller test asserts the previously wrong fields (`reqNationGold`, `reqNationRice`) and the absence of fabricated keys.

## Evidence

- Red check before implementation: `:app:game-api:test --tests opensamguk.gameapi.controller.F4ReadControllersTest --rerun-tasks` failed with `$.defaultPolicy.reqNationGold expected:<10000> but was:<0>`.
- Green check after implementation:
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test --tests opensamguk.logic.ai.AutorunNationPolicyTest --rerun-tasks`
  - `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.F4ReadControllersTest --rerun-tasks`
  - XML summaries: `AutorunNationPolicyTest` 21 tests, 0 failures, 0 errors; `F4ReadControllersTest` 25 tests, 0 failures, 0 errors.

## Residual risk

- `web/game/app/game/npc-control/page.tsx` appears to expect `defaultNationPolicy`/`currentNationPolicy` naming while the backend response currently exposes `defaultPolicy`/`currentPolicy`. That is a separate read-shape parity gap and was not mixed into this hardcoding-removal slice.
