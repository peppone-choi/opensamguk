# tools/web — 화면 품질 도구

| 도구 | 하는 일 |
|---|---|
| `measure-pages.mjs` | 화면 한 번 적재의 첫 그림 · 요청 수 · 전송 크기 · 모바일 동작 · 접근성 위반을 잰다 |
| `board-lint.mjs` | 설계 보드(`*.dc.html`)의 44px 미만 누를 것 · title 전용 정보 · 이모지 · 쓰지 않는 말 · 글자 대비 미달을 센다 |
| `check_well_known_places.py` | 지명 목록과 城 표 대조(별도) |

둘 다 `web/game`의 `@playwright/test`와 시스템 Chrome(`channel: chrome`)을 쓴다. 브라우저를 내려받지 않는다.

```sh
pnpm -C web install --frozen-lockfile --filter @opensamguk/web-game...
node --test tools/web/measure-pages.test.mjs tools/web/board-lint.test.mjs   # 적색 프로브
```

## measure-pages.mjs

```sh
node tools/web/measure-pages.mjs --out <dir> --base https://sam.peppone.dev \
  --pages /login,/join,/board --profiles desktop,mobile --throttle none
```

- 실행마다 새 브라우저 컨텍스트를 쓴다(콜드 캐시).
  - 프로필: `desktop`은 1440×900, `mobile`은 390×844 · DPR 3 · 터치.
  - 망: `broadband`는 50/10 Mbps · RTT 20ms다.
- **지도 첫 그림과 적재 창은 2026-10-01에 바뀌었다. 09-30 M1 기준선 값과 바로 비교하지 않는다.** 시각 기준(goto 호출)과 전송 크기 식만 같다.
  - 지도 첫 그림: 지도 뿌리(`--map-selector`, 기본 `.os-iso-map`) 안 캔버스 전부를 20×20 격자로 훑어 alpha>0 표본이 8개 이상인 시점(뿌리가 늦게 붙어도, 큰 캔버스에 작게 그려도 잡는다). 09-30은 첫 캔버스 81점 중 20점 초과였다.
  - 지도 뿌리를 기다리는 시간: 망이 잠잠해지고 load 가 지난 뒤 `--map-grace-ms`(기본 15초)까지. 지도 없는 화면은 행마다 이만큼 길어진다(요청은 늘지 않는다).
  - 적재 창: 첫 그림(지도 없는 화면은 지도 대기가 끝난 때) 뒤 망이 `--settle-quiet-ms`(기본 3초) 동안 조용할 때까지, 최대 `--settle-max-ms`(기본 30초). 첫 그림 뒤에 오는 요청(省 PNG 등)도 적재 수치에 든다. 09-30은 networkidle 뒤 0.5초에 닫아 첫 그림 뒤 요청을 놓칠 수 있었다.
  - 행이 끝날 때까지 받는 중인 요청은 `pendingList`(주소 · 그때까지 받은 바이트)와 `pendingPartialBytes`로 남긴다. `transferBytes`에는 다 받은 것만 든다. EventSource · WebSocket 같은 끝나지 않는 흐름은 기다리지 않는다. 취소된 요청(AbortController · 바뀐 이미지 src · prefetch 중단 · 「머리만 받고 끊기」)은 실패도 받는 중도 아니다. `canceledList`(주소 · 서버가 알린 크기 `offeredBytes` · 끊기 전 받은 바이트)와 `canceledPartialBytes`로 따로 남긴다.
  - `wireBytes`: 실제로 선을 탄 바이트(다 받은 것 + 받는 중 · 취소된 것의 받은 만큼). summary 「선 위 MB」 열. 2026-10-01 운영 /login 은 provinces 를 요청해 `content-length` 24,666,640 B 를 받고 본문 0 B 에서 끊는다(省 레이어 꺼짐).
  - 전송 크기: CDP `encodedDataLength`의 합.
- **실패를 0으로 삼키지 않는다.**
  - 측정을 못 한 행(오류 행)이 하나라도 있으면 CLI 종료 코드가 1이다. `checks`가 걸린 것은 실패가 아니다(측정 도구다).
  - 요청한 화면이 아니면 오류 행이다: 최종 경로가 요청 경로와 다르거나(로그인이 풀려 `/login`으로 넘어감 등) 문서 응답이 4xx · 5xx. 이때는 `…-wrong-page.png` 캡처만 남긴다.
- 결과 파일
  - `<화면>-<프로필>-<망>.json`: 전부 담는다.
  - `…-viewport.png` · `…-map.png`: 캡처. 요청한 화면이 아니면 `…-wrong-page.png`만.
  - `summary.md` · `summary.json`: 표.
- 재는 것
  - 시간: FCP · LCP · CLS, 지도 첫 그림, 망이 잠잠해진 시각, 첫 그림 뒤 기다린 시간(`postDrawSettle`: 기다린 ms · 상한에 닿았는지 · 첫 그림 뒤 요청 수).
  - 요청: 요청 수와 전송 크기(종류별), 같은 URL 중복 전송, 압축 안 한 글자 응답, 실패한 요청, 콘솔 오류.
  - 모바일 동작: 가로 넘침, 누를 영역 44px 미만(화면 밖 요소는 가운데로 들여서 재고, 들일 수 없는 것만 상자 크기 `targetsMeasuredByRectOnly`. 원인을 `cause`로 나눈다: 상자는 44 이상인데 누를 영역이 작으면 `overlap`(덮여서 줄어듦), 상자 자체가 작으면 `box` — `smallTargetsByCause`). 라벨로 감싸거나 `for` 로 이은 입력(체크 상자 · 라디오)은 입력 · 라벨을 사각형 하나씩 본다 — 둘 중 하나라도 누를 영역이 44×44 이면 통과이고, 둘 다 미달이면 넓이가 큰 쪽을 보고한다(너비 · 높이를 따로 골라 섞지 않는다). 원인(`cause`)은 결과를 정한 사각형의 상자로 나눈다, 덮인 누를 것(첫 화면에서 가려지면 화면 가운데로 스크롤해 한 번 더 재고, 그래도 가려질 때만 `coveredTargets`. 입력이 제 라벨(또는 그 안)에 덮였으면(꾸민 체크 상자) 덮임이 아니라 라벨 규칙으로 재고, 라벨도 다른 것에 덮였을 때만 덮임이다. 고정 아래 탭 · 머리줄 밑에 걸쳤다가 스크롤하면 맞는 것은 `coveredAtFirstViewOnly`로 따로 적고 결함으로 세지 않는다 — 2026-10-02), title에만 있는 정보, `:hover`로 드러내는 CSS, 12px 미만 글자.
  - 접근성: axe(wcag2a · 2aa · 21a · 21aa, `e2e/a11y-smoke.spec.ts`와 같은 태그)의 영향도별 위반.
  - 지도(데스크톱): 가운데 점이 지도 캔버스인지, 휠 · 끌기 뒤 그림이 바뀌는지, 그동안의 프레임 수.
- `checks`에는 문서에 있는 기준만 넣는다. 각 기준에 출처를 함께 적는다.
  - 지도 첫 그림 3초(광대역)
  - 중복 전송 0: 문서에 크기 기준이 없어서 중복을 전부 센다.
  - 콘솔 오류 0 · 네트워크 실패 0
  - axe 치명 0
  - 44px
  - title 전용 0
- `--cdp-url http://127.0.0.1:9222`는 **사용자가 직접 로그인해 둔** Chrome에 붙어 로그인 뒤 화면을 잰다.
  - 새 탭 하나만 열어 캐시를 끄고 재고 닫는다.
  - 이 도구는 계정을 만들거나 자격증명을 입력하지 않는다.
- `--print-snippet`은 CDP가 없는 사용자 브라우저용이다. 탭 안에서 돌릴 JS를 찍는다(브라우저 도구의 JS 실행이나 개발자 도구에 붙인다).
  - 같은 배치 · 지도 검사 함수를 쓰고, 결과는 JSON 한 줄이다.
  - 콜드가 아니다. 캐시에서 온 것은 `cachedResources`로 따로 센다.
  - Resource Timing 버퍼(기본 250개)가 차면 `resourceBufferFull`이 참이고, 이때 요청 수는 하한이다.
  - axe는 돌리지 않는다. 사용자 화면에 바깥 스크립트를 넣지 않기 위해서다.
- 운영 서버에는 필요한 만큼만 쓴다. 한 번 실행하면 화면 × 프로필 × 망 × `--repeat` 번 적재한다. `/login`은 적재 1번에 약 57 MB였고(2026-09-30), 10-01 운영 게이트웨이에서는 약 1.9 MB다.

**시간 값 읽는 법**
- 시간은 측정하는 기계의 CPU 경합에 흔들린다. 그래서 결과에 `host.loadavg1`과 CPU 수를 남긴다.
- 부하가 CPU 수보다 크게 높을 때 잰 시간은 상한으로 읽는다. 요청 수 · 바이트는 부하와 무관하다.
- 프레임 수는 헤드리스 rAF로 센 추정값이다.

## board-lint.mjs

```sh
node tools/web/board-lint.mjs docs/design/ui-v3/project --md out.md --json out.json [--fail-on small,title,emoji,words]
```

보드를 그린 뒤 센다. 바깥 요청(글꼴 · `support.js` · `/_blob` 그림)은 막는다.

| 항목 | 기준 | 세는 법 |
|---|---|---|
| `small` | V3System 「누르는 것은 모두 44px 이상」 + 2026-09-30 K0 「보이는 크기가 아니라 누를 영역」(`.btn.sm` 예외 폐기) | 누를 영역이 가로나 세로 44 미만인 것. 누를 영역은 가운데에서 바깥으로 `elementFromPoint`를 훑어 그 요소가 적중하는 폭 · 높이다(패딩 · `::before` 확장 포함). 문장 속 링크(WCAG 2.5.8 예외)는 `smallInline`으로 따로 센다 |
| `fake` | 09-18 BRIEF 「버튼은 진짜 `<button>`」 | cursor:pointer인데 button · a · label · input이 아닌 것 |
| `title` | V3System 「호버 · title로만 보이는 정보 금지」 | title 글자가 요소의 보이는 글자에 없는 것 |
| `hover` | 같은 규칙 | 보드 요소에 걸리는 `:hover` 규칙 중 display · visibility · opacity를 드러내는 것 |
| `emoji` | BRIEF 「이모지 금지」 | 기본이 그림 표시인 글자, VS16을 붙인 그림 글자, 키캡. ▲▼ 같은 글자 기호는 세지 않는다 |
| `words` | V3System 「쓰지 않는 말」(`boards_v3_shell.py` `WORDS`) | 취소선을 그은 글자(그 표 자체)는 뺀다 |
| `clipped` | BRIEF 「내용이 넘치면 잘린다」 | 보드 뿌리(고정 크기) 밖으로 나가 잘린 글자 · 누를 것 |
| `covered` | 2026-09-30 K0 판정 | 가운데가 다른 요소에 덮인 누를 것(겹친 투명 상자 · 장식 · 띠). 무엇이 덮었는지 경로를 함께 적는다. 열린 층(`.sheet` · `.dim` · `.scrim` · `.pop` · `role=dialog` · `aria-modal`) 아래는 결함이 아니라 `underLayer`로 센다. 지도 표식 `.mk`는 층 아래여도 결함이다 |
| `contrast` | WCAG AA 4.5:1(큰 글자 3:1). 제품 a11y 스모크 · 측정 도구와 같은 axe `color-contrast`(2026-10-03, 원장 D57 · D73–D76a: 보드 색이 화면 토큰에서 어긋나 같은 빨강이 화면에서 되풀이됐다) | axe 가 대비 미달로 판정한 글자 노드 수. 바탕이 그라데이션 · 그림 · 겹친 상자라 axe 가 정하지 못한 것은 `contrastUnknown`으로 따로 센다. 그래서 0 은 「대비 통과」가 아니라 「판정한 것 중 미달 0」이다. `data-lint="skip"` 설명 글은 뺀다. CI 는 `naming-lint` 잡에서 `--fail-on contrast` 로 막는다(아래) |

**CI 게이트(2026-10-03, CEO):** 필수 잡 `naming-lint` 가 보드 · 생성기 · board-lint · ci.yml 이 바뀐 PR 과 main push 에서 `board-lint docs/design/ui-v3/project --fail-on contrast` 를 돈다. 미달 기준선은 걸림 0 이다(#1280 뒤 보드 전부). 이 단계 때문에 잡 한도를 20분으로 올렸다(로컬 210장 412초).
- 한글 글꼴(`fonts-noto-cjk`)을 깐다. 글꼴이 다르면 줄이 늘어 꽉 찬 뿌리 밖으로 글자가 잘리고, 잘린 글자는 axe 가 세지 않는다.
- 걸리면 종료 코드 1 이고, 걸린 노드(보드 · 대상 · 글자 · 색 · 대비)를 로그에 찍는다. 표 · JSON 은 artifact `board-contrast-<attempt>` 로 남는다. 표의 「대비 잰 노드」는 통과 + 미달 + 판정 못 함이다.
- `web (game)` 잡이 아닌 까닭: 그 잡은 docs/ 변경에서 돌지 않는다(`tools/ci/changed_paths.py`).
- **잰 노드 하한(2026-10-04, CEO)**: 같은 단계에 `--contrast-floor tools/web/board-contrast-baseline.json` 이 붙는다. 「미달 0」이 「못 잼」이 아님을 보인다.
  - 걸리는 경우: 보드마다 대비를 잰 노드가 기준선보다 허용 차이(1)를 넘게 줄었거나, 기준선에 없는 새 보드에서 0 이다.
  - 기준선 · 허용 차이는 CI 실측에서 뽑았다. 근거는 그 파일의 `source` · `why` 에 있다. CI 세 run 은 210장 모두 같았고, 로컬(macOS) ↔ CI 는 보드당 최대 1노드 차이였다.
  - 걸리면 `[contrastFloor] 보드: 잰 노드 N < 기준선 M − 허용 1` 을 찍는다. 걸린 것이 없어도 `--contrast-floor …: 보드 N장 · 잰 노드 … · 기준선 아래 0장` 한 줄을 찍는다(배선 확인).
  - 보드를 바꿔 글자가 줄었으면 같은 PR 에서 기준선을 고친다. 브라우저 없이, 결과 JSON 에 있는 보드 값만 덮어쓴다.
    - 그 PR CI 의 artifact `board-contrast-*` 에서 `board-lint.json` 을 받아 쓴다(실패해도 올라간다). 로컬 `--json` 결과도 된다(차이 1 안).
    - 명령: `node tools/web/board-lint.mjs --update-contrast-floor tools/web/board-contrast-baseline.json --from board-lint.json --source "어디서 쟀나"`

`clipped`에서 빼는 것:
- 지도 SVG 글자
- 화면 읽기 전용 글자
- 안쪽 상자가 일부러 자른 줄(순 띠 등). 이것은 `innerCropped`로 따로 센다.

**해석과 한계**
- 「전(錢)」 · 「곡(穀)」의 「전」 · 「곡」은 한 글자라 다른 말과 겹친다. 그래서 한자만 센다.
- 「년 월(표기)」은 순이 없는 「N년 N월」로 읽는다. 이것은 해석이다.
- 누르는 모양은 cursor:pointer로만 알아본다. 커서 지정 없이 탭처럼 그린 `span`은 세지 않는다.
- 보드의 설계 설명 글(주석)은 조상에 `data-lint="skip"`을 달면 `words` · `emoji` · `contrast`에서 빠진다. 크기 검사는 그대로 한다.
- 대비는 바깥 글꼴을 막은 채 잰다. 글자 크기 · 굵기는 CSS 값이라 큰 글자 기준(3:1) 판정은 그대로다.
- `WORDS`가 바뀌면 `board-lint.test.mjs`의 대조 테스트가 깨진다. 그때 `FORBIDDEN`을 함께 고친다.
- 층을 표식 없이 그리면(클래스 · role 없음) 그 아래 누를 것이 `covered`(결함)로 잡힌다. 열린 층은 위 표식 중 하나로 표시한다.
- 헤드리스 Chrome이 도중에 닫히면 두 도구 모두 한 번 다시 띄워 잰다. 그래도 안 되면 오류 행을 남기고, 보드 검사는 종료 코드 1이다.
  - 2026-09-30에는 다른 세션의 넓은 패턴 pkill이 원인이었다. 이 도구를 끌 때는 자기 PID만 끈다.
