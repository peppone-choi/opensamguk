# Ticket triage — CQRS Highest/P0 next after S4

Date: 2026-07-23
Role: research only — **no production code edits**
Surface of truth: GitHub jira-mirror issues + local docs/code on branch `peppone-choi/arowana`
Main tip: `73fb13cb` = merge of PR #312 (`fix(OPENSAM-135): durable reserved result outbox`)
Atlassian MCP: unavailable this run; GH mirror + repo evidence used.

---

## 0. Executive recommendation

| Question | Answer |
|----------|--------|
| Can we CLOSE #279 / OPENSAM-133 and #280 / OPENSAM-134? | **Yes — already CLOSED on GH** (2026-07-23) as **build-only complete**. Closure is **justified** by PR #312 + `Verdict: cleared` + path/test evidence below. |
| Residual vs AC GWT? | **No open build-scope AC** for T1/T2 on main tip. **Production deploy/cutover / operational activation** remain out of scope and **must not** be inferred closed. |
| Next implementable ticket after S4? | **OPENSAM-137 / #283 (`ARCH-S5-T1`)** — real implement ticket, **not** hygiene-only. |
| S4-T4 dependency for S5? | **Satisfied**: OPENSAM-136 / #282 **CLOSED** 2026-07-22 with PR #312. |
| Explicit do-not | Prod deploy/cutover · golden fabricate · force-merge |

---

## 1. Open / recent GH CQRS track — status vs main code

### 1.1 Parent stack (still open unless noted)

| GH | Jira | Draft ID | GH state (2026-07-23) | Code/evidence on `73fb13cb` |
|----|------|----------|------------------------|-----------------------------|
| **#266** | OPENSAM-120 | **ARCH-S4** (story) | **OPEN** | Child T1–T4 closed; story left open for activation/operational residual (see close comments on #279/#280). Build-side durable inbox/outbox/wake path is on main via PR #312. |
| **#267** | OPENSAM-121 | **ARCH-S5** | **OPEN** | Not started as a story; next work is T1 (#283). |
| **#268** | OPENSAM-122 | **ARCH-S6** | **OPEN** | Rollout/canary/replica ADR — **post S2–S5**; **do not** start as next coding ticket. |
| #271 | OPENSAM-125 | ARCH-S1-T3 | **OPEN** | Capacity thresholds / admission policy — still open; soft gate for full S5 “activation” language in plan, not a reason to skip S5-T1 catalog work. |
| #262 / #263 / #264 / #265 | OPENSAM-116…119 | epic / S0–S3 parents | mostly OPEN or partially closed | Prior S2/S3 children closed (e.g. #274/#275/#278); not the critical path for “after S4” implement pick. |

### 1.2 ARCH-S4 children

| GH | Jira | Draft ID | GH state | Close / merge evidence |
|----|------|----------|----------|------------------------|
| **#279** | OPENSAM-133 | ARCH-S4-T1 | **CLOSED** `2026-07-23T11:23:33Z` | Close comment: build-only complete on main via **PR #312** (`73fb13cb…`); review cleared; no prod cutover claimed. |
| **#280** | OPENSAM-134 | ARCH-S4-T2 | **CLOSED** `2026-07-23T11:23:32Z` | Same; cites consumer-group wake / PEL / post-commit ACK **foundations**. |
| **#281** | OPENSAM-135 | ARCH-S4-T3 | **CLOSED** `2026-07-22T18:51:36Z` | PR #312; Jira transitioned 완료. |
| **#282** | OPENSAM-136 | ARCH-S4-T4 | **CLOSED** `2026-07-22T18:51:36Z` | PR #312; fault/crash matrix coverage in same merge. |

### 1.3 PR #312 + review verdict

- **PR #312** MERGED `2026-07-22T18:49:49Z` → merge commit **`73fb13cbbe60b031d09d09ec03e4672f2013f4b2`** (this branch tip).
- Title: `fix(OPENSAM-135): durable reserved result outbox`.
- PR body verification (focused):
  - `CommandResultOutboxFlushIT` tests=7 f=0 e=0
  - `TurnRunServiceFlushRecoveryTest` tests=7 f=0 e=0
  - `TurnRunServiceIT` tests=1 f=0 e=0
  - `tools/agent-system/check.py --strict --base origin/main` → Errors: 0
  - Independent review → **`Verdict: cleared`**
- Review artifact (updated, not stale fix-required):
  `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md`
  - **Verdict: cleared**
  - **No blocking findings**
  - Residual risks (non-blocking for ticket close):
    1. Broad `scripts/agent/verify-changes.sh --run` Gradle `--rerun-tasks` stall — tooling baseline, **not** counted green.
    2. Design-level `reservationRevision` coordinator from contract remains outside this build-only fix.

### 1.4 Historical research (superseded on main tip)

`docs/superpowers/research/2026-07-22-s4-remaining-gaps.md` (branch worktree snapshot, 2026-07-22) listed two remaining gaps:

1. Reserved-turn EXECUTION correlation (`EXECUTION_APPLIED` / `EXECUTION_REJECTED`)
2. S4-T4 crash/replay matrix

Those gaps were closed on main by the OPENSAM-135 remediation + PR #312 (see `.ai/current-state.md` sections “reserved execution correlation + S4-T4 matrix” and “OPENSAM-135 review remediation”, and the cleared review). **Do not treat the 2026-07-22 remaining-gap map as current open work.**

---

## 2. Can we CLOSE #279 and #280? Evidence vs AC GWT

### 2.1 Recommendation

| Ticket | Close? | Scope of close |
|--------|--------|----------------|
| #279 OPENSAM-133 / S4-T1 | **Yes (already closed)** | **Build-only** durable inbox authority before `202` |
| #280 OPENSAM-134 / S4-T2 | **Yes (already closed)** | **Build-only** consumer-group wake + post-commit ACK + DB poll fallback |

Reopen only if new evidence shows AC regression on main; none found this triage.

### 2.2 OPENSAM-133 / #279 — AC → evidence map

| AC (GWT, abbreviated) | Status | Evidence pointers |
|-----------------------|--------|-------------------|
| `(world_id, request_id)` inbox + payload/schema version committed **before** `202`; Redis wake failure does not undo admission | **Met (build)** | Schema: `infra/src/main/resources/db/migration/V34__command_inbox.sql`
Repo: `infra/.../CommandInboxRepository.kt` (`INSERT INTO command_inbox`, claim/lease)
API: `app/game-api/.../CommandReserveService.kt` (inbox then wake; `markRedisWakePublished`; Redis fail tolerated)
Test: `CommandReserveServiceTest` — ``immediate command inserts inbox before publishing and tolerates redis failure``; `CommandReserveServiceIT`; `CommandControllerIT` — ``AVAILABLE command returns 202 with a requestId`` |
| Reserved-turn: `RESERVATION_ACCEPTED` only when ring + inbox durable; execution separate lifecycle | **Met (build)** | API terminal path: `CommandTerminalResultFactory` + `CommandResultRepository.insertTerminalResult` (same TX as inbox/ring)
Wire seq: admission `result_seq=1` vs execution `result_seq=2` (`TurnRunService.ADMISSION_RESULT_SEQ` / `EXECUTION_RESULT_SEQ`)
Test: `CommandResultOutboxFlushIT` — ``reserved execution result coexists with reservation terminal under same request id``; `CommandReserveServiceTest` reserved intent / idempotency cases |
| Immediate: terminal `APPLIED`/`REJECTED` with durable result | **Met (build)** | Flush TX writes: `JdbcFlushExecutor` command_result + command_outbox + inbox terminal (`V35__command_result_outbox.sql`)
Test: `CommandResultOutboxFlushIT` — ``command terminal result and outbox commit with the state effect``; rollback IT |

### 2.3 OPENSAM-134 / #280 — AC → evidence map

| AC (GWT, abbreviated) | Status | Evidence pointers |
|-----------------------|--------|-------------------|
| Consumer group + claim inbox; ACK **after** DB TX with state/CAS/result/outbox | **Met (build)** | `RedisCommandStream.kt`: `XGROUP`, `readWakeEnvelopes`, PEL `claim`, `acknowledgeWake`
`TurnRunService.kt`: `claimExecutableEnvelopes` → dispatch → flush → **`acknowledgeClaimedWakes` only after success**
Tests: `RedisCommandStreamIT` — ``consumer group wake requires explicit ack after read``; ``current consumer claims stale wake from another consumer PEL``; `TurnRunServiceFlushRecoveryTest` — ``claimed Redis wake is acked only after successful intake flush``; ``... not acked when intake flush fails``; ``reserved execution outbox relay still runs when post-commit wake ack fails`` |
| Redis down/trim/crash → durable inbox poll + reclaim; no double-apply | **Met (build)** | `CommandInboxRepository.claimPendingForExecution` (lease reclaim)
`TurnRunService.claimExecutableEnvelopes` falls back to pending poll when wake path empty
Tests: `CommandResultOutboxFlushIT` — ``claimForExecution returns DB payloads in wake order…``; ``claimPendingForExecution reclaims expired leases only``; ring-pull rollback/retry IT |

### 2.4 Residual gaps (explicit — do **not** block T1/T2 close)

These remain **true** after close; they are **not** S4-T1/T2 implementation holes:

1. **No production deploy / cutover** was performed (close comments + review residual). S4 remains **build-only**.
2. **Epic #266** stays OPEN so operational/activation narrative is not silently closed with the children.
3. **Contract design residual**: `reservationRevision` / broader coordinator semantics in `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` — out of PR #312 build scope (review Residual Risks).
4. **Tooling residual**: full local `verify-changes.sh --run` broad matrix stall — documented, not a ticket reopen.
5. **Activation of live ops gates** (canary, expand/backfill, replica ADR) lives under **#268 / ARCH-S6** and related S1 baseline tickets — **still blocked** by design until S2–S5 stories complete.

**ACK cutover language in original S4-T2 ticket:** “ACK/reclaim **activation** only after T1+T3+S3 evidence.” On main tip, **code path is active in the daemon build** (consumer group + post-flush ACK). What remains blocked is **production rollout/cutover** of that architecture, not the missing implementation of ACK.

---

## 3. Next implementable ticket after S4

### 3.1 Recommendation: **OPENSAM-137 / #283 — `ARCH-S5-T1`**

| Field | Value |
|-------|--------|
| GH | **#283 OPEN** |
| Jira | OPENSAM-137 |
| Draft ID | **ARCH-S5-T1** |
| Parent story | #267 / OPENSAM-121 (`ARCH-S5`) |
| Priority | Highest |
| Type | Real implement (catalog + prefetch + architecture guards) — **not hygiene-only close** |

**Why this, not something else:**

- S4 children T1–T4 are closed; S4 story #266 holds only activation residual → **do not** invent more S4 coding tickets without new AC failure evidence.
- Plan order: S5 after S4; first leaf is **hot/cold catalog + deterministic phase-boundary prefetch**.
- #268 S6 is **not** implementable next (depends on S2–S5 complete).
- #284–#288 (S5-T2/T3, S6-T*) are **downstream** of #283 or of full S5.

### 3.2 Dependency check

| Dependency | Ticket | State | Gate for #283 |
|------------|--------|-------|----------------|
| **ARCH-S4-T4** | OPENSAM-136 / **#282** | **CLOSED** | **Satisfied** — proceed. |
| **ARCH-S1-T3** | OPENSAM-125 / **#271** | **OPEN** | Plan: preliminary **access-graph inventory may run in parallel**; **activation** after both deps. For #283 build work, start with CodeGraph/call-site inventory + catalog draft; do not claim production activation or final heap-threshold numbers owned by S1-T3. |

### 3.3 #283 acceptance (from GH body / plan)

1. Explicit **always-hot / phase-hot / query-only cold** catalog; phase-hot prefetched with bounded keyset/limit + stable order; **zero lazy SQL** inside RNG draw / entity iteration.
2. Architecture test fails on unlimited snapshot load or loop-internal repository calls outside catalog.
3. Verify via inventory artifact, query-count tests, deterministic ordering, RNG/log golden comparison (parity discipline — **no fabricated goldens**).

### 3.4 Suggested first slice (for implementer; not executed this run)

1. Access-graph inventory artifact under `docs/superpowers/research/` (or plan-owned path).
2. Catalog data structure + load boundaries in engine boot / phase hooks.
3. Architecture / query-count tests; run focused Gradle + parity gate as required.
4. Independent review; **no** prod cutover.

### 3.5 Hygiene-only close? **No**

Closing #283 without catalog/prefetch/tests would be a **false close**. Hygiene is limited to updating epic/story comments after real S5 work ships.

---

## 4. Explicit do-not (coordinator + implementers)

| Forbidden | Why |
|-----------|-----|
| **Production deploy / cutover** | S4 closes are build-only; S6 (#268) owns expand/backfill/canary/replica GO. Close comments on #279/#280 explicitly exclude prod. |
| **Golden fabricate** | Parity discipline: goldens only from real PHP capture (`tools/php-golden`). On mismatch fix Kotlin, never invent fixtures. |
| **Force-merge** | PR #312 already merged with CI evidence; future S5 PRs require green checks / authorized external-only bypass, never force-merge over failed durability/parity gates. |

Also: do **not** silently close epic **#266** or story **#267** without an explicit activation residual disposition; do **not** start S6 coding as “next after S4.”

---

## 5. Tracker hygiene checklist (human / coordinator)

Already done (as of this triage):

- [x] #281 / #282 closed with PR #312
- [x] #279 / #280 closed with build-only evidence + residual caveats
- [x] Review artifact `Verdict: cleared`

Still open / next:

- [ ] Leave **#266** OPEN until activation residual is reassigned (S6) or explicitly waived
- [ ] Dispatch implement on **#283** (access-graph → catalog → tests → review)
- [ ] Keep **#271** (S1-T3) visible as parallel capacity work; do not block catalog inventory on it
- [ ] Optional: add GH comment on #266 summarizing S4 children complete + pointer to this report
- [ ] Optional: sync Jira OPENSAM-133/134 to 완료 if mirror lag (Atlassian MCP was down this run — **UNKNOWN** live Jira state)

---

## 6. Sources

| Kind | Path / ref |
|------|------------|
| Tip commit | `73fb13cb` on `peppone-choi/arowana` |
| PR | https://github.com/peppone-choi/opensamguk/pull/312 |
| Review | `docs/superpowers/reviews/2026-07-22-opensam-135-durable-result-outbox-review.md` |
| Plan | `docs/superpowers/plans/2026-07-18-cqrs-memory-consistency-hardening-plan.md` (ARCH-S4 / S5) |
| Contract | `docs/superpowers/specs/2026-07-18-cqrs-consistency-failure-contract.md` |
| Prior gap map (superseded) | `docs/superpowers/research/2026-07-22-s4-remaining-gaps.md` |
| Session state | `.ai/current-state.md` (S4 / OPENSAM-134/135 sections through PR CI follow-up) |
| Migrations | `V34__command_inbox.sql`, `V35__command_result_outbox.sql` |
| Core code | `CommandInboxRepository`, `CommandReserveService`, `TurnRunService`, `JdbcFlushExecutor`, `RedisCommandStream`, `CommandOutboxRelay` |
| Core tests | `CommandResultOutboxFlushIT`, `TurnRunServiceFlushRecoveryTest`, `RedisCommandStreamIT`, `CommandReserveServiceTest`, `CommandResultLookupTest` |

---

## 7. Bottom line

S4 (**OPENSAM-133…136 / #279…#282**) is **build-complete on main** at `73fb13cb` with independent review **cleared**. Closing #279/#280 is correct; residuals are **ops activation / S6 / tooling**, not missing T1/T2 code. **Next coding ticket: OPENSAM-137 / #283 (`ARCH-S5-T1`)** with S4-T4 dependency already closed. Do not deploy, fabricate goldens, or force-merge.
