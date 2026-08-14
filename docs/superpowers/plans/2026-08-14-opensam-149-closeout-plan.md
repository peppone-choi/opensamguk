# OPENSAM-149 Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align canonical gap and handoff documentation with the already-merged bounded restart gate, then close GitHub issue #324 without claiming quarantined channels as green.

**Architecture:** Treat PR #399 and its immutable review/test evidence as the product source of truth. This change is documentation-only: it converts the stale blanket blocker into a bounded closure plus explicit quarantine and leaves live promotion as an operations gate.

**Tech Stack:** Markdown, GitHub issue/PR metadata, `tools/agent-system/check.py`.

## Global Constraints

- Do not edit Kotlin, SQL, tests, golden fixtures, `legacy/**`, deployment workflows, or production state.
- Preserve every lifecycle marked Q in the authoritative closure matrix; EventStore C/X, resident general/nation allocators, diplomacy-letter allocator, and same-due-tick visibility are notable examples, not the complete list.
- Do not claim that the current live server image contains PR #399 until a separately authorized promotion proves it.
- Commit, push, PR, merge, and issue closure remain human approval points.

---

### Task 1: Bind the active task and ownership

**Files:**
- Modify: `.ai/task.md`
- Modify: `.ai/current-state.md`
- Modify: `.ai/ownership.md`

- [x] Record the docs-only goal, exact allowed files, evidence boundary, and non-goals.
- [x] Register a single writer for the canonical gap/handoff documents.
- [ ] At completion, mark the lane released and record the final PR/issue outcome.

### Task 2: Correct the canonical restart status

**Files:**
- Modify: `docs/superpowers/gap/LOGIC_GAP.md`
- Modify: `docs/superpowers/SESSION_HANDOFF.md`

- [x] Replace the stale “full round-trip unstarted” statement with the merged PR #399 gate and exact review/test evidence.
- [x] Preserve every Q cell as a named quarantine rather than an all-channel pass.
- [x] Remove the blanket doc-only-main-push prohibition while keeping live promotion/restart/clock proof as a separate operations gate.

### Task 3: Verify and review the docs-only diff

**Files:**
- Create: `docs/superpowers/reviews/2026-08-14-opensam-149-closeout-review.md`

- [x] Run `git diff --check` and resolve every whitespace error.
- [x] Run `python3 tools/agent-system/check.py --strict --base origin/main --format json`; expected result: `ok: true`, zero findings after the review artifact exists.
- [x] Run `scripts/agent/verify-changes.sh --run`; expected result: docs-only verification exits 0.
- [x] Obtain an independent review of the exact diff; expected verdict: `cleared` with no claim beyond bounded restart equivalence.

### Task 4: Publish and close

- [ ] After explicit approval, commit with the required Co-Authored-By trailer, push `codex/opensam149-closeout`, and open a ready PR.
- [ ] Require hosted CI green and zero unresolved review findings before merge.
- [ ] Merge the PR, then close #324 with links to PR #399, the closeout PR, the bounded test evidence, and the quarantine list.
- [ ] Verify `gh issue view 324` reports `CLOSED`; do not deploy or promote an engine image in this task.
