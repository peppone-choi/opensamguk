# 운영 VM 밖 공개 헬스 감시

GitHub Actions의 `External Health Monitor`와 `External Health Watchdog`는 GitHub 호스트 `ubuntu-latest`에서 각각 5분마다 실행된다. 기존 `Daemon Health Alert`는 운영 VM의 자체 호스트 러너에서 Docker 내부 데몬·actuator를 계속 자세히 검사한다. 공개 감시는 그 검사를 대신하지 않는다.

## 검사 범위

`External Health Monitor`는 실행당 아래 세 공개 URL을 인증·쿠키 없이 각각 한 번 GET한다.

| 경로 | 정상 계약 | 별도 장애 |
|---|---|---|
| `https://sam.peppone.dev/health` | 200, JSON `status=up`, `nginx=ok` | 원점 무응답·타임아웃·Cloudflare 52x·비정상 응답 |
| `https://sam.peppone.dev/api/health` | 200, JSON `status=UP`, `app=web-gateway` | 게이트웨이 무응답·타임아웃·Cloudflare 52x·비정상 응답 |
| `https://sam.peppone.dev/api/server-basic-info/pep` | 200, `game` 객체와 기본 월드 필드 | 502는 game-api 연결 장애로 별도 표시. 그 밖의 무응답·타임아웃·Cloudflare 52x·비정상 응답도 구분 |

공개 턴 계약은 게임 일정 `lastTurnAt`·`nextTurnAt`과 실제 성공 flush의 벽시각 `lastTickExecutedAt`을 구분한다. `serverTime`은 응답 작성 시각이며 응답은 `Cache-Control: no-store`다. `turnLoop`는 `state`와 마지막 실제 실행 뒤 경과 초 `staleSeconds`를 제공한다. catch-up은 과거 게임 시각을 처리하므로 게임 일정의 나이를 정지 판정에 쓰지 않는다.

| 상태 | 판정 |
|---|---|
| RUNNING | OPEN, 최근 성공 flush 벽시각 있음 |
| CATCHING_UP | 최근 성공 flush 벽시각 있음, catchUp.active=true |
| WAITING | 첫 실행 전이고 nextTurnAt이 아직 미래 |
| PAUSED | 현재 공개 producer에서는 비OPEN/유효하지 않은 tick 설정. 실제 gate 연결 후 신선 true |
| STALLED | 실행 예정 이후 벽시각 누락·비정상·지연 |
| UNKNOWN | 후속 common collector의 실제 gate 관측 누락·만료·미래·식별 불일치. 공개 producer 연결 전 |

OPEN의 마지막 실제 실행이 `min(3 × tick_seconds, 25시간)`을 넘으면 `turn_stalled`다. 주 정지 정책은 기존 3tick이며 25시간은 §2 J의 정지 회귀를 막는 cap으로 구분한다. 20시간 전 게임 턴을 처리하는 catch-up도 최근 벽시각이 있으면 정상이고, 배속과 관계없이 실제 실행이 멈추면 장애다. 필수 상태/응답 시각·벽시각 계약이 누락되면 UNKNOWN/healthy로 처리하지 않는다. game-api `/health`는 HTTP 200을 유지하면서 `world.stale`, `world.turnLoop`, `lastTurnAt`, `lastTickExecutedAt`과 `serverTime`을 제공하고 PAUSED·정지·DB/Redis 비정상이면 `status=degraded`다. 기존 내부 상세 actuator 검사는 계속 유지한다.

마지막 벽시각은 엔진의 세계 턴 flush에만 기록한다. 일반 intake·개인 턴은 갱신하지 않으며 transaction 실패 시 함께 롤백된다. 새 엔진 적용 뒤 첫 성공 턴까지 기존 월드에 필드가 없어 STALLED로 표시될 수 있으므로 전환 시점을 승인 후 확인한다.

두 호스트 감시기는 GitHub Actions API에서 서로의 최근 `schedule` 실행을 본다. 공개 Monitor는 운영 VM의 `Production Ops Monitor` 일정도 같은 방식으로 감시한다. 최근 15분의 취소·분류기 실행 전 실패는 더 새 실행이 있어도 구분하며, 마지막 일정 실행이 15분보다 오래되면 일정 누락·지연으로 표시한다. 15분은 5분 간격 세 번이다. GitHub 일정은 지연·누락될 수 있으므로 실행 시각과 실제 상태를 대조한다. GitHub 자체가 두 일정을 모두 실행하지 못하면 이 상호 감시만으로는 경보를 낼 수 없다.

## 알림과 상태

- 기존 `DAEMON_ALERT_WEBHOOK_URL` secret을 재사용한다. 새 계정·서비스·secret은 없다. 워크플로 로그·요약·artifact에는 URL, 토큰, 공개 응답 본문을 남기지 않는다.
- 장애 분류 집합이 바뀌면 경보, 동일하면 중복 억제, 정상으로 바뀌면 복구 알림을 보낸다. 전송 실패나 secret 누락은 실행 오류에 남기고 다음 일정에서 다시 보낸다. HTTP 실패는 상태 코드, 그 외는 예외 타입만 기록한다. 영구 4xx(429 제외)는 같은 실행에서 반복하지 않으며 URL·본문·예외 메시지는 출력하지 않는다.
- 상태는 각 실행의 `external-health-state-probe` 또는 `external-health-state-watchdog` Actions artifact에 코드와 전송 성공 여부만 저장한다. 보존 기간은 7일이다. artifact를 읽지 못하면 `state_unavailable`로 표시한다.
- 공개 장애를 정상적으로 판정한 감시 실행도 빨간 실행으로 남는다. 상대 감시기는 그 실행의 상태 artifact가 있으면 분류기가 동작한 것으로 보고, 같은 공개 장애를 다시 알리지 않는다. 분류 전에 실패하거나 실행이 취소되어 artifact가 없으면 감시기 장애로 알린다.
- 최초 배포 직후 상대 감시기의 첫 일정 실행 전에는 `peer_not_started`가 발생할 수 있다. 두 감시기의 첫 일정 실행 후 복구 알림을 확인한다.

## 확인과 대응

1. GitHub Actions의 두 호스트 워크플로에서 최근 실행 시각·결론과 로그의 분류 코드, `Daemon Health Alert` 자체 호스트 검사 실행을 함께 본다.
2. `origin_cloudflare_52x`·`origin_unreachable`은 Cloudflare 원점과 VM 도달성을 확인한다. `gateway_*`는 게이트웨이 경로, `game_api_down`은 서버 `pep`의 game-api 경로를 확인한다. 두 코드가 같이 뜨면 게이트웨이를 먼저 확인한다. `turn_stalled`는 내부 데몬 status와 마지막 성공 턴, recovery gate를 확인한다.
3. `peer_cancelled`·`peer_schedule_missing`·`peer_not_started`는 GitHub Actions 일정과 runner 상태를 본다. 감시 실패와 서비스 장애를 같은 원인으로 취급하지 않는다.
4. 복구 뒤 첫 정상 실행의 복구 알림과 두 워크플로의 최근 정상 일정을 확인한다. 경보 채널 실패 시 워크플로 경고·실패와 `delivered=false` 상태를 확인한다.

배포·VM 중지·운영 DB 변경은 이 감시 확인 절차에 포함되지 않는다. `workflow_dispatch`에서 `exercise_alert_recovery=true`를 한 번 선택하면 공개 경로를 한 번씩 검사한 뒤, 정상 상태일 때 기존 웹훅으로 **시험 경보와 시험 복구를 각각 한 건** 보낸다. 제목에 `[전송 시험]`이 붙고 실제 장애가 아님을 표시한다. 실제 공개 장애가 있으면 시험 전송을 건너뛰고 장애 경로만 실행한다. 이 운영 호출은 별도 승인 후 한 번만 실행한다.


## 배포·maintenance·디스크

- 공개 Monitor는 main push 배포의 최근 완료 기록을 확인한다. 실패·시간 초과·시작 실패가 2회 연속이면 `deploy_consecutive_failures` 경보를 내며 성공하면 복구한다. 취소나 다른 브랜치 실행은 성공으로 보지 않는다. 배포 완료 workflow_run에서도 검사한다.
- `Production Ops Monitor`는 운영 VM에서 5분마다 공유 `/tmp/opensamguk-production.lock`을 비차단으로 잡고 deployer의 GET `/maintenance`만 읽는다. 배포가 잠금을 보유하면 점검을 보류한다. 잠금 없이 drained면 `maintenance_orphaned`, 조회 실패면 `maintenance_unavailable`이며 자동 해제하지 않는다. 출력은 capability/state만 허용하고 토큰·lease·응답 본문은 보존하지 않는다.
- `Production Disk Cleanup`은 수동 실행이다. `minimum_age_hours`는 대상 승인 때 지정한다. 기본 plan은 `df`와 Docker 용량만 읽는다. apply는 별도 운영 승인과 `CLEAN BUILD CACHE AND DANGLING IMAGES` 확인이 필요하다. 동일 운영 잠금 아래 해당 시간보다 오래된 dangling image와 사용하지 않는 build cache만 정리한다. volume·container·태그 있는 rollback image에는 prune하지 않는다.
- 새 워크플로 활성화·실제 경보 수신 시험·디스크 apply는 각각 대상 승인 후 실행한다. 경보 전송 성공 응답만으로 완료라 하지 않고 수신 측 캡처를 증거로 남긴다.

## 동결 관측 수신 후속 (연결 전)

RUNNING/CATCHING_UP/WAITING만 nextTurnAt을 노출한다. PAUSED/STALLED/UNKNOWN은 null이고 year/month/turnPhase 게임 달력은 보존한다. PAUSED는 degraded이며 사유를 ADMIN으로 만들지 않는다. 현재 #1073 producer에는 실제 gate 연결이 없고, 별도 #1088 기반이 main에 들어온 뒤 ServerBasicInfo/HealthCheck/TurnLoopHealth가 동일 Result/observedAt을 소비해야 한다. 운영 opt-in은 별도 대상 승인이다.

수신기는 UNKNOWN의 paused=null/unknownSince/resetCompletedAt을 검사한다. 첫 연속 관측과 artifact의 시작 시각 중 이전 값을 유지하며, 같은 세계의 새로운 확인된 reset 완료 시각만 구간을 다시 시작한다. 미래 API·시작·reset 시각은 실패다. UNKNOWN은 즉시 HTTP200/degraded 및 pause_observation_unavailable이며 빨간 감시 실행으로 남는다. 알림은 기존 정책인 엄격한 >3tick에서 같은 incident 경로로 승격하고 UNKNOWN에는25시간 cap을 적용하지 않는다.

신선한 실제 PAUSED는 새 paused 전송이나 오래된 tick만으로 STALLED 전송을 만들지 않는다. outstanding incident와 전송 실패 재시도는 보존하고 healthy/recovered로 확인하지 않는다. peer·배포·maintenance 등 다른 사고는 계속 판정한다. 관측 만료/실패는 UNKNOWN 정책을 적용한다. 외부 감시 artifact schema2는 codes/delivered에 notificationCodes 및 정규화한 unknown 시각만 추가한다. 운영 감시기의 기존 schema1은 유지한다. 실제 전송 시험은 대상 승인 후이며 이번 fixture 검증에는 발송이 없다.
