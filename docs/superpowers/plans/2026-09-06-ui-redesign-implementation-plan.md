# UI 리디자인(야전 사령부 · Concept A) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-09-06 사용자가 확정한 디자인 캔버스(19 아트보드)를 `web/gateway`·`web/game`·`web/shared`에 그대로 구현하고, 시안이 요구하는 소형 백엔드 기능(공지·세력 현황·커뮤니티 확장·회의실 표결/종류·기밀실 열람 기록)을 같이 붙인다. 어드민은 아트보드가 없으므로 같은 디자인 시스템으로 현행 백엔드가 지원하는 기능 전부를 노출한다. 화면이 요구하는 게임 이미지(아이콘·깃발·빈 상태·배경)는 `opensamguk-images`에서 제작해 export로 붙이고, 3D는 마지막에 조건이 되면 한다. 끝나면 게임 서버를 한 번 초기화한다.

**Architecture:** 토큰·프리미티브·초상 3종·쉘을 `@opensamguk/ui`(web/shared)에 먼저 세우고(Tier-0), 화면은 작전실 → 로그인/로비 → 어드민 → 나머지 순으로 페이즈별 PR 하나씩 갈아 끼운다. 라벨·게이팅·API 계약·엔진 판정은 바꾸지 않는다. 새 백엔드는 gateway-api/board-api(계정·커뮤니티·공지)와 game-api 읽기 + 엔진 인테이크(회의실·기밀실 쓰기)로 나뉘며 daemon-write 규칙을 지킨다.

**Tech Stack:** Next.js 15 / React 19 / TypeScript 5.7 / Vitest 3 / Playwright 1.52 / pnpm 워크스페이스, Kotlin + Spring Boot(gateway-api·board-api·game-api·game-engine) / Flyway / PostgreSQL / Redis Streams.

**Spec:**
- 정본 시안: 디자인 캔버스 artifact `35136bc0-55c7-409f-a2e6-4e29f5939d30` (19 아트보드). 소스 사본: `docs/design/ui-redesign-2026-09/` (`src/*.body.html` + `_shared.css` + `build.mjs` + `canvas.json`, 초상·로고 이미지는 저장소에 두지 않는다).
- 확정 결정(2026-09-06): Concept A 팔레트·타이포, 메인=작전실(지도 중앙·예턴 우측 고정), 커뮤니티/회의실/기밀실 분리, 초상 3종 규칙, 예턴 = 한 순 한 턴(12슬롯 = 12순), 현행 라벨 전부 보존(S2 대조표), 텍스트 워드마크 금지, RTK14 초상 제품 사용(ADR-LITE-048).
- 사용자 추가 결정(2026-09-06): 시안의 소형 기능은 백엔드까지 만든다. 이어서 「제외 범위도 동시에 실행」 지시로 WEGO 봉인·리플레이 페이즈 타임라인·작전·휘하 인물·부곡까지 이 계획 안에서 구현한다(Phase 4X). 순서는 기반 → 작전실 → 로그인/로비 → 어드민 → 나머지, 지라 티켓·깃허브 이슈를 같은 PR에서 함께 닫는다, 게임에 필요한 이미지도 제작한다, 3D 제작은 마지막에 가능하면 한다, 완료 후 서버 초기화.

## Global Constraints

- 현행 화면의 라벨은 한 글자도 바꾸지 않는다(「서 버 선 택」 같은 띄어쓰기 라벨 포함). `CONTROL_BUTTONS` 20개의 label/compactLabel/href/bucket과 `GlobalMenuController`의 8항목 name/url은 verbatim으로 재사용한다.
- 게이팅·권한·엔진 판정은 CSS나 렌더러가 새로 정의하지 않는다. 비활성 항목은 숨기지 않고 점선 + 사유 툴팁으로 남긴다(OPENSAM-113 표시 원칙).
- 인테이크 202는 성공이 아니다. 모든 mutation은 `pollCommandResult`로 `RESOLVED`까지 폴링한다.
- 시안에 찍힌 수치(04:12, 6/12, 38명 등)는 전부 예시다. 화면 값은 실데이터에서만 오고, 없는 값은 구획째 렌더하지 않는다. 가짜 수치·빈 껍데기 구획 금지.
- 확장 범위(사용자 지시 2026-09-06 「제외 범위도 동시에 실행」): 09 전투·WEGO 봉인, 10 리플레이 플레이어(페이즈 타임라인·캠페인 정산), 작전(회의실 「작전」 글 + 리플레이 첨부), 휘하 인물·부곡 구획을 Phase 4X 세 트랙으로 구현한다. 각 트랙은 spec → 교차 비평 → 구현 → 게이트 순서를 지키고, 봉인된 계획이 없는 전투는 오늘과 완전히 같은 경로를 타서 frozen baseline·골든을 건드리지 않는다.
- 초상 원본·서빙 파일은 `opensamguk-images`가 정본이다. 앱은 `portraitVariantUrl()` 계약만 쓴다. 저장소에 초상 이미지를 커밋하지 않는다.
- 브랜드는 `logo-wordmark.png`(마스터 `assets/brand/logo-master.png`)만 쓴다. 헤더 28~32px, 로그인 히어로 280~340px.
- `HanMapCanvas`·`isoMap`·`provinceMap`의 좌표·픽셀 값은 게임 정합성이라 건드리지 않는다. 지도는 감싸는 패널만 바꾼다.
- daemon-write 규칙: game-engine은 `ChangeRecorder` → `JdbcFlushExecutor`로만 쓴다. 회의실·기밀실의 새 쓰기(글 종류, 표결 연결, 열람 기록)는 기존 `boardArticle`/`boardComment`처럼 인테이크 명령으로 들어가 엔진 핸들러가 기록한다. 커뮤니티·공지·신고·대표 장수는 gateway 스택(board-api/gateway-api, JPA)이 소유한다.
- 페이즈 = PR 하나(Phase 4는 웨이브별 PR). 각 PR 게이트: `corepack pnpm -r typecheck && corepack pnpm -r lint && corepack pnpm -r test` + `pnpm build`(gateway·game) + 기존 e2e 3건 + 손댄 백엔드 모듈 `:app:*:test`/`:infra:test`(XML로 판정) + 1440×1000·390×844 스크린샷 리포트(`docs/superpowers/reviews/2026-09-*-ui-phase-N-screens.md`) + 교차 비평(fix-required 0).
- 문서는 같은 PR에서 갱신한다: `docs/user/**`(화면 안내), `docs/admin/**`(관리 화면 경로), README 스크린샷, `CLAUDE.md` 프론트 절 한 줄. 영향 없으면 `docs-impact: none`을 PR 본문에 적는다.
- 브랜치 `work/opensamguk/ui-redesign-2026-09`에서 페이즈별로 `work/opensamguk/ui-p<N>-<slug>`를 파고 base=main으로 PR한다(squash 머지 뒤 같은 브랜치에 더 푸시하지 않는다). 커밋 트레일러 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

## 티켓·이슈 매핑 (같은 PR에서 함께 닫는다)

미러 규약(2026-08-20, #482): GH 제목 `[OPENSAM-###] <요약>`, 라벨 `jira-mirror`, 본문 끝에 Jira URL. 진실은 코드 > PR > GitHub > Jira. 닫을 때는 GH close + Jira 전환 + 같은 코멘트(PR 링크·증거 경로)를 둘 다 남긴다.

| 시점 | Jira | GitHub | 처리 |
|---|---|---|---|
| Phase 0 시작 (완료 2026-09-06) | OPENSAM-113 | #256 (closed 08-18) | Jira 완료 전환. 코멘트: 택일 완료 2026-09-06 Concept A, 캔버스 ID, ADR-LITE-049 |
| Phase 0 시작 (완료 2026-09-06) | OPENSAM-210 | #470 (closed 08-21) | Jira 완료 전환(`web/shared` 워크스페이스 패키지 존재). 드리프트 정정 |
| Phase 0 시작 (완료 2026-09-06) | OPENSAM-211 | #471 (closed) | Jira 완료 전환(`Brand` 공용 컴포넌트, 워드마크 규칙). 드리프트 정정 |
| Phase 0 시작 (완료 2026-09-06) | OPENSAM-212 | #472 (closed) | Jira 완료 전환(감사 문서 `2026-08-20-design-quality-audit.md` 존재). 남은 P0/P1 항목은 Phase 1·2 작업에 흡수(아래) |
| Phase 0 시작 (본문 갱신 완료 2026-09-06) | OPENSAM-115 (할 일) | #258 (open) | 본문 갱신: 「픽셀 UI」 전제를 Concept A(ADR-LITE-049)로 교체, 범위를 Phase 0+1로 고정. Phase 1 머지 시 둘 다 닫는다 |
| Phase 0 시작 (갱신 완료 2026-09-06) | OPENSAM-112 (진행 중) | #255 (open) | 에픽 본문 갱신(픽셀 UI → Concept A). 닫지 않는다(AI 에셋 파이프라인 잔여) |
| Phase 0 시작 (발행 완료 2026-09-06) | OPENSAM-243 (P1) · 239 (P2) · 240 (P3) · 241 (P4) · 242 (P4C) · 244 (P7) — OPENSAM-112 하위 | #645 · #646 · #647 · #648 · #649 · #650 | ① 작전실 ② 로그인·로비·공지·세력현황 ③ 어드민 콘솔·게임 관리 허브 ④ 나머지 화면(웨이브 A·B) ⑤ 커뮤니티·회의실·기밀실 확장(백엔드 포함) ⑥ 3D(가능하면). 각 페이즈 PR이 닫는다 |
| Phase 0 머지 | OPENSAM-100 (할 일) | #243 (open) | 초상 3종 viewport 소비 + 1,000 ID × 3 variant CDN 검증 스크립트로 닫는다. 코멘트에 전제 변경(자체/AI 초상 → RTK14 소유자 인수, ADR-LITE-048) 명시. OPENSAM-96/#239 에픽에는 재범위 코멘트만 |
| Phase 1 머지 | OPENSAM-46 (할 일) | #188 (open) | 체크리스트 1-h, D3-11·12·13·16·17 체크. D3-14(모달 내부 대상 모델)·D3-15(서버 후보 API)는 남긴다. 닫지 않는다 |
| Phase 2 머지 | OPENSAM-212 잔여 | — | 감사 P1 「계정 폼 모바일 충돌」, P1 「하단 나브가 콘텐츠 가림」 해소를 코멘트로 기록 |
| Phase 4 웨이브 C | OPENSAM-228 (할 일) | #494 (open) | 회의실 thread·표결·결정 기록 부분 기여 코멘트. 닫지 않는다(귀환 인과 요약·작전 회의는 작전 백엔드 필요) |
| Phase 4 웨이브 B | OPENSAM-71 (할 일) | #213 (open) | 8-h~j 부대 편성 UI 리스타일 부분 기여 코멘트. 닫지 않는다 |
| Phase 5 이미지 | OPENSAM-114 (할 일) | #257 (open) | 스타일 가이드·생성→후처리→export 파이프라인·CDN 스모크로 닫는다(`opensamguk-images` PR + 본 저장소 export) |
| Phase 5 이미지 | OPENSAM-203 (할 일) | #463 (open) | 깃발 sprite(문양·테두리·축·바람)·도시/지형 아이콘 부분 기여 코멘트. 지형·경계 타일 세트는 닫지 않는다 |
| Phase 5 이미지 | OPENSAM-238 (할 일) | #597 (open) | 도시 등급 실루엣·상태 배지 layer 분리 부분 기여 코멘트. `imperial-residence` 런타임은 범위 밖 |
| Phase 4X-A | OPENSAM-20 (epic)·48·61 | #162·#190·#203 | 휘하 인물·부곡 수직 절편(계약·materialize·read model·UI). 48·61 닫기, 20 에픽은 잔여(제안 흐름 LLM 0) 코멘트 |
| Phase 4X-B | OPENSAM-23 (epic)·56 | #165·#198 | 작전 계약·결정 규칙·adapter·검증 → 56 닫기, 23 에픽 코멘트. OPENSAM-228/#494 회의 thread·결정 기록도 여기서 닫는다 |
| Phase 4X-C | OPENSAM-24·25 (epic)·57·58·59·173·170 | #166·#167·#199·#200·#201·#350·#347 | 야전 WEGO 봉인·결정론 해결·replay spine·리플레이 렌더러. 57·59·173·170 닫기, 58은 5종 명령 중 구현분 체크, 24·25 에픽 코멘트(공성·해전 잔여) |
| Phase 7 3D | OPENSAM-46 (할 일) | #188 (open) | D3-13의 「3D picking 연결」을 3D 페이즈에서 수행하면 체크. 못 하면 사유를 남긴다 |

## 어드민을 만드는 방식 (아트보드 없음)

아트보드가 없으므로 S1 디자인 시스템(팔레트·타이포·명령 상태 4종·버튼·초상 아이콘 96·점선 비활성 + 사유)을 그대로 적용하고, 화면 구조는 현행 백엔드 엔드포인트를 기준으로 짠다. 새 기능을 지어내지 않고 **백엔드가 이미 할 수 있는 것을 전부 UI로 노출**한다.

현행 백엔드 어드민 기능(실측, 2026-09-06 main):
- gateway-api `AdminController` (`/admin/**`, ROLE_ADMIN): version · deploy/status · deploy · turn-daemon/status·pause·resume · servers 생성·삭제·리셋 + operations/{id} 폴링 · scenarios · env/shared·env/servers/{id} 조회·패치 · users 목록 · users/{id}/{action} · users/scrub/{deleted|old} · ban-email · system/{allow_login|allow_join}.
- board-api `GatewayBoardController`: posts pin(PATCH)·soft-delete(글·댓글)·수정.
- game-api `AdminReadController`(`/api/admin`): game-settings · general-moderation · nation-stats · general-log · diplomacy-all. `AdminWriteController`: server-status(POST, **FE 미배선**) · general-moderation(POST) · game-settings(PATCH). `TournamentController` 관리 경로(`/game/tournament-admin`).
- 현행 FE: gateway `/admin` 4탭(회원 관리·게시판 관리·서버 제어·게임 환경, 1,756줄 단일 파일) + game `admin1/2/5/7/8`·`tournament-admin` 산재.

계획: gateway `/admin`은 **운영 콘솔**(좌측 레일 7섹션: 개요 / 회원 / 게시판·신고 / 서버 / 배포 / 환경 / 공지)로, game `admin*`은 **`/game/admin` 허브**(탭 7개: 게임 설정 / 장수 조치 / 일제정보 / 로그정보 / 외교정보 / 토너먼트 관리 / 서버 상태)로 재편한다. 옛 라우트는 리다이렉트로 남긴다. 파괴적 작업(리셋·삭제·scrub·차단)은 `ConfirmDialog` + 대상 ID 재입력 + `docs/admin/README.md`의 operation 상태 5종 규칙을 그대로 화면에 쓴다. 위험 등급 4단(조회/가역/배포/파괴)을 chip으로 표시한다.

---

## Phase 0 — 기반 (PR `ui-p0-foundation`)

### Task 0.0: 티켓 정리와 신규 티켓 발행

**Files:** 없음(Jira·GitHub)

- [x] **Step 1:** 위 매핑표의 「Phase 0 시작」 행을 실행한다: OPENSAM-113·210·211·212 완료 전환 + 코멘트, OPENSAM-115/#258·OPENSAM-112/#255 본문 갱신(Concept A, ADR-LITE-049 링크).
- [x] **Step 2:** 신규 Jira 작업 6건(OPENSAM-239~244)과 GitHub 미러(#645~#650)를 발행했다. 원문: 신규 Jira 작업 5건을 OPENSAM-112 하위로 만들고 GitHub 미러 5건을 규약대로 만든다. 각 본문에 이 계획 문서 경로와 담당 페이즈, 완료 조건(게이트)을 적는다.
- [x] **Step 3:** 이 계획 문서의 매핑표에 발행된 키/번호를 채워 커밋한다.

### Task 0.1: ADR-LITE-049 + 시안 소스 편입

**Files:**
- Modify: `.ai/decisions.md` (ADR-LITE-049)
- Create: `docs/design/ui-redesign-2026-09/README.md`
- Already copied: `docs/design/ui-redesign-2026-09/{build.mjs,canvas.json,src/*}`
- Modify: `CLAUDE.md` 「프론트엔드/배포」 절(디자인 정본 한 줄), `docs/design/README.md`

- [x] **Step 1:** ADR-LITE-049 「UI 리디자인 정본은 야전 사령부(Concept A) 캔버스다」를 쓴다. 결정 항목: 팔레트·타이포, 메인=작전실(지도·예턴 고정), 세 게시 공간 분리, 초상 3종 규칙, 예턴=한 순 한 턴, 라벨 보존, 워드마크 규칙, 제외 범위(WEGO·리플레이), OPENSAM-112/115의 「픽셀 UI」 전제 폐기. 뒤집기 경로: 캔버스 재발행 + ADR 개정.
- [x] **Step 2:** README에 소스 사용법(`node build.mjs`가 `@@PT/@@FLAG/@@ISO` 플레이스홀더를 치환한다는 것, 이미지 자산은 저장소 밖), 아트보드 19장 목록, S1/S2의 역할을 적는다. `build.mjs`의 초상 파일 참조는 플레이스홀더 규칙만 남기고 실제 파일명은 지운다.
- [x] **Step 3:** `CLAUDE.md`·`docs/design/README.md`에 한 줄씩 링크한다.

### Task 0.2: 디자인 토큰 교체

**Files:**
- Modify: `web/shared/src/tokens.css`
- Modify: `web/game/app/globals.css`, `web/gateway/app/globals.css` (`:root` 오버라이드 제거만)
- Test: `web/game/__tests__/shared-ui-foundation.test.tsx`

- [ ] **Step 1:** 실패 테스트: 토큰 파일에 `--bronze --bronze-dim --bronze-glow --moss --moss-2 --rust --info --focus --panel --inset --raised --line --line-2 --text --text-2 --muted --font-serif`가 있고, `--radius-*`가 0이며, 기존 별칭(`--gold --crimson --jade --bg-base --bg-card --border-subtle` 등)이 새 값으로 매핑돼 있다.
- [ ] **Step 2:** `tokens.css`를 S1 팔레트로 바꾼다: `--bg-base:#0c0f0e --bg-elevated:#141816 --bg-card:#1b201d --bg-hover:#232a26 --gold:#d3b064 --gold-dim:#9c7f3f --crimson:#c96b5d --jade:#697e58 --text-primary:#ece6d8 --text-secondary:#b9b2a3 --text-muted:#8a8477 --border-subtle:#2c342f --border-medium:#3d4740`, 신규 변수 추가, `--radius-sm/md/lg: 0`, 포커스 링 `3px #ffd36d`, `--motion-turn: 300ms`. 기존 변수명은 전부 유지한다(두 앱 3,500줄 CSS가 즉시 새 팔레트로 넘어간다).
- [ ] **Step 3:** 두 앱 `globals.css`에서 `:root` 재정의·중복 토큰을 지우고 `@import '@opensamguk/ui/tokens.css'` 하나만 남긴다. 테스트 GREEN.

### Task 0.3: 폰트

**Files:**
- Modify: `web/gateway/package.json`, `web/game/package.json` (`pretendard` 의존성)
- Modify: `web/gateway/app/layout.tsx`, `web/game/app/layout.tsx`
- Create: `web/shared/src/fonts.ts` (next/font 선언은 앱 레이아웃에서만 가능하므로 공용 CSS 변수명·폴백 스택만)

- [ ] **Step 1:** Pretendard는 npm `pretendard`의 dynamic-subset CSS를 import한다(런타임 외부 요청 없음). Noto Serif KR(700·900)과 JetBrains Mono(500·700)는 `next/font/google`로 빌드 시 self-host한다(unicode-range 슬라이스 자동). CSS 변수 `--font-sans/--font-serif/--font-mono`로 노출하고 폴백 스택은 `_shared.css`와 같게 둔다.
- [ ] **Step 2:** 빌드 크기 실측(`.next/static/media`)을 스크린샷 리포트에 적는다. Noto Serif KR 900이 과하면 700만 남긴다(수치를 미리 정하지 않는다).

### Task 0.4: 공용 프리미티브

**Files:**
- Create: `web/shared/src/{SectionHeader,Panel,Chip,KV,StatRow,Gauge,Feed,Slot,Tile,PillTabs,NavItem,Frame,ReasonTooltip}.tsx`
- Modify: `web/shared/src/Button.tsx`(variant `primary|ghost|danger|disabled` + `reason`), `Card.tsx`, `Table.tsx`, `Modal.tsx`, `ConfirmDialog.tsx`, `index.ts`
- Modify: `web/shared/src/tokens.css` (컴포넌트 클래스는 `_shared.css` 규칙을 `.os-*` 접두로 이식)
- Test: `web/shared`에 vitest 설정 추가(`web/shared/vitest.config.ts`) + `web/shared/src/__tests__/*.test.tsx`

- [ ] **Step 1:** 실패 테스트: 각 컴포넌트가 role/aria를 갖고 렌더되며, `Button disabled`는 `aria-disabled` + `title`(사유)를 반드시 가진다(사유 없는 비활성 버튼은 타입 오류).
- [ ] **Step 2:** `_shared.css`의 `.sec-h .chip .btn .kv .stat-row .gauge .feed .slot .tile .pill-tabs .nav-item .frame-bronze`를 `.os-*`로 이식해 구현한다. 터치 목표 44px, `prefers-reduced-motion` 존중.
- [ ] **Step 3:** `docker/web-*.Dockerfile`이 `web/shared`를 COPY하는지 확인한다(OPENSAM-210 제약). GREEN.

### Task 0.5: 초상 3종 + 깃발

**Files:**
- Create: `web/shared/src/Portrait.tsx` (`PortraitHero`(원본, 그라데이션 마스크, object-position top) / `PortraitCard`(148×210, 프리셋 126×178·56×80·48×68·44×62·36×50) / `PortraitIcon`(96, 프리셋 48·40·32·28·24·20)), `web/shared/src/Flag.tsx`(깃발 mask + 국가색)
- Modify: `web/game/lib/portrait.ts`, `web/gateway/lib/portrait.ts` (variant 선택 헬퍼 공유)
- Create: `tools/assets/check_rtk14_cdn.py` (1,000 ID × original/portrait/icon HEAD 200 검증, 결과 리포트)
- Test: `web/shared/src/__tests__/Portrait.test.tsx`, 기존 `portrait.test.tsx` 확장

- [ ] **Step 1:** 실패 테스트: 크기별로 올바른 variant URL을 고른다(≥148 → portrait, ≤96 → icon, hero → original), `nationRing`은 `self|ruler|context` 중 하나일 때만 outline, `inactive`는 grayscale, Retina `srcset`, 정사각 각진 모서리(원형 금지), `object-fit: contain`(잘림 0, OPENSAM-100 조건).
- [ ] **Step 2:** 구현. 기본 이미지 실패 시 `onPortraitError` 폴백 유지.
- [ ] **Step 3:** `check_rtk14_cdn.py`를 실제 CDN에 돌려 결과를 리포트에 첨부한다(실패 ID는 숨기지 않는다).

### Task 0.6: 부서 메뉴 모델

**Files:**
- Create: `web/game/lib/dept-menu-config.ts`
- Modify: `web/game/lib/control-bar-config.ts`(변경 없음, import만), `web/game/lib/menu-filter.ts`
- Test: `web/game/__tests__/dept-menu-config.test.ts`

- [ ] **Step 1:** 실패 테스트: (a) `CONTROL_BUTTONS` 20개 id가 부서 그룹에 정확히 한 번씩 들어간다 (b) `GlobalMenuController`와 동일한 8항목(`global-menu-fixture.ts`)이 한 번씩 흡수된다 (c) 그룹별 게이팅은 `MainControlBar`가 계산하는 것과 같은 결과다 (d) 비활성 사유 문자열이 bucket별로 존재한다(`myLevel`→「장수 직위 이상 필요」, `permission2`→「수뇌부 권한 필요」, `showSecret`→「기밀 열람 권한 필요」, `myLevelAndNation`→「국가 소속 + 직위 필요」).
- [ ] **Step 2:** S1 매핑표대로 구현: 작전실 / 국가 운영(세력 정보·인사부·세력 장수·회의실·외교부·내무부·사령부·NPC 정책·암행부·감찰부·기밀실) / 군사(부대 편성·세력 도시) / 정보(중원 정보·현재 도시·천하 지도·전투 시뮬레이터) / 광장(토너먼트·경매장 2·베팅장·천통국 베팅·유산 관리·내 정보&설정·오픈 톡 2) / 기록(연감·세력일람·장수일람·명장일람·명예의전당·왕조일람·접속량정보·빙의일람). 하이라이트(`isTournamentApplicationOpen`·`isBettingActive`·`nationBetting`)와 `condShowVar`(npcMode) 유지.

### Task 0.7: 쉘

**Files:**
- Create: `web/game/components/GameShell.tsx`(상태바 56 + 부서 나브 44 + 콘텐츠 + 모바일 5탭 작전실·지도·명령·국가·더보기), `web/game/components/DeptNav.tsx`
- Modify: `web/game/components/Shell.tsx`(GameShell 위임), `Header.tsx`, `BottomNav.tsx`, `BackBar.tsx`
- Create: `web/gateway/components/GatewayShell.tsx`(상단바: 워드마크 28~32 · 로비 · 게시판 · 계정 · 로그아웃 · 관리(ADMIN))
- Modify: `web/gateway/components/Topbar.tsx`
- Test: `Shell.main-route.test.tsx` 확장, 신규 `GatewayShell.test.tsx`

- [ ] **Step 1:** 실패 테스트: 메인은 BackBar 없음, 서브는 있음(현행 유지), 모바일 5탭 href가 부서 그룹과 일치, 하단 나브가 콘텐츠를 가리지 않는다(`padding-bottom: var(--bottom-nav-inset)` 적용, 감사 P1).
- [ ] **Step 2:** 구현. 1232px 미만은 부서 나브를 가로 스크롤, 768px 미만은 5탭.

### Phase 0 게이트

- [ ] `corepack pnpm -r typecheck lint test`, `pnpm build` 두 앱, e2e 3건, 스크린샷 리포트(로그인·로비·작전실·랭킹 4장 × 2 뷰포트, 토큰만 바뀐 상태), 교차 비평 cleared, OPENSAM-100/#243 닫기.

---

## Phase 1 — 작전실 (PR `ui-p1-war-room`) · 아트보드 03·04·M3

### Task 1.1: 상태바 + 서버 정보 스트립

**Files:** Modify `web/game/components/game/GameInfo.tsx`, `GameChrome.tsx`; Test `GameInfo.test.tsx`

- [ ] 13셀 라벨 verbatim 유지(서버명·기수·시나리오·NPC 요약·NPC선택·토너먼트·기타 설정·현재 年月순·전체 접속자 수·턴당 갱신횟수·등록 장수·토너먼트 상태·동작 시각·거래 진행 건수·설문 상태). 연호는 serif 16px+, 「다음 순」 카운트다운은 `turnTime`+`turnTerm`으로 계산하고 값이 없으면 셀을 뺀다. 「갱신」·「로비로」는 나브 우측.

### Task 1.2: 3열 그리드 — 지난 순 · 지도 · 명령 목록

**Files:** Modify `GameChrome.tsx`, `MainRecordZone.tsx`(3탭 장수 동향·개인 기록·중원 정세 ≤15건), `PartialReservedCommand.tsx`; Test `PartialReservedCommand.test.tsx`, `GameChrome.main-map.test.tsx`

- [ ] 실패 테스트: 12슬롯 각각 `年月순`·`HH:MM`·명령·상태(현재 순 `now`, 휴식 `rest`), 편집·당기기/미루기(수량+적용)·조작 대상(본인/휘하) 컨트롤 존재, 한 순 아래 여러 슬롯을 묶는 표현이 없다.
- [ ] 그리드: ≥1232px 좌 300 / 중앙 1fr / 우 360, 1000~1231px 2열(지도+명령) 아래 스택, <768 M3 탭. `MapViewer`는 props·픽셀 불변.

### Task 1.3: 하단 — 조작 대상 · 장수 · 국가 · 도시 · 서신

**Files:** Modify `GeneralBasicCard.tsx`(20항목) `NationBasicCard.tsx`(16항목) `CityBasicCard.tsx` `MainStatusPanel.tsx`(접속중인 국가·【 접속자 】·【 국가방침 】) `MessagePanel.tsx`(3탭 + 「서신을 입력하세요」); Test 기존 4개 확장

- [ ] 조작 대상 패널(OPENSAM-46 D3-11·12·13·16·17): 기본=본인, 도시/국가/장수 단일 상태 패널, 미지원 대상 비활성+사유, 가신 슬롯 placeholder(라벨 「휘하」, 데이터 없으면 「아직 없음」 한 줄).
- [ ] 장수 카드 초상 `PortraitCard 126×178` + 국가색 링(self), 모바일은 통무지정매·병사·훈련·사기만.

### Task 1.4: 명령 예약 모달(04)

**Files:** Modify `web/game/components/CommandModal.tsx`; Test `CommandModal.form-spec.test.tsx`, `CommandModal.terminal-result.test.tsx`

- [ ] 헤더 히어로 마스크(`PortraitHero`), 명령 상태 4종(사용 가능·대상 필요·사용 불가+이유·정보 부족)은 `/api/commands/available` 응답에서만 오고, 결과는 `pollCommandResult` 분기(성공 위조 금지). 기존 폼 스펙 테스트 전부 유지.

### Task 1.5: 순 전환 연출

**Files:** Modify `web/game/hooks/useTurnRefresh.ts`, `GameChrome.tsx`; Test `turnRefresh.test.tsx`

- [ ] `turnCompleted` 수신 → 연호 300ms 전환 + 피드 슬라이드. `prefers-reduced-motion`이면 즉시 교체. 리마운트 없음(OPENSAM-196 유지).

### Task 1.6: S2 대조 테스트

**Files:** Create `web/game/__tests__/parity/main-labels.test.tsx`, `web/game/__tests__/fixtures/front-info.full.json`

- [ ] S2 표의 현행 라벨(GameInfo 13 · MainStatusPanel 3 · General 20 · Nation 16 · City 13 · 예턴 컨트롤 · 기록 3탭 · 메시지 3탭 · 부서 메뉴 20+8 · 갱신/로비로)이 전부 한 화면에 렌더된다. 하나라도 빠지면 빨갛다.

### Phase 1 게이트

- [ ] Phase 0 게이트 + 감사 P0(장수 생성 직후 무한 스피너: `useFrontInfo` 타임아웃·재시도 상태) 해소 확인 + OPENSAM-115/#258 닫기 + OPENSAM-46/#188 체크리스트 갱신.

---

## Phase 2 — 로그인 · 로비 (PR `ui-p2-entrance`) · 아트보드 01·02·M1·M2

### Task 2.1: 백엔드 — 세력 현황 · 공지

**Files:**
- game-api: Create `controller/PublicNationSummaryController.kt` (`GET /api/public/nation-summary` → nation id·name·color·cityCount·generalCount·isMine, permitAll, 60s 캐시). 기존 `/api/rankings/kingdoms`가 같은 값을 공개로 주면 재사용하고 새 컨트롤러는 만들지 않는다(구현 전 확인).
- gateway-api: Create `infra/src/main/resources/db/migration/V49__gateway_notice.sql`(id·title·body_html·pinned·published_at·created_by·deleted_at), `controller/NoticeController.kt`(`GET /notices` 공개, `/admin/notices` CRUD·pin), 서비스·리포지토리·IT.
- web/gateway: `app/api/notices/route.ts`, `app/api/game/[...path]` 경유 세력 현황 프록시, `lib/notices.ts`.
- Test: gateway-api IT(공개 읽기 200 · 비ADMIN 쓰기 403), vitest 라우트 테스트.

### Task 2.2: 로그인(01) · M1

**Files:** Modify `web/gateway/app/login/page.tsx`, `components/ServerBoard.tsx`, `ServerLog.tsx`, `MapPreview.tsx`(패널만), `globals.css` auth 절; Test 신규 `login-page.test.tsx`

- [ ] 히어로 워드마크 280~340, 문구 2줄, 로그인 폼(계정명·비밀번호·표시·로그인 유지·「계정이 없으신가요? 회원가입」·오류 문자열 3종 verbatim), ServerBoard(서버 탭·세력/로그·프로빈스/현/군) 유지, 세력 현황(2.1) + 최근 사건(server-log), 개인정보처리방침·이용약관. 「비밀번호를 잊으셨나요?」는 복구 API가 없으므로 넣지 않는다.

### Task 2.3: 로비(02) · M2

**Files:** Modify `web/gateway/app/lobby/page.tsx`, `lib/constants.ts`(라벨 불변), `globals.css` lobby 절; Test `lobby-*.test.tsx`

- [ ] 서버 카드: 서 버(기수 배지) · 정 보(`nCountryLabel` 등 verbatim) · 캐 릭 터(`PortraitCard 148×210` + 링 / - 미 등 록 - / - 장수 등록 마감 -) · 선 택(입장 / 장수생성·장수빙의·장수선택 / - 폐 쇄 중 - / - 준 비 중 -). 필터 전체·참가 중·참가 가능·종료. 계 정 관 리(비밀번호 & 전콘 & 탈퇴 · 커뮤니티 게시판 · 관리(ADMIN) · 로그아웃), 각주 2건. 우측 세력 현황 + 공지. 로딩 「불러오는 중…」, 실패 행은 「- 폐 쇄 중 -」으로 남기고 숨기지 않는다.

### Task 2.4: 회원가입 · 계정

**Files:** Modify `web/gateway/app/join/page.tsx`, `app/account/page.tsx`

- [ ] 같은 프리미티브로 리스타일. 계정 폼은 390px에서 라벨 블록 + 컨트롤 전폭(감사 P1). 대표 장수 변경 UI는 Phase 4 웨이브 C에서 붙인다.

### Phase 2 게이트

- [ ] 공통 게이트 + `lobby-possession` e2e + gateway-api·game-api 테스트 XML + 신규 티켓 ② 닫기.

---

## Phase 3 — 어드민 (PR `ui-p3-admin`)

### Task 3.0: API 커버리지 대조표

**Files:** Create `docs/admin/admin-surface-map.md`

- [ ] gateway-api 22 엔드포인트 · board-api 관리 3 · game-api 8 · 토너먼트 관리 ↔ 화면 섹션/탭 표. 미배선 1건(`POST /api/admin/server-status`)을 표에 명시하고 3.2에서 연결한다.

### Task 3.1: 게이트웨이 운영 콘솔

**Files:**
- Split: `web/gateway/app/admin/page.tsx`(1,756줄) → `components/admin/{AdminConsole,Overview,Members,BoardAndReports,Servers,Deploy,Environment,Notices}.tsx`, 기존 `MemberControl`·`BoardControl*`·`admin-server-lifecycle.ts` 재사용
- Test: `web/gateway/__tests__/admin-*.test.tsx`(섹션별 렌더 + 401/403 graceful + 파괴적 작업 확인 흐름)

- [ ] 개요: version(서비스별 태그) · deploy/status · turn-daemon/status(pause/resume) · 서버 목록.
- [ ] 회원: users 목록(초상 아이콘 40·등급·상태) · users/{id}/{action} 전부 · scrub deleted/old · ban-email · system allow_login/allow_join 토글(현재값 먼저 표시, 변경 전 값 기록).
- [ ] 게시판·신고: pin·soft-delete·(웨이브 C 이후) 신고 처리.
- [ ] 서버: 생성·리셋·삭제 + `operationId` 폴링, 상태 5종(`pending/running/recovery_required/succeeded/failed·cancelled`) 표시 규칙을 `docs/admin/README.md` 그대로. 확인은 대상 서버 ID 재입력.
- [ ] 배포: 태그 선택·재배포(immutable tag 안내). 환경: shared/server env 편집(현행 `EnvConfigEditor`). 공지: 2.1 CRUD.

### Task 3.2: 게임 관리 허브

**Files:**
- Create: `web/game/app/game/admin/page.tsx`(탭), `components/admin/{GameSettings,GeneralModeration,NationStats,GeneralLog,DiplomacyAll,TournamentAdmin,ServerStatus}.tsx`(기존 admin1/2/5/7/8·tournament-admin 본문 이동)
- Modify: `app/game/admin{1,2,5,7,8}/page.tsx`, `tournament-admin/page.tsx` → `redirect('/game/admin?tab=…')`
- Modify: `web/game/lib/api.ts`(`adminServerStatus` POST)
- Test: 기존 `admin1-route`·`admin2-route` 유지 + 신규 `admin-hub-route.test.tsx`

- [ ] 라벨·정렬 옵션은 BE `sortOptions` verbatim. BLOCKED 항목(historyStats·sabotageLog)은 「원천 부재」 안내 그대로. 서버 상태 탭은 `SERVER_STATUSES` 중 선택 + 확인.

### Task 3.3: 진입점 · 문서

- [ ] 로비 「관리」(ADMIN) → 콘솔, 게임 상태바 ADMIN chip → `/game/admin`, 콘솔 서버 행 → 해당 서버 `/game/admin`(serverId 경로). `docs/admin/*.md`의 화면 경로 갱신.

### Phase 3 게이트

- [ ] 공통 게이트 + 실제 ADMIN 계정으로 로컬 도커 스택에서 섹션별 스크린샷 + 신규 티켓 ③ 닫기.

---

## Phase 4 — 나머지 화면 (웨이브별 PR) + 소형 백엔드

### 웨이브 A (PR `ui-p4a-info-records`) · 05·06·12·10(레일만)

- [ ] 05 천하 지도 `/game/map`: 범례·프로빈스 선택 패널·부대 목록(아이콘 28). 캔버스 불변.
- [ ] 06 도시 `/game/city`: 보급 끊김 배지 · 8게이지 · 태수/군사/종사 · 주둔 장수 표(얼 굴 · 이 름 · 통솔 … 명 령 14열 verbatim) · 인접 표.
- [ ] 12 기록: `history`·`world-log`·`rankings/*`·`hall-of-fame`(랭킹 상위 3 `PortraitCard 56×80`, 이하 아이콘 24).
- [ ] 감찰부 `/game/battle-center`: 우측 레일(정렬 4종·대상 장수·전투 기록·전투 결과·장수 열전·개인 기록)만. 리플레이 플레이어 없음.

### 웨이브 B (PR `ui-p4b-nation-plaza`) · 07·08·11 + 아트보드 없는 페이지

- [ ] 08 국가 운영: `my-nation`·`nation-finance`·`diplomacy`·`chief-center`·`npc-control`·`my-boss`·`my-cities`·`my-generals`(15열).
- [ ] 07 장수: `generals`(암행부 포함)·`troop`(부대 편성 — 라벨 유지, OPENSAM-71 8-h~j 부분). 휘하 인물·부곡 구획은 만들지 않는다.
- [ ] 11 광장: `auction`(금/쌀·유니크 verbatim 열)·`betting`·`nation-betting`·`tournament`·`inherit`(포인트 12종·버프 8종)·`my`·`vote`·`select-pool`·`join`·`mailbox`·`simulator`·`v2-lab/*`(최소 리스타일).

### 웨이브 C (PR `ui-p4c-boards`) · 13·14 + 백엔드

**커뮤니티(gateway 스택)**
- [ ] `V50__gateway_board_extend.sql`: category CHECK에 `STRATEGY`·`SERVER`·`CREATIVE` 추가, `view_count`, `gateway_board_report`(post_id/comment_id·reporter_account_id·reason·status·handled_by·created_at), `users.representative_server_id`·`representative_general_id`.
- [ ] board-api: 조회수 증가(상세 GET), 정렬 `latest|popular`(popular = 최근 7일 조회+댓글 가중, 기준을 코드 상수로 문서화)·`mine`, 검색(`q` → title/content ILIKE, 인덱스), 신고 생성·관리자 목록·처리. gateway-api: 대표 장수 설정(`PATCH /account/representative`, 소유 검증은 game-api `server-basic-info/me`로). IT: 권한(신고 처리 비ADMIN 403), 검색 결과 경계.
- [ ] web/gateway `/board`: 카테고리 6 + 카운트, 최신/인기/내 글, 검색, 글쓰기, 목록 행(아이콘 40 + 서버 배지), 상세(아이콘 48, 수정·신고, 댓글 28), 우측 내 계정(대표 장수 변경)·인기 글·「세 공간의 경계」 안내. 계정 페이지에 대표 장수 변경.

**회의실·기밀실(게임 스택, 인테이크 경유)**
- [ ] `V51__board_post_kind.sql`: `board_post.kind`(general|vote|operation|notice) · `board_post.vote_id`(→ vote_poll) · `board_post_read`(post_id·general_id·read_at, UNIQUE).
- [ ] 엔진: `BoardHandler`에 `boardArticle` 인자 `kind`·`voteId` 수용, 신규 명령 `boardRead`(기밀글 열람 기록, 멱등) — `ChangeRecorder` 채널 + `JdbcFlushExecutor` flush step. game-api 읽기 DTO에 `kind`·`vote`(찬/반/미표 집계 + 표결자 아이콘)·`readers`(n/m + 아이콘)·참여 스택(국가 장수 최근 순 활동/침묵) 추가. 가드 재검증(permission ≥ 2).
- [ ] web/game `/game/board`: 헤더(깃발·국가·회의실, 내 직책, 年月순), 탭 전체/표결/작전/공지(작전은 kind=operation 글일 뿐, 첨부 없음), 표결 카드(찬·반 스택 + 마감), 기밀실 적갈 프레임 + 열람 기록 + 「직책이 바뀌면 즉시 접근이 끊기고…」 안내, 「커뮤니티는 서버 밖 ↗」 링크. 라벨 회의실/기밀실 그대로.
- [ ] Test: `logic`/`engine` 핸들러 단위(kind·voteId·boardRead 가드), game-api 읽기 DTO, vitest 화면.

### Phase 4 게이트

- [ ] 웨이브별 공통 게이트 + 백엔드 XML + 신규 티켓 ④⑤ 닫기, OPENSAM-71/#213 코멘트.

---

## Phase 4X — 확장 범위: 휘하·부곡 / 작전 / WEGO 봉인·리플레이 (트랙별 PR)

사용자 지시(2026-09-06)로 제외 범위를 같은 계획에서 실행한다. 세 트랙은 서로 의존한다(작전이 부곡을 부대로 쓰고, WEGO가 작전 안에서 벌어지며, 회의실 작전 글이 리플레이를 첨부한다). 트랙마다 **spec 먼저**(`docs/superpowers/specs/2026-09-*-*.md`), 교차 비평 cleared 뒤 구현한다. 모든 엔진 쓰기는 `ChangeRecorder` 경유, 새 경로는 봉인·작전·휘하가 존재할 때만 타고, 없으면 현행과 바이트 동일(골든 불변 증거를 게이트에 포함).

### 4X-A 휘하 인물 · 부곡 (PR `ui-p4xa-retinue`) · 아트보드 07

**Spec:** 로드맵 「휘하 인물과 부곡」 + `docs/superpowers/plans/2026-07-17-v2-ticket-backlog/`의 OPENSAM-48·61 항목.

- [ ] 도메인: `retinue_person`(general_id·name·role 막료/부장/문객·loyalty·task·assigned_buqu_id), `buqu`(general_id·troops·formation·training·morale·fatigue·provisions·commander_retinue_id). Flyway `V52__retinue_and_buqu.sql`. 국가군과 분리(로드맵).
- [ ] 명령(인테이크 → 엔진 핸들러 → flush): 휘하 등용/해임, 임무 부여(내정 보좌·정찰·훈련), 부곡 편성/해산(장수 병력 일부를 부곡으로, 총 병력 불변), 부장 배정. 월간 틱: 충성·피로·군량 정산(수치 규칙은 spec에 명시, 기존 골든 경로 비접촉).
- [ ] read API `/api/my-retinue`, `/api/general/{id}/retinue`(권한 내). AI: NPC는 기본 정책(등용 없음)으로 결정성 유지 — AI 확장은 spec에서 UNKNOWN으로 남긴다.
- [ ] UI: 07 아트보드 휘하 목록(카드 44×62)·부곡 표, 조작 대상 「휘하」 슬롯(D3-17) 실제 연결, 명령 모달 대상=휘하.
- [ ] 게이트: logic/engine/infra 테스트 + 골든 불변 + game vitest.

### 4X-B 작전 (PR `ui-p4xb-operation`) · 아트보드 08·14

**Spec:** 로드맵 「작전 목표와 교전」, `docs/superpowers/plans/2026-08-22-beyond-che-world-map-and-game-loop-plan.md`, OPENSAM-56.

- [ ] 도메인: `operation`(nation_id·kind 도시 점령/도로 확보/보급 차단/통과/봉쇄/구원·target(city/province/route)·deadline(年月순)·status 선언/진행/달성/실패/종료·progress·created_by), `operation_unit`(operation_id·general_id·buqu_id?·role). `V53__operation.sql`.
- [ ] 명령: 작전 선언(수뇌부 permission ≥ 2), 참여/이탈, 종료. 월간 틱: 진척 = 실제 점유·통제·보급 연결(기존 spatial 보급·도달성 계약 재사용)에서 계산. 기한 초과 → 실패 정산(군량·사기 비용은 spec).
- [ ] read API `/api/operations`, `/api/operations/{id}`(진척·참여 부대·연결 전투·회의 thread).
- [ ] 회의실: `board_post.kind=operation` + `operation_id`, 결정 기록(표결 결과 → operation 메모). 리플레이 첨부는 4X-C의 `battle_replay_id`.
- [ ] UI: 08 국가 운영 「작전」 패널, 14 회의실 작전 탭, 작전실 우측 상단 진행 중 작전 배지.
- [ ] 게이트: 작전 시작→진척→종료까지 관리자 개입 0 시뮬레이션 테스트 1건(로드맵 4단계 관문의 축소판) + 골든 불변.

### 4X-C WEGO 야전 봉인 · 결정론 해결 · 리플레이 (PR `ui-p4xc-wego-field`) · 아트보드 09·10

**Spec:** `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md` + BATTLE-F 티켓(OPENSAM-156~174) 중 야전 절편. 공성·해전은 잔여로 남긴다.

- [ ] 계약: `battle_plan`(battle_id·general_id·stance 돌격/전진/방어/우회·target·conditions[사기 임계→방어 / 합류 전 추격 금지 / 손실 임계→퇴각선]·sealed_at·version), `battle_replay`(battle_id·seed·input_hash·phases[]·settlement·created_at). 봉인 마감 = 해결 직전 순. `V54__battle_plan_replay.sql`.
- [ ] 해결: 기존 `war/*` 엔진에 「봉인된 계획이 있으면」 훅을 단다 — 태세·조건이 기존 파라미터(공격 개시·퇴각 임계·추격 여부·지형 보정)에 매핑되는 규칙을 spec에 표로 고정. 계획이 없으면 훅은 no-op(골든 바이트 동일). 결과는 페이즈별 상태(병력·사기·보정·발동 조건)로 기록하고 같은 seed·입력이면 같은 리플레이(해시 게이트).
- [ ] 정산: 캠페인 정산(사상·군량·작전 진척·경험)을 replay.settlement에 기록, 4X-B 작전 진척에 반영.
- [ ] read API `/api/battles/{id}/plan`(아군 것만), `/api/battles/{id}/replay`, 감찰부 목록 연결, 공유 링크(권한 내). 명령: `sealBattlePlan`(수정은 봉인 전까지, 봉인 뒤 409).
- [ ] UI: 09 명령 봉인 화면(아군 부대 카드 148×210·적 정찰 정보·페이즈 6·태세/목표/조건·예상 범위는 결정론 시뮬 `simulate-battle` 재사용), 10 리플레이 플레이어(對 화면·페이즈 스크럽·0.5/1/2×·로그·정산), 회의실·커뮤니티 리플레이 첨부 카드.
- [ ] 게이트: 결정성(같은 seed 두 번 = 같은 해시), 봉인 후 수정 409, 계획 없는 전투 골든 불변, 리플레이 렌더 vitest, e2e 1건(봉인→해결→리플레이 열기). OPENSAM-57·59·173·170 닫기.



## Phase 5 — 게임 이미지 제작 (PR `ui-p5-images` + `opensamguk-images` PR)

원칙: 원본·생성 요청·빌더·큐레이션 미리보기의 정본은 `opensamguk-images`(`originals/`·`tools/`·`exports/`·`previews/`)에 두고, 이 저장소에는 `web/*/public/**` export만 둔다(#463 제작 경계). 제3자 게임 에셋을 파생하지 않는다. 국가색은 깃발 mask·영토 tint에만 쓰고 아이콘 본체는 재염색하지 않는다. 이미 정본화된 자산(중앙평원 1~11등급 도시 아이콘, 황제 배지, 한 지도 마커 3종, 계절 타일, 상태 아이콘 16종)은 다시 만들지 않고 리스타일 화면이 그대로 소비한다.

### Task 5.1: 필요 이미지 목록과 스타일 가이드

**Files:** Create `opensamguk-images/originals/ui-2026-09/STYLE.md`, `docs/design/ui-redesign-2026-09/image-manifest.md`

- [ ] 아트보드 19장을 훑어 실제로 픽셀이 필요한 항목만 적는다(현재 파악: ① 부서·명령·자원 UI 아이콘 SVG 세트 16/20px 약 40종 — 시안은 인라인 SVG ② 세력 깃발 sprite(cloth·pole 4종 → 문양·테두리·축·바람 방향, mask 기반, 24/32/48) ③ 빈 상태·오류·로딩 일러스트 5종(작전실·회의실·커뮤니티·기록·경매) ④ 로그인 히어로 배경(시안은 라이브 `MapPreview`라 이미지 불필요 — 지도 미로딩 폴백 1장만) ⑤ 광장 배지(경매·베팅·토너먼트 하이라이트) ⑥ 국가 성향·병종·특기 아이콘은 현행 텍스트 라벨 유지, 이미지 없음).
- [ ] 스타일 가이드: Concept A 팔레트 10색 제한, 각진 모서리, 1px 청동 테두리, nearest-neighbor 축소 가독성(16px에서 판별), 색각 비의존(형태 차이 필수).

### Task 5.2: 생성·후처리·export 파이프라인 (OPENSAM-114)

**Files:** Create `opensamguk-images/tools/ui-icons/{build.py,manifest.json}`, `opensamguk-images/tools/flags/build_flag_sprites.py`; Modify `opensamguk-images/ASSET-EXPORTS.md`

- [ ] SVG 아이콘은 손으로 그린 원본 SVG(정본) → `svgo` 최적화 → sprite sheet + 개별 export. 래스터(깃발·일러스트)는 `sprite-gen` 파이프라인(생성 요청 SSoT → 행 스트립 → 크로마키 → 컴포넌트 추출 → atlas·manifest)으로 만들고 큐레이션 웹뷰로 후보를 고른다. 생성 도구가 세션에서 못 쓰이면 손 제작 SVG/픽셀로 대체하고 그 사실을 기록한다(UNKNOWN 은폐 금지).
- [ ] 같은 입력에서 같은 atlas·manifest가 재생성되는지 `--check`로 검증. 시드·프롬프트·후처리 파라미터를 manifest에 남긴다.
- [ ] export를 `web/{game,gateway}/public/{icons,flags,illustrations}/`로 복사하는 스크립트 + jsDelivr 태그 + CDN 200 스모크.

### Task 5.3: 화면 연결

**Files:** Modify `web/shared/src/{Icon,Flag}.tsx`, 소비 화면

- [ ] `Icon` 컴포넌트가 sprite 를 참조(name → symbol). `Flag`는 새 sprite + 국가색 mask. 빈 상태 일러스트는 `EmptyState` 프리미티브로. DPR 1/2 스냅샷 테스트.

### Phase 5 게이트

- [ ] `opensamguk-images` PR(빌더·원본·export·프리뷰 시트) 머지 → 태그 → 본 저장소 export PR. 16px 판별 검사 시트를 리포트에 첨부. OPENSAM-114/#257 닫기, OPENSAM-203/#463·OPENSAM-238/#597 코멘트.

---

## Phase 6 — 마감 (PR `ui-p6-closeout`)

- [ ] 두 앱 `globals.css`에서 이관 완료된 죽은 클래스 제거(사용처 grep 0건만).
- [ ] 브라우저 매트릭스: macOS Chrome·Safari·Firefox + Windows Chrome(가능한 것만, 못 한 것은 UNKNOWN 기록), 키보드 포커스 순회, reduced-motion, 390px.
- [ ] 접근성 스모크: Playwright + axe로 로그인·로비·작전실·커뮤니티·콘솔 5화면 critical 0.
- [ ] `docs/user/**` 화면 안내·README 스크린샷 교체, `docs/design/roadmap.md` 「세계와 지도」 아래에 UI 정본 링크.
- [ ] 최종 스크린샷 리포트 + 교차 비평.

## Phase 7 — 3D (가능하면, PR `ui-p7-3d`)

진입 조건: Phase 0~6이 main에 있고, 서버 초기화(Phase 8) 일정에 영향을 주지 않을 때만 한다. 못 하면 이 절을 「미착수 + 사유」로 남긴다.

현재 3D 표면: `web/game/components/v2/SpaceProof3D.tsx`(three 0.171, `v2-lab/space` 증명 화면, `v2-space-fps` e2e). 시안에는 3D 화면이 없다. 따라서 3D는 새 화면이 아니라 **작전실 지도의 3D 뷰 토글**과 **에셋**이다.

### Task 7.1: 저폴리 에셋 (opensamguk-images)

- [ ] 도시 등급 3종(현·군국치·수도)·관문·나루·항구·깃발 폴 low-poly 모델(glb, 정점 수 상한을 manifest에 기록), Concept A 팔레트 텍스처, `originals/3d/` 정본 + `exports/3d/` + 프리뷰 렌더. 제작 도구가 없으면 SVG 실루엣 extrude 로 대체.

### Task 7.2: 작전실 3D 뷰 토글 + picking

- [ ] `MapViewer`에 「3D」 토글(기본 2D, 설정 저장). `SpaceProof3D`의 씬 코드를 재사용해 프로빈스 면·도시 모델·깃발을 올린다. 클릭 = 같은 도시 id(D3-13 「3D picking」 — 2D와 동일 candidate id). 성능 게이트: `v2-space-fps` e2e 기준 유지, 저사양 폴백 2D.

### Phase 7 게이트

- [ ] 공통 게이트 + fps e2e + OPENSAM-46/#188 D3-13 체크 또는 사유.

## Phase 8 — 배포 · 서버 초기화 (사용자 지시 2026-09-06)

- [ ] main 머지 후 `deploy.yml` 성공 확인(web 반영). 게임 서버 승격은 `promote-game-server.yml`(엔진 포함, SHA는 `git rev-parse`로 풀어서 입력) — 상시 승인 범위.
- [ ] 프로덕션 프로브: `https://sam.peppone.dev/api/server-basic-info/<id>` 200, 로그인·로비·작전실 렌더 확인.
- [ ] 서버 초기화 전 **실측**: `GET /admin/env/servers/<id>`·`GET /api/admin/game-settings`로 현재 scenario code·generation·turn term을 읽는다. `reset-game-server.yml` 기본값(`scenario_2`·`current`)을 그대로 쓰지 않는다 — 시나리오를 바꾸면 초기화가 아니라 교체다.
- [ ] `reset-game-server.yml` 실행: server_id 실측값, confirmation `RESET <id>`, backup=true, scenario=실측 코드, generation=`current` 또는 사용자 지정, turn term=`current`. `operationId`를 `succeeded`까지 폴링한다. `recovery_required`면 멈추고 보고한다.
- [ ] 초기화 후 스모크: 기존 계정 로그인 → 장수 생성 → 작전실 → 명령 1건 예약 → 결과 폴링 `RESOLVED`. 결과를 사용자에게 보고한다.

## 리스크 · UNKNOWN

- 해소: 「GlobalMenu 8라벨 소스 없음(UNKNOWN)」은 틀렸다 — `app/game-api/.../GlobalMenuController.kt`에 있다. S2 대조표를 갱신한다.
- 토큰 교체(0.2)는 두 앱 전 화면의 색을 한 번에 바꾼다. 화면별 리스타일 전까지 옛 레이아웃 + 새 팔레트 상태가 잠깐 존재한다. 유저 유입 전이라 허용(승격 상시 승인 범위).
- 폰트 용량과 CI 네트워크: `next/font/google`은 빌드 시 다운로드한다. CI/도커 빌드가 막히면 Task 0.3을 self-host 슬라이스로 바꾼다.
- 회의실 「표결」은 기존 `/game/vote` 설문(서버 전체)과 다른 국가 단위 표결이다. `vote_poll`을 재사용하되 `nation_id` 범위 필터가 필요하면 V51에서 열을 더한다(구현 전 `VoteController` 확인).
- 열람 기록은 인테이크 경유라 202 뒤 폴링이 필요하다. 목록 갱신은 `RESOLVED` 후에만 한다.
- 확장 범위(Phase 4X)는 로드맵 4~6단계 백엔드를 앞당기는 것이라 전체 기간을 가장 크게 늘린다. 트랙마다 spec을 먼저 쓰고, 계획·작전·휘하가 없을 때의 경로가 오늘과 바이트 동일함을 골든으로 증명한다.
- 1000px 폭에서 사이드바를 뺐던 과거 사용자 요청과 3열 그리드가 충돌하지 않도록 1232px 미만은 2열로 떨어뜨린다.
- 이미지 생성 도구(`sprite-gen`의 이미지 생성 백엔드·`perfectpixel`)가 이 세션에서 실제로 동작하는지는 Phase 5 시작 시 확인한다. 안 되면 손 제작 SVG·픽셀로 범위를 줄이고 UNKNOWN 으로 남긴다.
- 3D는 시안에 없는 추가 작업이라 Phase 7로 밀고, 서버 초기화(Phase 8)를 막지 않는다.
