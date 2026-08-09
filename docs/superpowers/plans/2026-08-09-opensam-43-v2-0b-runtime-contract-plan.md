# OPENSAM-43 V2-0B runtime contract implementation plan

Status: approved by user on 2026-08-09 (`"승인."`).

## 1. Outcome

V2-0B proves that the v2 sandbox is an independently identified runtime with a fail-closed content
catalog, isolated Flyway execution, versioned v2 wire contracts, and zero application to v1 production.
It opens no persistence leaf and changes no v1 wire shape.

## 2. Approved input contract

- Canonical input: `infra/src/main/resources/scenario/cities_1010.json`.
- It is referenced, never copied into `content/v2`.
- Expected SHA-256: `6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393`.
- Expected rows: 94 total cities and 24 rows with `nation_id != 0`.
- Two independent loads must produce equal typed snapshots and an empty in-memory diff.
- G0's 1,180 counter and gameplay `CountyParticipationFixture` are post-open work, not OP43 acceptance.

## 3. Acceptance mapping

| Item | Deliverable | Observable acceptance |
|---|---|---|
| 0B-a | v1 completion ledger reference | points to the cleared v1 non-operational ledger; no new parity claim |
| 0B-b | canonical process-world identity measurement | live gated context exposes its existing configured `WorldId` and v2 profile; no duplicate identity bean |
| 0B-c | existing AND gate integration | property-only/profile-only remain closed; both open |
| 0B-d | migration contract | sibling location, V900+ naming, world-scoped forward-only/compensating rollback rule |
| 0B-e | test-only Flyway probe | sandbox applies probe; v1/default context does not |
| 0B-f | typed catalog | ACTIVE loads; CANDIDATE/EXCLUDED/BUDGET_ONLY and malformed metadata fail closed |
| 0B-g | city adapter | source hash/counts match and repeat snapshot diff is zero |
| 0B-h | v2 command-result envelope | explicit schema version and round-trip without v1 wire changes |
| 0B-i | v2 turn-event envelope | explicit schema version and round-trip without v1 wire changes |
| 0B-j | query impact ledger | consumers and future invalidation/read surfaces are enumerated |
| 0B-k | v1 non-application guard | production has zero v2 beans, v2 probe tables, and loaded v2 content |

## 4. Scope boundaries

OP43 may add read-only catalog/runtime contract types, test-only migration probes, tests, and evidence.
Actual v2 schema/persistence is OPENSAM-44/150. The first production `migration_v2` SQL file and first
v2 leaf remain OPENSAM-150. RTK builders 104/105, G0, deployment, and cutover are excluded.

## 5. Execution lanes

### Lane A: catalog and pinned city adapter

Owns only infra v2 catalog source/resources/tests. Start with tests that fail because status parsing,
inactive rejection, hash/count enforcement, and repeat snapshots do not exist. Implement the smallest typed
metadata and adapter. Do not wire Spring beans yet.

RED/GREEN:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :infra:test \
  --tests opensamguk.infra.v2.V2ContentCatalogTest \
  --tests opensamguk.infra.v2.V2CityCatalogAdapterTest --rerun-tasks
```

### Lane B: v2-only wire versions

Owns only new `common.wire.v2` source/tests. It must not edit existing v1 `RealtimeEvent`,
`TurnDaemonCommandResult`, or golden fixtures.

RED/GREEN:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test \
  --tests opensamguk.common.wire.v2.V2WireContractTest --rerun-tasks
```

### Lane C: runtime/Flyway/v1 guard fan-in

Starts after A and B. It is the sole writer for shared app v2 configurations and their tests. It measures the
existing canonical process-world bean (`EngineProcessWorld` / game-api equivalent) together with the active
profile instead of creating a second world identity. It registers the read-only adapter only inside the existing
AND gate. A sibling-location test-only Flyway probe proves execution in v2 and absence in v1. No production
migration is added.

RED/GREEN:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:game-engine:test :app:game-api:test \
  --tests '*v2*' --rerun-tasks
```

### Lane D: ledger, evidence, and review

Updates 0B-a/j, backlog/state/handoff, and writes one PR-visible independent review artifact. It records
executed and unexecuted checks separately and never upgrades local evidence into deploy evidence.

## 6. Fan-in gates

```bash
git diff --check
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :infra:test \
  :app:game-engine:test :app:game-api:test --rerun-tasks
tools/parity/gate.sh backend
tools/agent-system/check.py --strict --base origin/main --format json
scripts/agent/verify-changes.sh --run
```

Gradle success requires `BUILD SUCCESSFUL` plus XML with zero failures/errors. Docker-disabled skips are
reported as skips, not passes. An independent agent reviews the exact tree; any `fix-required` blocks PR merge.

## 7. PR and release boundary

The PR closes OPENSAM-43 only after checks and review are green. The approved v2 completion goal authorizes
commit, push, PR, review remediation, and merge for this ticket. It does not authorize deployment/cutover,
secret access, data deletion, legacy/golden writes, or test weakening.
