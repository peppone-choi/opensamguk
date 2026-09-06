# UI 리디자인 시안 소스 (2026-09-06, 야전 사령부 · Concept A)

> 정본은 디자인 캔버스 artifact `35136bc0-55c7-409f-a2e6-4e29f5939d30`(19 아트보드)다. 이 디렉터리는 그 캔버스를 만든 소스의 사본이며, 결정 사항은 `.ai/decisions.md` ADR-LITE-049, 구현 순서는 `docs/superpowers/plans/2026-09-06-ui-redesign-implementation-plan.md`에 있다.

## 구성

| 경로 | 내용 |
|---|---|
| `src/_shared.css` | 팔레트·타이포·컴포넌트 클래스(`.sec-h .chip .btn .kv .stat-row .gauge .feed .slot .tile .pill-tabs .nav-item .frame-bronze`). `web/shared/src/tokens.css`와 프리미티브의 원본 규칙 |
| `src/<Name>.body.html` | 아트보드 본문 19장. 플레이스홀더 `@@ISO(w,h,variant)@@`(아이소 지도), `@@PT(w,h,seed[,#hex])@@`(초상), `@@FLAG(#hex[,size])@@`(깃발) |
| `build.mjs` | `src/*.body.html` → `<Name>.dc.html`(캔버스 아트보드) 조립. 플레이스홀더를 SVG로 치환한다 |
| `canvas.json` | 아트보드 배치·제목 |

`node build.mjs`로 재조립할 수 있다. 저장소 사본에는 초상·로고 이미지가 없다. 캔버스 발행본은 RTK14 파생 초상 3종(original/portrait/icon)과 `logo-wordmark.png`를 썼고, 이 사본의 빌더는 초상을 결정적 실루엣으로 대체한다.

## 아트보드

| 번호 | 파일 | 화면 |
|---|---|---|
| 01 | `Login` | 로그인 (게이트웨이) |
| 02 | `Lobby` | 로비 · 서 버 선 택 |
| 03 | `Main` | 작전실 (게임 메인) — 지도 중앙 · 명령 목록 12순 우측 고정 |
| 04 | `Command` | 명령 예약 모달 |
| 05 | `Map` | 천하 지도 |
| 06 | `City` | 도시 |
| 07 | `General` | 장수 · 휘하 · 부곡 |
| 08 | `Nation` | 국가 운영 · 사령부 |
| 09 | `BattlePlan` | 전투 · WEGO 명령 봉인 |
| 10 | `Replay` | 전투 리플레이 |
| 11 | `Plaza` | 광장 · 경매·베팅·토너먼트 |
| 12 | `Chronicle` | 기록 · 연대기 · 랭킹 |
| 13 | `Community` | 커뮤니티 (서버 밖, 계정 단위) |
| 14 | `Council` | 회의실 · 기밀실 (게임 안, 국가 단위) |
| M1~M3 | `MobileLogin` `MobileLobby` `MobileMain` | 모바일 |
| S1 | `System` | 팔레트 · 타이포 · 명령 상태 4종 · 초상 3종 규칙 · 20기능→부서 메뉴 매핑 · 세 공간의 경계 |
| S2 | `Parity` | 현재 화면 정보 대조표 — 현행 컴포넌트가 그리는 항목 전부의 시안 위치. 라벨은 바꾸지 않는다 |

## 시안이 확정한 규칙 (요약)

- 팔레트: `#0c0f0e` 바탕 · `#1b201d` 패널 · `#d3b064` 청동(활성·CTA) · `#697e58` 이끼(보조·가능) · `#c96b5d` 적갈(위험·전투) · `#7aa7c7` 정보 · `#ffd36d` 포커스 · `#ece6d8` 본문 · `#8a8477` 보조. 국가색은 깃발·초상 링·영토 tint에만.
- 타이포: 제목·연호·인물명은 Noto Serif KR 700/900(16px 이상), 본문은 Pretendard/Noto Sans KR 400/500/700, 수치·시각은 JetBrains Mono tabular.
- 초상 3종: 원본 633×900 히어로(하단 그라데이션 마스크) / 148×210 카드 / 96 아이콘(48·40·32·28·24·20). 각진 정사각, 원형 금지. 국가색 링은 내 장수·군주·현재 문맥 국가에만.
- 비활성은 흐리기 대신 점선 테두리 + 사유 툴팁. 터치 목표 44px. 포커스 링 3px `#ffd36d`.
- 예턴은 한 순에 한 턴. 명령 목록 12슬롯 = 12순.
- 세 게시 공간은 서로 다른 화면: 커뮤니티(서버 밖·계정) / 회의실(국가 소속 전원) / 기밀실(permission ≥ 2 · 열람 기록 · 적갈 프레임).
- 브랜드는 `logo-wordmark.png`만. 텍스트 워드마크 금지. 헤더 28~32px, 로그인 히어로 280~340px.

## 정정

S2 대조표의 「GlobalMenu 8라벨은 API 주도라 소스에 없음(UNKNOWN)」은 틀렸다. 라벨과 URL은 `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GlobalMenuController.kt`에 있다. `web/game/lib/global-menu-fixture.ts`는 라벨은 같지만 url/newTab 이 몇 항목 다르므로 서버 메뉴가 정본이고 픽스처는 폴백이다(부서 나브는 `buildDeptGroups(서버 메뉴)`).
