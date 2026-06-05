# W5d - Diplomacy Letters Send/Rollback/Destroy

**Slice ID:** W5d  
**PHP reference:** `legacy/devsam-core/hwe/j_diplomacy_{send,rollback,destroy}_letter.php`  
**Kotlin pattern:** F4 Wave C2 intake (mirrors BoardHandler + VoteHandler)  
**Status:** IMPLEMENTATION-READY spec  

---

## Overview

Three diplomacy letter intake actions (immediate, not turn-reserved):
1. **diploSendLetter** — send a new diplomacy letter (외교 서신 발송)
2. **diploRollbackLetter** — recall/rollback an unsent letter (외교 서신 회수)
3. **diploDestroyLetter** — destroy an activated letter with optional two-phase agreement (외교 서신 파기)

All three are **immediate-intake** commands (CommandWireMapper → TurnDaemonCommand → TurnDaemonCommandDispatcher → DiplomacyLetterHandler), NOT turn-reserved.

---

## Wire Variants (TurnDaemonCommand sealed class)

### DiploSendLetter
```kotlin
@Serializable
@SerialName("diploSendLetter")
data class DiploSendLetter(
    val requestId: String? = null,
    val generalId: Int,
    val destNationId: Int,        // target nation (상대국)
    val prevLetterNo: Int? = null, // nullable; if < 1, treat as null (j_diplomacy_send_letter.php:23-25)
    val textBrief: String,         // required; trimmed, must be non-empty
    val textDetail: String,        // required; trimmed (can be empty after trim)
) : TurnDaemonCommand() {
    override val type: String get() = "diploSendLetter"
}
```

### DiploRollbackLetter
```kotlin
@Serializable
@SerialName("diploRollbackLetter")
data class DiploRollbackLetter(
    val requestId: String? = null,
    val generalId: Int,
    val letterNo: Int,
) : TurnDaemonCommand() {
    override val type: String get() = "diploRollbackLetter"
}
```

### DiploDestroyLetter
```kotlin
@Serializable
@SerialName("diploDestroyLetter")
data class DiploDestroyLetter(
    val requestId: String? = null,
    val generalId: Int,
    val letterNo: Int,
) : TurnDaemonCommand() {
    override val type: String get() = "diploDestroyLetter"
}
```

---

## Result Variants (TurnDaemonCommandResult)

### DiploSendLetterOk / DiploSendLetterFail
```kotlin
@Serializable
data class DiploSendLetterOk(
    override val type: String = "diploSendLetter",
    override val ok: Boolean = true,
    val letterNo: Int,  // ng_diplomacy.id (the newly inserted letter)
) : TurnDaemonCommandResult()

@Serializable
data class DiploSendLetterFail(
    override val type: String = "diploSendLetter",
    override val ok: Boolean = false,
    val reason: String,
) : TurnDaemonCommandResult()
```

### DiploRollbackLetterOk / DiploRollbackLetterFail
```kotlin
@Serializable
data class DiploRollbackLetterOk(
    override val type: String = "diploRollbackLetter",
    override val ok: Boolean = true,
    val letterNo: Int,
) : TurnDaemonCommandResult()

@Serializable
data class DiploRollbackLetterFail(
    override val type: String = "diploRollbackLetter",
    override val ok: Boolean = false,
    val reason: String,
) : TurnDaemonCommandResult()
```

### DiploDestroyLetterOk / DiploDestroyLetterFail
```kotlin
@Serializable
data class DiploDestroyLetterOk(
    override val type: String = "diploDestroyLetter",
    override val ok: Boolean = true,
    val letterNo: Int,
    val state: String,  // "activated" (first request) or "cancelled" (both agreed / destroyed)
) : TurnDaemonCommandResult()

@Serializable
data class DiploDestroyLetterFail(
    override val type: String = "diploDestroyLetter",
    override val ok: Boolean = false,
    val reason: String,
) : TurnDaemonCommandResult()
```

---

## Intake Codes (CommandWireMapper)

Add to `CommandWireMapper.intakeCodes`:
```kotlin
val intakeCodes: Set<String> = setOf(
    // ... existing codes ...
    "diploSendLetter",
    "diploRollbackLetter",
    "diploDestroyLetter",
)
```

Add to `CommandWireMapper.toCommand()`:
```kotlin
"diploSendLetter" -> TurnDaemonCommand.DiploSendLetter(
    requestId = requestId,
    generalId = generalId,
    destNationId = args.int("destNation") ?: 0,
    prevLetterNo = args.int("prevNo"),
    textBrief = args.str("brief") ?: "",
    textDetail = args.str("detail") ?: "",
)
"diploRollbackLetter" -> TurnDaemonCommand.DiploRollbackLetter(
    requestId = requestId,
    generalId = generalId,
    letterNo = args.int("letterNo") ?: 0,
)
"diploDestroyLetter" -> TurnDaemonCommand.DiploDestroyLetter(
    requestId = requestId,
    generalId = generalId,
    letterNo = args.int("letterNo") ?: 0,
)
```

---

## PHP Business Logic (Faithful Ports)

### 1. diploSendLetter (j_diplomacy_send_letter.php)

**Gates & guards (in order):**
1. Access limit check (`checkLimit` refresh_score)
2. destNationNo != self nation
3. Brief/detail present (null check)
4. Brief trimmed and non-empty
5. Permission >= 4 (수뇌부, chief)
6. If prevLetterNo > 0, verify prevLetter exists and is in state 'proposed' or other (no state check per line 76)
7. If prevLetterNo, verify no NEWER letter exists (`SELECT count(*) FROM ng_diplomacy WHERE prev_no = prevNo AND state != 'cancelled'`)
8. If prevLetterNo and prevLetter.state == 'proposed', update prev state to 'replaced' with reason `{ who, action: 'new_letter', reason: 'new_letter' }`

**Side effects (in order):**
1. Increment refresh_score ("외교부", 1) via `increaseRefresh()`
2. INSERT ng_diplomacy with:
   - src_nation_id, dest_nation_id, prev_no (nullable)
   - state = 'proposed'
   - text_brief (trimmed), text_detail
   - date = now
   - src_signer = me.no, dest_signer = null
   - aux = JSON: `{ src: {...nation/general info...}, dest: {...nation info...} }`
3. Get inserted ng_diplomacy.id → newLetterNo
4. Send MESSAGE (diplomacy type):
   - src = acting general's nation
   - dest = recipient nation
   - text = "새로운 외교 문서 #{newLetterNo}이(가) 준비되었습니다. 외교부에서 확인해주세요." (with JosaUtil.pick for 이/가)
   - If prevNo exists: "문서 #{prevNo}의 새로운 외교 문서 #{newLetterNo}이(가) 준비되었습니다."

**Deny strings (exact Korean):**
- "접속 제한입니다."
- "자국으로 보낼 수 없습니다."
- "올바르지 않은 입력입니다."
- "요약문이 비어있습니다"
- "권한이 부족합니다. 수뇌부가 아닙니다."
- "올바르지 않은 국가입니다."
- "이전 문서가 없습니다."
- "해당 문서에 대한 새로운 문서가 이미 있습니다."

### 2. diploRollbackLetter (j_diplomacy_rollback_letter.php)

**Gates & guards:**
1. Access limit check
2. letterNo present (int)
3. Permission >= 4
4. Letter exists: `SELECT * FROM ng_diplomacy WHERE no = letterNo AND src_nation_id = me.nation AND state = 'proposed'`

**Side effects:**
1. Increment refresh_score ("외교부", 1)
2. UPDATE ng_diplomacy SET state = 'cancelled', aux = (add reason: `{ who, action: 'cancelled', reason: '회수' }`)
3. Send MESSAGE (diplomacy type):
   - text = "외교 서신(#{letterNo})이 회수되었습니다."

**Deny strings:**
- "접속 제한입니다."
- "올바르지 않은 입력입니다."
- "권한이 부족합니다. 수뇌부가 아닙니다."
- "서신이 없습니다."

### 3. diploDestroyLetter (j_diplomacy_destroy_letter.php)

**Gates & guards:**
1. Access limit check
2. letterNo present (int)
3. Permission >= 4
4. Letter exists: `SELECT * FROM ng_diplomacy WHERE no = letterNo AND (src_nation_id = me.nation OR dest_nation_id = me.nation) AND state = 'activated'`
5. Check aux['state_opt']: if already 'try_destroy_src'/'try_destroy_dest' (matching current player's side), deny "이미 파기 신청을 했습니다."

**Logic:**
- If aux['state_opt'] in ['try_destroy_src', 'try_destroy_dest'] (second request — other party already requested):
  - **Destroy phase:** UPDATE state = 'cancelled', then cascade UPDATE prev_no chain (loop until no prev_no found)
  - Message: "외교 서신(#{letterNo})을 파기했습니다."
  - Result state = 'cancelled'
- Otherwise (first request):
  - Set aux['state_opt'] = 'try_destroy_src' (if src_nation_id) or 'try_destroy_dest' (if dest_nation_id)
  - Message: "외교 서신(#{letterNo})을 파기 요청합니다."
  - Result state = 'activated' (not yet destroyed, awaiting other party)

**⚠️ Open question:** j_diplomacy_destroy_letter.php lines 95–107 loop through prev_no chain and construct deleteAux but never write it. Dead code? Proposed behavior: only destroy the TOP letter until BOTH parties agree; leave chain intact unless each link is explicitly destroyed.

**Side effects:**
1. Increment refresh_score ("외교부", 1)
2. UPDATE ng_diplomacy SET aux = (add/update state_opt flag and/or reason), optionally state = 'cancelled'
3. Send MESSAGE (diplomacy type):
   - varies by destroy phase (see above)

**Deny strings:**
- "접속 제한입니다."
- "올바르지 않은 입력입니다."
- "권한이 부족합니다. 수뇌부가 아닙니다."
- "서신이 없습니다."
- "이미 파기 신청을 했습니다."

---

## ChangeRecorder Side Effects

### ng_diplomacy channel
Already exists in ChangeRecorder as `diplomacyUpdateDirty` (per existing code comment T0.4). Add new channels as needed:

**diploSendLetter:**
- Use **diplomacy INSERT** channel (NOT defined yet; parallel to `createdMessages` or dedicated `diplomacyInserts`):
  ```kotlin
  recorder.recordDiplomacyInsert(
      linkedMapOf(
          "src_nation_id" to srcNationId,
          "dest_nation_id" to destNationId,
          "prev_no" to prevLetterNo,
          "state" to "proposed",
          "text_brief" to textBrief,
          "text_detail" to textDetail,
          "date" to nowDateTime,
          "src_signer" to generalId,
          "dest_signer" to null,
          "aux" to Json.encode(auxMap),
      )
  )
  ```
  ChangeRecorder must return the **allocated letter ID** for the result (via allocator lambda, similar to messageIdAllocator).

**diploRollbackLetter / diploDestroyLetter:**
- Use existing **diplomacyUpdateDirty** channel:
  ```kotlin
  recorder.recordDiplomacyUpdate(
      fromNationId = srcNationId,
      toNationId = destNationId,
      columns = mapOf("state" to "cancelled", "aux" to newAuxJson),
  )
  ```

### message channel
Both send and rollback/destroy send MESSAGE rows (diplomacy type). Use existing **createdMessages** channel:
```kotlin
recorder.recordMessage(
    CreatedMessage(
        type = MessageType.DIPLOMACY,
        src = MessageTarget(...),
        dest = MessageTarget(...),
        text = msgText,
        date = now,
        validUntil = unlimited,
        options = mapOf("deletable" to false),
    )
)
```

---

## File Changes Summary

### 1. CommandWireMapper.kt
- **Change:** Add 3 codes to `intakeCodes` set; add 3 when branches in `toCommand()` method.
- **File:** `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt`
- **Lines affected:** ~43, ~104-227

### 2. TurnDaemonCommand.kt
- **Change:** Add 3 sealed data classes after existing variants.
- **File:** `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`
- **New classes:** DiploSendLetter, DiploRollbackLetter, DiploDestroyLetter
- **Lines affected:** end of file

### 3. TurnDaemonCommandResult.kt
- **Change:** Add 6 data classes (Ok/Fail pairs) + update `selectSerializer()` to route 3 new types.
- **File:** `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt`
- **New classes:** DiploSendLetterOk/Fail, DiploRollbackLetterOk/Fail, DiploDestroyLetterOk/Fail
- **Update selectSerializer:** add case statements for each type

### 4. TurnDaemonCommandDispatcher.kt
- **Change:** Add 3 when branches in `dispatch()` method, instantiate diplomacyLetter handler.
- **File:** `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonCommandDispatcher.kt`
- **New handler field:** `private val diplomacyLetter = DiplomacyLetterHandler(...)`
- **Lines affected:** ~58, ~104-137

### 5. DiplomacyLetterHandler.kt (NEW FILE)
- **Purpose:** Per-run handler for 3 intake commands.
- **File:** `app/game-engine/src/main/kotlin/opensamguk/engine/intake/DiplomacyLetterHandler.kt`
- **Size:** ~300 lines
- **Pattern:** Mirrors BoardHandler + VoteHandler (world + recorder read-only, no state mutations)
- **Methods:**
  - `handleSendLetter(command: TurnDaemonCommand.DiploSendLetter): TurnDaemonCommandResult`
  - `handleRollbackLetter(command: TurnDaemonCommand.DiploRollbackLetter): TurnDaemonCommandResult`
  - `handleDestroyLetter(command: TurnDaemonCommand.DiploDestroyLetter): TurnDaemonCommandResult`

### 6. ChangeRecorder.kt
- **Change:** Add `diplomacyInserts` channel and allocator for ng_diplomacy.id.
- **File:** `app/game-engine/src/main/kotlin/opensamguk/engine/turn/ChangeRecorder.kt`
- **Methods:** Add `recordDiplomacyInsert()`, expose via `diplomacyInserts()` getter.
- **Lines affected:** ~45-49 (allocator), ~110+ (channel definition)

---

## Test Plan

### Unit Tests (DiplomacyLetterHandlerTest.kt)

**sendLetter:**
- ✓ Valid send with no prevNo
- ✓ Valid send with prevNo pointing to an existing 'proposed' letter
- ✓ Deny: destNationId == self
- ✓ Deny: brief empty after trim
- ✓ Deny: detail empty (allowed, but PHP checks brief only)
- ✓ Deny: permission < 4
- ✓ Deny: prevNo exists but letter not found
- ✓ Deny: prevNo exists and newer letter exists on that prevNo
- ✓ Side effect: previous 'proposed' letter state→'replaced' with reason metadata
- ✓ Result includes allocated letterNo

**rollbackLetter:**
- ✓ Valid rollback of 'proposed' letter sent by self
- ✓ Deny: letterNo not found
- ✓ Deny: state not 'proposed'
- ✓ Deny: not src_nation_id == me.nation
- ✓ Deny: permission < 4
- ✓ Side effect: state→'cancelled' with reason metadata
- ✓ Message sent (diplomacy type)

**destroyLetter:**
- ✓ Valid first-request destroy (first party requests, aux['state_opt'] set)
- ✓ Valid second-request destroy (other party already requested, state→'cancelled')
- ✓ Deny: state not 'activated'
- ✓ Deny: already made a request (aux['state_opt'] matches caller's side)
- ✓ Deny: letterNo not found
- ✓ Deny: permission < 4
- ✓ Side effect: aux['state_opt'] set on first request
- ✓ Side effect: state→'cancelled' on second request
- ✓ Result state field reflects 'activated' vs 'cancelled'

### Integration Tests (DiplomacyLetterControllerIT.kt)

- ✓ POST /api/command/diploSendLetter → 202 Accepted, requestId echoed
- ✓ ChangeRecorder.diplomacyInserts() captures ng_diplomacy INSERT
- ✓ ChangeRecorder.createdMessages() captures message (diplomacy type)
- ✓ Generl.refresh_score incremented by 1 (외교부 channel)
- ✓ POST /api/command/diploRollbackLetter → diplomacyUpdateDirty captures UPDATE
- ✓ POST /api/command/diploDestroyLetter (both phases) → state transitions

### Parity Tests (CaptureW5dDiplomacy.php → DiplomacyLetterParityTest.kt)

**PHP capture golden:**
```php
// legacy/devsam-core/hwe/test/capture_diplomacy.php
// Captures: sendLetter, rollbackLetter, destroyLetter payloads
// Outputs: PINNED ChangeRecorder state (diplomacyInserts, diplomacyUpdateDirty, createdMessages)
```

**Kotlin parity test:**
- Draw-for-draw refresh_score increment (외교부 channel)
- Message text byte-parity (JosaUtil.pick 이/가, text format)
- ng_diplomacy.aux JSON structure and key order (src, dest, reason, state_opt)
- State transitions: proposed → activated → cancelled, replaced
- prev_no chain logic (if clarified)

### Sanity Checks

- No RNG draws (deterministic=true) ✓
- ChangeRecorder is sole dirty source ✓
- Message allocation matches flushed SERIAL ✓
- Permission gates match PHP checkSecretPermission ✓

---

## Open Questions & Risks

### 1. prev_no chain destroy logic
**PHP code (j_diplomacy_destroy_letter.php:95-107):**
```php
while(true){
    $deleteLetter = $db->queryFirstRow('SELECT prev_no, aux FROM ng_diplomacy WHERE no = %i AND state = \'replaced\'', $letterNo);
    if(!$deleteLetter) break;
    $deleteAux = Json::decode($deleteLetter['aux']);
    $deleteLetterNo = $deleteLetter['prev_no'];
    $deleteAux['reason'] = [...]; // constructed but never written back
}
```
Dead code or intended? **Proposal:** Do NOT cascade delete until BOTH parties explicitly agree to destroy each link. This prevents silent loss of the entire letter chain.

### 2. Message ID in result
**j_diplomacy_send_letter.php:197** returns `$db->insertId()` (the letterNo). Should Kotlin `DiploSendLetterOk` include `letterNo` for FE telemetry? **Proposal:** Yes, like `PlaceBetOk` includes `bettingId`.

### 3. Josa (조사) morphology
Message text uses `JosaUtil::pick($newLetterNo, '이')` to pick 이/가 suffix. PHP determines suffix by final consonant (홀수/짝수 Hangeul ordinal). Verify Kotlin `KoreanJosaUtil` byte-matches PHP.

### 4. aux JSON key order
ng_diplomacy.aux is JSON with deeply nested structure (src, dest, reason, state_opt). LinkedHashMap insertion order must be preserved to byte-match PHP golden. Manual verification required.

### 5. General.refresh_score channel
diploSendLetter/rollbackLetter/destroyLetter all increment refresh_score via `increaseRefresh("외교부", 1)`. ChangeRecorder must expose this as KV delta or general column patch. Clarify whether KV or general.meta.

---

## Revision History

| Date | Revision | Notes |
|------|----------|-------|
| 2026-06-06 | 1.0 | Initial spec (W5d) from PHP golden `j_diplomacy_{send,rollback,destroy}_letter.php` |

