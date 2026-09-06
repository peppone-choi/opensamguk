# UI 리디자인 Phase 0 (기반) 스크린샷·게이트 리포트

- 날짜: 2026-09-06 · 브랜치 `work/opensamguk/ui-redesign-2026-09` · 계획 `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md` Phase 0
- 정본: ADR-LITE-049 (야전 사령부 · Concept A)
- 스택: 로컬 Docker 백엔드(postgres·redis·gateway-api·game-api·game-engine, **2주 전 로컬 이미지**) + `web/gateway`·`web/game` dev 서버(이 브랜치). board-api 이미지 없음 → 커뮤니티 목록은 「게시판 서버에 연결할 수 없습니다」 상태로 찍힘.
- 뷰포트: 1440×1000 · 390×844. 파일: `reports/ui-redesign/phase0/*.png` (스크립트는 Playwright, 세션 scratchpad).

## 게이트 결과

| 항목 | 결과 |
|---|---|
| `web/shared` vitest | 23/23 |
| `web/game` vitest | 642/642 (88 파일) |
| `web/gateway` vitest | 231/231 (26 파일) — `MapPreview.iso` 1건은 main 에서도 빨갛던 stale 단언(#638 이후)을 새 동작에 맞춤 |
| typecheck (shared·game·gateway) | 오류 0 |
| lint (game·gateway) | 신규 경고 0 (기존 `<img>`·exhaustive-deps 경고만) |
| `next build` game / gateway | 성공. 폰트 self-host 산출물 `.next/static/media` 225 파일 8.85 MB(unicode-range 슬라이스 — 브라우저는 필요한 조각만 받는다) |
| RTK14 초상 CDN 검증 `tools/assets/check_rtk14_cdn.py` | 1,000 ID × original/portrait/icon = 3,000 요청 전부 200 (`reports/ui-redesign/2026-09-06-rtk14-cdn-check.md`) |
| e2e 3건 | 미실행 — 라이브 스택이 옛 이미지라 `v1-core-live` 전제가 맞지 않음. 백엔드 현재 코드 이미지 재빌드 뒤 Phase 1 게이트에서 돌린다 (UNKNOWN 아님, 미실행) |

## 스크린샷 (토큰·쉘만 바뀐 상태, 화면 본문은 아직 옛 레이아웃)

| 화면 | 데스크톱 | 모바일 | 관찰 |
|---|---|---|---|
| 01 로그인 | `01-login-desktop.png` | `01-login-mobile.png` | 팔레트·워드마크 반영. 폼 본문은 Phase 2 |
| 회원 가입 | `01b-join-desktop.png` | — | 라벨·검증 문구 그대로 |
| 02 로비 | `02-lobby-desktop.png` | `02-lobby-mobile.png` | 새 상단바(로비·게시판·계정·로그아웃). 서버 목록은 dev 레지스트리 빈 값이라 「이용할 수 있는 서버 없음」 |
| 계정 | `account-desktop.png` | `account-mobile.png` | |
| 13 커뮤니티 | `13-community-desktop.png` | `13-community-mobile.png` | board-api 미기동 상태 메시지 |
| 장수 생성 | `join-general-desktop.png` | `join-general-mobile.png` | **발견**: 「장수 생성」 버튼 배경 투명 — `--color-primary` 가 어느 토큰에도 정의된 적 없음(이번 변경 전부터). 별칭 추가 + 공용 버튼으로 수정(커밋 참조) |
| 작전실(메인) | `03-game-main-*.png` | | 장수 없는 계정이라 join 으로 리다이렉트. **장수 생성 intake 가 503** — 옛 game-api 이미지 + 새 스키마(44 vs 41) 조합. 현재 코드로 백엔드 이미지 재빌드 중, 작전실 캡처는 Phase 1 게이트로 이월 |
| 12 기록(랭킹) | `12-rankings-desktop.png` | `12-rankings-mobile.png` | 쉘(상태바·부서 나브) 위에 옛 카드 레이아웃 |
| 06 도시 | `06-city-desktop.png` | `06-city-mobile.png` | 장수 없음 → 로딩 상태 |
| 14 회의실 | `14-council-desktop.png` | `14-council-mobile.png` | |
| 장수 일람 | `07-generals-desktop.png` | `07-generals-mobile.png` | 부서 나브 「국가 운영」 활성 표시. 표 초상이 깨진 진짜 원인(교차 비평 #1): 옛 picture 코드가 CDN `icons/` 에 없고 **기본 초상 `icons/default.jpg` 자체가 404** 라 폴백이 죽은 URL 로 갔다. 기본 초상을 앱 자체 출처 `public/portrait-default.svg` 로 바꿔 해소(`DEFAULT_PORTRAIT_PATH`) |

## docs-impact

`docs-impact: CLAUDE.md(UI 정본 절)·docs/design/README.md·docs/design/ui-redesign-2026-09/README.md`. `docs/user/**` 에는 메뉴·하단 탭 서술이 없어 변경 없음(grep 0).

## 남긴 것

- 작전실 실캡처(장수 보유 계정)와 e2e 3건은 백엔드 현재 이미지로 재기동한 뒤 Phase 1 게이트에서 찍는다.
- 로비 서버 목록을 dev 에서 보이게 하려면 `SERVER_REGISTRY_JSON` 항목의 `gameApiUrl` 이 `http://s<id>-game-api:8081` 고정이라 로컬 game-api 로 프록시할 수 없다(레지스트리가 비어 있을 때만 `GAME_API_ORIGIN` 폴백). Phase 2 로비 작업에서 dev 전용 폴백을 검토한다.
- 쉘의 `useShellFrontInfo` 가 front-info 를 페이지당 1회(+턴마다) 더 부른다(GameChrome 의 useFrontInfo 와 중복). Phase 0/1 에서 감수하고 Phase 4 웨이브 A 에서 컨텍스트 하나로 합친다(교차 비평 #11).
- 게이트웨이 `Topbar` 는 `usePathname` 대신 페이지 prop(`current`)으로 현재 구역을 받는다 — 기존 7개 테스트가 `next/navigation` 을 `useRouter` 만으로 mock 하기 때문.
