# C8 실제 동결 관측 후속 — 로컬 초안

## 안 된 것

이 변경은 구현 초안이다. Kotlin 실행 검증과 collector/cache 구현은 준비했으며 API/C10/감시 연결과 reset 완료 hook은 하지 않았다. C0는 서버 판정 방향과 로컬 검증 profile을 수용했으며 K0/K3/K10 UNKNOWN enum 수신과 운영 설정 승인은 대기다. #1073에 합치지 않았다.

## 구현 준비

- 엔진 status에 gate 읽기 직전의 source serverTime을 additive로 제공한다. 기존 paused/상태/제어 의미를 바꾸지 않는다.
- common TurnDaemonObservation/TurnDaemonProjection은 OPEN 세계에서 공통 API serverTime으로 현재 pause true/false/unknown과 tick 벽시각을 판정하는 순수 함수다. freshness budget은 호출자 확정값이며 운영 기본값을 정하지 않았다. 테스트의 30초는 fixture 입력이다.
- 관리자 pause/resume 직접 gate와 DurableGameLock의 DB plock/일시 world action을 구분한다. 사유가 기록되지 않은 실제 true는 UNKNOWN 이유다.
- 관측 없음/서버 불일치/미래/수집 시각 역전/만료는 UNKNOWN이다. 이전 true를 계속 유지하지 않는다. 정상=false 관측에서도 catchUp이 오래된 tick을 가리지 않는다.
- 동일 관측을 C10/session과 game-api public 읽기에서 공유하도록 후속 연결해야 한다. 수집기는 승인된 내부 origin GET만 읽고 공개 요청은 cache projection을 소비하는 안이다. 새로운 secret/서비스/DB migration/운영 쓰기는 없다.

## 준비한 검증

순수 회귀 8개: 현재 pause+오래된 marker, catchUp 중단, 최초 미래 턴/누락 marker, 만료 true, 관측 누락+최근 tick, 서버/미래/역전, freshness 경계, 26시간/미래 marker. 엔진 기존 status 테스트에 source time 직렬화 관측도 추가했다. 실행 전에는 통과라고 주장하지 않는다.

## Collector 설정과 연결 대기

collector는 game-api 프로세스의 명시 opt-in(turn-daemon-observation.enabled=true)일 때만 bean이 만들어진다. origin/server-id/world-id/tick-seconds/poll-millis/deadline-millis/max-age-millis를 전부 지정해야 한다. world-id는 GameApiProcessWorld와 같아야 한다. URI에는 credential/query/fragment/path를 허용하지 않으며 HTTP redirect와 profile token 재사용도 없다. 이 코드는 설정을 운영에 추가하거나 collector를 켜지 않았다.

C0가 지정한 로컬 검증 입력은 tickSeconds300/poll5000ms/deadline2000ms/maxAge15000ms다. 운영 기본값이 아니며 범용 입력은 0<deadline<poll, maxAge>=poll+deadline, maxAge<3tick을 만족해야 한다. 소스 tick이 빠르게 변경되면 HTTP adapter도 예산을 다시 검사하고 UNKNOWN으로 남겨 별도 설정을 요구한다.

엔진 clockSnapshot은 실제 로드된 state.id/state.serverId를 source identity로 제공한다. 세계 미로드/서버 식별 없음은 false가 아니다. cache는 하나의 scheduled executor로 HTTP를 수집하고 공개 읽기는 snapshot을 읽은 뒤 공통 serverTime/freshness를 적용한다. 실패하면 오래된 true도 폐기한다.

reset 완료 경로는 invalidateAfterReset(completedAt)을 호출해야 한다. cache와 진행 중 HTTP ticket을 함께 폐기하고 완료 전 source 관측을 받아들이지 않는다. C2 reset 완료·엔진 새 세계 로드의 확인과 이 hook 연결은 아직 필요하다. 같은 worldId를 재사용하는 reset에서 이 연결 없이 캐시 안전 완료를 주장하지 않는다. 운영 내부 ID나 오류 원문은 공개 result에 담지 않는다.

새 회귀: cache 공개 읽기 무HTTP, read 실패 시 true 폐기, world/server 불일치/만료/미래, reset 중 HTTP/완료 전 관측 폐기, typed internal source 및 token 미사용/legacy·잘못된 bool·빠른tick·원시 오류 거절. 실행하지 않은 Kotlin tests는 통과로 계산하지 않는다.

## CI 증거

전체 test/skip gate 명령은 유지하고 common/API 지정3 XML 및 engine StatusController XML을 성공/실패 시7일 보존한다. 파일 수와 실제 testcase 결과를 확인하기 전 통과로 계산하지 않는다.

- 추가 remote 회귀는 격리 loopback HTTP의 6초 응답을 설정 deadline2초로 끊는지 확인한다. 운영 origin/URL을 호출하지 않는다. 신규 common/API 회귀는16개이며 실행 증거 전 합격으로 세지 않는다.
