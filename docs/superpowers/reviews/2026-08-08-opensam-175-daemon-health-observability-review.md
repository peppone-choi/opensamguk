# Review: OPENSAM-175 — 턴 데몬 차단이 헬스에 드러나지 않던 관측 공백

Scope: `app/game-engine/src/main/kotlin/opensamguk/engine/status/TurnDaemonHealthIndicator.kt`, `app/game-engine/src/main/kotlin/opensamguk/engine/status/DaemonPauseGate.kt`, `app/game-engine/src/main/kotlin/opensamguk/engine/status/StatusController.kt`, `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonRunner.kt`, `app/game-engine/src/main/kotlin/opensamguk/engine/turn/InMemoryTurnWorld.kt`, `app/game-engine/src/test/kotlin/opensamguk/engine/status/TurnDaemonHealthIndicatorTest.kt`, `app/game-engine/src/test/kotlin/opensamguk/engine/status/StatusControllerTest.kt`, `app/game-engine/src/test/kotlin/opensamguk/engine/run/TurnDaemonRunnerTest.kt`, `app/game-engine/src/test/kotlin/opensamguk/engine/flush/FlushRecoveryHealthIndicatorTest.kt`, `.github/workflows/deploy.yml` — 데몬 상태를 헬스에 정직하게 노출하는 신규 `HealthIndicator` 와 그 지지 변경
Verdict: cleared

## 사고 (프로덕션 실측)

`spep` 게임 서버의 턴 데몬이 2026-08-05T16:20:49Z ~ 2026-08-08T00:44Z **2.3일 영구 차단**. 그동안 컨테이너 12개 전부 `Up`, `/api/health` 는 `{"status":"UP"}`. 어떤 헬스체크·재시작 트리거·알림도 안 걸렸다. 근본원인(diplomacy flush)은 PR #365 로 수정·배포 완료. **이 티켓은 관측 공백만 다룬다.** 사고 전말: `docs/superpowers/reviews/2026-08-08-diplomacy-flush-deleted-nation-review.md`.

## 이 PR 이 실제로 닫는 것 — 과장하지 않는다

독립 리뷰가 확인한 사실:

- `FlushRecoveryHealthIndicator` 는 OPENSAM-132 이래 recovery not-ready 에 **이미 DOWN** 을 반환했다. 사고 2.3일 동안 엔진 자신의 `/actuator/health` 는 이미 DOWN 이었을 것이 거의 확실하다. 사고 기록의 `{"status":"UP"}` 은 nginx 가 노출하는 `/api/health`(게이트웨이/게임API 평면)이지 엔진 액추에이터가 아니다 — **엔진 액추에이터는 애초에 외부에서 볼 수 없었다.**
- `docker-compose.production.yml` 의 game-engine 블록에는 **healthcheck 자체가 없고**(`expose: 8082` 뿐), `infra/nginx/nginx.conf:151-157` 엔진 라우트는 **주석 처리**되어 있다.

**정정 — 소비자는 0개가 아니다.** 이 문서 초안은 "듣는 주체가 0개"라고 단정했다. 커밋 직후 `deploy.yml` 실패 이력을 실측해 보니 **틀렸다.** `.github/workflows/deploy.yml:591` 이 배포마다 `docker exec <engine> wget -qO- localhost:8082/actuator/health | grep -q '"status":"UP"'` 로 **엔진 액추에이터를 직접 소비한다.** 그리고 실제로 사고를 잡았다 — 2026-08-07T23:41 / 08-08T00:27 배포 두 건이 정확히 이 줄에서 NO-GO 됐다(run 31227905107 / 31230125448, `deploy` job failure).

문제는 그 줄이 **메시지 없이 `status=1`** 로만 끝난다는 것이다. 게이트는 제대로 걸렸는데 로그에는 "왜"가 없어 아무도 읽어내지 못했다. **침묵한 게이트는 안 걸린 게이트와 구분되지 않는다** — 이 리뷰의 필자조차 그 실패를 못 보고 "소비자 0개"라고 썼다. 같은 커밋에서 세 헬스 체크(api/engine/web route)에 실패 사유 echo 를 붙였다.

따라서 정직한 진술은 이렇게 좁혀진다:

> **배포 시점에는 소비자가 있다(그리고 작동했다). 배포와 배포 사이에는 없다.** 2.3일 방치는 continuous monitoring 부재이지 소비자 전무가 아니다.

**이 PR 이 배포 게이트에 주는 영향(초안이 누락했던 것):** `deploy.yml:591` 이 액추에이터 종합 status 를 읽으므로, 신규 `turn_stalled`/`loop_not_running`/`clock_unavailable` DOWN 은 **그대로 배포 NO-GO 가 된다.** 의도한 방향이고(멈춘 데몬 위로 배포하지 않는다), `paused → UP` 결정이 여기서 결정적이다 — 그게 없었다면 의도된 동결이 배포를 막았다. 오탐 위험은 낮다: 재배포 시 컨테이너가 재기동하며 `loopStartedAt` 이 3틱 유예를 새로 발급한다. 다만 **stalled 상태에서 배포하면 verify 가 빨간불이 된다**(수정 배포 자체는 verify 앞 단계에서 이미 적용되므로 차단되지는 않는다).

실질적 신규 커버리지는 **`turn_stalled` 하나**다. 이건 진짜 가치가 있다 — recovery gate 를 세우지 **않고** 멈추는 고장류(락업, `XREAD BLOCK 0` 동결)를 잡는 유일한 상위 지표이고, 기존 recovery 인디케이터가 못 잡는 영역이다. 그러나 **"2.3일 방치 방지" 는 프로덕션 healthcheck + 알림 소비자가 붙기 전까지 닫히지 않는다.** 후속 티켓이 필요하다.

## 설계

신규 `TurnDaemonHealthIndicator` 하나. 새 엔드포인트·새 진단 수집기·새 클래스를 만들지 않고 기존 `TurnDaemonRunner.diagnostics()` + `DaemonPauseGate` 를 읽는다. 판정 순서:

| 조건 | 결과 |
|---|---|
| `autoStartEnabled = false` | UP `disabled` |
| 루프 uptime 없음(미기동/스레드 사망) | **DOWN `loop_not_running`** |
| 월드 미생성 | UP `not_started` |
| 클럭 조회 실패 | **DOWN `clock_unavailable`** |
| `tickSeconds <= 0` | **DOWN `tick_seconds_invalid`** |
| `paused` | UP `paused` / `statusLabel=동결중` |
| 기준점 age > `3 × tickSeconds` | **DOWN `turn_stalled`** |

**세 가지 핵심 판단:**

1. **`paused` 는 UP 이다.** 1차 리뷰가 BLOCKER 로 잡았다 — `Status.OUT_OF_SERVICE` 는 Boot 기본 매핑이 503(`javap -c` 로 `SimpleHttpCodeStatusMapper.DEFAULT_MAPPINGS` 확인, `application.yml` 에 오버라이드 없음)이라 의도된 어드민 동결이 `docker-compose.yml:264` 의 `web-game` 기동과 **`tools/ops/predeploy_go_check.sh:170` 배포 게이트를 차단**한다. 위험 배포 전 동결은 운영자가 마땅히 하는 일인데 그게 배포를 막는다. 동결 구분은 `StatusController` 가 이미 `state="paused"`/`statusLabel="동결중"` 로 정확히 노출한다.
2. **지연 지표는 게임 클럭이 아니라 벽시계다.** `TurnRunService.kt:489` 는 `setLastTurnTime(runTime)` 으로 **게임 스케줄 시각**을 심는다. 캐치업 중에는 그 값이 며칠 뒤처진 채 틱마다 `tickSeconds` 씩만 전진하므로, 게임 클럭 기준 판정은 **데몬이 정상 작동하는 내내 거짓 경보**를 낸다(사고 직후 실제로 8초/턴으로 몰아쳤다). 대신 틱 성공 직후 갱신되는 기존 `lastTickCompletedAt` volatile 을 지표로 승격했다 — hot path 추가 비용 0.
3. **recovery 판정은 `FlushRecoveryHealthIndicator` 에 위임.** 중복 블록을 삭제하고 증명 책임을 신규 `FlushRecoveryHealthIndicatorTest` 3케이스로 이전했다.

**임계 `STALE_TICK_MULTIPLIER = 3`** 은 하드코딩 + 근거 주석(`CLAUDE.md` M-config: post-parity 까지 상수 외부화 보류).

## 교차검토 — Claude 3회 + Codex 1회, 매 라운드 실제 결함을 잡았다

이 티켓은 **리뷰가 세 번 되돌려보냈다.** 그 자체가 기록할 가치가 있다: 되돌아온 결함이 매번 **"관측 코드 자신이 거짓 UP 을 낸다"** 는 같은 종류였다 — 사고를 만든 실패 양식이 그 사고를 감시하는 코드에서 반복됐다.

### 1차 (Claude, `fix-required`)
- **[BLOCKER]** `paused → OUT_OF_SERVICE`(503) 가 배포 게이트와 의존 기동을 차단. → `Health.up()` + details 로 복귀.
- **[P1]** 이 변경이 사고를 잡는다는 주장이 과장 — recovery 판정은 신규 커버리지가 아니고, 프로덕션에는 듣는 주체가 없다. → 위 "실제로 닫는 것" 절로 정직하게 축소.
- **[P1]** `serviceMaterialized == false → UP` 이 "데몬이 영영 안 뜸" 을 숨긴다. → 3분기로 분리.
- **[P2]** 캐치업 오탐. → 벽시계 지표로 교체.
- **[P2]** `InMemoryTurnWorld.kt:90` `private var state` 가 `@Volatile` 아님(선재 결함, 이 PR 이 폴러를 붙이며 노출 빈도를 올림). → 한 단어 추가.
- **[P2]** `clock_unavailable` 이 파싱 실패와 설정 이상을 뭉갬 / recovery 중복 / 빈 등록 미증명. → 각각 분리·삭제·컨텍스트 테스트.

### 2차 (Claude, `fix-required`)
A/B/C/D 설계 질문은 전부 닫혔다. 특히 **B**: recovery-gated 동안 `lastTickCompletedAt` 이 실제로 갱신을 멈추는지 확인 — `TurnDaemonRunner.kt:220-241` 이 `continue` 해서 `runTick` 에 도달하지 못하고 대입 지점은 성공 경로 단 한 곳이므로 성립. **C**: 실패 틱은 `lastTickFailedAt` 으로 분리돼 있어 매초 예외로 실패하는 사고 상황에서 age 가 단조 증가 → DOWN.

- **[P1]** `loopUptimeSeconds` 가 `running.get()` 만 보고 **스레드 생존을 확인하지 않는다.** `running` 은 `stop()` 에서만 false 가 되는데 루프는 `break` 나 `Error`(OOM/StackOverflow — 월드 전체를 RAM 에 올리는 데몬에서 현실적)로도 빠져나간다. 그러면 헬스가 `daemon=running` 을 3시간 보고한다 — **이 티켓이 없애려던 거짓 UP 이 신규 코드에 그대로 재현.** → `worker` 생존 확인 추가.
- **[P2]** 신규 진단 필드가 `show-details: never` 때문에 어디에도 안 보인다(5번 산출물이 관측 불가). → `StatusController` 로만 노출. actuator 본문은 배포 게이트 정규식 때문에 안 건드림.
- **[P2]** 실패 틱이 `lastTickCompletedAt` 을 갱신하지 않는다는 **핵심 불변식에 단언이 없다** — `:278` 근처에 잘못 추가해도 전 테스트 green. → `assertNull` 추가.

### 3차 (Claude, `ship`)
A/B/C/D 재확인. **B 재판정**: 5번 순서 변경이 1차 BLOCKER 를 재개방하지 않는다 — `clockError` 는 DB·I/O 없는 순수 산술 경로(`world.getState()` 필드 반환 + `nextRunTime()` 산술)라 정상 운영에서 던질 수 없고, `tickSeconds<=0` 은 `ScenarioSeedRunner.resolveTurnTerm` 이 허용집합 밖이면 **부팅을 실패**시켜 런타임에 도달하지 않는다. **D**: 재배포 유예 3시간 ≫ compose 판정 한계(`start_period 90s + 30×10s` ≈ 390초)라 정상 재배포가 unhealthy 로 굳지 않는다.

- **[P2]** `Thread(...)` 생성자가 OOM 으로 던지면 `running=true` + `worker=null` + `loopStartedAt` non-null 이 영구히 남아 `?: true` 폴백이 거짓 UP 을 통과시킨다. → `loopStartedAt` 대입을 `t.start()` 뒤로 옮기고 가드를 `worker?.isAlive == true` 로 복원(NIT 하나가 같이 닫힘).
- **[P2]** `StatusController.loopAlive = runner.isRunning` 이라 스레드 사망 시 어드민(`loopAlive=true`)과 헬스(DOWN)가 모순. → `loopUptimeSeconds != null` 기준으로 통일.

### Codex (다른 프로바이더, `fix-required` → 반영)

격리 워크트리에서 완주. 위 세 라운드가 모두 놓친 것을 잡았다:

> `VERDICT: fix-required — 의도적 동결 해제와 동일 JVM 재기동에서 정상 복구 중인 데몬을 건강하지 않다고 보고한다.`

- **[P2, 실질 BLOCKER]** 동결 중에는 지연 판정을 건너뛰어 UP 이지만, **해제되는 순간** `lastTickCompletedAt` 이 동결 기간만큼 낡아 있어 다음 성공 틱까지 `turn_stalled` DOWN(prod 최대 1시간)이다. "위험 배포 전 동결 → 배포 → 해제" 가 정상 운영 흐름이므로, **1차 BLOCKER 가 다른 문으로 되돌아온다.**
- **[P2]** in-process `stop()`→`start()` 재기동도 같은 뿌리.

**수정**: 판정 기준점을 "마지막 성공 틱" 단독에서 **"데몬이 틱을 돌 수 있었던 가장 최근 시점"** = `max(lastTickCompletedAt, 마지막 unlock, loopStartedAt)` 으로 일반화(경과초로는 셋의 `min`). 기존 `neverTicked → loopStartedAt` 특수 케이스가 이 규칙의 퇴화형이라 흡수된다. `DaemonPauseGate` 에 unlock 전이 시각을 기록하되 **실제 동결→가동 전이일 때만** 기록하고(no-op unlock 은 유예를 재발급하지 않는다), 부팅 시 durable `plock` 복원은 전이가 아니므로 clear 한다(그 창은 `loopStartedAt` 이 이미 덮는다).

**기각한 대안**: 동결 중 `lastTickCompletedAt` 을 계속 밀어주기. 실제로 안 돈 시간을 돈 것처럼 기록하는 **성공 위조**다. 사실 기록은 그대로 두고 **판정 기준점만** 옮겼다 — 이 구분을 코드 주석에 남겼다.

**완화가 아님**: 세 기준점 중 어느 것이든 `3 × tick_seconds` 를 넘도록 성공 틱이 없으면 여전히 DOWN 이다. resume/restart 는 정확히 3틱 유예 한 번을 살 뿐이다.

## 반영하지 않은 지적

- **[P2]** `enabled=false` 오설정 자체는 여전히 UP 이다. 설정이 선언한 의도와 오설정은 정의상 구분 불가하고, 여기서 DOWN 을 내면 `GameEngineApplicationTests` 계열 컨텍스트가 쓰는 정상 경로까지 장애로 부른다. 대신 `daemon=disabled` 를 본문에 박아 조용히 묻히지 않게 했다.
- **[NIT]** 사고 재현 테스트가 `StackOverflowError` 를 스레드 밖으로 흘려 uncaught 스택트레이스를 test `system-out` 에 남긴다. 삼키면 스레드가 안 죽어 테스트가 아무것도 증명하지 못하고(반증력 0), `setDefaultUncaughtExceptionHandler` 는 JVM 전역 가변 상태라 병렬 테스트에 부작용이 남는다. → 의도된 로그임을 KDoc 에 명시하고 향후 로그 스캐너 화이트리스트 대상으로 남겼다(`docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md:233`).

## 범위 밖으로 남긴 것 (후속 필요)

- **배포 사이 continuous monitoring 부재** — `docker-compose.production.yml` game-engine 블록에 healthcheck 가 없고(로컬 `docker-compose.yml:202-208` 에는 있다) nginx 엔진 라우트는 주석 처리다. 배포와 배포 사이 며칠을 아무도 안 본다. **이게 2.3일 방치의 진짜 원인이고 이 PR 은 손대지 않는다.** 후속: #368.
- **알림/재시작 트리거 없음** — DOWN 을 사람에게 도달시키는 경로가 없다. 배포 verify 는 배포할 때만 본다.
- **`deploy.yml:503` 조용한 skip 재검토** — `recovery_ready != true` 일 때 "verification skipped" 로 넘어간다. 이번에 고친 침묵(`:591`)과 같은 종류의 문제이나, skip 은 사유를 echo 하므로 로그에는 남는다. 그 skip 이 정당한지는 별도 판단이 필요하다.
- **`management.endpoint.health.show-details`** 미설정(기본 `never`) 유지 — actuator 본문 변경은 `predeploy_go_check.sh:63-65` 정규식과 `docker-compose.yml:203` 에 영향이 가므로 이 티켓에서 건드리지 않았다. 진단 디테일은 `/admin/turn-daemon/status` 로만 노출된다.

## 검증

- 게이트: `:app:game-engine:test --rerun-tasks` — 오케스트레이터가 직접 실행해 `BUILD SUCCESSFUL`, XML 집계 **tests 780 / failures 0 / errors 0 / skipped 1**(skipped 1 = 선재 Docker 의존 IT).
- **반증 실증**: 기준점 일반화를 되돌리면 동결-해제 테스트와 재기동 테스트가 `TurnDaemonHealthIndicatorTest.kt:170,196` 에서 실패(`17 tests completed, 2 failed`). 스레드 생존 가드를 빼면 `a loop thread killed by an Error is not reported as a running daemon` 이 실패. 각각 원복 후 재실행 green.
- **완화가 진짜 고장을 가리지 않음(가장 중요한 회귀)**: 사고 재현 테스트(임계 10배 age → DOWN), 경계값(정확히 임계 = UP, +1 = DOWN), 캐치업 면역 테스트(며칠 뒤처진 게임 클럭 + 방금 성공한 벽시계 틱 = UP) 모두 본문 변경 없이 green.

## 이 리뷰가 만족하지 못한 요건

- `CLAUDE.md` mandatory legacy-gap chain 의 `loop-engineering` baseline/hypothesis/grader 산출물(`docs/loops/`)이 없다. 사고 자체는 PR #365 에서 관측→근본원인→수정→게이트 경로를 직접 탔고, 이 티켓은 그 사후 관측 보강이다. 증거는 이 문서와 위 반증 실증이 전부다.
- **프로덕션 검증 없음.** 새 인디케이터가 실제 프로덕션에서 어떤 status 를 내는지는 배포 전까지 UNKNOWN 이다. 테스트는 단위 수준이고, 위에 적었듯 프로덕션에는 이 엔드포인트를 긁는 주체가 아직 없다.
- 커밋 트레일러가 `Claude Opus 5 (1M context)` 로 `CLAUDE.md` 규정 문자열(`Claude Opus 4.8 (1M context)`)과 다르다. 실제 작성 모델을 적었다.
