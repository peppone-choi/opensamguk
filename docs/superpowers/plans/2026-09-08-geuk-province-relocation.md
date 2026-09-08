# Geuk Province Relocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Move the first proven misplaced province to its source coordinate without changing unrelated territory or supply policy.

**Architecture:** A deterministic local materializer records the source and output document hashes and exact reversible deltas. The existing fragment stage validates the restored prior state, then composes the local stage. Derived adjacency and downstream artifacts are regenerated only after structural checks pass.

**Tech Stack:** Python3, existing NumPy map helpers, unittest.

**Spec:** `docs/superpowers/specs/2026-09-08-geuk-province-relocation-design.md`.

## Global Constraints

Preserve all IDs, terrain, seatOwner, protected305/548, and owner cells outside the source25/destination82-cell union. Minimum province area8. Never synthesize movement or weaken existing adjudications. No commit before independent review.

## Task1: Deterministic local stage

Create `tools/map/relocate_han_province.py`, `tools/map/tests/test_relocate_han_province.py`, and `data/curated/han/province-relocations-v1.json`.

- [x] Write failing tests: projected target owner becomes703, new36/donor46 cells connected, old703 area completely removed, exact61delta, other territory/IDs unchanged, tampering rejected, second application identical.
- [x] Implement `relocate_document(document, ledger) -> dict`, `restore_document(document, ledger) -> dict`, deterministic delta generation and input/output fingerprints. Derive parentOwner and adjacency from owner.
- [x] Run `python3 -m unittest tools.map.tests.test_relocate_han_province`.

## Task2: Preserve fragment validation through composition

Modify `tools/map/adjudicate_han_province_fragments.py` only at its stage boundary.

- [x] Test restoration of the accepted input and rejection of a mutated prior fragment decision.
- [x] Compose prior fragment validation on exact restored input then reapply relocation. Keep every prior ledger row unchanged.
- [x] Run both fragment and relocation focused suites and both materializers with `--check`.

## Task3: Derive and verify runtime artifacts

- [x] Produce candidate han-tiles and run structural owner, locality, jurisdiction and territory gates before changing any dependent adjudication.
- [x] Regenerate water/strategic, runtime world, commandery supply, route candidates, scenario ownership, province raster and manifests using repository generators. For any changed provenance base, validate unchanged IDs/terrain and exact local delta first; preserve all adjudication rows.
- [x] Run existing map/scenario tests and check gates, including all15 seed supply baselines, supply disagreements and LAND physical adjacency. Render local before/after PNG for review.
- [x] Write report with results, verification, uncommitted status and remaining risk. Stop for independent review.

Independent review accepted the exact-state restoration, historical adjudication projection, and both fixed input/output fingerprint guards. A forged output with an extra reversible owner change was reproduced as a failing regression before the output fingerprint fix. Final validation results are recorded in the task report.
