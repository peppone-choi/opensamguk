# tournament (대회) capture backlog

Golden: `logic/src/test/resources/golden/tournament/fight-fixtures.json`
Capture script: `tools/php-golden/capture_tournament.php`
Oracle: `legacy/devsam-core/hwe/func_tournament.php`

## Captured (faithful, sha256-identical across two fresh scenario_1010 installs)

- `fight()` (func_tournament.php:1004) — tnmt_type {0,1,2,3} × type {0,1}: item-flavor logs
  (non-buyable 마/무/서), phase damage/energy log lines (byte-exact markup + Josa),
  승부 판정(sel 0/1/2), tournament win/draw/lose/gl deltas, rank_data {tp2}w/d/l/g updates
  (incl. the >, ==, < gl1/gl2 branches via C2 equal-base and C6 gt-base).
- `qualify()` promote branch (func_tournament.php:594-611) — top-4 per group by
  `win*3+draw desc, gl desc, seq`, prmt=1..4 assignment, KV transition tournament=3/phase=0.
- Pure PHP MT19937 conformance vectors (`rngConformance`) — mt_rand / mt_rand%100 /
  mt_rand(1,100) / mt_rand(150,300) / array_rand(size6), for seeds {1,777,12345}.

## RNG note (NOT a miss — a property of the mechanic)

Tournament uses PHP **native** `rand()`/`mt_rand()`/`array_rand()` (Util::randRangeInt=mt_rand,
Util::choiceRandom=array_rand, `rand()`==mt_rand alias PHP7.1+), NOT sammo RandUtil/LiteHashDRBG.
Determinism is pinned with `mt_srand($seed, MT_RAND_MT19937)`. A per-draw stream (à la
RandUtilDrawRecorder) is **not recordable without editing the native rand oracle**, so
faithfulness is proven by (a) the exact seed, (b) the full real fight/qualify output
(logLines + row deltas), and (c) the pure MT19937 conformance vectors. The Kotlin port must
implement PHP-MT19937 + array_rand + mt_rand-range and verify against this golden.

## Quarantined — `ORDER BY rand()` selection (proof, do NOT fabricate)

`selection()` / `selectionAll()` (func_tournament.php:625-686) draw the promoted seed with SQL
`SELECT * FROM tournament WHERE prmt=... ORDER BY rand() LIMIT 1` (lines 634/639/643/647).
This `rand()` is **MySQL's RAND() SQL function**, seeded by the MariaDB server — NOT the PHP
`mt_srand` global state — and the code calls it **unseeded**, so the row picked is genuinely
nondeterministic per query. A fixed golden cannot be captured faithfully on this install
(same class as the P5 Q1 "ORDER BY RAND()" quarantine). The Kotlin `assignMainGroups` currently
sorts by promote/group/seq deterministically — a **sanctioned divergence** from PHP's random
seed draw (documented here, not silently). Revisit only if a seedable substitute is decided.

## Deferred (out of this task's scope — fight()+qualify()+promote requested)

- `finallySingle()`/`finallyAll()` (:688-726) — 본선(grp 10-17) fight loop + top-2 promote;
  same fight() engine, capturable with the same harness when the 본선 gate is built.
- `finalFight()` (:765-808) — 16강↑ knockout single-elimination fight.
- `setGift()` (:810-964) / `setRefund()` (:966-990) — reward/inheritance/betting payout.
- `startTournament()`/`startBetting()`/`fillLowGenAll()` — bracket seeding (NPC fill uses rand()).
