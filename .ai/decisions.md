# Decisions (ADR-LITE)

인간이 승인한 결정만 `approved`가 된다. 에이전트는 `proposed`까지만 기록할 수 있다.

## ADR-LITE-001 기존 CLAUDE.md/AGENTS.md 보존

- Date: 2026-07-16
- Status: approved
- Decision: `CLAUDE.md`(패러티 정본)·`AGENTS.md`(요약)는 본문을 보존하고, 상단에 Agent OS 부트스트랩 섹션(읽기 순서 + `.ai/` + `docs/agent/` 라우터 링크)만 추가한다.
- Context: Agent OS 프롬프트는 "짧은 부트스트랩 문서"를 요구했으나 기존 문서는 load-bearing 정본이라 재구성 리스크가 큼.
- Alternatives: 짧게 재구성 / 완전 무수정.
- Consequences: 부트스트랩은 다소 길지만 기존 세션·에이전트 정의(`.claude/agents/*`)가 참조하는 내용이 깨지지 않음.
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-002 신규 운영 문서는 docs/agent/ 집약

- Date: 2026-07-16
- Status: approved
- Decision: `workflow-before-after.md`, `failure-cases.md`, `lifecycle-*.md`를 루트가 아닌 `docs/agent/` 아래에 둔다. 필수 파일명은 유지.
- Context: 루트가 이미 혼잡(빌드 파일·compose·workflow mjs 등).
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-003 Hooks는 스크립트 + example 설정만

- Date: 2026-07-16
- Status: superseded (→ ADR-LITE-005)
- Decision: 훅 로직은 `scripts/agent/`의 실행 가능한 셸 스크립트로 두고, `.claude/settings.example.json`만 생성한다. 실제 활성화(`settings.json`/`settings.local.json` 반영)는 사람이 검토 후 수동으로 한다.
- Context: 전역 OMC가 이미 자체 훅을 주입 중이라 충돌 위험이 있고, 활성 설정은 검증된 스키마가 필요.
- Consequences: 훅이 자동으로 동작하지 않음 — Codex 등 타 에이전트는 같은 스크립트를 수동/자체 훅으로 호출.
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-004 .ai/task.md는 현재 루프로 시드

- Date: 2026-07-16
- Status: approved
- Decision: `.ai/task.md`를 빈 템플릿이 아니라 live-gap-closure + v2 준비의 실제 상태로 시드한다. 이후 갱신은 사람이 한다.
- Approved by: 사용자 (AskUserQuestion 인터뷰)

## ADR-LITE-005 훅 실활성화 (.claude/settings.json)

- Date: 2026-07-16
- Status: approved
- Decision: `.claude/settings.json`을 생성해 PreToolUse(`Read|Write|Edit` → `protect-sensitive-files.sh`)·PostToolUse(`Write|Edit` → `verify-changes.sh`) 훅을 실활성화한다. ADR-LITE-003(example 전용)을 대체한다.
- Context: ADR-LITE-003의 우려였던 "전역 OMC 훅과의 충돌"을 실측으로 해소 — ① 전역 `~/.claude/settings.json`에 hooks 키 없음, ② OMC 플러그인 훅 11종은 오케스트레이션 계층(keyword-detector/skill-injector/persistent-mode 등)이라 이 레포 가드(시크릿·골든·legacy 차단, 검증 매트릭스)와 기능 중복 없음(체인 누적만). ③ `verify-changes.sh`는 plain stdout + exit 0이라 모델 컨텍스트 미주입, docs-only diff는 한 줄로 종료 → matcher 협소화 불필요 판정.
- Evidence: 훅 프로토콜(stdin JSON) 실사격 5케이스 — `.env.hooktest` Write→exit 2 차단, 골든 Edit→exit 2 차단, legacy Read→exit 0 허용, 일반 Write→exit 0, verify-changes.sh 훅 모드→변경 매트릭스 출력. @멘션 첨부는 PreToolUse 우회 → `.claudeignore`가 담당.
- Consequences: 훅은 세션 시작 시 스냅샷되므로 **다음 세션부터** 적용. Codex 등 타 에이전트는 종전대로 같은 스크립트를 수동 호출(듀얼 모드 유지).
- Approved by: 사용자 (합의 계획 `.omc/plans/2026-07-16-agent-os-activation-plan.md` 전체 승인, 2026-07-16)

## ADR-LITE-006 런북 커맨드 /os-* 개명

- Date: 2026-07-16
- Status: approved
- Decision: `.claude/commands/`의 7종 런북을 `/os-` 접두로 개명한다 (`/os-start-task` `/os-analyze` `/os-implement` `/os-debug` `/os-verify` `/os-review` `/os-checkpoint`). 참조 갱신은 3버킷 게이트로 분류 — ①개명 대상(런북 상호참조·CLAUDE/AGENTS/docs/agent), ②보존(전역 라우팅 `CLAUDE.md:121` `invoke /review` = gstack 스킬, parity-ship SKILL.md의 `/review`), ③무시(스크립트 경로·산문 슬래시·stale worktrees). 게이트 원장: `.omc/artifacts/w0-1-rename-gate.md`.
- Context: 무접두 `/verify`·`/review`·`/analyze`가 전역 OMC 스킬·gstack 커맨드와 이름 충돌 — 라우팅이 비결정적이 됨 (current-state Open question ①).
- Consequences: 이 레포 런북은 항상 `/os-*`로 호출. 전역 스킬(`/review` 등)의 라우팅 문구는 건드리지 않았으므로 기존 파이프라인 무영향. parity-ship SKILL.md 참조의 모호성 1건은 게이트 원장에 기록(후속 판단 대상).
- Approved by: 사용자 (합의 계획 전체 승인, 2026-07-16)

## ADR-LITE-007 .mcp.json un-ignore + 토큰 스캔 가드레일

- Date: 2026-07-16
- Status: approved
- Decision: `.gitignore`에서 `.mcp.json`을 제거해 MCP 선언(playwright stdio · atlassian sse · sentry http · headroom)을 커밋 가능하게 한다. 보상 가드레일로 `protect-sensitive-files.sh` §3에 `.mcp.json` 쓰기 시 토큰 패턴 스캔(sk-ant/sk-/ghp_/github_pat_/xox*/AKIA/glpat-/sntrys_/JWT + 비-env token/apiKey/password/secret 필드)을 추가한다. `.mcp.example.json` 템플릿 방식은 기각(사본 드리프트).
- Context: git-ignored 상태로는 W1 도구 배선 커밋이 no-op(Architect F1). un-ignore는 시크릿-커밋 방어선 하나를 제거하므로 보상 장치 필수(Critic #3). 재현성은 선언 수준 — 원격 2종은 사용자별 대화형 OAuth(온보딩: `docs/agent/tool-capabilities.md`).
- Evidence: 가드레일 실사격 6케이스 — sk-ant 토큰 Write→exit 2, token 필드 Edit→exit 2, env 참조 선언→exit 0, 수동 모드 디스크 스캔→exit 0, `.env` 차단 회귀 무손상, 일반 파일 허용 무손상.
- Approved by: 사용자 (합의 계획 W1-1 전체 승인, 2026-07-16)

---

## 템플릿

```md
## ADR-LITE-NNN 제목

- Date:
- Status: proposed / approved / superseded
- Decision:
- Context:
- Alternatives:
- Consequences:
- Approved by:
```
