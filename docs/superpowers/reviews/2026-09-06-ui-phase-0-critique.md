# UI 리디자인 Phase 0 교차 비평 (1회차) 과 처리

- 날짜: 2026-09-06 · 대상: `work/opensamguk/ui-redesign-2026-09` (Phase 0 기반 + Phase 1 작전실 초안) vs `origin/main`
- 비평자: 독립 에이전트(읽기 전용, 세 스위트·typecheck 재실행, CDN 실측). 판정: **fix-required** (3건) + should-fix 12건 + nit 7건
- 처리 원칙: fix-required 전부 해소, should-fix 는 아래 표대로(미해소는 사유 명시). 2회차 비평은 PR 시점에 다시 받는다.

## fix-required

| # | 지적 | 처리 |
|---|---|---|
| 1 | 기본 초상 폴백 `icons/default.jpg` 가 CDN 에서 404(실측 HEAD/GET, bare·@main·@master). 새 상태바가 그 깨진 이미지를 항상 보여 준다. 리포트가 원인을 잘못 적음 | **해소.** 기본 초상을 앱 자체 출처 자작 SVG `web/{game,gateway}/public/portrait-default.svg` 로 바꿈(`DEFAULT_PORTRAIT_PATH`, 외부 의존 없음, 항상 200). `onPortraitError` 는 절대 URL 로 비교하고 속성은 상대 경로로 둔다. 두 앱·shared 테스트를 상수 기준으로 갱신. 리포트 문장 정정 |
| 2 | 히어로 초상 `object-fit: cover` — S1·컴포넌트 주석·계획과 모순(잘림 0) | **해소.** `contain` + `object-position: top center`. `tokens.test.ts` 가 CSS 텍스트로 검사 |
| 3 | 계획 체크박스가 없는 테스트·파일(토큰 테스트, srcset/contain 테스트, 5탭/padding 테스트, `fonts.ts`·`Frame.tsx`·`GameShell.tsx`·`GatewayShell*.tsx`)을 완료로 표시 | **해소.** 토큰 테스트·5탭 테스트(`BottomNav.test.tsx`·`dept-menu-config.test.ts`)를 실제로 추가. srcset 은 「하지 않았다 + 사유(2x 자산 없음)」로 정정. 없는 파일 목록은 실제 파일로 고침 |

## should-fix

| # | 지적 | 처리 |
|---|---|---|
| 4 | 부서 나브가 서버 전역 메뉴 대신 픽스처만 읽는다(url/newTab 8잎 상이) | **해소.** `useShellFrontInfo` 가 `api.globalMenu()` 를 1회 받고 `buildDeptGroups(서버 메뉴)` 로 그룹을 만든다. 잎이 없으면 픽스처 폴백. README 문장 정정. 테스트 추가 |
| 5 | 390px 에서 상태바가 잘림(서버명·시계) | **해소.** 서버 블록 ellipsis, 768 미만에서 턴텀·접속자·자원 숨김, 시계 축소 허용. 실캡처는 Phase 1 게이트에서 재확인 |
| 6 | 모바일 「국가」 탭이 게이팅 없이 링크, 「명령」 앵커가 죽은 id | **해소.** 국가 탭은 세력 정보(#11)와 같은 규칙으로 점선 + 사유, 명령 탭은 `#reservedCommandPanel`(명령 목록 패널 id) |
| 7 | `next/font/google` 이 빌드 시 fonts.googleapis.com 이 필요 — CI/도커 egress UNKNOWN | **미해소(기록).** 로컬 `next build` 는 통과. GHCR 빌드는 PR CI 에서 확인하고, 막히면 `next/font/local` 로 vendoring 한다(OFL) |
| 8 | 게이팅을 모르는 로딩/오류 구간에 「장수 직위 이상 필요」 같은 사유를 지어냄 | **해소.** `GatingState`(loading/error/ready): loading 은 중립(비활성 아님·사유 없음), error 는 「서버 정보 없음」. 테스트 추가 |
| 9 | `.os-table td { white-space: nowrap }` 이 13개 표 페이지의 줄바꿈을 막음 | **해소.** th 만 nowrap, td 는 `.os-table--nowrap` 옵트인 |
| 10 | join 페이지가 typed `Button` 을 우회해 사유 없는 비활성 | **해소.** `Button` + `reason` 으로 교체(생성 중·합계 초과) |
| 11 | 쉘의 front-info 중복 요청(페이지당 1회 + 턴마다) | **감수(기록).** Phase 0/1 에서 허용, Phase 4 웨이브 A 에서 컨텍스트 하나로 합친다. 게이트 리포트에 적음 |
| 12 | 접근성: 메뉴 화살표 이동·포커스 이동·Escape, 시트 포커스 트랩/Escape, PillTabs 화살표, Gauge 이름, 세로 모드 aria-expanded | **대부분 해소.** 메뉴: ArrowDown 으로 열고 첫 항목 포커스, Up/Down/Home/End 이동, Escape 로 닫고 버튼 복귀. 시트: 첫 요소 포커스 + Escape 복귀. PillTabs: 화살표/Home/End + roving tabindex. Gauge `aria-label`. 세로 모드 `aria-expanded` 제거. 시트의 완전한 포커스 트랩(Tab 순환)은 미구현 — Phase 6 접근성 스모크에서 다룬다 |

## nit

| # | 지적 | 처리 |
|---|---|---|
| 13 | 정보는 왔는데 서버명이 없을 때 「서버 갱신 중」 | 해소(빈 문자열) |
| 14 | 상단바 라벨이 상수에 없음, board 페이지에 Topbar 미장착 | 라벨을 `LOBBY_LABELS` 로 이동. board 장착은 Phase 4C 에서 |
| 15 | `toHaveTextContent('한')` 부분 일치 | 해소(`/^한$/`) |
| 16 | ReasonTooltip 이 항상 `aria-describedby`, Escape 없음 | 해소(열렸을 때만, Escape 로 닫힘) |
| 17 | shared devDependencies 가 도커 빌드에 설치됨 | 감수(빌드 시간만) |
| 18 | `NAV_ITEMS`·`Sidebar` 죽은 코드 | 해소(삭제) |
| 19 | 리포트에 `docs-impact:` 없음 | 해소 |

## 비평자가 확인한 것(재실행)

shared 23/23 · game 642/642 · gateway 231/231 · typecheck 0 · lint 신규 경고 0 · `verify:topology` 통과 · `check_rtk14_cdn.py` 는 실제 게이트(가짜 base 로 빨개짐) · MapPreview 테스트 정정은 050058c7 이후 stale 이 맞음 · 20버튼·14잎 라벨 verbatim · 시크릿·초상 파일 커밋 없음.

## 비평자의 UNKNOWN(그대로 둔다)

`next build`/도커 빌드 미실행(비평자 범위 밖), CI 폰트 egress, 프로덕션 `NEXT_PUBLIC_IMAGE_CDN` 값, 유저 생성 장수가 RTK14 picture ID 를 받는지, jsDelivr 목록 API 거부로 `icons/` 부재는 404 두 경로로 추정, e2e 3건·장수 보유 계정 작전실 캡처 미실행.
