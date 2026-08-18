# OPENSAM-198 — 서신 저장 시각 9시간 과거 기록 (라이브 E2E 발견)

Scope: `app/game-engine/src/main/kotlin/opensamguk/engine/intake/MessageHandler.kt` 의 `formatPhpDate` 오프셋 누락 수정, `MessageHandlerTest` 회귀 테스트 추가, `web/game/e2e/mailbox-delete-live.spec.ts` 의 terminal payload 중첩 해석 정정
Verdict: cleared

## 무엇을 고쳤나

### 1. 결함 — 저장 시각이 정확히 9시간 과거

로컬 풀스택(8서비스, docker compose) 라이브 주행에서 서신을 보낸 직후 삭제하면
항상 거부됐다.

```
reason = "5분 이내의 메시지만 삭제할 수 있습니다."
DB 실측: now() - message.time = 09:01:06
```

`formatPhpDate` 가 PHP `$date->format('Y-m-d H:i:s')` 를 흉내내며 **오프셋 없는**
문자열을 만들고, `JdbcFlushExecutor.messageCreateMany`(`infra/.../JdbcFlushExecutor.kt:1680-1690`)
가 그 문자열을 `CAST(:time AS timestamptz)` 로 바인딩한다. 오프셋이 없으면
PostgreSQL 은 값을 **세션 TimeZone** 으로 해석한다 — 세션이 Asia/Seoul 이고
문자열이 UTC 벽시계면 저장 순간이 정확히 9시간 과거로 밀린다.

`CreatedMessageRow.time`(`:2639-2648`)은 `Instant` 가 아니라 `String` 이므로
`Timestamp.from` 누락 문제가 아니다. 결함은 문자열 생산자 한 곳이다.

삭제 게이트(`MessageHandler.kt:177-180`)는 PHP 충실 이식이라 **옳다** —
`$prev5min = now - 5min; if ($msgObj->date < $prev5min)`. 9시간 밀린 저장 시각
때문에 항상 걸린 것이므로, 게이트가 아니라 시각을 고쳤다.

수정: 패턴을 `"yyyy-MM-dd HH:mm:ssXXX"` + `withZone(ZoneOffset.UTC)` 로 바꿔
오프셋을 문자열에 담는다. 로그 바이트 패리티 대상이 아닌 **DB 저장 전용** 문자열이며
UI 노출 경로가 없다.

### 2. 회귀 테스트

`formatPhpDate가 오프셋을 포함해 절대시각을 보존한다` — 포맷 결과에 오프셋이
있는지, 되읽었을 때 원래 `Instant` 와 같은지 검사한다.

기존 테스트가 이 결함을 **놓친 이유**를 KDoc 에 기록했다: 저장·조회가 같은 세션 TZ
로 상쇄되므로 왕복(round-trip)만 보는 테스트는 9시간 이동을 관측할 수 없다.

### 3. E2E 스펙의 payload 오독 정정

`command/result` terminal payload 실측(2026-08-18):

```json
{"status":"RESOLVED","ok":true,"type":"sendMessage",
 "result":{"type":"sendMessage","ok":true,"generalId":1230,"msgID":2}}
```

`ok`/`type`/`reason` 은 최상위에 복제되지만 **`msgID` 는 `result` 안에만** 있다.
스펙이 최상위에서 `msgID` 를 읽고 있었으므로 `terminalResult()` 헬퍼를 두고
두 호출부(`:195`, `:325`)를 중첩 경로로 돌렸다.

## 증거

```
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests '*MessageHandlerTest*' --rerun-tasks
BUILD SUCCESSFUL in 4m 31s

TEST-opensamguk.engine.intake.DiplomaticMessageHandlerTest.xml   11 tests 0 fail 0 err
TEST-opensamguk.engine.intake.RaiseInvaderMessageHandlerTest.xml  3 tests 0 fail 0 err
TEST-opensamguk.engine.intake.MessageHandlerTest.xml             26 tests 0 fail 0 err
=== 합계 tests=40 failures=0 errors=0 skipped=0
```

## 건드리지 않은 것

- `VALID_UNTIL_SENTINEL = "9999-12-31 00:00:00"`(`:569`) — 먼 미래 상수라 9시간
  이동의 실질 영향이 없다. 지금 바꾸면 무관한 diff 가 늘 뿐이다.
- 삭제 게이트 자체. PHP 충실 이식이며 이번 결함의 원인이 아니다.
- 다른 타임스탬프 바인딩. `.toString()`(ISO-8601) 또는 `Timestamp.from` 을 쓰므로
  같은 결함이 없다 — 확인 후 남긴 판정이다.

## 남은 것

- 라이브 E2E 재주행(수정 이미지 기동 후) 로 green 증거 확보 → OPENSAM-5 종결 조건.
