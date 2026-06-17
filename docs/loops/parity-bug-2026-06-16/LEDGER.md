# LEDGER — parity-bug-2026-06-16

오답 노트 + 백로그. 바퀴마다 1줄. 실패도 반드시 기록.
행 형식: `| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정(채택/폐기/승인대기) | 원인 한 줄 |`
(0바퀴 = 베이스라인 채점. 채점자 칸 비거나 "본인"이면 무효.)

## 바퀴 기록

| 바퀴 | 가설 | 점수 전→후 | 채점자 | 판정 | 원인 |
|---|---|---|---|---|---|
| 0 | 어드민 서버 로딩 베이스라인 | 19 tests / 4 failed | `:app:gateway-api:test --tests AdminVersionDeployTest` | 기준 | gateway-api가 deployer 서버 API와 런타임 registry 계약을 따르지 않음 |
| 1 | gateway-api deployer 서버 API와 런타임 registry 사용 | 4 failed -> 0 failed | `:app:gateway-api:test --tests AdminVersionDeployTest` | 채택 | `/servers/create|close|reset` 계약 정렬 + `GET /servers` 런타임 서버 조회 |
| 2 | seed/load 장수 lifecycle meta 보강 | compile red -> 2 tests / 0 failed | `:common:test --tests ScenarioLifecycleMetaTest` | 채택 | PHP reset `killturn`/`deadyear`를 seed와 loader 양쪽에서 보장 |

## 백로그 (가설 단위, 1바퀴=1가설)

- 국가 소실: seed/load된 장수 meta의 `killturn`/`deadyear` 누락 여부와 PHP `TurnExecutionHelper` 사망 판정 비교.

## 빼기 주기 추적

- 3바퀴마다 1회 삭제 바퀴. 현재 더하기 연속: 0.
