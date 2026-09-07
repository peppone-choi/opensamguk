# Phase 5 이미지 — 제작·export·연결 보고 (2026-09-06)

계획 `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` §Phase 5. 정본은 `opensamguk-images`, 이 저장소에는 `web/{game,gateway}/public/**` export 만 둔다(#463 제작 경계). 제3자 게임 에셋 파생 없음(자작 SVG).

## I-1 UI 아이콘 SVG 36종 — 완료

- 정본: opensamguk-images PR #7 (머지, 태그 `v2026.09.06`). `assets/ui-icons/source/*.svg`(20×20 격자, `currentColor`, 1.5px 각진 선), 빌더 `tools/assets/build_ui_icons.py`(표준 라이브러리, `--check`, unittest 3, CI 배선).
- export: `web/{game,gateway}/public/icons/icons.svg`(sprite, `<symbol id="ico-<name>">`) + 개별 36. sprite 의 stroke/fill 은 `<use>` 상속 규칙 때문에 `<symbol>` 에 둔다(루트 `<svg>` 속성은 clone 에 닿지 않는다 — 첫 빌드에서 검게 채워진 채로 판별 실패, 수정 후 통과).
- 연결: `@opensamguk/ui` `Icon(name, size, label)` + `ICON_NAMES`. `web/shared/src/__tests__/icon.test.tsx` 가 두 앱 sprite 의 symbol id 집합과 `ICON_NAMES` 가 정확히 같은지, `fill/stroke="#…"` 가 없는지 검사한다. 적색 프로브: 이름 하나를 빼면 두 sprite 테스트가 빨개진다(확인).
- 이모지 대체: 기록 허브 7 타일(`app/game/rankings/page.tsx`), 루트 로비 12 타일(`app/page.tsx`).
- 판별 시트: `reports/ui-redesign/phase5/ui-icons-sheet.png`(16/20/32px × 청동·이끼·적갈·정보). 실화면: `records-hub-icons.png`, `lobby-tiles-icons.png`.

| 묶음 | 이름 |
|---|---|
| 부서 6 | dept-ops · dept-nation · dept-military · dept-info · dept-plaza · dept-records |
| 기록 허브 7 | hub-best-generals · hub-emperor · hub-generals · hub-kingdoms · hub-npcs · hub-hall-of-fame · hub-traffic |
| 명령 상태 4 | cmd-ok · cmd-need · cmd-no · cmd-sealed |
| 자원 4 | res-gold · res-rice · res-troops · res-provisions |
| 공통 9 | search · refresh · close · arrow-left/right/up/down · external · filter |
| 로비 타일 6 | auction · dice · diplomacy · mail · tools · members |

## I-2 빈 상태 일러스트 3종 — 완료

- 정본: opensamguk-images PR #8 (머지, 태그 `v2026.09.06-2`). `assets/ui-illustrations/source/*.svg`(96×96, 청동 `#d3b064`·이끼 `#697e58` 2색 고정), 빌더 `tools/assets/build_ui_illustrations.py`(`--check`, unittest 3, CI).
- export: `web/{game,gateway}/public/illustrations/{records-empty,posts-empty,map-pending}.svg`.
- 연결: `EmptyState` 프리미티브 `illustration?: 'records' | 'posts' | 'map'`(장식 `<img alt="">`). `web/shared/src/__tests__/emptyState.test.tsx` 가 렌더 계약과 두 앱 export 존재를 검사한다. 소비: 편년체(`world-log`)·역사(`history`)·회의실 목록·`MapViewer` 「지도 데이터 준비 중입니다.」·커뮤니티 목록(`BoardList`).
- 판별 시트: `reports/ui-redesign/phase5/ui-illustrations-sheet.png`(96/48px). 실화면(로컬 che 지도 → 접힌 지도): `map-pending.png`.

## 남은 것 · UNKNOWN

- I-3 명령 카테고리 픽토그램 6·I-4 로그인 히어로 폴백은 미착수(우선순위 2·3). 깃발 sprite·래스터 일러스트는 `sprite-gen` 이미지 생성 백엔드가 이 세션에서 동작하는지 확인하지 않았다(UNKNOWN) — 이번 두 묶음은 손제작 SVG 로 범위를 줄였다(계획 Task 5.2 「생성 도구가 못 쓰이면 손 제작 SVG 로 대체」).
- CDN 스모크는 초상 3,000건 보고서(`reports/ui-redesign/2026-09-06-rtk14-cdn-check.md`)만 있다. 아이콘·일러스트는 CDN 이 아니라 `public/` 정적 경로다.
- 티켓: OPENSAM-114/#257 은 PR 머지 시점에 닫는다. OPENSAM-203/#463·OPENSAM-238/#597 은 부분 기여 코멘트.
