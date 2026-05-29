# P4 War-Trigger Draw Catalog — the F3/A3/G1b cursor-parity oracle

**Status:** source-transcribed from PHP grand truth (`legacy/devsam-core`). Every draw cites real `file:line`.
**Date:** 2026-05-30
**Scope:** the single, ordered RNG draw stream produced by all battle (`WarUnitTrigger`) triggers + the
`ActionSpecialWar` specialties + crewType/item injections that feed them, as dispatched by `process_war.php`.

---

## 0. Why this is the most parity-critical P4 deliverable

A whole battle (`processWar`) consumes ONE `RandUtil` wrapping ONE `LiteHashDRBG(warSeed)`:

```php
// hwe/process_war.php:11
$rng = new RandUtil(new LiteHashDRBG($warSeed));
```

That single `$rng` is threaded into `WarUnitGeneral`/`WarUnitCity` at construction
(`process_war.php:36,38,47`) and is the *only* randomness source for the entire fight — every phase, every
trigger, both sides. `LiteHashDRBG` is a non-reseeding SHA-512 DRBG with a monotonic `stateIdx` byte cursor
(`src/sammo/LiteHashDRBG.php:35-41`); it is **stateful and position-dependent**. Therefore:

> **One extra, missing, or mis-ordered draw shifts every subsequent draw and desyncs the entire battle.**

For F3 (the phase machine) this catalog is the contract for *when* to draw. For A3 (trigger port) it is the
contract for *what* each trigger draws. For G1b (the gate) it is the **expected-stream oracle**: replay the
Kotlin battle on the same `warSeed` and the ordered list of `(method, args) → value` draws must match PHP
byte-for-byte.

**Draw equivalence is exact:** `nextFloat1()` must reproduce PHP `LiteHashDRBG::parseU64 / max` to the bit
(the TS port `hwe/ts/util/LiteHashDRBG.ts` is the proof the algorithm is portable). Kotlin must match.

---

## 1. Draw primitives — the RNG methods triggers call

All draws go through `RandUtil` (`src/sammo/RandUtil.php`), which delegates to the `RNG` interface
(`LiteHashDRBG`). Signatures and *exact draw-consuming behavior*:

| Method | Signature | Consumes RNG? | Body / parity note |
|---|---|---|---|
| `nextFloat1()` | `(): float` | **1 float draw** | the atomic primitive; `RandUtil.php:11-14` → `LiteHashDRBG` pulls bytes, advances `bufferIdx`/`stateIdx`. |
| `nextBool($prob=0.5)` | `(int\|float): bool` | **conditional** | `RandUtil.php:40-51`. **`$prob>=1` → `return true` with NO draw. `$prob<=0` → `return false` with NO draw.** `$prob===0.5` (strict `===`) → `nextBit()` (1 bit, not a float). Else → `nextFloat1() < $prob` (1 float draw). **The short-circuits are load-bearing: a guaranteed/impossible probability does not touch the stream.** |
| `nextBit()` | `(): bool` | **1 bit draw** | `RandUtil.php:35-38` → `rng->nextBits(1)`. Distinct byte path from `nextFloat1`; only reached by `nextBool(0.5)` exactly. |
| `nextRange($min,$max)` | `(num,num): float` | **1 float draw** | `RandUtil.php:16-20` = `nextFloat1()*($max-$min)+$min`. One `nextFloat1`. |
| `nextRangeInt($min,$max)` | `(int,int): int` | **1 int draw** | `RandUtil.php:22-29` → `rng->nextInt($range)+$min`. One int pull (NOT a float). |
| `nextInt($max=null)` | `(?int): int` | **1 int draw** | `RandUtil.php:31-33`. |
| `choice($items)` | `(array)` | **1 int draw** | `RandUtil.php:94-102` = `rng->nextInt(count(keys)-1)`; returns `$items[$keys[idx]]`. One int pull. |
| `choiceUsingWeight($items)` | `(array)` | **1 float draw** | `RandUtil.php:104+` = one `nextFloat1()*sum` then linear scan. One float. (No war trigger uses it; listed for completeness.) |
| `shuffle($arr)` | `(array): array` | **N-1 int draws** | Fisher-Yates `RandUtil.php`; `nextInt` per index `0..cnt-1` (the `srcIdx===destIdx` skip does NOT skip the draw — the draw already happened). No war trigger uses it. |

**Composite draw helpers on `WarUnit` (not triggers, but fire inside the phase loop — must be ordered):**

| Call | Draw | Source |
|---|---|---|
| `criticalDamage()` | `1 nextRange(...$range)` where `$range=[1.3,2.0]` folded by `onCalcStat('criticalDamageRange')` | `WarUnit.php:437-446` |
| `calcDamage()` | `1 nextRange(0.9, 1.1)` | `WarUnit.php` (`calcDamage`) — **called once per side per phase in the macro loop, see §4** |
| `computeWarPower()` | `1 nextRangeInt(($warPower+100)/2, 100)` **only if** `$warPower < 100` | `WarUnit.php:280-288` — runs inside `beginPhase()` for BOTH sides each phase |
| `WarUnitGeneral::tryWound()` | `nextBool(0.05)` then, **if true**, `nextRangeInt(10,80)` | `WarUnitGeneral.php:306-325` — called post-battle-resolution on win |

> `criticalDamage()` is invoked *by triggers* (필살발동/격노발동) so its draw is counted in those rows.
> `calcDamage()`, `computeWarPower()` (via `beginPhase`), and `tryWound()` are invoked by the **macro loop**,
> not by triggers — they interleave with trigger draws and are placed in §4.

---

## 2. Dispatch mechanics — how draws compose into one ordered stream

### 2.1 Trigger object identity, priority, dedup
- `ObjectTrigger` (`hwe/sammo/ObjectTrigger.php`) defines priority bands (lower = earlier):
  `PRIORITY_MIN=0, BEGIN=10000, PRE=20000, BODY=30000, POST=40000, FINAL=50000`. Each concrete trigger sets
  `$priority` as a band + offset.
- `getUniqueID()` for war triggers = `"{priority}_{FQCN}_{spl_object_id(unit)}_{raiseType}"`
  (`BaseWarUnitTrigger.php:19-29`). **The trailing `_{raiseType}` is the dedup key**: two instances of the same
  trigger class on the same unit with the **same raiseType collapse to one** (later overwrites earlier in the
  `[$uniqueID=>$trigger]` map); **different raiseType = two distinct entries that BOTH fire.**
  This is why `che_견고` passes `TYPE_DEDUP_TYPE_BASE*404` (§6) — to *force* a distinct ID so its injected
  `che_부상무효` is not merged away by another source's `che_부상무효`.
- `TriggerCaller` (`hwe/sammo/TriggerCaller.php`) stores `triggerListByPriority[$priority][$uniqueID]`. On
  construct it `ksort`s by priority if not already ascending (`:47-49`). `merge()` (`:80-118`) does an ordered
  merge of two priority maps; equal priorities `array_merge` (RHS unique-IDs appended after LHS).
- **`fire()` (`TriggerCaller.php:121-128`) is the dispatch loop:** outer `foreach` over priority buckets
  (ascending), inner `foreach` over each bucket's `[$uniqueID=>$trigger]` in **insertion order**, calling
  `$trigger->action($rng,$env,$arg)`. **This nested iteration order IS the draw order within a phase.**

### 2.2 Insertion order = the tie-break that fixes the stream
Within one priority value, the inner-loop order is the order trigger entries were inserted into the map. The
caller is assembled as: **attacker's list first, then `merge(defender's list)`** (§4). So at equal priority,
**attacker's trigger fires before defender's**. The 7 base battle triggers (§4.2) are inserted in a fixed
literal order in `General::getBattlePhaseSkillTriggerList` (`General.php:920-928`), then each specialty/item/
crewType `merge`s its triggers in `getActionList()` order. **Port note for A3/F3: replicate
attacker-then-defender, priority-ascending, insertion-order-within-priority, raiseType-dedup EXACTLY.**

### 2.3 `actionWar` envelope + the `stopNextAction` short-circuit
`BaseWarUnitTrigger::action` (`:31-67`) is the wrapper every trigger inherits:
- If `$env['stopNextAction']` is already set → **returns immediately, the trigger body never runs, NO draw**
  (`:42-44`). `che_저지발동` is the only trigger that sets this (it `return false` from `actionWar`, and the
  wrapper translates a `false` return into `$env['stopNextAction']=true`, `:63-65`). **So once 저지 fires, ALL
  later-priority triggers in that phase are skipped and make zero draws.**
- It splits `$env` into `$selfEnv`/`$opposeEnv` by `isAttacker`, calls `actionWar`, swaps them back.

---

## 3. Master trigger table — all 36 `WarUnitTrigger` (priority-ascending)

Counts confirmed: **`ls WarUnitTrigger/` = 36 files** (review claim of 36 is CORRECT). Of these, **9 draw RNG**
(필살시도, 회피시도, 계략시도[up to 3], 저격시도, 약탈시도, 전투치료시도, 저지시도, 격노시도[up to 2], 저격발동[post-injury];
plus 필살발동 & 격노발동 draw indirectly via `criticalDamage()`), the rest are **pure stat-folds / skill-flag setters
that make ZERO draws but still occupy an ordering slot (matter for dedup + stopNextAction).**

Draws happen inside `actionWar` unless noted. `prio` = numeric priority. Korean class name shown.

| # | Trigger (class) | prio | raiseType | #draws | ordered draws (method · args · gate) | no-draw short-circuits | log lines | source |
|---|---|---|---|---|---|---|---|---|
| 1 | `WarActivateSkills` | 10000 (BEGIN) | ctor-arg | 0 | — pure: `self/oppose->activateSkill(...skills)` | n/a | none | `WarActivateSkills.php:11-33` |
| 2 | `능력치변경` | 10010 (BEGIN+10) | ctor-arg | 0 | — pure: setVar/inc/mul by operator; then `processConsumableItem()` | n/a | item-consume log only (via base) | `능력치변경.php:10-59` |
| 3 | `전투력보정` | 10020 (BEGIN+20) | 0 | 0 | — pure: `self->mul(attMul)`, `oppose->mul(defMul)`; `processConsumableItem()` | n/a | none | `전투력보정.php:10-24` |
| 4 | `che_선제사격시도` | 10050 (BEGIN+50) | 0 | 0 | — pure flag: `activateSkill('특수','선제')` | guards: phase!=0 both / already 선제 → return, no draw | none | `che_선제사격시도.php:15-35` |
| 5 | `che_궁병선제사격` *(deprecated)* | 10050 (BEGIN+50) | 0 | 0 | — pure: phase shift + warpower mul (not injected by current crewType list; kept for legacy) | many early-return guards | "서로 선제 사격…" battleDetail | `che_궁병선제사격.php:16-60` |
| 6 | `che_선제사격발동` | 10051 (BEGIN+51) | 0 | 0 | — pure: `addPhase(-1)` both; warpower mul (2/3 or 0); flag-set | not 선제 / 맞선제-attacker → return | "선제 사격…" battleDetail | `che_선제사격발동.php:15-50` |
| 7 | `che_위압시도` | 10100 (BEGIN+100) | 0 | 0 | — pure: `activateSkill('위압')`, oppose `activateSkill('회피불가','필살불가','계략불가')` | phase!=0 both / 위압불가 → return | none | `che_위압시도.php:10-29` |
| 8 | `che_성벽부상무효` | 10150 (BEGIN+150) | ctor-arg | 0 | — pure: if oppose is City → `activateSkill('부상무효')` | non-City → return | none | `che_성벽부상무효.php:10-23` |
| 9 | `che_부상무효` | 10200 (BEGIN+200) | ctor-arg (견고: `+1024*404`) | 0 | — pure: `activateSkill('부상무효')` | n/a | none | `che_부상무효.php:10-19` |
| 10 | `che_퇴각부상무효` | 10300 (BEGIN+300) | 0 | 0 | — pure: `activateSkill('퇴각부상무효')` | n/a | none | `che_퇴각부상무효.php:10-19` |
| 11 | `che_저지시도` | 20000 (PRE+0) | 0 | **1** | `nextBool($ratio/400)` where `$ratio = getComputedAtmos()+getComputedTrain()` → if true `activateSkill('특수','저지')` `:27` | attacker / 특수 / 저지불가 → return, **no draw** | none | `che_저지시도.php:11-32` |
| 12 | `che_저격시도` | 20100 (PRE+100) | ctor-arg | **1** | `nextBool($this->ratio)` `:42` → if true set 저격 flags + `selfEnv['woundMin/Max/addAtmos']` | self/oppose phase!=0 / oppose.phase<0 / 저격(불가) → return, **no draw** | none | `che_저격시도.php:28-53` |
| 13 | `che_필살시도` | 20120 (PRE+120) | 0 | **1** | `nextBool($self->getComputedCriticalRatio())` `:25` → if true `activateSkill('필살시도','필살')` | not General / 특수 / 필살불가 → return, **no draw** | none | `che_필살시도.php:11-33` |
| 14 | `che_필살강화_회피불가` | 20150 (PRE+150) | 0 | 0 | — pure: if self has '필살' → oppose `activateSkill('회피불가')` | no 필살 → return | none | `che_필살강화_회피불가.php:11-21` |
| 15 | `event_충차아이템소모` | 20200 (PRE+200) | 0 | 0 | — pure: siege item bookkeeping / `processConsumableItem()` | various early returns | "충차로 성벽을…" battleDetail | `event_충차아이템소모.php:14-46` |
| 16 | `che_회피시도` | 20200 (PRE+200) | 0 | **1** | `nextBool($self->getComputedAvoidRatio())` `:25` → if true `activateSkill('회피시도','회피')` | not General / 특수 / 회피불가 → return, **no draw** | none | `che_회피시도.php:11-33` |
| 17 | `che_계략시도` | 20300 (PRE+300) | 0 | **1–3** | (a) `nextBool($magicTrialProb)` `:57`; **only if (a) true:** (b) `choice(array_keys(tableToCity\|tableToGeneral))` `:66/70`; (c) `nextBool($magicSuccessProb)` `:79` → sets '계략' or '계략실패' + `selfEnv['magic']` | **계략불가 → return, NO draw** `:33`; `$magicTrialProb<=0 → return` no draw `:43`; if (a) false → 1 draw only | none here | `che_계략시도.php:27-89` |
| 18 | `che_전투치료시도` | 20350 (PRE+350) | 0 | **1** | `nextBool(0.4)` `:22` → if true `activateSkill('치료')` | 치료 / 치료불가 → return, **no draw** | none | `che_전투치료시도.php:11-30` |
| 19 | `che_약탈시도` | 20400 (PRE+400) | ctor-arg | **1** | `nextBool($this->ratio)` `:38` → if true `activateSkill('약탈')` + `selfEnv['theftRatio']` | phase!=0 both / oppose not General / 약탈(불가) → return, **no draw** | none | `che_약탈시도.php:24-46` |
| 20 | `che_반계시도` | 30300 (BODY+300) | ctor-arg (`prob=0.4`) | **1** | `nextBool($this->prob)` `:29` → if true `activateSkill('반계')`, oppose `deactivateSkill('계략')` | oppose has no '계략' / 반계불가 → return, **no draw** | none | `che_반계시도.php:21-39` |
| 21 | `che_격노시도` | 30400 (BODY+400) | 0 | **0–2** | **branch on oppose skill:** if oppose has '필살' → set 격노, **then if `isAttacker()`: `nextBool(1/2)` `:25`** (→진노). **else if oppose has '회피': `nextBool(1/4)` `:29`**; **if that true** → set 격노, **then if `isAttacker()`: `nextBool(1/2)` `:32`** (→진노). | oppose has neither 필살 nor 회피 → return, **no draw**; 격노불가 → return, no draw | none | `che_격노시도.php:14-37` |
| 22 | `che_저격발동` | 40100 (POST+100) | ctor-arg | **0–1** | **POST-resolution injury draw:** `nextRangeInt($selfEnv['woundMin'],$selfEnv['woundMax'])` `:50` — **ONLY if** oppose lacks '부상무효' AND oppose is `WarUnitGeneral`. Also `increaseVarWithLimit('atmos', addAtmos)` (no draw). `processConsumableItem()`. | not '저격' / wrong raiseType / already 저격발동 → return, **no draw**; injury draw skipped if 부상무효 or oppose is City | "저격했다/당했다!" action+battleDetail | `che_저격발동.php:19-56` |
| 23 | `che_반계발동` | 40250 (POST+250) | 0 | 0 | — pure: `multiplyWarPowerMultiply($damage)` from `opposeEnv['magic']` | no '반계' → return | "반계로…되돌렸다!" battleDetail | `che_반계발동.php:15-33` |
| 24 | `che_계략발동` | 40300 (POST+300) | 0 | 0 | — pure: `multiplyWarPowerMultiply($damage)` (folded by onCalcStat) | no '계략' / already 계략발동 → return | "{magic} 성공했다!" battleDetail | `che_계략발동.php:15-40` |
| 25 | `che_계략실패` | 40300 (POST+300) | 0 | 0 | — pure: self `mul(1/$damage)`, oppose `mul($damage)` | no '계략실패' / already → return | "{magic} 실패했다!" battleDetail | `che_계략실패.php:15-40` |
| 26 | `che_약탈발동` | 40350 (POST+350) | 0 | 0 | — pure: transfer gold/rice by `theftRatio`; `processConsumableItem()` | no '약탈' / already / oppose not General → return | "약탈했다/당했다!" action+battleDetail | `che_약탈발동.php:19-55` |
| 27 | `che_필살발동` | 40400 (POST+400) | 0 | **1 (indirect)** | `multiplyWarPowerMultiply($self->criticalDamage())` `:28` — **`criticalDamage()` = 1 `nextRange(1.3..2.0 folded)`** | no '필살' / already 필살발동 → return, **no draw** | "필살 공격!" battleDetail | `che_필살발동.php:15-31` |
| 28 | `che_회피발동` | 40500 (POST+500) | 0 | 0 | — pure: oppose `multiplyWarPowerMultiply(1/6)` | no '회피' → return | "회피했다!" battleDetail | `che_회피발동.php:15-26` |
| 29 | `che_전투치료발동` | 40550 (POST+550) | 0 | 0 | — pure: oppose `mul(0.7)`, self `setVar('injury',0)`; `processConsumableItem()` | no '치료' / already → return | "치료했다!" battleDetail | `che_전투치료발동.php:15-34` |
| 30 | `che_격노발동` | 40600 (POST+600) | 0 | **1 (indirect)** | `multiplyWarPowerMultiply($self->criticalDamage())` `:32` (1 `nextRange`); if 진노 also `addBonusPhase(1)` (no draw) | no '격노' → return, **no draw** | "격노/진노했다!" battleDetail | `che_격노발동.php:15-35` |
| 31 | `che_위압발동` | 40700 (POST+700) | 0 | 0 | — pure: oppose `setWarPowerMultiply(0)`, oppose atmos −5 | no '위압' → return | "위압받았다/줬다!" battleDetail | `che_위압발동.php:15-28` |
| 32 | `che_전멸시페이즈증가` | 40800 (POST+800) | 0 | 0 | — pure: if self.phase!=0 & oppose.phase==0 → `addBonusPhase(1)` | else no-op | "진격이 이어집니다!" battleDetail | `che_전멸시페이즈증가.php:15-23` |
| 33 | `che_돌격지속` | 40900 (POST+900) | 0 | 0 | — pure: bonus-phase bookkeeping by attackCoef/phase | City / not attacker → return | none | `che_돌격지속.php:15-35` |
| 34 | `che_저지발동` | 40000 (POST+0) | 0 | 0 | — pure: phase shifts, dex/exp, warpower=0 both. **`return false` → sets `stopNextAction`, halting all later triggers this phase** | no '저지' / already 저지발동 → return | "저지했다/당했다!" battleDetail | `che_저지발동.php:14-51` |
| 35 | `che_기병병종전투` | 50100 (FINAL+100) | 0 | 0 | — pure: small warpower muls by attacker/City | n/a | none | `che_기병병종전투.php:11-29` |
| 36 | `che_방어력증가5p` | 50200 (FINAL+200) | 0 | 0 | — pure: if defender → oppose `mul(1/1.05)` | attacker → no-op | none | `che_방어력증가5p.php:11-20` |

> **Important ordering note:** `che_저지발동` sets priority `PRIORITY_POST` (=40000), which is the *lowest* POST
> value, so it fires BEFORE all other POST triggers — meaning if 저지 activated, its `stopNextAction` halts the
> rest of POST/FINAL this phase. `che_저지시도` is `PRIORITY_PRE` (=20000), the lowest PRE, firing before all
> other PRE draws. (`:12` "최 우선 순위" / `:15` "최우선 순위".)

> **`event_충차아이템소모` and `che_회피시도` both have priority 20200.** Tie broken by insertion order
> (attacker-then-defender, then merge order). Neither's draw count depends on the other; 회피시도 draws 1, 충차
> draws 0.

---

## 4. Macro dispatch order — phase-by-phase from `process_war.php`

`processWar` → builds defender list (sorted by `extractBattleOrder` desc, `:59-61`) → calls
`processWar_NG($warSeed, $attacker, $getNextDefender, $city)`. The per-phase loop (the draw spine):

### 4.1 Per defender, per phase (the loop body, `process_war.php` ~250-460)
For each `(attacker vs current defender)` phase iteration, in this exact order:

1. **(first contact with a NEW defender only)** `setOppose` both, then **INIT caller**:
   ```php
   $initCaller = attacker->getGeneral()->getBattleInitSkillTriggerList(attacker);
   $initCaller->merge( defender->getGeneral()->getBattleInitSkillTriggerList(defender) );
   $initCaller->fire(attacker->rng, [], [attacker,defender]);   // :331-334
   ```
   Init list comes only from specialties' `getBattleInitSkillTriggerList` (e.g. `che_견고` → `che_부상무효`).
   **Init triggers are all pure-fold/flag (zero draws) in the current 21 specialties.**

2. `attacker->beginPhase()` then `defender->beginPhase()` (`:337-338`). **`beginPhase` =
   `clearActivatedSkill()` + `computeWarPower()`.** `computeWarPower` may draw **1 `nextRangeInt`** *iff*
   `$warPower<100` (`WarUnit.php:287`). **Order: attacker's computeWarPower draw (if any) precedes defender's.**

3. **PHASE caller** (the bulk of trigger draws):
   ```php
   $battleCaller = attacker->getGeneral()->getBattlePhaseSkillTriggerList(attacker);
   $battleCaller->merge( defender->getGeneral()->getBattlePhaseSkillTriggerList(defender) );
   $battleCaller->fire(attacker->rng, [], [attacker,defender]);  // :340-343
   ```
   This `fire()` runs the §3 table priority-ascending. **All §3 trigger draws happen here, in §3 order, both
   sides interleaved by priority then insertion (attacker-before-defender at equal priority).**

4. `$deadDefender = attacker->calcDamage();` then `$deadAttacker = defender->calcDamage();` (`:345-346`).
   **Each `calcDamage` = 1 `nextRange(0.9,1.1)`. Order: attacker's calcDamage draw, THEN defender's.**

5. HP/kill resolution (`:347-373`): clamping + `decreaseHP`/`increaseKilled` — **no draws.**

6. retreat / `continueWar` / win-loss logging (`:374+`). On a **win** (defender wiped, not a non-siege city),
   `attacker->tryWound()` then `defender->tryWound()` (`process_war.php`, win branch). **Each `tryWound` (for a
   `WarUnitGeneral`) = `nextBool(0.05)` and, if true, `nextRangeInt(10,80)`.** Order: attacker then defender.

### 4.2 The 7 always-present base phase triggers
`General::getBattlePhaseSkillTriggerList` (`General.php:918-938`) seeds the caller — **before** any specialty
merge — with exactly these 7, in this literal order (`:920-927`):
`che_필살시도, che_필살발동, che_회피시도, che_회피발동, che_계략시도, che_계략발동, che_계략실패`.
Then `foreach getActionList()` merges each specialty's contribution. **So even a no-specialty general always
contributes 필살시도(1 draw)+회피시도(1 draw)+계략시도(1–3 draws) to the stream per phase (gated by their guards).**

### 4.3 Draw sources outside the 21 specialties (crewType + items)
- **crewType** (`GameUnitConstBase.php`) injects via the GameUnitDetail trigger columns:
  `che_선제사격시도/발동` (archer & some), `che_기병병종전투` (cavalry), `che_방어력증가5p` (footman-ish),
  `che_성벽부상무효`+`che_저지시도/발동` (city wall units). **Of these only `che_저지시도` draws (1 `nextBool`).**
  (`GameUnitConstBase.php:50,124-160,170-233,325,343,352`.)
- **items** (`ActionItem/*`) inject `전투력보정`, `능력치변경`, `che_약탈시도`, `che_저격시도`, `WarActivateSkills` etc.
  via `raiseType=TYPE_ITEM|TYPE_CONSUMABLE_ITEM` so `processConsumableItem()` consumes the item. **Item-injected
  `che_저격시도`/`che_약탈시도` each add their 1 `nextBool` draw; `전투력보정`/`능력치변경` add zero.**

---

## 5. `ActionSpecialWar` → trigger / stat-fold injection table

Counts confirmed: **`ls ActionSpecialWar/` = 21 files** (incl. `None.php`) — review claim of 21 is CORRECT.
"Phase triggers" = added to the per-phase caller; "Init triggers" = added to the first-contact init caller.
Stat-folds change draw *probabilities* (so they shift WHICH branch a `nextBool` takes — parity-relevant — but
add no draws themselves).

| Specialty | id | injects (phase) | injects (init) | stat-fold (`onCalcStat`/`onCalcOpposeStat`/`getWarPowerMultiplier`) | source |
|---|---|---|---|---|---|
| `che_필살` | 71 | `che_필살강화_회피불가` | — | `warCriticalRatio +0.30`; `criticalDamageRange → [(min+max)/2, max]` | `che_필살.php:27-43` |
| `che_저격` | 70 | `che_저격시도($unit, TYPE_NONE, 0.5, 20, 40)`, `che_저격발동` | — | none | `che_저격.php:25-30` |
| `che_위압` | 63 | `che_위압시도`, `che_위압발동` | — | none | `che_위압.php:23-28` |
| `che_격노` | 74 | `che_격노시도`, `che_격노발동` | — | `getWarPowerMultiplier = [1+0.2*격노cnt, 1]` | `che_격노.php:23-33` |
| `che_반계` | 45 | `che_반계시도`, `che_반계발동` | — | self `warMagicSuccessDamage +0.9` if magic=='반목'; oppose `warMagicSuccessProb −0.1` | `che_반계.php:25-45` |
| `che_의술` | 73 | `che_전투치료시도`, `che_전투치료발동` | — | (also pre-turn `che_도시치료` general trigger — out of battle scope) | `che_의술.php:34-40` |
| `che_돌격` | 60 | `che_돌격지속` | — | `initWarPhase +2`; `getWarPowerMultiplier=[1.05,1]` if attacker | `che_돌격.php:24-41` |
| `che_견고` | 62 | `che_부상무효($unit, TYPE_NONE + TYPE_DEDUP_TYPE_BASE*404)` | **same `che_부상무효`** | oppose `warMagicSuccessProb −0.1`, `warCriticalRatio −0.20`; `getWarPowerMultiplier=[1,0.9]` | `che_견고.php:36-53` |
| `che_보병` | 50 | — | — | `getWarPowerMultiplier` (atk [1,0.9]/def [1,0.8]); dex-fold; domestic cost −10% | `che_보병.php:30-49` |
| `che_기병` | 52 | — | — | `getWarPowerMultiplier` (atk [1.2,1]/def [1.1,1]); dex-fold; domestic | `che_기병.php:30-49` |
| `che_궁병` | 51 | — | — | `warAvoidRatio +0.2`; dex-fold; domestic | `che_궁병.php:30-45` |
| `che_공성` | 53 | — | — | `getWarPowerMultiplier=[2,1]` vs City; dex-fold; domestic | `che_공성.php:30-49` |
| `che_귀병` | 40 | — | — | `warMagicSuccessProb +0.2`; dex-fold; domestic | `che_귀병.php:29-44` |
| `che_환술` | 42 | — | — | `warMagicSuccessProb +0.1`; `warMagicSuccessDamage ×1.3` | `che_환술.php:20-28` |
| `che_집중` | 43 | — | — | `warMagicSuccessDamage ×1.5` | `che_집중.php:20-25` |
| `che_신중` | 44 | — | — | `warMagicSuccessProb +1` (→ guaranteed; `nextBool` short-circuits to true, **NO draw consumed**) | `che_신중.php:20-25` |
| `che_신산` | 41 | — | — | `warMagicTrialProb +0.2`, `warMagicSuccessProb +0.2`; domestic 계략 success +0.1 | `che_신산.php:28-36` |
| `che_척사` | 75 | — | — | `getWarPowerMultiplier=[1.2,0.8]` vs region/city crew | `che_척사.php:22-28` |
| `che_징병` | 72 | — | — | `leadership +25%`; domestic train/atmos overrides | `che_징병.php:22-46` |
| `che_무쌍` | 61 | — | — | `warCriticalRatio +0.1` if attacker; `getWarPowerMultiplier` log(killnum)-scaled | `che_무쌍.php:23-37` |
| `None` | 0 | — | — | none (disabled placeholder) | `None.php:7-18` |

**11 of 21 specialties inject triggers; the rest are pure stat-folds.** `che_신중`'s `+1` to
`warMagicSuccessProb` is the clearest parity trap: it drives `nextBool($magicSuccessProb)` `≥1`, which
**short-circuits to `true` with no draw** — so a 신중 general consumes ONE FEWER draw at the 계략시도 success step
than a non-신중 general. F3/A3 must honor the `nextBool` short-circuits §1.

---

## 6. The `che_견고` dedup raiseType — worked detail

`che_견고` injects `che_부상무효` with `raiseType = TYPE_NONE + TYPE_DEDUP_TYPE_BASE*404` (= `0 + 1024*404`).
`che_부상무효` makes **zero draws** so this does not change the stream length — BUT the `_{raiseType}` suffix in
`getUniqueID()` (`BaseWarUnitTrigger.php:28`) makes 견고's instance distinct from any other `che_부상무효` (e.g.
crewType-injected `che_성벽부상무효` uses a different class entirely; another 견고 on the same unit would dedup).
**Lesson for the port:** dedup is on `(priority, FQCN, objectId, raiseType)`. Replicate the tuple. Two
zero-draw triggers deduping or not deduping has no stream effect *here*, but the same mechanism on a *drawing*
trigger (e.g. two item-injected `che_저격시도` with the same vs different raiseType) WOULD change draw count —
so the dedup rule is load-bearing in general.

---

## 7. Draw-stream assembly — worked example

**Setup:** attacker A (specialties: `che_필살`, `che_저격`; crewType has no injected drawing trigger) vs one
defender D (specialty: `che_의술`; no drawing crewType trigger). Single defender, examine phase 0. `warSeed`
fixed; one shared `$rng`.

### Phase setup
- First contact → **init caller fires.** A's init list: 필살→`che_필살강화_회피불가`(no init), 저격→(no init);
  D's init: 의술→(no init). **Init draws: 0.**
- `A.beginPhase()`: `computeWarPower()` → **draw #1 only if A.warPower<100** = `nextRangeInt((wp+100)/2,100)`.
  Assume A.warPower≥100 → **no draw.**
- `D.beginPhase()`: same → assume ≥100 → **no draw.**

### Phase caller assembly (priority-ascending, A-before-D at ties)
A's phase list = 7 base [필살시도, 필살발동, 회피시도, 회피발동, 계략시도, 계략발동, 계략실패] merged with
필살→[필살강화_회피불가], 저격→[저격시도(0.5,20,40), 저격발동].
D's phase list = 7 base merged with 의술→[전투치료시도, 전투치료발동]. Then `A.merge(D)`.

Resulting fire order (only **drawing** steps listed; `[A]`/`[D]` = owner; non-drawing pure triggers omitted
but still iterated):

| draw seq | trigger (owner) | prio | method · args | note |
|---|---|---|---|---|
| 1 | `che_저격시도` [A] | 20100 | `nextBool(0.5)` | A's 저격 (item/spec arg ratio=0.5) |
| 2 | `che_필살시도` [A] | 20120 | `nextBool(A.getComputedCriticalRatio())` | crit ratio folded by 필살 `+0.30` |
| 3 | `che_필살시도` [D] | 20120 | `nextBool(D.getComputedCriticalRatio())` | D after A at equal prio |
| 4 | `che_회피시도` [A] | 20200 | `nextBool(A.getComputedAvoidRatio())` | — |
| 5 | `che_회피시도` [D] | 20200 | `nextBool(D.getComputedAvoidRatio())` | — |
| 6 | `che_계략시도` [A] | 20300 | `nextBool(A.magicTrialProb)` | if true → +`choice` + `nextBool(successProb)` |
| 6a/6b | (A 계략 cont.) | 20300 | `choice(magicTable)`, `nextBool(0.7±folds)` | only if draw 6 true |
| 7 | `che_계략시도` [D] | 20300 | `nextBool(D.magicTrialProb)` | (+choice/+success if true) |
| 8 | `che_전투치료시도` [D] | 20350 | `nextBool(0.4)` | D's 의술 |
| 9 | `che_저격발동` [A] | 40100 | `nextRangeInt(20,40)` | **only if** A activated 저격 at #1 AND D lacks 부상무효 AND D is General |
| 10 | `che_필살발동` [A] | 40400 | `criticalDamage()` → `nextRange(folded 1.3..2.0)` | only if A activated 필살 at #2 |
| 11 | `che_필살발동` [D] | 40400 | `criticalDamage()` | only if D activated 필살 at #3 |
| 12 | `che_계략발동`/`실패` | 40300 | (no draw — pure fold) | ordering slot only |

### Macro tail (after `fire`)
| draw seq | call | method | note |
|---|---|---|---|
| 13 | `A.calcDamage()` | `nextRange(0.9,1.1)` | attacker first |
| 14 | `D.calcDamage()` | `nextRange(0.9,1.1)` | then defender |
| (win) 15 | `A.tryWound()` | `nextBool(0.05)` [+`nextRangeInt(10,80)` if true] | only on win branch |
| (win) 16 | `D.tryWound()` | `nextBool(0.05)` [+`nextRangeInt(10,80)` if true] | only on win branch |

**This ordered list — with the conditional steps included/excluded by their guards — is exactly what G1b must
replay and byte-match.** Next phase repeats from "Phase setup" with the *same* `$rng` cursor (no reseed).

---

## 8. The three trickiest draw-order subtleties (call these out for F3/A3/G1b)

1. **`nextBool` probability short-circuits silently change stream length.** `nextBool($p)` consumes **zero**
   draws when `$p>=1` or `$p<=0`, and consumes a **bit** (not a float) when `$p===0.5` exactly
   (`RandUtil.php:40-51`). `che_신중` (`warMagicSuccessProb +1`) and 위압-forced `회피불가`/`필살불가` (which make
   `getComputedCriticalRatio/AvoidRatio` paths still draw, but the *발동* gate change) mean a stat-fold can add
   OR remove a draw without any visible trigger change. Port the short-circuits verbatim; never "round" a
   probability.

2. **`che_저지발동`'s `return false` → `stopNextAction` kills every later trigger in the phase.** Because its
   priority is `PRIORITY_POST` (40000, the lowest POST), once 저지 activates it fires before 저격발동/필살발동/etc.
   and the `BaseWarUnitTrigger::action` wrapper sets `$env['stopNextAction']`, so **all higher-priority-number
   triggers that phase early-return with zero draws** (`BaseWarUnitTrigger.php:42-44, 63-65`). A naive port
   that keeps firing triggers after 저지 will over-draw and desync.

3. **저격's draw is split across two priority bands and gated POST-resolution.** `che_저격시도` draws its
   `nextBool(ratio)` in the PRE band (20100), but the **injury `nextRangeInt(woundMin,woundMax)`** is a SECOND,
   separate draw in `che_저격발동` at POST (40100) — and it is **conditionally skipped** when the opponent has
   `부상무효` or is a `WarUnitCity` (`che_저격발동.php:49-51`). Same split-and-gate shape for 격노시도's nested
   `nextBool(1/2)` 진노 roll, which fires **only when `isAttacker()`** and only inside the taken branch
   (`che_격노시도.php:25,32`). Mis-modeling either the band split or the post-resolution gate corrupts the stream
   from that point on.

---

## 9. Gaps / unresolved (genuinely not determinable from the trigger sources alone)

- **`extractBattleOrder` / defender sort + multi-defender continuation** (`process_war.php:48-86`): the catalog
  fixes per-(attacker,defender) phase draw order, but the *number of defenders* and when `getNextDefender`
  advances depend on `extractBattleOrder` and HP outcomes — these feed the loop count, not the per-phase draw
  order. The phase-count/defender-count logic is F3's spine and should be transcribed from `processWar_NG`
  directly (not covered draw-by-draw here since it makes no RNG draws itself).
- **`WarUnitCity` draws:** this catalog covers `WarUnit`/`WarUnitGeneral` primitives. `WarUnitCity::calcDamage`
  / `computeWarPower` overrides were not opened here; the base `WarUnit::calcDamage` (1 `nextRange(0.9,1.1)`)
  and `computeWarPower` (`WarUnit.php`) apply unless City overrides them — **verify `WarUnitCity.php` before
  G1b** (a city defender's calcDamage/beginPhase draw must be confirmed identical).
- **Item-injected trigger raiseTypes / exact item args** (`ActionItem/*`): items inject 저격시도/약탈시도/전투력보정
  with item-specific `ratio`/`raiseType` constructor args; the *per-item* numeric args were not enumerated
  (18+ item files). For G1b fixtures that include items, transcribe each item's `getBattlePhaseSkillTriggerList`
  args individually.
- **`getCrewType()->getCriticalRatio()/getAvoidRatio()` base values** live in `GameUnitConstBase.php` crewType
  rows — the *probability inputs* to 필살/회피's `nextBool`. They don't change draw *order/count* but DO change
  which branch is taken; transcribe the crewType numeric table when building the expected-value oracle.
