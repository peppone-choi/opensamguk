# UI 리디자인 이미지 매니페스트 (Phase 5 · Task 5.1)

정본은 `opensamguk-images`(`originals/`·`tools/`·`exports/`·`previews/`)에 두고, 이 저장소에는 `web/{game,gateway}/public/**` export만 둔다(#463 제작 경계). 아트보드 19장의 이미지 참조를 집계한 결과다(`src/*.body.html`: `<img>` 15 · `@@PT` 120 · `@@FLAG` 46 · `@@ISO` 8 · 인라인 `<svg>` 36).

## 이미 있는 것(만들지 않는다)
| 아트보드 참조 | 현재 원천 | 비고 |
|---|---|---|
| `logo-wordmark.png` / `-sm.png` | `web/*/public/logo-wordmark.png` (마스터 `assets/brand/logo-master.png`) | 브랜드는 이것만(텍스트 워드마크 금지) |
| `@@PT(…)` 초상 3종 | RTK14 CDN `portraits/rtk14/serving/{original,portrait,icon}` (ADR-LITE-048, 3000/3000 200) + 기본 초상 `portrait-default.svg` | 아이콘 96 / 카드 148×210 / 원본 |
| `@@FLAG(#hex)` 깃발 | `@opensamguk/ui` `Flag`(인라인 SVG, 국가색 mask) + `public/flags/flag-{cloth,pole}-{0..3}.png`(지도 마커) | sprite 추가 불필요 |
| `@@ISO(…)` 지도 | `HanMapCanvas`(런타임 렌더, `public/map/tiles`) | 이미지 아님 |
| `cast-capital/town/city.png` 도시 등급 | `public/city/cast_{1..11}.png`(`build_city_icons.py`) | 06 도시 히어로에 연결 시 재사용 |
| 상태 아이콘(수도 별·재해·황실 NPC) | `public/status/**`(`build_status_icons.py`) | 1x/2x |

## 만들어야 하는 것
| # | 항목 | 규격 | 쓰이는 곳 | 우선 |
|---|---|---|---|---|
| I-1 | **UI 아이콘 SVG 세트** — 부서 6(작전실·국가 운영·군사·정보·광장·기록), 기록 허브 7(명장·황제·장수·세력·NPC·명예의 전당·접속), 명령 상태 4(가능·부족·불가·봉인), 자원 4(금·쌀·병력·군량), 공통 8(검색·갱신·닫기·화살표 4·외부 링크·필터) | 16/20px, 1px 청동 선, 단색(currentColor), 각진 모서리 | `records-hub` 타일(지금은 이모지 ⚔️👑📜🏛️🤖🏆📊), 루트 `app/page.tsx` 타일, `PillTabs`·`SectionHeader` 장식, 명령 타일 | 1 |
| I-2 | **빈 상태 일러스트 3종** — 「기록이 없습니다」(두루마리), 「게시물이 없습니다」(빈 상소), 「지도 데이터 준비 중」(접힌 지도) | 96×96, 2색(청동+이끼) | `EmptyState` 프리미티브 | 2 |
| I-3 | **명령 카테고리 픽토그램 6** — 내정·군사·인사·외교·특수·기타 | 20px, I-1 과 같은 규격 | `CommandModal` 카테고리 탭 | 2 |
| I-4 | **로그인 히어로 배경**(선택) — 먹·검 이미지의 상단 마스크 버전 | 1440×420 webp, 24bit | 01 로그인 | 3 |

## 스타일 가이드(요약, 정본은 `opensamguk-images/originals/ui-2026-09/STYLE.md`)
- Concept A 팔레트 10색 제한(`#0c0f0e #1b201d #141816 #232a26 #2c342f #3d4740 #d3b064 #697e58 #c96b5d #7aa7c7`), 각진 모서리, 1px 청동 테두리.
- 16px 에서 판별되어야 한다(nearest-neighbor 축소 시트로 검사). 색각 비의존 — 형태 차이 필수.
- 제3자 게임 에셋(Koei 등) 금지. RTK14 초상은 소유자 결정(ADR-LITE-048)으로 예외.

## 파이프라인(Task 5.2)
- SVG 아이콘: 손으로 그린 원본 SVG(정본) → `svgo` → sprite(`icons.svg` `<symbol id="…">`) + 개별 export → `web/{game,gateway}/public/icons/`.
- 래스터: 기존 `tools/assets/build_*_icons.py` 규약(재생성 `--check`, manifest 에 파라미터).
- 소비: `@opensamguk/ui` `Icon`(name → `<use href="/icons/icons.svg#name">`).
