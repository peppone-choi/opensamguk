# Daemon Health Alert — Discord 웹훅 어댑터 적대적 리뷰 (2026-08-17)

Scope: tools/ops/daemon_health_alert.sh 및 tools/ops/daemon_health_alert_contract_test.sh — 자체 형식 JSON을 Discord Execute Webhook 스키마(embed)로 감싸고, 전달 실패를 가시화하되 판정 종료 코드와 분리한 변경.

## 전제 확인 (Discord 스키마 근거)

- Discord Execute Webhook 문서(https://docs.discord.com/developers/resources/webhook):
  본문은 `content` / `embeds` / `components` / `file` / `poll` 중 **최소 하나**를 포함해야 하며,
  없으면 요청이 실패한다. embed는 `title`·`description`·`color`·`fields`·`timestamp`·`footer`를 가진다.
- JSON 에러 코드(https://docs.discord.com/developers/topics/opcodes-and-status-codes):
  `50006` "Cannot send an empty message", `50035` "Invalid form body" — 둘 다 **HTTP 400**.
- 레이트 리밋(https://docs.discord.com/developers/topics/rate-limits): 429 응답에 `Retry-After`
  헤더와 본문 `retry_after`(float)·`global`(bool)이 실린다.

따라서 기존 `{"source":...,"server":...,"state":...}` 자체 형식 본문은 위 5개 키가 하나도 없어
Discord가 400으로 거절한다는 진단은 **문서 근거로 확인됨**.

**단, 파생 진단 하나는 정정한다.** "그래서 정상 데몬이 빨개진다"는 성립하지 않았다.
머지된 `#413` 시점 코드에서 `dispatch_alert`는 이상 경로에서만 호출되고 그 경로는 모두
전달 결과와 무관하게 `exit 1`이며(`daemon_health_alert.sh:171,180,207`), UP 경로는 전달을
아예 시도하지 않고 `exit 0`한다(`:196-201`). 즉 400의 실제 피해는 **오탐 빨간불이 아니라
"모든 실제 경보가 한 번도 도달하지 않는 것"** + 실패 사실이 stderr 한 줄로만 남는 것이었다.
수정 방향은 동일하므로 작업은 그대로 진행했다.

## 변경

- `dispatch_alert`가 Discord 메시지 본문을 만든다. 진단 dict는 **그대로 유지**되고, 그 dict
  하나에서 embed `fields`가 파생된다(키/값 손실 없음, 중복 정의 없음).
  - `title`: `[{state}] {server} / {reason}` — 상태와 사유가 알림 목록에서 바로 읽힌다.
  - `color`: DOWN `0xE74C3C`(빨강) / OUT_OF_SERVICE `0xF1C40F`(노랑) / 그 외 `0x2ECC71`(초록).
  - `fields`: `source, server, state, reason, recoveryMode, tickSeconds, allowedSeconds, lastTurnTime`.
    `lastTurnTime`만 `inline: false`(길이 때문).
- 429·일시적 5xx: `curl --retry 3 --retry-delay 2 --retry-max-time 40`. curl은 408/429/5xx를
  transient로 보고 `Retry-After`를 존중한다. 5분 주기 단일 서버 경보이므로 자체 백오프 루프나
  큐잉은 만들지 않았다(과설계 회피).
- 전달 실패 가시화: `warn_dispatch_failed`가 `::warning` + `$GITHUB_STEP_SUMMARY` 한 줄을 남긴다.
  기존 stderr 라인은 유지.

경보 조건(`recovery_gated` / `turn_stalled` / `health_down` / `paused` / `*_unreadable`),
임계값 `STALE_TICK_MULTIPLIER=3`, 종료 코드 규약은 손대지 않았다.

## 동작표 (데몬 상태 × 웹훅 설정 × 전달 결과)

| # | 데몬 | 웹훅 | 전달 | exit | 전달 시도 | 가시화 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 정상(UP) | 설정됨 | — | 0 | 없음 | 없음 |
| 2 | 정상(UP) | 설정됨 | 엔드포인트 고장 | **0** | **없음** | 없음 |
| 3 | 정상(UP) | 미설정 | — | 0 | 없음 | `::warning` + summary |
| 4 | 이상 | 설정됨 | 성공 | 1 | Discord embed 1회 | `daemon alert dispatched` |
| 5 | 이상 | 설정됨 | 실패 | **1** | 1회(실패) | `::warning` + summary + stderr |
| 6 | 이상 | 미설정 | — | 1 | 없음 | `::warning` + summary + `daemon alert undelivered` |
| 7 | 잘못된 입력 | 무관 | — | 2 | 없음 | stderr |

행 2가 이번 작업의 핵심 성질이다 — 정상 데몬은 전달 경로를 건드리지도 않으므로 통보 채널
고장이 초록불을 빨갛게 만들 수 없다. 행 5는 fail-closed가 전달과 무관함을 고정한다.

## 계약 테스트 확장

- `assert_discord_schema`: 페이로드에 `embeds` 배열(1..10)과 비어있지 않은 `fields`가 있고,
  `title`이 `[STATE] server / reason` 꼴이며 길이 256 이하, `color`가 상태별 상수인지 단언.
  Discord가 요구하는 "content 또는 embeds 존재"를 python3으로 파싱해 검증한다.
- `assert_diagnostic_field`: 8개 진단 키가 embed field로 살아있는지 키별 단언(기존
  `"state":"DOWN"` 류 단언을 embed 형태로 **이관**했고, `source` 단언은 신규 추가).
- 행 2 신규 케이스 `dispatch_fail_healthy`: curl 스텁이 실패하도록 두고 정상 데몬을 돌려
  exit 0 + 전달 0회 + `alert dispatch failed` 미출현을 고정.
- 행 5: `::warning title=Daemon alert dispatch failed::` + step summary `alert NOT delivered` 단언 추가.
- `--retry 3` 존재 단언(429/5xx 취급이 조용히 사라지는 것을 막는다).
- 시크릿 유출 방지 확장: `run_alert`(웹훅 설정 경로)에도 `GITHUB_STEP_SUMMARY`를 주입해
  `assert_safe_output`의 `SECRET_SENTINEL`/`WEBHOOK_SENTINEL` 검사가 **Discord 전달 경로의
  step summary까지** 덮게 했다. 이전에는 무웹훅 경로에서만 summary가 검사됐다. curl 오류
  메시지는 여전히 `>/dev/null 2>&1`로 봉쇄한다(curl은 오류에 URL을 포함할 수 있다).

## 검증

- `bash tools/ops/daemon_health_alert_contract_test.sh` → `PASS: daemon health alert workflow and script contracts`.
- 뮤테이션 3종, 모두 FAIL 확인 후 복원 시 PASS:
  1. 어댑터 제거(자체 형식 그대로 전송) → `FAIL: expected output to contain '{"name":"server","value":"spep",'`
  2. `--retry` 제거 → `FAIL: dispatch does not retry Discord 429/5xx responses`
  3. 전달 실패 `::warning` 제거 → `FAIL: expected output to contain '::warning title=Daemon alert dispatch failed::'`
- Discord 엔드포인트는 전 구간 스텁. **실제 Discord로 전송하지 않았다.**
- `bash -n` 통과(계약 테스트 내부). `shellcheck`는 이 호스트에 미설치 → **UNKNOWN**.
- 실제 Discord가 이 본문을 200으로 받는지는 시크릿 미설정 + 무전송 원칙상 **UNKNOWN**
  (문서 스키마 준수까지만 증명됨).
- 실제 GitHub Actions 러너 스케줄 실행 결과는 머지 전이므로 **UNKNOWN**.

## 잔여 리스크

- embed `fields`는 25개, 각 value 1024자 제한이 있다. 현재 8개 고정 필드이고 값이 모두
  검증된 짧은 토큰이라 초과 불가하지만, 진단 키를 늘릴 때 확인이 필요하다.
- `--retry`가 소진된 뒤의 경보는 유실된다. 5분 뒤 다음 스캔이 같은 이상을 다시 보고하므로
  재시도 큐는 만들지 않았다. 수용한다.

Verdict: cleared
