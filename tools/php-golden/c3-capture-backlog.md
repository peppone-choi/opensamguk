# C3 chief-command golden-capture backlog (capture_command_c3.php)

Per-command PHP golden fixtures for the 12 missing **C3 chief commands**
(`tools/php-golden/manifest_c3.json`) captured from the REAL `legacy/devsam-core` PHP via
`tools/php-golden/capture_command_c3.php` (the C3 arg/state-gated sibling of
`capture_command_args.php`). Every captured golden is 100% real PHP — the acting/dest
action log, the broadcast globalAction line, the RNG draw stream (via `RandUtilDrawRecorder`,
DRAW-NEUTRAL), the 6-component `nationCommand` seed string, the post-state rows, and the
`hiddenSeed` all come from a single running install. **No golden is ever hand-fabricated.**

The harness keeps every P1/P2 HARD assertion verbatim (module-free acting general, no
explevel/dedlevel cross, exact acting action-line count = `manifest_c3` `logLines`, integer
trust, unchanged equipment-slot count). A command that would trip an assertion, or whose
`hasFullConditionMet()`/`run()` finds no reachable outcome, is SKIPPED (PLAN-MISS) and
recorded below with the exact PHP deny reason — never weakened, never faked.

## Capture install of record

All 12 committed fixtures share one install / one seed:
`hiddenSeed=a2db167cd66b54bd49ea970c03882e34`, scenario_1010, year 181, startYear 181,
develCost 20, 174 generals, 2 nations (1=후한 lvl7 gennum36 cap 낙양 / 2=황건적 lvl2 gennum19
cap 업), 24 owned cities. Acting general for ALL 12 = **gid 152 ⓝ하진** (nation 1, officer 12,
module-free, city 70 호관) — the only module-free officer≥5 chief on this seed (nation 2's chief
장각 carries a special module). Byte/draw-stability VERIFIED: two consecutive full runs on the
same install produced byte-identical fixtures for all 12 (0 diffs).

## Captured (13 / 13) — all GREEN, ZERO PLAN-MISS

(12 chief commands + `event_극병연구`, the BeChief national-research command, added later.)

Fixtures: `logic/src/test/resources/golden/p2/<command>-fixtures.json` (same shape as
`capture_command_args.php`, PLUS a `draws` block = `{draw_count, draw_stream}`).

| Command | Group | acting logLines | dest lines | broadcast(global) | draws | gate / static-input precondition (documented, score 100% real PHP) |
| --- | --- | --- | --- | --- | --- | --- |
| `che_백성동원` | strategic | 1 | — | — | 0 | strategic_cmd_limit=0; own occupied dest city (= actor's city 70). def/wall raised to max*0.8. |
| `che_수몰` | strategic | 1 | — (nation-broadcast PLAIN→bystanders, not load-bearing) | — | 0 | strategic_cmd_limit=0; diplomacy state **0 (war)** vs nation 2; enemy BattleGroundCity (city 1 업). def/wall ×0.2. |
| `che_허보` | strategic | 1 | 3 enemy generals moved (post-state) | — | **3** | strategic_cmd_limit=0; state **1 (선포)** vs nation 2; enemy supplied dest city with enemy generals. `rng->choice` per enemy general (+ re-roll on same-city). |
| `che_의병모집` | strategic | 1 | — | — | **77** | strategic_cmd_limit=0; year bumped to startYear+3 (NotOpeningPart, relYear≥3). Creates 3+round(avgGennum/8) NPCs into the actor's city (pickGeneralFromPool name choices + nextRangeInt(64,70) + fillRemainSpecAsRandom). |
| `che_이호경식` | strategic | 1 | — | — | 0 | strategic_cmd_limit=0; state **1** vs nation 2 (AllowDiplomacyBetweenStatus[0,1]). diplomacy term+3, state→1, SetNationFront both. |
| `che_급습` | strategic | 1 | — | — | 0 | strategic_cmd_limit=0; state **1 (선포) term 12** vs nation 2 (AllowDiplomacyWithTerm(1,12)). diplomacy term−3. |
| `che_피장파장` | strategic | 1 | — | — | 0 | strategic_cmd_limit=0; state **1** vs nation 2; `commandType=che_백성동원` (another 전략 cmd != self, currently available). nation_env delay KV set both sides. |
| `che_필사즉생` | strategic | 1 | 35 nation generals (PLAIN + train/atmos→100) | — | 0 | strategic_cmd_limit=0; state **0 (war)** vs nation 2 (AllowDiplomacyStatus[0]). zero-arg. |
| `che_초토화` | special | 1 | — | **1** (pushGlobalActionLog) | 0 | surlimit=0; **peacetime** (state 2, DisallowDiplomacyStatus[0]); own non-capital supplied dest city 4 장안→neutral. **Actor exp floored to 40 (explevel 0)**: 초토화 does `addExperience(-exp*0.1)` and at any high explevel the −10% drop crosses a level (band width < 10%); the exp-floor keeps `−10% + the +15 gain` inside [0,100) so the no-level-cross gate holds (the deferred checkStatChange 레벨다운 path is NOT this golden's target). City/treasury/log deltas are exp-independent → 100% real PHP. |
| `che_몰수` | personnel | 1 | 1 (recipient PLAIN, gold seized) | — | **1** | strategic_cmd_limit/surlimit=0; year+3 (NotOpeningPart); friendly dest gid 14 with gold≥1000. seizes금 500 → nation treasury. `rng->nextBool($npcSeizureMessageProb=0.01)` fires because dest NPCType≥2 (npc=2) → 1 draw (False; the npc-complaint message is a hit-only branch). |
| `che_부대탈퇴지시` | personnel | 1 | 1 (member detached) | — | 0 | friendly dest. static-input troop: a real `troop` row (leader gid 18) with member gid 18 as the dest's troop leader and the detach target as a member (troop≠0, troop≠self) → the detach branch (not the "부대원이 아닙니다"/"부대장입니다" alt outcomes). |
| `che_물자원조` | diplomacy | **2** | — | — | 0 | DifferentDestNation (dest = nation 2); surlimit=0 on BOTH nations; actor nation gold/rice≥50000; amount [1000,1000] within level×coefAidAmount. TWO acting lines (broadcast + PLAIN). |
| `event_극병연구` | event | 1 | — | — | 0 | BeChief + OccupiedCity national-research command (zero-arg, DETERMINISTIC). Reachability precondition: nation 1 gold bumped to 1000000 (≥basegold 0 +100000) + rice to 1000000 (≥baserice 2000 +100000=102000) + aux[can_극병사용] UNSET (<1). Real PHP deltas: nation gold/rice **−100000** each, aux[can_극병사용]→**1** (joining the pre-existing can_국기변경/can_국호변경 flags), actor exp/ded **+120** each (5×(getPreReqTurn 23 +1)), acting log `<C>●</>1월:<M>극병 연구</> 완료`. Fixture adds `nationBefore/nationAfter` (gold/rice/aux) + `inheritanceBefore/inheritanceAfter` blocks. |

`물자원조` is listed in `manifest.json`'s `out` block as "diplomatic, deferred" — but with two
nations present in scenario_1010 it IS capturable (dest = the other nation), so it is captured
here as part of the C3 surface.

### event_극병연구 — inheritance active_action is a real PHP NO-OP for this actor (NOT +1)

`event_극병연구.php:101` calls `increaseInheritancePoint(InheritanceKey::active_action, 1)`, but
`InheritancePointManager::increaseInheritancePoint` (InheritancePointManager.php:258-271) has TWO
early-return guards: `if (!$ownerID) return;` (owner==0) and `if ($general->getVar('npc') >= 2)
return;`. The C3 install-of-record actor **gid 152 ⓝ하진 has npc=2, owner=0** — BOTH guards fire,
so the inheritance point is **genuinely not incremented**. The captured `inheritanceBefore`/
`inheritanceAfter` both read `{owner:0, active_action:null}` — this is the FAITHFUL oracle, not a
miss. The Kotlin port MUST replicate `increaseInheritancePoint` no-op-ing for `npc>=2`/`owner==0`
generals (the +1 only lands for a human-owned non-NPC general). The fixture's nation gold/rice/aux
deltas + exp/ded + log byte-strings are the full parity surface for this NPC-actor capture.

## Backlogged (0)

None. All 12 C3 commands captured GREEN on the single `a2db167…` install.

## Re-capture

One-shot, manual host, never CI. From the repo root, inside the php capture container
(see `tools/php-golden/README.md` §43-61; reuse the running `devsam-golden-*` containers):

> **Container-mount quirk:** the `devsam-golden-php` container mounts a DIFFERENT worktree at
> `/work` (e.g. `…/conductor/workspaces/opensamguk/bogota`) with THIS repo's `legacy/` overlaid
> at `/work/legacy`. So `tools/` + `logic/` under `/work` are the OTHER worktree's. Author the
> scripts in THIS repo, `docker cp` them into the container's `/work/tools/php-golden/`, run with
> `--out-dir=/tmp/c3out` (a container temp dir), then `docker cp` the fixtures back to THIS repo's
> `logic/src/test/resources/golden/p2/` and sha256-verify.

```sh
DB="-e SAMMO_DB_HOST=devsam-golden-db -e SAMMO_DB_PORT=3306 \
    -e SAMMO_DB_USER=root -e SAMMO_DB_PASS=rootpw -e SAMMO_DB_NAME=samdb"

# 1. fresh install (draws a new hiddenSeed; all 12 must share it → one process run).
docker exec $DB devsam-golden-php bash -lc \
  'cd /work && php tools/php-golden/install_scenario.php --scenario=1010 --turnterm=120 --sync=0'

# 2. copy the (THIS-repo) C3 script + manifest into the container, then capture all 12.
#    capture_command_c3.php self-positions + full-world-restores per command (snapshotWorld/
#    restoreWorld), so the N captures stay mutually independent and the install ends pristine.
docker cp tools/php-golden/capture_command_c3.php devsam-golden-php:/work/tools/php-golden/
docker cp tools/php-golden/manifest_c3.json       devsam-golden-php:/work/tools/php-golden/
docker exec $DB devsam-golden-php bash -lc \
  'cd /work && php tools/php-golden/capture_command_c3.php --out-dir=/tmp/c3out'

# 3. copy the fixtures back to THIS repo + sha256-verify.
for cmd in che_백성동원 che_수몰 che_허보 che_의병모집 che_이호경식 che_급습 \
           che_피장파장 che_필사즉생 che_초토화 che_몰수 che_부대탈퇴지시 che_물자원조 \
           event_극병연구; do
  docker cp "devsam-golden-php:/tmp/c3out/${cmd}-fixtures.json" \
            "logic/src/test/resources/golden/p2/${cmd}-fixtures.json"
done
```

Regenerate ONLY when the C3 PHP source changes (`hwe/sammo/Command/Nation/{che_급습,che_몰수,
che_물자원조,che_백성동원,che_부대탈퇴지시,che_수몰,che_의병모집,che_이호경식,che_초토화,
che_피장파장,che_필사즉생,che_허보,event_극병연구}.php`), the seed
derivation (`TurnExecutionHelper`/`Util::simpleSerialize`), `RandUtil`/`LiteHashDRBG`,
`pickGeneralFromPool`, or the ConstraintHelper gate logic. On a Kotlin↔golden mismatch: fix the
Kotlin port, not the golden.
