# OPENSAM-195 — v2-lab 라우트 'use client' 회귀 복구

Scope: OPENSAM-195 v2-lab 클라이언트 격리 회귀 — v2-lab 라우트 3종의 서버 컴포넌트 복원과 대화형 본문의 components/v2 이관(web/)
Verdict: cleared

## 0. 무엇이 깨져 있었나

`web/game/__tests__/v2-lab-route.test.tsx > v2-lab 정적 자산 격리 전제`가 main에서 빨간 상태였다.

```
AssertionError: expected [ …(3) ] to deeply equal []
```

R4·R5·R6(OPENSAM-153/154/155)에서 v2-lab 라우트 3개가 `'use client'`가 되면서 깨졌다. **내가 만든
회귀다.** 세 PR 모두 CI green으로 머지됐는데, CI가 `web/game` vitest 전체를 돌리지 않아 드러나지
않았다(그 사실 자체는 별개 문제로 §5에 남긴다).

## 1. 스캔 통과 문제가 아니다 — 실제 격리 전제가 깨졌다

OPENSAM-35 §7.6의 판정은 "v2-lab은 server-only라 프로덕션 클라이언트 청크가 빈 스텁"이라는 전제
위에 있다. OPENSAM-41이 `lib/v2/buildIsolation.mjs` webpack 훅으로 `components/v2/**` import를
`V2_ENABLED !== 'true'` 빌드에서 빈 스텁으로 치환해 그 전제를 복원했는데, **라우트 파일 자체가
클라이언트 컴포넌트면 그 파일의 코드는 스텁을 우회해 그대로 번들에 들어간다.** `/_next/static/**`는
middleware matcher 밖이라 404 게이트가 닿지 않으므로, v2 화면 코드가 HTTP 200으로 나가고 있었다.

그래서 테스트의 단언을 고치지 않았다. 코드를 전제에 맞췄다.

## 2. 한 일

`app/game/v2-lab/{garrison,transport,ledger}/page.tsx`의 대화형 본문을 그대로 옮겼다.

| 라우트 | 옮긴 곳 | 남은 라우트 파일 |
| --- | --- | --- |
| garrison | `components/v2/GarrisonRecruitForm.tsx` | 서버 컴포넌트 — `Shell` + `h1` + 폼 |
| transport | `components/v2/CityTransportForm.tsx` | 〃 |
| ledger | `components/v2/CityLedgerBoard.tsx` | 〃 |

`space` 라우트(OPENSAM-41)가 이미 쓰던 모양 그대로다. 동작·문구·제출 경로는 손대지 않았다 —
파일 위치만 옮긴 변경이다.

## 3. 실측 (주장 아님)

`pnpm build` 두 번, 같은 명령에 `V2_ENABLED`만 바꿔서 대조군을 만들었다.

```
V2_ENABLED 미설정 (프로덕션 기본)
  garrison/page-5f8dd200….js   1,075 B
  ledger/page-10b73be0….js     1,075 B
  transport/page-66620160….js  1,075 B
  space/page-2a0aa616….js        675 B   (기존)
  grep -rlE "city-transport|garrison-recruit|v2_city_ledger|fetchCityLedger|도시 자원 수송|병사 보충" .next/static/ → 0 파일

V2_ENABLED=true (대조군 — 플러그인이 늘 스텁을 만드는 게 아님을 증명)
  garrison/page-8959a10b….js   5,796 B   (스텁 대비 5.4배)
  transport/page-e2238052….js  6,159 B   (5.7배)
  ledger/page-bc16e3f3….js     2,562 B   (2.4배)
  grep -rlE "city-transport|garrison-recruit" .next/static/ → garrison·transport 청크 2개
```

프로덕션 빌드의 클라이언트 번들에 v2 화면 코드도, 엔드포인트 문자열도 없다. OPENSAM-35 §7.6 전제가
실측으로 복원됐다.

## 4. 검증

- `pnpm vitest run __tests__/v2-lab-route.test.tsx` → **17/17 통과**(이전 16/17).
- `web/game` 전체 vitest → 375건 중 374 통과. 남은 실패 1건은
  `live-noop-closures.test.tsx > select-pool update …`으로, `docs/loops/opensam-41-v2-g0c-3d-proof/README.md`
  §4에 이 작업 이전부터의 실패로 이미 기록된 항목이다(이 브랜치와 무관).
- `pnpm lint` error 0 · `pnpm build` 성공(§3).
- 백엔드 미실행 — **변경 파일이 `web/game`뿐이다**(Kotlin/SQL 무수정).

## 5. 남기는 것 (고치지 않았다)

CI의 `web (game)` 잡이 이 실패를 잡지 못했다. R4·R5·R6가 전부 green으로 머지된 것이 증거다.
vitest 전체를 CI에 넣을지는 러닝타임·기존 red 1건 처리와 함께 결정할 문제라 이 티켓에서 손대지
않는다. 지금 상태에서도 격리 전제 자체는 §3 실측으로 닫혔다.
