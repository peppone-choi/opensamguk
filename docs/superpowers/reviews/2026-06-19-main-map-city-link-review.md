# 2026-06-19 main map city link review

## Scope

메인 지도 도시 클릭이 실서버 path-server URL(`/game/s1`)에서 도시 상세 URL로 commit되지 않는 버그를 닫는다.

## Legacy Evidence

- `legacy/devsam-core/hwe/ts/components/MapCityDetail.vue:16-25`: 도시 마커의 실제 이동 표면은 `<a :href="props.href">`.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:392-394`: `disallowClick`이면 clickable을 0으로 만든다.
- `legacy/devsam-core/hwe/ts/components/MapViewer.vue:439-455`: 터치 첫 탭은 `preventDefault()`로 선택만 하고, 조건을 통과하면 city-click을 emit한다.
- `legacy/devsam-core/hwe/ts/PageFront.vue:37-49`: 메인 화면 지도는 detail map, `disallowClick=false`, city-click 허용 모드다.

## Baseline

PR#116 배포 및 s1 승격 후:

- `/health`: OK.
- `/api/servers`: `s1`, name `빼섭`, generation `0`, gameUrl `/game/s1`.
- `/api/game/sse/turn`: HTTP 200, first byte 724ms, body begins with `: proxy-connected`.
- `/game/s1`: map wrapper 700x528, canvas 698x499, city 94, myCity 1, `/api/game/api/map?neutralView=0&showMe=1` 200.
- Direct `/game/s1/city?id=1`: 정상 렌더.
- Main map DOM click: RSC `/game/s1/city?id=1&_rsc=...` is 200, but `location.href` remains `/game/s1`.

## Root Cause

`MapViewer`만 도시 이동에 `router.push()`를 사용했다. 운영 URL은 middleware가 `/game/s1/city`를 `/game/city?server=s1`로 rewrite하는 path-server 구조라서, client router가 RSC를 가져와도 visible URL commit이 안정적으로 완료되지 않았다. 같은 앱의 서버 보존 링크들은 이미 native anchor를 사용하고, legacy도 city marker href를 anchor로 둔다.

## Change

- `MapViewer` 도시 마커를 `<a href="/game/{server}/city?id=...">`로 렌더한다.
- 클릭 비활성 및 터치 첫 탭 선택은 `preventDefault()`로 유지한다.
- 테스트는 `router.push` mock 대신 실제 `href`와 비활성 `aria-disabled`/no-href를 검증한다.

## Verification

- Fresh correctness reviewer Galileo the 2nd: Verdict: cleared, findings none.
- `pnpm --dir web/game test -- MapViewer.interaction.test.tsx MapViewer.props.test.tsx`
  - Vitest 전체 14 files, 73 tests passed.
- `pnpm --dir web/game typecheck`
- `pnpm --dir web/game build`
  - 기존 lint warnings only.
- `git diff --check`

## Pending

머지/배포/s1 승격 후 Playwright로 `/game/s1` 도시 클릭이 `/game/s1/city?id=1`로 실제 URL commit되는지 재측정한다.
