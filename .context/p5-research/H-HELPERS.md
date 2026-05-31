# H-HELPERS — every rate/reward/threshold helper body pinned with file:line (closes B7/M5/M6, GAP G9/G11)

> **PARITY LAW.** PHP `legacy/devsam-core/` is GRAND TRUTH. Every rounding mode, sentinel,
> early-return, dead-append, and gate constant below is a parity target. `Util::round` =
> half-AWAY-from-zero → `phpRound` (NEVER `Math.round`); `Util::toInt`/`intdiv`/`intval` =
> truncate-toward-zero → `phpToInt`; `ceil`/`floor` are DISTINCT from round. PHP wins every
> divergence vs `devsam-core2026` TS (the TS `aiUtils.roundTo` half-up is WRONG everywhere).
>
> **THIS NOTE IS A CITATION SOURCE — NOT CODE.** F-INSTANCE / F-FACADE / L-REWARD / L-RATES
> tasks MUST cite the `file:line` here and **never assert an unpinned threshold constant**.
> Every body below was read IN FULL from the actual PHP source (not paraphrase). All line
> numbers are `path:line` against `legacy/devsam-core/` (read 2026-05-30).

---

## 0. The rounding kernel (already GREEN — REUSE, do NOT re-port)

| PHP | semantics | Kotlin GREEN |
|---|---|---|
| `Util::round($v,$pos=0)` = `intval(round($v,$pos))` (`src/sammo/Util.php:14-17`; `assert($pos<=0)`) | **half-AWAY-from-zero** (`PHP_ROUND_HALF_UP`), returns int | `logic/util/PhpRound.kt:7` `phpRound(Double):Int` — **pos=0 ONLY** |
| `Util::setRound(&$v,$pos)` (`Util.php:24-26`) | in-place wrapper of round | (call `phpRound`) |
| `Util::toInt($v)` (`Util.php:222-250`) — `intval()` on numeric | **truncate-toward-zero** | `logic/util/PhpRound.kt:14` `phpToInt(Double):Int` |
| `intdiv($a,$b)` (PHP builtin) | truncate-toward-zero integer division | `a / b` on Kotlin Ints (or `phpToInt(a.toDouble()/b)`) |
| `ceil()` | toward +inf | `logic/util/PhpRound.kt:20` `phpCeil` / `kotlin.math.ceil` |
| `floor()` | toward −inf | `kotlin.math.floor` |
| `Util::valueFit/clamp($v,$min,$max)` (`Util.php:488-508`) | **`max<min → min`**; else lower-clamp then upper-clamp; min/max nullable; **NO rounding** | `logic/util/PhpRound.kt:23-29` `clamp`/`valueFit` |

> **⚠ M5 — `PhpRound(-2)` IS NOT YET COVERED by the GREEN kernel.** PHP `Util::round($v,-2)`
> rounds to the nearest 100 (half-away at the 10² position): `intval(round($v,-2))`. The Kotlin
> `phpRound(Double):Int` only implements `pos=0`. Call sites that need `-2`:
> `maxResourceActionAmount` (`GeneralAI.php:131`, F-INSTANCE) and `do징병` `round($crew-49,-2)`
> (`GeneralAI.php` §5.L, L-GENDOM). **F-INSTANCE / L-GENDOM MUST add a pos-aware overload**
> (e.g. `phpRound(v, pos): Int` = `BigDecimal.valueOf(v).setScale(pos, HALF_UP).toInt()` —
> `setScale` accepts a NEGATIVE scale = round at 10^|pos|) and NEVER substitute `phpRound(v/100)*100`
> (that double-rounds). This is flagged for the consumer task, not built here.

---

## 1. `Util::joinYearMonth` / `parseYearMonth` — the **-1** variant (M6/G11)

`src/sammo/Util.php:709-715` (read in full):
```php
public static function joinYearMonth(int $year, int $month):int{ return $year * 12 + $month - 1; }   // :709-711
public static function parseYearMonth(int $yearMonth):array{ return [intdiv($yearMonth, 12), $yearMonth%12 + 1]; } // :713-715
```
- **`joinYearMonth = year*12 + month - 1`** — the **`-1`** variant (NOT `+0`). An off-by-one here
  flips the `calcDiplomacyState` early-return and desyncs every do불가침제의/do선전포고/do천도 boolean.
- `parseYearMonth` is the exact inverse: `[intdiv(ym,12), ym%12 + 1]` (`intdiv` trunc; month 1-based).
- **Kotlin GREEN:** `logic/actions/nation/NationCommand.kt:82` already defines
  `joinYearMonth(year,month) = year*12 + month - 1` — **REUSE it; do NOT re-derive.**

**Consumers (all must use the byte-identical `-1` formula):**
- `GeneralAI.php:212` `$yearMonth = joinYearMonth(env.year, env.month)` (calcDiplomacyState head).
- `GeneralAI.php:219` early-gate: `if ($yearMonth <= joinYearMonth($startyear+2, 5))`
  → threshold = `(startyear+2)*12 + 5 - 1` = **`(startyear+2)*12 + 4`** (read in full `:219-228`).
- `GeneralAI.php:306` `$yearMonth = joinYearMonth(year,month)` (do부대전방발령 head) and `:323`
  `$compYearMonth = $yearMonth` (the cutTurn compare, see §2.cutTurn below).
- `GeneralAI.php:1830` `[$y,$m] = parseYearMonth($yearMonth + $diplomatMonth)` (불가침 target year/month).

---

## 2. `General::calcRecentWarTurn` + `cutTurn` (B7, feeds userWar/userCivil bucketing)

### `General::calcRecentWarTurn(int $turnTerm): int` — `hwe/sammo/General.php:273-296` (read in full)
```php
$cacheKey = "recent_war_turn_{$turnTerm}";                 // :275
if (key_exists($cacheKey, $this->calcCache)) return ...;   // :276-278  calcCache MEMO (per general per turnTerm)
if (!$this->getVar('recent_war')) {                        // :279  null/''/0 = never fought
    $result = 12 * 1000;                                   // :280  SENTINEL = 12000
    $this->calcCache[$cacheKey] = $result; return $result; // :281-282
}
$recwar  = new DateTimeImmutable($this->getVar('recent_war')); // :284
$turnNow = new DateTimeImmutable($this->getVar('turntime'));   // :285
$secDiff = TimeUtil::DateIntervalToSeconds($recwar->diff($turnNow)); // :286
if ($secDiff <= 0) { $this->calcCache[$cacheKey]=0; return 0; }      // :288-291  clamp to 0
$result = intdiv(Util::toInt($secDiff), 60 * $turnTerm);            // :293  trunc-toward-zero
$this->calcCache[$cacheKey] = $result; return $result;             // :294-295
```
- **SENTINEL = `12 * 1000` = `12000`** (`:280`) when `recent_war` is falsy (the "never fought" marker).
- `secDiff <= 0 → 0` (`:288`). `intdiv(Util::toInt(secDiff), 60*turnTerm)` (`:293`) — BOTH trunc-toward-zero.
- **calcCache-memoized** (`:276/281/289/294`) — per general per `turnTerm`; recompute only once.

**Consumers — `categorizeNationGeneral` (`GeneralAI.php:3516-3613`, cited via R-FACADE §2):**
- `:3543` `$recentWar = $nationGeneral->calcRecentWarTurn(env.turnterm)`.
- `:3544` `if ($recentWar >= ($belong - 1) * 12) continue;` — exclude pre-임관 battles from `$lastWar`.
- `:3549` `$lastWar = min($lastWar, $recentWar);` (`$lastWar` seeded `PHP_INT_MAX` at `:3541`).
- `:3585` `if ($nationGeneral->calcRecentWarTurn($turnterm) <= $lastWar + 12)` → `userWarGenerals`;
  else `dipState !== 평화 && crew >= minWarCrew` → `userWarGenerals`; else → `userCivilGenerals`
  (the exact userWar/userCivil split, `:3585-3596`).

### `cutTurn($date, int $turnterm, bool $withFraction=true)` — `hwe/func.php:946-962` (read in full)
```php
$date     = new DateTime($date);                       // :948
$baseDate = new DateTime($date->format('Y-m-d'));      // :950  midnight of $date's day
$baseDate->sub(new DateInterval("P1D"));               // :951  -1 day
$baseDate->add(new DateInterval("PT1H"));              // :952  +1 hour  => (day-1) 01:00:00
$diffMin = intdiv($date->getTimeStamp() - $baseDate->getTimeStamp(), 60); // :954  trunc div, minutes
$diffMin -= $diffMin % $turnterm;                      // :955  floor to turnterm boundary
$baseDate->add(new DateInterval("PT{$diffMin}M"));     // :957
return $baseDate->format('Y-m-d H:i:s' . ($withFraction ? '.u' : '')); // :958-961
```
- Anchor = **(day−1) at 01:00:00**; `intdiv` (trunc) for the minute diff; `% turnterm` then subtract
  → floor to a multiple of turnterm. NOT a round.
- **Consumer (`do부대전방발령`, `GeneralAI.php:305-327`, read in full):**
  - `:305` `$chiefTurn = cutTurn($this->general->getTurnTime(), env.turnterm)`.
  - `:322` `$leaderTurn = cutTurn($troopLeader->getTurnTime(), env.turnterm)` (only if `last발령` set).
  - `:323-327` `$compYearMonth = $yearMonth; if ($chiefTurn < $leaderTurn) $compYearMonth += 1;
    if ($compYearMonth === $yearMonth) continue;` — "한턴마다 한번씩만 발령" gate (string compare on the
    formatted timestamps; `<` is lexicographic on `Y-m-d H:i:s.u`).

---

## 3. Reward / bill helpers (L-REWARD, L-RATES)

### `getDedLevel($dedication)` — `hwe/func_converter.php:643-651` (read in full)
```php
$level = Util::valueFit( ceil(sqrt($dedication) / 10), 0, GameConst::$maxDedLevel );  // :644-648
return $level;
```
- **`ceil`** (DISTINCT from round) of `sqrt(ded)/10`, clamped `[0, maxDedLevel]`.
- `GameConst::$maxDedLevel = 30` (`GameConstBase.php:80`; Kotlin GREEN `GameConst.kt:48` = 30).
- **Kotlin GREEN:** `logic/domestic/DomesticHelpers.kt:67-68`
  `getDedLevel = valueFit(ceil(sqrt(dedication)/10.0), 0.0, MAX_DED_LEVEL).toInt()` — REUSE.

### `getBill` / `getBillByLevel` — `hwe/func_converter.php:664-670` (read in full)
```php
function getBill(int $dedication):int{ return getBillByLevel(getDedLevel($dedication)); }  // :664-666
function getBillByLevel(int $dedLevel):int{ return ($dedLevel * 200 + 400); }               // :668-670
```
- `getBillByLevel = dedLevel*200 + 400`. **Kotlin GREEN:** `DomesticHelpers.kt:81` (getBillByLevel),
  `:84` (getBill) — REUSE.

### `getOutcome(float $billRate, array $generalList)` — `hwe/func_time_event.php:239-249` (read in full)
```php
$outcome = 0;
foreach($generalList as $general){ $outcome += getBill($general['dedication']); }  // :242-244  sum of getBill (int)
$outcome = Util::round($outcome * $billRate / 100);                                // :246  HALF-AWAY (phpRound)
return $outcome;                                                                   // :248
```
- per-general `getBill` (int) summed, then `* billRate/100`, then **`Util::round` = half-away** = `phpRound`.
- **Consumers `chooseGoldBillRate`/`chooseRiceBillRate` (`GeneralAI.php:4229`/`:4275`):**
  `$outcome = Util::valueFit(getOutcome(100, $dedicationList), 1);` → **floor-bounded to min 1** (clamp, no upper).
  Then `intval($income/$outcome*90)` (`:4231`/`:4277`, trunc) and possible `intval((moreBill+bill)/2)`
  (`:4235`/`:4281`, trunc); final `valueFit($bill, 20, 200)` (`:4239`/`:4285`).

### ⚠ The `dedicationList` filter + the DEAD unused-append (port verbatim — latent bug)
`GeneralAI.php:4215-4222` (gold) and `:4262-4269` (rice), read in full:
```php
$nationGenerals = $this->nationGenerals;                          // :4215 / :4262  LOCAL copy
$nationGenerals[] = $this->general;                               // :4216 / :4263  ⚠ DEAD APPEND — never read again
$dedicationList = array_map(fn(General $g)=>$g->getRaw(),         // :4218 / :4265
    array_filter($this->nationGenerals,                           // :4220 / :4267  filters $this->nationGenerals (NOT the local +self copy)
        fn(General $g)=> $g->getVar('npc') != 5));                // :4221 / :4268  npc != 5
```
- **`dedicationList` = `$this->nationGenerals` filtered `npc != 5`** — it does **NOT** include `$this->general`.
  The `$nationGenerals[] = $this->general` local append (`:4216/:4263`) is **never read** → dead code.
  **Port verbatim** (the bill estimate excludes self + all npc==5 generals). Do NOT "fix" it to include self.

### Income helpers `getGoldIncome`/`getRiceIncome`/`getWallIncome` — `hwe/func_time_event.php:141-237` (read in full)
- `getGoldIncome` `:141-164`, `getRiceIncome` `:187-211`, `getWallIncome` `:213-237` — same shape:
  - officer-count map `:149`/`:195`/`:221`: `SELECT officer_city, count(*) FROM general
    WHERE nation=%i AND officer_level IN (2,3,4) AND city=officer_city GROUP BY officer_city`
    → `$officersCnt[$cityID]=$cnt` (NO `ORDER BY`, **order-INSENSITIVE** — read by key `$officersCnt[$cityID]??0`).
  - per-city `calcCityGoldIncome`/`calcCityRiceIncome`/`calcCityWallRiceIncome` summed (`:158`/`:205`/`:231`).
  - then `$cityIncome *= ($taxRate / 20);` (`:161`/`:208`/`:234`) — **FLOAT multiply, NO round at this level.**
  - `if (!$cityList) return 0;` short-circuit (`:142`/`:188`/`:214`).
- **per-city rounding** — `calcCityGoldIncome` `:88-104` / `calcCityRiceIncome` `:106-122` /
  `calcCityWallRiceIncome` `:124-139` (read in full):
  - `if ($rawCity['supply'] == 0) return 0;` (`:89`/`:107`/`:125`) — supply==0 ⇒ 0.
  - `$trustRatio = trust/200 + 0.5` (`:93`/`:111`).
  - base = `pop * comm|agri / *_max * trustRatio / 30` (`:95`/`:113`); wall = `def*wall/wall_max/3` (`:129`).
  - `*= 1 + secu/secu_max/10` (`:96`/`:114`/`:130`); `*= pow(1.05, officerCnt)` (`:97`/`:115`/`:131`).
  - capital bonus: gold/rice `*= 1 + (1/3/$nationLevel)` (`:99`/`:117`); **wall `*= 1 + 1/(3*$nationLevel)`**
    (`:133` — DIFFERENT grouping; `1/3/L` == `1/(3*L)` numerically but the source literal differs — pin both).
  - final `Util::round($nationType->onCalcNationalIncome('gold'|'rice', $cityIncome))` (`:101`/`:119`/`:136`)
    — **per-city HALF-AWAY round** to int (= `phpRound`).
- **⚠ CALLER rate DIVERGES by call site (M-flagged):**
  - **`taxRate = 15`** (literal) for the diplomacy-cost estimate at `GeneralAI.php:1808-1810` (read in full):
    `getGoldIncome(..., 15, ...)` / `getRiceIncome(..., 15, ...)` / `getWallIncome(..., 15, ...)`.
  - **`$nation['rate']`** for bill-rate at `GeneralAI.php:4225`/`:4271` (read in full):
    `getGoldIncome(..., $nation['rate'], ...)` etc.
  - `getWarGoldIncome($nation['type'], $cityList)` (`:4226`, war income add-on) has NO taxRate factor.

---

## 4. Tech / unit-cost helpers (L-GENDOM 징병/출병, facade reqMoney gates)

### `getTechLevel` / `getTechCost` / `TechLimit` — `hwe/func_converter.php` (read in full)
```php
function getTechLevel($tech):int{ return Util::valueFit(floor($tech/1000), 0, GameConst::$maxTechLevel); } // :676-682  FLOOR
function TechLimit($startYear,$year,$tech):bool{                                                            // :684-697
    $relYear = $year - $startYear;                                                                          // :686
    $relMaxTech = Util::valueFit(                                                                            // :688-692
        floor($relYear / GameConst::$techLevelIncYear) + GameConst::$initialAllowedTechLevel, 1, GameConst::$maxTechLevel); // FLOOR, clamp [1,maxTech]
    $techLevel = getTechLevel($tech);                                                                       // :694
    return $techLevel >= $relMaxTech;                                                                       // :696  true = LIMITED (blocked)
}
function getTechCost($tech):float{ return 1 + getTechLevel($tech) * 0.15; }                                 // :703-705  FLOAT, no round
```
- **`getTechLevel` uses `floor(tech/1000)`**, clamp `[0, maxTechLevel]` (NOT round).
- **`TechLimit` uses `floor(relYear / techLevelIncYear) + initialAllowedTechLevel`**, clamp `[1, maxTechLevel]`;
  returns `techLevel >= relMaxTech` (true = blocked).
- GameConst pins (`GameConstBase.php`, all match Kotlin GREEN `GameConst.kt`):
  `maxTechLevel=12` (`:82` / `GameConst.kt:49`), `techLevelIncYear=5` (`:98` / `GameConst.kt:125`),
  `initialAllowedTechLevel=1` (`:100` / `GameConst.kt:126`).
- **Kotlin GREEN:** `common/constants/GameUnitDetail.kt:117` (`techLevel`=floor, private),
  `:126` (`getTechCost = 1.0 + techLevel(tech)*0.15`) — REUSE. (`TechLimit` itself: confirm a GREEN port
  exists before L-GENDOM consumes it; if absent, the consumer task ports it citing `:684-697`.)

### `riceWithTech` / `costWithTech` — `hwe/sammo/GameUnitDetail.php:120-128` (read in full)
```php
public function riceWithTech(int $tech, int $crew=100): float { return $this->rice * getTechCost($tech) * $crew / 100; } // :120-123
public function costWithTech(int $tech, int $crew=100): float { return $this->cost * getTechCost($tech) * $crew / 100; } // :125-128
```
- `unit.rice|cost * getTechCost(tech) * crew / 100` — **FLOAT, no rounding** (rounding, if any, at call site).
- **Kotlin GREEN:** `logic/actions/military/UnitSetTable.kt:46` (costWithTech), `:49` (riceWithTech) — REUSE.

### Facade reqMoney threshold gates (the +3yr vs +5yr split) — `GeneralAI.php` (read in full)
| line | expression | meaning |
|---|---|---|
| `:1260` | `$crewtype->costWithTech(nation.tech, toInt(targetUserGeneral.getLeadership(false))) * 100 * 3 * 1.1` | **user-war** general reqMoney (**×3×1.1**) |
| `:1261` | `if (env.year > env.startyear + 3) reqMoney = max(reqMoney, reqHumanMinRes)` | **+3yr** floor (user) |
| `:1264` | `$enoughMoney = $reqMoney * 1.1` | first-tier pay target = reqMoney **×1.1** |
| `:1364` | `costWithTech(...) * 100 * 6 * 1.1` | **second** user-pay tier (**×6×1.1**) |
| `:1365` | `if (env.year > env.startyear + 3)` | **+3yr** gate (same) |
| `:1368` | `$enoughMoney = $reqMoney * 1.2` | second-tier pay target = **×1.2** (NOT 1.1 — R-FACADE row said 1.1; SOURCE says 1.2) |
| `:1431` `:1436` | `reqNPCWarGold / 2`, `reqNPCWarRice / 2` | **NPC half** thresholds (`/2`) |
| `:1460` | `costWithTech(...) * 100 * 1.5` | NPC-war reqMoney (**×1.5**) |
| `:1461` | `if (env.year > env.startyear + 5)` | **+5yr** gate (NPC — note **5**, not 3) |
| `:1464` | `$enoughMoney = $reqMoney * 1.2` | NPC-war pay target = **×1.2** |
| `:1557` | `costWithTech(nation.tech, ...) * 100 * 3 * 1.1` | NPC **second** tier (**×3×1.1**) |
| `:1558` | `if (env.year > env.startyear + 5)` | **+5yr** gate (NPC) |
| `:1561` | `$enoughMoney = $reqMoney * 1.5` | NPC-second pay target = **×1.5** |
| `:3363` | `if (env.year < env.startyear + 3)` | 임관(joining) early-period gate (**`<`**, +3yr); below it `nextBool(pow(1/(nationCnt+1)/pow(notFullNationCnt,3), 1/4))` (`:3371`) |

> **PIN (the gate-year split):** user-side reqMoney gates use **`startyear + 3`** (`>`); NPC-side
> recruit reqMoney gates use **`startyear + 5`** (`>`); the joining(임관) gate uses **`startyear + 3`**
> (`<`). `* 100` is the crew scale (leadership×100 troops). `Util::toInt(leadership)` = trunc before
> passing as `$crew`. `leadership` is read via `getLeadership(false)` (no-injury flavor — see G8).
> **CORRECTION vs R-FACADE §4 table:** R-FACADE's `:1264` row labels enoughMoney "×1.1"; the SOURCE
> shows three distinct pay-target multipliers — first user tier `:1264`=**×1.1**, second user tier
> `:1368`=**×1.2**, NPC-war `:1464`=**×1.2**, NPC-second `:1561`=**×1.5**. Use the source values.

---

## 5. Cross-cutting rounding cheat-sheet (consumer quick-reference)

| operation | mode | Kotlin |
|---|---|---|
| per-city income, `getOutcome`, `maxResourceActionAmount`, `round($crew-49,-2)` | **half-AWAY** | `phpRound` (+ pos-aware overload for `-2`, M5) |
| `calcRecentWarTurn` intdiv, `cutTurn` intdiv, `parseYearMonth` intdiv, `Util::toInt(leadership)`, bill `intval(...)` | **trunc-toward-zero** | `phpToInt` / Int `/` |
| `getDedLevel` `ceil(sqrt/10)` | **ceil** (toward +inf) | `phpCeil` / `ceil` |
| `getTechLevel` `floor(tech/1000)`, `TechLimit` `floor(relYear/incYear)` | **floor** (toward −inf) | `floor` |
| `getTechCost`, `riceWithTech`, `costWithTech`, income `* taxRate/20` | **FLOAT, NO round** | `Double` |
| `Util::valueFit/clamp` | pure clamp, `max<min→min`, NO round | `clamp`/`valueFit` |

---

## Gate constants summary (every constant a consumer may assert — all pinned above)

- `joinYearMonth = y*12 + m - 1` (`Util.php:709`); early-gate `(startyear+2)*12 + 4` (`GeneralAI.php:219`).
- `calcRecentWarTurn` falsy-sentinel **12000** (`General.php:280`); `secDiff<=0→0` (`:288`); calcCache memo.
- `categorizeNationGeneral` bucket constants: `(belong-1)*12` exclude (`:3544`), `$lastWar+12` userWar split (`:3585`).
- `getDedLevel = ceil(sqrt(ded)/10)` clamp [0,30] (`func_converter.php:643`, maxDedLevel=30).
- `getBillByLevel = dedLevel*200 + 400` (`func_converter.php:668`).
- `getOutcome = phpRound(sum(getBill)*billRate/100)` (`func_time_event.php:246`); consumer `valueFit(...,1)` min-1.
- `dedicationList` = nationGenerals filtered `npc != 5`, **excludes self** (dead append `:4216/:4263`).
- income `*= taxRate/20`; per-city `phpRound(onCalcNationalIncome)`; supply==0→0; capital `*=1+1/3/L` (wall `1+1/(3*L)`); officer `pow(1.05,n)`. Caller rate: **15** for diplo-est (`:1808`), **`nation['rate']`** for bill (`:4225/4271`).
- `getTechLevel = floor(tech/1000)` clamp [0,12]; `TechLimit relMaxTech = floor(relYear/5)+1` clamp [1,12] (`func_converter.php:676/684`).
- `getTechCost = 1 + techLevel*0.15`; `riceWithTech/costWithTech = unit.rice|cost * techCost * crew/100` (FLOAT).
- reqMoney gates: user **×3×1.1 +3yr** (`:1260/1261`), user-2 **×6×1.1 +3yr** (`:1364/1365`), NPC half `/2` (`:1431/1436`), NPC-war **×1.5 +5yr** (`:1460/1461`), NPC-2 **×3×1.1 +5yr** (`:1557/1558`); 임관 gate `< startyear+3` (`:3363`).
- pay targets: user-1 ×1.1 (`:1264`), user-2 ×1.2 (`:1368`), NPC-war ×1.2 (`:1464`), NPC-2 ×1.5 (`:1561`).

> **No leaf may assert any constant not on this list with a `file:line`.** Every value above was
> read from the actual PHP body (not R-FACADE paraphrase); where R-FACADE diverged (the `:1368`
> ×1.2 vs ×1.1 enoughMoney), this note supersedes it with the source value.
