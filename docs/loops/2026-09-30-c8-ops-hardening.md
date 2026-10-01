# C8 공개 턴·운영 감시와 R-01

## 안 된 것

K10-01f 후속과 #1088 main collector의 공개 연결 수정본은 새 head의 Kotlin/전체 CI 검증 대기다. 이전 green은 합격 근거가 아니다. V70은 gateway 자동 Flyway 적용 경로에 포함되어 A03 전까지 draft를 유지한다.

- 경보 수신 측 도착·pep 적용·운영 rehearsal은 미확인이다. 실제 발송 시험·운영 maintenance 조회/해제·디스크 apply·운영 DB migration은 실행하지 않았다.
- 최초 중단 실행은 XML0이었다. 새 로컬 집중 검증30건 및 infra 테스트 컴파일 성공, remote JVM5750건/엔진1372건 모두 skip0이다. 경보 수신·운영 적용 증거는 남았다.
- merge로 즉시 적용되는 워크플로와 V70은 대상 운영/DB 승인 전 draft로 보존한다.

## 변경

- 세계 턴 flush에서만 DB clock_timestamp()를 meta.lastTickExecutedAt에 저장한다. 같은 transaction의 실패는 시각도 롤백한다. 일반 intake는 이 시각을 갱신하지 않는다.
- 공개 읽기는 게임 일정 lastTurnAt/nextTurnAt과 실제 성공 flush 벽시각을 구분한다. RUNNING/CATCHING_UP/WAITING/PAUSED/STALLED/UNKNOWN, serverTime, staleSeconds 및 no-store를 제공한다. 20시간 전 게임 일정 catch-up을 실제 벽시계 정지로 오판하지 않는다.
- 공개 감시는 실제 벽시각으로 정지를 독립 판정한다. 필드 누락을 healthy로 판단하지 않으며 HTTP200 degraded 계약을 유지한다. 기존 상세 데몬/actuator 검사는 보존한다.
- 안전한 웹훅 HTTP 상태/예외 타입 진단, main 배포 2회 연속 실패 경보, 운영 lock 없는 drained 감시, 수동 디스크 plan/apply를 추가했다. 기존 webhook만 재사용하고 자동 maintenance 해제는 없다.
- R-01: 기존 server.catchUpFinished PUBLIC/WORLD/PUBLISHED writer가 V65 공개 CHECK에 거절되는 코드 원인을 확인했다. C0 예약 V70은 빈 refs/facts의 정확한 kind만 허용 목록에 추가한다. V65와 다른 target/publication 제약은 변경하지 않는다.

## 수치 근거

- 3×tick: 기존 TurnDaemonHealthIndicator.STALE_TICK_MULTIPLIER와 #1018 운영 계약.
- 25시간 cap: 2026-09-30 full-scope-requirements §2 J의 정지 회귀 보호. 일반 정지 정책 근거는 기존 3tick이고, 이 cap을 일반 정책의 출처로 확대하지 않는다. K10/C0 검토 CONFIRMED.
- serverTime/no-store·별도 wall clock·CATCHING_UP·PRE_OPEN PAUSED: K10/C0 계약판 K10-01.
- 배포 2회 연속: 연속 실패 감시의 최소 횟수. 이전 13회 실패 근거는 메타 C8 보고서에 기록했다.
- 디스크 보존 시간은 고정하지 않으며 적용 승인 때 사용자가 지정한다.

## 로컬 검증

- `python3 -m unittest tools/ops/test_external_health_contract.py tools/ops/test_external_health_run.py tools/ops/test_production_ops.py tools/ops/test_production_disk_cleanup.py -q`: 37건 성공. 모든 API·Docker·웹훅은 fixture/mock.
- `bash tools/ops/daemon_health_alert_contract_test.sh`: exit0.
- `git diff --check`: 성공. 변경 워크플로 3개 YAML 파싱 성공, actionlint는 미설치.
- 로컬 지정5클래스 XML30건(실패/오류/skip0), infra compileTestKotlin 성공. remote CI run36704359608 headce9c907e5: jvm-core721 suites/5750 tests/skip0, engine239 suites/1372 tests/skip0. CI는 infra 전체 테스트(JDBC rollback·V65/V69 거절→V70 허용/민감 payload 차단)를 포함하며 성공 XML artifact는 업로드하지 않으므로 전수 명령/게이트 로그가 근거다. 골든·해시 게이트·정규화는 변경하지 않았다.

## 위험

새 엔진 적용 후 첫 성공 세계 턴까지 기존 meta에는 wall clock이 없어 STALLED로 보일 수 있다. 시각은 world UPDATE 시점이고 transaction commit 후 공개되므로 긴 flush에서는 완료 시각보다 앞선다. 웹훅 HTTP 진단만으로 전달 완료라 할 수 없으며 수신 측 캡처가 필요하다. 운영 lock 없이 의도적으로 연 maintenance도 drained 경보 대상이므로 확인 후 대상별 승인으로 해제한다.

## K10-01f 후속

PAUSED/STALLED countdown null 및 PAUSED health degraded를 준비했다. main의 실제 engine pause collector 캐시를 단일 요청 시각으로 투영한다. 현재 pause 관측만 정상 판정에 사용하며, 미설정·조회 실패·만료·world 불일치는 UNKNOWN이다. 기본 비활성도 UNKNOWN이며 nextTurnAt=null, paused=null, unknownSince를 제공한다. public read는 HTTP를 호출하지 않고 프로세스 world만 읽는다. 운영 collector 활성은 별도 대상 승인이다. 감시 수신은 >3tick, 최초 UNKNOWN 지속, reset 확인, 미래 시각 거절, PAUSED prior incident/전송 실패 보존 및 다른 사고 비억제를 구현했다. 자세한 계약은 docs/operations/external-health-monitor.md를 따른다. 모든 회귀는 fixture/mock이며 실제 웹훅 전송0이다.

## 2026-10-01 실제 관측 연결 회귀

- 실패 확인 head `cd758b08f` / CI `36817489730`: 무관측 OPEN의 UNKNOWN 기대가 RUNNING으로, health degraded 기대가 up으로 실패했다. XML은 각각 8건·4건, skip0이며 두 회귀만 실패했다.
- 복원본은 실제 pause, 관측 만료, world 불일치, reset 무효화와 공개 JSON의 null/시각 필드를 검증한다. CI의 기존 JVM 보고서 업로드는 성공·실패 모두 보존하며 테스트 실행과 필수 체크는 바꾸지 않는다.
- 수정본 native 합격·운영 적용·수신 측 도착은 아직 확인하지 않았다. 로컬 Gradle은 실행하지 않았다.
