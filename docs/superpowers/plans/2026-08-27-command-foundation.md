# Public Alpha Command Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Freeze one machine-readable public-alpha command contract, inventory the legacy and planned command families without false implementation claims, and make Stage 0 closure mechanically verifiable.

**Architecture:** A reviewed JSON catalog is the command source of truth. A small Python validator checks its schema, identities, aliases, lifecycle, legacy coverage, and ticket-owned counts against the checked-in Kotlin menu and extracted legacy evidence. Human-readable contract and catalog notes explain decisions that cannot live in JSON. Runtime loaders remain Stage 1 work: this plan must not silently change command execution, reservation rings, logs, or fallback behavior.

**Tech Stack:** JSON, Python 3 standard library, `unittest`, Kotlin source evidence, Markdown.

**Spec:** `docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md`

## Scope and ticket order

1. `OPENSAM-73` / GitHub #215 — revise P-1 through P-7.
2. `OPENSAM-74` / GitHub #216 — revise P-8 through P-14.
3. `OPENSAM-75` / GitHub #217 — revise P-15 and Stage 0 gates after 1–2.
4. `OPENSAM-76` / GitHub #218 — freeze the 46 personal and 24 chief legacy menu adapters.
5. `OPENSAM-77` / GitHub #219 — freeze approved new command families and dispositions.
6. `OPENSAM-30` / GitHub #172 — close the umbrella only after 1–5 have passing evidence.

## Invariants

- The historical 2026-07-12 and 2026-08-16 documents remain evidence; this plan supersedes their realtime, 3D-first, PHP-oracle-everywhere, and release-tail onboarding assumptions.
- `GameConst.availableGeneralCommand` contains 46 menu entries and `availableChiefCommand` contains 24. OPENSAM-76 requires one canonical adapter row per surface. A row may reference a shared normalized intent, but distinct legacy payload, cost, RNG, log, and result contracts are never collapsed.
- `data/extracted/commands/constraints.json` contains 93 legacy PHP-derived commands. It is provenance evidence, not the public menu or public-alpha catalog.
- Aliases never count as separate implementations. Unknown identifiers fail validation closed. Removed commands name a replacement and migration policy.
- A catalog row's delivery state is evidence, not intent. Stage 0 may set only `DOMAIN_READY`; later states require their named implementation surfaces.
- Runtime `CommandRegistry`, reservation rings, persistence, logs, and result behavior do not change in this Stage 0 plan.
- Help, tutorial, AI, replay, recovery, and documentation disposition are fields of each canonical command, never release-tail placeholders.

---

### Task 1: Freeze the revised P-1 through P-15 contract

**Files:**
- Create: `docs/superpowers/specs/2026-08-27-public-alpha-command-contract-freeze.md`
- Reference: `docs/superpowers/specs/2026-08-16-v2-contract-freeze-p1-p15.md`
- Reference: `docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md`

**Interfaces:**
- Defines revised P-1–P-15, explicitly mapping each old decision to `PRESERVE`, `REVISE`, or `REPLACE`.
- Defines the canonical row schema, five command layers, WEGO result/replay boundary, and Stage 0 exit gates.

- [ ] Write a decision crosswalk for all P-1–P-15 entries; no item may disappear without a replacement reference.
- [ ] Replace realtime `BattleSession` timing with sealed deterministic WEGO rounds and real-time-like replay.
- [ ] Replace four old layers with `PERSONAL_RING`, `CHIEF_RING`, `PERSISTENT_PLAN`, `BATTLE_ROUND`, and `SYSTEM_RESOLVER`.
- [ ] Preserve legacy ring position, logs, RNG order, and results behind explicit aliases/adapters.
- [ ] Make help/tutorial/AI/replay fields mandatory and define `N/A` as a reason-bearing disposition, not a blank value.
- [ ] Limit legacy-oracle comparison to commands and surfaces that actually have a preserved legacy contract.
- [ ] Define P-15 as mechanical Stage 0 gates consumed by the validator in Task 2.
- [ ] Verify no normative realtime, 3D-first, invite-alpha, late-onboarding, or calendar-deadline premise remains.

**Verification:**

```bash
rg -n 'P-[1-9]|P-1[0-5]' docs/superpowers/specs/2026-08-27-public-alpha-command-contract-freeze.md
rg -n '실시간 전투|3D 기본|초대 알파|출시 후 도움말|TBD|TODO' docs/superpowers/specs/2026-08-27-public-alpha-command-contract-freeze.md
```

Expected: all fifteen P identifiers are present; the stale-premise search has no normative match.

### Task 2: Add the catalog schema validator using TDD

**Files:**
- Create: `tools/commands/tests/test_public_alpha_command_catalog.py`
- Create: `tools/commands/validate_public_alpha_command_catalog.py`
- Create: `data/commands/public-alpha-command-catalog.json`

**Interfaces:**
- `load_catalog(path: Path) -> dict`
- `validate_catalog(catalog: dict, repo_root: Path) -> list[str]`
- CLI exits 0 with a concise count summary, or non-zero with every validation error.

- [ ] RED: write tests for missing required row fields, duplicate canonical IDs, alias collisions, unknown replacements, replacement cycles, invalid lifecycle states, lifecycle evidence overclaim, and unknown identifier lookup.
- [ ] Run `python3 -m unittest tools.commands.tests.test_public_alpha_command_catalog`; confirm failures because the validator does not exist.
- [ ] GREEN: implement the minimum JSON loader and pure validator using only the Python standard library.
- [ ] Add a minimal valid catalog fixture in the test module and make the unit tests pass.
- [ ] Add the real catalog envelope with schema version, source references, allowed enums, and an initially empty `commands` list.
- [ ] Run the validator against the empty real catalog and confirm it fails the Stage 0 count gates rather than accepting a placeholder.

**Verification:**

```bash
python3 -m unittest tools.commands.tests.test_public_alpha_command_catalog
python3 tools/commands/validate_public_alpha_command_catalog.py data/commands/public-alpha-command-catalog.json
```

Expected: unit tests pass; the empty catalog CLI fails with explicit missing legacy and planned-family counts.

### Task 3: Inventory legacy menu adapters for OPENSAM-76

**Files:**
- Modify: `data/commands/public-alpha-command-catalog.json`
- Modify: `tools/commands/tests/test_public_alpha_command_catalog.py`
- Create: `docs/superpowers/specs/2026-08-27-public-alpha-command-catalog-notes.md`
- Reference: `common/src/main/kotlin/opensamguk/common/constants/GameConst.kt`
- Reference: `logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt`
- Reference: `data/extracted/commands/constraints.json`

**Interfaces:**
- Each legacy menu surface records `legacySurface`, `legacyCode`, `sourceRing`, `adapterPolicy`, `parityStatus`, and provenance.
- Contextual aliases resolve to exactly one canonical ID; `general_turn:휴식` and `nation_turn:휴식` retain distinct ring contracts, while unqualified `휴식` is rejected as ambiguous.

- [ ] RED: add tests that parse the two Kotlin menu blocks and require exactly 46 personal plus 24 chief surface mappings.
- [ ] Add a test requiring every non-rest menu code to exist in `CommandRegistry` and every catalog legacy code to name its Kotlin or extracted provenance.
- [ ] Populate the reviewed legacy rows and make the exact coverage tests pass.
- [ ] Record ambiguous mergers as separate canonical rows unless cost, authority, RNG, log order, and effects are demonstrably identical.
- [ ] Document the 70 one-to-one canonical adapter rows, reviewed normalized-intent references, and the separate 93-command extracted evidence set.
- [ ] Keep every legacy row at `DOMAIN_READY`; do not infer UI, AI, help, tutorial, or replay completion from existing code alone.

**Verification:**

```bash
python3 -m unittest tools.commands.tests.test_public_alpha_command_catalog
python3 tools/commands/validate_public_alpha_command_catalog.py data/commands/public-alpha-command-catalog.json --gate legacy
./gradlew :logic:test --tests opensamguk.logic.actions.CommandContractMatrixTest
```

Expected: 46/24 menu surfaces are covered exactly; registry contract tests pass unchanged.

### Task 4: Inventory approved new families for OPENSAM-77

**Files:**
- Modify: `data/commands/public-alpha-command-catalog.json`
- Modify: `tools/commands/tests/test_public_alpha_command_catalog.py`
- Modify: `docs/superpowers/specs/2026-08-27-public-alpha-command-catalog-notes.md`
- Reference: `docs/superpowers/specs/2026-08-27-public-alpha-rebaseline-design.md`

**Interfaces:**
- Rows cover identity, office, travel, operations, subordinate people, Bugok, governance, WEGO land/siege/naval, and system/admin families.
- Every planned row owns an implementation issue, declared layer, replay policy, AI policy, help topic, tutorial objective or reason-bearing N/A, and delivery state.

- [ ] RED: add family-presence tests for every family listed in rebaseline design §2.
- [ ] Add tests forbidding old `TACTICAL`/realtime layers and requiring land, siege, and naval WEGO dispositions.
- [ ] Add tests requiring a non-empty owner issue and preventing `VERIFIED` until all lifecycle evidence fields are populated.
- [ ] Populate planned canonical rows without inventing aliases or claiming implementation.
- [ ] Mark unsettled command names as explicit `contractStatus: PROVISIONAL` and make the full Stage 0 gate reject them.
- [ ] Resolve all provisional identities through linked owner tickets before Stage 0 closure.

**Verification:**

```bash
python3 -m unittest tools.commands.tests.test_public_alpha_command_catalog
python3 tools/commands/validate_public_alpha_command_catalog.py data/commands/public-alpha-command-catalog.json --gate planned
rg -n 'TACTICAL|realtime|실시간 전투|TODO|TBD' data/commands/public-alpha-command-catalog.json docs/superpowers/specs/2026-08-27-public-alpha-command-catalog-notes.md
```

Expected: planned-family validation passes; the stale-term search returns no normative catalog row or unresolved placeholder.

### Task 5: Close the Stage 0 evidence gate for OPENSAM-75 and OPENSAM-30

**Files:**
- Modify: `tools/commands/tests/test_public_alpha_command_catalog.py`
- Modify: `docs/superpowers/specs/2026-08-27-public-alpha-command-catalog-notes.md`
- Create: `reports/opensamguk/tasks/2026-08-27-opensam-30-command-contract-freeze.md` (meta-repository, outside this Git worktree)

**Interfaces:**
- `--gate stage-0` proves all contract rows are final, all legacy surfaces and planned families are owned, aliases/replacements are closed, and no lifecycle state is overclaimed.
- GitHub and Jira receive the same dependency-ordered evidence links and dispositions.

- [ ] RED: add an end-to-end test showing one provisional row, missing ticket, blank help/tutorial disposition, or alias collision fails `stage-0`.
- [ ] Resolve every failure in the catalog and notes; do not weaken the gate.
- [ ] Run the complete Python and focused Kotlin verification suites.
- [ ] Check changed docs for broken relative links and stale premises.
- [ ] Review `README.md`, module docs, `CLAUDE.md`, and `AGENTS.md`; record `docs-impact: none` with reasons for untouched files.
- [ ] Commit the repository changes with the required co-author trailer.
- [ ] Open a PR, wait for required CI, and merge only after green review evidence.
- [ ] Update GitHub #215–#219 and Jira OPENSAM-73–77 in dependency order, then close GitHub #172 and Jira OPENSAM-30 only if every child is done.
- [ ] Write the task report with result, commit and merge hashes, exact validation commands, external mutations, and remaining risks.
- [ ] Remove the clean worktree through `bin/finish-task`; never remove it automatically if dirty.

**Verification:**

```bash
python3 -m unittest tools.commands.tests.test_public_alpha_command_catalog
python3 tools/commands/validate_public_alpha_command_catalog.py data/commands/public-alpha-command-catalog.json --gate stage-0
./gradlew :logic:test --tests opensamguk.logic.actions.CommandContractMatrixTest
git diff --check
git status --short
```

Expected: every command validation and legacy contract test passes, diff check is empty, and the branch contains only planned Stage 0 artifacts.
