# Han Province Fragment Adjudication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair only the five unambiguous, anchor-free, fully enclosed inland fragment cells in the committed Han spatial-province grid while preserving every anchor-bearing, maritime, lacustrine, multi-neighbor, and historically unresolved component for explicit review.

**Architecture:** Treat `data/map/han-tiles.json` as the immutable input shape and apply an idempotent, ledger-driven patch over its RLE ownership arrays. Validate every reassignment against the actual connected component, terrain, seat, and surrounding-province evidence before changing `owner`; derive `parentOwner` from the target province and rebuild both adjacency graphs from the patched grids. Keep `seatOwner`, geometry records, and all unresolved components unchanged.

**Tech Stack:** Python 3.11+, `unittest`, NumPy, existing `tools.map.build_terrain_grid` adjacency derivation.

**Adjudication criteria:** Repair only exact, seatless and anchor-free secondary components that are fully enclosed by one target province, touch no negative terrain, and preserve the eight-cell minimum. Defer every maritime, lacustrine, multi-neighbor, anchor-bearing, or historically unsupported case with explicit evidence rather than inferring a fill.

## Global Constraints

- Product map is only `han-world-v2` / `han`.
- Preserve exactly 1,524 spatial provinces, 1,020 jurisdictions, and 172 commanderies.
- Do not rebuild the canonical map with the historical generator chain.
- Every playable spatial province remains assigned to exactly one `jurisdictionId`.
- Every spatial province retains at least 8 cells in total.
- Do not infer ownership or geometry merely to remove visual holes.
- Do not modify `seatOwner` or the legacy gameplay namespace.

---

### Task 1: Ledger-driven fragment patcher

**Files:**
- Create: `tools/map/adjudicate_han_province_fragments.py`
- Create: `tools/map/tests/test_adjudicate_han_province_fragments.py`
- Create: `data/curated/han/province-fragment-adjudications-v1.json`

**Interfaces:**
- Consumes: committed Han tile document plus adjudication ledger entries containing source ID, target ID, exact `[col, row]` cells, terrain classes, surrounding IDs, and seat/negative-contact evidence.
- Produces: `materialize_document(document: dict, ledger: dict) -> dict`, an idempotently patched document with aligned `owner`, `parentOwner`, adjacency arrays, and metadata counts.

- [x] **Step 1: Write failing unit tests**

  Add literal synthetic fixtures proving that the patcher rejects a source-seat or other runtime-anchor component, sea/out-of-scope contact, multiple surrounding provinces, stale cell coordinates, and a target that would leave `parentOwner` inconsistent. Add a positive test proving that only an exact enclosed source component moves and that rerunning the patch is idempotent.

- [x] **Step 2: Run the focused tests and verify RED**

  Run: `python3 -m unittest tools.map.tests.test_adjudicate_han_province_fragments -v`

  Expected: import failure because `adjudicate_han_province_fragments.py` does not exist.

- [x] **Step 3: Implement the minimum patcher**

  Decode the compact grids, resolve IDs to stable province/parent indices, validate exact four-neighbor components and evidence, change only ledger cells, derive the target parent labels, rederive both adjacency graphs with existing terrain and commandery seats, update adjacency counts, and expose `--check`, `--source`, `--ledger`, and `--output` CLI arguments.

- [x] **Step 4: Run focused tests and verify GREEN**

  Run: `python3 -m unittest tools.map.tests.test_adjudicate_han_province_fragments -v`

  Expected: all fragment adjudication tests pass.

### Task 2: Canonical five-cell repair and topology snapshot

**Files:**
- Modify: `data/map/han-tiles.json`
- Modify: `data/curated/han/administrative-topology-audit-v1.json`
- Modify: `tools/map/tests/test_han_admin_topology_audit.py`
- Modify: `tools/map/tests/test_han_tiles_owner_locality.py` only if the independently measured locality baseline legitimately changes.

**Interfaces:**
- Consumes: Task 1 materializer and the three approved source→target decisions: `70741→70735` at `(306..308,241)`, `85634→85640` at `(487,211)`, and `95729→95724` at `(368,212)`. The `40957` component at `(423,391)` is deferred because it contains runtime place `32540` (`庐陵郡`).
- Produces: canonical grid where those three source provinces are connected, the five target cells have matching parent ownership, and all non-adjudicated cells remain byte-for-byte equal after RLE expansion.

- [x] **Step 1: Add a failing canonical contract test**

  Assert that `--check` succeeds, all three adjudicated source provinces have one component, all 29 deferred province IDs retain their pre-adjudication cell sets, record counts stay `1524/1020/172`, every province area stays at least eight cells, and `seatOwner` matches the pre-patch digest recorded in the ledger.

- [x] **Step 2: Verify the canonical test fails before materialization**

  Run: `python3 -m unittest tools.map.tests.test_adjudicate_han_province_fragments.HanProvinceFragmentCanonicalTest -v`

  Expected: failure because the five exact source cells still belong to the disconnected source components.

- [x] **Step 3: Materialize the canonical patch and audit snapshot**

  Run the patcher once against `data/map/han-tiles.json`, then run `tools/map/audit_han_admin_topology.py` to rewrite `administrative-topology-audit-v1.json`. Update only the independently measured topology-count assertions.

- [x] **Step 4: Verify the focused map contracts**

  Run the fragment tests, topology audit tests, owner/adjacency consistency tests, connectivity tests, and parent reconciliation tests. Confirm all pass; classify unrelated pre-existing warnings separately.

### Task 3: Repository verification and integration

**Files:**
- Modify: `docs/superpowers/plans/2026-09-03-han-province-fragment-adjudication.md` only to check completed steps.
- Create outside repository after merge: `reports/opensamguk/tasks/2026-09-03-map-province-fragment-adjudication.md`

**Interfaces:**
- Consumes: patched canonical map and adjudication ledger.
- Produces: reviewed commit, PR, merge SHA, successful main deployment evidence, runtime health/route checks, and a report naming every deferred classification and remaining risk.

- [x] **Step 1: Run full verification**

  Run the complete map test suite, scenario tests, type checks, production build, and canonical map/scenario audits required by `AGENTS.md` and the goal objective.

- [x] **Step 2: Review the diff independently**

  Run `codex review -c model=gpt-5.4 --uncommitted`, address every actionable finding through a new failing test, and rerun focused plus full verification.

- [ ] **Step 3: Commit, push, and merge a small PR**

  Commit with the required co-author footer, push the task branch, open the PR, wait for CI and review, merge only when green, and record the merge SHA.

- [ ] **Step 4: Validate deployment and report**

  Confirm main deployment, internal and public health/routes, and that no PEP promotion or reseed occurs unless a runtime image actually changed. Record results, files, commit/PR/merge, verification, deployment images, PEP status, health/routes, ledger decisions, risks, and the next smallest task in the metarepo report.
