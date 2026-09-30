# tools/web — 화면 품질 도구

| 도구 | 하는 일 |
|---|---|
| `measure-pages.mjs` | 화면 한 번 적재의 첫 그림 · 요청 수 · 전송 크기 · 모바일 동작 · 접근성 위반을 잰다 |
| `board-lint.mjs` | 설계 보드(`*.dc.html`)의 44px 미만 누를 것 · title 전용 정보 · 이모지 · 쓰지 않는 말을 센다 |
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
- 지도 첫 그림과 전송 크기는 2026-09-30 M1 기준선과 같은 식이다. 그래서 전후 값을 바로 비교할 수 있다.
  - 지도 첫 그림: `.os-iso-map` 캔버스 81점 중 20점 넘게 칠해진 시점.
  - 전송 크기: CDP `encodedDataLength`의 합.
- 결과 파일
  - `<화면>-<프로필>-<망>.json`: 전부 담는다.
  - `…-viewport.png` · `…-map.png`: 캡처.
  - `summary.md` · `summary.json`: 표.
- 재는 것
  - 시간: FCP · LCP · CLS, 지도 첫 그림, 망이 잠잠해진 시각.
  - 요청: 요청 수와 전송 크기(종류별), 같은 URL 중복 전송, 압축 안 한 글자 응답, 실패한 요청, 콘솔 오류.
  - 모바일 동작: 가로 넘침, 44px 미만 누를 것, title에만 있는 정보, `:hover`로 드러내는 CSS, 12px 미만 글자.
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
- 운영 서버에는 필요한 만큼만 쓴다. 한 번 실행하면 화면 × 프로필 × 망 × `--repeat` 번 적재한다. `/login`은 적재 1번에 약 57 MB다(2026-09-30).

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
| `small` | V3System 「누르는 것은 모두 44px 이상」 | 진짜 조작 요소와 누르는 모양(cursor:pointer) 요소 중 가로나 세로가 44 미만인 것. 문장 속 링크(WCAG 2.5.8 예외)는 `smallInline`으로 따로 센다 |
| `fake` | 09-18 BRIEF 「버튼은 진짜 `<button>`」 | cursor:pointer인데 button · a · label · input이 아닌 것 |
| `title` | V3System 「호버 · title로만 보이는 정보 금지」 | title 글자가 요소의 보이는 글자에 없는 것 |
| `hover` | 같은 규칙 | 보드 요소에 걸리는 `:hover` 규칙 중 display · visibility · opacity를 드러내는 것 |
| `emoji` | BRIEF 「이모지 금지」 | 기본이 그림 표시인 글자, VS16을 붙인 그림 글자, 키캡. ▲▼ 같은 글자 기호는 세지 않는다 |
| `words` | V3System 「쓰지 않는 말」(`boards_v3_shell.py` `WORDS`) | 취소선을 그은 글자(그 표 자체)는 뺀다 |
| `clipped` | BRIEF 「내용이 넘치면 잘린다」 | 보드 뿌리(고정 크기) 밖으로 나가 잘린 글자 · 누를 것 |

`clipped`에서 빼는 것:
- 지도 SVG 글자
- 화면 읽기 전용 글자
- 안쪽 상자가 일부러 자른 줄(순 띠 등). 이것은 `innerCropped`로 따로 센다.

**해석과 한계**
- 「전(錢)」 · 「곡(穀)」의 「전」 · 「곡」은 한 글자라 다른 말과 겹친다. 그래서 한자만 센다.
- 「년 월(표기)」은 순이 없는 「N년 N월」로 읽는다. 이것은 해석이다.
- 누르는 모양은 cursor:pointer로만 알아본다. 커서 지정 없이 탭처럼 그린 `span`은 세지 않는다.
- 보드의 설계 설명 글(주석)은 조상에 `data-lint="skip"`을 달면 `words` · `emoji`에서 빠진다. 크기 검사는 그대로 한다.
- `WORDS`가 바뀌면 `board-lint.test.mjs`의 대조 테스트가 깨진다. 그때 `FORBIDDEN`을 함께 고친다.
