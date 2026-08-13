# OPENSAM-44 contract reconciliation — independent architecture review

Date: 2026-08-13

## Review contract

- Scope: the complete documentation diff plus the exact proposed GitHub issue #186 title, body,
  and follow-up comment in
  `docs/superpowers/plans/2026-08-13-opensam-44-contract-crosswalk.md`.
- Reviewer: independent `fable-deep-reasoner` agent; read-only, no file edits.
- Required checks: no implementation/schema/migration work; broad T1 batch superseded without losing
  checklist obligations; OPENSAM-150 remains first product migration `V901`; ADR-LITE-019/029 and
  completed OPENSAM-43 consistency; one-daemon-write, world scope, and v1 isolation.

## Findings and remediation

The first pass found one documentation contradiction: `SESSION_HANDOFF.md` said the OPENSAM-43
runtime plan was unchanged while this diff refined its scope paragraph. The handoff now says the plan
remains authoritative for runtime behavior and explicitly records the OP44 decomposition/OP150
`V901` pointer. The reviewer confirmed the contradiction is resolved.

No BLOCKER, MAJOR, MINOR, or QUESTION finding remains.

The first PR-conversation Codex round then found two integration gaps on commit `01ddf4c37d`:

- The crosswalk asserted an authoritative supersession without a durable approval record. The
  crosswalk now records the user's 2026-08-13 merge authorization and is explicitly the task-local
  OPENSAM-44 execution contract. Shared `.ai/*` remains single-writer/fan-in scope rather than being
  edited concurrently from this worktree.
- Removing product implementation from OPENSAM-44 left the older R1-R6 prose claiming that the v2
  scenario seed mechanism already existed. The crosswalk, R1-R6 design, and ticket ledger now assign
  migration-before-seed/configured-source-to-DB integration to OPENSAM-150 and the scenario event
  payload, action registration, and seed/reseed acceptance to OPENSAM-151. OPENSAM-152 remains the
  sequential consumer.

An independent remediation review inspected the latest dirty tree and returned `CLEAR` with no
BLOCKER, MAJOR, MINOR, QUESTION, or NIT. It verified the task-local approval record, shared `.ai`
single-writer boundary, the OPENSAM-150/151/152 split across the crosswalk/R1-R6 design/ticket
ledger, and the truthful pending external issue synchronization. A final immutable exact-head
review remains required after commit and push.

A later exact-head Codex round found one additional stale routing defect on `ec4815aa`: the active
shared-flush handoff, CQRS failure contract, and hardening plan still named OPENSAM-44 as the entity
mapper/flush implementation consumer. Those authoritative documents now retain the foundation and
historical Jira-link facts while routing the first product extension to OPENSAM-150 and every later
extension to its crosswalk-assigned just-in-time product owner.

The independent dirty-tree remediation review returned `CLEAR`: no active OPENSAM-44
owner/consumer/blocker routing remains in the five authoritative documents or the combined changed
documentation. It verified that the foundation sequence, world scope, JDBC-only daemon write,
no-second-dirty-truth rule, and v1 isolation remain unchanged. An immutable exact-head review is
still required after commit and push.

## Independent evidence

- All 14 issue #186 checklist families appear exactly once in the crosswalk: A02/A03, A07/A08,
  A14, B02/B03/B04, B09, B14, B16, C05, C06, C08, D02, F09/F10, G04, and I08. T2 remains a
  separate V2-7 concern.
- Every changed/untracked path is Markdown. Production `infra/src/main/resources/db/migration_v2/`
  contains only `README.md`; the only V900 file is the OP43 test probe under
  `app/game-engine/src/test/resources/db/migration_v2/`.
- The admission contract requires `world_id`, world-scoped unique keys and foreign keys,
  forward-only compensation, `ChangeRecorder -> JdbcFlushExecutor`, no JPA daemon write or second
  dirty truth, scoped reads, and zero v2 application in v1.
- ADR-LITE-019 keeps G0/C-track post-open. ADR-LITE-029 assigns the first real leaf to OP150. The
  completed OP43 plan retains its runtime/isolation boundary and now names OP150 `V901`.
- The exact proposed issue title/body/comment preserves all checklist families semantically and
  points readers to the exact-ID crosswalk.

The reviewer rejected broad OP44 implementation because it would pre-create speculative tables and
channels before model/consumer contracts and contradict OP150's first-leaf ownership. Deleting the
persistence obligations was also rejected. Just-in-time product ownership preserves the obligations
at the first observable consumer.

## External issue synchronization

GitHub issue #186 was updated before the original PR handoff with the reviewed title/body and a
contract-correction comment. The remediation adds the explicit OPENSAM-150/151 scenario-seeding
split; issue #186 and the OPENSAM-150/151 issue bodies must receive the same wording after the
remediation commit is pushed. Until that synchronization is observed, issue/body truth is pending.

Verdict: cleared
