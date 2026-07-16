# Agent Documentation Router

에이전트는 **모든 문서를 읽지 않는다**. 현재 작업에 필요한 것만 이 라우터로 고른다. (Progressive Disclosure — 항상 로딩되는 것은 최소로.)

## Always Read (모든 작업 공통, 이 순서로)

1. `.ai/task.md` — 현재 작업 계약
2. `.ai/decisions.md` — 승인된 결정
3. `project-overview.md` — 저장소가 무엇인지 (이미 알면 생략 가능)
4. 현재 작업과 직접 관련된 문서 (아래 표)

## Read by Task Type

| 작업 유형 | 읽을 문서 |
|---|---|
| 패러티 갭 폐쇄 (명령 1개) | `docs/superpowers/WORKING_SYSTEM.md`(정본 절차) + `.claude/HARNESS.md` + `verification.md` — Claude는 `/parity-close` 스킬이 이 전부를 대신함 |
| 신규 기능 / v2 구현 | `architecture.md`, `coding-rules.md`, `lifecycle-planning.md`, `lifecycle-testing.md` |
| 버그 수정 / 라이브 갭 | `failure-cases.md`, `verification.md`, `lifecycle-testing.md`, 해당 `docs/loops/*/LEDGER.md` |
| UI/프론트 변경 (`web/*`) | `coding-rules.md` §프론트, `verification.md`(pnpm typecheck/test), `lifecycle-testing.md` — 프론트 grand truth는 `hwe/ts/` Vue(PHP가 이김), 브라우저 재현은 `webapp-testing` 스킬 |
| 코드 리뷰 | `lifecycle-review.md`, `coding-rules.md`, `verification.md` |
| 배포·운영 | `lifecycle-ops.md`, `tool-capabilities.md`, `.claude/HARNESS.md` §6 |
| 문서화 | `project-overview.md`, `architecture.md` |
| 병렬/다중 에이전트 작업 | `collaboration-protocol.md`, `lifecycle-collaboration.md`, `.ai/ownership.md` |
| 긴 작업 재개 / 세션 인수 | `.ai/current-state.md`, `.ai/handoff.md` (+ 오래됐으면 `git log`·LEDGER로 교차 검증) |
| 프롬프트/위임 작성 | `prompt-pack.md` |
| 작업 방식 선택 (사람 vs AI) | `workflow-before-after.md` |
| 컨텍스트가 길어짐 | `context-strategy.md` |

## 정본 관계 (중복 금지)

- **패러티 규율·아키텍처 불변식 정본 = `/CLAUDE.md`.** 이 디렉터리 문서는 요약·라우팅만 하고 규칙을 재정의하지 않는다.
- **패러티 작업 절차 정본 = `docs/superpowers/WORKING_SYSTEM.md`**, 하니스 지도 = `.claude/HARNESS.md`.
- 루프 이력 정본 = `docs/loops/*/LEDGER.md`, 세션 이력 = `docs/superpowers/SESSION_HANDOFF.md`.
- 충돌 발견 시: 조용히 택일하지 말고 `.ai/current-state.md` Open questions에 기록 + 사람에게 보고.

## Do Not Load by Default

- `docs/loops/`의 과거 루프 원장 전체, `docs/superpowers/plans|research|reviews/`의 과거 산출물, `docs/wiki/` 전체 — 현재 작업이 직접 참조할 때만.
- 긴 예시·과거 로그·현재 작업과 무관한 lifecycle 문서.
