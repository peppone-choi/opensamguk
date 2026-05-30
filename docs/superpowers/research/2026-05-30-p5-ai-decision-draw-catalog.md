# P5 AI-Decision Draw Catalog — the GeneralAI per-turn RNG-stream parity oracle

**Status:** source-transcribed from PHP grand truth (`legacy/devsam-core/hwe/sammo/GeneralAI.php`, 4293 lines). Every draw cites a real `file:line`, re-grepped line-for-line (`$this->rng->*` 185→4102) and cross-checked against the P5 research facets A2/A3–A10 + GAPS.md.
**Date:** 2026-05-30
**Scope:** the single, ordered RNG draw stream produced by one `GeneralAI` instance over one general's turn (general-turn OR nation-turn dispatch), as driven by `chooseGeneralTurn` / `chooseNationTurn` / `chooseInstantNationTurn`. This is the P5 analogue of the P4 war-trigger draw catalog: replay the Kotlin AI on the same per-general seed and the ordered `(method, args) → value` draws must match PHP byte-for-byte.

> **PHP wins every divergence.** Where core2026 (TS) diverges it is noted inline and PHP is the target. The biggest confirmed TS divergence in this surface: TS **drops** the `do선전포고` re-target abort draw (B) `nextBool(1/count(lowTargetNations))` (§5.D) — PHP has up to 3 draws there, naive TS has 2.

---

## 0. Why this is the single most parity-critical P5 deliverable

A whole AI turn consumes ONE `RandUtil` wrapping ONE `LiteHashDRBG`, built **once** in `__construct` (`GeneralAI.php:153-159`) and **never reseeded**:

```php
// GeneralAI.php:14   protected RandUtil $rng;
// GeneralAI.php:153-159
$this->rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
    UniqueConst::$hiddenSeed,   // string
    'GeneralAI',                // string literal
    $this->env['year'],         // int
    $this->env['month'],        // int
    $general->getID(),          // int
)));
```

`LiteHashDRBG` is a non-reseeding SHA-512 DRBG with a monotonic `stateIdx` byte cursor (same kernel proven in P0-B). Every draw consumes a deterministic number of bytes; `nextInt`/`nextFloat1` rejection loops consume EXTRA bytes per rejection. Therefore:

> **One extra, missing, or mis-ordered draw shifts every subsequent draw and desyncs the whole turn.**

Unlike a battle (where the draw spine is the phase machine), here the draw spine is the **dispatcher priority loop**: the order in which `do*` methods fire IS the draw order. A `do*` that returns `null` earlier/later (because a constraint `hasFullConditionMet()` flips, or a guard threshold differs by one unit) shifts every later draw. The two hardest desync classes are:

1. **`nextBool` probability short-circuits** (a draw that silently appears/disappears), and
2. **`&&`/`||` short-circuit guards** (a draw consumed only when the left operand allows).

Both are hammered below per call site.

---

## 1. Seed synthesis (EXACT — the single most critical artifact)

### 1.1 Field order (do NOT reorder), `__construct` L153-159
| # | value | PHP type | notes |
|---|---|---|---|
| 1 | `UniqueConst::$hiddenSeed` | string | `'891ff83c3dee6932dd87bbc36d09d201'` (32 ASCII chars, `hwe/d_setting/UniqueConst.php:8`). `mb_strlen`=32. |
| 2 | `'GeneralAI'` | string literal | 9 chars. |
| 3 | `$this->env['year']` | int | game year. |
| 4 | `$this->env['month']` | int | game month. |
| 5 | `$general->getID()` | int | general's `no`. |

**Per-general, per-(year,month) seed.** (Contrast: warSeed in P4 is per-battle and includes destCityId.)

### 1.2 `Util::simpleSerialize` format (`Util.php:872-891`)
```
str(<mb_strlen>,<rawvalue>)   for strings  (length = CHAR count via mb_strlen, NOT byte count)
int(<value>)                  for ints     (raw decimal)
float(<number_format(v,6,'.','')>)  for floats  (6 decimals — none used here)
```
joined by `'|'`. **Resulting GeneralAI seed string:**
```
str(32,891ff83c3dee6932dd87bbc36d09d201)|str(9,GeneralAI)|int(<year>)|int(<month>)|int(<genID>)
```
Example (year=180, month=1, genID=42):
```
str(32,891ff83c3dee6932dd87bbc36d09d201)|str(9,GeneralAI)|int(180)|int(1)|int(42)
```
No float fields → the `number_format` path is never exercised by the GeneralAI seed. The seed+`'GeneralAI'` are ASCII so `mb_strlen`==byte-count here; the mb-vs-byte distinction matters only if hiddenSeed ever held multibyte.

> **No RNG draw in `__construct`.** The first POSSIBLE draw is in `calcGenType` (called from `updateInstance`, §3).

---

## 2. RandUtil primitive semantics (needed to interpret every draw) — `src/sammo/RandUtil.php`

| Method | Lines | Draws on DRBG | Behavior |
|---|---|---|---|
| `nextFloat1()` | 11-14 | **1 float** (`nextBits(54)`, may reject) | atomic primitive. |
| `nextRange(min,max)` | 16-20 | **1 float** | `nextFloat1()*(max-min)+min`. |
| `nextRangeInt(min,max)` | 22-29 | **1 int** | `rng->nextInt(max-min)+min` → int in `[min,max]` inclusive. |
| `nextInt(?max)` | 31-33 | **1 int** | passthrough; rejection-sampled, INCLUSIVE of max. |
| `nextBit()` | 35-38 | **1 bit** (1 byte) | `rng->nextBits(1) !== "\0"`. Distinct byte path from `nextFloat1`. |
| `nextBool(prob=0.5)` | 40-52 | **0 OR 1** — see CRITICAL | short-circuits below. |
| `choice(arr)` | 94-102 | **1 int** on `count-1` | `keys=array_keys(items); idx=nextInt(count(keys)-1); return items[keys[idx]]`. Insertion order defines keys; INCLUSIVE upper bound. |
| `choiceUsingWeight(arr)` | 104-131 | **1 float** | sum positive values; `rd=nextFloat1()*sum`; walk in **insertion order**, return first KEY where `rd<=value` else `rd-=value`. RETURNS THE KEY. |
| `choiceUsingWeightPair(arr)` | 133-161 | **1 float** | same as weight but items are `[item,weight]` pairs; destructure `[$item,$value]`; RETURNS `$item` (pair[0]). Insertion order matters. |
| `shuffle(arr)` | 54-80 | N int draws | Fisher-Yates. **NOT used anywhere in GeneralAI.** |

### CRITICAL — `nextBool` short-circuits = silent stream-length changes (`RandUtil.php:40-52`)
```php
public function nextBool(int|float $prob = 0.5): bool {
    if ($prob >= 1)   return true;        // NO DRAW
    if ($prob === 0.5) return $this->nextBit();   // 1 BIT draw (NOT a float)
    if ($prob <= 0)   return false;       // NO DRAW
    return $this->nextFloat1() < $prob;   // 1 FLOAT draw
}
```
- `prob >= 1` → true, **no draw**. `prob <= 0` → false, **no draw**.
- `prob === 0.5` EXACTLY (strict `===`, float identity) → `nextBit()` (1 byte) — a **different byte cost** than `nextFloat1`.
- Any other prob in (0,1) → `nextFloat1()`.
- **Compute the probability identically to PHP to decide the draw path.** Whether `nextBool($x)` draws at all, and how many bytes it pulls, depends on the runtime value of `$x`.

### CRITICAL — `choice` / `choiceUsingWeight*` insertion order
- `choice` over a **list** draws `nextInt(n-1)` and returns `items[idx]`; over an **assoc** the keys are the assoc keys. Even a 1-element array still draws (`nextInt(0)=0`).
- `choiceUsingWeight` RETURNS THE KEY (`$item` in `foreach($items as $item=>$value)`), weight = value; non-positive weights skipped in the sum and clamped to 0 in the walk; the single `nextFloat1` happens once regardless of list size.
- `choiceUsingWeightPair` RETURNS pair[0]. **Candidate-list insertion order is set by upstream sorts / BFS / DB-row order — it is itself a parity target** (the float-walk index depends on it). See §8.

---

## 3. Derived-state pre-pass — the FIRST possible draw (`updateInstance` → `calcGenType`)

`updateInstance()` (L86-145) runs at the top of every dispatcher (and re-runs whenever `reqUpdateInstance` was set dirty by a mutating command at L1308/1509/1972/1990/2113). It makes **no draw itself** but calls `calcDiplomacyState()` (no draw) then `calcGenType($general)` (L144), which holds the **first conditional draw of the whole turn**.

### `calcGenType` (L175-204) — 0 OR 1 draw
Inputs (L177-179, all `getX(false)` = no-injury, item/adjust/floor ON):
`leadership=getLeadership(false)`, `strength=valueFit(getStrength(false),1)`, `intel=valueFit(getIntel(false),1)`.

| seq | line | call | gate (draw fires only when…) | purpose |
|---|---|---|---|---|
| **1a** | 185 | `nextBool($intel / $strength / 2)` | `strength >= intel` AND `intel >= strength*0.8` | 무장→무지장 hybrid roll. `p∈[0.4,0.5)`. |
| **1b** | 193 | `nextBool($strength / $intel / 2)` | `strength < intel` AND `strength >= intel*0.8` | 지장→지무장 hybrid roll. `p∈[0.4,0.5)`. |

Exactly ONE of {1a,1b} can fire, and only inside the near-balance band (`weaker >= stronger*0.8`). Tie `strength==intel` → 무장 branch (`>=`). Outside the band → **zero draws**. The post-branch `통솔장` flag (L200, `leadership >= minNPCWarLeadership`) is NON-RNG.

> **HIGH-RISK (RANK #2 below).** This single conditional `nextBool` is the FIRST draw of the turn; its presence/absence shifts EVERY later draw in the shared stream. Must reproduce 1:1 incl. the float arg and the exact `getX(false)` stat flavor (A2 §10 / GAPS G8 flag the flavor mapping as UNCERTAIN — confirm Kotlin `getStatValue(false,T,T,T)`).

`calcDiplomacyState` / `calcWarRoute` / `categorizeNationCities` / `categorizeNationGeneral` make **zero draws** (A2 §4-8). They only set candidate ORDER (which feeds later `choice`/`choiceUsingWeight*` indices) — see §8.

---

## 4. The dispatcher draw spine — order in which `do*` fire

The draw order across a turn = the order the dispatcher invokes `do*`. Three entry points:

### 4.1 `chooseGeneralTurn` (L3709-3848) — general-action turn
Fixed prologue, THEN the policy-priority loop:
1. `updateInstance()` — may draw calcGenType (§3).
2. **L3719** `nextBool(npcMessageFreqByDay * term / (60*24))` — NPC-message broadcast gate, **drawn only if `getVar('npcmsg')` truthy** (`&&` short-circuit).
3. `defence_train=80` set (npc≥2; no draw). 선양 (L3745, officer_level==12 & can선양) → `do선양` (SQL-rand, NO drbg draw, §6).
4. **npcType==5** → `do집합` (L3759) → §5.G `nextRangeInt(2,4)` (only inside, when npc==5). Returns immediately after.
5. reserved-honor / 요양(injury) early returns (no draw).
6. **npc∈{2,3} & nation==0** → `do거병` (L3779) → §5.E (up to 4 draws).
7. **nation==0 & can국가선택** → `do국가선택` (L3787, §5.F) ELSE `do중립` (L3792, §5.H). (mutually exclusive with the priority loop.)
8. **npc≥2 & officer_level==12 & no capital** (wander) → `do건국` (L3807, §5.I two draws) / `do방랑군이동` (L3814, §5.J up to 2 draws) / `do해산` (no draw).
9. **priority loop** (L3829): `foreach generalPolicy->priority as actionName → do{actionName}()`, first non-null wins. Each may draw — see §5 per-method.
10. fallback **`do중립`** (L3845, §5.H).

### 4.2 `chooseNationTurn` (L3616-3683) — nation-action turn
1. `updateInstance()` (§3 calcGenType draw) → `categorizeNationGeneral()` → `categorizeNationCities()` (no draws).
2. **officer_level==12 & month∈{3,6,9,12}** → `choosePromotion()` (§5.M, per-chief-level conditional draws) + chooseTexRate/BillRate (NO draw). **officer_level!=12 & month∈{3,6,9,12}** → `chooseNonLordPromotion()` (§5.L, up to 5 `choice` per empty slot). (`use_auto_nation_turn` auxVar write L3630-3632 = side-effect, no draw — GAPS G10.)
3. reserved-honor / fail-log (no draw).
4. **priority loop** (L3661): `foreach nationPolicy->priority → do{actionName}($lastTurn)`. Each may draw — §5.
5. fallback neutral command (no draw).

### 4.3 `chooseInstantNationTurn` (L3685-3707)
`updateInstance()` then the priority loop restricted to `$availableInstantTurn` whitelist. Same per-`do*` draws as §5, subset only.

> **The priority-list ORDER (post policy-merge) = the do* dispatch order = log order = RNG draw order** (GAPS G1/G2). The `AutorunGeneralPolicy`/`AutorunNationPolicy` merge that produces `->priority` was NOT fully read; a wrong merge or wrong default value silently picks a different command and desyncs. **Resolve before gating.**

---

## 5. PER-METHOD ORDERED DRAW TABLE (the exhaustive map)

Format per row: `seq | line | method · args | gate | purpose`. Within a method the seq column is intra-method draw order (load-bearing). `[NO-DRAW gate]` marks where a `&&`/`||`/short-circuit suppresses the draw.

### 5.A Troop/general 발령 (deployment) — `choice` picks, generals-before-cities
PHP evaluates array-literal elements top-to-bottom, so in the double-`choice` literals the **general pick draws BEFORE the city pick**. In the two-stage methods the bare `choice($generalCandidates)` draws FIRST (used to compute a downstream threshold), THEN `choiceUsingWeight($cities)`.

| seq | line | method | call | note |
|---|---|---|---|---|
| — | 337/343/349 | `do부대전방발령` | `choice($this->frontCities)['city']` | one per troop in lost-route states (variable count). |
| — | 381 | `do부대전방발령` | `choice($nextCityCandidate)` | one per ambiguous advance hop (variable). |
| last | 391 | `do부대전방발령` | `choice($troopCandidate)` | exactly one final pick. |
| 1 | 477 | `do부대후방발령` | `choice($troopCandidate)->getID()` | array-literal: troop BEFORE city. |
| 2 | 478 | `do부대후방발령` | `choice($cityCandidates)['city']` | second in same literal. |
| 1 | 530 | `do부대구출발령` | `choice($troopCandidate)->getID()` | troop before city. |
| 2 | 531 | `do부대구출발령` | `choice($cityCandidates)['city']` | second. |
| 1 | 642 | `do부대유저장후방발령` | `choice($generalCadidates)->getID()` | general before city. |
| 2 | 643 | `do부대유저장후방발령` | `choice($cityCandidates)['city']` | second. |
| 1 | 696 | `do유저장후방발령` | `choice($generalCadidates)` | picks general (→minRecruitPop) FIRST. |
| 2 | 748 | `do유저장후방발령` | `choiceUsingWeight($recruitableCityList)` | dest city weighted by pop_ratio. |
| 1 | 866 | `do유저장전방발령` | `choice($generalCandidates)->getID()` | general first. |
| 2 | 867 | `do유저장전방발령` | `choiceUsingWeight($cityCandidates)` | dest front city weighted by 'important'. |
| 1 | 929 | `do유저장내정발령` | `choice($generalCandidates)` | source general first. |
| 2 | 931 | `do유저장내정발령` | `choiceUsingWeight($cityCandidiates)` | dest city weighted by score. |
| 1 | 996 | `doNPC후방발령` | `choice($generalCadidates)` | npc general first. |
| 2 | 1053 | `doNPC후방발령` | `choiceUsingWeight($recruitableCityList)` | recruit city weighted by pop_ratio. |
| 1 | 1144 | `doNPC전방발령` | `choice($generalCandidates)->getID()` | general first. |
| 2 | 1145 | `doNPC전방발령` | `choiceUsingWeight($cityCandidates)` | dest front weighted by 'important'. |
| 1 | 1203 | `doNPC내정발령` | `choice($generalCandidates)` | source general first. |
| 2 | 1205 | `doNPC내정발령` | `choiceUsingWeight($cityCandidiates)` | dest city weighted by score. |

### 5.B 구출발령 (rescue) — per-loop choice + final choice
| seq | line | method | call | note |
|---|---|---|---|---|
| loop | 793 | `do유저장구출발령` | `choice($this->frontCities)` | per qualifying lostGeneral: front (war/직전, >2 fronts). |
| loop | 795 | `do유저장구출발령` | `choice($this->supplyCities)` | else-branch (one per loop iter, builds `$args`). |
| last | 807 | `do유저장구출발령` | `choice($args)` | one final pick. **Draws = #qualifying lostGenerals + 1.** |
| loop | 1075 | `doNPC구출발령` | `choice($this->supplyCities)` | per lostGeneral in loop. |
| last | 1085 | `doNPC구출발령` | `choice($args)` | one final. **Draws = #qualifying + 1.** |

### 5.C 포상/몰수 (reward/confiscate) — single weighted-pair pick, weight set by a preceding sort
Each draws exactly ONE `choiceUsingWeightPair`. The candidate order (and thus weight via `count - idx`) is fixed by a `usort`/comparator immediately above — see §8. No draw in the sort itself.
| seq | line | method | call | weight |
|---|---|---|---|---|
| 1 | 1302 | `do유저장긴급포상` | `choiceUsingWeightPair($candidateArgs)` | `count - idx` (rank-rev), after L1247 usort ASC. |
| 1 | 1410 | `do유저장포상` | `choiceUsingWeightPair($candidateArgs)` | `count - idx`, after L1344 usort ASC. |
| 1 | 1503 | `doNPC긴급포상` | `choiceUsingWeightPair($candidateArgs)` | `count - idx`, after L1447 usort ASC. |
| 1 | 1625 | `doNPC포상` | `choiceUsingWeightPair($candidateArgs)` | `max(warCnt,civilCnt) - idx`, after L1544/1584 usort ASC. |
| 1 | 1755 | `doNPC몰수` | `choiceUsingWeightPair($candidateArgs)` | `takeAmount`, after L1663/1702 DESC. |

### 5.D `do선전포고` (war declaration) — up to 3 draws (L1848-1973)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| A | 1923 | `nextBool($trialProp)` | always reached (head L1848-1922 has NO draw) | trial gate. `$trialProp = (...)**6` — can be ≥1 (no draw, →true) or ≤0 (no draw, →false). |
| B | 1959 | `nextBool(1 / count($lowTargetNations))` | **only in the `$nations`-empty fallback** (already-at-war branch) | re-target abort. **Consumed even on the abort-null path. TS DROPS THIS — PHP wins (3 draws vs 2).** |
| C | 1966 | `choiceUsingWeight($nations)` | reached if not aborted | target nation, weight `1/sqrt(power+1)` (favors weak). Insertion order = `getAllNationStaticInfo()` order. |

Sets `reqUpdateInstance=true` (L1972) on success.

### 5.E `do거병` (rebellion/found) — up to 4 draws (L3217-3288)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 3232 | `nextBool(0.5)` | **only if `cityLevel<5 || 6<cityLevel`** (`&&` short-circuit); `0.5` → **nextBit path** | non-foundable-city 50% skip. |
| 2 | 3258 | `nextBool()` (default 0.5 → **nextBit**) | **per dist-3 candidate**, only when `dist==3`, until break | dist-3 city 50% skip. **Variable count — depends on BFS visit order (GAPS G3).** |
| 3 | 3268 | `nextFloat1() * (defaultStatNPCMax + chiefStatMin)/2` = `nextFloat1()*70` | always, once a near-city is found | rebellion stat-threshold prop. |
| 4 | 3278 | `nextBool(0.0075 * $more)` | always reached; `more∈{1,2,3}` → prob∈{0.0075,0.015,0.0225} (never 0.5/≥1/≤0) → always a float draw | final gate. |

> **HIGH-RISK (RANK #3 below):** the variable dist-3 loop draws hang off `searchDistance` BFS visitation order, which was NOT read (GAPS G3). Wrong BFS order → wrong dist-3 draw count → desync.

### 5.F `do국가선택` (nation choice) — gated tree (L3334-3401)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| (sql) | 3345 | `SELECT … ORDER BY RAND() LIMIT 1` | npcType==9 (오랑캐) | ruler nation for 임관. **NOT a drbg draw** (MySQL RNG) — §9. |
| 1 | 3358 | `nextBool(0.3)` | always reached | enter 임관 branch. |
| 1a | 3371 | `nextBool(pow(1/($nationCnt+1)/pow($notFullNationCnt,3), 1/4))` | inside 임관 AND early period (`year < startyear+3`) | early-game 임관 abort. **Float-exact pow/div parity target.** |
| 1b | 3376 | `nextBool()` (default 0.5 → **nextBit**) | inside 임관 AND NOT early period | post-early 50% abort (comment "0.3*0.5=0.15"). |
| 2 | 3390 | `nextBool(0.2)` | **only if L3358 `nextBool(0.3)` was FALSE** | move-instead branch. |
| 2a | 3393 | `choice($paths)` | inside the 0.2 branch | random adjacent city for `che_이동`. |

### 5.G `do집합` (assemble) — 0 or 1 draw (L3111-3125)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 3116 | `nextRangeInt(2, 4)` → `(killturn + draw) % 5` | **only if `getNPCType()==5`** | NPC type-5 killturn reroll → int in [2,4]. Else no draw. (Method has NO `hasFullConditionMet` gate — always returns its cmd.) |

### 5.H `do중립` (neutral fallback) — 0 or 1 draw (L3436-3466)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 3441 | `nextBool(0.8)` | **`|| ` short-circuit**: drawn only if `che_인재탐색` `hasFullConditionMet()` (nationID==0 path) | 견문 vs 인재탐색 (80%). |
| 1' | 3458 | `choice($candidate)` | non-zero-nation path; `count`∈{1,2} | pick 물자조달/인재탐색. **Always draws even with 1 element.** |

(Note: the nationID==0 path uses L3441; the in-nation path uses L3458 — mutually exclusive within one call.)

### 5.I `do건국` (found nation) — 2 draws, type-then-color (L3302-3316)
| seq | line | call | purpose |
|---|---|---|---|
| 1 | 3304 | `choice(GameConst::$availableNationType)` | random nation type. 13-elem list (`['che_도적',…,'che_법가']`) → `nextInt(12)`. |
| 2 | 3305 | `choice(array_keys(GetNationColors()))` | random color. **`GetNationColors()` is a LIST of 33 hex strings (keys 0..32)** → `nextInt(32)` returns an INDEX (GAPS G6 corrects A8's "key-order" framing: capture the 33-color list verbatim; draw is over indices 0..32). |

**Draw order is type THEN color — do not swap.**

### 5.J `do방랑군이동` (wander move) — up to 2 draws (L3127-3215)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 3180 | `choiceUsingWeightPair($candidateCities)` | only if `movingTargetCityID===null` (no cached target) | pick wander target, weight `1/2^dist` (BFS dist via searchDistance — GAPS G3). |
| 2 | 3208 | `choiceUsingWeightPair($candidateCities)` | reached if not returned earlier | next move step, weight `10` (foundable-adjacent) / `1` (toward target). |

### 5.K domestic / war-domestic / battle-prep — terminal weighted-pair pick + guard draws
| seq | line | method | call | gate |
|---|---|---|---|---|
| 1 | 2136 | `do일반내정` | `nextBool(0.3)` | **`&&`**: only if `nation.rice < baserice`. Low-rice skip 30%. |
| 2 | 2217 | `do일반내정` | `choiceUsingWeightPair($cmdList)` | terminal; only if `$cmdList` non-empty. |
| 1 | 2271 | `do전쟁내정` | `nextBool(0.3)` | **`&&`**: only if `nation.rice < baserice`. |
| 2 | 2279 | `do전쟁내정` | `nextBool(0.3)` | **ALWAYS drawn** (no guard). The double-0.3 is the trap. |
| 3 | 2362 | `do전쟁내정` | `choiceUsingWeightPair($cmdList)` | terminal; non-empty. |
| 1 | 2681 | `do전투준비` | `choiceUsingWeightPair($cmdList)` | pick 훈련/사기진작 weighted. |
| 1 | 2236 | `do긴급내정` | `nextBool($leadership / chiefStatMin)` | **`&&`**: only if `city.trust < 70`. (prob may exceed 1 → no draw, true; still gated.) |
| 2 | 2243 | `do긴급내정` | `nextBool($leadership / chiefStatMin / 2)` | **`&&`**: only if `city.pop < minNPCRecruitCityPopulation`. |

### 5.L `do징병` (recruit) — up to 3 draws (L2483-2600+)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 2512 | `nextBool($remainPop / $maxPop)` | **`&&`**: only if `pop/pop_max < safeRecruitCityPopulationRatio` AND `!can한계징병` | recruit-skip roll. |
| 2 | 2554 | `choiceUsingWeight($availableArmType)` | only if no preset `armType` | pick arm type. **Insertion order FOOTMAN, ARCHER, CAVALRY, (WIZARD)** — parity-critical (GAPS G7: 4 entries effectively always present). |
| 3 | 2580 | `choiceUsingWeight($types)` | always (post arm type) | pick crew type weighted by pickScore. |

### 5.M misc nation/general guards
| seq | line | method | call | gate |
|---|---|---|---|---|
| 1 | 2695 | `do소집해제` | `nextBool(0.75)` | **ALWAYS draws** — 75% skip disband. |
| 1 | 2720 | `do출병` | `nextBool(0.7)` | **`&&`**: only if `nation.rice < baserice` AND `getNPCType()>=2`. 70% skip sortie. |
| 2 | 2769 | `do출병` | `choice($attackableCities)` | pick target city for sortie. |
| 1 | 2841 | `doNPC헌납` | `nextBool(($genRes/$reqRes) - 0.5)` | **`&&`**: only if `reqRes>0`; used as `!nextBool(...)`. tribute gate. |
| 2 | 2858 | `doNPC헌납` | `choiceUsingWeightPair($args)` | pick tribute resource/amount weighted by amount. |
| 1 | 2100 | `do천도` | `choice($candidates)` | only if persistence-branch not taken AND chosen city `dist>1`. Intermediate stop toward best capital (after L2075 `arsort`). |

### 5.N warp trio (L2940-3090)
| seq | line | method | call | gate |
|---|---|---|---|---|
| 1 | 2960 | `do후방워프` | `choiceUsingWeight($recruitableCityList)` | warp dest weighted by pop_ratio. |
| 1 | 3011 | `do전방워프` | `choiceUsingWeight($candidateCities)` | warp dest weighted by 'important'. |
| 1 | 3029 | `do내정워프` | `nextBool(0.6)` | **ALWAYS draws** — 60% skip internal-warp. |
| 2 | 3050 | `do내정워프` | `nextBool($warpProp)` | proceed gate (`$warpProp` = product of develVals); used as `!nextBool(...)`. |
| 3 | 3085 | `do내정워프` | `choiceUsingWeight($candidateCities)` | warp dest weighted by `1/(develRate*sqrt(gens+1))`. |

### 5.O `doNPC사망대비` (death-prep) — 0 or 1 draw (L3403-3434)
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 3413 | `nextBool()` (default 0.5 → **nextBit**) | **`||`**: drawn only if `che_인재탐색` `hasFullConditionMet()` (nationID==0 path) | 견문 vs 인재탐색 (50%). |

### 5.P `chooseNonLordPromotion` (L3881-3960) — NESTED, up to 5 `choice` per empty chief slot
Outer loop over empty chief levels; inner `Util::range(5)` (×5) retry, each doing EXACTLY ONE `choice` by strict pool priority (first non-empty pool wins). Pool emptiness `break`s with NO draw. Redraw-on-reject (officer_level!=1 / stat gate) consumes a draw per attempt.
| seq | line | call | pool (priority order) |
|---|---|---|---|
| ≤5/slot | 3910 | `choice($this->npcWarGenerals)` | 1st: npc war. |
| ≤5/slot | 3913 | `choice($this->npcCivilGenerals)` | 2nd: npc civil. |
| ≤5/slot | 3916 | `choice($this->userWarGenerals)` | 3rd: user war. |
| ≤5/slot | 3919 | `choice($this->userCivilGenerals)` | 4th: user civil. |
Mutates DB directly (applyDB) — side-effect order tied to draw order.

### 5.Q `choosePromotion` (L3978-4110) — per-chief-level conditional gate
| seq | line | call | gate | purpose |
|---|---|---|---|---|
| 1 | 4099 | `nextBool(0.1) ? 1 : 0` | **occupied slot only** (empty slot → `newChiefProb=1`, NO draw) | 10% chief-churn prob. |
| (none) | 4102 | `nextBool($newChiefProb)` used as `$newChiefProb<1 && !nextBool($newChiefProb)` | `$newChiefProb` is 1 or 0; both short-circuit (≥1→true NO-DRAW, ≤0→false NO-DRAW) | **NEVER actually draws.** ⚠ A naive port that always calls this adds a phantom draw. |

> **Per occupied chief slot: exactly ONE `nextBool(0.1)`. Per empty slot: ZERO draws.** (GAPS G-summary / A9 confirm: the L4102 call is structurally present but its prob is always 1 or 0 → no draw.)

---

## 6. Methods with ZERO drbg draws (verified — do NOT add draws)
`updateInstance`, `calcDiplomacyState`, `calcWarRoute`, `categorizeNationCities`, `categorizeNationGeneral`, `calcNationDevelopedRate`, `calcCityDevelRate`, `do불가침제의` (arsort only, L1813), `do금쌀구매` (deterministic, L2367-2481), `do귀환`, `do해산`, `do선양` (SQL-rand only — §9), `chooseTexRate`, `chooseGoldBillRate`, `chooseRiceBillRate`, the `do선전포고` head (L1848-1922). `doNPC증여` is commented out of the priority list and stub-returns (GAPS G15) — never wire it.

---

## 7. Sort comparators interleaved with draws (set candidate order → set the choice index/weight)
`choice`/`choiceUsingWeight*` results depend on array order, which these stable PHP-8 sorts set. **Tie-break = spaceship `<=>` ONLY; no secondary comparator → rely on stable-sort to preserve insertion order on ties.**
| line | method | sort | direction | feeds |
|---|---|---|---|---|
| 1247 | `do유저장긴급포상` | `usort(userWarGenerals, getVar(resName) <=>)` | ASC | L1302 weighted-pair (weight `count-idx`). |
| 1344 | `do유저장포상` | `usort(<=>)` | ASC | L1410. |
| 1447 | `doNPC긴급포상` | `usort` | ASC | L1503. |
| 1544/1584 | `doNPC포상` | `usort` (war / civil) | ASC | L1625. |
| 1663/1702 | `doNPC몰수` | `usort(-(<=>))` (civil / war) | DESC | L1755. |
| 1813 | `do불가침제의` | `arsort($candidateList)` | DESC | deterministic pick (no draw). |
| 2075 | `do천도` | `arsort($cityScoreList)` | DESC | L2100 `choice` on intermediate stops. |
| 4027 | `choosePromotion` | `uasort(userGenerals, penalty/leadership)` | — | deterministic chief select (draws are gates, not picks). |
| 4069 | `choosePromotion` | `uasort(generals, stat)` | — | deterministic chief select. |

The `count - idx` weighting in 포상 means the sort order DIRECTLY sets each candidate's weight in the subsequent `choiceUsingWeightPair`.

---

## 8. Candidate-ORDER parity dependencies (non-RNG, but they set the draw index)
A `choice`/`choiceUsingWeight*` draw's RESULT is `keys[idx]` / a float-walk over insertion order. So the **order** of these source collections is a parity target even though building them makes no draw:
- **BFS / distance maps** (`searchDistance`, `searchAllDistanceByNationList`, Floyd-Warshall): `[cityID=>dist]` insertion = BFS visitation order. Consumed by do거병 (dist-3 loop §5.E), do방랑군이동 (§5.J), do천도 (§5.M), calcWarRoute. **PHP BFS wins (CLAUDE.md findNextCapital law).** NOT read yet — GAPS G3, must pin source-at-dist-0 inclusion + adjacency `path` key order + tie handling.
- **DB-row order** of `SELECT * FROM city WHERE nation=%i` (no ORDER BY) and `SELECT no FROM general WHERE nation=%i AND no!=self` → `nationCities` / general buckets insertion order (LinkedHashMap, never re-key by id). GAPS G13: confirm MariaDB PK-ascending without ORDER BY.
- **`getAllNationStaticInfo()` order** → `$nations` insertion for do선전포고 `choiceUsingWeight` (§5.D-C).
- **`GameConst::$availableNationType`** (13-elem) and **`GetNationColors()`** (33-elem list) literal order → do건국 indices (§5.I).
- **`chiefGenerals` keyed by officerLevel** (overwrites on dup level), **`important` weight** += per officer (A2 §7-8) — set candidate membership for promotion/발령.

---

## 9. NON-drbg randomness (SQL `ORDER BY RAND()`) — flag for quarantine
Two MySQL-side random picks that are **NOT on the LiteHashDrbg stream** (so they do NOT consume/advance the rng cursor — the stream position is unaffected — but WHICH id is chosen is non-deterministic vs replay):
- **`do선양` L3324:** `SELECT no FROM general WHERE nation=%i AND npc!=5 ORDER BY RAND() LIMIT 1`.
- **`do국가선택` 오랑캐 L3345:** `SELECT nation FROM general WHERE officer_level=12 AND npc=9 AND nation ORDER BY RAND() LIMIT 1`.

> **Decision required (GAPS G4):** per CLAUDE.md rule 5, these cannot be invented. Recommended P5 strategy: capture the chosen id from a real PHP golden, OR substitute a deterministic order (e.g. `min(no)`) **quarantined with sibling-path byte-match proof**, logged to the phase backlog. State explicitly that the drbg cursor is NOT advanced by these.

---

## 10. The three trickiest draws to get EXACTLY right (call these out for the Kotlin port)

1. **`calcGenType` L185/L193 — the turn's FIRST draw, conditional on stat shape.** It fires (or not) based on the near-balance band `weaker >= stronger*0.8` computed from `getX(false)` (no-injury, item/adjust/floor ON). If the Kotlin stat-flavor mapping or the `>=`/`*0.8` boundary differs by epsilon, the AI draws-or-skips this `nextBool`, shifting EVERY subsequent draw in the shared per-general stream. The float arg (`weaker/stronger/2 ∈ [0.4,0.5)`) must be bit-identical. (A2 §3 HIGH-RISK; GAPS G8 flags the flavor mapping UNCERTAIN.)

2. **`nextBool` short-circuit + `&&`/`||` guard draws across the whole dispatch.** Whether a `nextBool($p)` draws at all — and a BIT vs a FLOAT — depends on `$p` (≥1/≤0 = no draw; `===0.5` = nextBit). Layered on top, `&&`/`||` guards suppress the draw entirely when the left operand decides (do일반내정/전쟁내정 rice guards L2136/2271, do출병 L2720, doNPC헌납 L2841, do긴급내정 L2236/2243, do징병 L2512, do거병 L3232, doNPC사망대비 L3413, do중립 L3441, do내정워프 L3050, choosePromotion L4102). The `do전쟁내정` double-0.3 (L2271 conditional + L2279 unconditional) and `choosePromotion` L4102 (structurally present, NEVER draws) are the worst phantom-draw traps. Compute every probability identically to PHP and replicate every short-circuit verbatim.

3. **Variable-count loops whose draw count rides on BFS / candidate order.** `do거병` L3258 draws one `nextBool()` (nextBit) **per dist-3 candidate** until break (§5.E); `do유저장구출발령`/`doNPC구출발령` draw one `choice` per qualifying lostGeneral then one final `choice` (§5.B); `chooseNonLordPromotion` draws up to 5 `choice` per empty chief slot with redraw-on-reject (§5.P). The COUNT is data-dependent and pivots on `searchDistance` BFS visit order (unread — GAPS G3) and DB-row order (GAPS G13). Pin the BFS visitation + DB ordering as parity artifacts before these can gate.

---

## 11. Gaps / unresolved (cannot be determined from GeneralAI.php alone)
- **Dispatch order itself** — `AutorunGeneralPolicy`/`AutorunNationPolicy` priority-merge + `can*` defaults + the `property_exists($this,$priorityItem)` override guard (may silently DROP all KV overrides) were NOT fully read (GAPS G1/G2). The merged `->priority` order = the do* dispatch = the draw order. **Foundation blocker.**
- **BFS/distance helper visitation order** (GAPS G3) — feeds every dist-keyed `choice`/`choiceUsingWeightPair` and the do거병 dist-3 draw count.
- **`ORDER BY RAND()`** resolution strategy (GAPS G4) — must be decided + proven.
- **Per-command constraint packs** (`hasFullConditionMet`) for every emitted `che_*` (GAPS G5) — their boolean outcome is a control-flow input; a divergent pack flips do*-null → reorders the priority loop → desyncs. P5 blocker for the commands the AI emits, not a P6 deferral.
- **`getStatValue` flavor table** (GAPS G8): calcGenType=`(false,T,T,T)`; reward math=`(false,T,T,T)`; promotion stat-gate=`(false,F,F,F)` — confirm each against the Kotlin pipeline.
- **TS divergence** (A5): core2026 drops the do선전포고 draw (B) — PHP wins; do NOT mirror the TS 2-draw shape.
