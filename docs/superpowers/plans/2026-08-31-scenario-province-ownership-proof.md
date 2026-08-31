# Scenario Province Ownership Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Materialize and prove one explicit owner-or-unowned result for all 1,524 provinces in all 15 active scenarios, reject unnatural unexplained political fragments, and emit evidence-linked review maps.

**Architecture:** A reviewed claim document becomes the political source of truth. Focused Python modules validate claim/evidence identity, deterministically expand claims into 22,860 assignment rows, audit the canonical province graph, and render PNG/HTML review artifacts; the existing scenario seed source remains temporarily compatible through a byte-checked political projection. Runtime capture, administration, and movement are not changed in this pull request.

**Tech Stack:** Python 3.11 standard library, Pillow, JSON, `unittest`, existing `han-tiles.json` and scenario resources

**Spec:** `docs/superpowers/specs/2026-08-31-scenario-province-ownership-proof-design.md`

## Global Constraints

- Use only `han-world-v2` / `han` and the 1,524 stable province records in `data/map/han-tiles.json`.
- The active scenario set is exactly `1010, 1020, 1021, 1030, 1031, 1040, 1041, 1050, 1060, 1070, 1080, 1090, 1100, 1110, 1120`.
- Every scenario has exactly one explicit scenario-wide unowned baseline.
- Ownership may come only from typed reviewed claims; nearest-neighbour, surrounding majority, parent representative, runtime city sample, raster morphology, and renderer fill are forbidden.
- A broad administrative claim is valid only when its evidence and rationale expressly support that scope.
- Direct county and explicit unowned exceptions name the broad claims they override.
- Historical and IF claims remain distinct; scenario `1120` uses `IF_SCENARIO` changes.
- Unknown, unowned, wandering, and unrepresented territory remains unowned rather than receiving a convenient colour.
- Natural-looking borders are an audit outcome. An unexplained speck, spike, checkerboard run, enclave, hole, disconnected component, or narrow connector fails; the audit never rewrites ownership.
- Review maps use canonical scenario colours, black outside-map cells, neutral unowned land, fixed opacity, clipped texture, and legible province/parent borders.
- Generated JSON and images contain no timestamps or machine-specific paths.
- The first pull request does not add DB state, capture fan-out, commandery policies, province stats, runtime renderer cutover, or movement changes.
- Every production change follows red-green-refactor and ends in an independently reviewable commit with the required co-author trailer.

---

### Task 1: Claim contract and typed validation

**Files:**
- Create: `tools/scenario/province_ownership_contract.py`
- Create: `tools/scenario/tests/test_province_ownership_contract.py`
- Create: `tools/scenario/tests/fixtures/province_ownership/minimal_claims.json`

**Interfaces:**
- Consumes: decoded claim JSON, province IDs, parent-region IDs, active scenario metadata.
- Produces: `OwnershipDocument`, `ScenarioClaims`, `TerritoryClaim`, `Evidence`, `AuditAllowlistEntry`, and `OwnershipContractError(code, context)`.

- [ ] **Step 1: Write the failing contract tests**

```python
class ProvinceOwnershipContractTest(unittest.TestCase):
    def test_requires_one_unowned_baseline_per_active_scenario(self):
        raw = fixture("minimal_claims.json")
        raw["scenarios"][0]["claims"] = []
        with self.assertRaisesRegex(OwnershipContractError, "MISSING_UNOWNED_BASELINE"):
            parse_ownership_document(raw, catalog(), scenario_catalog())

    def test_rejects_unknown_references_before_materialization(self):
        raw = fixture("minimal_claims.json")
        raw["scenarios"][0]["claims"][1]["target"]["provinceIds"] = ["missing"]
        with self.assertRaisesRegex(OwnershipContractError, "UNKNOWN_PROVINCE"):
            parse_ownership_document(raw, catalog(), scenario_catalog())

    def test_nation_key_joins_exactly_one_scenario_nation(self):
        parsed = parse_ownership_document(fixture("minimal_claims.json"), catalog(), scenario_catalog())
        self.assertEqual(1, parsed.scenarios[1010].nation_ids["S1010-HAN"])
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `python3 -m unittest tools.scenario.tests.test_province_ownership_contract -v`

Expected: import failure for `tools.scenario.province_ownership_contract`.

- [ ] **Step 3: Implement the minimal immutable contract model**

```python
class OwnershipContractError(ValueError):
    def __init__(self, code: str, **context: object):
        self.code = code
        self.context = context
        super().__init__(f"{code}: " + ", ".join(f"{k}={v}" for k, v in sorted(context.items())))

@dataclass(frozen=True)
class TerritoryClaim:
    claim_id: str
    claim_kind: str
    owner_nation_key: str | None
    province_ids: tuple[str, ...]
    parent_region_ids: tuple[str, ...]
    evidence_ids: tuple[str, ...]
    overrides_claim_ids: tuple[str, ...]
    rationale: str
```

Implement exact active-scenario validation, unique IDs, exact nation-name joins, evidence types,
validity intervals, allowed claim kinds, target shape, override references, allowlist shape, and one
baseline per scenario. Keep parsing independent from materialization and Pillow.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `python3 -m unittest tools.scenario.tests.test_province_ownership_contract -v`

Expected: all contract tests pass.

- [ ] **Step 5: Commit the contract slice**

```bash
git add tools/scenario/province_ownership_contract.py \
  tools/scenario/tests/test_province_ownership_contract.py \
  tools/scenario/tests/fixtures/province_ownership/minimal_claims.json
git commit -m "feat: define province ownership claim contract" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 2: Lossless migration of the reviewed political source

**Files:**
- Create: `tools/scenario/migrate_han_ownership_claims.py`
- Create: `tools/scenario/tests/test_migrate_han_ownership_claims.py`
- Create: `data/curated/han/scenario-province-claims-v1.json`
- Modify: `tools/scenario/han_ownership.json` only if migration review finds a source defect; do not mechanically rewrite unrelated imperial or roster metadata.

**Interfaces:**
- Consumes: the 15 scenario sections, 187 nation entries, 1,091 commandery grants, six direct-city grants, seven wandering/unplaced entries, and basis text in `tools/scenario/han_ownership.json`.
- Produces: one curated claim document and `project_legacy_political_view(document)` for compatibility comparison.

- [ ] **Step 1: Write migration parity tests**

```python
class MigrateHanOwnershipClaimsTest(unittest.TestCase):
    def test_projection_preserves_every_legacy_political_decision(self):
        legacy = load_json(LEGACY)
        curated = migrate(legacy, load_map())
        self.assertEqual(15, len(curated["scenarios"]))
        self.assertEqual(187, sum(len(s["nationRefs"]) for s in curated["scenarios"]))
        self.assertEqual(1091, count_parent_grants(curated))
        self.assertEqual(6, count_direct_grants(curated))
        self.assertEqual(legacy_political_view(legacy), project_legacy_political_view(curated))

    def test_migration_assigns_stable_keys_once(self):
        first = migrate(load_json(LEGACY), load_map())
        second = migrate(load_json(LEGACY), load_map())
        self.assertEqual(canonical_bytes(first), canonical_bytes(second))
```

- [ ] **Step 2: Run the migration test and verify RED**

Run: `python3 -m unittest tools.scenario.tests.test_migrate_han_ownership_claims -v`

Expected: missing migration module.

- [ ] **Step 3: Implement the one-way migration and review projection**

Assign committed keys such as `S1030-N001` independently of runtime numeric nation IDs, retain the
exact scenario nation name as the compatibility join, create one baseline claim per scenario,
convert each `juns` entry to an `ADMIN_REGION_CONTROL` target by stable `parentRegionId`, convert
each direct city entry to the exact containing province, and turn each legacy `basis` into a
reviewable evidence row. Preserve the raw basis string and classify it without parsing ownership
from prose.

The migration command writes only when passed `--write`; normal CI calls `--check` and compares the
committed curated file plus political projection.

- [ ] **Step 4: Generate, inspect, and validate the curated file**

Run:

```bash
python3 tools/scenario/migrate_han_ownership_claims.py --write
python3 tools/scenario/migrate_han_ownership_claims.py --check
python3 -m unittest tools.scenario.tests.test_migrate_han_ownership_claims -v
```

Expected: 15 scenarios, 187 nation refs, 1,091 parent grants, six direct grants, seven explicitly
unplaced forces, no unknown parent/city/evidence reference, and an equal legacy political view.

- [ ] **Step 5: Manually review the high-risk rows**

Inspect scenario 1010 effective court control, 1030 Yanzhou, 1100/1110 Shangyong and Fangling,
all seven empty placements, and 1120 IF placement. Fix source claims rather than migration output
when a discrepancy is found.

- [ ] **Step 6: Commit the curated source**

```bash
git add data/curated/han/scenario-province-claims-v1.json \
  tools/scenario/migrate_han_ownership_claims.py \
  tools/scenario/tests/test_migrate_han_ownership_claims.py
git commit -m "data: migrate reviewed scenario territory claims" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3: Deterministic 22,860-row materializer

**Files:**
- Create: `tools/scenario/province_ownership_materializer.py`
- Create: `tools/scenario/build_scenario_province_ownership.py`
- Create: `tools/scenario/tests/test_province_ownership_materializer.py`
- Create: `data/map/han-scenario-province-ownership-v1.json`

**Interfaces:**
- Consumes: `OwnershipDocument`, stable province catalog order, scenario numeric nation resolution.
- Produces: `materialize_scenario(document, scenario_code) -> tuple[ProvinceAssignment, ...]`, canonical generated JSON, and `--check` drift status.

- [ ] **Step 1: Write failing precedence and determinism tests**

```python
def test_direct_county_override_beats_broad_parent_claim():
    assignments = materialize_scenario(yanzhou_fixture(), 1030)
    self.assertEqual("S1030-CAO", by_id(assignments)["JUANCHENG"].owner_nation_key)
    self.assertEqual("S1030-LU_BU", by_id(assignments)["PUYANG"].owner_nation_key)
    self.assertEqual(
        ("S1030-LU_BU-YANZHOU", "S1030-CAO-JUANCHENG"),
        by_id(assignments)["JUANCHENG"].claim_trace,
    )

def test_same_tier_conflict_fails_in_any_input_order():
    for fixture in conflicting_claim_orders():
        with self.assertRaisesRegex(OwnershipContractError, "CLAIM_CONFLICT"):
            materialize_scenario(fixture, 1010)

def test_all_scenarios_have_exactly_1524_rows():
    generated = materialize_all(load_curated_document())
    self.assertEqual(22860, sum(len(s.assignments) for s in generated.scenarios))
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `python3 -m unittest tools.scenario.tests.test_province_ownership_materializer -v`

Expected: missing materializer module.

- [ ] **Step 3: Implement explicit tier application**

```python
CLAIM_TIER = {
    "SCENARIO_BASELINE_UNOWNED": 0,
    "ADMIN_REGION_CONTROL": 1,
    "TEMPORAL_CARRY": 1,
    "PROVINCE_DIRECT": 2,
    "UNOWNED_EXPLICIT": 2,
    "IF_SCENARIO": 3,
}
```

Initialize all catalog provinces from the one baseline, expand exact typed targets, require named
override edges across tiers, reject conflicting same-tier assignments, and emit the winning claim,
complete trace, evidence IDs, derived confidence, and nullable `controllerCityId` in catalog order.

- [ ] **Step 4: Implement canonical CLI output and drift checking**

`python3 tools/scenario/build_scenario_province_ownership.py` writes atomically after all scenarios
pass. `--check` regenerates in memory and exits 1 on byte drift. Serialize with UTF-8, two-space
indent, stable list ordering, and a final newline; exclude timestamps and absolute paths.

- [ ] **Step 5: Generate and verify the complete artifact**

Run:

```bash
python3 tools/scenario/build_scenario_province_ownership.py
python3 tools/scenario/build_scenario_province_ownership.py --check
python3 -m unittest tools.scenario.tests.test_province_ownership_materializer -v
```

Expected: 15 scenarios, 1,524 assignments per scenario, 22,860 total, zero duplicate or unknown IDs.

- [ ] **Step 6: Commit the materializer**

```bash
git add tools/scenario/province_ownership_materializer.py \
  tools/scenario/build_scenario_province_ownership.py \
  tools/scenario/tests/test_province_ownership_materializer.py \
  data/map/han-scenario-province-ownership-v1.json
git commit -m "feat: materialize scenario province ownership" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4: Topology, hole, and natural-boundary audit

**Files:**
- Create: `tools/scenario/province_ownership_audit.py`
- Create: `tools/scenario/tests/test_province_ownership_audit.py`
- Modify: `tools/scenario/build_scenario_province_ownership.py`

**Interfaces:**
- Consumes: materialized assignments, `adjacency.county`, province cell areas decoded from `owner`, parent identities, and exact allowlist rows.
- Produces: `ScenarioOwnershipAudit` with typed fatal findings and non-fatal shape metrics.

- [ ] **Step 1: Write graph fixtures that fail for unexplained artifacts**

```python
def test_surrounded_unowned_component_is_a_hole():
    audit = audit_assignments(ring_graph(), ring_assignments(), allowlist=())
    self.assertEqual("UNALLOWLISTED_HOLE", audit.errors[0].code)

def test_isolated_owner_speck_requires_direct_evidence_or_allowlist():
    audit = audit_assignments(speck_graph(), inherited_speck(), allowlist=())
    self.assertIn("UNEXPLAINED_ISOLATED_COMPONENT", {e.code for e in audit.errors})

def test_directly_attested_enclave_is_preserved():
    audit = audit_assignments(speck_graph(), direct_claim_speck(), allowlist=())
    self.assertFalse(audit.errors)
```

Add fixtures for duplicate/missing assignments, disconnected components, minority parent splits,
alternating ownership, spike provinces, a narrow connector, exact allowlist matching, and rejected
wildcards.

- [ ] **Step 2: Run the audit tests and verify RED**

Run: `python3 -m unittest tools.scenario.tests.test_province_ownership_audit -v`

Expected: missing audit module.

- [ ] **Step 3: Implement graph identity and fatal audits**

Use only cardinal-edge entries from `adjacency.county`. Compute connected components in stable
province order. Detect surrounded unowned components, owned enclaves, secondary owner components,
minority parent splits, and one-province spikes. Direct evidence may explain an enclave but never a
missing assignment or conflict.

- [ ] **Step 4: Implement deterministic coherence metrics**

Compute component area from RLE cell counts, perimeter from adjacency edge-cell counts plus exterior
edges, perimeter/area, articulation provinces, degree, boundary owner transitions, and alternating
sequences. Emit metrics and typed review findings. Do not mutate assignments or turn a metric into
an owner selection.

- [ ] **Step 5: Gate generation on the audit**

The CLI prints a compact scenario table and refuses to replace JSON or images if any fatal finding
is not matched by an exact approved allowlist row.

- [ ] **Step 6: Run focused and full scenario Python tests**

Run:

```bash
python3 -m unittest tools.scenario.tests.test_province_ownership_audit -v
python3 -m unittest discover -s tools/scenario/tests -p 'test_*.py' -v
```

Expected: all tests pass and no existing seed/roster test changes its expectation.

- [ ] **Step 7: Commit the audit slice**

```bash
git add tools/scenario/province_ownership_audit.py \
  tools/scenario/tests/test_province_ownership_audit.py \
  tools/scenario/build_scenario_province_ownership.py
git commit -m "feat: audit scenario territory topology" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 5: Evidence-linked review maps and gallery

**Files:**
- Create: `tools/scenario/render_scenario_province_ownership.py`
- Create: `tools/scenario/tests/test_render_scenario_province_ownership.py`
- Create: `reports/generated/scenario-province-ownership/.gitkeep` only if repository policy requires retaining the generated output directory; otherwise keep review output out of git and attach it to the task report.
- Modify: `tools/scenario/build_scenario_province_ownership.py`

**Interfaces:**
- Consumes: canonical province owner grid, assignment artifact, scenario nation colours, audit report.
- Produces: 15 deterministic PNGs, one overview PNG, `index.html`, and per-province JSON evidence links in an explicit output directory.

- [ ] **Step 1: Write failing renderer contract tests**

```python
def test_renderer_uses_black_outside_and_neutral_unowned():
    image = render_fixture_map(fixture_grid(), fixture_assignments())
    self.assertEqual((0, 0, 0), image.getpixel((0, 0))[:3])
    self.assertEqual(UNOWNED_RGB, image.getpixel((1, 0))[:3])

def test_renderer_never_bleeds_fill_across_province_identity():
    image = render_fixture_map(two_province_grid(), two_owner_assignments())
    self.assertEqual(owner_a_rgb(), image.getpixel(PROVINCE_A_INTERIOR)[:3])
    self.assertEqual(owner_b_rgb(), image.getpixel(PROVINCE_B_INTERIOR)[:3])
```

Also assert deterministic hashes, complete legends, exact scenario metadata, parent-border strength,
and HTML links from a province/audit finding to its winning claim and evidence.

- [ ] **Step 2: Run the renderer tests and verify RED**

Run: `python3 -m unittest tools.scenario.tests.test_render_scenario_province_ownership -v`

Expected: missing renderer module.

- [ ] **Step 3: Implement a deterministic layered renderer**

Use Pillow with nearest-neighbour source pixels. Layer order is terrain, fixed-opacity political
fill clipped by province identity, subtle deterministic luminance texture clipped to the same
province, province edges, stronger parent edges, audit markers, then legend. Never blur or
antialias across political boundaries.

- [ ] **Step 4: Implement the static review gallery**

The gallery shows the overview and all 15 maps at identical bounds, includes owner/unowned counts,
lists fatal and review findings, and links each marked province to claim trace and evidence. Escape
all source text before HTML insertion.

- [ ] **Step 5: Generate and visually inspect all maps**

Run:

```bash
python3 tools/scenario/build_scenario_province_ownership.py \
  --review-output reports/generated/scenario-province-ownership
```

Open `reports/generated/scenario-province-ownership/index.html` in the in-app browser. Verify the
overview, 15 full-resolution maps, natural outer/frontier shapes, no unexplained rat-bite holes or
checkerboards, correct Yanzhou three-county exception, legible borders, and evidence navigation.

- [ ] **Step 6: Commit renderer code and tests**

Do not commit bulky review output unless existing repository policy requires it.

```bash
git add tools/scenario/render_scenario_province_ownership.py \
  tools/scenario/tests/test_render_scenario_province_ownership.py \
  tools/scenario/build_scenario_province_ownership.py
git commit -m "feat: render scenario ownership review maps" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 6: Historical regressions and repository gates

**Files:**
- Modify: `tools/scenario/tests/test_province_ownership_materializer.py`
- Modify: `tools/scenario/tests/test_province_ownership_audit.py`
- Modify: `tools/scenario/tests/test_apply_han_world.py`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: committed claims and artifact.
- Produces: permanent CI gates for historical exceptions, source drift, generated drift, and audit success.

- [ ] **Step 1: Add failing historical regression tests**

Pin stable province IDs for Juancheng, Fan, Dong'e, Shangyong, and Fangling from the catalog rather
than using array positions. Assert:

```python
self.assertOwner(1030, "鄄城县", "조조")
self.assertOwner(1030, "范县", "조조")
self.assertOwner(1030, "东阿县", "조조")
self.assertOwner(1100, "上庸县", "조비")
self.assertOwner(1110, "房陵县", "조예")
self.assertScenarioBasis(1120, "IF_SCENARIO")
```

Also assert scenario 1010 does not paint all nominal Han territory, wandering forces receive no
province, and the legacy city projection remains equal to `han_ownership.json`.

- [ ] **Step 2: Run the historical tests and verify meaningful RED**

Run:

```bash
python3 -m unittest \
  tools.scenario.tests.test_province_ownership_materializer \
  tools.scenario.tests.test_apply_han_world -v
```

Expected: fail on missing or incorrect explicit claims, not fixture setup.

- [ ] **Step 3: Correct only the reviewed claim source**

Add or repair direct claims and evidence. Do not patch generated assignments or renderer output.
Regenerate until the regression and audit gates pass.

- [ ] **Step 4: Add CI check commands**

CI runs migration projection check, materializer `--check`, audit tests, and renderer contract tests.
It does not regenerate and commit output implicitly.

- [ ] **Step 5: Run all relevant gates**

```bash
python3 tools/scenario/migrate_han_ownership_claims.py --check
python3 tools/scenario/build_scenario_province_ownership.py --check
python3 -m unittest discover -s tools/scenario/tests -p 'test_*.py' -v
python3 -m unittest discover -s tools/map/tests -p 'test_*.py' -v
./gradlew :infra:test --tests 'opensamguk.infra.seed.ScenarioJsonTest'
pnpm --dir web/game test -- provinceMap.test.ts HanMapCanvas.test.ts HanMapCanvas.interaction.test.tsx
pnpm --dir web/game typecheck
pnpm --dir web/game build
```

Expected: all commands pass from a clean worktree except the intentionally generated review-output directory.

- [ ] **Step 6: Commit historical gates and repaired claims**

```bash
git add data/curated/han/scenario-province-claims-v1.json \
  data/map/han-scenario-province-ownership-v1.json \
  tools/scenario/tests/test_province_ownership_materializer.py \
  tools/scenario/tests/test_province_ownership_audit.py \
  tools/scenario/tests/test_apply_han_world.py .github/workflows/ci.yml
git commit -m "test: gate scenario province ownership evidence" \
  -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 7: Browser review, task report, and integration handoff

**Files:**
- Create outside the Git worktree: `reports/opensamguk/tasks/2026-08-31-scenario-province-ownership-proof.md`
- Keep review images in the explicit generated report directory or copy them into the metarepo report tree according to existing report convention.

**Interfaces:**
- Consumes: clean generated artifact, full audit, browser screenshots, test/build logs, commits.
- Produces: review-ready branch and a report containing result, files, commits, verification, visual evidence, allowlists, remaining risk, and next smallest task.

- [ ] **Step 1: Re-run drift and full verification from the final commit**

Run every Task 6 command again after the last code/data change. Record exact command, exit code,
test count, and artifact hash. Do not reuse earlier green output after a later edit.

- [ ] **Step 2: Perform browser visual review**

Open the local gallery, capture the overview plus representative screenshots for 1010, 1030,
1100, and 1120, click evidence links, and verify that labels/audit markers stay within their own
province. Record any surface that cannot be checked automatically.

- [ ] **Step 3: Inspect repository state and diff**

```bash
git diff --check origin/main...HEAD
git status --short
git log --oneline origin/main..HEAD
```

Expected: only intended source, generator, tests, CI, spec, and plan changes; no generated cache or
machine-path file.

- [ ] **Step 4: Write the metarepo task report**

Include user-visible result, source and generated file paths, every commit SHA, verification output,
review image hashes/screenshots, all allowlist entries with evidence, unverified risks, and the next
smallest phase: durable controller-city capture bundles and renderer cutover.

- [ ] **Step 5: Request code review and follow the branch integration workflow**

Use the repository's small-PR process. Do not merge, deploy, or promote until CI and review pass.
After merge, record merge SHA; this data-only/review slice does not alter runtime political paint,
so deployment verification must explicitly state that no live renderer cutover was expected.
