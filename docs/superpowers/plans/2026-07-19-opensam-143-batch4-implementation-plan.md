# OPENSAM-143 Batch 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete OPENSAM-143 through OPENSAM-147 by producing a deterministic RTK14 refinement and scenario-generation pipeline, three ignored pilot scenarios, and a fresh-database seed proof.

**Architecture:** Keep source pages, refined officer data, and generated scenario JSON outside Git or under ignored paths. Commit only deterministic tools, mappings, the frozen officer registry/name mapping, JSON-compatible YAML manifests, tests, and evidence. The generator consumes only refined data plus reviewed manifests and emits the existing positional scenario schema.

**Tech Stack:** Python 3 standard library, Kotlin/JDK 21, Spring JDBC, Testcontainers PostgreSQL, existing `ScenarioJson`/`ScenarioImporter`.

## Global Constraints

- PHP `legacy/devsam-core` is grand truth for officer ranks; no golden, legacy, or `.env*` writes.
- `ScenarioImporter.kt` and `ScenarioJson.kt` remain unchanged.
- Raw pages, `refined/rtk14-officers.json`, reports containing source values, and `scenario_3*.json` are ignored and untracked.
- No new dependency: `.yaml` files use the JSON-compatible YAML 1.2 subset and are parsed with Python `json`.
- Officer joins use the complete `(birth, death, leadership, strength, intelligence, politics, charm)` fingerprint; ordinal joins are forbidden.
- Officer identity is stable-id keyed, not flat-name keyed; IDs are unique and contiguous `10001..11000`.
- Officer tuple mapping is `君主=12`, `太守=4`, `都督=0`, `一般=0`, `在野=0`. `0` becomes runtime general level `1` for affiliated officers through the existing importer. `都督` has no PHP role and is reported as a semantic downgrade.
- Every non-ruler city office is reported. `太守=4` is emitted only because PHP names level 4 `태수`; its missing `officer_city` semantics remains explicit evidence, not an invented value.
- Location mapping fails closed. The tracked 42 direct cities and 12 approved special remaps are required; `襄平→북평` and `建安→회계` are explicit nearest representable che-map remaps derived from the reviewed normalized RTK14 map coordinates.
- Generated general tuples have exactly 16 elements; nation tuples 9; diplomacy tuples 4.
- Output is UTF-8 JSON with stable key order, sorted officer IDs, two-space indentation, `ensure_ascii=false`, and a trailing newline.
- No commit, push, PR, merge, deploy, or Jira/GitHub mutation without separate human approval.

### Batch 4 v1 seed-projection boundary

- The ignored `data/scenarios/refined/rtk14-officers.json` produced here is a **v1 seed projection**, not a replacement for the full v2.1 refined master described in `docs/superpowers/specs/2026-07-18-scenario-system.md` §2.1. It contains the stable ID, source/page identity, Korean name, flat seven-field seed fingerprint, and scenario rows with `v1_rank` needed by the existing tuple importer.
- The v2-only `ideology`/`policy`/`traits`/`formations`/`tactics`/`portrait` fields and the portrait-column registry contract remain reserved for the v2 reconciliation. The cached full master and the generated projection are both ignored; this batch must not overwrite or claim to migrate the full master. A direct identity audit found the same 1,000 IDs and duplicate-name groups, so this projection introduces no current ID churn.
- The tracked Batch 4 registry intentionally freezes IDs by `(name_kanji, name_reading, page_key)` plus the seven-field fingerprint because portrait artifacts are outside this v1 seed boundary. Extending the pipeline beyond the three pilots is blocked on reconciling that registry with the full v2 contract.
- The `ScenarioSeedRunner` freeze has one OPENSAM-147 exception: parse the canonical `scenario_<number>` suffix and pass that numeric identity to the otherwise unchanged importer. `ScenarioImporter`, `ScenarioJson`, rank behavior, and positional tuple semantics remain unchanged.
- PHP-compatible unaffiliated rows (`在野`) remain in the base `general` array with nation `0`; `general_neutral` is the optional extended-NPC pool and stays empty. This is a documentation correction, not a rank-system change.
- The full v2.1 contract's active-row `faction == null` downgrade is not exercised by these pilots (zero rows at `190.1`, `200.1`, and `219.7`). Batch 4 deliberately fails closed instead of inventing a projection rule; T6 must reconcile that fallback before consuming a scenario that contains one.

---

### Task 1: OPENSAM-143 — deterministic source parsing and refinement

**Files:**
- Create: `tools/scenario/parse_pages.py`
- Create: `tools/scenario/refine_officers.py`
- Create: `tools/scenario/city_map.json`
- Create: `tools/scenario/location-remap.yaml`
- Create: `tools/scenario/officer-name-map.tsv`
- Create: `tools/scenario/name-join-overrides.tsv`
- Create: `tools/scenario/officer-id-registry.tsv`
- Create: `tools/scenario/tests/test_parse_pages.py`
- Create: `tools/scenario/tests/test_refine_officers.py`
- Modify: `.gitignore`

**Interfaces:**
- `parse_pages.parse_roster(html: str) -> list[dict]`
- `parse_pages.parse_officer_page(html: str, *, name_kanji: str, name_reading: str, page_key: str) -> dict`
- `parse_pages.collect_pages(roster_html: Path, page_cache: Path) -> list[dict]`
- `refine_officers.load_xlsx_rows(path: Path) -> list[dict]`
- `refine_officers.join_korean_names(raw: list[dict], xlsx_rows: list[dict]) -> tuple[list[dict], dict]`
- `refine_officers.refine(raw: list[dict], xlsx_rows: list[dict], existing_registry: list[dict]) -> tuple[list[dict], list[dict], dict]`

- [x] **Step 1: Add parser tests first.** Synthetic roster/page HTML must prove visible-kanji names, rare-kanji page keys, seven-field stats, all nine statuses, scenario `year_month/status/location/faction`, and absence of source labels in tracked fixtures.
- [x] **Step 2: Run RED.** `python3 -m unittest discover -s tools/scenario/tests -p 'test_parse_pages.py'` must fail because the module does not exist.
- [x] **Step 3: Implement the standard-library parser and polite cache CLI.** Network fetches require an explicit cache directory, `User-Agent`, minimum 1.0 second delay, bounded timeout/retry, and atomic cache writes; parse failures are reported and never fabricated.
- [x] **Step 4: Run parser GREEN.** The focused parser suite must report its exact test count and `OK`.
- [x] **Step 5: Add refinement tests first.** Cover shuffled-input byte identity, unique/ambiguous/unresolved fingerprints, registry preservation, registry drift rejection, IDs `10001..11000`, and a zero-fallback success report.
- [x] **Step 6: Run RED.** `python3 -m unittest discover -s tools/scenario/tests -p 'test_refine_officers.py'` must fail for the missing module/API.
- [x] **Step 7: Implement refinement and mapping output.** Join by the seven-field fingerprint, require exactly one XLSX candidate, key Korean mappings by stable ID, preserve existing registry IDs, and write reports with `unresolved`, `ambiguous`, `collisions`, and `semantic_downgrades` arrays. The independently adjudicated 22-row source drift is accepted only through `name-join-overrides.tsv`: exact `(name_kanji,name_reading,page_key)` and XLSX officer number/name, exactly one declared mismatching field, an unused candidate, and a final bijection are mandatory; no generic fuzzy fallback is allowed.
- [x] **Step 8: Add direct/remap maps.** Validate every target against `infra/src/main/resources/map/che.json`; duplicate keys or targets outside the 94-city catalog are fatal.
- [x] **Step 9: Run GREEN and deterministic checks.** Run the full scenario Python suite twice with shuffled fixtures and compare output SHA-256.
- [x] **Step 10: Run the real refinement.** Use `/Users/apple/Desktop/삼국지14 무장정보.xlsx` and ignored cached wiki pages, produce exactly 1,000 records, and require all fallback/collision counters to be zero before T2.

### Task 2: OPENSAM-144 — manifest contract and three pilots

**Files:**
- Create: `tools/scenario/manifest.py`
- Create: `tools/scenario/defaults.json`
- Create: `tools/scenario/manifests/scenario_3190.yaml`
- Create: `tools/scenario/manifests/scenario_3200.yaml`
- Create: `tools/scenario/manifests/scenario_3219.yaml`
- Create: `tools/scenario/tests/test_manifest.py`

**Interfaces:**
- `manifest.load_manifest(path: Path) -> dict`
- `manifest.validate_manifest(data: dict, refined: list[dict], city_names: set[str]) -> dict`
- `manifest.derive_nation_defaults(lord_id: int, members: list[dict], ordinal: int) -> dict`

**Reviewed defaults:**
```json
{
  "palette": ["#8B0000", "#1F4E79", "#548235", "#7030A0", "#C55A11", "#7F6000", "#44546A", "#A64D79", "#2F5597", "#BF9000", "#38761D", "#674EA7"],
  "scale_by_member_count": [[40, 8], [25, 7], [16, 6], [10, 5], [6, 4], [3, 3], [2, 2], [1, 1]],
  "resources_by_scale": {"1": [3000, 3000, 250], "2": [4500, 4500, 400], "3": [6000, 6000, 550], "4": [7500, 7500, 700], "5": [9000, 9000, 900], "6": [10000, 10000, 1100], "7": [11000, 11000, 1300], "8": [12000, 12000, 1500]},
  "ideology": "중립",
  "diplomacy": {"neutral_state": 2, "neutral_term": 0, "war_state": 0, "war_term": 0}
}
```

- [x] **Step 1: Add manifest validation tests first.** Reject code/number/startYear mismatch, duplicate or unknown lord IDs, uncovered active factions, invalid colors/resources/scales, invalid diplomacy references/state/term, city overlap, missing/non-owned capital, and name-based ambiguous overrides.
- [x] **Step 2: Run RED.** The focused test must fail because `manifest.py` is absent.
- [x] **Step 3: Implement the JSON-compatible YAML loader and validation.** `lord_id` is the sole required nation field; name/color/resources/tech/ideology/scale/cities derive deterministically.
- [x] **Step 4: Implement deterministic city ownership.** A lord's city wins; otherwise highest member count wins; ties use lowest lord ID. If multiple lords' distinct source locations collapse onto one che city, member count descending then lord ID ascending chooses the winner; each loser receives the nearest unowned city by BFS over `cities_1010.json` connections, with city ID ascending as tie-break. Other unique lord cities are reserved before relocation, general source locations remain unchanged, and relocation is reported. Each nation receives a capital first and no city appears in two nations.
- [x] **Step 5: Run GREEN.** Report the exact test count and `OK`.
- [x] **Step 6: Generate and review three manifests.** List every active faction by ruler stable ID for `190.1`, `200.1`, and `219.7`; use only custom Korean titles and year-month keys.

### Task 3: OPENSAM-145 — pure deterministic scenario builder

**Files:**
- Create: `tools/scenario/build_scenario.py`
- Create: `tools/scenario/tests/test_build_scenario.py`

**Interfaces:**
- `build_scenario.build(refined: list[dict], manifest: dict, city_map: dict[str, str], remap: dict[str, str], name_map: dict[int, str], defaults: dict) -> tuple[dict, dict]`
- `build_scenario.dump_scenario(scenario: dict) -> bytes`
- `build_scenario.validate_scenario_shape(scenario: dict) -> None`

Expected root shape:
```python
{
    "title": manifest["title"],
    "startYear": manifest["startYear"],
    "life": 1,
    "fiction": 0,
    "map": {"mapName": "che"},
    "const": {"defaultMaxGeneral": 600},
    "nation": nation_tuples,
    "stored_icons": {".": stable_id_icon_map},
    "general": affiliated_and_unaffiliated_tuples,
    "general_ex": [],
    "general_neutral": [],
    "diplomacy": diplomacy_tuples,
}
```

- [x] **Step 1: Add builder tests first.** Cover 16/9/4 tuple sizes, officer mapping, ruler uniqueness, neutral separation, unknown status/faction/name/location failure, city collision resolution, source-label leakage rejection, and shuffled-input byte identity.
- [x] **Step 2: Run RED.** The focused test must fail because the builder is absent.
- [x] **Step 3: Implement minimal pure transformations.** No network, clock, random, Python-hash order, or raw-source access is allowed.
- [x] **Step 4: Run GREEN and SHA checks.** Two normal runs and one shuffled-input run must produce identical bytes and SHA-256.

### Task 4: OPENSAM-146 — generate and gate three actual pilots

**Files:**
- Create ignored outputs: `data/scenarios/scenario_3190.json`, `data/scenarios/scenario_3200.json`, `data/scenarios/scenario_3219.json`
- Create ignored evidence: `data/scenarios/reports/scenario_{3190,3200,3219}-report.json`
- Create: `tools/scenario/verify_pilots.py`
- Create: `tools/scenario/tests/test_verify_pilots.py`

**Interfaces:**
- `verify_pilots.verify(scenario: dict, report: dict, refined: list[dict], manifest: dict, che_cities: set[str]) -> list[str]`

- [x] **Step 1: Add five-gate verifier tests first.** Exact active count, representative stable-ID affiliation/location, unresolved location zero, Korean-name fallback zero, and tuple/schema validity are independent failures.
- [x] **Step 2: Run RED, implement verifier, then GREEN.** The test evidence must show the verifier catches each deliberately broken fixture.
- [x] **Step 3: Generate all pilots twice.** Require byte-identical outputs and exact affiliated counts `249`, `304`, `370`; report neutral and importer-eligible totals separately.
- [x] **Step 4: Verify representative officers.** Record stable ID, source faction/location, emitted nation/city, and officer level for 조조·원소·유비·손권·여포·주유 when present.
- [x] **Step 5: Confirm ignore boundary.** `git status --short -- data/scenarios` must be empty and `git check-ignore` must identify the explicit ignore rule.

### Task 5: OPENSAM-147 — fresh database seed proof

**Files:**
- Modify: `app/game-engine/src/main/kotlin/opensamguk/engine/boot/ScenarioSeedRunner.kt`
- Modify: `app/game-engine/src/test/kotlin/opensamguk/engine/boot/ScenarioMapSeedIT.kt`
- Create: `app/game-engine/src/test/resources/scenario/scenario_3190_test.json`

**Interfaces:**
- `SeedBootstrap.scenarioNumber(): Int` parses exactly `scenario_(0|[1-9]\\d*)` and rejects non-canonical or negative codes before writes, preserving the supported `scenario_0` blank-game path.
- `SeedBootstrap.ensureSeeded(jdbc)` passes both `scenarioCode` and the parsed `scenarioNumber` to the unchanged importer.

- [x] **Step 1: Add failing identity/idempotency IT first.** A synthetic `scenario_3190_test` external file must seed a fresh database, write `world_state.scenario_code=scenario_3190`, write `ng_games.scenario=3190`, return false on the second call, and keep all counts unchanged.
- [x] **Step 2: Run RED.** Confirm the existing bootstrap stores `1010`, proving the test catches the defect.
- [x] **Step 3: Apply the one-line semantic wiring.** Parse the numeric suffix and pass `scenarioNumber`; do not change importer/decoder logic.
- [x] **Step 4: Add orphan/capital/count assertions.** Require zero missing nation/city/general-turn/rank/diplomacy references and every capital owned by its nation.
- [x] **Step 5: Run GREEN with Docker.** Verify `BUILD SUCCESSFUL` and XML `failures=0 errors=0 skipped=0` for the targeted IT.
- [x] **Step 6: Run local actual-pilot seed.** Point the test/bootstrap at ignored `scenario_3190.json`; compare row counts to its build report.

### Task 6: branch-wide verification and independent review

**Files:**
- Create: `docs/superpowers/reviews/2026-07-19-opensam-143-batch4-review.md`
- Update: `.ai/current-state.md` only if its current owner is released; otherwise write the result in the batch review and leave `.ai/*` untouched.

- [x] **Step 1: Run `scripts/agent/verify-changes.sh --run`.** Record every executed and unexecuted gate separately.
- [x] **Step 2: Run Python suites.** `python3 -m unittest discover -s tools/scenario/tests -p 'test_*.py'` must report exact count and `OK`.
- [x] **Step 3: Run JVM suites.** Run `:infra:test` and `:app:game-engine:test` with JDK 21 and `--rerun-tasks`; inspect XML, not only exit code.
- [x] **Step 4: Run backend parity gate.** `tools/parity/gate.sh backend` must finish with XML green.
- [x] **Step 5: Run static checks.** `git diff --check` and `tools/agent-system/check.py --strict --base origin/main` must be clean.
- [x] **Step 6: Dispatch an independent adversarial reviewer.** Verdict must be `cleared`; all Critical/Important findings require fixes and re-review.
- [x] **Step 7: Stop before Git mutations.** Present the uncommitted diff and evidence; do not stage/commit/push without explicit approval.
