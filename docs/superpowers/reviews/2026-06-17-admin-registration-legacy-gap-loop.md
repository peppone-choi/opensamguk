# Admin and registration legacy gap loop

## Skill chain evidence

- `opensamguk-php-oracle`: legacy registration entry is `legacy/devsam-core/hwe/ts/gateway/entrance.ts:47-58`; join page data source is `legacy/devsam-core/hwe/v_join.php:41-78`; Vue rendering is `legacy/devsam-core/hwe/ts/PageJoin.vue:31-52` and `legacy/devsam-core/hwe/ts/PageJoin.vue:136-230`; inherit page is `legacy/devsam-core/hwe/v_inheritPoint.php:74-107`.
- `webapp-testing`: production/API baseline showed `/game/s1/register` 404 and unauthenticated `/api/admin/game-settings` 401 after nginx direct routing. Local browser loop used the helper script plus Playwright MCP: `/game/register?server=s1` reached `/game/join?server=s1`, rendered nation `scoutMsg`/`infoText`, and exposed the inherit section controls. A mock game-api confirmed gateway `GET`/`PATCH /api/game/api/admin/game-settings` forwards `sam_access` as `Authorization: Bearer`.
- `systematic-debugging`: root cause is not one symptom. Gateway admin called game-api directly without the httpOnly cookie-to-Bearer proxy; registration had no `/game/register` alias; join UI used map preview nations without `scout_msg`/`infoText`; inherit submit remains blocked by the engine dispatcher gate.
- `loop-engineering`: one hypothesis for this pass is to restore missing gateway/game frontend seams without weakening the engine deny gate for unimplemented inherit spending.

## Mapped tests and gates

- `MapPreviewControllerTest.preview nations expose join scout message and scenario info text` pins `scout_msg` and `infoText`.
- `pnpm typecheck` and `pnpm build` for `web/game` pin the `/game/register` alias and join-page render contract.
- `pnpm typecheck` and `pnpm build` for `web/gateway` pin the new `/api/game/[...path]` admin proxy route.
- Browser evidence: Playwright MCP snapshot after mock auth/game-api showed `/game/join?server=s1`, `인재를 구합니다`, `한실부흥`, `유산 포인트 사용`, `보유한 유산 포인트`, `필요 유산 포인트`, `천재로 생성`, `도시`, and `턴 시간 지정`.
- Gateway proxy evidence: mock game-api returned `{"ok": true, "auth": "Bearer admin-token"}` for `GET`, and `{"ok": true, "auth": "Bearer admin-token", "body": "{\"turnterm\":20}"}` for `PATCH`.
- Post-merge/deploy browser verification must re-check `/game/s1/register` no longer returns 404 and admin game settings no longer fails through the gateway UI.

## Blocked

- Full legacy inherit-point spending during general creation is still blocked by `TurnDaemonCommandDispatcher.dispatchMakeGeneral`: inherit options return `MakeGeneralFail` until the engine handler consumes point deduction and side effects.

Verdict: cleared
