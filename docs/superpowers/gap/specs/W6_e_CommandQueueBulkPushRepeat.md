# W6_e: Command Queue ReserveBulk/Push/Repeat — Implementation Spec

**Slice ID:** W6_e  
**Domain:** Command Queue Management (general + nation twins)  
**Status:** Specification  
**Parity Base:** PHP `legacy/devsam-core/hwe/sammo/API/{Command,NationCommand}/{ReserveBulk,Push,Repeat}.php`

---

## Executive Summary

This slice implements **three ring-buffer queue-manipulation operations** (ReserveBulk, Push, Repeat) on the command turn rings, both for **general commands** (`general_turn`) and **nation commands** (`nation_turn`). These are **REST-only** endpoints — no new intake codes, no TurnDaemonCommand variants. The implementation uses existing [ReservedTurnRepository](file:///Users/apple/Desktop/개人프로젝트/opensamguk/infra/src/main/kotlin/opensamguk/infra/persistence/ReservedTurnRepository.kt) for the durable ring writes via JDBC.

The endpoints are exposed by **CommandController** (game-api) and dispatch to a new **CommandQueueService** (or expanded **CommandReserveService**), which calls the underlying ring-manipulation functions in the repository.

---

## PHP Ground Truth

### General Command APIs

#### 1. ReserveCommand.php — `POST /api/command/reserve`
**Location:** `legacy/devsam-core/hwe/sammo/API/Command/ReserveCommand.php` (lines 14–57)

```php
class ReserveCommand extends \sammo\BaseAPI {
    public function validateArgs(): ?string {
        $v = new Validator($this->args);
        $v->rule('required', ['action', 'turnList'])
          ->rule('lengthMin', 'action', 1)
          ->rule('integerArray', 'turnList');
        if (!$v->validate()) return $v->errorStr();
        return null;
    }
    
    public function getRequiredSessionMode(): int {
        return static::REQ_GAME_LOGIN | static::REQ_READ_ONLY;
    }
    
    public function launch(Session $session, ?DateTimeInterface $modifiedSince, ?string $reqEtag) {
        $action = $this->args['action'];
        $turnList = $this->args['turnList'];
        $arg = $this->args['arg']??[];
        
        if(!$turnList) return '턴이 입력되지 않았습니다';
        if(!in_array($action, Util::array_flatten(GameConst::$availableGeneralCommand))) {
            return '사용할 수 없는 커맨드입니다.';
        }
        if(!is_array($arg)) return '올바른 arg 형태가 아닙니다.';
        
        return setGeneralCommand($session->generalID, $turnList, $action, $arg);
    }
}
```

**Deny Strings** (exact byte-for-byte):
- `"턴이 입력되지 않았습니다"`
- `"사용할 수 없는 커맨드입니다."`
- `"올바른 arg 형태가 아닙니다."`

---

#### 2. ReserveBulkCommand.php — `POST /api/command/bulk`
**Location:** `legacy/devsam-core/hwe/sammo/API/Command/ReserveBulkCommand.php` (lines 14–77)

```php
class ReserveBulkCommand extends \sammo\BaseAPI {
    public function validateArgs(): ?string {
        foreach ($this->args as $idx => $turn) {
            $v = new Validator($turn);
            $v->rule('required', ['action', 'turnList'])
              ->rule('lengthMin', 'action', 1)
              ->rule('integerArray', 'turnList');
            if (!$v->validate()) return "{$idx}:{$v->errorStr()}";
        }
        return null;
    }
    
    public function getRequiredSessionMode(): int {
        return static::REQ_GAME_LOGIN | static::REQ_READ_ONLY;
    }
    
    public function launch(Session $session, ?DateTimeInterface $modifiedSince, ?string $reqEtag) {
        $briefList = [];
        foreach ($this->args as $idx => $turn) {
            $action = $turn['action'];
            $turnList = $turn['turnList'];
            $arg = $turn['arg'] ?? [];
            
            if (!$turnList) {
                return "{$idx}: 턴이 입력되지 않았습니다";
            }
            if (!in_array($action, Util::array_flatten(GameConst::$availableGeneralCommand))) {
                return "{$idx}: 사용할 수 없는 커맨드입니다.";
            }
            if (!is_array($arg)) {
                return "{$idx}: 올바른 arg 형태가 아닙니다.";
            }
            
            $partialResult = setGeneralCommand($session->generalID, $turnList, $action, $arg);
            if(!$partialResult['result']){
                return [
                    'result' => false,
                    'briefList' => $briefList,
                    'errorIdx' => $idx,
                    'reason' => $partialResult['reason']
                ];
            }
            $briefList[$idx] = $partialResult['brief'];
        }
        
        return [
            'result' => true,
            'briefList' => $briefList,
            'reason' => 'success'
        ];
    }
}
```

**Input Format (array of commands):**
```
[
  { "action": "che_매복", "turnList": [5, 10, 15], "arg": { "destCityID": 123 } },
  { "action": "che_인건비", "turnList": [7, 14], "arg": { "amount": 500 } },
  ...
]
```

**Deny Strings** (indexed):
- `"{idx}: 턴이 입력되지 않았습니다"`
- `"{idx}: 사용할 수 없는 커맨드입니다."`
- `"{idx}: 올바른 arg 형태가 아닙니다."`

---

#### 3. PushCommand.php — `POST /api/command/push`
**Location:** `legacy/devsam-core/hwe/sammo/API/Command/PushCommand.php` (lines 12–47)

```php
class PushCommand extends \sammo\BaseAPI {
    public function validateArgs(): ?string {
        $v = new Validator($this->args);
        $v->rule('required', ['amount'])
          ->rule('int', 'amount')
          ->rule('min', 'amount', -12)
          ->rule('max', 'amount', 12);
        if (!$v->validate()) return $v->errorStr();
        return null;
    }
    
    public function getRequiredSessionMode(): int {
        return static::REQ_GAME_LOGIN | static::REQ_READ_ONLY;
    }
    
    public function launch(Session $session, ?DateTimeInterface $modifiedSince, ?string $reqEtag) {
        $amount = $this->args['amount'];
        if($amount == 0) return '0은 불가능합니다';
        
        pushGeneralCommand($session->generalID, $amount);
        return ['result'=>true];
    }
}
```

**Constraints:**
- `amount` range: `-12 ≤ amount ≤ 12` (validator enforces)
- `amount != 0` (explicit deny: `"0은 불가능합니다"`)

**Deny String:**
- `"0은 불가능합니다"`

**Implementation (func_command.php:31–54):**
```php
function pushGeneralCommand(int $generalID, int $turnCnt=1) {
    if($turnCnt == 0) return;
    if($turnCnt < 0) {
        pullGeneralCommand($generalID, -$turnCnt);
        return;
    }
    if($turnCnt >= GameConst::$maxTurn) return;
    
    $db = DB::db();
    
    // Shift all rows DOWN by turnCnt (each turn_idx increases)
    $db->update('general_turn', [
        'turn_idx'=>$db->sqleval('turn_idx + %i', $turnCnt)
    ], 'general_id=%i ORDER BY turn_idx DESC', $generalID);
    
    // Wrap-around rows that exceed MAX_GENERAL_TURNS
    $db->update('general_turn', [
        'turn_idx'=>$db->sqleval('turn_idx - %i', GameConst::$maxTurn),
        'action'=>'휴식',
        'arg'=>'{}',
        'brief'=>'휴식'
    ], 'general_id=%i AND turn_idx >= %i', $generalID, GameConst::$maxTurn);
}
```

**Ring Semantics:**
- Positive `amount`: shifts all turns DOWN (inserts `amount` new "휴식" slots at index 0)
- Negative `amount`: calls `pullGeneralCommand(-amount)` (shifts turns UP, removes `amount` slots from the front)
- Guard: `amount >= GameConst::$maxTurn` (= 30) — no-op

---

#### 4. RepeatCommand.php — `POST /api/command/repeat`
**Location:** `legacy/devsam-core/hwe/sammo/API/Command/RepeatCommand.php` (lines 12–46)

```php
class RepeatCommand extends \sammo\BaseAPI {
    public function validateArgs(): ?string {
        $v = new Validator($this->args);
        $v->rule('required', ['amount'])
          ->rule('int', 'amount')
          ->rule('min', 'amount', 1)
          ->rule('max', 'amount', 12);
        if (!$v->validate()) return $v->errorStr();
        return null;
    }
    
    public function getRequiredSessionMode(): int {
        return static::REQ_GAME_LOGIN | static::REQ_READ_ONLY;
    }
    
    public function launch(Session $session, ?DateTimeInterface $modifiedSince, ?string $reqEtag) {
        $amount = $this->args['amount'];
        repeatGeneralCommand($session->generalID, $amount);
        return ['result'=>true];
    }
}
```

**Constraints:**
- `amount` range: `1 ≤ amount ≤ 12` (validator enforces)

**Implementation (func_command.php:81–107):**
```php
function repeatGeneralCommand(int $generalId, int $turnCnt) {
    if($turnCnt <= 0) return;
    if($turnCnt >= GameConst::$maxTurn) return;
    
    $db = DB::db();
    
    $reqTurn = $turnCnt;
    if($turnCnt * 2 > GameConst::$maxTurn) {
        $reqTurn = GameConst::$maxTurn - $turnCnt;
    }
    
    $turnList = $db->query(
        'SELECT turn_idx, `action`, arg, brief FROM general_turn 
         WHERE general_id=%i AND turn_idx < %i', 
        $generalId, $reqTurn
    );
    
    foreach($turnList as $turnItem) {
        $turnIdx = $turnItem['turn_idx'];
        // Generate target turn indices: turnIdx + turnCnt, + 2*turnCnt, ..., up to MAX
        $turnTarget = iterator_to_array(
            Util::range($turnIdx+$turnCnt, GameConst::$maxTurn, $turnCnt)
        );
        
        $db->update('general_turn', [
            'action'=>$turnItem['action'],
            'arg'=>$turnItem['arg'],
            'brief'=>$turnItem['brief']
        ], 'general_id=%i AND turn_idx IN %li', $generalId, $turnTarget);
    }
}
```

**Ring Semantics:**
- Copies turns 0..(turnCnt-1) forward by `turnCnt`, `2*turnCnt`, ..., filling slots up to the ring end
- Guard: `turnCnt >= GameConst::$maxTurn` — no-op
- De-duplication: if `turnCnt * 2 > 30`, the "reqTurn" is clamped to `30 - turnCnt` to avoid overwriting source turns

---

### Nation Command APIs (C3 Chief Commands)

#### 5. NationCommand/ReserveCommand.php
**Location:** `legacy/devsam-core/hwe/sammo/API/NationCommand/ReserveCommand.php`

**Identical to GeneralCommand/ReserveCommand.php except:**
- Calls `setNationCommand($session->generalID, $turnList, $action, $arg)` instead of `setGeneralCommand`
- Deny strings identical
- General must have `officer_level >= 5` (enforced by `setNationCommand`)

---

#### 6. NationCommand/ReserveBulkCommand.php
**Location:** `legacy/devsam-core/hwe/sammo/API/NationCommand/ReserveBulkCommand.php`

**Identical to GeneralCommand/ReserveBulkCommand.php except:**
- Calls `setNationCommand` per item (instead of `setGeneralCommand`)
- Deny strings identical
- General must have `officer_level >= 5` (enforced by `setNationCommand`)

---

#### 7. NationCommand/PushCommand.php
**Location:** `legacy/devsam-core/hwe/sammo/API/NationCommand/PushCommand.php` (lines 13–62)

```php
class PushCommand extends \sammo\BaseAPI {
    public function validateArgs(): ?string {
        $v = new Validator($this->args);
        $v->rule('required', ['amount'])
          ->rule('int', 'amount')
          ->rule('min', 'amount', -12)
          ->rule('max', 'amount', 12);
        if (!$v->validate()) return $v->errorStr();
        return null;
    }
    
    public function getRequiredSessionMode(): int {
        return static::REQ_GAME_LOGIN | static::REQ_READ_ONLY;
    }
    
    public function launch(Session $session, ?DateTimeInterface $modifiedSince, ?string $reqEtag) {
        $amount = $this->args['amount'];
        if($amount == 0) return '0은 불가능합니다';
        
        $db = DB::db();
        $me = $db->queryFirstRow('SELECT officer_level, nation FROM general WHERE no = %i', 
                                  $session->generalID);
        if(!$me) return '올바르지 않은 장수입니다.';
        if(!$me['nation']) return '국가에 소속되어 있지 않습니다.';
        if($me['officer_level'] < 5) return '수뇌가 아닙니다.';
        
        pushNationCommand($me['nation'], $me['officer_level'], $amount);
        return ['result'=>true];
    }
}
```

**Pre-Checks:**
- General exists and has valid nation_id (not 0)
- General has officer_level >= 5
- amount != 0

**Deny Strings:**
- `"0은 불가능합니다"`
- `"올바르지 않은 장수입니다."`
- `"국가에 소속되어 있지 않습니다."`
- `"수뇌가 아닙니다."`

---

#### 8. NationCommand/RepeatCommand.php
**Location:** `legacy/devsam-core/hwe/sammo/API/NationCommand/RepeatCommand.php`

**Identical to GeneralCommand/RepeatCommand.php pre-checks + identical deny strings, but:**
- Calls `repeatNationCommand($me['nation'], $me['officer_level'], $amount)`
- Requires `officer_level >= 5`

**Implementation (func_command.php:171–200):**
```php
function repeatNationCommand(int $nationID, int $officerLevel, int $turnCnt) {
    if($turnCnt <= 0) return;
    if($turnCnt >= GameConst::$maxChiefTurn) return;
    
    // (identical to repeatGeneralCommand but uses nation_turn table, maxChiefTurn=12)
}
```

---

## Ring Constants

| Constant | Value | Table | Semantics |
|----------|-------|-------|-----------|
| `MAX_GENERAL_TURNS` | 30 | `general_turn` | Ring buffer length for general commands |
| `MAX_CHIEF_TURNS` | 12 | `nation_turn` | Ring buffer length for nation commands |
| `DEFAULT_TURN_ACTION` | `"휴식"` | both | Default action when slot is unset |
| `EMPTY_ARG` | `"{}"` | both | Default (empty JSON object) arg |

---

## Kotlin Implementation Plan

### 1. New Endpoints (CommandController)

**Path:** `app/game-api/src/main/kotlin/opensamguk/gameapi/web/CommandController.kt`

Expand the existing `@RestController` with three POST methods:

```kotlin
/**
 * Reserve multiple commands in bulk (F4 Wave LC3).
 * POST /api/command/bulk
 * 
 * Body: Array<{ action: string, turnList: int[], arg?: object }>
 * Returns: 202 Accepted when AVAILABLE (all items reserved)
 *          200 OK with blocked/unknown reason when first item fails
 */
@PostMapping("/bulk")
fun bulkReserve(
    @AuthenticationPrincipal userId: Long?,
    @RequestParam generalId: Int,
    @RequestBody commandArray: List<Map<String, Any>>,
): ResponseEntity<Any> { ... }

/**
 * Push (shift) the general_turn ring forward/backward.
 * POST /api/command/push
 * 
 * Body: { amount: int }  // -12 <= amount <= 12, amount != 0
 * Returns: 202 Accepted on success
 */
@PostMapping("/push")
fun push(
    @AuthenticationPrincipal userId: Long?,
    @RequestParam generalId: Int,
    @RequestBody request: Map<String, Any>,
): ResponseEntity<Any> { ... }

/**
 * Repeat (fill forward) the general_turn ring.
 * POST /api/command/repeat
 * 
 * Body: { amount: int }  // 1 <= amount <= 12
 * Returns: 202 Accepted on success
 */
@PostMapping("/repeat")
fun repeat(
    @AuthenticationPrincipal userId: Long?,
    @RequestParam generalId: Int,
    @RequestBody request: Map<String, Any>,
): ResponseEntity<Any> { ... }
```

**Parallel endpoints for nation commands:**

```kotlin
/**
 * Nation command variants (chief/C3 commands).
 * Require: generalId's general.officer_level >= 5 AND nation_id > 0
 */
@PostMapping("/nation/bulk")
fun nationBulkReserve(...) { ... }

@PostMapping("/nation/push")
fun nationPush(...) { ... }

@PostMapping("/nation/repeat")
fun nationRepeat(...) { ... }
```

---

### 2. Service Layer (CommandQueueService)

**Path:** `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandQueueService.kt` (new file)

```kotlin
@Service
class CommandQueueService(
    private val reservedTurns: ReservedTurnRepository,
) {
    
    /**
     * Bulk-reserve multiple commands (general_turn ring).
     * Fails fast on first invalid item; returns partial briefList.
     * 
     * @throws IllegalArgumentException for validation failures (exact PHP deny strings)
     */
    fun reserveBulk(
        generalId: Int,
        commands: List<CommandBulkItem>,
    ): BulkReserveResult {
        val briefList = mutableListOf<String>()
        
        for ((idx, item) in commands.withIndex()) {
            // Validate per-command
            val turnList = item.turnList
            if (turnList.isEmpty()) {
                throw IllegalArgumentException("${idx}: 턴이 입력되지 않았습니다")
            }
            
            val action = item.action
            // TODO: validate action is in GameConst.availableGeneralCommand
            if (!isValidGeneralCommand(action)) {
                throw IllegalArgumentException("${idx}: 사용할 수 없는 커맨드입니다.")
            }
            
            val arg = item.arg ?: emptyMap()
            if (arg !is Map<*, *>) {
                throw IllegalArgumentException("${idx}: 올바른 arg 형태가 아닙니다.")
            }
            
            // Reserve each turn in turnList
            for (turnIdx in turnList) {
                reservedTurns.reserve(
                    generalId = generalId,
                    turnIdx = turnIdx,
                    actionCode = action,
                    argJson = JsonEncode(arg),
                    brief = commandObj.getBrief(),  // TODO: build brief from action+arg
                )
            }
            
            briefList.add(brief)
        }
        
        return BulkReserveResult(
            result = true,
            briefList = briefList,
            reason = "success"
        )
    }
    
    /**
     * Push (shift) the general_turn ring.
     * Positive amount: shift down (prepend empty slots)
     * Negative amount: shift up (remove from front)
     * amount == 0: deny "0은 불가능합니다"
     * abs(amount) >= MAX_GENERAL_TURNS: no-op
     */
    fun pushGeneral(generalId: Int, amount: Int) {
        if (amount == 0) {
            throw IllegalArgumentException("0은 불가능합니다")
        }
        if (amount > 0) {
            reservedTurns.pushGeneralTurn(generalId, amount)
        } else {
            reservedTurns.pullGeneralTurn(generalId, -amount)
        }
    }
    
    /**
     * Repeat (fill forward) the general_turn ring.
     * Copies turns 0..(amount-1) forward by amount, 2*amount, ..., up to MAX_GENERAL_TURNS.
     */
    fun repeatGeneral(generalId: Int, amount: Int) {
        // TODO: implement via ReservedTurnRepository
        // (may need to add repeatGeneralTurn(generalId, amount) method)
    }
    
    // Nation variants (identical logic with nation_turn table + officer_level checks)
    fun reserveBulkNation(generalId: Int, commands: List<CommandBulkItem>): BulkReserveResult { ... }
    fun pushNation(generalId: Int, nationId: Int, officerLevel: Int, amount: Int) { ... }
    fun repeatNation(generalId: Int, nationId: Int, officerLevel: Int, amount: Int) { ... }
}

data class CommandBulkItem(
    val action: String,
    val turnList: List<Int>,
    val arg: Map<String, Any>? = null,
)

data class BulkReserveResult(
    val result: Boolean,
    val briefList: List<String>,
    val reason: String,
    val errorIdx: Int? = null,
)
```

---

### 3. ReservedTurnRepository Extensions

**Path:** `infra/src/main/kotlin/opensamguk/infra/persistence/ReservedTurnRepository.kt` (expand)

Add two new methods:

```kotlin
/**
 * Push (shift) the general_turn ring by amount > 0.
 * Mirrors func_command.php:pushGeneralCommand.
 * 
 * Guards:
 *  - amount <= 0: no-op (caller must handle)
 *  - amount >= MAX_GENERAL_TURNS: no-op
 */
fun pushGeneralTurn(generalId: Int, amount: Int) {
    if (amount <= 0 || amount >= MAX_GENERAL_TURNS) return
    val params = MapSqlParameterSource()
        .addValue("general_id", generalId)
        .addValue("amount", amount)
    
    // 1. shift all rows DOWN (turn_idx increases)
    jdbc.update(
        """
        UPDATE general_turn
           SET turn_idx = turn_idx + :amount
         WHERE general_id = :general_id
         ORDER BY turn_idx DESC
        """.trimIndent(),
        params,
    )
    
    // 2. wrap rows that exceed MAX_GENERAL_TURNS back to the front and reset to 휴식
    jdbc.update(
        """
        UPDATE general_turn
           SET turn_idx = turn_idx - :max_turn,
               action_code = '휴식',
               arg = '{}'::jsonb,
               brief = '휴식'
         WHERE general_id = :general_id AND turn_idx >= :max_turn
        """.trimIndent(),
        MapSqlParameterSource(params.values).addValue("max_turn", MAX_GENERAL_TURNS),
    )
}

/**
 * Repeat (fill forward) the general_turn ring.
 * Mirrors func_command.php:repeatGeneralCommand.
 * 
 * Copies turns [0..min(amount, (MAX-amount))] forward by amount, 2*amount, ...
 */
fun repeatGeneralTurn(generalId: Int, amount: Int) {
    if (amount <= 0 || amount >= MAX_GENERAL_TURNS) return
    
    val reqTurn = if (amount * 2 > MAX_GENERAL_TURNS) MAX_GENERAL_TURNS - amount else amount
    val params = MapSqlParameterSource()
        .addValue("general_id", generalId)
        .addValue("req_turn", reqTurn)
    
    // Read source turns (turn_idx < reqTurn)
    val sourceTurns = jdbc.query(
        """
        SELECT turn_idx, action_code, arg::text AS arg, brief
          FROM general_turn
         WHERE general_id = :general_id AND turn_idx < :req_turn
         ORDER BY turn_idx ASC
        """.trimIndent(),
        params,
    ) { rs, _ ->
        Triple(
            rs.getInt("turn_idx"),
            mapOf(
                "action_code" to rs.getString("action_code"),
                "arg" to rs.getString("arg"),
                "brief" to rs.getString("brief"),
            )
        )
    }
    
    // For each source turn, generate target indices and update
    for ((srcIdx, srcData) in sourceTurns) {
        val targets = mutableListOf<Int>()
        var t = srcIdx + amount
        while (t < MAX_GENERAL_TURNS) {
            targets.add(t)
            t += amount
        }
        
        if (targets.isNotEmpty()) {
            jdbc.update(
                """
                UPDATE general_turn
                   SET action_code = :action_code,
                       arg = :arg::jsonb,
                       brief = :brief
                 WHERE general_id = :general_id AND turn_idx IN (:targets)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("general_id", generalId)
                    .addValue("action_code", srcData["action_code"])
                    .addValue("arg", srcData["arg"])
                    .addValue("brief", srcData["brief"])
                    .addValue("targets", targets),
            )
        }
    }
}

// Nation variants (identical logic, nation_turn table + officer_level)
fun pushNationTurn(nationId: Int, officerLevel: Int, amount: Int) { ... }
fun repeatNationTurn(nationId: Int, officerLevel: Int, amount: Int) { ... }
```

---

## REST Endpoints

### General Command Endpoints

| Method | Path | Input | Output | Notes |
|--------|------|-------|--------|-------|
| POST | `/api/command/bulk` | `[{ action, turnList, arg? }]` | 202 or 200 | Bulk reserve with indexed errors |
| POST | `/api/command/push` | `{ amount: ±[1..12] }` | 202 or 200 | Ring shift (push/pull) |
| POST | `/api/command/repeat` | `{ amount: 1..12 }` | 202 or 200 | Ring fill (repeat) |

### Nation Command Endpoints

| Method | Path | Input | Output | Notes |
|--------|------|-------|--------|-------|
| POST | `/api/command/nation/bulk` | `[{ action, turnList, arg? }]` | 202 or 200 | Require officer_level >= 5 |
| POST | `/api/command/nation/push` | `{ amount: ±[1..12] }` | 202 or 200 | Require officer_level >= 5 |
| POST | `/api/command/nation/repeat` | `{ amount: 1..12 }` | 202 or 200 | Require officer_level >= 5 |

---

## Exact Log Strings (Deny List)

All deny strings must be **byte-for-byte identical** to PHP:

### General Commands
- `"턴이 입력되지 않았습니다"` (ReserveCommand, ReserveBulkCommand)
- `"{idx}: 턴이 입력되지 않았습니다"` (ReserveBulkCommand indexed variant)
- `"사용할 수 없는 커맨드입니다."` (ReserveCommand, ReserveBulkCommand)
- `"{idx}: 사용할 수 없는 커맨드입니다."` (ReserveBulkCommand indexed)
- `"올바른 arg 형태가 아닙니다."` (ReserveCommand, ReserveBulkCommand)
- `"{idx}: 올바른 arg 형태가 아닙니다."` (ReserveBulkCommand indexed)
- `"0은 불가능합니다"` (PushCommand, RepeatCommand)

### Nation Commands (identical deny strings + additional pre-checks)
- `"0은 불가능합니다"` (PushCommand, RepeatCommand)
- `"올바르지 않은 장수입니다."` (PushCommand, RepeatCommand pre-check)
- `"국가에 소속되어 있지 않습니다."` (PushCommand, RepeatCommand pre-check)
- `"수뇌가 아닙니다."` (PushCommand, RepeatCommand pre-check)

---

## Side Effects (Database Writes)

**General Commands:**
- `general_turn` (INSERT ON CONFLICT / UPDATE): action_code, arg, brief
- `general_turn` (UPDATE): turn_idx shift (push/pull), turn_idx wrap (push), action_code/arg/brief reset

**Nation Commands:**
- `nation_turn` (INSERT ON CONFLICT / UPDATE): action_code, arg, brief
- `nation_turn` (UPDATE): turn_idx shift, turn_idx wrap, action_code/arg/brief reset

**Order:**
- Bulk reserve: multiple UPSERTs (one per turn per item)
- Push: two UPDATEs (shift down, wrap overflow)
- Repeat: one SELECT (read source turns), multiple UPDATEs (fill targets)

---

## Ambiguities & Risks

1. **Brief Calculation (ReserveBulk)**
   - PHP generates `brief` from the command object (`$commandObj->getBrief()`), which depends on the action's `Brief()` method + arg values.
   - **Risk:** The Kotlin code must mirror each action's brief formatter exactly. **Resolution:** Import the action's brief-builder from `:logic` (same as `:engine` intake handlers do).

2. **Turn Index Semantics (negative values in setGeneralCommand)**
   - PHP `setGeneralCommand` accepts special negative indices (-1 = odd turns, -2 = even, -3 = all turns).
   - ReserveBulk **does not use** these (only raw positive indices in `turnList`).
   - **Risk:** The validation must reject negative indices with "올바른 턴이 아닙니다."
   - **Resolution:** Validate `0 <= turnIdx < MAX_GENERAL_TURNS` in the loop.

3. **Nation Pre-Checks (Push/Repeat)**
   - The nation endpoints must fetch the general's `officer_level` and `nation_id` to verify the pre-conditions.
   - **Risk:** If the general changes nation or loses office between precheck and dispatch, the mutation may target the wrong ring or succeed silently.
   - **Resolution:** Fetch and cache `(nation_id, officer_level)` in the API layer before calling the service. Return deny immediately if checks fail.

4. **RNG & Golden Testing**
   - **Deterministic:** ReserveBulk, Push, Repeat have **no RNG**. The golden test must verify the turn indices are shifted/filled correctly without randomness.
   - **Resolution:** The ChangeRecorder delta fixtures will pin the DB state pre- and post-operation.

---

## Test Plan

### Unit Tests (CommandQueueService)

1. **ReserveBulk — General**
   - ✓ Valid multi-item reserve (verify briefList entries)
   - ✓ Empty turnList deny (`"{idx}: 턴이 입력되지 않았습니다"`)
   - ✓ Invalid action deny (`"{idx}: 사용할 수 없는 커맨드입니다."`)
   - ✓ Invalid arg deny (`"{idx}: 올바른 arg 형태가 아닙니다."`)
   - ✓ Partial success: 2nd item fails, briefList stops, errorIdx=1, reason=<partial failure>

2. **Push — General**
   - ✓ Positive amount (shift down): verify turn_idx wrap + 휴식 reset
   - ✓ Negative amount (shift up): calls pullGeneralTurn (via integration test)
   - ✗ amount == 0: deny `"0은 불가능합니다"`
   - ✓ amount >= 30: no-op (internal guard)

3. **Repeat — General**
   - ✓ amount=5: copy turns [0..5) to [5..10, 10..15, …]
   - ✓ amount=15 (> MAX/2): clamp reqTurn to 15, copy [0..15) to [15..30)
   - ✓ amount >= 30: no-op

4. **Push/Repeat — Nation**
   - ✓ officer_level >= 5 + nation_id > 0: allow
   - ✗ officer_level < 5: deny `"수뇌가 아닙니다."`
   - ✗ nation_id == 0: deny `"국가에 소속되어 있지 않습니다."`
   - ✗ general not found: deny `"올바르지 않은 장수입니다."`

### Integration Tests (CommandController)

1. **POST /api/command/bulk**
   - ✓ 200 OK with error message (invalid item)
   - ✓ 202 Accepted with briefList (success)
   - ✓ Ownership check (userId != generalId → 403)

2. **POST /api/command/push**
   - ✓ 202 Accepted (valid amount)
   - ✓ 200 OK with error (amount=0)

3. **POST /api/command/repeat**
   - ✓ 202 Accepted (valid amount)

4. **POST /api/command/nation/push + nation/repeat**
   - ✓ 202 Accepted (officer_level >= 5)
   - ✓ 200 OK with error (officer_level < 5)

### E2E Golden Test (ChangeRecorder)

1. **Bulk + Push + Repeat sequence**
   - Reserve turns [0, 5, 10] to "매복" + some arg
   - Push +3 (shifts all down, inserts 3 휴식 at [0..2])
   - Repeat 2 (copies [0..1] to [2..3, 4..5])
   - Verify final `general_turn` state matches PHP golden

---

## Files to Create/Modify

### New Files
- **CommandQueueService.kt** — `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandQueueService.kt`
- **CommandQueueTest.kt** — `app/game-api/src/test/kotlin/opensamguk/gameapi/reserve/CommandQueueTest.kt`

### Modified Files
- **CommandController.kt** — Add bulk, push, repeat, and nation variants
- **ReservedTurnRepository.kt** — Add pushGeneralTurn, repeatGeneralTurn, pushNationTurn, repeatNationTurn
- **ReserveBeans.kt** — (no change; no new bean wiring needed)

---

## Parity Checklist

- [ ] Deny strings: exact byte-for-byte match to PHP
- [ ] Ring shift semantics: pushGeneralCommand faithful replica (shift down, wrap reset)
- [ ] Ring repeat semantics: repeatGeneralCommand faithful replica (forward fill, overlap guard)
- [ ] Nation pre-checks: officer_level >= 5, nation_id > 0
- [ ] Bulk partial failure: indexed error strings, briefList accumulation, fast-fail on first error
- [ ] No RNG, no golden variance
- [ ] ChangeRecorder delta pinning (one-daemon-write rule NOT violated; REST-only, no intake codes)

---

## Notes for Implementer

1. **Brief Calculation:** ReserveBulk must call each action's brief-builder. Import from `:logic` action registry (study how `PlaceBetHandler` / `VoteHandler` do this; likely similar to `CommandRegistry.resolve(code).getBrief(arg)` or an action builder factory).

2. **Turn Index Validation:** Add explicit guards:
   ```kotlin
   val turnList = item.turnList
   if (turnList.any { it < 0 || it >= MAX_GENERAL_TURNS }) {
       throw IllegalArgumentException("올바른 턴이 아닙니다. : $turnIdx")
   }
   ```

3. **Push/Repeat Index Mapping:** The ring slot is `turnIdx % MAX` (handled in ReservedTurnRepository). The service receives absolute turn indices from the API; the repository normalizes them.

4. **Nation Officer-Level Check:** Cache the result in the API layer (`@PostMapping` method) before calling the service, to avoid TOCTOU race.

5. **Test Fixtures:** Use the existing `ChangeRecorder`-based golden pattern from `capture_vote.php` and `C2Handler` tests. Pin the initial `general_turn`/`nation_turn` state, run the operation, capture the final state, compare to PHP golden.

