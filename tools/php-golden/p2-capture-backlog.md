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
