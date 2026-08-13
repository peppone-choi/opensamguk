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

## Residual condition

GitHub issue #186 was intentionally not mutated before review. The exact reviewed title/body/comment
must be applied after commit, push, and ready PR creation before OPENSAM-44 is reported complete.

Verdict: cleared
