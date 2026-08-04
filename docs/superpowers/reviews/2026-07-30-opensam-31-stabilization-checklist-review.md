# OPENSAM-31 v1 안정화 체크리스트 독립 리뷰

Scope: docs/superpowers/plans/, docs/superpowers/reviews/, .ai/
Verdict: cleared

- Date: 2026-07-30
- Scope:
  - `docs/superpowers/plans/2026-07-13-v1-stabilization-and-v2-open-plan.md`
    의 `v1 안정화 실행 체크리스트 (D4-01~07)`
  - OPENSAM-31 관련 `.ai/task.md`, `.ai/current-state.md`,
    `.ai/ownership.md` 추가 구간
- Reviewer: independent `fable-implementer` agent (read-only)
- Implementer: root Codex

## Initial verdict

`fix-required`

### MAJOR findings

1. D4-03은 `CommandControllerIT`가 `requestId`와 Redis stream payload의
   상관관계를 검증한다고 과장했다. 실제 단언은 non-empty `requestId`와
   stream size 1이다.
2. D4-05는 `WorldScopedReadRepositoryIT`가 동일 local ID의 두 world 격리를
   검증한다고 과장했다. fixture의 두 world ID cohort는 서로 다른 entity ID를
   사용한다.
3. D4-06은 `RealtimeRelayIT`가 연결된 `SseEmitter`의 출력을 관측한다고
   과장했다. 테스트는 Redis listener가 받은 원문을 decode하고
   `fanOut`을 호출하지만 browser-facing emitter 출력을 수집하지 않는다.

### Additional scope correction

- D4-07의 `tools/smoke.sh`는 nginx의 모든 경계를 검증하지 않고
  gateway-api 프록시 health 한 경계를 검증한다.
- D4-04의 한 트랜잭션 표현은 `JdbcFlushExecutor.flush`의
  `TransactionTemplate.execute` wrapper와 rollback IT 근거가 있어
  overclaim으로 판정하지 않았다.

## Remediation

- D4-03을 non-empty `requestId` + stream size 1의 실제 단언으로 제한하고
  payload correlation 미검증을 명시했다.
- D4-05에서 동일 local-ID 주장을 제거하고 해당 격리는 별도 2-world 근거
  없이는 완료로 승격하지 않도록 했다.
- D4-06을 `SSE relay ingress`로 좁히고 browser-facing `SseEmitter` 전달은
  `채점대기`로 기록했다.
- D4-07을 nginx gateway-api proxy health 한 경계로 좁혔다.
- `.ai/task.md`의 진행 상태를 Jira 전이와 구분해 local execution으로
  명시했다. Jira는 `할 일`이며 외부 상태 변경은 수행하지 않았다.

## Verification observed by reviewer

- 각 테스트/스크립트의 현재 source assertion과 문서 PASS 문구를 대조했다.
- 수정 후 D4-03/05/06/07 문구가 실제 assertion/script 범위와 일치함을
  재확인했다.
- `git diff --check` clean을 관측했다.

## Completion-gate evidence

- `scripts/agent/verify-changes.sh --run` 1회 실행.
- fresh backend XML: 552 suites / 4,758 tests / failures 0 / errors 0 /
  skipped 1. 스크립트가 임시 Gradle 로그를 삭제해 literal
  `BUILD SUCCESSFUL` line은 별도 보존되지 않았다.
- `web/game`: typecheck PASS, 46 files / 227 tests PASS.
- `scripts/agent/test-codex-agent-os.sh`: PASS.
- `git diff --check`: PASS.
- strict checker: FAIL (2 pre-existing baselines)
  - user-owned `.codex/config.toml` personal model pin
  - historical 2026-07-27 review의 uppercase `Verdict: CLEARED`가 현재
    lowercase anchored-verdict 규칙과 불일치
- 반복된 Fablize generic tool-failure 표시는 exit 0 명령에도 발생하는
  `.ai/known-issues.md`의 기존 baseline과 동일하며 직접 종료코드와
  결과 아티팩트로 분리 판정했다.

## Final verdict

`cleared`

Remaining note: D4-06 browser-facing SSE와 이 문서에 열거한 7개 런타임
명령의 fresh 실행은 이번 docs-only 티켓에서 수행하지 않았으므로
실행 증거로 승격하지 않는다.
