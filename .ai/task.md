# Current Task

## 2026-07-30 V2 실시간 전투 공통 기반 계획·티켓화

- Status: approved design; implementation planning in progress.
- User request (verbatim):
  - "커밋 및 푸시. 그리고 다음 단계들을 [$superpowers:brainstorming] 이용해서 물어봐서 설계후 지라 타켓과 깃허브 이슈로 넣어줘."
  - "전체 승인."
  - "좋아. 승인."
  - "승인."
- Goal:
  - Convert the approved realtime battle session/authority/order/replay spec into one executable common-foundation implementation plan.
  - Keep land, siege, naval, and 2.5D client as separately specified consumers of that foundation while retaining all three as V2 launch requirements.
  - After human plan approval, create the Jira OPENSAM target/Epic hierarchy and linked GitHub issues with Given-When-Then acceptance criteria.
- Approved design:
  - `docs/superpowers/specs/2026-07-30-v2-realtime-battle-session-command-replay-design.md`
  - Dedicated single-world `battle-engine`; authoritative 200ms session actor; WebSocket; durable events/snapshots; campaign handoff/result boundaries.
  - Commander + delegated officers, one human per formation, delayed authority/orders, limited pause, reconnect/AI/deputy succession.
  - Launch baseline 16 formations per side, 12–15 minutes; land/siege/naval required; deterministic headless fallback only for failed realtime gates.
- Allowed files:
  - `docs/superpowers/specs/**`, `docs/superpowers/plans/**`, `docs/superpowers/SESSION_HANDOFF.md`
  - `.ai/task.md`, `.ai/decisions.md`, `.ai/current-state.md`, `.ai/handoff.md`
  - Jira OPENSAM and the connected GitHub repository after plan approval
- Acceptance evidence:
  - Plan maps every common-foundation spec requirement to an exact file/interface/test task with no placeholders.
  - Independent adversarial plan review has no remaining `fix-required`.
  - Jira and GitHub items cross-link to the approved spec/plan and preserve foundation-first dependencies.
- Human approval checkpoints:
  - Implementation plan adoption before external tracker writes.
  - Any implementation, merge, deploy, production change, data deletion, secret access, legacy/golden write, or test weakening needs separate authority.
- Non-goals:
  - Runtime implementation, PR creation, merge/deploy, production/S6 activation, or changing v1 parity behavior.

---

## Previous tracked task contract

- Status: approved, in progress.
- Updated: 2026-07-25
- Goal: refresh the current documentation and agent harness, with `README.md` as the primary onboarding surface.
- Approved plan:
  - Update `README.md` to match the current July 2026 implementation, local quick start, CQRS consistency/runtime state, and shared/server production model.
  - Update `.claude/HARNESS.md` from its obsolete parity-only/deploy narrative to the current Agent OS, parity, verification, and deployment entry points.
  - Correct the comment-language and deployment wording in the tracked parity skills.
  - Refresh `docs/agent/project-overview.md` so README can link to a current bounded status source.
- Allowed files:
  - `README.md`
  - `.claude/HARNESS.md`
  - `.claude/skills/parity-close/SKILL.md`
  - `.claude/skills/parity-ship/SKILL.md`
  - `docs/agent/project-overview.md`
  - `.ai/task.md`
  - `.ai/current-state.md`
  - `.ai/ownership.md`
  - `docs/superpowers/reviews/2026-07-25-docs-harness-refresh-review.md`
- Acceptance evidence:
  - No obsolete `V1..V10`, `appleboy/ssh-action`, unresolved `commandBlockMs` wiring-gap, or Korean-code-comment claim remains in the refreshed surfaces.
  - README quick start names required environment variables without exposing values or secrets.
  - Production guidance points to `opensamguk-docker` shared/server orchestration and clearly labels repository production compose/manual deploy as compatibility-only.
  - `git diff --check`, path/link checks, and `tools/agent-system/check.py` results are recorded.
- Human approval checkpoints:
  - Commit, push, merge, deploy, data deletion, or secret access remain prohibited without separate approval.
- Non-goals:
  - Runtime/code/schema changes, legacy or golden edits, production operations, and external tracker mutations.
