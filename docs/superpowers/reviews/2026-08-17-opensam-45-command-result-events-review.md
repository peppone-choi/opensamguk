# OPENSAM-45 — 명령 결과 push 신호 (SSE `commandResolved`/`commandRejected`)

Scope: OPENSAM-45 명령 결과 push 신호 — RealtimeEvent lifecycle 변형(common/) · outbox 릴레이 발행과 SSE 이벤트 이름(app/) · 신호-폴링 경주 수신(web/)
Verdict: cleared

## 0. 무엇을 했나

FE는 명령 제출 뒤 `pollCommandResultResponse`로 300ms × 20회 되물어 결과를 확인했다(최선 300ms,
최악 6초). 엔진은 flush commit 뒤 outbox 릴레이에서 결과를 Redis 키에 쓰지만 **그 사실을 아무에게도
알리지 않았다**. 이 티켓은 그 알림을 붙인다.

- `common`: `RealtimeEvent`에 `commandSettled` **한** 변형 추가(별도 파일 — 게이트 ② 동결 파일을
  건드리지 않는다). payload는 `at` + `requestId`뿐이다(§2).
- `app/game-engine`: `CommandOutboxRelay`가 outbox 행을 published로 마킹한 **뒤** 같은 게임
  이벤트 채널로 신호를 발행한다.
- `app/game-api`: `RealtimeRelayController.fanOut`이 SSE 이벤트 이름을 payload의 `type`에서 뽑는다.
- `web/game`: `submitCommandAndAwaitResult`가 **신호 vs 폴링을 경주**시킨다. 신호는 Shell이 이미
  열어 둔 SSE 연결(`hooks/useSSE.ts`)에 얹어 받는다 — 제출 때 두 번째 연결을 새로 열지 않는다.

## 1. 착수 전에 측정한 것 (추정 아님)

티켓 체크리스트를 코드에 대조했다. 이미 되어 있던 항목을 다시 "구현"하지 않기 위해서다.

| 항목 | 측정 결과 |
| --- | --- |
| 1-a~1-d (이벤트 정의·발행·릴레이) | **없음** — 구현 대상 |
| 1-e (FE 수신) | **부분** — 폴링만 있었다(`pollCommandResultResponse`) |
| 1-f (프런트 info 폴링 제거) | **이미 충족** — `web/game`에 주기적 info 폴링이 없다. 코드 변경 0 |
| 1-g (턴 이벤트에 부분 갱신) | **미수행** — §5 참조 |

## 2. 신호는 결과가 아니다 (설계의 축)

푸시 payload에는 `at`과 `requestId`만 싣는다. 결과 본문도, 성공/실패 여부도, deny 사유도 싣지
않는다. 정본은 여전히 `GET /api/command/result/{requestId}`이다. 이유는 세 가지다.

1. **정보 노출 (초판의 blocker).** `/sse/turn`은 수신자별 필터가 **없는** 월드 전역 브로드캐스트다.
   초판은 `reason`("금이 부족합니다.")과 `resultType`을 실었는데, 그러면 접속한 모든 브라우저가
   남의 명령 결과와 실패 사유를 그대로 받아 본다. 필터를 다는 대신 payload를 지웠다 — FE가 그 값을
   애초에 쓰지 않았기 때문에(깨움 신호로만 썼다) 지우는 쪽이 더 작은 diff다. `requestId`는 제출자만
   아는 불투명 값이라 남이 받아도 쓸 수 없다.
2. **위조 방지 (OPENSAM-13/135).** 두 경로가 각자 판정하면 조용히 어긋난다. push를 판정 근거로
   쓰면 "202 = 성공"과 같은 종류의 거짓말이 다시 생긴다.
3. **유실 내성.** SSE는 프록시 버퍼링·연결 끊김으로 없어질 수 있다. 신호가 사라졌을 때 결과까지
   사라지면 안 되므로 폴링 루프를 **제거하지 않았다** — 신호는 폴링을 앞당길 뿐이다.

그래서 FE는 `Promise.race([폴링, 신호])`를 돌리고, 신호가 이겨도 곧바로 정본을 한 번 더 읽는다.
그 읽기가 아직 `PENDING`이면 결론을 내지 않고 원래 폴링이 끝나기를 기다린다(성공을 '처리 지연'으로
잘못 접는 것을 막는다 — 테스트로 고정).

## 3. 순서 문제 — 키 먼저, 신호 나중

`CommandOutboxRelay`는 `publishCommandResultPayload`(폴링 키) → `markCommandOutboxPublished`
→ `publishRealtimeEvent`(신호) 순으로 움직인다. 뒤집으면 알림을 받은 FE가 **아직 없는 키**를 읽고
PENDING으로 물러나 신호가 무의미해진다. 릴레이가 이미 커밋된 outbox 행만 읽으므로 커밋 전 알림은
구조적으로 불가능하다. 신호 발행은 `runCatching`으로 감쌌다 — 발행이 실패해도 결과 처리(키 + 마킹)는
되돌리지 않는다. 세 가지 모두 테스트로 고정했다(`CommandOutboxRelayLifecycleEventTest`).

**반대 방향의 손실은 남는다.** 마킹 성공 후 발행 전에 데몬이 죽으면 그 신호는 재시도 없이 사라진다.
받아들이는 이유는 FE 폴링이 같은 창을 그대로 덮기 때문이다 — 신호가 없으면 예전 속도로 결론이 날 뿐
결과를 잃지 않는다. 신호를 재시도 대상으로 만들면 outbox에 두 번째 '발행됨' 상태가 생겨 멱등 기준이
둘로 갈라진다. 실패는 `runCatching`이 삼키지 않고 WARN으로 남긴다 — 조용히 삼키면 push 경로가 죽어도
폴링이 가려서 아무도 눈치채지 못한다.

## 4. 함께 고친 실제 결함 — 릴레이가 모든 이벤트를 `turnCompleted`로 이름 붙였다

`fanOut`은 payload와 무관하게 항상 `.name("turnCompleted")`를 붙이고 있었다. 그 상태로는 새
`commandSettled` 리스너가 **영원히 깨어나지 못한다**. 이름을 payload의 `type`에서 뽑도록 고쳤고,
읽을 수 없는 payload는 예전 이름(`DEFAULT_EVENT_NAME = "turnCompleted"`)으로 떨어뜨려 기존
구독자가 신호를 잃지 않게 했다.

**관측된 장애가 아니라 구조적 결함이다.** `RealtimeEvent.MessageCreated`도 같은 문제를 안고 있지만
main 소스에 **생산자가 없어**(선언·wire 테스트·골든 corpus뿐) 실제로 깨진 기능은 없다. 초판 문서는
이를 "메시지가 턴 이벤트 이름으로 도착했다"는 관측 사실처럼 적었는데, 근거 없는 서술이라 고쳤다.
기존 리스너 16곳은 전부 `turnCompleted` 하나만 구독하고 `onmessage` 핸들러는 `web/` 어디에도 없으므로,
이름 변경으로 깨지는 라이브 기능은 없다.

## 5. 미수행 — 1-g (턴 이벤트 부분 갱신)

이 티켓에서 하지 **않았다**. 현재 `Shell`은 `useSSE(refresh)`로 붙어 있고 `refresh`는
`window.location.reload()`다. 즉 턴 이벤트마다 전체 페이지가 다시 로드된다. 이를 부분 갱신으로
바꾸는 것은 (a) 어떤 read 쿼리를 무효화할지 화면별로 정하고 (b) BackBar·ErrorBoundary를 포함한
재마운트 지점을 전수 확인해야 하는 별개 작업이며, 이 티켓의 3개 백엔드 파일과 공유하는 코드가 없다.
같은 커밋에 넣으면 "지연 단축"과 "렌더 구조 변경"이 한 diff에 섞여 회귀 원인 분리가 어려워진다.
**후속 티켓 OPENSAM-196으로 분리했다.** 지금 상태에서도 명령 결과 지연은 사라지므로 사용자가 체감하는 목표는 달성된다.

## 6. 범위 밖에서 발견한 red — 별도 처리

`web/game` 전체 vitest에서 2건이 실패한다. **둘 다 이 브랜치 이전부터 실패한다**(stash 후 재현 확인).

- `live-noop-closures.test.tsx > select-pool update ...` — `docs/loops/opensam-41-v2-g0c-3d-proof/README.md`
  §4에 이미 base 실패로 기록된 항목이다.
- `v2-lab-route.test.tsx > v2-lab 라우트에 'use client'가 없다` — R4·R5·R6에서 v2-lab 페이지 3개가
  `'use client'`가 되면서 깨졌다(내가 만든 회귀다). OPENSAM-35 §7.6의 격리 전제(프로덕션 클라이언트
  번들에 v2 코드 없음)를 실제로 건드리므로 **별도 티켓 OPENSAM-195로 분리해 바로 처리한다.** 이 티켓에서 같이 고치면
  두 개의 무관한 변경이 한 diff에 섞인다.

## 7. 검증

- `:common:test :app:game-engine:test :app:game-api:test --rerun-tasks` → BUILD SUCCESSFUL.
  XML 집계: common 239 / game-engine 911(skip 1, Docker 부재 IT) / game-api 521 = **1671, fail 0 err 0**.
- `web/game` vitest 신규 10건 통과(`commandResultEvents.test.ts` 4, `commandSubmit.result-events.test.ts` 6).
  전체 385건 중 383 통과 + §6의 선행 실패 2건. 기존 테스트 4곳의 `pollCommandResultResponse` 호출
  단언은 인자가 하나 늘어(중단 신호) `expect.any(AbortSignal)`로 **넓히지 않고 정확히** 갱신했다.
- `pnpm lint` error 0(기존 `<img>`/exhaustive-deps warning만) · `pnpm build` 성공.
- `scripts/agent/v2-isolation-gate.sh` → **PASS**. ③ 경계 목록 = 선언한 3개 파일과 일치
  (`RealtimeRelayController.kt`, `CommandOutboxRelay.kt`, `RealtimePublisher.kt`).
- **비어 있지 않음(non-vacuity) 실측:** `fanOut`의 `.name(eventNameOf(json))`을 예전
  `.name(DEFAULT_EVENT_NAME)`로 되돌려 재실행 → `RealtimeRelayControllerEventNameTest` FAILED,
  복구 후 다시 green. 초판에서는 이 되돌림이 백엔드 1672건을 전부 통과시켰다.
- **미측정(UNKNOWN):** 실제 브라우저에서의 end-to-end 지연 단축은 재지 않았다(webapp-testing 채점대기).
  단위 수준에서는 신호 경로가 폴링 간격을 기다리지 않음을 테스트로 고정했다.

## 8. 프로덕션 코드에 남긴 테스트용 구멍

- `RealtimePublisher`를 `open class` + 두 메서드 `open`으로 바꿨다. 같은 파일군의
  `CommandResultRepository`/`CommandOutboxRelay`가 이미 쓰는 방식이며, 릴레이의 발행 **순서**를
  실제 객체로 검증하기 위해 필요하다.
- `RealtimeRelayController.eventNameOf`를 `private` → `internal`로 낮췄다. `fanOut`이 그 값을
  `SseEmitter.event().name(...)`에 넘기는 부분은 한 줄이고, pub/sub→fanOut 배달 경로는
  `RealtimeRelayIT`가 따로 덮는다.

## 9. 독립 리뷰 대응 (blocker 1 · fix-required 8 · nit 7)

작성자와 분리된 리뷰 에이전트가 공격한 결과와 처리다. 판정은 **REQUEST CHANGES**였고, 아래를 반영한
뒤 백엔드·프런트를 전부 다시 돌렸다.

| # | 지적 | 처리 |
| --- | --- | --- |
| blocker | `commandRejected.reason`이 월드 전역 SSE로 나가 남의 deny 사유가 노출된다 | **수정.** 이벤트를 `commandSettled` 하나로 합치고 payload를 `at`+`requestId`로 축소(§2). 필드가 늘면 즉시 브로드캐스트가 된다는 사실을 wire 테스트로 고정 |
| fix-required | `fanOut`의 `.name(eventName)`이 무테스트 — 되돌려도 1672건 전부 green | **수정.** `eventFor()`로 추출해 실제 전송 프레임의 `event:` 라인을 단언. 되돌림 실측으로 FAILED 확인(§7) |
| fix-required | 테스트 주석의 "`RealtimeRelayIT`가 배달 경로를 덮는다"는 거짓 | **수정.** 해당 주석 삭제 — IT에는 등록된 emitter가 없어 루프 본문이 돌지 않는다 |
| fix-required | `messageCreated`가 잘못된 이름으로 도착했다는 서술이 미검증 | **수정.** 생산자 부재를 확인하고 "구조적 결함, 관측된 장애 아님"으로 정정(§4) |
| fix-required | 제출 시점에 EventSource를 열면 첫 신호를 놓친다(pub/sub replay 없음) | **수정.** Shell이 이미 연 연결(`useSSE`)에 리스너를 얹었다 |
| fix-required | 모듈 전역 EventSource가 닫히지 않아 탭마다 상시 연결이 하나 더 쌓인다 | **수정.** 연결 소유를 버렸다 — 이 모듈은 대기소만 남는다 |
| fix-required | 신호로 결론이 나도 폴링이 남은 19회를 계속 쏜다 | **수정.** `pollCommandResultResponse(requestId, signal)` + 결론 시 abort. 정본이 PENDING이면 끊지 않는 것도 테스트로 고정 |
| fix-required | 릴레이의 맨 `runCatching`이 실패를 조용히 삼킨다 | **수정.** 세 지점 모두 WARN 로깅 |
| fix-required | "신호 후 정본이 거절"인 경우의 테스트가 없다 | **수정.** 해당 케이스 추가 — 신호가 와도 판정은 정본이 한다 |
| nit | `EVENT_WAIT_MS`가 폴링 상수의 손계산 복제 | **미반영(사유 기록).** 상수를 export하면 `@/lib/api`를 좁게 mock한 기존 테스트 4종이 로드 단계에서 깨진다. 어긋나도 안전한 방향(창이 짧으면 신호가 안 쓰일 뿐)이라 주석으로 근거를 남겼다 |
| nit | `__reset`이 타이머를 정리하지 않는다 | **수정.** 대기자를 깨움 없이 종료시키며 타이머까지 정리 |
| nit | `RecordingPublisher`가 `publishRealtimeEvent` 본체를 건너뛴다 | **수정.** `RealtimePublisherEventTest` 추가 — 채널·다형 직렬화를 실제로 태운다 |
| nit | 릴레이의 FQN 인라인 참조 | **자연 해소.** payload 디코드 자체가 사라졌다 |
| nit | `VerticalSliceE2EIT:645` 낡은 주석(`name("realtime")`) | **미반영.** 선행 결함이며 이 티켓 경계(3파일) 밖이다 |
| nit | 테스트마다 heartbeat 스케줄러가 뜬다 | **미반영.** 데몬 스레드라 JVM을 붙잡지 않는다 |
| — | 잘못된 판정 노출·ONE daemon-write 위반·기존 리스너 파손·공허한 테스트 | 리뷰어가 각각 전수 추적 후 **없음**으로 판정 |
