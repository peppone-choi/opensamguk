# Join (장수생성 / create-general) — PHP grand-truth spec

**Oracle:** `legacy/devsam-core` (PHP). Byte-for-byte target. PHP wins every divergence.
**Date:** 2026-06-06. Read-only audit. All line numbers are from `legacy/devsam-core`.

## Source files

| Role | Path | Lines cited |
| ---- | ---- | ----------- |
| API entrypoint (MakeGeneral inline — NO separate class) | `hwe/sammo/API/General/Join.php` | 39–546 |
| Join page server side | `hwe/v_join.php` | 9–88 |
| Form (Vue) | `hwe/ts/PageJoin.vue` | 1–608 |
| RNG facade | `src/sammo/RandUtil.php` | 22–131 |
| RNG core (byte-exact SHA-512 DRBG) | `src/sammo/LiteHashDRBG.php` | 178–215 |
| Speciality picker (천재 전투특기) | `hwe/sammo/SpecialityHelper.php` | 125–150, 259–328 |
| `simpleSerialize` (seed string) | `src/sammo/Util.php` | 872–891 |
| `Util::round` (half-away → intval(round)) | `src/sammo/Util.php` | 14–17 |
| `Util::range` / `valueFit`/`clamp` | `src/sammo/Util.php` | 815–838, 488–508 |
| `getRandTurn` / `cutTurn` / `addTurn` | `hwe/func.php` | 2199–2215, 946–963, 924–937 |
| `TimeUtil::now` (returns **string**, not int) | `src/sammo/TimeUtil.php` | 88–92 |
| RankColumn enum (rank_data rows) | `hwe/sammo/Enums/RankColumn.php` | 5–91 |
| GeneralAccessLogColumn enum | `hwe/sammo/Enums/GeneralAccessLogColumn.php` | 5–14 |
| Constants | `hwe/d_setting/GameConst.php` + `hwe/sammo/GameConstBase.php` + `hwe/sammo/GameUnitConstBase.php` | see §B |

**There is no `MakeGeneral` class.** The whole create-general routine is inline in `Join::launch()` (Join.php:126–545). The seed label `'MakeGeneral'` is just the second token of the seed string (Join.php:228).

---

## A. RNG draw sequence (draw-for-draw, IN ORDER)

### Seed construction (Join.php:225–231)

```php
$now = TimeUtil::now(false);          // line 225 — STRING "Y-m-d H:i:s" (no fraction)
$rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
    UniqueConst::$hiddenSeed,         // string
    'MakeGeneral',                    // string literal
    $userID,                          // int
    $now                              // STRING (TimeUtil::now(false)) — NOT an int!
)));
```

`Util::simpleSerialize` (Util.php:872–891) encodes by type:
- string → `str({mb_strlen},{value})`
- int    → `int({value})`
- float  → `float({number_format(v,6)})`
joined by `|`. So the seed string is:
```
str({len},{hiddenSeed})|str(11,MakeGeneral)|int({userID})|str(19,{YYYY-MM-DD HH:MM:SS})
```
**§5.2 correction #1:** the 4th seed token is a **string** (`TimeUtil::now(false)` → `Y-m-d H:i:s`, width 19), NOT `int(now)`. The Kotlin seed builder must serialize `now` as a string of that exact format/length. (`$now` is re-read at Join.php:373 with `now(true)` for the turntime guard — that second read is AFTER all draws and does not affect the seed.)

`UniqueConst::$hiddenSeed` is a fixed per-game secret captured from the install. Treat it as an opaque string input to the seed.

### Draw stream (only the branches reached in scenario_1010 with NO inherit options)

The draw order below assumes the **plain** path: no `inheritSpecial`, no `inheritCity`, no `inheritBonusStat`, no `inheritTurntimeZone` (all four are `null`). Inherit branches SKIP the corresponding draw (noted inline).

| # | PHP line | Method | Args | Decides | Notes |
|---|----------|--------|------|---------|-------|
| 1 | 264 | `nextBool(0.01)` | prob = **0.01** (float) | `$genius` — is this a 천재(genius)? | **SKIPPED** if `$inheritSpecial` set (then genius forced true, no draw). `nextBool(0.01)` is `nextFloat1() < 0.01` (RandUtil.php:51) → consumes **one nextFloat1**. |
| 2 | 283 | `choice($cities)` | `$cities` = `SELECT city FROM city WHERE level>=5 AND level<=6 AND nation=0` (Join.php:279); if empty, fallback `… WHERE level>=5 AND level<=6` (281) | birth `$city` (공백지, vacant) | **SKIPPED** if `$inheritCity` set. `choice` = `nextInt(count(keys)-1)` (RandUtil.php:100) → consumes **one nextInt**. Result = `$cities[keys[idx]]` (a city id). |
| 3 | 293 | `nextRangeInt(3, 5)` | min=3, max=5 (inclusive both ends → 3,4, or 5) | bonus-stat loop count `$N` | **SKIPPED** if `$inheritBonusStat` set. `nextRangeInt(a,b)` = `nextInt(b-a)+a` (RandUtil.php:22–29). `nextInt(2)` → one int draw. |
| 4..3+N | 294 | `choiceUsingWeight([$leadership,$strength,$intel])` ×N | weights = the **form-input** lead/str/int (pre-bonus) | which stat (+1) each iteration: 0=lead,1=str,2=int | **SKIPPED entirely** if `$inheritBonusStat` set. Each call = exactly **one nextFloat1** (`$rd = nextFloat1()*$sum`, RandUtil.php:117). Loop runs `$N` times (Util::range(N) yields 0..N-1, Util.php:815). So **N draws** here. |
| 3+N+1 | 314 | `nextRangeInt(0, 1)` | min=0, max=1 (→ 0 or 1) | age jitter: `age = 20 + (bonusSum)*2 - draw` | **ALWAYS** (even with inheritBonusStat — bonusSum is then the inherit values). `nextInt(1)` → one int draw. |
| (only if genius) | 321 | `SpecialityHelper::pickSpecialWar($rng, stats)` | general stats with `dex1..dex5 = 0` | 천재 birth 전투특기 `$special2` | Only when `$genius` true AND `$inheritSpecial` null. **Consumes a variable sub-stream** — see §A.1. |
| 3+N+2 | 392 | `nextRangeInt(1, 150)` | min=1, max=150 | `$affinity` (상성) | **ALWAYS.** `nextInt(149)+1`. |
| — | 389 | `choice(GameConst::$availablePersonality)` | the 10-personality list | random `$character` | **CONDITIONAL** — only if submitted `character` is NOT in `availablePersonality` (Join.php:388). `'Random'` is allowed by validation (Join.php:73) → falls through → ONE choice draw. If a concrete personality submitted → **no draw**. **Order:** this draw is at line 389, which is BEFORE affinity (line 392). So the true order is: …, age(314), [genius spec(321)], **personality(389, conditional)**, affinity(392). |
| (only if inheritTurntimeZone) | 359, 363 | `nextRangeInt(0, turnterm-1)` + `nextRangeInt(0,999999)` | — | inherit turntime offset | **SKIPPED** in plain path (the else branch at 369 runs getRandTurn instead). |
| last (plain) | 369 → func.php:2210–2211 | `getRandTurn`: `nextRangeInt(0, 60*turnterm - 1)` then `nextRangeInt(0, 999999)` | — | `$turntime` (random within turn) → **TWO int draws** | **ALWAYS in plain path.** First = random second within turn window; second = micro-fraction (÷1e6). |

**Plain-path draw count** (no inherit, `character='Random'`, not genius):
`nextBool` (1) + `choice` city (1) + `nextRangeInt(3,5)` (1) + `choiceUsingWeight` ×N (N) + `nextRangeInt(0,1)` (1) + `choice` personality (1) + `nextRangeInt(1,150)` (1) + getRandTurn `nextRangeInt`×2 (2) = **8 + N draws** (N ∈ {3,4,5}).
If genius: insert the pickSpecialWar sub-stream between age and personality.

### §5.2 cross-check (line 102 of SESSION_HANDOFF.md)

> 천재 `nextBool(0.01)` → city=`rng->choice(... level∈[5,6] AND nation=0)` → bonus `choiceUsingWeight([lead,str,int])`×`nextRangeInt(3,5)` → age=`20+bonus*2-nextRangeInt(0,1)` → affinity `nextRangeInt(1,150)` → turntime.

**Confirmed steps:** genius nextBool(0.01) ✅, city choice (level 5–6, nation=0, with fallback) ✅, bonus choiceUsingWeight×nextRangeInt(3,5) ✅, age=20+bonus*2−nextRangeInt(0,1) ✅, affinity nextRangeInt(1,150) ✅, turntime ✅.

**Corrections / additions to §5.2:**
- **#1 (seed):** the `now` token is a **string** `TimeUtil::now(false)` (`Y-m-d H:i:s`, mb width 19), serialized as `str(19,…)` — not `int(now)`. (See seed section above.)
- **#2 (draw ORDER of the count vs the picks):** `nextRangeInt(3,5)` is drawn **FIRST** (it is the `Util::range(...)` loop bound, evaluated once at Join.php:293), THEN the N `choiceUsingWeight` draws follow. §5.2's "`choiceUsingWeight([...])×nextRangeInt(3,5)`" notation is ambiguous about which comes first — the **count draw precedes** the per-iteration stat draws.
- **#3 (genius spec sub-stream):** §5.2 omits that when `$genius` is true, `SpecialityHelper::pickSpecialWar` runs a **variable-length RNG sub-stream** (Join.php:321) between age and affinity. See §A.1 — this MUST be captured for any genius fixture.
- **#4 (personality draw):** §5.2 omits the conditional `choice(availablePersonality)` at Join.php:389 (drawn when `character='Random'`, which is the FORM DEFAULT — PageJoin.vue:363). In the default path this draw **is taken**, and it sits between age and affinity. Do not drop it.
- **#5 (turntime = TWO draws):** "turntime" in the plain path is `getRandTurn` = **two** `nextRangeInt` draws (func.php:2210–2211: `nextRangeInt(0, 60*term-1)` then `nextRangeInt(0,999999)`), not one opaque step.

### A.1 Genius 전투특기 sub-stream (`SpecialityHelper::pickSpecialWar`, only if genius)

Called as `pickSpecialWar($rng, ['leadership'=>L,'strength'=>S,'intel'=>I,'dex1'..'dex5'=>0])` (Join.php:321–331). Draw sub-sequence (SpecialityHelper.php):
1. `calcCondDexterity` (line 266 → 125–150):
   - `nextBool(0.8)` (line 137) — if true, returns 0 (no dex bonus) — **one nextFloat1**.
   - If false: `nextRangeInt(0, 99)` (line 141) vs `$dexBase` — **one int draw**. Here `$dexSum = 0` so `$dexBase = round(sqrt(0)/4) = 0`; the `< $dexBase` test is `n < 0` → always false, so it does NOT early-return.
   - Then `$dexSum` is 0 → `$rng->choice($dex)` (line 147) over the 5-key dex map — **one nextInt** (returns one of the five 0-values → contributes 0 to cond).
2. `choiceUsingWeight(...)` over the filtered war-special weight pool (line 302/309/316) — **one nextFloat1**, selects the special.

So genius adds: `nextBool(0.8)` (1) + [if false: `nextRangeInt(0,99)` (1) + `choice(dex)` (1)] + `choiceUsingWeight` (1). The `pickSpecialWar` weight tables (`BaseSpecial::$type/$selectWeight/$selectWeightType`) are part of the oracle — capture them via the recorder rather than re-deriving.

### A.2 RNG primitive semantics (must match byte-exact)

- `nextRangeInt(min,max)` = `nextInt(max-min) + min`. **Both ends inclusive.** `nextInt(k)` returns 0..k inclusive (LiteHashDRBG.php:178–202, rejection-sampled to ≤ max).
- `nextBool(p)`: `p>=1`→true (no draw); `p===0.5`→`nextBit()` (1-bit draw); `p<=0`→false (no draw); else `nextFloat1() < p` (one float draw). For `0.01` and `0.8`: float path.
- `choice(items)` = `nextInt(count(keys)-1)`; returns `items[keys[idx]]`. For a list it's index-based.
- `choiceUsingWeight(items)` = exactly **one** `nextFloat1()` (`$rd = nextFloat1()*$sum`); the accumulate-subtract loop consumes NO further draws.
- `getRandTurn(rng, term, base)` (func.php:2199): `nextRangeInt(0, 60*term-1)` then `nextRangeInt(0,999999)`; offset = (sec + frac/1e6) added to `base`.

---

## B. `general` INSERT field list (Join.php:404–438)

Source legend: **C** = constant, **F** = form input, **R** = RNG-derived, **D** = DB/member/derived.

| Column | Value | Src |
| ------ | ----- | --- |
| `owner` | `$userID` (`$session->userID`) | D (session) |
| `name` | sanitized form name (htmlspecialchars→removeSpecialCharacter→htmlPurify→textStrip, Join.php:131–134); if `blockCustomGeneralName` → `bin2hex(random_bytes(5))` then later overwritten by `Auction::genObfuscatedName($generalID)` (446–452) | F / C-block |
| `owner_name` | `$member['name']` (member table) | D |
| `picture` | `$member['picture']` if `show_img_level>=1 && grade>=1 && picture!='' && pic` else `"default.jpg"` (379–385) | D / C |
| `imgsvr` | `$member['imgsvr']` else `0` (same cond) | D / C |
| `nation` | **`0`** (재야 / vacant — no nation) | **C** |
| `city` | `$city` (RNG choice of vacant lv5–6 city, or inheritCity) | **R** (or F-inherit) |
| `troop` | `0` | C |
| `affinity` | `$rng->nextRangeInt(1,150)` | **R** |
| `leadership` | form `leadership` + `$pleadership` (bonus) | F + R |
| `strength` | form `strength` + `$pstrength` | F + R |
| `intel` | form `intel` + `$pintel` | F + R |
| `experience` | `0` if `relYear<3`; else `0.8 × experience(general at 20th-percentile of nation!=0,npc<4 ordered by experience ASC)` (345–355) | D (computed; **NOT RNG** — DB-order driven) |
| `dedication` | `0` | C |
| `gold` | `GameConst::$defaultGold` = **1000** | C |
| `rice` | `GameConst::$defaultRice` = **1000** | C |
| `crew` | `0` | C |
| `train` | `0` | C |
| `atmos` | `0` | C |
| `officer_level` | **`0`** | **C** |
| `turntime` | `$turntime` (getRandTurn or inherit; +addTurn if already past `now`, 374–376) | **R** + D |
| `killturn` | **`6`** | **C** |
| `crewtype` | `GameUnitConst::DEFAULT_CREWTYPE` = **1100** (GameUnitConstBase.php:25) | C |
| `makelimit` | `0` | C |
| `betray` | `2` if `relYear>=4` else `0` (394–397) | D |
| `age` | `20 + (pleadership+pstrength+pintel)*2 - nextRangeInt(0,1)` (314) | **R** |
| `startage` | same as `age` | R |
| `personal` | `$character` (form; or `choice(availablePersonality)` if 'Random'/invalid) | F / R |
| `specage` | scenario>=1000 → `age+3`; else `valueFit(round((retirementYear−age)/12 − relYear/2),3)+age` (337,340–343). `retirementYear`=80 | D (scenario-gated) |
| `special` | `GameConst::$defaultSpecialDomestic` = **'None'** | C |
| `specage2` | scenario>=1000 → `age+3`; genius → `= age`; else `valueFit(round((retirementYear−age)/6 − relYear/2),3)+age` (333,341) | D / R-branch |
| `special2` | genius → picked war special (or inheritSpecial); else `GameConst::$defaultSpecialWar`='None' (318–335) | R / C |
| `penalty` | `Json::encode($penalty)` — member penalties still active (155–163) | D |

> **scenario_1010 note:** `scenario >= 1000` is TRUE for 1010, so `specage = specage2 = age + 3` (Join.php:340–343). `relYear = year - startyear` (`valueFit(...,0)`, so ≥0).

`$generalID = $db->insertId()` (439) — the auto-increment `no` used by all subsequent inserts.

---

## C. Side-effect WRITE / LOG order (parity target)

The order is **load-bearing** (log gate). Exactly (Join.php):

1. **`general` INSERT** (404–438) → `$generalID = insertId()` (439).
2. **`general_access_log` INSERT** (440–444): `{general_id: $generalID, user_id: $userID, last_refresh: $now}` where `$now = TimeUtil::now(true)` (set at 373, WITH fraction). Columns from `GeneralAccessLogColumn` enum (`general_id`/`user_id`/`last_refresh`).
3. *(if `blockCustomGeneralName`)* obfuscated-name `general` UPDATE (446–452) — skipped in normal play.
4. **30× `general_turn` INSERT** (454–464): `Util::range(GameConst::$maxTurn)` = **maxTurn = 30** rows, `turn_idx` 0..29, each `{general_id, turn_idx, action:'휴식', arg:null, brief:'휴식'}`. Single batch `$db->insert('general_turn', $turnRows)`.
5. **`rank_data` INSERT** (466–475): one row per `RankColumn` case (RankColumn.php enumerates **all** cases — firenum, warnum, …, inherit_point_spent_dynamic). Each `{general_id, nation_id:0, type:<enum value>, value:0}`. Insertion order = enum declaration order (RankColumn.php:8–90).
6. *(if `inheritRequiredPoint>0`)* userLogger push + inherit KV update + `rank_data` UPDATE of `inherit_point_spent_dynamic` (479–488) — only when inherit options used.
7. *(if `restInheritPoint>0`)* new/returning-player bonus point grant (491–498) — `calcRestInheritPoint(userID) * 500` (depends on prior finished games; **0 in a fresh scenario_1010 capture**).
8. **`ActionLogger`** global/general logs (502–528), then `$logger->flush()`:
   - Genius path (506–510): global action `<G><b>{city}</b></>에서 <Y>{name}</>{josaRa}는 기재가 천하에 이름을 알립니다.` + `<C>{special2name}</> 특기를 가진 <C>천재</>의 등장으로 온 천하가 떠들썩합니다.` + global history `<L><b>【천재】</b></><G><b>{city}</b></>에 천재가 등장했습니다.`
   - Non-genius (512): `<G><b>{city}</b></>에서 <Y>{name}</>{josaRa}는 호걸이 천하에 이름을 알립니다.`
   - Always (515–521): general history `<Y>{name}</>, <G>{city}</>에서 큰 뜻을 품다.` + 5 PLAIN welcome lines (env greeting / 도움말 / 게시판 / 즐거운 삼모전 / `통솔 <C>$pleadership</> 무력 <C>$pstrength</> 지력 <C>$pintel</> 의 보너스를 받으셨습니다.`) + `연령은 <C>$age</>세로 시작합니다.`
   - Genius extra (523–525): `축하합니다! 천재로 태어나 처음부터 <C>{special2name}</> 특기를 가지게 됩니다!` + general history `<C>{special2name}</> 특기를 가진 천재로 탄생.`
   - `$josaRa = JosaUtil::pick($name, '라')` (504) — 조사 parity.
9. `pushAdminLog([...])` (530) and **RootDB `member_log` INSERT** (532–542): `{member_no, date: now, action_type:'make_general', action: json{server,type:'general',generalID,generalName}}`.

`return null` (544) = success (the API contract; non-null string = error message).

---

## D. Form contract (PageJoin.vue + Join validation)

### Request payload (`JoinArgs`, validated by `Join::validateArgs`, Join.php:41–85)

| Field | Required | Type / constraint | Default (PageJoin.vue) |
| ----- | -------- | ----------------- | ---------------------- |
| `name` | yes | string, `stringWidthBetween 1..18` (mb_strwidth) | `member.name` (358) |
| `leadership` | yes | int, `[defaultStatMin=15, defaultStatMax=80]` | `total − 2*floor(total/3)` (359) |
| `strength` | yes | int, `[15,80]` | `floor(total/3)` (360) |
| `intel` | yes | int, `[15,80]` | `floor(total/3)` (361) |
| `pic` | yes | boolean (use 전콘/picture) | `true` (362) |
| `character` | yes | in `availablePersonality ∪ {'Random'}` (Join.php:73) | `'Random'` (363) |
| `inheritSpecial` | no | in `availableSpecialWar` (천재 전투특기) | `undefined` |
| `inheritTurntimeZone` | no | int `[0,59]` | `undefined` |
| `inheritCity` | no | in `array_keys(CityConst::all())` | `undefined` |
| `inheritBonusStat` | no | `integerArray`, exactly 3 entries, each ≥0, sum ∈ {0 (→null), or 3..5} (Join.php:200–215) | `[0,0,0]` (→treated null when sum 0) |

### Server-side cross-field rules (Join.php:181–256)
- One general per user: `general WHERE owner=userID` must be empty (181) → "이미 등록하셨습니다!".
- Unique name: `general WHERE name=name` empty (184).
- `gencount(npc<2) < maxgeneral` (187).
- `leadership+strength+intel ≤ defaultStatTotal=165` (196).
- inherit options require enough `inheritTotalPoint ≥ inheritRequiredPoint` (sum of: city 1000, stat 1000, special 6000, turntime 2500) (233–248).
- `inheritSpecial` requires `game_env.genius > 0` (250).
- `block_general_create & 1` → 직접 생성 불가 (171); `& 2` → 무작위 이름 강제 (175, 399).

### Client validation (PageJoin.vue)
- `submitForm` (403–429): if `total < defaultStatTotal`, confirm() "능력치가 적습니다" before sending; on success alert + `location.href='./'`.
- Stat preset buttons (376–391): 랜덤형/통솔무력형/통솔지력형/무력지력형 set lead/str/int.
- The form caps each stat at `[min,max]` and shows total / `bonusMin(3)..bonusMax(5)`.

### Constants used (final values)
`defaultStatTotal=165`, `defaultStatMin=15`, `defaultStatMax=80`, `chiefStatMin=65`, `defaultGold=1000`, `defaultRice=1000`, `maxTurn=30`, `retirementYear=80`, `bornMinStatBonus=3`, `bornMaxStatBonus=5`, `defaultSpecialWar='None'`, `defaultSpecialDomestic='None'`, `DEFAULT_CREWTYPE=1100`, inherit costs city=1000/stat=1000/special=6000/turntime=2500.
`availablePersonality` (10): `che_안전, che_유지, che_재간, che_출세, che_할거, che_정복, che_패권, che_의협, che_대의, che_왕좌`.
`availableSpecialWar` (20): `che_귀병, che_신산, che_환술, che_집중, che_신중, che_반계, che_보병, che_궁병, che_기병, che_공성, che_돌격, che_무쌍, che_견고, che_위압, che_저격, che_필살, che_징병, che_의술, che_격노, che_척사`.

---

## E. Golden-capture readiness

### Precondition in scenario_1010
- Join needs a **logged-in user (member.no) with NO existing general** (`general WHERE owner=userID` empty, Join.php:178/181). scenario_1010 ships 174–678 NPC generals all with `owner=0` (NPCs), so any positive `userID` is free of a player general → Join reachable for a synthetic userID.
- Auth path: `Join::getRequiredSessionMode()` = `REQ_LOGIN | REQ_READ_ONLY` (Join.php:87–90). `$userID = $session->userID`. The capture does NOT need a real HTTP session — it can call the inline routine with a fabricated `userID` once a `member` row exists (Join.php:149 reads `RootDB.member WHERE no=userID` for `name/picture/grade/imgsvr/penalty`).
- `game_env` must have `block_general_create` clear (bit 1), `maxgeneral` > current gencount, `genius` ≥ 0. scenario_1010 install satisfies these (genius pool present).
- `experience` path: `relYear<3` → experience=0 (avoids the 20th-percentile DB query). In scenario_1010, `year=180,startyear=180` → relYear=0 → experience=0 (deterministic). Good: no need to seed the experience-rank query.

### Harness fit (tools/php-golden/)
The established pattern (`capture_vote.php`, `capture_che.php`, `capture_command_args.php`):
- `require __DIR__.'/_boot.php'` (binds DB via `DB::db()`, loads lib/func) + `require '/RandUtilDrawRecorder.php'`.
- Pull env from `game_env` KV (year/month/startyear/scenario/turnterm/genius/turntime).
- Build `RandUtilDrawRecorder(new LiteHashDRBG(seedString))` with the **exact** seed string and replay the REAL code path under the recorder; record `getDrawStream()`.
- HARD-assert reproducibility (build twice → identical stream) and faithful structure; write JSON via `Json::encode(..., JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_PRETTY_PRINT)`.

The recorder already overrides every primitive Join uses: `nextBool`, `nextRangeInt`, `nextInt`, `choice`, `choiceUsingWeight`, `nextFloat1`. **No new override needed** — Join uses no `choiceUsingWeightPair`/`shuffle`.

### What a future `capture_join.php` MUST do (DO NOT write it now — spec only)
1. **Boot** + recorder require (as above). Read `hiddenSeed = UniqueConst::$hiddenSeed` and `game_env` env.
2. **Fabricate a member**: ensure a `RootDB.member` row with a chosen `userID` (no) exists with deterministic `name/picture(''/default)/grade(0)/imgsvr(0)/penalty('{}')`, and **no** `general WHERE owner=userID`. Snapshot for restore.
3. **Build the EXACT seed string** Join.php:226–231 builds:
   `Util::simpleSerialize($hiddenSeed, 'MakeGeneral', $userID, $now)` where `$now = TimeUtil::now(false)` — but for a REPRODUCIBLE golden, **pin `$now` to a fixed string** (e.g. `'2026-06-06 00:00:00'`) and feed that same pinned string into the seed AND record it in the fixture (the live code uses wall-clock now; the golden pins it so Kotlin can replay).
4. **Drive the draw stream WITHOUT side effects**: rather than running the full `Join::launch` (which INSERTs rows + writes member_log), reproduce the **draw sub-sequence** of MakeGeneral under the recorder in the exact Join order: `nextBool(0.01)` → `choice($cities)` (query the SAME `SELECT city WHERE level∈[5,6] AND nation=0`, with the fallback) → `nextRangeInt(3,5)` → `choiceUsingWeight([L,S,I])`×N → `nextRangeInt(0,1)` → [if genius: `SpecialityHelper::pickSpecialWar($rec, stats)`] → [if character invalid/'Random': `choice(availablePersonality)`] → `nextRangeInt(1,150)` → `getRandTurn($rec, turnterm, new DateTimeImmutable(turntime))`. Capture stream + derived outputs (city, bonus[L,S,I], age, special2, personal, affinity, turntime). Build twice → assert identical (reproducibility).
5. **Capture multiple fixtures** by varying `userID` and/or the pinned `$now` string (each unique seed → distinct stream). Include at least one **genius** case if the natural draws yield one (do NOT force genius by editing the golden — if 0.01 never fires across the chosen seeds, either widen the seed set or, faithful-never-fabricate, quarantine the genius branch with a note). The genius sub-stream (pickSpecialWar) must be captured via the recorder, not re-derived.
6. **Assert + restore**: HARD-assert each draw's method/args match the Join order; restore the fabricated member + any state; write JSON (oracle string, hiddenSeed, env, per-fixture seedString + draws + outcome).

**Quirks to respect:** the seed's `now` is a **string** (`str(19,…)`) not an int (§A #1). The genius spec is a variable sub-stream (§A.1). The personality draw is conditional on `'Random'`/invalid (§A #4, and `'Random'` is the form default → normally taken). turntime is **two** draws (§A #5). `experience` is DB-order driven, not RNG (keep relYear<3 so it's 0).

---

## Quick reference — plain-path Kotlin replay order (no inherit, character='Random', not genius)

```
seed = simpleSerialize(hiddenSeed, "MakeGeneral", userID:int, now:str("Y-m-d H:i:s"))
1. nextBool(0.01)                 → genius? (false in non-genius case)
2. choice(vacantCities lv5-6 nation=0)  → city
3. nextRangeInt(3,5)              → N
4. choiceUsingWeight([L,S,I]) × N → +1 to lead/str/int each   (weights = FORM stats, pre-bonus, constant ×N)
5. nextRangeInt(0,1)             → age = 20 + bonusSum*2 - draw
6. [genius only] pickSpecialWar(POST-bonus L/S/I, dex=0) → special2   (§A.1; Join.php:321)
7. nextRangeInt(0, 60*turnterm-1) → getRandTurn second        (Join.php:369)
8. nextRangeInt(0, 999999)        → getRandTurn fraction
9. choice(availablePersonality)  → personal   (Join.php:388, only because character='Random')
10. nextRangeInt(1,150)          → affinity   (Join.php:392)
```
**ORDER (corrected 2026-06-06):** `getRandTurn` (Join.php:369) executes **BEFORE** personality (388) and
affinity (392) — line-execution order is 314(age)→321(genius spec)→369(getRandTurn)→388(personality)→392(affinity).
An earlier draft of this doc (and the first golden capture) mis-placed getRandTurn LAST; corrected after a
`grep -n` on Join.php and a re-captured golden (`golden/entrance/장수생성-fixtures.json`, sha256 535ddb9c…).
`pickSpecialWar` receives **post-bonus** L/S/I (`$leadership` is reassigned at Join.php:307, before line 321).
The §A table below (rows ordered by line number) is authoritative; the Kotlin port `logic/world/MakeGeneral.kt`
+ gate `MakeGeneralGoldenTest` follow this corrected order draw-for-draw (14 fixtures / 180 draws, green).
