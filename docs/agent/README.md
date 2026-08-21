# Agent Documentation Router

에이전트는 **모든 문서를 읽지 않는다**. 현재 작업에 필요한 것만 이 라우터로 고른다. (Progressive Disclosure — 항상 로딩되는 것은 최소로.)

Codex를 처음 사용하는 사람은 먼저 [`codex-user-manual.md`](codex-user-manual.md)의, Claude Code를 처음 사용하는 사람은 [`claude-user-manual.md`](claude-user-manual.md)의 5분 빠른 시작과 일상 업무 흐름을 따른다.

## Always Read (모든 작업 공통, 이 순서로)

1. `.ai/task.md` — 현재 작업 계약
2. `.ai/decisions.md` — 승인된 결정
3. `project-overview.md` — 저장소가 무엇인지 (이미 알면 생략 가능)
4. 현재 작업과 직접 관련된 문서 (아래 표)

## Read by Task Type

| 작업 유형 | 읽을 문서 |
|---|---|
| 명시적으로 요청된 동결 회귀/역사적 패러티 유지보수 (명령 1개) | `docs/superpowers/WORKING_SYSTEM.md`(정본 절차) + `.claude/HARNESS.md` + `verification.md` — `/parity-close`/`$parity-close`는 신규 제품 작업의 선행 조건이 아닌 opt-in 역사 워크플로 |
| 신규 기능 / v2 구현 | `architecture.md`, `coding-rules.md`, `lifecycle-planning.md`, `lifecycle-testing.md` |
| 버그 수정 / 라이브 갭 | `failure-cases.md`, `verification.md`, `lifecycle-testing.md`, 해당 `docs/loops/*/LEDGER.md` |
| UI/프론트 변경 (`web/*`) | `coding-rules.md` §프론트, `verification.md`(pnpm typecheck/test), `lifecycle-testing.md` — 승인된 디자인 방향과 현재 Next.js 구현이 기준, `hwe/ts/` Vue는 흐름 참고만, 브라우저 재현은 `webapp-testing` 스킬 |
| 코드 리뷰 | `lifecycle-review.md`, `coding-rules.md`, `verification.md` |
| 배포·운영 | `lifecycle-ops.md`, `tool-capabilities.md`, `.claude/HARNESS.md` §6 |
| 문서화 | `project-overview.md`, `architecture.md` |
| 병렬/다중 에이전트 작업 | `collaboration-protocol.md`, `lifecycle-collaboration.md`, `.ai/ownership.md` |
| 긴 작업 재개 / 세션 인수 | `.ai/current-state.md`, `.ai/handoff.md` (+ 오래됐으면 `git log`·LEDGER로 교차 검증) |
| 프롬프트/위임 작성 | `prompt-pack.md` |
| 작업 방식 선택 (사람 vs AI) | `workflow-before-after.md` |
| 컨텍스트가 길어짐 | `context-strategy.md` |
| Jira 티켓 ↔ GitHub 이슈 미러 | `jira-github-mirror.md` |
| 무엇부터 할지 (열린 이슈 우선순위) | `issue-priority-tiers.md` |

## 정본 관계 (중복 금지)

- **제품·회귀 규율·아키텍처 불변식 정본 = 승인된 `.ai/decisions.md` + `/CLAUDE.md`.** 이 디렉터리 문서는 요약·라우팅만 하고 규칙을 재정의하지 않는다.
- **작업 절차 정본 = `docs/superpowers/WORKING_SYSTEM.md`**, 하니스 지도 = `.claude/HARNESS.md`. 역사적 패러티 절차는 명시적 opt-in 범위에서만 사용한다.
- 루프 이력 정본 = `docs/loops/*/LEDGER.md`, 세션 이력 = `docs/superpowers/SESSION_HANDOFF.md`.
- 충돌 발견 시: 조용히 택일하지 말고 `.ai/current-state.md` Open questions에 기록 + 사람에게 보고.

## Do Not Load by Default

- `docs/loops/`의 과거 루프 원장 전체, `docs/superpowers/plans|research|reviews/`의 과거 산출물, `docs/wiki/` 전체 — 현재 작업이 직접 참조할 때만.
- 긴 예시·과거 로그·현재 작업과 무관한 lifecycle 문서.
