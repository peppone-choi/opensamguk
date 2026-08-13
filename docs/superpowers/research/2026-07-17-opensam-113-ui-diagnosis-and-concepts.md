# OPENSAM-113 UI 진단과 concept 비교

- **status:** `EVIDENCE_PASS_WITH_PHP_PARITY_PENDING`
- **lane:** `lane-113-ui-concepts`
- **scope:** gateway login/lobby, game `GameChrome`/auction의 진단과 A3 선택용 시안만
- **implementation:** 없음. 제품 CSS/TSX/asset/backend/gating은 변경하지 않았다.
- **approval fence:** A3 미승인. 이 문서는 concept를 추천하거나 선택하지 않으며 OPENSAM-114/115를 시작하지 않는다.
- **current-app addendum:** 2026-08-13 `origin/main` `f4ee9135`의 실제 Next DOM/CSS를 비밀 없는 fixture로 다시 렌더해
  `GameChrome`/resource auction의 desktop·mobile 4면을 관측했다. §14가 이 재개 작업의 정본이다.
- **comparison board:** `/Users/apple/.codex/visualizations/2026/07/17/019f6da9-8684-7500-a561-477b7aea3e48/opensam-113/comparison.html`

## 1. 판정 언어와 결론

- `[사실]`은 현재 source, component-test fixture, 실행한 명령, 실제 렌더된 comparison board, 별도 mocked browser baseline에서 직접 확인했다.
- `[추론]`은 사실에서 도출한 UX 판단이다.
- `[UNKNOWN]`은 live 인증/서버 응답 등 이번 lane에서 직접 확인하지 못한 값이다.
- `채점대기`는 통과가 아니다. 이번 addendum의 rendered/synthetic observations are scoped `EVIDENCE PASS` only;
  PHP-golden draw-for-draw parity, live 인증/CDN, and the corresponding phase gate remain `채점대기`.

**결론:** `[추론]` 현재 화면의 가장 큰 문제는 단순히 "어두운 색"이 아니라, 동일한 dark token 위에서 거의 모든 정보가 같은 크기·같은 표면·같은 경계 강도로 경쟁하고, 메인과 auction의 chrome이 갈라지며, desktop의 고밀도 구조가 mobile에서 축소만 된다는 점이다. 세 concept는 실제 라벨과 상태를 고정한 채 palette, typography, surface/hierarchy, spacing/density를 서로 다르게 바꾼다.

## 2. 범위와 불변식

### 2.1 포함

1. gateway login과 lobby의 desktop/critical-mobile 진단
2. game `GameChrome`과 auction의 desktop/critical-mobile 진단
3. color, density, typography, spacing, hierarchy, component 6차원 분석
4. 서로 실질적으로 다른 concept 3개
5. concept별 대표 mockup 정확히 2개, palette/type/spacing/density/component/a11y/trade-off
6. 동일 fixture, 동일 빈 상태, 동일 오류 문구, 동일 20-action conceptual gating을 사용한 공정 비교

### 2.2 제외

- CSS/TSX/component/asset 변경
- backend, API, command intake, 20-action gating 변경
- fake server, fake auction row, 미확인 live 수치 생성
- concept 선택, OPENSAM-114 구현, OPENSAM-115 design system 구현

## 3. 증거 인벤토리

### 3.1 CodeGraph와 source

`.codegraph/` 존재를 확인한 뒤 text search보다 먼저 다음 탐색을 실행했다.

```bash
codegraph explore "OPENSAM-113 current gateway login lobby GameChrome auction components and styles; show relevant files and call paths"
codegraph explore "web/gateway/app/login/page.tsx web/gateway/app/lobby/page.tsx and their rendered components plus global CSS; show full current source and relationships"
```

주요 source 근거:

| 근거 | 직접 확인한 사실 |
|---|---|
| `web/gateway/app/login/page.tsx:47-113` | `로그인`, `계정명`, `비밀번호`, 가입 링크, `ServerBoard`, footer가 한 세로 shell에 있다. client-empty validation과 API 인증 오류는 서로 다른 상태이며 오류는 form 안에서 한 줄 block으로 렌더된다. |
| `web/gateway/app/lobby/page.tsx:101-211` | 서버 행은 loading/closed/owned/full/unregistered를 한 table row 안에서 바꾸며, cyan/orange inline color도 사용한다. |
| `web/gateway/app/lobby/page.tsx:215-287` | 초기 서버 목록은 `[]`; `/api/servers` 실패도 `[]`; `servers.length > 0`일 때만 서버 선택 table이 보인다. empty에서는 server UI와 별도 empty copy가 모두 없다. lobby는 `AuthGate` 아래다. |
| `web/gateway/components/ServerBoard.tsx:35-72` | 서버가 없으면 server tabs/map/log 전체를 `null`로 숨긴다. |
| `web/gateway/app/globals.css:3-45` | `#0a0a0a`–`#1a1a1a` dark surface, gold, crimson, jade, 4/8/16/24/32 spacing이 gateway 전역 token이다. |
| `web/gateway/app/globals.css:186-283` | login card는 배경/경계 없는 폭 380px column이며 viewport 중앙에 놓인다. footer까지 같은 muted hierarchy다. |
| `web/gateway/app/globals.css:585-627,736-761,1231-1253` | lobby는 1100px, table은 horizontal scroll, mobile은 padding만 줄이며 reduced-motion override가 있다. |
| `web/gateway/app/layout.tsx:9-20` | gateway만 jsDelivr의 Pretendard variable stylesheet를 불러온다. |
| `web/game/components/game/GameChrome.tsx:98-189` | GlobalMenu→GameInfo→refresh/lobby→status→map/reserved/subject→20개 conceptual action→record/message→GlobalMenu 반복→privileged-only mobile 국가 메뉴 순서다. |
| `web/game/components/game/MainControlBar.tsx:110-153` | gate 실패는 링크가 아닌 `span[aria-disabled=true]`; enabled만 anchor다. 기능적으로 올바른 disabled 차단이다. |
| `web/game/lib/control-bar-config.ts:23-75` | `always`, `myLevel`, `myLevelAndNation`, `permission2`, `showSecret`의 5 bucket과 20개 conceptual action의 실제 한글 라벨/순서가 고정돼 있다. auction action이 둘로 갈라져 실제 `.control-btn` DOM node는 21개다. |
| `web/game/app/globals.css:647-856` | GameInfo는 13-cell grid, GlobalMenu는 mobile 4/desktop 8열, control bar는 mobile 5/desktop 10열이다. 20-action control bar가 존재하며 mobile 국가 메뉴는 privileged-only다. |
| `web/game/app/globals.css:1283-1449` | main board는 1232px부터 540/360/300px 3열, 그 아래는 단일 column으로 재배치한다. subject와 record는 gap 0/border 중심이다. |
| `web/game/components/Shell.tsx:21-33` | 모든 game page 공통은 Header/BackBar/BottomNav이고, `GameChrome`은 메인 page 내부에만 추가된다. |
| `web/game/app/game/auction/page.tsx:26-55` | auction은 `GameChrome`이 아니라 `Shell`만 사용한다. `금/쌀`/`유니크` selected state는 font weight만 달라지고 split menu의 `?type=unique` query는 초기 mode source로 소비되지 않는다. |
| `web/game/components/auction/AuctionResource.tsx:204-310` | 쌀 구매/판매는 각각 8열 CSS grid이고, row가 없어도 header는 남는다. 등록 form 기본값은 1000/24/500/2000이다. recent logs 원천은 비어 있다. |
| `web/game/components/auction/AuctionResource.tsx:83-92` | 목록 요청 실패는 toast `거래장 목록을 불러올 수 없습니다.`를 내보낸다. auction 자체에는 loading/closed 표본이 없다. |
| `web/game/components/auction/AuctionUniqueItem.tsx:170-278` | unique detail은 4열 detail, 3열 bid, 7열 list다. 목록 자체에는 horizontal-scroll wrapper가 없다. |
| `web/game/app/layout.tsx:9-14` | game root에는 font stylesheet가 없어 `globals.css`의 Pretendard 이름은 설치 환경 fallback에 의존한다. |

### 3.2 fixture와 상태 근거

| fixture/source | 비교 보드에 고정한 값 | 인식론 |
|---|---|---|
| `web/gateway/__tests__/account-settings.interaction.test.tsx:13` | authenticated user `tester`, role `USER` | `[사실] component-test fixture` |
| `web/gateway/app/lobby/page.tsx:215-262` | server list `[]`; server board/table hidden | `[사실] source invariant` |
| `web/gateway/lib/constants.ts:10-72` | login/lobby/footer의 실제 한글 label과 footnote | `[사실] source label` |
| `web/game/__tests__/GameInfo.test.tsx:6-18,66-80` | `빼섭 0기`, `【역사모드2-2】 반동탁연합 결성(정사)`, 200年 3月, NPC 3명 | `[사실] component-test fixture` |
| `web/game/__tests__/GameChrome.main-map.test.tsx:43-72` | online nations `위, 촉`, 접속자 `3`, 국가방침 `한실부흥`, `myLevel=0`, `permission=0`, `showSecret=false`, nation level 1 | `[사실] component-test fixture` |
| `web/game/__tests__/AuctionUniqueItem.test.tsx:34-73` | `전설의 말`, `적토마`, `가나다42`, `3,210포인트`, bid 300 | `[사실] component-test fixture`; annotation only |
| `web/game/components/auction/AuctionResource.tsx:75-81,204-310` | default resource auction, empty rows, 1000/24/500/2000 form | `[사실] source default/empty state` |

20개 conceptual action의 fixture gate 결과는 7 enabled / 13 disabled다. 단, auction action이 두 `.control-btn`으로 분리되어 production browser DOM은 21개 node를 렌더한다. privileged-only mobile dropdown은 이 fixture의 `permission=0`, officer level 0에서 0개다.

- enabled: `토 너 먼 트`, `중원 정보`, `현재 도시`, `유산 관리`, `내 정보&설정`, `경 매 장`, `베 팅 장`
- disabled: `회 의 실`, `기 밀 실`, `부대 편성`, `외 교 부`, `인 사 부`, `내 무 부`, `사 령 부`, `NPC 정책`, `암 행 부`, `세력 정보`, `세력 도시`, `세력 장수`, `감 찰 부`

### 3.3 live browser baseline manifest — A2 채점대기

`webapp-testing`의 helper를 source로 읽지 않고 먼저 help로 실행했다.

```bash
python3 .agents/skills/webapp-testing/scripts/with_server.py --help
```

결과: `--server`, `--port`, `--timeout`, trailing command 사용법을 확인했다.

초기 환경 확인:

```bash
curl -sS -o /dev/null -w 'gateway %{http_code} %{url_effective}\n' --max-time 5 http://127.0.0.1:3000/login
curl -sS -o /dev/null -w 'game %{http_code} %{url_effective}\n' --max-time 5 http://127.0.0.1:3001/game
docker compose ps --format json
```

관측:

- `[사실]` 두 URL 모두 연결되지 않아 HTTP `000`이었다.
- `[사실]` Docker socket `/Users/apple/.docker/run/docker.sock` 접근은 `permission denied`였다.
- `[사실]` `corepack pnpm dev`는 이 실행 환경에서 `corepack: command not found`; 직접 `pnpm 10.33.0`은 존재했다.
- `[사실]` sandbox 안의 `pnpm dev`는 `listen EPERM 0.0.0.0:3000`; 승인된 sandbox 밖 helper 재실행은 `Server ready on port 3000`까지 도달했다.
- `[사실]` Python Playwright의 versioned Chromium binary가 없었지만 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`은 존재해 그 executable로 재시도했다.
- `[사실]` 첫 desktop `/login`의 `page.goto(..., wait_until="networkidle", timeout=60000)`가 완료/timeout event를 반환하지 않았다. 약 100초 뒤 lane stop condition에 따라 interrupt했고 DOM/network/screenshot 결과는 생성되지 않았다.
- `[UNKNOWN]` navigation이 반환하지 않은 원인이 Next dev compile, HMR/network-idle, 브라우저/host 조합 중 무엇인지는 확정하지 않았다.
- `[사실]` `.env*`, cookie, username/password credential을 읽거나 출력하지 않았다.

실제 probe의 핵심 invocation은 다음과 같았다. inline Python은 desktop 1440×1000, mobile 390×844, response/console listener, full-page screenshot을 묶었다.

```bash
python3 .agents/skills/webapp-testing/scripts/with_server.py --timeout 90 \
  --server "cd web/gateway && pnpm dev" --port 3000 -- \
  python3 -c '<sync_playwright; installed Chrome; goto /login then /lobby; wait_until=networkidle; DOM/network/screenshot>'
```

| surface | intended viewport | live URL/state | DOM | network | screenshot | 판정 |
|---|---:|---|---|---|---|---|
| gateway login | 1440×1000 | `/login` public | 미수집 | 미수집 | 없음 | `채점대기` — first navigation 미완료 |
| gateway login | 390×844 | `/login` public | 실행 전 중단 | 실행 전 중단 | 없음 | `채점대기` |
| gateway lobby | 1440×1000, 390×844 | authenticated `AuthGate` 필요 | 실행 전 중단 | 실행 전 중단 | 없음 | `채점대기` — 인증 상태/credential 없음 |
| game GameChrome | desktop, critical mobile | `/game`, gateway auth + front-info 필요 | 실행하지 않음 | 실행하지 않음 | 없음 | `채점대기` — gateway baseline blocker 뒤 환경 복구 중단 |
| game auction | desktop, critical mobile | `/game/auction`, gateway auth + front-info/auction API 필요 | 실행하지 않음 | 실행하지 않음 | 없음 | `채점대기` — 동일 blocker |

따라서 "네 baseline surface가 실제 서버 응답과 일치한다"는 완료 조건은 이번 lane에서 주장하지 않는다. comparison은 아래에서 명시한 source/test fixture evidence일 뿐 live evidence가 아니다.

### 3.4 mocked API browser baseline — downstream implementation input

별도 lane이 현재 on-disk source의 `.env` 없는 임시 복사본에서 gateway/game production build를 각각 통과시킨 뒤 synthetic non-PII API fixture로 8개 화면을 렌더했다. 모든 API/external request를 intercept했고 credential은 읽지 않았다.

- artifact: `/Users/apple/.codex/visualizations/2026/07/17/019f6da9-8684-7500-a561-477b7aea3e48/opensam-113-live-baseline/baseline-report.json`
- SHA-256: `bbc09e29cc300f499425e9a7c1d42ca5f4a748d5a8db9ab526c44dbb2cb1e3b6`
- mode: `MOCKED API baseline`; `liveStack=false`

| surface | desktop 1440×1000 | mobile 390×844 | 사실 판정 |
|---|---|---|---|
| gateway login | `PASS` | `PASS` | empty submit의 client validation은 `유저명을 입력해주세요`; comparison board의 API-error specimen과 구별한다. |
| gateway lobby | `PASS` | `PASS` | mocked authenticated user + empty server fixture가 render/network/console/page-overflow/layout을 통과했다. |
| GameChrome | `FAIL` | `FAIL` | page overflow는 없지만 `.reserved-command-panel` 내부가 양쪽 viewport 모두 정확히 20px clip된다. `overflow:hidden`과 grid min/padding 조합을 **downstream implementation input**으로 기록한다. |
| resource auction | `FAIL` | `FAIL` | `.auctionHeader` styled-jsx owner scope가 맞지 않아 computed `display:block`; 헤더가 세로로 무너진다. 이를 **downstream implementation input**으로 기록한다. |

8개 화면 모두 console error, page error, bad response/request failure/unexpected fixture, page-level horizontal overflow는 0이다. GameChrome의 `overflow=FAIL`은 이 page-level PASS와 별개인 owned panel 내부 clip 판정이다. 관측된 production DOM은 20 conceptual action에 `.control-btn` 21개(auction split), privileged-only mobile dropdown 0개(`permission=0`, officer level 0)다. source finding으로 split menu의 `?type=unique`가 초기 mode에 반영되지 않는 점도 후속 구현 입력에 포함한다.

이 baseline은 mocked API 실행 증거이지 live 인증/CDN 증거가 아니다. 따라서 A2 live lane은 계속 `채점대기`이며 여기서는 두 layout bug나 query handling을 수정하지 않는다.

## 4. 현재 UI 6차원 진단

| 차원 | `[사실]` 현재 구현 | `[추론]` 사용자 영향 |
|---|---|---|
| color | gateway/game 모두 `#0a0a0a`, `#141414`, `#1a1a1a`의 매우 좁은 dark surface range다. legacy 상태에 raw `cyan`, `magenta`, `orange`도 병존한다. | 검은 면이 넓고 경계가 미세해 섹션 간 우선순위가 약하다. raw status color는 강조가 아니라 서로 경쟁하는 신호가 된다. |
| density | GameInfo 13 cell, GlobalMenu 최대 8열, 20-action bar 10열, map/reserved/subject/records가 gap 0으로 연결된다. auction은 8열/7열 grid다. | 숙련 유저에게 정보량은 보존되지만 첫 시선이 어디로 가야 하는지 불분명하다. 좁은 화면에서 같은 정보량이 압축돼 읽기보다 해독에 가깝다. |
| typography | gateway는 CDN Pretendard Variable, game은 stylesheet 없이 `Pretendard` 이름과 system fallback이다. 많은 정보·버튼이 12–14px이며 숫자/label 계층이 거의 같다. | 두 앱 사이 glyph width와 weight가 환경별로 달라질 수 있고, 제목/상태/데이터가 typographic hierarchy로 분리되지 않는다. |
| spacing | token은 4/8/16/24/32로 있으나 GameChrome 핵심 조립은 gap 0, 1px border 중심이다. gateway login은 반대로 큰 중앙 여백 안에 380px form만 남는다. | game은 답답하고 gateway empty-server 상태는 비어 보인다. 동일 제품인데 밀도 리듬이 양극화된다. |
| hierarchy | GameChrome은 `Header` 위에 GlobalMenu/GameInfo/status/board/control을 추가하고 bottom에 GlobalMenu를 반복한다. auction은 Shell만 사용해 GameChrome context가 사라진다. | 메인에서 경매로 갈 때 세계/장수/국가 context와 20-action command spine이 끊겨 다른 제품으로 이동한 느낌을 준다. |
| component | disabled anchor 차단, `aria-disabled`, `aria-pressed`, table caption, reduced-motion은 좋은 기반이다. 반면 active auction tab은 font weight만, 대부분의 clickable surface는 비슷한 dark rectangle이다. | 기능 상태는 코드에 있으나 시각 affordance가 충분히 분화되지 않는다. hover를 못 쓰는 mobile에서 active/clickable/disabled 차이가 약하다. |

### 4.1 가장 큰 visual issue

1. **Chrome 단절:** `[사실]` 메인만 `GameChrome`; auction은 `Shell`만 사용한다. `[추론]` 게임 문맥을 보존하는 공통 척추가 시각적으로 일관되지 않다.
2. **mobile 축소:** `[사실]` 999px 아래에서도 20-action bar는 5열이다. 별도 mobile dropdown은 privileged-only이며 현재 fixture(`permission=0`, officer level 0)에서는 렌더되지 않는다. `[추론]` 일반 사용자에게는 작은 tile의 우선순위 재구성이 없다.
3. **auction 압축:** `[사실]` resource 8열과 unique 7열 grid에는 자체 horizontal overflow wrapper/min-width가 없다. mocked baseline에서는 별도로 `.auctionHeader` scoped-style 누락에 따른 세로 붕괴를 관측했다. `[추론]` 390px에서 label/숫자가 지나치게 좁아질 가능성이 높다.
4. **표면 간 명도 차 부족:** `[사실]` base/elevated/card가 각각 `#0a0a0a/#141414/#1a1a1a`다. `[추론]` table, card, menu, status의 역할보다 1px border가 hierarchy를 떠맡는다.
5. **font 계약 불일치:** `[사실]` gateway만 remote Pretendard를 load한다. `[추론]` 동일 브랜드가 route에 따라 다른 metrics로 보일 수 있다.
6. **빈 상태의 의미 부재:** `[사실]` empty server에서 map/log/table은 모두 사라지고 account/footnote만 남는다. 이는 fake server 금지 invariant에는 맞다. `[추론]` 시각적으로는 실패/미설정/정상 empty를 구분하기 어렵다. 새 문구를 발명하지 않고도 empty container의 구조적 treatment가 필요하다.

## 5. 현재 accessibility 점검

### 5.1 직접 계산한 contrast

WCAG relative luminance 공식으로 current token을 계산했다.

| foreground / background | ratio | 판정 |
|---|---:|---|
| `#f0f0f0` / `#0a0a0a` | 17.37:1 | normal text AA/AAA |
| `#a0a0a0` / `#0a0a0a` | 7.57:1 | normal text AA/AAA |
| `#c9a227` / `#0a0a0a` | 8.18:1 | normal text AA/AAA |
| `#666666` / `#0a0a0a` | 3.45:1 | 12–14px normal text AA 미달 |
| `#666666` / `#1a1a1a` | 3.03:1 | 12–14px normal text AA 미달 |
| `#c62828` / `#0a0a0a` | 3.52:1 | normal text AA 미달 |

`[사실]` footnote/muted/error가 작은 글자에 이 낮은 token을 사용한다. `[추론]` 보조 정보와 오류가 가장 읽기 어려운 역전이 생긴다.

### 5.2 interaction

- `[사실]` project CSS는 `button:focus-visible`을 제공하지만 menu/control anchor용 명시적 focus-visible rule은 없다. 브라우저 기본 focus에 의존한다.
- `[사실]` global/control button은 14px text + 상하 8px padding이다. `[추론]` inherited line-height를 포함한 target은 약 39px로 44px 목표보다 작다.
- `[사실]` disabled control은 실제 navigation을 제거하고 `aria-disabled=true`를 제공한다. 이는 유지해야 한다.
- `[사실]` auction mode button은 `aria-pressed`, tables는 caption/heading을 제공한다. 이는 유지해야 한다.
- `[사실]` 두 CSS 모두 `prefers-reduced-motion` override가 있다. 세 concept도 이를 유지한다.
- `[추론]` raw cyan/magenta만으로 state를 구분하지 말고 text, border/pattern, weight를 함께 써야 한다.

## 6. 비교 공정성 계약

세 concept는 다음을 바꾸지 않는다.

1. `AUTH_LABELS`, `LOBBY_LABELS`, footnote 원문
2. global menu 8 label과 control 20 label/order
3. fixture gate의 enabled/disabled 결과 7/13
4. GameInfo fixture, online nation/user/notice fixture
5. resource auction default mode, 빈 row, form default
6. gateway `ServerRow`의 loading `불러오는 중…`와 closed `- 폐 쇄 중 -`, login error, disabled 상태의 존재
7. gateway empty server는 server UI/copy를 모두 생략하고, auction 실패는 toast `거래장 목록을 불러올 수 없습니다.`를 사용

comparison board는 외부 font/image/script/network 요청이 없는 단일 HTML이다. 지도 영역은 data screenshot이 아니라 CSS schematic이며 실제 city 값을 주장하지 않는다.

## 7. Concept A — 야전 사령부

**핵심:** 현재 dark war-room을 폐기하지 않고, 검은 면을 `야전 흑/철판`으로 정리하고 `청동/이끼/적갈`에 명시적 역할을 준다.

- **palette:** `#0c0f0e` 야전 흑, `#1b201d` 철판, `#d3b064` 청동 active, `#697e58` 이끼 secondary, `#c96b5d` danger
- **type scale:** display serif 28/18/16px 700–800; body system sans 13–15px; number `ui-monospace` 10–12px tabular
- **spacing:** 4/8/12/18/24px
- **density:** high. 20-action conceptual set과 command ledger를 한 viewport에 유지하되 framed section header로 덩어리를 끊는다.
- **component treatment:** square metal panel, inset active edge, dashed disabled tile, framed ledger, square map node
- **accessibility:** focus `#ffd36d` 3px, target ≥44px, disabled opacity 대신 dashed border+6.59:1 text, reduced motion
- **trade-off:** 세계관/숙련자 고밀도에 강하다. 어두운 면이 여전히 넓고 신규 사용자는 엄숙하게 느낄 수 있다.

대표 mockup은 정확히 2개다.

1. `A-GATEWAY`: desktop login+empty-server lobby + mobile login+mobile lobby
2. `A-GAME-AUCTION`: desktop GameChrome+resource auction + mobile GameChrome+mobile auction + 20 gate

## 8. Concept B — 현대 전략실

**핵심:** off-white work surface 위에 navy chrome, teal executable action, red error를 분리해 scan speed와 주간 가독성을 우선한다.

- **palette:** `#f3f5f1` work canvas, `#ffffff` panel, `#172638` navy chrome, `#0c6f68` primary, `#a7342d` danger
- **type scale:** system sans 32/24/18/16/14px 600–800; number `ui-monospace` 10–12px
- **spacing:** 4/8/12/16/24/32px
- **density:** medium. summary→map/command→auction의 명확한 단계; mobile은 중요 상태 우선
- **component treatment:** white work surface, 7px control radius, navy topbar, teal action, solid disabled tile, clean data table
- **accessibility:** focus red 3px, target ≥44px, muted 7.15:1, disabled 5.36:1, color+border+weight 병행
- **trade-off:** 학습성과 scan speed가 직접적이다. 역사 게임 고유성이 약해져 일반 dashboard처럼 보일 위험이 있다.

대표 mockup은 정확히 2개다.

1. `B-GATEWAY`: desktop login+empty-server lobby + mobile login+mobile lobby
2. `B-GAME-AUCTION`: desktop GameChrome+resource auction + mobile GameChrome+mobile auction + 20 gate

## 9. Concept C — 수묵 장부

**핵심:** 한지/먹/주사로 밝은 고전 장부를 만들고, 작은 data는 sans/mono를 써 장식성과 기능성을 분리한다.

- **palette:** `#eae2cf` paper, `#f4eddd` panel paper, `#252b28` ink chrome, `#a33b2f` cinnabar active, `#3f5963` blue ink
- **type scale:** serif display 28/18/16px 700–800; body system sans 13–15px; data `ui-monospace`; serif는 16px 미만 금지
- **spacing:** 4/8/12/20/28/40px
- **density:** medium. section 사이 여백은 크고 table cell은 8px로 유지한다.
- **component treatment:** square paper band, 4px seal rule, ink topbar, ruled ledger, cinnabar active state
- **accessibility:** ink/paper high contrast, target ≥44px, muted 5.99:1, disabled 4.63:1, state에 text/rule 동반
- **trade-off:** 세계관과 밝은 가독성을 결합한다. serif/texture 품질이 낮으면 테마 장식처럼 보일 수 있다.

대표 mockup은 정확히 2개다.

1. `C-GATEWAY`: desktop login+empty-server lobby + mobile login+mobile lobby
2. `C-GAME-AUCTION`: desktop GameChrome+resource auction + mobile GameChrome+mobile auction + 20 gate

## 10. Concept 비교표

| 비교축 | A 야전 사령부 | B 현대 전략실 | C 수묵 장부 |
|---|---|---|---|
| palette | dark charcoal + bronze/moss | off-white + navy/teal | paper + ink/cinnabar |
| typography | serif display + dense sans/mono | all-sans + mono | serif hierarchy + sans/mono data |
| surface | metal frame/inset edge | white work surface/radius | paper band/rule/seal edge |
| hierarchy | section frame와 accent edge | whitespace와 color block | whitespace와 ruled ledger |
| density | high | medium | medium |
| mobile | compact command sheet | prioritized status/work area | scrollable ledger strips |
| strongest fit | 기존 dark identity 연속성 | 빠른 학습/업무형 scan | 고유한 밝은 역사성 |
| primary risk | dark fatigue | generic dashboard | decorative pastiche |
| small text contrast | muted 8.59, disabled 6.59 | muted 7.15, disabled 5.36 | muted 5.99, disabled 4.63 |

이 표는 선택을 대신하지 않는다. 세 방향은 종류가 다르며 coverage는 동일하다.

## 11. Comparison artifact 검증

생성물:

| ID | path | 의미 |
|---|---|---|
| `BOARD-HTML` | `.../opensam-113/comparison.html` | self-contained 3-concept board |
| `BOARD-DESKTOP-1600` | `.../opensam-113/comparison-desktop.png` | board 자체의 1600px full-page QA screenshot; live baseline 아님 |
| `BOARD-MOBILE-390` | `.../opensam-113/comparison-mobile.png` | board 자체의 390px full-page QA screenshot; live baseline 아님 |

headless installed Chrome로 board를 직접 열어 확인했다.

- concepts `3`
- mockups `6` (`2 × 3`)
- composite 내부 mobile frame `12`: login/lobby/GameChrome/auction 각 `3`
- rendered comparison-board control tiles `60` (`20 conceptual actions × 3`); production의 `.control-btn` 21개와 literal DOM parity를 뜻하지 않음
- enabled controls `21` (`7 × 3`), disabled controls `39` (`13 × 3`)
- visible state specimen: gateway loading `3`, gateway closed/error `3`, login error `6`, auction failure toast `6`, empty omission `3`
- interactive targets: desktop `186`, mobile `180`; `<44px` violation `0`
- accidental overflow `0`; desktop document `1600 == 1600`, mobile document `390 == 390`
- mobile viewport `390`, document scroll width `390`
- six `.screen`: desktop `706 == 706`, mobile `330 == 330`
- console errors `0`, page errors `0`
- external requests `0`

## 12. A3 사용자 결정 양식

다음 중 하나를 사용자가 명시해야 A3가 열린다.

```text
선택 concept: A 야전 사령부 | B 현대 전략실 | C 수묵 장부
허용 변경: palette / typography / spacing / component / mobile hierarchy 중 선택
보존 조건: 실제 한글 label, 20-action conceptual order+gating, API state, empty/error/disabled semantics
```

**현재 A3 상태:** `BLOCKED BY USER SELECTION`. 선택 전 product implementation과 design-system 추출을 시작하지 않는다.

선택을 빠르게 하기 위한 사용자 관점 행렬:

| 내가 가장 중요하게 보는 것 | 고를 concept | 얻는 것 | 감수할 것 |
|---|---|---|---|
| 기존 dark 정체성과 숙련자용 고밀도 | **A 야전 사령부** | 현재 정보량을 거의 그대로 두고 frame·bronze·moss로 위계를 만든다. | 어두운 면적과 높은 밀도는 남는다. |
| 신규 사용자 학습성과 가장 빠른 scan | **B 현대 전략실** | 밝은 work canvas, navy chrome, teal action으로 가능/불가·상태·form을 가장 빨리 읽는다. | 역사 게임만의 인상이 가장 약하다. |
| 밝은 가독성과 삼국지 고유 분위기의 균형 | **C 수묵 장부** | paper/ink/cinnabar와 ruled ledger로 세계관과 데이터 위계를 함께 만든다. | serif·texture 품질 관리가 부족하면 장식처럼 보인다. |

선택 후 허용 변경 범위도 함께 고른다: `palette`, `typography`, `spacing`, `component`, `mobile hierarchy`.

## 13. Validation record

실행한 검증과 미실행 검증을 구분한다.

| 검증 | 상태 | 증거 |
|---|---|---|
| comparison HTML browser render | `EVIDENCE PASS` | §11 DOM counts, zero overflow, zero console error, desktop/mobile PNG; not a PHP-golden parity pass |
| mocked gateway/game browser baseline | `4 PASS / 4 FAIL — DOWNSTREAM INPUT` | §3.4 exact report SHA; gateway 4면 PASS, GameChrome 2면 internal clip FAIL, auction 2면 header layout FAIL |
| live auth/CDN gateway/game browser baseline | `채점대기 — SEPARATE A2 LANE` | mocked result와 comparison board 검증으로 live baseline을 주장하지 않음 |
| PHP-golden draw-for-draw UI/parity replay | `채점대기 — NOT RUN` | this docs-only lane has no PHP capture/replay artifact; no parity or phase-gate pass is claimed |
| tracked doc whitespace | `PASS` | 비어 있지 않음, 마지막 LF 존재, trailing whitespace 0개를 Python으로 확인 |
| `git diff --check -- <this doc>` | `NO COVERAGE` | 문서가 untracked라 exit 0은 본문 검증이 아니다. PASS 근거로 사용하지 않고 Python 전체본문 검사로 대체 |
| comparison HTML static validation | `PASS` | 44,714 bytes, `HTMLParser` parse, invented empty copy 0, concept data 3개, gateway/game render template 2개, external URL 0. 렌더 후 exact DOM counts는 §11 Chrome 결과 |
| `tools/agent-system/check.py` | `BASELINE ERROR — OUT OF LANE` | exit 1: 공유 worktree의 `.codex/config.toml` personal-model pin(`codex-surface`). 이 lane 소유 파일이 아니며 수정하지 않음 |
| gateway/game typecheck/test | `INCOMPLETE — NOT CLAIMED` | 세 명령을 시작했지만 30초 관측 안에 최종 exit를 얻지 못함. 공유 worktree가 계속 변하는 상태에서 재시도하지 않았으며 성공으로 간주하지 않음 |
| `./tools/smoke.sh` | 미실행 | Docker socket 접근 불가; doc/board-only lane에서 product smoke 성공을 주장하지 않음 |

## 14. 2026-08-13 current-app desktop/mobile 재진단

### 14.1 실행 경계와 재현성

- source: `origin/main` exact revision `f4ee9135`; worktree의 product TSX/CSS/API/backend는 변경하지 않았다.
- surface: 실제 `web/game` Next.js 15.5.20 DOM/CSS/component tree. 브라우저 요청만 Playwright가 synthetic non-PII
  fixture로 intercept했다. credential, cookie, `.env*`, backend, 외부 CDN은 읽거나 호출하지 않았다.
- enumerated set: `GameChrome`과 resource auction 각각 desktop `1440×1000`, mobile `390×844` — 총 4면 전부.
- report: `/Users/apple/.codex/visualizations/2026/08/08/019fdf9f-ecf1-7900-957e-04427e0b99f9/opensam-113-resume/current-app-report.json`
  (`SHA-256 eea8532d1d53613e3608dbdfa319973e2d1be7aca509e7e02b5ba35dc4ac69fa`).
- 모든 screenshot은 PNG signature `89504e470d0a1a0a`, 요청 viewport와 document width가 일치하고 console error/page
  error가 0이다. fixture hit는 auth/front-info/const/menu/map/reserved/mailbox/auction 및 의도적으로 abort한 SSE뿐이다.

초기 자동 캡처 timeout은 product failure가 아니었다. 같은 worktree의 orphaned Next 4 PID가 abandoned pipe에 연결된
상태였고, 정리 뒤 webpack은 `/instrumentation` 104.1초, ready 155초, `/middleware` 57.1초를 썼다. clean-copy
Turbopack은 외부 `node_modules` symlink를 명시적으로 거부했다. 최종 in-root Turbopack은 instrumentation Node 34.3초,
Edge 16.3초, middleware 6초, ready 104.4초, `/game` 81.5초/HTTP 200으로 warm-up한 뒤 4면 캡처를 완료했다.
이 기록은 반복된 tool failure를 cold-compile/evidence harness 문제로 격리하며 UI 합격 근거로 사용하지 않는다.

### 14.2 실제 렌더 관측

| surface | viewport | screenshot SHA-256 | DOM/geometry 관측 | 판정 |
|---|---:|---|---|---|
| GameChrome | 1440×1000 | `34d04916…ce8d` | document `1440×2239`; control node 21, disabled 13; reserved panel `298/318px` | `FAIL`: 내부 20px clip |
| GameChrome | 390×844 | `c4786d97…a250` | document `390×4660`; control node 21, disabled 13; reserved panel `372/392px` | `FAIL`: 내부 20px clip + 지나친 세로 길이 |
| resource auction | 1440×1000 | `66902e61…a26b` | document `1440×1000`; 두 `.auctionHeader` 모두 `display:block`, width 1100 | `FAIL`: 8열 header가 세로 목록으로 붕괴 |
| resource auction | 390×844 | `95eaa6fd…b491` | document `390×914`; 두 `.auctionHeader` 모두 `display:block`, width 374 | `FAIL`: 같은 붕괴, form은 viewport 하단에서 잘림 |

직접 screenshot 검토에서 추가로 확인했다.

1. desktop GameChrome은 상단 13-cell 상태, 지도, 36턴 명령, 도시/국가/장수 card, 20-action bar가 모두 같은
   1px dark boundary와 11–14px text로 경쟁한다. primary task가 지도인지 명령인지 조작 대상인지 한눈에 정해지지 않는다.
2. mobile GameChrome은 재구성이 아니라 desktop section의 단일-column 직렬화다. 첫 viewport에 map 일부만 보이고,
   action bar는 약 3,000px 아래에 있어 “지금 할 수 있는 행동”이 초기 화면에서 보이지 않는다.
3. auction은 GameChrome의 world/general/nation context와 action spine이 사라진다. desktop도 canvas 대부분이 비며,
   mobile은 header label 8개가 행이 아니라 세로 텍스트로 쌓여 데이터 구조를 읽을 수 없다.
4. 올바른 기반도 유지한다: page-level horizontal overflow 0, 실제 disabled navigation 제거+`aria-disabled`, auction
   `aria-pressed`, 빈 경매 행 날조 없음, mobile bottom navigation, console/page error 0.

### 14.3 concept가 current defects를 다루는 방식

| current defect | A 야전 사령부 | B 현대 전략실 | C 수묵 장부 |
|---|---|---|---|
| GameChrome hierarchy 경쟁 | frame header와 inset edge로 map/ledger/subject를 구획 | summary→map/command→action의 whitespace 단계 | paper band와 ruled ledger로 장부 단위 분리 |
| mobile action discoverability | compact command sheet를 상단 context 뒤 배치 | executable action을 prioritized work area로 승격 | 핵심 action strip을 첫 장부 뒤에 고정 |
| 20-action personalization | enabled bronze/moss, disabled dashed+reason | enabled teal, disabled solid neutral+reason | cinnabar active, ink-muted disabled+reason |
| auction 8열 붕괴 | framed ledger + mobile row/detail disclosure | clean table + mobile key/value work card | ruled ledger + horizontal strip/detail pair |
| game/auction chrome 단절 | 공통 metal command frame | 공통 navy workspace header | 공통 ink band와 seal marker |

세 방향 모두 서버 precheck의 allow/deny+reason을 소비하며 frontend 조건 복제를 금지한다. 신분상 무관한 항목만 숨기고,
현재는 불가능하지만 학습 가치가 있는 항목은 reason과 함께 disabled로 둔다는 ADR-LITE-022를 보존한다.

### 14.4 A2/A3 판정

- **current-app A2:** `EVIDENCE PASS WITH PRODUCT FINDINGS / PHP-GOLDEN PARITY 채점대기` — 실제 current Next surface
  4면의 완전한 desktop/mobile evidence와 defect 위치가 확보됐다. 이는 synthetic fixture의 evidence 판정일 뿐이며,
  PHP draw-for-draw replay·live account/backend 수치·CDN asset 성공 또는 phase-gate 통과를 주장하지 않는다.
- **concept comparison:** 세 concept, concept별 정확히 2 mockup, 동일 20-action gate와 동일 empty/error/disabled semantics를 유지한다.
- **A3:** `BLOCKED BY USER SELECTION + PHP-GOLDEN PARITY` — A/B/C와 허용 변경 범위를 사용자가 선택해야 하며,
  parity/live evidence가 `채점대기`인 동안 A3/phase-gate 완료를 주장하지 않는다.
- 이 문서/PR은 진단과 선택지만 제공한다. product implementation, OPENSAM-114/115, merge, deploy는 수행하지 않는다.

### 14.5 fresh concept-board evidence

독립 visual review 1차는 기존 2026-07-17 board의 mobile specimen이 CJK text를 7.5–11px로 축소해 concept가 선언한
13–15px body scale을 실제로 보여주지 못하고, current-app 재진단보다 capture가 오래됐다는 두 blocker를 찾았다. 제품
source가 아닌 off-repo `comparison.html`만 다음처럼 보정했다.

- mobile phone specimen 폭을 240→330px로 늘리고 핵심 CJK leaf text 최소 크기를 13px로 고정했다.
- mobile action specimen에 enabled action과 서버 precheck deny reason을 가진 disabled `회의실`을 함께 표시했다.
- current-app report SHA와 2026-08-13 diagnosis mapping을 board 상단에 명시했다.
- A/B/C desktop+mobile 전체를 새로 렌더했다. 3 concepts, 6 mockups, 12 mobile frames를 그대로 보존했다.

fresh artifact:

| artifact | SHA-256 | 측정 |
|---|---|---|
| `.../opensam-113-resume/comparison-desktop-fresh.png` | `bde627cc…0ff0` | 1600px document width, concepts 3, mockups 6 |
| `.../opensam-113-resume/comparison-mobile-fresh.png` | `441d745b…4189` | 390px document width, mobile frames 12 |
| `.../opensam-113-resume/concept-board-fresh-report.json` | `2ab31db8…18ac` | PNG signatures valid, min mobile font 13px, below-13 text 0, sub-44px target 0, console/page error 0 |

`...`의 공통 prefix는
`/Users/apple/.codex/visualizations/2026/08/08/019fdf9f-ecf1-7900-957e-04427e0b99f9`이다. full-page PNG는 세
concept 전체를 한 파일에 담은 comparison board이며 정확한 document height를 계약으로 삼지 않는다. 실제 current
GameChrome full-page capture의 파일명에 적힌 `390×844`는 viewport
요청값이고 실제 document는 `390×4660`임을 §14.2가 별도로 기록한다.

### 14.6 independent visual review gate

**`채점대기 — NOT CLAIMED`**. The six screenshots/reports referenced above are off-repo local artifacts, and this
branch does not contain a portable visual-review record identifying a reviewer and recording the exact commands and
results. Therefore this document makes no independent visual approval or `APPROVE` claim. The local fixture captures
remain implementation input/evidence only; PHP-golden draw-for-draw parity, live phase-gate, and independent visual
review are all `채점대기`.
