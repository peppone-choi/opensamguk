# Han B1 寧陽 Affiliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Apply the approved historical affiliation correction for physical/jurisdiction ID `45277 寧陽` and remove its resolved administrative disconnection.

**Architecture:** Add one exact-parent row to the existing curated ledger and run the existing deterministic materializer. Preserve province geometry and frozen world identities; recompute only administrative parent surfaces, their audits and strict dependent bindings. Do not combine the separate 永 compatibility or 東部侯官 geometry adjudication work.

**Tech Stack:** Python unittest and deterministic JSON materializers; existing Kotlin loader tests under JDK 21.

**Spec:** external `opensamguk-meta` repository's `reports/opensamguk/tasks/2026-09-05-handoff-han-map-track.md` §4; `docs/superpowers/specs/2026-09-05-han-supply-disconnection-safety-design.md`; the user approved the spec and requested remaining implementation, commit, PR and merge.

## Global Constraints

- Only `45277 宁阳县` moves from `PARENT-0028 山陽郡` to `PARENT-0024 東平國`.
- Preserve 1,524 provinces, 1,020 jurisdictions, 172 commanderies and all stable IDs/parent roster bytes.
- Province `owner`, terrain, coordinates, cities, county adjacency, seats and frozen V2/V3 runtime catalogs remain unchanged. `parentOwner`, commandery adjacency, commandery membership and related counts may change as deterministic consequences of this exact reparenting.
- Scenario province ownership assignments must remain identical across all 15 active scenarios; source hash metadata may change. No route node or runtime city is added, deleted or renumbered.
- Remove only resolved live disconnection `PARENT-0028@452:210` (33 cells); no synthetic edges, water crossings or geometry edits. All other component identities/verdicts remain unchanged.
- Preserve schema-1 legacy and schema-2 V3 supply decisions. Verify the real loader accepts both domains after the data update. Do not weaken source/parent/hash validation to make tests pass.
- Water geometry remains 2 zones, 0 edges/barriers/ports; strict base-dependent hashes must be regenerated.
- No raw source corpus, private inputs, secrets, server/DB operations or parallel Gradle writers.

### Task 1: Materialize 寧陽 parent correction with compatibility proof

**Files:**
- Modify `data/curated/han/jurisdiction-commandery-adjudications-v1.json` with one reviewed row.
- Modify `data/curated/han/territory-disconnection-adjudications-v1.json` only to remove the resolved live component; verify the actual ledger path in the audit module before editing.
- Tests: `tools/map/tests/test_build_tile_grid_parent_adjudications.py`, `tools/map/tests/test_materialize_province_jurisdictions.py`, `tools/map/tests/test_territory_disconnection_adjudications.py`.
- Exact digest expectations: `tools/map/tests/test_han_tiles_contract.py`, `infra/src/test/kotlin/opensamguk/infra/seed/HanStrategicTopologyJsonTest.kt`, `app/game-api/src/test/kotlin/opensamguk/gameapi/controller/MapPreviewControllerTest.kt`, and any existing snapshot tied to proven changed administrative inputs. Keep all strict assertions.
- Regenerate `data/map/han-tiles.json`, impacted administrative topology/reconciliation artifacts, scenario province ownership source binding, water base adjudication/artifact/strategic manifest, V3 world manifest and existing deterministic disconnection audit outputs.
- Document `docs/superpowers/research/2026-09-05-han-b1-ningyang.md` with source, exact changed fields, counts and unresolved B1 risks.

**Interfaces:** `apply_jurisdiction_parent_adjudications(document, adjudications_document) -> list[str]` mutates the document and validates exact IDs/names/current source-or-target/seat restrictions; `materialize_document` and `materialize_province_jurisdictions.py` reuse it. Existing generator already consumes the same ledger. Do not add a second parent override path.

- [ ] Read primary source `references/sources/shiliao/corpus/hhs-111.txt:85-101` under the meta root: 東平國 header and `〖-{寧}-陽〗故屬泰山。`. Read the handoff §4 warnings. Existing `宁阳县` is the exact current artifact name; source quotation uses traditional 寧陽.
- [ ] Add RED tests asserting the committed ledger contains precisely the new source/target row; apply it to a pristine in-memory source-parent fixture, require all intended parent surfaces change, and require idempotence, unknown/third-parent and name-drift rejection. Pin the resolved component absence and preserve other components. Example committed assertion:

```python
rows = json.loads(LEDGER.read_text(encoding="utf-8"))["adjudications"]
row = next((row for row in rows if row["jurisdictionId"] == "45277"), None)
self.assertIsNotNone(row)
self.assertEqual(("PARENT-0028", "PARENT-0024"),
                 (row["fromCommanderyId"], row["toCommanderyId"]))
```

- [ ] Run focused unittest modules before the ledger edit and record the behavioral RED, not an import/setup error.
- [ ] Add this row, using checked source references without committing the corpus:

```json
{
  "jurisdictionId": "45277",
  "jurisdictionNameCh": "宁阳县",
  "fromCommanderyId": "PARENT-0028",
  "fromCommanderyNameCh": "山陽郡",
  "toCommanderyId": "PARENT-0024",
  "toCommanderyNameCh": "東平國",
  "reviewState": "APPROVED_EXACT_PARENT",
  "evidenceRefs": [
    "shiliao:後漢書/卷111 郡國志 東平國 縣列「寧陽，故屬泰山」",
    "https://zh.wikisource.org/wiki/後漢書/卷111#東平國"
  ]
}
```

- [ ] Run `python3 tools/map/materialize_province_jurisdictions.py`; remove the now-stale 33-cell component row and recompute administrative outputs. Parse before/after JSON and reject any change outside the specified surfaces. Confirm the old component disappears and no replacement component appears; keep all other adjudications unchanged.
- [ ] Rebind measured tile hashes, then run existing builders for parent reconciliation, administrative topology, scenario ownership, water/strategic and V3 world manifests. Use CLI `--help` to confirm flags; do not run the full external source tile generator. Preserve pretty formatting where the existing artifact is pretty-printed.
- [ ] GREEN focused tests, then full `python3 -m unittest discover -s tools/map/tests -q` and `python3 -m unittest discover -s tools/scenario/tests -q`. Record total, skips and actual failures separately; preserve raw test output in ignored task evidence if possible.
- [ ] Run `materialize_province_jurisdictions.py --check`, `audit_territory_disconnections.py --check`, `build_han_parent_reconciliation.py --check`, `audit_han_admin_topology.py --check`, `build_scenario_province_ownership.py --check`, `build_han_water_topology.py --check`, `audit_han_water_topology.py --check`, `build_han_world.py --target han-world-v3 --check`, `apply_han_world.py --map han-world-v3 --check` and `audit_han_supply_disagreements.py --map han-world-v3 --check`. Use correct `tools/map` or `tools/scenario` paths.
- [ ] Parent serializes JDK 21 Gradle verification of `HanStrategicTopologyJsonTest`, `MapPreviewControllerTest` and `HanSupplyDisconnectionPolicyLoaderTest`; the last covers actual frozen/V3 loader data. Worker does not run Gradle. Report any stale exact pin to parent and rebind only after semantic proof.
- [ ] Write source/invariant/replay-domain evidence and task report, self-review, and return for independent task and whole-branch review before commit. Parent owns commit, PR, exact-head merge, issue updates and server operations. Do not claim 永 or 東部侯官 finished.
