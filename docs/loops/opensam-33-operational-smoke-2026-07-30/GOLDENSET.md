# OPENSAM-33 B2 운영 스모크 고정 채점표

## 범위

- Jira D4-14~17.
- production 값 변경이 아닌 isolated local Compose QA grader.
- 기능 패러티 골든이 아니라 운영 연결성·가시성 grader다.

## Frozen graders

| 항목 | 관측 | PASS |
|---|---|---|
| D4-14 cadence | fresh isolated world의 `turnterm=1` 설정과 실제 daemon tick | production default 불변, 연속 `turnCompleted` 간격이 60초 cadence 허용 오차 안 |
| D4-15 path | HTTP intake request ID → durable terminal → authoritative read → Redis event → browser SSE | 한 artifact chain에 모두 존재 |
| D4-16 no-op | terminal success 전후 기대 상태 | `ok=true`인데 authoritative delta가 없으면 FAIL |
| D4-16 false-deny | 유효한 1분 QA 설정 | 유효 요청이 admission/terminal에서 거절되면 FAIL |
| D4-16 stale UI | `turnCompleted` 이후 active game document | browser document refresh와 refresh 뒤 authoritative read가 없으면 FAIL |
| D4-17 seed retry | mid-import 예외 뒤 관계 테이블 + 재시도 | 부분 행 0, retry seed 성공, Testcontainers skip 0 |

## Fixed effect fixture

- 새 neutral 장수의 personality는 exp/ded identity인 `che_유지`로 고정한다.
- 공개 catalog의 `che_요양`만 예약한다. 임의 첫 command/`휴식` fallback은
  허용하지 않는다.
- authoritative effect: experience `0 → 10`, dedication `0 → 7`.
  injury `0 → 0`은 mutation predicate로 세지 않는다.
- 같은 request ID의 `reservationAccepted`와 후속 `executionApplied`를
  구분한다. 새 장수 turn-time jitter와 strict due 비교 때문에 execution은
  최대 두 개 60초 boundary를 기다릴 수 있다.

## Safety

- 기존 Compose project를 재사용하거나 덮어쓰지 않는다.
- 필요한 admin/JWT 값은 local run에서 임시 생성하고 출력·보존하지 않는다.
- `.env*`를 읽지 않는다.
- production/EC2/deploy/Jira/git/legacy/golden mutation은 금지한다.
