# 2026-06-18 join-created-general-entry review

Verdict: cleared

## Scope

- Bug: after successful character creation, the user could still be treated as having no playable general.
- User-facing surfaces: game join page, game main entry, gateway lobby server row.

## Legacy evidence

- `legacy/devsam-core/hwe/sammo/API/General/Join.php:177-182` checks existing registration by `SELECT no FROM general WHERE owner=%i`.
- `legacy/devsam-core/hwe/j_server_basic_info.php:119-126` fills lobby `me` from `general.owner`.
- `legacy/devsam-core/hwe/j_basic_info.php:14-29` resolves in-game identity from `general.owner`.
- `legacy/devsam-core/hwe/ts/PageJoin.vue:420-428` alerts success and immediately navigates to `./`.

## Root cause

opensamguk join writes the created general's ownership into game-state `general.user_id`, matching the PHP `general.owner` model. The shared game-api resolver only consulted account-side `general_owner`, which is used by claim/possession flows. A newly created general therefore existed in game state but was invisible to lobby `me`, front-info, and command ownership checks until a separate possession link existed.

## Loop

| 바퀴 | 가설 | 점수 전->후 | 채점자 | 판정 | 원인 한 줄 |
| --- | --- | --- | --- | --- | --- |
| 1 | `GeneralResolver` should prefer `general_owner` but fall back to `general.user_id`, and the join page should wait until `front-info` observes the created general before navigating. | lobby/front-info created-general tests absent and prod join could land on no-general state -> new tests pass, web build passes | `:app:game-api:test --tests ServerBasicInfoControllerTest --tests FrontInfoControllerTest`, `web/game pnpm build` | 채택 | Created generals used legacy ownership while resolver only read possession links. |

Approval waiting: 없음

## Verification

- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-api:test --tests opensamguk.gameapi.controller.ServerBasicInfoControllerTest --tests opensamguk.gameapi.controller.FrontInfoControllerTest`
- `cd web/game && pnpm build`

Existing warnings during `pnpm build`:

- `app/game/generals/page.tsx`: `aria-sort` on implicit button role.
- `app/game/tournament/page.tsx`: unstable `useMemo` dependencies for `entrants` and `bracket`.
