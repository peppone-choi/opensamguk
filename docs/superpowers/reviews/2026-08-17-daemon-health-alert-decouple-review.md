# Daemon Health Alert — 감지/통보 분리 적대적 리뷰 (2026-08-17)

Scope: tools/ops/daemon_health_alert.sh 및 tools/ops/daemon_health_alert_contract_test.sh — 웹훅 미설정 시 헬스 체크가 실행되기 전에 exit 2로 죽던 결함을 감지(항상 실행)와 통보(웹훅 있을 때만)로 분리한 변경.

## 문제

`DAEMON_ALERT_WEBHOOK_URL` 시크릿이 비어 있어 스크립트가 상태 조회 이전에
`invalid_input 'alert webhook is not configured'` → exit 2로 종료했다. 결과적으로
Daemon Health Alert 워크플로는 5분마다 실패했고(run `31958491531` 등), 그 빨간불은
"데몬이 아프다"가 아니라 "확인조차 못 했다"였다 — 즉 프로덕션 데몬 모니터링이 사실상 없었다.

## 변경

- 선행 웹훅 게이트 제거. 상태/헬스 조회와 상태 판정은 웹훅 설정 여부와 무관하게 항상 수행된다.
- `dispatch_alert`는 웹훅이 없으면 전달을 건너뛰되 `::warning` 애노테이션 + `$GITHUB_STEP_SUMMARY`
  한 줄 + `daemon alert undelivered ...` stdout 라인을 남긴다. 판정 종료 코드는 그대로다
  (모든 이상 경로는 `dispatch_alert` 뒤 무조건 `exit 1`).
- 정상(UP) 경로에서 웹훅이 없을 때도 동일한 가시적 경고를 1회 남긴다 (전달 채널이 죽어 있다는 사실이 묻히지 않도록).

경보 조건(`recovery_gated` / `turn_stalled` / `health_down` / `paused` / `*_unreadable`)과 임계값
(`STALE_TICK_MULTIPLIER=3`)은 손대지 않았다. 느슨해진 판정은 없다.

## 2×2 동작

| | 웹훅 설정 | 웹훅 미설정 |
| --- | --- | --- |
| 데몬 정상 | exit 0, 전달 없음, 경고 없음 | exit 0, 전달 없음, `::warning` + step summary |
| 데몬 이상 | exit 1, 웹훅 전달 1회 | exit 1, 전달 없음, `::warning` + step summary + `daemon alert undelivered` |

## 적대적 리뷰 결과 (독립 code-reviewer 에이전트, 8건)

반영:
- HIGH `contract_test`: 무웹훅 이상 경로가 `recovery_gated` 하나만 잠겨 있어 선택적 swallow 뮤턴트가
  통과했다 → `recovery_gated / stalled / paused / health_unreadable / status_unreadable` 5개 전부 루프로 잠갔고
  뮤테이션으로 FAIL을 확인했다.
- MEDIUM `contract_test`: 무웹훅 하네스가 주변 환경의 `DAEMON_ALERT_WEBHOOK_URL`을 상속 → `env -u`로 하드닝.
- MEDIUM `contract_test`: 새 싱크(undelivered stdout, step summary)에 누출 검사 부재 → `assert_safe_output`에
  step summary 검사 추가 + 신규 케이스에서 호출.
- MEDIUM `daemon_health_alert.sh`: 애노테이션 중복(선행 + dispatch) → 선행 경고를 UP 경로로 옮겨 실행당 1회로 축소.
- LOW: 정상 + 웹훅 설정 경로에 `::warning`이 새지 않는지 negative assertion 추가.

미반영(근거 명시):
- HIGH "무웹훅 + 정상 = 초록불이라 오설정이 영원히 안 보인다": 의도된 설계 결정이다. 5분마다 빨간 워크플로는
  늑대소년이 되어 실제 데몬 이상까지 무시되게 만든다(이번 결함의 본질). 대신 초록 런에도 `::warning` +
  job summary가 남는다. **잔여 리스크: 아무도 초록 런을 열어보지 않으면 통보 채널이 죽은 채 유지될 수 있다.**
  시크릿 설정은 사람의 일이며, 이 잔여 리스크는 수용한다.
- LOW 웹훅 값 형식 검증(공백/비URL): 기존 게이트에도 없던 사전 결함. 값 자체를 다루지 않는다는 제약과
  범위 밖이라 이번 변경에서 손대지 않는다.

## 검증

- `bash tools/ops/daemon_health_alert_contract_test.sh` → `PASS`.
- 뮤테이션: (1) 무웹훅 이상 경로 선택적 swallow → `FAIL: daemon incident must still fail closed ... stalled`,
  (2) UP 경로 경고 제거 → `FAIL: expected output to contain '::warning ...'`. 복원 후 PASS.
- 2×2 수동 재현(도커/curl 스텁): 위 표대로 exit 0/0/1/1, 전달 0/0/1/0회.
- `bash -n` 통과. `shellcheck`는 이 호스트에 미설치 → **UNKNOWN**.
- 실제 GitHub Actions 러너에서의 스케줄 실행 결과는 머지 전이므로 **UNKNOWN**.

Verdict: cleared
