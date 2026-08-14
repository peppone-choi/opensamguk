# OPENSAM-149 full rehydrate turn-gate review

Date: 2026-08-13
Reviewer: independent read-only `lazycodex-code-reviewer`
Scope: `app/game-engine/` bounded `N -> discard/reload -> N+1` restart equivalence and its focused integration gate
Historical claimed source SHA: `a9a167881c86c8d2458baec985027c2d1134ef10` (unreachable; superseded below)
Exact base SHA: `f4ee9135ad6cbce1c6cfb28f7113d7742f478282`
Historical verdict: invalid as merge evidence because its claimed source SHA is not reachable

## Scope reviewed

- `app/game-engine/src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt`
- `app/game-engine/src/test/kotlin/opensamguk/engine/boot/FullRehydrateTurnGateIT.kt`
- `docs/loops/opensam-149-rehydrate-gate/LEDGER.md`

The review covers the bounded `N -> discard/reload -> N+1` restart-equivalence gate. It does not
infer all-channel rehydration coverage.

## Findings and resolution

The first review blocked because the later architecture run had replaced the game-engine test-results
directory, so no focused XML remained for direct inspection. The exact source SHA was rerun after the
shared JVM slot was reacquired. The reviewer directly inspected the retained post-commit XML and
cleared the blocker.

No source defect or remaining fix-required finding was identified. The integration test's size is a
nonblocking maintainability watch: its behavior coverage is real and non-tautological, using
Testcontainers PostgreSQL, Flyway, the live turn runner, JDBC flush, and a fresh snapshot loader.

## Evidence inspected

- Focused JDK 21 `--rerun-tasks` command: `BUILD SUCCESSFUL in 19m 47s`, 17 tasks executed.
- Retained `FullRehydrateTurnGateIT` XML mtime: `2026-08-13T18:10:10+0900`.
- Focused XML: `tests=1`, `skipped=0`, `failures=0`, `errors=0`; Testcontainers connected, Flyway
  migrated, and five `WorldSnapshotLoader` loads were recorded.
- Earlier serialized architecture XML: `DaemonNoEntityManagerTest` and
  `InfraNoEntityManagerTest` each `tests=1`, `skipped=0`, `failures=0`, `errors=0`.
- Exact source diff check: clean.

## Explicit quarantine

- EventStore event insert/delete is not covered.
- Resident general/nation allocator continuation is not covered.
- Diplomacy-letter allocator continuation is not covered.
- Same-due-tick intake visibility remains a separate follow-up.
- No all-channel or all-allocator completion claim is made.

The broader backend parity gate remains host-blocked by unrelated Testcontainers startup failures and
is not represented as green.

## Decision

APPROVED for the bounded full-rehydrate turn-gate scope above. No merge or deployment authorization
is implied.

## Exact-source revalidation after PR review remediation

The preceding section is retained as history, but its unreachable `a9a167...` identifier must not be
used as merge evidence. The durable source reviewed for this PR is commit
`85c79bee6b9d93961997b794ba4a63188081c5e0`, based on
`f4ee9135ad6cbce1c6cfb28f7113d7742f478282`. Later documentation-only commits are bound to the
same implementation by the immutable `app/game-engine` tree object, not by a moving worktree.

- Exact `app/game-engine` tree: `4ee085c4e8ed57df8d0dd3acb80cdc415a528e15`.
- Exact boot-test directory tree: `2b5264313c2c328fb38341ba3a9be659b785230b`.
- Canonical reviewed-path `git ls-tree` SHA-256:
  `daca0808b2440dea0d001618b1aede9b59d74c72f10936bf57504167cc825a61`.
- Production loader blob: `badd8ec7433e50c11c2c435a475bd2091d9dad9d`, 28,119 bytes,
  SHA-256 `8dbff3e8f259ce8eacdb6bca1a412c4384dbfb236b64f003aae29ef45fc90366`.
- Test-support blobs:
  - fixture factory `5ed5573013815d123e67fa3a97b4a348a49373d2`, 4,278 bytes,
    SHA-256 `19250da56dc1e5a811b14002004c46939be1b49ac6b434a360d0bb03fa31d552`;
  - flush assertions `54e645f8fa3b11a809b55d1a1562348cfeb0a568`, 2,000 bytes,
    SHA-256 `2f5fd18d02912584638c55e2678fe8489304d48c1ced7dac877bebe5eae41b8a`;
  - hot-state signature `0f1dc28042eba5cdeadb2ee82b8c0e94789498e8`, 1,305 bytes,
    SHA-256 `b1dd702d09ddafc0335ab8dc41c620f8f6f5b1d6120ea6099c1635f5ecdc776d`;
  - persistence signatures `12089aad75b97b47ff67609699e4e07b7569f62e`, 7,016 bytes,
    SHA-256 `456125dc8c1e0ebd2dfa2f9b518c0e6b5092eb06fbce0ace89db65bc6a8db487`;
  - gate orchestration `7a979125021a40fe78af7b9b6cbfb250dfb7e1ca`, 9,251 bytes,
    SHA-256 `b14026b0d0450d36005e42a309a492a76ed9c3f253c3d8a3a4b3be110249dca5`;
  - world seeder `fb37c2d8cd6fd0d44ebb97a7843659c20fa072d7`, 5,105 bytes,
    SHA-256 `369e7c81d62bcd1187186056ac3e35f7bc71eeab0c26fbc90eef57a281b54cec`.

The formerly monolithic integration test is now split by fixture, seeding, durable-query,
flush-assertion, and hot-signature responsibilities. The six files measure 92, 51, 28, 172, 165,
and 126 pure LOC respectively. The exact scenario still has one test, one Docker availability
assumption, and four `runTick` calls.

### Replayed verification

- Focused JDK 21 gate on the immutable source tree: `BUILD SUCCESSFUL in 3m 9s`, 17 actionable
  tasks; XML `tests=1`, `skipped=0`, `failures=0`, `errors=0`, SHA-256
  `a08f6924a28f26f3bc088183cfc4053770fc5db8e2ffcb4b5eff0682b4417946`.
- Serialized architecture gate: `BUILD SUCCESSFUL in 16m 59s`, 20 tasks executed.
  `DaemonNoEntityManagerTest` and `InfraNoEntityManagerTest` each report `tests=1`, `skipped=0`,
  `failures=0`, `errors=0`. Because Gradle replaces a module's focused test-results directory,
  the focused and daemon XML files were copied to isolated `/tmp` evidence immediately after their
  runs and then replayed together for simultaneous inspection. Their SHA-256 values from the isolated
  copies are respectively
  `a08f6924a28f26f3bc088183cfc4053770fc5db8e2ffcb4b5eff0682b4417946` and
  `179347937470b01234a15f960e54010b7348e4c75adb40470a681ddc944a4fe0`; the infra XML SHA-256 is
  `ddc84313bda84968449a3661863408539cd1a4619f4245b3df2901061c73b5c6`.
- Final simultaneous engine replay ran the focused gate and `DaemonNoEntityManagerTest` in one JDK 21
  invocation: `BUILD SUCCESSFUL in 1m 19s`. Both XML files are simultaneously present with
  `tests=1`, `skipped=0`, `failures=0`, `errors=0`; their final SHA-256 values are
  `a9f137c1472f6545251cd3049b913edda24ed860c8237dc243e5bd3ebaba6e5d` and
  `b8abc27c64bea97ba92057a88ca8e974a3940011b6331558cf074d84798d70d5`.
- `tools/agent-system/check.py --strict --base origin/main --format json` returned `ok: true` with
  zero findings; `git diff --check` is clean.

The independent functional review found no loader correctness, world-isolation, security,
test-honesty, or one-daemon-write defect. A separate gate adjudication cleared the only disputed
finding: `WorldSnapshotLoader` was already 568 pure LOC at the base and this PR adds a bounded
13-pure-LOC clock reconstruction; the repository has no enforced 250-LOC Kotlin gate. That legacy
size is a follow-up quality note, not grounds to expand this remediation into a broad production
refactor.

Verdict: cleared

The clearance is limited to the bounded restart-equivalence scope and the exact implementation tree
above. The explicit quarantines remain unchanged, and no merge or deployment authorization is
implied.
