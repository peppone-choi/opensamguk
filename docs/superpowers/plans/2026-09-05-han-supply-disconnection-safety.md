# Han Supply Disconnection Safety Implementation Plan

> **For Codex:** Execute with `superpowers:test-driven-development`. Do not attempt county-anchor,
> coastline, or lake-mask geometry repair in this task. Run independent review and
> `superpowers:verification-before-completion` before issue updates or completion claims.

**Goal:** Prevent an incomplete spatial projection from decaying or neutralizing a live Han city,
while producing a fail-closed canonical audit of every city-vs-spatial reachability disagreement.

**Architecture:** Add a pure dual-evidence evaluator in `logic`, a versioned adjudication ledger and
Python audit for canonical data, and an engine loader that attaches reviewed policies to the existing
seat-only spatial projection. Runtime protects unclassified destructive disagreements; CI rejects
them. Existing decay formulas, neutral-city behavior, and non-Han behavior remain unchanged.

**Tech Stack:** Kotlin/JDK 21, Jackson, Python 3 `unittest`, Gradle, GitHub Actions.

---

## Task 1: Pure dual-evidence reachability evaluator

**Files:**

- Create: `logic/src/main/kotlin/opensamguk/logic/world/SupplyReachability.kt`
- Modify: `logic/src/main/kotlin/opensamguk/logic/world/UpdateCitySupply.kt`
- Modify: `logic/src/test/kotlin/opensamguk/logic/world/UpdateCitySupplyBfsTest.kt`
- Modify: `logic/src/test/kotlin/opensamguk/logic/world/UpdateCitySupplyLossTest.kt`

**Step 1: Write failing verdict tests**

Add table-driven tests for:

- both graphs supplied → `BOTH_SUPPLIED`;
- city only, no policy → fail-safe `CITY_ONLY_PROTECTED`;
- city only, `PROTECT_*` → `CITY_ONLY_PROTECTED`;
- city only, `UPHOLD_*` → `SPATIAL_CUT_UPHELD` and destructive unsupplied result;
- spatial only → `SPATIAL_ONLY_SUPPLIED`;
- neither → `BOTH_UNSUPPLIED`;
- unmapped legacy city uses the city graph and never bridges spatial provinces;
- rows are ordered by city ID regardless of input iteration order.

Add loss tests proving protected cities receive no city/general decay or neutralization, while
upheld and both-unsupplied cities retain the existing 10%/5% and trust behavior.

Run and capture RED:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :logic:test \
  --tests 'opensamguk.logic.world.UpdateCitySupplyBfsTest' \
  --tests 'opensamguk.logic.world.UpdateCitySupplyLossTest' \
  --no-daemon
```

**Step 2: Implement the pure contract**

Create:

```kotlin
enum class SupplyDisconnectionDecision {
    PROTECT_GEOMETRY_DEFECT,
    PROTECT_PARENT_MISASSIGNMENT,
    UPHOLD_WATER_ROUTE_ONLY,
    UPHOLD_HISTORICAL_EXCLAVE,
}

enum class SupplyReachabilityVerdict {
    BOTH_SUPPLIED,
    CITY_ONLY_PROTECTED,
    SPATIAL_ONLY_SUPPLIED,
    BOTH_UNSUPPLIED,
    SPATIAL_CUT_UPHELD,
}
```

Add `SupplyFallbackPolicy`, `SupplyReachabilityRow`, `SupplyReachabilityEvaluation`, and
`evaluateSupplyReachability`. Preserve `computeSuppliedCitiesWithSpatialNetwork` as a compatibility
wrapper if existing callers require it.

Extend `SpatialSupplyNetwork` with
`fallbackPolicies: Map<Int, SupplyFallbackPolicy> = emptyMap()`. Make `applyCitySupply` consume the
evaluation and expose deterministic reachability rows in `CitySupplyResult`.

**Step 3: Run focused tests and commit the logical kernel**

Expected: focused logic tests pass and existing supply tests remain unchanged.

```text
feat(supply): gate destructive loss on dual reachability

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 2: Canonical disagreement audit and schema

**Files:**

- Create: `tools/scenario/audit_han_supply_disagreements.py`
- Create: `tools/scenario/tests/test_han_supply_disagreement_audit.py`
- Create: `data/curated/han/supply-disconnection-adjudications-v1.json`

**Step 1: Write synthetic failing audit tests**

Fixture tests must red-probe:

- unknown decision;
- duplicate/overlapping effective scenario ranges;
- runtime city, physical place, or jurisdiction identity drift;
- missing `sourceLedgerRow`;
- unclassified city-only mismatch;
- stale adjudication whose expected mismatch disappeared;
- city-bearing degree-zero province without repair/protection;
- deterministic output ordering and summary counts.

Run and capture RED:

```bash
python3 -m unittest tools.scenario.tests.test_han_supply_disagreement_audit
```

**Step 2: Implement independent graph reconstruction**

The audit must load the committed scenario/runtime map, `han-tiles.json`, scenario ownership,
territory/parent adjudication sources, and the new ledger. Compute city-graph and spatial-graph BFS
independently; do not call production Kotlin or copy a precomputed verdict.

Support `--check` and a deterministic human/JSON inventory mode. Fail on every invalid, unclassified,
or stale row. Report all five verdict counts per scenario plus degree-zero coverage.

**Step 3: Materialize and adjudicate the complete production inventory**

Current city-only counts are expected to begin at:

```text
1010=6, 1020=33, 1021=29, 1030=51, 1031=50,
1040=3, 1041=15, 1050=3, 1060=5, 1070=7,
1080=6, 1090=9, 1100=7, 1110=7, 1120=2
```

Do not paste one blanket protection over this inventory. Join every row to a stable physical place,
jurisdiction, effective scenarios, and a real source ledger decision. Use only the four approved
decisions. If evidence cannot distinguish protection from uphold, leave the audit red and report the
blocked row instead of guessing.

Known source rows include `PARENT-0000@401:224`, `PARENT-0023@437:191`,
`PARENT-0034@480:266`, `PARENT-0038@498:182`, `PARENT-0050@463:286`,
`PARENT-0051@549:331`, `PARENT-0051@551:339`, `PARENT-0051@511:311`,
`PARENT-0101@340:544`, and `PARENT-0101@367:500`.

**Step 4: Run the canonical audit and commit**

```bash
python3 -m unittest tools.scenario.tests.test_han_supply_disagreement_audit
python3 tools/scenario/audit_han_supply_disagreements.py --check
```

Expected: `unclassified=0`, `stale=0`, identity drift zero.

```text
feat(map): adjudicate Han supply disagreements

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 3: Engine policy loader and seat-only projection wiring

**Files:**

- Create: `app/game-engine/src/main/kotlin/opensamguk/engine/world/HanSupplyDisconnectionPolicyLoader.kt`
- Create: `app/game-engine/src/test/kotlin/opensamguk/engine/world/HanSupplyDisconnectionPolicyLoaderTest.kt`
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/world/HanSpatialSupplyProvider.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/world/HanSpatialSupplyProviderTest.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/config/SpatialSupplyNetworkWiringTest.kt`

**Step 1: Write failing loader/provider tests**

Cover malformed schema, unknown decisions, duplicate active rows, scenario-range selection,
physical/jurisdiction drift, and deterministic city-policy mapping. Red-probe 漁陽 潞縣 with a changed live
owner so the non-seat projection disagreement is real rather than vacuous.

Replace frozen `owned/supplied/blocked` triples with intent assertions:

- mapped capitals are supplied;
- every city-only row is protected or explicitly upheld;
- no unclassified row survives the canonical fixture;
- only exact reviewed `UPHOLD_*` policies are destructive;
- 1,524 provinces, 1,020 jurisdictions, and 172 commanderies remain.

**Step 2: Implement strict ledger loading**

The loader validates stable identity against `provinceRecords`, `jurisdictionRecords`, and runtime
city mapping. Malformed committed configuration fails engine startup. A new live mismatch without a
ledger row remains runtime-protected by Task 1.

Attach active policies to `SpatialSupplyNetwork`. Preserve the scenario baseline and current
seat-province-only live override. Do not project one city owner across every jurisdiction province.

**Step 3: Run focused engine tests and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests 'opensamguk.engine.world.HanSupplyDisconnectionPolicyLoaderTest' \
  --tests 'opensamguk.engine.world.HanSpatialSupplyProviderTest' \
  --tests 'opensamguk.engine.config.SpatialSupplyNetworkWiringTest' \
  --no-daemon
```

```text
feat(supply): load reviewed Han fallback policies

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 4: Monthly diagnostics and deployment wiring

**Files:**

- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/world/WorldActionContext.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/turn/MonthlyWorldEventSeamTest.kt`
- Modify: `docker/game-engine.Dockerfile`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/superpowers/plans/2026-09-02-spatial-supply-projection.md`
- Modify: `docs/superpowers/specs/2026-09-02-administrative-spatial-hierarchy-design.md`

**Step 1: Red-test structured monthly diagnostics**

Prove reachability rows reach the monthly seam in stable order. Unclassified protected rows log at
WARN with city ID and both booleans. Adjudicated protect/uphold rows include decision and source row.
Do not add these internal diagnostics to player history.

**Step 2: Implement diagnostics and packaging**

Copy the new curated ledger into the game-engine image. Add
`python3 tools/scenario/audit_han_supply_disagreements.py --check` as a named CI step. Update the two
existing design/plan documents to state that projected spatial occupancy is independent evidence,
not destructive truth before persistent province-control producers exist.

**Step 3: Run focused tests and commit**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:game-engine:test \
  --tests 'opensamguk.engine.turn.MonthlyWorldEventSeamTest' \
  --no-daemon
```

```text
chore(supply): expose and gate Han mismatch diagnostics

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

## Task 5: Full verification, review, issues, and report

**Files:**

- Create: `reports/opensamguk/tasks/2026-09-05-han-supply-disconnection-safety.md` in the metarepo

**Step 1: Run map/scenario gates**

```bash
python3 -m unittest discover -s tools/map/tests -p 'test_*.py'
python3 tools/map/audit_territory_disconnections.py --check
python3 -m unittest discover -s tools/scenario/tests -p 'test_*.py'
python3 tools/scenario/build_han_world.py --check-gate
python3 tools/scenario/audit_han_supply_disagreements.py --check
```

No test may rewrite `han-tiles.json`. 徐·鄞·劇·東郡·陽泉·合浦 remain protected; this task must not
use `adjudicate_han_province_fragments.py` to approximate their anchor/coast/lake repairs.

**Step 2: Run JVM gates with XML confirmation**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :logic:test :app:game-engine:test :app:game-api:test --no-daemon
python3 tools/agent-system/check_test_xml.py logic app/game-engine app/game-api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build --no-daemon
```

Confirm `BUILD SUCCESSFUL` and test XML, not only process exit.

**Step 3: Independent review**

Request a reviewer to attack fail-safe/fail-closed inversion, stale policies, seat-only ownership,
scenario range boundaries, nondeterministic ordering, and accidental geometry changes. Resolve every
fix-required finding.

**Step 4: Update issues with actual evidence**

- #596: verdict table, scenario counts, `unclassified=0`, tests, and scenario/topology scope only.
- #598: protected geometry rows and explicit statement that no geometry was approximated; do not
  close.
- #473: persistent province-control prerequisite and the destructive-supply boundary; do not close.
- #541: no mutation unless its independent generator bug was actually fixed.

**Step 5: Write the task report**

Record result, commits, exact verification outputs/counts, issue comments, no-geometry-change proof,
and remaining risks. Remove a clean worktree only after the report and only when it is no longer
needed; never remove a dirty worktree automatically.
