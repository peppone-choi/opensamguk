# OPENSAM-43 V2-0B runtime-contract documentation ledger

## Scope and evidence boundary

This is the docs-only lane for 0B-a and 0B-j. It reconciles approved contract
truth with the canonical v1 evidence and current source seams; it does not
implement or certify the runtime/Flyway/catalog/wire lanes.

- Approved plan: [OPENSAM-43 runtime contract plan](../../superpowers/plans/2026-08-09-opensam-43-v2-0b-runtime-contract-plan.md).
- Approval record: [ADR-LITE-030](../../../.ai/decisions.md) (2026-08-09).
- 0B-a pointer ledger: [0B-a v1 completion reference](0B-a-v1-completion-reference.md).
- 0B-j inventory ledger: [0B-j query/read/write impact inventory](0B-j-query-read-write-impact-ledger.md).

## Frozen approved input contract

| Field | Approved value | Boundary |
|---|---|---|
| Canonical source payload | `infra/src/main/resources/scenario/cities_1010.json` | Existing tracked input; it is referenced, never copied into `content/v2`. |
| Metadata reference | `infra/src/main/resources/content/v2/cities_1010.json` | Seven-field metadata only; its filename is not a second city payload. |
| SHA-256 | `6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393` | Fail closed if the source bytes differ. |
| City counts | 94 total; 24 where `nation_id != 0` | Fail closed if either count differs. |
| Determinism | Two typed loads; in-memory diff is empty | This is an adapter acceptance condition, not a deployment claim. |

The formerly recorded G0/counter-1,180/`CountyParticipationFixture` prerequisite
is superseded only for OPENSAM-43. V2-G0 remains post-open work. OPENSAM-44/150
persistence and first leaf, OPENSAM-104/105 RTK builders, deployment, and cutover
remain outside this lane.

## Documentation loop

| Round | Hypothesis | Score before → after | Grader | Decision | One-line reason |
|---:|---|---|---|---|---|
| 0 | The historical backbone micro still treats G0/1,180 as OP43 entry criteria and leaves 0B-a/j without durable evidence. | stale OP43 prerequisite / no 0B-a pointer / no 0B-j seam inventory → contract-correct prerequisite / linked 0B-a / source-anchored 0B-j | Approved plan §2–4, ADR-LITE-030, canonical v1 ledger, live source inspection | adopted | The approved 94/24 pinned-source contract is smaller than, but does not retire, post-open G0. |

## Executed and unexecuted checks

- Executed in this docs lane: SHA-256 inspection of the tracked source; direct
  metadata/source distinction; source-symbol inventory; Markdown fence balance
  and `git diff --check` (recorded after this edit).
- Not executed in this docs lane: 0B-b~k Gradle tests, Flyway probe, backend
  gate, production context, deploy/cutover, and any PHP/golden replay. Those
  are not implied by this documentation adoption.

## Approval pending

None for this documentation reconciliation. The existing human approval does
not authorize deploy/cutover, secret access, data deletion, legacy/golden
writes, or test weakening.
