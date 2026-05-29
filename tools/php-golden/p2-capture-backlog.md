# P2 golden-capture backlog (Task GR1 ignore-list)

Per-command PHP golden fixtures for the P2 command surface (`tools/php-golden/manifest.json`)
are captured from the REAL `legacy/devsam-core` PHP via the generalized capture harness
(`capture_command.php` for the zero-arg develop family, `capture_command_args.php` for the
arg-required / state-gated commands). Every captured golden is 100% real PHP — the action
log, the post-state rows, the 6-component seed string, and the `hiddenSeed` all come from a
single running install.

The GS1 byte gate uses **matched-count + this documented ignore-list**: partial capture is
acceptable. A command lands here ONLY when no actor in the populated standard scenario
(`scenario_1010`: 174 generals, 2 nations, year 181) can satisfy its PHP preconditions
**without fabricating computed values**, or when the action's RNG seam (unique-item lottery)
makes a module-free capture seed-fragile. **No golden is ever hand-fabricated.** The harness
HARD assertions (module-free acting general, no level cross, exact action-line count, integer
trust) stay intact — a command that would trip an assertion is backlogged, not weakened.

Capture install of record (all 28 committed fixtures share this seed):
`hiddenSeed=cff8658592f2d3c55d404232a019e016`, year 181, develCost 20.

## Captured (28 / 40 manifest commands)

develop: che_상업투자, che_농지개간, che_성벽보수, che_수비강화, che_치안강화, che_기술연구,
che_정착장려, che_주민선정, che_물자조달
military: che_징병, che_모병, che_훈련, cr_맹훈련, che_사기진작, che_소집해제, che_이동
personnel: che_하야, che_등용
trade: che_증여, che_헌납, che_장비매매, che_군량매매
nation-internal: che_발령, che_포상, che_천도, che_국호변경, che_국기변경
founding: che_거병

## GT1 — the module-BEARING (non-identity) golden (GATE-TRAIT)

The 28 above are all MODULE-FREE (the `assertModuleFree` HARD assertion forced special/special2/
personal ∈ {None,'',null} + every equipment slot a None item, so the empty `GeneralActionPipeline`
folds as the identity). That gate proves log/mutation fidelity but it can NEVER exercise the real
9-source stat stack. Task GT1 (GATE-TRAIT) needs the INVERSE: an actor that NATURALLY carries
specialty/personality/nation-type modules, byte-matching the multi-source onCalcDomestic/onCalcStat
accumulation.

`capture_command.php`/`capture_command_args.php` cannot produce that — both force `personal=>'None'`
in their precondition and HARD-abort on a non-None module. So GT1 has a DEDICATED inverse-capture
script, `capture_nonidentity.php`, which keeps every numeric/log/no-level-cross/exact-line-count/
integer-trust assertion but asserts the modules ARE active (the inverse identity guard) and records a
`moduleFold` observability proof (the module-free vs module-bearing after-state on the SAME seed).

Capture of record (its OWN install/seed — NOT the cff8658592… module-free install):
`hiddenSeed=8ebfeb6fa932a181ec9ef43b7473f4c9`, scenario_1010, year 181, develCost 20.
- Actor: gid 14 공융 (nation 1 후한 level 7, city 42 홍농) carrying
  `special=che_경작` (source #3) + `personal=che_왕좌` (source #5); nation type `che_유가` (source #1).
- Command: `che_농지개간` (turnType 농업) folds THREE sources across BOTH pipelines —
  cost  : 20 → 유가 ×0.8 = 16 → 경작 ×0.8 = 12.8 → round = 13 (reqGold);
  score : ×1.1 (유가) × ×1.1 (경작) on agri;
  exp   : ×1.1 (왕좌, via onCalcStat('experience')).
- Fixture: `golden/p2/che_농지개간-nonidentity-fixtures.json` (4 cases, distinct months/outcomes).
- Asserted by `logic/.../golden/NonIdentityFoldGoldenTest.kt`.

GT1 surfaced + fixed a real parity bug: `CommerceInvestment.resolve` accumulated exp/ded WITHOUT the
`onCalcStat` fold, so 왕좌's experience ×1.1 was dropped (invisible to the module-free goldens, where
onCalcStat is identity). Re-ported from PHP `General::addExperience`/`addDedication` (General.php:448-495,
affectTrigger default TRUE folds onCalcStat BEFORE increaseVar). All module-free develop/military/trade
goldens stay byte-exact (the fold is identity for them).

## Backlogged (12)

| Command | Group | Reason (from the RUNNING PHP) |
| --- | --- | --- |
| `che_임관` | personnel | `hasFullConditionMet()` == false on this install: deny `임관이 제한되고 있습니다` — `AllowJoinDestNation(relYear=0)` blocks joining at the opening year (year == startYear). Capturable only after the game clock advances past the opening-restriction window; no actor satisfies it in the pristine year-181 install. |
| `che_장수대상임관` | personnel | Same opening-restriction deny (`임관이 제한되고 있습니다`) — `AllowJoinDestNation(relYear=0)`. Needs a non-opening relYear. |
| `che_랜덤임관` | personnel | Runs, but its `tryUniqueItemLottery` seam (`func_command.php`, actionName `무작위 국가로 임관`) grants a unique item on this install's `hiddenSeed`, producing a 2nd (PLAIN) acting-log line → trips HARD assertion 4 (exact `logLines`). A module-free capture is seed-fragile here (the lottery hit is RNG-dependent); rather than weaken the no-lottery assertion or seed-hunt, deferred. The seed/log/draws would be real PHP, but the captured shape is not the documented 1-line `levelCross=false` golden. |
| `che_방랑` | personnel | Lord actor exists but `BeOpeningPart(relYear)` denies: `초반 제한 중에는 불가능합니다` — 방랑(자기 국가 해산) is blocked during the opening part. Needs a post-opening clock. |
| `che_은퇴` | personnel | `ReqGeneralValue('age','나이','>=',60)` — `scenario_1010` ships **zero** generals aged ≥ 60 (deny `나이가 60세 이상이어야 합니다`). Bumping `age` would be fabricating a computed input the 은퇴 path reads, so deferred to a scenario/clock with an aged general. |
| `che_집합` | military | `MustBeTroopLeader()` + `ReqTroopMembers()` — needs a multi-general troop (부대). `scenario_1010` ships no troop with a leader+members the module-free actor heads (deny `부대장이 아닙니다`). Requires multi-general troop setup (a P-later state). |
| `che_건국` | founding | `BeLord()` + `WanderingNation()` + `ReqNationValue('gennum','>=',2)` + `BeOpeningPart(relYear+1)` + a valid `nationType` class. 건국 is the 2nd step after 거병 (it converts a wandering nation, gennum≥2, to a settled one); no single-turn actor in `scenario_1010` is a wandering-nation lord with ≥2 retainers in the opening window. Deny `인자가 올바르지 않습니다` (no reachable valid `nationType`/state). Multi-step (거병 → recruit → 건국) — deferred. |
| `cr_건국` | founding | Same as `che_건국` (the conscription-rule variant of 건국 — identical wandering-lord + nationType + gennum≥2 preconditions). |
| `che_무작위건국` | founding | Same founding preconditions as `che_건국` plus a random-city pick; same wandering-lord/gennum gate is unmet. Deny `인자가 올바르지 않습니다`. |
| `che_감축` | nation-internal | **Structurally un-satisfiable in this revision**: `init()` sets the dest city to the nation **capital**, then requires `ReqDestCityValue('level','>',origCityLevel)` where `origCityLevel` IS the capital's own level — i.e. `capitalLevel > capitalLevel`, always false (deny `더이상 감축할 수 없습니다`). The PHP path cannot meet full condition for any capital; faithful = PHP wins → backlog. |
| `che_증축` | nation-internal | `ReqDestCityValue('level','<',8)` on the capital. Both nations' capitals (낙양, 업) are already level **8** (max) in `scenario_1010` (deny `더이상 증축할 수 없습니다`). No nation has a sub-max capital to grow. |
| `che_무작위수도이전` | nation-internal | Deny `더이상 변경이 불가능합니다` — the random-capital-move variant is gated by a per-nation once/availability flag that is already exhausted/closed in the pristine install. (Deterministic `che_천도` IS captured; the random-pick variant needs the move-availability state this install lacks.) |

## Out of P2 (not backlog — never in the P2 surface)

Per `manifest.json`'s `out` block: `물자원조` (diplomatic, needs a counterpart nation; diplomacy
phase), `등용수락` (the 등용 accept side; P6), and the ~57 non-P2 commands (combat 출병/화계/
탈취/…, diplomacy 선전포고/불가침*/…, the `event_*` unit-research, NPC-only 능동).

## GR2 quarantine — develop goldens with an internally-inconsistent capture (5)

These five develop goldens ARE committed, but the GR2 Kotlin byte gate
(`logic/.../golden/DevelopGoldenTest.kt`) cannot drive the Kotlin resolver to reproduce them
**by any actor stat**, while the file-disjoint sibling commands that share the exact same code
path DO byte-match at the install-true stats. This is a **capture-data defect** (the committed
before/after of these five encodes a different, mutually-inconsistent effective stat for the
same gid 8), **NOT a Kotlin resolver parity bug** — proven by:

* gid 8's install stats are recovered as the UNIQUE triple `L42 / S73 / I24` (= the scenario_1010
  `고승` template) from a joint brute-force over EVERY gid-8 develop golden's acting log; this triple
  byte-reproduces **상업투자, 농지개간, 치안강화, 물자조달** (intel-score, strength-score, AND the
  leadership+strength+intel-sum path — all four exact).
* **치안강화** (strength-develop, `CommerceInvestment(statKey=strength)`) is byte-exact at S73/I24, so
  the strength score path is correct — yet **수비강화/성벽보수** (the IDENTICAL class, differing only in
  cityKey/actionKey/debuffFront/seed) require `strength_statval ≈ 71` (raw S ≈ 65), impossible for the
  same general. **물자조달** (L+S+I sum) is byte-exact, yet **정착장려/주민선정** (leadership path) want
  `L = 46`, while **사기진작** (the pure-leadership `round(L*100/crew*atmosDelta)` path) pins `L = 42`.
* An exhaustive `(L,S,I)` brute-force finds **0 solutions** for 기술연구 and 정착장려 even *alone*
  (their own success+fail cases cannot self-reconcile), and finds solutions for 수비/성벽/주민 only at
  stats that contradict the four reproducible commands.

| Command | Group | GR2 status |
| --- | --- | --- |
| `che_수비강화` | develop | RED-quarantined: needs `strength_statval≈71`; 치안강화 (same code) is byte-exact at the install S73 → capture-data inconsistency, not a resolver bug. |
| `che_성벽보수` | develop | RED-quarantined: same as 수비강화 (statval≈71 vs install 79). |
| `che_기술연구` | develop | RED-quarantined: `tech_success`+`tech_fail` are not jointly reproducible by ANY `(L,S,I)` (0 hits). |
| `che_정착장려` | develop | RED-quarantined: `settle_success`+`settle_fail` not jointly reproducible (0 hits); leadership pinned 42 by 사기진작. |
| `che_주민선정` | develop | RED-quarantined: reproducible only at `L=46`, contradicting the install `L=42`. |

The quarantine test (`quarantined develop goldens are non-reproducible at the install stats`) asserts
these REMAIN non-reproducible at the install stats — it never edits a golden or fakes a green byte-match;
a clean re-capture (or a resolver regression) trips it RED, at which point the command moves into the
`reproducible` list. **Fix = re-capture these five on a single pristine install** (the Re-capture steps
below) so their before/after share one consistent gid-8 stat with the four reproducible develop commands.

## Re-capture

One-shot, manual host, never CI. From the repo root, inside the php capture container
(see `tools/php-golden/README.md`):

1. `install_scenario.php --scenario=1010` (draws a fresh `hiddenSeed`).
2. `probe_command.php` → paste the develop-family `gid`/months into `capture_command.php`'s
   `$picks` (the develop picks are seed-specific).
3. `capture_command_args.php` (arg/state-gated commands; self-positions per command — seed-
   agnostic), THEN `capture_command.php` (develop family + 하야; run AFTER the arg-aware pass
   because 하야 leaves its actor resigned). Both MUST run on the **same** install so every
   committed fixture shares one `hiddenSeed`.

If a backlogged command becomes capturable (clock advanced past opening, a troop/aged general
added, a sub-max capital), move it from this table into the captured set.
