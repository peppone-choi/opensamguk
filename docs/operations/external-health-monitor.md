# 운영 VM 밖 공개 헬스 감시

GitHub Actions의 `External Health Monitor`와 `External Health Watchdog`는 GitHub 호스트 `ubuntu-latest`에서 각각 5분마다 실행된다. 기존 `Daemon Health Alert`는 운영 VM의 자체 호스트 러너에서 Docker 내부 데몬·actuator를 계속 자세히 검사한다. 공개 감시는 그 검사를 대신하지 않는다.

## 검사 범위

`External Health Monitor`는 실행당 아래 세 공개 URL을 인증·쿠키 없이 각각 한 번 GET한다.

| 경로 | 정상 계약 | 별도 장애 |
|---|---|---|
| `https://sam.peppone.dev/health` | 200, JSON `status=up`, `nginx=ok` | 원점 무응답·타임아웃·Cloudflare 52x·비정상 응답 |
| `https://sam.peppone.dev/api/health` | 200, JSON `status=UP`, `app=web-gateway` | 게이트웨이 무응답·타임아웃·Cloudflare 52x·비정상 응답 |
| `https://sam.peppone.dev/api/server-basic-info/pep` | 200, `game` 객체와 기본 월드 필드 | 502는 game-api 연결 장애로 별도 표시. 그 밖의 무응답·타임아웃·Cloudflare 52x·비정상 응답도 구분 |

공개 `game.lastTurnTime`이 존재하고 `status=OPEN`이며 `turnTerm`이 양수일 때만, 마지막 턴 뒤 `3 × turnTerm` 분을 넘기면 턴 정지로 분류한다. 이 배포의 공개 응답에는 아직 턴 시각이 없으므로 이 신호는 **UNKNOWN**이다. `year`·`month`·`turnPhase`가 같다는 이유만으로 턴 정지라고 단정하지 않는다. 턴 정지 판단에는 VM 내부 상세 검사 결과를 함께 본다.

두 호스트 감시기는 GitHub Actions API에서 서로의 최근 `schedule` 실행을 본다. 최근 15분의 취소·분류기 실행 전 실패는 더 새 실행이 있어도 구분하며, 마지막 일정 실행이 15분보다 오래되면 일정 누락·지연으로 표시한다. 15분은 5분 간격 세 번이다. GitHub 일정은 지연·누락될 수 있으므로 실행 시각과 실제 상태를 대조한다. GitHub 자체가 두 일정을 모두 실행하지 못하면 이 상호 감시만으로는 경보를 낼 수 없다.

## 알림과 상태

- 기존 `DAEMON_ALERT_WEBHOOK_URL` secret을 재사용한다. 새 계정·서비스·secret은 없다. 워크플로 로그·요약·artifact에는 URL, 토큰, 공개 응답 본문을 남기지 않는다.
- 장애 분류 집합이 바뀌면 경보, 동일하면 중복 억제, 정상으로 바뀌면 복구 알림을 보낸다. 전송 실패나 secret 누락은 실행 오류에 남기고 다음 일정에서 다시 보낸다.
- 상태는 각 실행의 `external-health-state-probe` 또는 `external-health-state-watchdog` Actions artifact에 코드와 전송 성공 여부만 저장한다. 보존 기간은 7일이다. artifact를 읽지 못하면 `state_unavailable`로 표시한다.
- 공개 장애를 정상적으로 판정한 감시 실행도 빨간 실행으로 남는다. 상대 감시기는 그 실행의 상태 artifact가 있으면 분류기가 동작한 것으로 보고, 같은 공개 장애를 다시 알리지 않는다. 분류 전에 실패하거나 실행이 취소되어 artifact가 없으면 감시기 장애로 알린다.
- 최초 배포 직후 상대 감시기의 첫 일정 실행 전에는 `peer_not_started`가 발생할 수 있다. 두 감시기의 첫 일정 실행 후 복구 알림을 확인한다.

## 확인과 대응

1. GitHub Actions의 두 호스트 워크플로에서 최근 실행 시각·결론과 로그의 분류 코드, `Daemon Health Alert` 자체 호스트 검사 실행을 함께 본다.
2. `origin_cloudflare_52x`·`origin_unreachable`은 Cloudflare 원점과 VM 도달성을 확인한다. `gateway_*`는 게이트웨이 경로, `game_api_down`은 서버 `pep`의 game-api 경로를 확인한다. `turn_stalled`는 내부 데몬 status와 마지막 성공 턴, recovery gate를 확인한다.
3. `peer_cancelled`·`peer_schedule_missing`·`peer_not_started`는 GitHub Actions 일정과 runner 상태를 본다. 감시 실패와 서비스 장애를 같은 원인으로 취급하지 않는다.
4. 복구 뒤 첫 정상 실행의 복구 알림과 두 워크플로의 최근 정상 일정을 확인한다. 경보 채널 실패 시 워크플로 경고·실패와 `delivered=false` 상태를 확인한다.

배포·VM 중지·운영 DB 변경은 이 감시 확인 절차에 포함되지 않는다. `workflow_dispatch`에서 `exercise_alert_recovery=true`를 한 번 선택하면 공개 경로를 한 번씩 검사한 뒤, 정상 상태일 때 기존 웹훅으로 **시험 경보와 시험 복구를 각각 한 건** 보낸다. 제목에 `[전송 시험]`이 붙고 실제 장애가 아님을 표시한다. 실제 공개 장애가 있으면 시험 전송을 건너뛰고 장애 경로만 실행한다. 이 운영 호출은 별도 승인 후 한 번만 실행한다.
