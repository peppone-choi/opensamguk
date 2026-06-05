# W6a: Messaging send/delete/contacts (SendMessage, DeleteMessage, GetContactList)

## Overview
Port PHP messaging intake (`SendMessage`/`DeleteMessage`/`GetContactList`) to Kotlin/Spring+Next.js. Messages are routed by mailbox (public/national/diplomacy/private) with distinct sender/receiver copies and a 5-minute deletion window. The `GetContactList` endpoint returns all playable generals organized by nation.

**Grand truth:** `legacy/devsam-core/hwe/sammo/API/Message/*.php` (SendMessage.php:24-262, DeleteMessage.php:11-38, GetContactList.php:11-35)  
**Messaging engine:** `logic/src/main/kotlin/opensamguk/logic/messaging/` (SendMessage.kt function exists; extend for intake)

---

## PHP Source Analysis

### 1. SendMessage.php::launch (lines 152-261)

**Arguments:**
- `mailbox: int` — routing target: 9999 (public), >=9000 (national as 9000+nationId), <9000 (private as generalId)
- `text: string` — message body (required, length ≥ 1)

**Validation & Gates (order matters):**
1. `blockLevel() == 1 || 3` → "차단되었습니다." (block denial)
2. `SELECT … FROM general WHERE owner=userId` → "장수가 없습니다." (session's general exists)
3. `checkLimit(refresh_score) >= 2` → "접속 제한입니다." (rate limit)
4. Public: `penalty[NoSendPublicMsg]` → "공개 메세지를 보낼 수 없습니다."
5. Private: `penalty[NoSendPrivateMsg]` → "개인 메세지를 보낼 수 없습니다."
6. Private throttle: `now - lastMsg < penalty[SendPrivateMsgDelay] ?? 2` → "개인메세지는 {delay}초당 1건만 보낼 수 있습니다!"
7. Dest general exists (private): `SELECT … FROM general WHERE no=destGeneralID` → "존재하지 않는 유저입니다."
8. Dest permission check (private, diplomacy): if dest is diplomat (permission==4) AND src != diplomat → "외교권자끼리는 메시지를 보낼 수 없습니다."
9. Dest nation exists → "존재하지 않는 국가입니다."

**Message Construction (genPublicMessage / genNationalMessage / genDiplomacyMessage / genPrivateMessage):**
- All: `now = new DateTime()`; `validUntil = '9999-12-31'`
- Public: src→public, dest→public
- National: dest nationId from src; if different nation → diplomacy type
- Diplomacy: lookup dest nation by nationId
- Private: lookup dest general by id; load dest picture/nation

**Send Logic (Message::send, lines 441-464):**
1. `sendToReceiver()` — always INSERT receiver message (mailbox = receiver's mailbox, isInboxMail=true)
2. `sendToSender()` — conditional sender copy:
   - Private (diff users): INSERT sender copy to src.generalID mailbox, isInboxMail=false
   - National (diff nations): INSERT sender copy to src.nationID+9000 mailbox
   - Diplomacy: INSERT sender copy to src.nationID+9000 (option['action'] stripped)
   - Public: no sender copy (return [0,0])
3. Receiver notification (private/diplomacy): UPDATE general SET newmsg=1 (if not silent)
4. Return msgID = receiverMessageID (always the receiver's ID)

**Log:**
- `increaseRefresh('서신전달', 1)` — single token "서신전달" (no per-message details; not golden-captured)

**Result:**
```json
{
  "msgType": "public|national|diplomacy|private",
  "msgID": int
}
```

---

### 2. DeleteMessage.php::launch (lines 31-37)

**Arguments:**
- `msgID: int` — message ID to delete

**Business Logic (Message::deleteMsg, lines 225-282):**
1. Load message by ID: `SELECT * FROM message WHERE id=msgID AND valid_until > now`
2. Gate: message exists → "메시지가 없습니다"
3. Gate: sender == currentUser → "본인의 메시지만 삭제할 수 있습니다."
4. Gate: not DiplomaticMessage → "시스템 외교 메시지는 삭제할 수 없습니다."
5. Gate: message.time >= now-5min → "5분 이내의 메시지만 삭제할 수 없습니다."
6. Gate: msgOption['deletable'] ?? true → "삭제할 수 없는 메시지입니다."

**Deletion (invalidate + reciprocal):**
- Mark sender's copy: `invalidate(hideMsg=false)` → set text='삭제된 메시지입니다.', set valid_until='2000-12-31', set option['invalid']=true
- Load receiver copy via option['receiverMessageID']: `invalidate(hideMsg=false)` on receiver too
- Create a system "req_del_msg" message with option['overwrite']=[sender_id, receiver_id] and valid_until=now+1min
- INSERT the req_del_msg message (mailbox routing = sender's mailbox)

**Result:**
- `null` on success (PHP convention: falsy return = success)
- `string` error message on failure

---

### 3. GetContactList.php::launch (lines 22-35)

**Arguments:** none

**Logic (func.php::getMailboxList, lines 4-52):**
1. SELECT `no, name, nation, officer_level, npc, permission, penalty FROM general WHERE npc<2` (all playable generals)
2. For each general: compute flags (bitmask):
   - Bit 1: officer_level == 12 (lord)
   - Bit 2: npc == 1 (NPC)
   - Bit 4: permission == 4 (diplomat/ambassador)
3. Group by nation; map nations with mailbox = nationId + 9000
4. For each nation: list generals as [id, name, flags]

**Result:**
```json
{
  "nation": [
    {
      "mailbox": int (nationId+9000),
      "name": "kingdom name",
      "color": "#hexcolor",
      "general": [[id, name, flags], ...]
    }
  ]
}
```

---

## Kotlin Intake Pattern

### Wire Variants (TurnDaemonCommand)

```kotlin
@Serializable
@SerialName("sendMessage")
data class SendMessage(
    val requestId: String? = null,
    val generalId: Int,
    val mailbox: Int,
    val text: String,
) : TurnDaemonCommand() {
    override val type: String get() = "sendMessage"
}

@Serializable
@SerialName("deleteMessage")
data class DeleteMessage(
    val requestId: String? = null,
    val generalId: Int,
    val msgID: Int,
) : TurnDaemonCommand() {
    override val type: String get() = "deleteMessage"
}
```

### CommandWireMapper.toCommand (additions)

```kotlin
intakeCodes: add "sendMessage", "deleteMessage"

toCommand(code, generalId, requestId, argJson):
  "sendMessage" -> TurnDaemonCommand.SendMessage(
    requestId = requestId,
    generalId = generalId,
    mailbox = args.int("mailbox") ?: 9999,  // PHP default: Message::MAILBOX_PUBLIC
    text = args.str("text") ?: "",
  )
  "deleteMessage" -> TurnDaemonCommand.DeleteMessage(
    requestId = requestId,
    generalId = generalId,
    msgID = args.int("msgID") ?: args.int("msgId") ?: 0,
  )
```

### REST Endpoints

1. **POST /api/command/sendMessage**
   - Body: `{mailbox: int, text: string}`
   - Controller resolves `generalId` from session
   - Maps to `TurnDaemonCommand.SendMessage` via mapper
   - Returns: `{msgType: string, msgID: int}` or error string

2. **POST /api/command/deleteMessage**
   - Body: `{msgID: int}`
   - Controller resolves `generalId` from session
   - Maps to `TurnDaemonCommand.DeleteMessage`
   - Returns: `{ok: boolean, reason?: string}` (structured CommandActionResult)

3. **GET /api/contacts**
   - No parameters
   - Calls `ContactController.getContacts()`
   - Returns: `{nation: [{mailbox, name, color, general: [[id, name, flags]]}]}`
   - Reads live general rows via injected `GeneralRepository` reader seam (or stub for IT)

---

## Engine Handler (MessageHandler)

**Location:** `app/game-engine/src/main/kotlin/opensamguk/engine/intake/MessageHandler.kt`

**Per-run class pattern** (mirror VoteHandler/BoardHandler):
- Constructor: `world: InMemoryTurnWorld, recorder: ChangeRecorder`
- Reader seams:
  - `generalReader: (generalId: Int) -> GeneralReadRow?` (infra adapter; stub = always null)
  - `destGeneralReader: (destGeneralId: Int) -> GeneralReadRow?` (for dest validation)
  - `nationReader: (nationId: Int) -> NationReadRow?` (for dest validation)

**Methods:**

#### handleSendMessage(c: SendMessage): TurnDaemonCommandResult

**Gate order (PHP faithful):**
1. Me (src) general exists: `world.getGeneralById(generalId)` or reader → "장수가 없습니다."
2. Block check → "차단되었습니다." (stub: always false for IT; live checks env/penalty)
3. Rate limit → "접속 제한입니다." (stub: always false)
4. Mailbox type validation (public/national/diplomacy/private):
   - **Public (mailbox == 9999):**
     - Gate: penalty[NoSendPublicMsg] → "공개 메세지를 보낼 수 없습니다."
     - Construct public message (Message.msgType=public, src=me, dest=me)
     - Send (receiver only, no sender copy)
     - Return SendMessageOk(msgType="public", msgID=receiverId)
   - **National/Diplomacy (mailbox >= 9000):**
     - Resolve dest nation = mailbox - 9000 (or own nation if permission < 4)
     - If dest == own nation:
       - Construct national message (msgType=national, src=me, dest=destNation)
       - Send (receiver + sender)
       - Return SendMessageOk(msgType="national", msgID=…)
     - Else (cross-nation):
       - Gate: dest nation exists → "존재하지 않는 국가입니다."
       - Construct diplomacy message (msgType=diplomacy, src=me, dest=destNation)
       - Send (receiver + sender, action stripped from sender copy option)
       - Return SendMessageOk(msgType="diplomacy", msgID=…)
   - **Private (mailbox < 9000):**
     - Gate: penalty[NoSendPrivateMsg] → "개인 메세지를 보낼 수 없습니다."
     - Gate: throttle `now - lastMsg < penalty[SendPrivateMsgDelay] ?? 2` → error with dynamic delay (interpolate PhpRound if needed)
     - Gate: dest general exists → "존재하지 않는 유저입니다."
     - Gate: permission check (if both diplomat → "외교권자끼리는 메시지를 보낼 수 없습니다.")
     - Gate: dest nation exists → "존재하지 않는 국가입니다."
     - Construct private message (msgType=private, src=me, dest=destUser)
     - Send (receiver + sender)
     - UPDATE general SET newmsg=1 for receiver (if not silent)
     - Update session lastMsg = now
     - Return SendMessageOk(msgType="private", msgID=…)

5. Log "서신전달" (side effect, not in result)

6. Return on success or SendMessageFail (reason: string)

#### handleDeleteMessage(c: DeleteMessage): TurnDaemonCommandResult

1. Gate: message exists → "메시지가 없습니다"
2. Gate: sender == generalId → "본인의 메시지만 삭제할 수 있습니다."
3. Gate: not DiplomaticMessage → "시스템 외교 메시지는 삭제할 수 없습니다."
4. Gate: message.time >= now-5min → "5분 이내의 메시지만 삭제할 수 있습니다."
5. Gate: msgOption['deletable'] ?? true → "삭제할 수 없는 메시지입니다."

6. Invalidate sender copy:
   - `recorder.diffMessage(msgID, {invalid: true, text: '삭제된 메시지입니다.'})` (or UPDATE via ChangeRecorder message channel)
7. Invalidate receiver copy (if reciprocal):
   - Load receiverMessageID from option
   - `recorder.diffMessage(receiverMessageID, {...})`
8. Insert "req_del_msg" system message (short-lived 1-min marker with option['overwrite']=[sender_id, receiver_id])

9. Return DeleteMessageOk() or DeleteMessageFail(reason)

---

## Database Schema (V7)

**message table** (V7__p6_messaging_economy.sql:24-34):
```sql
CREATE TYPE message_type AS ENUM ('private', 'national', 'public', 'diplomacy');

CREATE TABLE message (
    id          serial PRIMARY KEY,
    mailbox     integer NOT NULL,                 -- 9999 == public, >= 9000 national
    type        message_type NOT NULL,
    src         integer NOT NULL,
    dest        integer NOT NULL,
    time        timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz NOT NULL DEFAULT '9999-12-31 23:59:59',
    message     jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX message_by_mailbox ON message (mailbox, type, id);
```

**message jsonb payload:**
```json
{
  "src": {"id": int, "name": str, "nation_id": int, "nation": str, "color": str, "icon": str},
  "dest": {"id": int, "name": str, "nation_id": int, "nation": str, "color": str, "icon": str},
  "text": "message body",
  "option": {
    "receiverMessageID": int,      // cross-link to receiver copy
    "senderMessageID": int,        // cross-link to sender copy
    "action": "...",               // for diplomacy (stripped from sender copy)
    "invalid": boolean,            // deletion marker
    "deletable": boolean,          // (optional) PHP msgOption['deletable'] ?? true
    "hide": boolean,               // deletion styling
    "silence": boolean,            // suppress newmsg flag
    "overwrite": [int, ...]        // deletion system message
  }
}
```

---

## Deny Strings (Byte-Exact Korean)

```
차단되었습니다.
장수가 없습니다.
접속 제한입니다.
공개 메세지를 보낼 수 없습니다.
개인 메세지를 보낼 수 없습니다.
개인메세지는 {msg_min_interval}초당 1건만 보낼 수 있습니다!
존재하지 않는 유저입니다.
존재하지 않는 국가입니다.
외교권자끼리는 메시지를 보낼 수 없습니다.
알 수 없는 에러입니다.

메시지가 없습니다
본인의 메시지만 삭제할 수 있습니다.
시스템 외교 메시지는 삭제할 수 없습니다.
5분 이내의 메시지만 삭제할 수 있습니다.
삭제할 수 없는 메시지입니다.
```

---

## Side Effects (Order Matters)

1. **SendMessage:**
   - message INSERT (receiver row, always)
   - message INSERT (sender row, conditional per type)
   - general UPDATE SET newmsg=1 (receiver, if private/diplomacy and not silent)
   - session.lastMsg UPDATE (private throttle tracking)

2. **DeleteMessage:**
   - message UPDATE (sender copy: invalid=true, text='삭제된 메시지입니다.', valid_until='2000-12-31')
   - message UPDATE (receiver copy: same)
   - message INSERT (req_del_msg system marker, valid_until=now+1min)

---

## Test Plan

### Unit Tests (CommandWireMapper)
- `toCommand("sendMessage", generalId, requestId, argJson)` with:
  - null/empty argJson
  - missing mailbox (default 9999)
  - invalid mailbox (out of range)
  - empty text (valid, no validation here; gate in handler)
  - integer/string coercion (lenient JSON)
- `toCommand("deleteMessage", generalId, requestId, argJson)` with:
  - null msgID (default 0)
  - negative msgID
  - coercion tests

### Integration Tests (MessageHandler)
- **SendMessage paths:**
  - Public: verify 1 message INSERT (receiver only, isInboxMail=true)
  - National (same-nation): verify 2 inserts (receiver + sender)
  - Diplomacy (cross-nation): verify 2 inserts + action stripped from sender option
  - Private: verify 2 inserts + newmsg=1 flag + throttle gate
  - All denial gates: verify error string matches byte-exact PHP
- **DeleteMessage paths:**
  - Sender check: only message creator can delete
  - 5-min window: verify boundary (exactly 5:00 allowed, 5:01 denied)
  - Reciprocal delete: load receiverMessageID, invalidate both
  - Diplomatic message block
  - Deletable option override

### E2E Tests (Controller → Handler → DB)
- Send private message: verify 2 rows in DB with cross-links (receiverMessageID/senderMessageID)
- Delete one: verify both rows updated (invalid=true, text changed)
- GetContacts: verify nations + generals array shape, flags bitmask

### Parity (PHP Golden)
- capture_message.php (if exists) — byte-exact "서신전달" log token
- Deny strings must match exactly (Korean encoding)
- Message payload structure (src/dest nested object) must match PHP Message.toArray()

---

## Implementation Notes

1. **Reader seams:** GeneralRepository (optional) for dest validation; stub = always-null for IT (gates disabled).
2. **Option['deletable']:** PHP default `true`; intake can override (advanced feature, currently always true).
3. **Penalty fields:** `NoSendPublicMsg`, `NoSendPrivateMsg`, `SendPrivateMsgDelay` live in general.penalty JSON (cached in session).
4. **Throttle interpolation:** If delay is dynamic (penalty key), format the error string with PhpRound if needed (likely not; compare as numeric).
5. **Log token:** "서신전달" is a single refresh token, not per-message detail (no golden capture needed).
6. **Deletion marker "req_del_msg":** System message visible only to editors; regular UI hides it. Text is the marker, not a displayable string.
7. **Block level / rate limit / refresh:** Likely gate-layer concerns (precheck, not daemon). Stub as false in handler; live deployment checks env/penalty.

---

## Files to Create/Edit

### New Files
- `app/game-engine/src/main/kotlin/opensamguk/engine/intake/MessageHandler.kt`
- `app/game-api/src/main/kotlin/opensamguk/gameapi/controller/ContactController.kt`

### Shared Edits
- `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt`
  - intakeCodes: add "sendMessage", "deleteMessage"
  - toCommand: add branches
- `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`
  - Add SendMessage, DeleteMessage variants
- `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonCommandDispatcher.kt`
  - Instantiate MessageHandler
  - Add routing in dispatch()

