# W6d Spec: General Join + BuildNationCandidate (거병 Intake Path)

**Slice ID:** W6d
**Feature:** General Join + BuildNationCandidate (pre-start 게임 진입/건국)
**PHP Truth:** `legacy/devsam-core/hwe/sammo/API/General/{Join,BuildNationCandidate}.php`
**Status:** INTAKE PATH (immediate, REST-driven, NOT turn-reserved)

---

## Overview

This slice migrates the **pre-game-start** general creation (Join.php) and **pre-opening-part** nation founding (BuildNationCandidate.php → che_거병) from PHP REST to Kotlin Spring + daemon command intake. The key distinction:

- **Join** (GeneralCreate) — POST `/api/general/join` — creates a player-owned general with RNG-drawn stats bonuses, face, turntime, city, age, specialty, experience, and an optional multi-turn history log. **DETERMINISTIC RNG** seeded by userId/timestamp; draws happen **in PHP** (genius%, bonus stat allocation).
- **BuildNationCandidate** (GenFound) — POST `/api/nation/build-candidate` — pre-start only; validates opening conditions, then dispatches che_거병 command to the daemon (turn-reserved path). che_거병 itself **creates a nation** (INSERT rows + diplomacy pairs), joins the general as lord (belong=1, officer_level=12), and draws the **unique-item lottery** after all writes.

**Critical:** BuildNationCandidate is a **GATE → che_거병** flow. The daemon runs che_거병 (existing logic); this spec only covers the **REST intake seam** that validates and queues it. The nation creation / diplomacy cascade / unique lottery all happen in the daemon's turn runner, **NOT in the intake handler**.

---

## 1. PHP Ground Truth Extraction

### 1.1 Join.php (GeneralCreate)

**File:** `legacy/devsam-core/hwe/sammo/API/General/Join.php:39-546`

#### Args (validateArgs)

```php
'required' => ['name', 'leadership', 'strength', 'intel', 'pic', 'character']
'int' => ['leadership', 'strength', 'intel', 'inheritTurntimeZone']
'boolean' => ['pic']
'stringWidthBetween' => 'name', 1, 18
'min' => [L/S/I], GameConst::$defaultStatMin (6 each)
'max' => [L/S/I], GameConst::$defaultStatMax (16 each)
'in' => 'character', GameConst::$availablePersonality | 'Random'
'in' => 'inheritSpecial', GameConst::$availableSpecialWar
'min' => 'inheritTurntimeZone', 0
'max' => 'inheritTurntimeZone', 59
'in' => 'inheritCity', CityConst::all()
'integerArray' => 'inheritBonusStat'
```

#### Deny Strings (exact byte-parity targets)

```
"잘못된 접근입니다!!!" (line 152) — no member row
"이미 등록하셨습니다!" (line 182) — oldGeneral exists
"이미 있는 장수입니다. 다른 이름으로 등록해 주세요!" (line 185) — name collision
"더이상 등록할 수 없습니다!" (line 188) — gencount >= maxgeneral
"이름이 짧습니다. 다시 가입해주세요!" (line 191) — name == ''
"이름이 유효하지 않습니다. 다시 가입해주세요!" (line 194) — mb_strwidth > 18
"능력치가 {defaultStatTotal}을 넘어섰습니다. 다시 가입해주세요!" (line 197) — sum > 48
"보너스 능력치가 잘못 지정되었습니다. 다시 가입해주세요!" (line 202) — count != 3
"보너스 능력치가 음수입니다. 다시 가입해주세요!" (line 206) — < 0
"보너스 능력치 합이 잘못 지정되었습니다. 다시 가입해주세요!" (line 213) — 0 < sum < 3 or sum > 5
"유산 포인트가 부족합니다. 다시 가입해주세요!" (line 247) — inheritTotalPoint < inheritRequiredPoint
"이미 천재가 모두 나타났습니다. 다시 가입해주세요!" (line 251) — inheritSpecial != null but genius count = 0
"도시가 잘못 지정되었습니다. 다시 가입해주세요!" (line 255) — inheritCity invalid
"장수 직접 생성이 불가능한 모드입니다." (line 172) — block_general_create & 1
```

#### Side-Effects (DB writes in PHP run() order)

1. **general table INSERT** (lines 404-438):
   - owner, name (htmlspecialchars + WebUtil::htmlPurify + StringUtil strip), owner_name
   - picture, imgsvr (from member if show_img_level && grade >= 1 && member.picture && pic=true, else "default.jpg", imgsvr=0)
   - nation=0, city (RNG choice from level 5-6 neutral, or inherit if inheritCity set)
   - affinity (RNG nextRangeInt 1-150), age, startage
   - L/S/I (base + bonus), experience, dedication=0
   - gold=defaultGold (500), rice=defaultRice (500)
   - crew=0, train=0, atmos=0, officer_level=0, turntime (RNG or inherit), killturn=6, crewtype=DEFAULT_CREWTYPE
   - betray (0, or +2 if relYear >= 4)
   - personal (character), specage, special (defaultDomestic), specage2, special2 (genius type or default)
   - penalty (JSON serialized active penalties)

2. **general_access_log INSERT** (lines 440-444)
   - generalID, userID, lastRefresh

3. **name obfuscation** (lines 446-451) — if blockCustomGeneralName=true:
   - name = Auction::genObfuscatedName(generalID)

4. **general_turn INSERT** (lines 454-464) — maxTurn rows:
   - action='휴식', brief='휴식', arg=null

5. **rank_data INSERT** (lines 466-475) — one row per RankColumn:
   - value=0 for all types

6. **inheritance point spend** (lines 479-488) — if inheritRequiredPoint > 0:
   - inheritance_{userID} 'previous' = [newTotalPoint, null]
   - rank_data inherit_point_spent_dynamic += inheritRequiredPoint

7. **inheritance point bonus** (lines 491-498) — if restInheritPoint > 0:
   - inheritance_{userID} 'previous' = [newTotalPoint, null]

8. **Action/History logs** (lines 502-528):
   - ActionLogger (general-scoped): "삼국지 모의전투 PHP의 세계에 오신 것을 환영합니다 ^o^" etc.
   - ActionLogger (general-scoped, genius): "축하합니다! 천재로 태어나 처음부터 {speicalText} 특기를 가지게 됩니다!"
   - Global action log (genius): "<G><b>{cityname}</b></>에서 <Y>{name}</>{josaRa}는 기재가 천하에 이름을 알립니다." + special-war text
   - Global action log (non-genius): "<G><b>{cityname}</b></>에서 <Y>{name}</>{josaRa}는 호걸이 천하에 이름을 알립니다."
   - Global history log (genius): "<L><b>【천재】</b></><G><b>{cityname}</b></>에 천재가 등장했습니다."
   - General history log: "<Y>{name}</>, <G>{cityname}</>에서 큰 뜻을 품다."
   - National history log: NOT WRITTEN (general has nation=0)

9. **member_log INSERT** (lines 532-542):
   - action_type='make_general', action=JSON {server, type, generalID, generalName}

10. **game_env.genius decrement** (lines 267-271):
    - if genius && gennum > 0: genius -= 1

11. **KVStorage inheritance_{userID} writes** (lines 481, 495):
    - userLogger flush (inheritance log to inheritance_log table)

#### RNG (join.php:226-231)

```php
$rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
    UniqueConst::$hiddenSeed,    // server-wide seed
    'MakeGeneral',               // action name
    $userID,
    $now                         // TimeUtil::now(false) — float timestamp
)));
```

**RNG Draws (in call order):**

1. **genius** (line 264) — `$rng->nextBool(0.01)` → 1% chance (unless inheritSpecial set)
2. **city** (line 283) — `$rng->choice($cities)` — one of ~level 5-6 neutral/empty cities
3. **stat bonus allocation** (lines 293-305) — `$rng->nextRangeInt(3, 5)` for count, then `$rng->choiceUsingWeight([$leadership, $strength, $intel])` per point
4. **age** (line 314) — `$rng->nextRangeInt(0, 1)` (0 or 1 subtracted from base)
5. **special2 (war)** (line 321) — `SpecialityHelper::pickSpecialWar($rng, stats)` if genius
6. **character** (line 389) — `$rng->choice(GameConst::$availablePersonality)` if character='Random'
7. **affinity** (line 392) — `$rng->nextRangeInt(1, 150)`
8. **inheritTurntime** (lines 359, 363) — `$rng->nextRangeInt(0, turnterm-1)` + `$rng->nextRangeInt(0, 999999) / 1000000`

**Critical:** All RNG is in-PHP. No golden golden needed for Join itself; it's **fully deterministic** given the seed, userID, and timestamp.

---

### 1.2 BuildNationCandidate.php

**File:** `legacy/devsam-core/hwe/sammo/API/General/BuildNationCandidate.php:22-98`

#### Args

None (no arguments accepted).

#### Deny Strings (exact byte-parity)

```
"장수가 없습니다" (line 45) — no general row for userID
"게임이 시작되었습니다." (line 53) — turntime > opentime (game started)
"이미 국가에 소속되어있습니다." (line 57) — general.nation != 0
"거병할 수 없는 모드입니다." (line 73) — che_거병 not in availableGeneralCommand
```

#### Side-Effects

**Direct REST handler:** NONE. All writes deferred to the daemon che_거병 command.

**Indirect (via che_거병 daemon execution):**

See che_거병.php (lines 65-185) below.

#### Pre-condition Checks

1. General ownership check (from session)
2. Game state: turntime < opentime (pre-start only)
3. General nation state: nation == 0 (neutral)
4. Command availability: che_거병 in availableGeneralCommand

#### RNG (BuildNationCandidate.php:82-86)

```php
$rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
    UniqueConst::$hiddenSeed,
    'BuildNationCandidate',
    $generalID,
)));
```

This seed is passed to che_거병->run($rng), but che_거병 **does not draw from it**. The RNG is unused in the PHP flow — it's only a seam for future unique-item lottery.

---

### 1.3 che_거병.php (che_거병 Command)

**File:** `legacy/devsam-core/hwe/sammo/Command/General/che_거병.php:25-186`

The **turn-reserved** command that creates the nation. This is NOT an immediate intake; it runs when the general's turn is executed. However, for parity checks, note:

#### Side-Effects (in run() order)

1. **nation INSERT** (lines 98-110):
   - name (general name + ㉥ dedup), color=#330000, gold=0, rice=baserice(2000)
   - rate=20, bill=100, strategic_cmd_limit=12, surlimit=72, secretlimit(1 if scenario>=1000, else 3)
   - type=neutralNationType, gennum=1

2. **diplomacy INSERT** (lines 114-138) — for each existing nation:
   - Two rows: {me:existing, you:new} and {me:new, you:existing}, state=2, term=0

3. **nation_turn INSERT** (lines 142-156) — 24 rows:
   - officer_level [12, 11], turn_idx 0..maxChiefTurn-1, action='휴식', arg=null, brief='휴식'

4. **general UPDATE** (lines 172-176):
   - belong=1, officer_level=12, officer_city=0, nation=nationID
   - experience += 100, dedication += 100

5. **game_env refreshNationStaticInfo()** (line 158)

6. **Logs** (lines 160-165):
   - General action log: "거병에 성공하였습니다. <1>{date}</>"
   - Global action log: "<Y>{generalName}</>{josaYi} <G><b>{cityName}</b></>에 거병하였습니다."
   - Global history log: "<Y><b>【거병】</b></><D><b>{generalName}</b></>{josaYi} 세력을 결성하였습니다."
   - General history log: "<G><b>{cityName}</b></>에서 거병"
   - National history log: "<Y>{generalName}</>{josaYi} <G><b>{cityName}</b></>에서 거병"

7. **Unique item lottery** (line 181):
   - `tryUniqueItemLottery(\sammo\genGenericUniqueRNGFromGeneral($general, '거병'), $general);`
   - This draws from a **separate RNG** keyed by general + action name; default human general has 0% chance.

#### Constraints (init -> fullConditionConstraints)

```php
BeNeutral()
BeOpeningPart($relYear+1)  // opening-part year boundary
AllowJoinAction()           // makelimit == 0 (not on cooldown)
NoPenalty(PenaltyKey::NoFoundNation)
```

---

## 2. Kotlin Wire Shape

### 2.1 TurnDaemonCommand Variants (common module)

Add to `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`:

**Note:** Join is NOT a daemon command; it's a pure REST handler. Only BuildNationCandidate queues a daemon command (che_거병 is turn-reserved, not immediate-intake, so no new variant here).

**Actually, the distinction:**
- **BuildNationCandidate REST** validates pre-conditions, then calls `che_거병.run(rng)` **inline** within the API handler (same as PHP).
- The **che_거병 command itself** is a turn-reserved general command (not a daemon intake).

**Therefore, NO NEW DAEMON COMMAND VARIANTS are introduced for W6d.** The BuildNationCandidate REST handler directly invokes the existing che_거병 logic.

However, if a future wave wants to make che_거병 an immediate daemon intake (like the troop/board/vote commands), it would add:

```kotlin
@Serializable
@SerialName("buildNationCandidate")
data class BuildNationCandidate(
    val requestId: String? = null,
    val generalId: Int,
) : TurnDaemonCommand() {
    override val type: String get() = "buildNationCandidate"
}
```

**For W6d, this is NOT added.** che_거병 stays turn-reserved.

---

### 2.2 TurnDaemonCommandResult Variants

None for W6d (no daemon command).

---

## 3. REST Endpoints

### 3.1 POST /api/general/join (GeneralCreate)

**Purpose:** Create a player-owned general with stats, RNG-drawn bonuses, and turn/city/specialty assignments.

**Request Body:**
```json
{
  "name": "string (1-18 width)",
  "leadership": "int (6-16)",
  "strength": "int (6-16)",
  "intel": "int (6-16)",
  "pic": "boolean",
  "character": "string (personality | 'Random')",
  "inheritSpecial": "string | null",
  "inheritTurntimeZone": "int (0-59) | null",
  "inheritCity": "int (city ID) | null",
  "inheritBonusStat": "[int, int, int] | null"
}
```

**Response (202 Accepted):**
```json
{
  "requestId": "UUID",
  "generalId": int,
  "status": "success"
}
```

**Response (400 Bad Request / 403 Forbidden):**
```json
{
  "error": "string (deny message, exact byte-parity to PHP)"
}
```

**Controller:** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GeneralController.kt` (new method)

**DB Writes:**
- general table INSERT
- general_access_log INSERT
- general_turn INSERT (maxTurn rows)
- rank_data INSERT (per RankColumn)
- inheritance_log (if inheritRequiredPoint > 0)
- game_env.genius decrement (if applicable)

**Parity Notes:**
- Seed: `hiddenSeed + 'MakeGeneral' + userID + now (float)`
- RNG draws must match PHP order exactly (genius%, city, stat allocation, age, special, character, affinity, turntime)
- All log messages byte-exact to PHP ActionLogger / GlobalActionLog

---

### 3.2 POST /api/nation/build-candidate (GenFound)

**Purpose:** Pre-game-start nation founding — validates conditions, executes che_거병 (turn-reserved), logs outcomes.

**Request Body:**
```json
{}  // no arguments
```

**Response (202 Accepted):**
```json
{
  "requestId": "UUID",
  "nationId": int,
  "status": "success"
}
```

**Response (400 Bad Request / 403 Forbidden):**
```json
{
  "error": "string (deny message, exact byte-parity to PHP)"
}
```

**Controller:** `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/NationController.kt` (new method)

**Inline Execution (NOT daemon):**
1. Load general from session
2. Validate: turntime < opentime, nation == 0
3. Validate constraints: BeNeutral, BeOpeningPart, AllowJoinAction, NoPenalty(NoFoundNation)
4. Create RNG seeded `hiddenSeed + 'BuildNationCandidate' + generalID`
5. Call che_거병.run(rng) → returns bool
6. If true: return 202 with nationId; else return 400 with che_거병.failReason

**DB Writes (from che_거병):**
- nation INSERT
- diplomacy INSERT (per existing nation pair)
- nation_turn INSERT (24 rows)
- general UPDATE (nation, belong, officer_level, officer_city, experience, dedication)
- game_env.genius decrement (if applicable)
- Action/General/Global/National history logs

**Parity Notes:**
- Seed: `hiddenSeed + 'BuildNationCandidate' + generalID` (no timestamp)
- RNG unused (future seam for unique lottery)
- Nation name dedup with ㉥ (U+3265) — two-pass collision check
- secretlimit = 1 if scenario >= 1000, else 3

---

## 4. Kotlin Implementation Plan

### 4.1 New Files

1. **`app/game-api/src/main/kotlin/opensamguk/gameapi/controller/GeneralController.kt`**
   - `POST /api/general/join` method
   - Validation (Validator), session check, RNG seeding, DB writes
   - Return 202 with requestId + generalId, or 400 with deny reason

2. **`app/game-api/src/main/kotlin/opensamguk/gameapi/controller/NationController.kt`** (new)
   - `POST /api/nation/build-candidate` method
   - Pre-condition validation, inline che_거병 execution, 202 response

3. **`app/game-api/src/main/kotlin/opensamguk/gameapi/dto/CreateGeneralRequest.kt`** (new)
   - DTO for Join request validation

4. **`app/game-engine/src/main/kotlin/opensamguk/engine/intake/GeneralCreateHandler.kt`** (new)
   - If Join is migrated to daemon intake (future wave) — currently NOT used

5. **`logic/src/main/kotlin/opensamguk/logic/actions/intake/GeneralCreate.kt`** (new)
   - Pure logic for RNG seeding, stat bonus allocation, age/specialty/affinity/turntime calculation
   - Input: userID, name, base stats, inherit flags, RNG
   - Output: general draft entity with all calculated fields

---

### 4.2 Shared File Edits

1. **`app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt`**
   - **No change** — Join and BuildNationCandidate are REST-only, not immediate intake commands

2. **`common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`**
   - **No change** — che_거병 stays turn-reserved

3. **`common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt`**
   - **No change**

4. **`app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonCommandDispatcher.kt`**
   - **No change** — no new daemon command

---

### 4.3 Logic Seam Mirrors

**GeneralCreate (logic/intake/GeneralCreate.kt)** mirrors PlaceBetHandler pattern:

```kotlin
object GeneralCreate {
    data class Input(
        val userId: Int,
        val name: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val picture: String,
        val imageSvr: Int,
        val inheritSpecial: String?,
        val inheritTurntimeZone: Int?,
        val inheritCity: Int?,
        val inheritBonusStat: List<Int>?,
        val character: String,
        val rng: RandUtil,
        // env inputs
        val maxGeneral: Int,
        val scenario: Int,
        val turnTerm: Int,
        val turnTime: String,
        val showImgLevel: Int,
        val genius: Int,
        val blockGeneralCreate: Int,
        val relYear: Int,
    )
    
    data class Output(
        val general: General,      // for insert
        val turnRows: List<...>,   // for insert
        val rankRows: List<...>,
        val log: ActionLog,
        val inheritSpend: Int?,
        val inheritBonusPoints: Int?,
    )
    
    fun create(input: Input): Result<Output> {
        // 1. validations (name, stats, inherit points, etc.)
        // 2. RNG draws (genius%, city, stat bonus, age, special, character, affinity, turntime)
        // 3. return Output with all calculated fields
    }
}
```

---

## 5. Exact String Targets (Byte-Parity Deny Strings)

These must be returned byte-for-byte identical to PHP:

```
"잘못된 접근입니다!!!"
"이미 등록하셨습니다!"
"이미 있는 장수입니다. 다른 이름으로 등록해 주세요!"
"더이상 등록할 수 없습니다!"
"이름이 짧습니다. 다시 가입해주세요!"
"이름이 유효하지 않습니다. 다시 가입해주세요!"
"능력치가 {defaultStatTotal}을 넘어섰습니다. 다시 가입해주세요!"
"보너스 능력치가 잘못 지정되었습니다. 다시 가입해주세요!"
"보너스 능력치가 음수입니다. 다시 가입해주세요!"
"보너스 능력치 합이 잘못 지정되었습니다. 다시 가입해주세요!"
"유산 포인트가 부족합니다. 다시 가입해주세요!"
"이미 천재가 모두 나타났습니다. 다시 가입해주세요!"
"도시가 잘못 지정되었습니다. 다시 가입해주세요!"
"장수 직접 생성이 불가능한 모드입니다."

"장수가 없습니다"
"게임이 시작되었습니다."
"이미 국가에 소속되어있습니다."
"거병할 수 없는 모드입니다."
```

---

## 6. Side-Effects & DB Writes (Intake Order)

### Join (GeneralCreate REST)

1. **general** — INSERT (owner, name, city, affinity, L/S/I, age, personality, special, special2, etc.)
2. **general_access_log** — INSERT
3. **general_turn** — INSERT ×maxTurn
4. **rank_data** — INSERT ×8 (one per RankColumn)
5. **inheritance_log** (if inheritRequiredPoint > 0) — via userLogger flush
6. **game_env** — UPDATE genius (decrement, if drawn)
7. **member_log** — INSERT

### BuildNationCandidate → che_거병 (inline, turn-reserved)

1. **nation** — INSERT (name, color, gold, rice, rate, bill, etc.)
2. **diplomacy** — INSERT ×2N (one pair per existing nation)
3. **nation_turn** — INSERT ×24
4. **general** — UPDATE (nation, belong, officer_level, officer_city, experience, dedication)
5. **ActionLog / GlobalActionLog / GeneralHistoryLog / NationalHistoryLog** — via logger flush
6. **game_env** — refreshNationStaticInfo() (infra seam)

---

## 7. Test Plan

### Join Unit Tests

1. **Happy path:** Valid args, RNG seeding, all draws in order
   - Assert: general row created with correct fields
   - Assert: 24 turn rows, 8 rank_data rows
   - Assert: RNG sequence matches PHP order (genius%, city, stat bonus, age, special, character, affinity, turntime)

2. **Deny cases (each exact error message):**
   - Already has general
   - Name collision
   - Stat sum > 48
   - Character='Random' → RNG choice applied
   - inheritBonusStat [2, 2, 1] → sum=5 allowed; [2, 2, 2] → sum=6 denied
   - inheritCity invalid
   - inheritSpecial but genius count=0

3. **Inheritance point spend/refund:**
   - inheritCity (inherit_born_city_point=150)
   - inheritBonusStat (inherit_born_stat_point=100)
   - inheritSpecial (inherit_born_special_point=250)
   - inheritTurntimeZone (inherit_born_turntime_point=100)
   - Assertion: inheritance_{userID} 'previous' updated, rank_data inherit_point_spent_dynamic bumped

4. **Genius logic:**
   - No inheritSpecial: 1% chance (RNG nextBool(0.01))
   - inheritSpecial set: genius=true (auto)
   - Genius but count exhausted: deny
   - No genius: specage2 = (retirement_year - age) / 6 - relYear/2 + age (min 3)

5. **Affinity draw:**
   - RNG nextRangeInt(1, 150) → expect 1..150 inclusive

6. **Turntime inheritance:**
   - inheritTurntimeZone=5 → base = 5 * turnterm + RNG(0..turnterm-1) + RNG micro-fraction
   - Assertion: turntime >= cutTurn + 1 turn (no same-turn action)

### BuildNationCandidate Unit Tests

1. **Happy path:**
   - Valid pre-conditions (neutral, nation=0, turntime < opentime)
   - che_거病 runs, returns true
   - Assert: nation row created, general.nation set, 202 response with nationId

2. **Deny cases (exact messages):**
   - No general
   - Game started (turntime >= opentime)
   - Already in a nation
   - che_거병 not available (command mode check)

3. **Nation dedup:**
   - First che_거病: "John" → nation name="John"
   - Second che_거病: "John" → nation name="㉥John"
   - Third che_거病: "John" → nation name="㉥㉥John" (no truncate)

4. **Diplomacy cascade:**
   - Assert: N (existing nations) → 2N diplomacy rows inserted (me:existing↔you:new)

---

## 8. Known Risks & Open Questions

1. **RNG Seeding Parity:** Join draws from 8 RNG calls in precise PHP order. The seed `hiddenSeed + 'MakeGeneral' + userID + now(float)` must match LiteHashDRBG exactly. **Golden required** for each RNG test case (PHP vs. Kotlin comparison).

2. **Genius % Calculation:** PHP `nextBool(0.01)` — how precisely is 0.01 interpreted in LiteHashDRBG? Need to verify 1% draw matches. **Golden test needed**.

3. **City Selection:** RNG choice from `city WHERE level 5-6 AND (nation=0 OR nation=0)` — must verify the SQL order is deterministic (PHP uses queryFirstColumn, likely ID order). **Verify query order**.

4. **Member Penalty Check:** PHP reads penalties from `member.penalty` JSON and filters by expiry. Kotlin REST handler must replicate this filter (current seam: read from rootDB member table, not passed via wire). **Confirm seam location**.

5. **Unique Item Lottery:** che_거병 calls `tryUniqueItemLottery(genGenericUniqueRNGFromGeneral, general)` after all writes. This is a **separate RNG seam** wired by the engine handler (not part of Join intake). Default human general has 0% draw (no golden needed for W6d, but note for P6+ waves).

6. **Inheritance Point Logic:** The calcRestInheritPoint and applyInheritanceUser calls are **PHP seam reads** (looking at previous seasons' games). Must be migrated to Kotlin-side logic or stubbed as read-only. Currently flagged as **P6 succession seam** (no Kotlin logic written yet).

7. **Log Timestamps:** General action log line "통솔 <C>$pleadership</> 무력 <C>$pstrength</> 지력 <C>$pintel</> 의 보너스를 받으셨습니다." has interpolated bonus stats — must preserve exact format with color codes (`<C>…</>`).

8. **Block Custom General Name:** If `block_general_create & 2`, name is obfuscated to hex. Kotlin must call `Auction::genObfuscatedName(generalID)` (existing Kotlin service or replicate PHP logic).

---

## 9. Test Parity Checklist

- [ ] Join deny strings (12 cases) — byte-exact
- [ ] BuildNationCandidate deny strings (4 cases) — byte-exact
- [ ] RNG seeding (seed string, order of draws)
- [ ] Genius % (PHP 1% vs Kotlin nextBool(0.01))
- [ ] Bonus stat allocation (3-5 points, choiceUsingWeight)
- [ ] Age calculation (base + 2*(L+S+I bonus) - RNG(0,1))
- [ ] Special2 type (genius or default)
- [ ] Affinity range (1-150 inclusive)
- [ ] Turntime base + inherit + micro-fraction
- [ ] City selection (level 5-6, neutral, RNG choice)
- [ ] Character random choice (if 'Random' input)
- [ ] Inheritance point spend/refund (per type)
- [ ] Betray stat (0 or +2 if relYear >= 4)
- [ ] Experience calculation (base or 80% of 20th percentile general if relYear >= 3)
- [ ] Action log color codes (`<C>`, `<Y>`, `<G>`, `<D>`, `<L>`)
- [ ] General name HTML purify (htmlspecialchars + WebUtil::htmlPurify + textStrip)
- [ ] Nation dedup ㉥ prefix (up to 2 iterations)
- [ ] Diplomacy pairs (2 per existing nation, state=2, term=0)
- [ ] Nation turn rows (24 = 2 officer_levels × 12 turns)
- [ ] game_env.genius count (decrement if drawn)
- [ ] member_log action JSON shape

---

## 10. Existing Kotlin Seam Reuse

- **FoundingCascade.kt** — NOT USED for W6d (che_거병 does its own writes, no wandering cascade)
- **CheGeobyeong.kt** — EXISTING logic for che_거병 (turn-reserved command). Use for reference only; BuildNationCandidate REST handler calls the existing PHP/Kotlin path inline.
- **GeneralActionDraft** — NOT USED (Join creates general directly, no draft)
- **ChangeRecorder** — NOT USED (Join is REST, not daemon intake; no delta recording via ChangeRecorder)
- **PlaceBetHandler** — MIRROR PATTERN for GeneralCreateHandler (if future wave wants immediate-intake version)

---

## 11. Summary

**W6d introduces:**
1. **REST endpoint** `/api/general/join` (GeneralCreate) — immediate, no daemon involvement
2. **REST endpoint** `/api/nation/build-candidate` (GenFound) — pre-start gate + inline che_거병 execution (turn-reserved, not immediate-intake)
3. **Logic** (intake/GeneralCreate.kt) — RNG-driven stat bonuses, age/specialty/affinity/turntime calculation
4. **Database writes** — 7 tables (general, general_access_log, general_turn, rank_data, inheritance_log, game_env, member_log) for Join; 5 tables (nation, diplomacy, nation_turn, general, game_env) for BuildNationCandidate

**No daemon command variants introduced** — che_거병 remains turn-reserved.

**RNG:** Join draws 8 values; BuildNationCandidate carries unused RNG (seam for future unique lottery). **Golden required for Join RNG parity.**

**Byte-parity:** 16 deny strings, action log color codes, log message templates, nation dedup ㉥ prefix.

