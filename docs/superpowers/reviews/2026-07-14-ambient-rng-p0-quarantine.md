# Ambient RNG P0 Quarantine

## Scope

This review records the three native-PHP ambient-shuffle boundaries found before shipment.
Runtime substitutes remain deterministic and preserve their enclosing seeded `RandUtil` cursors.
The owned evidence is:

- `tools/php-golden/capture_ambient_shuffle_quarantine.php`
- `tools/php-golden/p3-capture-backlog.md`
- `logic/src/main/kotlin/opensamguk/logic/actions/personnel/CheRandomImgwan.kt`
- `logic/src/test/kotlin/opensamguk/logic/actions/personnel/RandomImgwanTest.kt`
- this review artifact

This artifact is intentionally separate from the shared scenario review and LEDGER.

## PHP Anchors

- `legacy/devsam-core/hwe/sammo/Event/Action/RaiseNPCNation.php:199-232`
  - Creates a seeded `RandUtil` at lines 209-211.
  - Uses that `RandUtil` for `calcAvgNationCity` at line 213.
  - Calls `Util::shuffle_assoc($emptyCities)` at line 232, outside the seeded `RandUtil`.
- `legacy/devsam-core/src/sammo/Util.php:414-425`
  - Copies the associative keys, calls native `shuffle($keys)`, then rebuilds the map in that order.
  - It does not accept or derive a `RandUtil` seed.

- `legacy/devsam-core/hwe/func.php:1278-1305`
  - `triggerTournament(RandUtil $rng)` receives the monthly RNG.
  - Consumes `$rng->nextBool(0.4)` at line 1292.
  - Calls ambient `shuffle($tnmt_pattern)` at line 1300 when no pattern exists.
  - Pops the selected type at line 1303, stores the remainder, then passes the type to
  `startTournament`, which persists it as `tnmt_type` (`func_tournament.php:277-292`).
- `legacy/devsam-core/hwe/sammo/Command/General/che_랜덤임관.php:150-173`
  - Calls native `shuffle($nations)` before the seeded command RNG score loop.
  - The native permutation is not recoverable from the command seed or persisted game state.
- `legacy/devsam-core/hwe/func_gamerule.php:423-434`
  - Q11 `checkWander`, Q15 `triggerTournament`, and Q16 `registerAuction` share the monthly
    `RandUtil`; the native Q15 shuffle is not a call on that object.
- `tools/php-golden/RandUtilDrawRecorder.php:276-281`
  - Its `RandUtil::shuffle` override delegates without per-swap records. More importantly,
    native `shuffle()` and `Util::shuffle_assoc()` never pass through that method at all.
- Repository-wide PHP source scan: no production `mt_srand(` or `srand(` call exists under
  `legacy/devsam-core` (excluding `vendor`). The only explicit pin is the capture harness
  `tools/php-golden/capture_tournament.php`, which deliberately seeds native MT19937 before a
  different tournament mechanic.

## Disposition

**Verdict: sanctioned deterministic divergence**. None of the three ambient permutations is
claimed as byte-parity-complete; the enclosing mechanics remain executable and replayable.

On 2026-07-15 the user explicitly directed the release to proceed without additional tests for
these ambient permutations. This waives no seeded-stream invariant and does not convert either
permutation into a PHP parity claim; it records the accepted residual gap for this shipment.

`RaiseNPCNation` executes the deterministic substitute adopted by LEDGER loop 26. It preserves the
PHP action `RandUtil` boundary while making the scenario-start event replayable. This deliberately
does not claim the same candidate permutation as PHP's process-global native RNG.

The tournament path executes the deterministic month-scoped substitute adopted by LEDGER loop 27.
It preserves the PHP source-stream boundary by keeping the permutation outside the monthly
`RandUtil` cursor. This is a replay-safety divergence, not proof that the selected tournament type
matches PHP.

The NPC foreign branch of random join keeps the supplied insertion order as its deterministic
substitute, then preserves PHP's per-nation `nextFloat1`, cumulative-affinity, and minimum-score
loop. Its tests validate only that post-boundary behavior and do not claim the insertion order is
the PHP native permutation.

## Real PHP 8.3 capture evidence

The new capture tool invokes the real `Util::shuffle_assoc()` and the exact tournament
`shuffle()` + `array_pop()` statement sequence on `php:8.3-cli`.

- Runtime observed: PHP `8.3.31`.
- Controlled state: for seeds `1`, `777`, and `12345`, with native-prefix draw counts `0`, `1`,
  and `7`, the script calls `mt_srand(seed, MT_RAND_MT19937)` and captures both sites.
- Reproducibility: two complete script runs produced the same SHA-256 (recorded below after the
  final run); the script also hard-asserts two in-process captures are identical.
- Shared-state proof: at seed `12345`, changing only the number of preceding `mt_rand()` calls
  changes both captured shuffle orders. Native `shuffle()` therefore advances/depends on the
  same process-global state and is sensitive to untracked prior native draws.
- Auto-seed proof: eight fresh PHP processes with no `mt_srand` produced eight distinct combined
  RaiseNPCNation/tournament permutations. This is expected auto-seeded behavior, not a fixture
  selection criterion.

The controlled vectors are intentionally not committed under
`logic/src/test/resources/golden`: inserting `mt_srand` creates a reproducible experiment but
changes the real turn's input state. Conversely, leaving the engine auto-seeded cannot pass the
required byte-identical fresh-process rerun. No number, seed, order, or draw from this experiment
is used as a Kotlin expected value.

## Why exact runtime replay is unavailable

The game snapshot records the install `hiddenSeed` and reconstructible `LiteHashDRBG` lineages.
It does not record PHP's native global RNG seed or cursor. `capture_monthtick.php` records only
the `LiteHashDRBG` cursor/draw count, and `RandUtilDrawRecorder` cannot intercept a direct native
function call. The real PHP process also has no explicit `mt_srand` from which the state could be
re-derived. Therefore the same game rows, hidden seed, year, and month do not determine either
ambient permutation across fresh PHP processes.

This is a source-stream boundary, not merely a missing test vector:

1. `RaiseNPCNation` derives a local DRBG, but line 232 does not use it.
2. Q15 consumes exactly the trigger `nextBool(0.4)` from the monthly DRBG before the ambient
   branch; the native shuffle adds no monthly-DRBG draw.
3. The remainder and selected `tnmt_type` preserve the shuffle result, not the native engine's
   pre-call seed/cursor, so a later snapshot cannot replay how that result was produced.

The two exact PLAN-MISS reasons are appended to `tools/php-golden/p3-capture-backlog.md`. The
accepted behavior until the quarantine is closed is:

- preserve every seeded/monthly `RandUtil` cursor boundary proven by PHP source;
- execute both mechanics through separately seeded deterministic streams without consuming their
  enclosing action/monthly `RandUtil` cursors;
- avoid nondeterministic JVM/Kotlin platform shuffles in helper code;
- do not assert byte-identical PHP ambient permutation order.

## Follow-up gate

The quarantine may be removed only when one of these explicit decisions lands:

1. **Faithful state capture:** instrument a real PHP request draw-neutrally so the native engine
   seed/state before each site can be snapshotted and restored across a second fresh process.
   Capture the complete enclosing mechanic twice with identical SHA-256, including:
   - input city key order and post-`Util::shuffle_assoc` key order for `RaiseNPCNation`;
   - input pattern, post-shuffle pattern, popped type, persisted remainder, and monthly
     `RandUtil` cursor before/after `triggerTournament`;
   - all resulting row/log deltas. The Kotlin gate must replay the captured native state, not a
     seed invented from `hiddenSeed`.
2. **Sanctioned deterministic divergence (selected):** cross-runtime replayability replaces PHP's
   unrecoverable ambient order. Production executes a deterministic substitute, and tests/docs call
   it divergence behavior rather than PHP parity.

Until either decision is implemented, the follow-up gate is a loud quarantine check:

- run `capture_ambient_shuffle_quarantine.php --runtime-parity` and require its two exact
  `PLAN-MISS` lines;
- fail review if production PHP gains an explicit native seeder, if either source anchor moves,
  or if a Kotlin test/resource claims an exact PHP permutation without a two-run enclosing
  mechanic capture;
- fail review if either substitute consumes the enclosing action/monthly `RandUtil`, uses platform
  nondeterminism, or is described as byte-identical PHP permutation parity.

## Verification record

- PHP image: `php:8.3-cli` (`PHP 8.3.31`).
- Controlled capture SHA-256 run 1: `8724817613d3a698fe407f5a95bdf39c83933b6e36699b82f22f1ced03d488d2`.
- Controlled capture SHA-256 run 2: `8724817613d3a698fe407f5a95bdf39c83933b6e36699b82f22f1ced03d488d2`.
- Unseeded fresh-process sample: 8 runs, 8 distinct combined permutations.
- `tools/agent-system/check.py --strict --base origin/main`: errors 0, warnings 0.
- Independent post-fix gate review `019f60b5-4e04-7473-bd20-75a63a149b6e`: `PASS`.
- No broad Gradle task was run.
