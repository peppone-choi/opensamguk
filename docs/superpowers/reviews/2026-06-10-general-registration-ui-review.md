# General Registration UI Review

## Scope

- `app/game-api` now exposes `front-info.global.blockGeneralCreate` for the unowned-character registration gate.
- `web/game` unowned-character UI now shows direct creation, NPC possession, and select-pool registration as separate options.
- `web/game/game/select-pool` is added as a QA-visible blocked surface for the currently unimplemented select-pool path.

## PHP Source Of Truth

- `legacy/devsam-core/hwe/v_join.php` is direct general creation.
- `legacy/devsam-core/hwe/select_npc.php` is NPC possession mode when `npcmode == 1`.
- `legacy/devsam-core/hwe/select_general_from_pool.php` is select-pool mode when `npcmode == 2`, with optional custom creation controls.
- `legacy/devsam-core/hwe/j_get_select_pool.php`, `j_select_picked_general.php`, and `j_update_picked_general.php` are the select-pool mutation/read endpoints.

## Parity Evidence

- Direct creation already maps to `POST /api/join` and `TurnDaemonCommand.MakeGeneral`.
- NPC possession remains mapped to `GET /api/generals/claimable` and claim actions.
- Select-pool backend commands exist as wire/engine placeholders, but `SelectPoolHandler` still returns `미구현`; the UI therefore exposes the mode without pretending it is playable.
- `block_general_create` is read from the same world config source as `JoinController` so the frontend can disable direct creation for bit 1 instead of hardcoding availability.

## Critical Review

Verdict: cleared

- Risk: showing only possession makes `npcmode == 0` and `npcmode == 2` servers look broken. Mitigation: the gate now renders all three legacy registration surfaces.
- Risk: select-pool could look complete while backend parity is absent. Mitigation: the page is explicitly blocked and does not call mutation APIs.
- Risk: direct creation could remain visible when PHP would block it. Mitigation: `front-info.global.blockGeneralCreate` is exposed and consumed by the UI.
- Risk: hiding unsupported select-pool would make QA miss the remaining port. Mitigation: the blocked route is first-class and links from the registration gate when `npcmode == 2`.

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests 'opensamguk.gameapi.controller.FrontInfoControllerTest'`
- `pnpm --dir web/game typecheck`

## Follow-Up Gate

- Implement select-pool read/pick/update only with PHP parity tests for `j_get_select_pool.php`, `j_select_picked_general.php`, and `j_update_picked_general.php`.
