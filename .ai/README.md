# .ai/ — 동적 상태 파일

이 디렉터리는 **장기 규칙이 아니라 현재 상태**를 담는다. 영구 규칙은 `CLAUDE.md`(정본)·`AGENTS.md`(요약), 절차는 `docs/agent/`에 있다.

| 파일 | 역할 | 갱신 주체 |
|---|---|---|
| `task.md` | 현재 작업 계약(목표·범위·수용 기준·승인 지점) | **사람이 작성/승인**. 에이전트는 읽기만. |
| `current-state.md` | 진행 중 작업의 압축 상태 | 에이전트가 체크포인트마다 갱신 |
| `decisions.md` | 인간이 승인한 결정(ADR-LITE) | 사람 승인 후에만 `approved` |
| `known-issues.md` | 확인된 미해결 이슈·백로그 포인터 | 에이전트/사람 |
| `ownership.md` | 병렬 에이전트 파일 소유권 등록부 | 작업 시작/종료 시 각 에이전트 |
| `handoff.md` | 세션/에이전트 간 인수인계 | 작업 종료·전환 시 에이전트 |

## 규칙

- 우선순위: 사용자 직접 지시 > `task.md` > `decisions.md` > `CLAUDE.md`/`AGENTS.md` > 실행 가능한 설정·테스트 > `docs/agent/` > 코드 패턴 > `current-state.md`/`handoff.md` > 에이전트 추론.
- `current-state.md`·`handoff.md`는 **자동으로 최신이 아니다**. `Updated at`이 오래됐으면 `git log`·`docs/loops/*/LEDGER.md`로 재검증 후 신뢰한다.
- 이 디렉터리에 장황한 로그를 쌓지 않는다. 상세 이력의 정본은 `docs/loops/*/LEDGER.md`와 `docs/superpowers/SESSION_HANDOFF.md`다.
- 에이전트가 `decisions.md`에 임의로 `approved`를 만들면 안 된다(`proposed`까지만).
