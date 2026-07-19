# OPENSAM-124 — GA-079 nation bulk PHP oracle capture

Status: PHP oracle evidence was reproduced twice and byte-identical on 2026-07-19 after a metadata-only source-range correction. ADR-LITE-014 selected an in-memory daemon-owned GA-079 lifecycle seam; its focused implementation and architecture guard are **independently reviewed / cleared**. Production activation remains blocked on canonical `world_id` (`OPENSAM-43`) and the W3 durable lifecycle/flush predecessor.

## Frozen capture contract

This capture resolves only the legacy observation needed by GA-079. It proves that, under the
captured PHP/MariaDB autocommit conditions, the successful `nation_turn` write precedes the
user-only `general.killturn` write and can remain durable when the latter fails or the process
is killed at that boundary. ADR-LITE-014 now selects the daemon-owned model described below; it
does not authorize an API-side `general` write, durable schema, Redis workflow, or production
activation. Those remain blocked on `OPENSAM-43` and W3.

Legacy source evidence is pinned to revision `4de7ebec17a722d516608dbb987467f1a451dada`:

| Source | Lines | Captured dimension |
|---|---:|---|
| `legacy/devsam-core/hwe/sammo/API/NationCommand/ReserveBulkCommand.php` | 16-75 | Whole-payload validation, ordered child loop, scalar versus structured result shape |
| `legacy/devsam-core/hwe/func_command.php` | 222-240, 402-496 | `_setNationCommand` ring update, user-only `killturn=max(env.killturn,current)`, `applyDB` order |
| `legacy/devsam-core/hwe/sammo/LazyVarUpdater.php` | 55-65 | `updateVarWithLimit` max semantics |
| `legacy/devsam-core/hwe/sammo/General.php` | 704-725 | Dirty general `UPDATE` persistence |
| `legacy/devsam-core/vendor/sergeytsalkov/meekrodb/db.class.php` | 919-961 | `run_success` hook runs only after successful SQL |
| `legacy/devsam-core/hwe/sammo/Command/Nation/휴식.php` | 10-39 | Valid zero-cost/zero-precondition nation command used by the capture |

The harness additionally hard-checks the SHA-256 of every cited source plus
`GameConstBase.php` and `schema.sql`, `@@autocommit=1`, and Aria engines for both `general`
and `nation_turn`. Source drift is a hard failure, not a fixture refresh.

## Loop record

| Loop element | Record |
|---|---|
| Baseline | The draft consistency contract identifies GA-079 as blocked because Kotlin omits the legacy actor-general `killturn` effect and its ordering/atomicity are unproven. |
| Single hypothesis | Under PHP/MariaDB autocommit, a successful `_setNationCommand` makes the `nation_turn` ring durable before the user-only `general.killturn` write; an interruption at that boundary leaves the ring and the old `killturn`. |
| Deterministic grader | `tools/php-golden/run_ga079_nation_bulk.sh` runs two independent fresh `scenario_1010` installs and accepts only byte-identical canonical JSON via `cmp`. |
| Adopt criterion | Every hard assertion passes in both runs, the runner stages actual output, and verified owned-resource cleanup succeeds before it atomically publishes `evidence/ga079-nation-bulk-php.json`; otherwise no new evidence file is produced. |
| Observed result | The runner exited 0 after both fresh installs, including the acknowledged post-`UPDATE nation_turn` `SIGKILL` checkpoint; its internal `cmp` passed, owned cleanup was verified, and it atomically published the canonical artifact. |
| Decision | The PHP hypothesis is accepted as evidence. ADR-LITE-014 selected the daemon-owned child lifecycle; its seam, eight focused tests, and architecture guard are independently reviewed and cleared. It represents the durable ring / old-`killturn` boundary without an API `general` write, while W3 remains responsible for durable world-scoped activation. |

## Selected lifecycle seam (review cleared; activation blocked)

The selected child model is `PENDING -> RING_COMMITTED -> APPLIED | NOOP |
FAILED_AFTER_RING`, or `PENDING -> REJECTED_BEFORE_RING`. Every transition compares its expected
`stageVersion`.

1. Stage A acknowledges only an already-committed ring child. It leaves the actor's in-memory
   `killturn` unchanged and deliberately performs no ring persistence in this W0 seam.
2. Stage B is daemon-owned. `npc >= 2` or a current `killturn` already at/above the frozen floor
   reaches `NOOP`; otherwise the in-memory general is patched through `ChangeRecorder` and reaches
   `APPLIED` only with that effect.
3. A no-effect Stage-B failure becomes `FAILED_AFTER_RING`, retains the committed-ring checkpoint
   and old `killturn`, and retries Stage B only. An unresolved earlier child blocks every later
   child from advancing.

The model is intentionally not a durable record or a singleton-world activation. `OPENSAM-43`
must define canonical `world_id`, and W3 must bind the same stages/version checks to durable CAS
and the fenced flush path before production use. The focused lifecycle/guard review is cleared;
durable W3 activation and its integration review remain pending.

## Matrix encoded by the harness

| # | Case | Required assertion |
|---:|---|---|
| 1 | Whole-payload validation fails at child 1 | Child 0 ring slot is unchanged; `launch` is not entered. |
| 2 | User `killturn=K-7` | Ring changes, `killturn=K`, statement order is `UPDATE nation_turn` then `UPDATE general`. |
| 3 | User `killturn=K+7` | Ring changes, `killturn` is unchanged, and no `general` update occurs. |
| 4 | `npc>=2` | Ring changes, `killturn` is unchanged, and no `general` update occurs. |
| 5 | Empty turns, unavailable action, non-array arg after a valid child 0 | Child 0 persists, child 2 is untouched, and each PHP scalar reason is exact. |
| 6 | Child 1 has `turnList=[12]` after a valid child 0 | `result=false`, prefix `briefList`, `errorIdx=1`, exact reason, and child 2 is untouched. |
| 7 | Catchable actor-scoped `BEFORE UPDATE general` trigger after the ring write | Ring persists while the old `killturn` remains; trace contains successful `UPDATE nation_turn` followed by failed `UPDATE general`, and the temporary trigger is dropped in `finally`. |
| 8 | True crash at the same hook boundary | The child blocks only after `run_success` for `UPDATE nation_turn`; the parent sends `SIGKILL` only to that acknowledged child PID, then a new PHP process reconnects and proves ring persistence with old `killturn`. |

## Disposable reachability adjustments

Each fresh install selects an existing officer already paired with a `nation_turn` row. It
changes only that disposable actor's `npc`, `killturn`, and empty penalty JSON; sets
`game_env.killturn=100`; and resets only observed slots 0-2 to explicit sentinel values.
The harness asserts every row exists and every adjusted before-state is exact before invoking
the API. It never assumes a fixed actor/nation ID, records no hidden seed, and never uses a
transaction or fixture fallback to manufacture the behavior.

## Observed capture and evidence state

Run of record on 2026-07-19:

```text
tools/php-golden/run_ga079_nation_bulk.sh --replace-existing
GA-079 run 1: installing fresh scenario_1010...
GA-079 run 1: capturing pre-crash matrix...
GA-079 run 1: arming post-ring crash checkpoint...
GA-079 run 1: terminating only the acknowledged capture child...
GA-079 run 1: reconnecting to verify persisted ring / old killturn...
GA-079 run 2: installing fresh scenario_1010...
GA-079 run 2: capturing pre-crash matrix...
GA-079 run 2: arming post-ring crash checkpoint...
GA-079 run 2: terminating only the acknowledged capture child...
GA-079 run 2: reconnecting to verify persisted ring / old killturn...
GA-079 fresh captures matched; owned cleanup verified before atomic evidence publish.
```

The command exited 0. The wrapper's internal `cmp -s` accepted the two independently installed
`final.json` artifacts, staged the canonical evidence, verified removal of every owned resource,
then atomically published the result. The result is
[`ga079-nation-bulk-php.json`](evidence/ga079-nation-bulk-php.json), 8,731 bytes, SHA-256
`a8918979ab2d532d85a4b4604c55944d76d5a70b4ee9bb726ba8161e3ff22418`. Its JSON gate confirmed
schema `ga079-nation-bulk-php-v1`, ten cases, `@@autocommit=1`, and Aria for both `general` and
`nation_turn`. The captured runtime was PHP 8.3.32 and MariaDB 11.4.12; the embedded source
revision is `4de7ebec17a722d516608dbb987467f1a451dada`.

This is a metadata-only correction from the previous 8,740-byte SHA-256
`0fae49ed1cd884f77531b26a2cdc8f11863ee86d5dad2ca752d1d0d920d220d4`: the sole changed JSON
entry is MeekroDB's source range, corrected from `246-277, 937-961` to `919-961` with the same
source hash. Removing `metadata.oracle.sourceEvidence` yields byte-identical canonical JSON, so
the observed runtime behavior and every assertion are unchanged.

The user-below-floor case records `UPDATE nation_turn` then `UPDATE general` and `killturn`
`93 -> 100`. User-above-floor and `npc>=2` cases record only `UPDATE nation_turn`. The catchable
trigger records `UPDATE nation_turn`, then failed `UPDATE general`, while retaining the old
`killturn`; the separate acknowledged `SIGKILL` case reconnects to the same durable ring / old
`killturn` state. The remaining matrix cases passed with the documented scalar and structured
prefix response shapes.

Static checks observed: `bash -n tools/php-golden/run_ga079_nation_bulk.sh`, runner `--help`,
the intentional-nonzero `--self-test-cleanup`, PHP 8.3.32 image
`php -l tools/php-golden/capture_ga079_nation_bulk.php`, and the direct
`--self-test-out-paths` check with read-only `/work` all passed their stated conditions. The
output-path check rejects traversal, nesting, and symlink escapes. After the wrapper completed,
label-filtered Docker container and network queries for `opensamguk.capture=ga079` returned no
resources, confirming that only run-owned resources were cleaned. `webapp-testing` is N/A: this
is a direct PHP/Docker oracle capture with no browser, UI, or HTTP surface under test.

Independent review found that an `EXIT`-trap cleanup failure could otherwise occur after a new
canonical fixture was already published. The runner now stages output first, explicitly attempts
and verifies every ownership-checked Docker cleanup, discards the stage on any cleanup failure,
and atomically publishes only after cleanup succeeds. It preserves a pre-existing nonzero failure
status; a cleanup failure after an otherwise successful wrapper exits nonzero. The deterministic
`tools/php-golden/run_ga079_nation_bulk.sh --self-test-cleanup` creates no Docker resources,
forces two synthetic container and one synthetic network cleanup failures, and confirms that no
final or staged artifact remains. Because the real PHP install generates legacy configuration,
each Docker `/work:rw` mount is a disposable copy; the shared workspace and `legacy/` tree are
not mounted writable. The 2026-07-19 two-install rerun exited 0 and left zero labelled resources.
