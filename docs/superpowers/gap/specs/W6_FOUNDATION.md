# W6/W5 REST Mutation Batch — FOUNDATION-FIRST Implementation Plan

> **Consolidation synthesizer output.** Six per-slice specs (W6a messaging · W6c auction-open ·
> W5d diplomacy-letters · W6e command-queue · W6f select-pool · W6d general-join/build-nation)
> folded into ONE foundation pass + disjoint per-slice implementation. Mirror the EXISTING done
> slices verbatim (PlaceBet / Vote / Board / Troop / InheritReset). PHP `legacy/devsam-core` = grand
> truth; every Korean log string below is a parity target. **NEVER fabricate a golden** — RNG-bearing
> slices defer their gate to `/parity-wave`.

## Slice inventory & determinism

| Slice | Codes | Deterministic? | Gate |
| --- | --- | --- | --- |
| **W6a** Messaging | `sendMessage`, `deleteMessage` (+GET `/api/contacts`) | ✅ deterministic | unit/IT |
| **W6c** Auction-open | `auctionOpenBuyRice`, `auctionOpenSellRice`, `auctionOpenUnique` | ✅ deterministic | unit/IT |
| **W5d** Diplomacy-letters | `diploSendLetter`, `diploRollbackLetter`, `diploDestroyLetter` | ✅ deterministic (Josa parity) | unit/IT |
| **W6e** Command-queue | `/bulk` `/push` `/repeat` ×{general,nation} (REST-only, NO wire) | ✅ deterministic | unit/IT |
| **W6f** Select-pool | `selectPoolPick`, `selectPoolUpdate` (+GET `/api/generals/select-pool`) | 🔴 **RNG-bearing** | **golden → /parity-wave** |
| **W6d** Join / build-nation | `join` (REST), `buildNationCandidate` (wire EXISTS) | 🔴 **RNG-bearing** | **golden → /parity-wave** |

**4 deterministic** (W6a, W6c, W5d, W6e) → unit-gateable, land first.
**2 golden-bearing** (W6f, W6d) → foundation + handler skeleton land now; the draw-for-draw gate
is captured + closed under `/parity-wave` (8-draw join seed, weighted select-pool pick).

---

## 1. Shared-file foundation (ONE creator pass — applied BEFORE any slice)

> **Why foundation-first.** Four files are co-widened by ≥2 slices. If parallel slice worktrees each
> edit them they collide (merge conflict; the CLAUDE.md "never co-widen the same file" rule). The
> creator builds ALL wire variants + result classes + mapper cases + dispatcher branches + **stub
> handler classes** in one commit so everything compiles, then each slice fills in only its own
> handler body + controller + tests in a disjoint file set.

### 1.0 Already present — DO NOT re-add (dedupe)

These are in the tree today; the foundation MUST NOT duplicate them:

- `TurnDaemonCommand.BuildNationCandidate(requestId, generalId)` — **EXISTS** (`TurnDaemonCommand.kt:219-226`). W6d build-nation reuses it verbatim; only wires a dispatcher branch + handler (see §1.4/§2-W6d).
- `BuyHiddenBuff` / `BuyRandomUnique` codes + variants + results — EXIST (inheritance). Not in scope.
- Board / Vote / Troop / nation-finance / inherit-reset intake — EXIST. Pattern templates only.

### 1.1 `common/.../wire/TurnDaemonCommand.kt` — new wire variants (deduped)

Append after the existing variants (the `buyRandomUnique` block, before the closing `}` at line 632).
All carry `requestId: String? = null` first, then `generalId: Int` (acting general, controller-resolved).

```kotlin
// ── W6a 메시지 인테이크 (SendMessage / DeleteMessage) — j_send_message.php / j_delete_message.php ──
@Serializable
@SerialName("sendMessage")
data class SendMessage(
    val requestId: String? = null,
    val generalId: Int,
    // 9999=공개, >=9000=국가(9000+nationId), <9000=개인(상대 generalId). PHP getPost('mailbox','int').
    val mailbox: Int,
    val text: String,
) : TurnDaemonCommand() { override val type: String get() = "sendMessage" }

@Serializable
@SerialName("deleteMessage")
data class DeleteMessage(
    val requestId: String? = null,
    val generalId: Int,
    val msgID: Int,
) : TurnDaemonCommand() { override val type: String get() = "deleteMessage" }

// ── W6c 경매 개설 (BuyRice / SellRice / Unique) — OpenBuyRiceAuction.php 등 ──
@Serializable
@SerialName("auctionOpenBuyRice")
data class AuctionOpenBuyRice(
    val requestId: String? = null,
    val generalId: Int,
    val amount: Int,
    val closeTurnCnt: Int,
    val startBidAmount: Int,
    val finishBidAmount: Int,
) : TurnDaemonCommand() { override val type: String get() = "auctionOpenBuyRice" }

@Serializable
@SerialName("auctionOpenSellRice")
data class AuctionOpenSellRice(
    val requestId: String? = null,
    val generalId: Int,
    val amount: Int,
    val closeTurnCnt: Int,
    val startBidAmount: Int,
    val finishBidAmount: Int,
) : TurnDaemonCommand() { override val type: String get() = "auctionOpenSellRice" }

@Serializable
@SerialName("auctionOpenUnique")
data class AuctionOpenUnique(
    val requestId: String? = null,
    val generalId: Int,
    val itemId: String,
    val amount: Int,
) : TurnDaemonCommand() { override val type: String get() = "auctionOpenUnique" }

// ── W5d 외교 서신 (Send / Rollback / Destroy) — j_diplomacy_*_letter.php ──
@Serializable
@SerialName("diploSendLetter")
data class DiploSendLetter(
    val requestId: String? = null,
    val generalId: Int,
    val destNationId: Int,
    // PHP `?? null`, <1 → null. 직전 문서 번호(체인). nullable 유지.
    val prevLetterNo: Int? = null,
    val textBrief: String,
    val textDetail: String,
) : TurnDaemonCommand() { override val type: String get() = "diploSendLetter" }

@Serializable
@SerialName("diploRollbackLetter")
data class DiploRollbackLetter(
    val requestId: String? = null,
    val generalId: Int,
    val letterNo: Int,
) : TurnDaemonCommand() { override val type: String get() = "diploRollbackLetter" }

@Serializable
@SerialName("diploDestroyLetter")
data class DiploDestroyLetter(
    val requestId: String? = null,
    val generalId: Int,
    val letterNo: Int,
) : TurnDaemonCommand() { override val type: String get() = "diploDestroyLetter" }

// ── W6f 장수 선택 풀 pick/update — j_pick_general.php / j_update_picked_general.php (RNG-BEARING) ──
@Serializable
@SerialName("selectPoolPick")
data class SelectPoolPick(
    val requestId: String? = null,
    val generalId: Int,
    val uniqueName: String,
    // 선택 스탯(풀 항목이 stat-editable일 때만 유효). 부재 시 null → 엔진이 풀 기본값 사용.
    val leadership: Int? = null,
    val strength: Int? = null,
    val intel: Int? = null,
    // 'Random' 또는 유효 성격명. 부재 시 null.
    val personalityName: String? = null,
    val useOwnPicture: Boolean = false,
) : TurnDaemonCommand() { override val type: String get() = "selectPoolPick" }

@Serializable
@SerialName("selectPoolUpdate")
data class SelectPoolUpdate(
    val requestId: String? = null,
    val generalId: Int,
    val uniqueName: String,
    val leadership: Int? = null,
    val strength: Int? = null,
    val intel: Int? = null,
    val personalityName: String? = null,
    val useOwnPicture: Boolean = false,
) : TurnDaemonCommand() { override val type: String get() = "selectPoolUpdate" }
```

**W6d note:** `join` does **NOT** get a wire variant — it is a REST-only general-create that runs in
the game-api process (RNG-seeded pure logic + JDBC insert via the seed/boot seam, NOT the daemon
write path; mirrors `engine.boot.ScenarioSeedRunner`). `buildNationCandidate` already has its wire
variant; only the dispatcher branch + handler are added.

### 1.2 `common/.../wire/TurnDaemonCommandResult.kt` — new result classes + selector

Add these classes (after the existing inherit-reset results, before the private `*_TYPES` sets at
line 448). Mirror the collapse pattern where shapes are identical:

```kotlin
// W6a — 메시지 (공개/국가/외교/개인 공통 shape). msgType + msgID echo, reason on fail.
@Serializable
data class SendMessageResult(
    override val type: String = "sendMessage",
    override val ok: Boolean,
    val generalId: Int,
    val msgType: String? = null,   // public|national|diplomacy|private
    val msgID: Int? = null,
    val reason: String? = null,
) : TurnDaemonCommandResult()

@Serializable
data class DeleteMessageResult(
    override val type: String = "deleteMessage",
    override val ok: Boolean,
    val generalId: Int,
    val msgID: Int? = null,
    val reason: String? = null,
) : TurnDaemonCommandResult()

// W6c — 경매 개설 (3 코드 collapse, mirrors NationSettingResult). auctionId echo on success.
@Serializable
data class AuctionOpenResult(
    override val type: String,     // auctionOpenBuyRice|auctionOpenSellRice|auctionOpenUnique
    override val ok: Boolean,
    val generalId: Int,
    val auctionId: Int? = null,
    val reason: String? = null,
) : TurnDaemonCommandResult()

// W5d — 외교 서신 (3 코드 collapse). letterNo echo on success.
@Serializable
data class DiploLetterResult(
    override val type: String,     // diploSendLetter|diploRollbackLetter|diploDestroyLetter
    override val ok: Boolean,
    val generalId: Int,
    val letterNo: Int? = null,
    val reason: String? = null,
) : TurnDaemonCommandResult()

// W6f — 장수 선택 풀 (pick/update collapse). 성공 시 생성/갱신된 generalId echo.
@Serializable
data class SelectPoolActionResult(
    override val type: String,     // selectPoolPick|selectPoolUpdate
    override val ok: Boolean,
    val generalId: Int,
    val reason: String? = null,
) : TurnDaemonCommandResult()

// W6d — build-nation candidate. 성공 시 nationId echo (reuses generalId envelope).
@Serializable
data class BuildNationCandidateResult(
    override val type: String = "buildNationCandidate",
    override val ok: Boolean,
    val generalId: Int,
    val nationId: Int? = null,
    val reason: String? = null,
) : TurnDaemonCommandResult()
```

> **W6d build-nation caveat.** `buildNationCandidate` is currently routed through the
> `BOOLEAN_OK_TYPES` collapse → `GeneralBoolResult`. To echo `nationId`, REMOVE `buildNationCandidate`
> from `BOOLEAN_OK_TYPES` (line 453-456) and route it to `BuildNationCandidateResult` in the selector.
> If the FE does not need `nationId`, keep it in `GeneralBoolResult` and SKIP `BuildNationCandidateResult`
> (simpler; recommended unless the spec's "202 + nationId" is load-bearing). **Open question Q-D1.**

**Selector edits** in `selectSerializer` (lines 477-514). Add four collapsed `*_TYPES` sets next to the
existing ones (line 448-462) and four early-returns, plus the W6d conditional:

```kotlin
private val AUCTION_OPEN_TYPES = setOf("auctionOpenBuyRice", "auctionOpenSellRice", "auctionOpenUnique")
private val DIPLO_LETTER_TYPES = setOf("diploSendLetter", "diploRollbackLetter", "diploDestroyLetter")
private val SELECT_POOL_TYPES = setOf("selectPoolPick", "selectPoolUpdate")
// (sendMessage/deleteMessage/buildNationCandidate are single-type → handled in the when below)
```
```kotlin
// inside selectSerializer, before the final `when`:
if (type in AUCTION_OPEN_TYPES) return AuctionOpenResult.serializer()
if (type in DIPLO_LETTER_TYPES) return DiploLetterResult.serializer()
if (type in SELECT_POOL_TYPES) return SelectPoolActionResult.serializer()
// inside the `when (type)`:
"sendMessage" -> SendMessageResult.serializer()
"deleteMessage" -> DeleteMessageResult.serializer()
"buildNationCandidate" -> BuildNationCandidateResult.serializer()   // ONLY if removed from BOOLEAN_OK_TYPES (Q-D1)
```

(`SendMessageResult` / `DeleteMessageResult` / `*Result` carry `ok` as a non-default field, so the
collapsed-shape classes need no `if (ok)` branch — selector keys on `type` only, identical to
`NationSettingResult`/`BoardActionResult`/`TroopActionResult`.)

### 1.3 `app/game-api/.../reserve/CommandWireMapper.kt` — intakeCodes + toCommand

**`intakeCodes` set** (lines 43-74) — append:

```kotlin
// W6a 메시지
"sendMessage", "deleteMessage",
// W6c 경매 개설
"auctionOpenBuyRice", "auctionOpenSellRice", "auctionOpenUnique",
// W5d 외교 서신
"diploSendLetter", "diploRollbackLetter", "diploDestroyLetter",
// W6f 장수 선택 풀 (RNG-bearing — 골든은 /parity-wave)
"selectPoolPick", "selectPoolUpdate",
```

> **NOT in intakeCodes:** `join` (REST-only, no daemon command), `/bulk` `/push` `/repeat` (W6e —
> REST-only, served by `CommandQueueService`, never reach the mapper), `buildNationCandidate` (it
> already has a wire variant but is published from a controller directly, like the other
> single-actor intakes — add it ONLY IF a `/api/command/buildNationCandidate` code path is desired;
> the spec routes it through `POST /api/nation/build-candidate` in `NationController`, see §2-W6d).

**`toCommand` when** (lines 107-227) — append cases:

```kotlin
"sendMessage" -> TurnDaemonCommand.SendMessage(
    requestId = requestId, generalId = generalId,
    mailbox = args.int("mailbox") ?: 0,
    text = args.str("text") ?: "",
)
"deleteMessage" -> TurnDaemonCommand.DeleteMessage(
    requestId = requestId, generalId = generalId,
    msgID = args.int("msgID") ?: args.int("msgId") ?: 0,
)
"auctionOpenBuyRice" -> TurnDaemonCommand.AuctionOpenBuyRice(
    requestId = requestId, generalId = generalId,
    amount = args.int("amount") ?: 0,
    closeTurnCnt = args.int("closeTurnCnt") ?: 0,
    startBidAmount = args.int("startBidAmount") ?: 0,
    finishBidAmount = args.int("finishBidAmount") ?: 0,
)
"auctionOpenSellRice" -> TurnDaemonCommand.AuctionOpenSellRice(
    requestId = requestId, generalId = generalId,
    amount = args.int("amount") ?: 0,
    closeTurnCnt = args.int("closeTurnCnt") ?: 0,
    startBidAmount = args.int("startBidAmount") ?: 0,
    finishBidAmount = args.int("finishBidAmount") ?: 0,
)
"auctionOpenUnique" -> TurnDaemonCommand.AuctionOpenUnique(
    requestId = requestId, generalId = generalId,
    itemId = args.str("itemId") ?: args.str("itemKey") ?: "",
    amount = args.int("amount") ?: 0,
)
"diploSendLetter" -> TurnDaemonCommand.DiploSendLetter(
    requestId = requestId, generalId = generalId,
    destNationId = args.int("destNation") ?: args.int("destNationId") ?: 0,
    // <1 → null (PHP). nullable 유지로 엔진이 '이전 문서 없음' 게이트를 태운다.
    prevLetterNo = args.int("prevNo")?.takeIf { it >= 1 } ?: args.int("prevLetterNo")?.takeIf { it >= 1 },
    textBrief = args.str("brief") ?: args.str("textBrief") ?: "",
    textDetail = args.str("detail") ?: args.str("textDetail") ?: "",
)
"diploRollbackLetter" -> TurnDaemonCommand.DiploRollbackLetter(
    requestId = requestId, generalId = generalId,
    letterNo = args.int("letterNo") ?: 0,
)
"diploDestroyLetter" -> TurnDaemonCommand.DiploDestroyLetter(
    requestId = requestId, generalId = generalId,
    letterNo = args.int("letterNo") ?: 0,
)
"selectPoolPick" -> TurnDaemonCommand.SelectPoolPick(
    requestId = requestId, generalId = generalId,
    uniqueName = args.str("uniqueName") ?: "",
    leadership = args.int("leadership"), strength = args.int("strength"), intel = args.int("intel"),
    personalityName = args.str("personalityName"),
    useOwnPicture = args.bool("useOwnPicture") ?: false,
)
"selectPoolUpdate" -> TurnDaemonCommand.SelectPoolUpdate(
    requestId = requestId, generalId = generalId,
    uniqueName = args.str("uniqueName") ?: "",
    leadership = args.int("leadership"), strength = args.int("strength"), intel = args.int("intel"),
    personalityName = args.str("personalityName"),
    useOwnPicture = args.bool("useOwnPicture") ?: false,
)
```

(All helpers — `args.int/str/bool/intList/strList` — already exist; no helper edits.)

### 1.4 `app/game-engine/.../run/TurnDaemonCommandDispatcher.kt` — handler fields + branches

**Constructor / imports** — add the new handler imports + the read-repo seams the handlers need.
**Field decls** (after line 96, the `vote` field) — instantiate the new per-run handlers (plain
classes, world+recorder, mirror `troop`/`board`):

```kotlin
// ── W6a 메시지 인테이크 핸들러 ──
private val message = MessageHandler(world, recorder, /* generalReader */ contactReader)
// ── W6c 경매 개설 핸들러 ──
private val auctionOpen = AuctionOpenHandler(world, recorder, auctionRepository, auctionBidRepository)
// ── W5d 외교 서신 핸들러 ──
private val diplomacyLetter = DiplomacyLetterHandler(world, recorder, diplomacyRepository)
// ── W6f 장수 선택 풀 핸들러 (RNG-bearing — 골든 게이트는 /parity-wave) ──
private val selectPool = SelectPoolHandler(world, recorder, selectPoolRepository)
// ── W6d 건국 후보(거병) 핸들러 ──
private val buildNation = BuildNationCandidateHandler(world, recorder)
```

> Add `diplomacyRepository: DiplomacyRepository? = null`, `selectPoolRepository: SelectPoolRepository? = null`,
> and a `contactReader`/general-read seam as nullable ctor params, mirroring the `votePollRepository?`
> injection (handlers fall back to a stub-empty reader when null, so IT without DB still compiles &
> runs). These infra read repos must exist OR be added (see "stub classes" below).

**`dispatch()` when** (lines 104-136) — add branches before `else -> null`:

```kotlin
is TurnDaemonCommand.SendMessage -> message.handleSend(command)
is TurnDaemonCommand.DeleteMessage -> message.handleDelete(command)
is TurnDaemonCommand.AuctionOpenBuyRice -> auctionOpen.handleBuyRice(command)
is TurnDaemonCommand.AuctionOpenSellRice -> auctionOpen.handleSellRice(command)
is TurnDaemonCommand.AuctionOpenUnique -> auctionOpen.handleUnique(command)
is TurnDaemonCommand.DiploSendLetter -> diplomacyLetter.handleSend(command)
is TurnDaemonCommand.DiploRollbackLetter -> diplomacyLetter.handleRollback(command)
is TurnDaemonCommand.DiploDestroyLetter -> diplomacyLetter.handleDestroy(command)
is TurnDaemonCommand.SelectPoolPick -> selectPool.handlePick(command)
is TurnDaemonCommand.SelectPoolUpdate -> selectPool.handleUpdate(command)
is TurnDaemonCommand.BuildNationCandidate -> buildNation.handle(command)
```

### 1.5 Stub handler classes the dispatcher references (so foundation COMPILES)

The foundation commit creates each handler as a compiling **skeleton** (signatures + a deny-only
default body returning `*Fail`/`*Result(ok=false, reason="미구현")`), so the dispatcher type-checks.
Each slice then fills its real body in a disjoint commit. Skeletons to create:

| Handler (new file) | Methods (stub returns) | Result type |
| --- | --- | --- |
| `engine/intake/MessageHandler.kt` | `handleSend`, `handleDelete` | `SendMessageResult`/`DeleteMessageResult` |
| `engine/auction/AuctionOpenHandler.kt` | `handleBuyRice`, `handleSellRice`, `handleUnique` | `AuctionOpenResult` |
| `engine/intake/DiplomacyLetterHandler.kt` | `handleSend`, `handleRollback`, `handleDestroy` | `DiploLetterResult` |
| `engine/intake/SelectPoolHandler.kt` | `handlePick`, `handleUpdate` | `SelectPoolActionResult` |
| `engine/intake/BuildNationCandidateHandler.kt` | `handle` | `BuildNationCandidateResult` or `GeneralBoolResult` (Q-D1) |

Infra read-seam stubs (nullable-injected, mirror `VotePollRepository`): `DiplomacyRepository`
(findLetter / findLetterChain), `SelectPoolRepository` (findPoolEntry / listForUser), a contact/general
reader for `MessageHandler`. Add interfaces under `infra/.../read/` + a `*ReadRow` DTO each (engine↔infra
cycle avoidance, exactly as `VotePollReadRow` → `VotePollState`).

**Foundation commit DONE-criteria:** `:common:test :app:game-engine:compileKotlin` green; serializer
round-trips all new result classes (add to the existing `TurnDaemonCommandResultSerializerTest`).

---

## 2. Per-slice DISJOINT implementation (parallel-safe AFTER foundation)

After the foundation commit lands, the slices below touch **disjoint** files (own handler body + own
controller + own tests). No two slices co-widen a shared file → safe to fan out to parallel worktrees.

### W6a — Messaging (DETERMINISTIC · unit-gateable)

- **Handler body:** `app/game-engine/.../engine/intake/MessageHandler.kt` (port `j_send_message.php` +
  `j_delete_message.php` + `Message`/`MessageTarget`). Side effects via `ChangeRecorder`:
  - `message` INSERT (receiver row always; sender row conditionally — public ⇒ NO sender row,
    `sendToSender()` returns `[0,0]`).
  - `general.newmsg = 1` UPDATE for private/diplomacy receivers (`world.updateGeneral`).
  - delete ⇒ message UPDATE invalidate + set text `삭제된 메시지입니다.` (runtime string, NOT a golden
    log — Q-A3), + reciprocal `receiverMessageID` invalidation.
  - mailbox routing: `9999`=public · `>=9000`=national(`9000+nationId`) · `<9000`=private(generalId).
- **Controllers (new/extend):**
  `app/game-api/.../controller/ContactController.kt` (GET `/api/contacts`) +
  `sendMessage`/`deleteMessage` flow through the EXISTING `CommandController` intake path (mapper →
  command stream), like `placeBet`. Contact list = nations + generals; general flags bitfield:
  `1`=lord(`officer_level==12`) · `2`=npc(`npc==1`) · `4`=diplomat(`permission==4`).
- **Exact log string (parity target):** `서신전달`
- **Exact deny strings (verbatim):**
  `차단되었습니다.` · `장수가 없습니다.` · `접속 제한입니다.` · `존재하지 않는 국가입니다.` ·
  `존재하지 않는 유저입니다.` · `외교권자끼리는 메시지를 보낼 수 없습니다.` ·
  `공개 메세지를 보낼 수 없습니다.` · `개인 메세지를 보낼 수 없습니다.` ·
  `개인메세지는 {msg_min_interval}초당 1건만 보낼 수 있습니다!` · `알 수 없는 에러입니다.` ·
  `메시지가 없습니다` · `본인의 메시지만 삭제할 수 있습니다.` ·
  `시스템 외교 메시지는 삭제할 수 없습니다.` · `5분 이내의 메시지만 삭제할 수 있습니다.` ·
  `삭제할 수 없는 메시지입니다.`
- **Tests:** `MessageHandlerTest` (send public/national/diplomacy/private + all deny gates;
  delete sender-check/5-min-window/reciprocal-invalidate), `ContactControllerIT`, mapper unit,
  serializer round-trip. **No RNG** — fully unit-gateable.

### W6c — Auction-open (DETERMINISTIC · unit-gateable)

- **Handler body:** `app/game-engine/.../engine/auction/AuctionOpenHandler.kt` (port
  `AuctionBasicResource::openResourceAuction` for BuyRice/SellRice, `AuctionUniqueItem` for Unique;
  mirror `AuctionBidHandler`). `ng_auction` INSERT via `ChangeRecorder`; resource deduction
  (rice/gold) AFTER `openAuction` succeeds (PHP line 89 ordering — **not before**); UniqueItem ⇒
  host's first bid placed immediately (buyAmount==startAmount) + inheritance-point record via the KV
  channel. Validation ORDER (PHP `openResourceAuction` lines 22-36): 3-month → closeTurnCnt(1-24) →
  amount(100-10000) → startBid(50-200%) → finishBid(110-200%) → finishBid≥startBid*1.1 → resource.
- **Controller:** flows through EXISTING `CommandController` intake (3 codes), like `auctionBid`.
- **Exact deny / log strings (verbatim):**
  `시작 후 3개월이 지나야 경매를 열 수 있습니다.` · `종료기한은 1 ~ 24 턴 이어야 합니다.` ·
  `거래량은 100 ~ 10000 이어야 합니다.` · `시작거래가는 50% ~ 200% 이어야 합니다.` ·
  `즉시거래가는 110% ~ 200% 이어야 합니다.` · `즉시거래가는 시작판매가의 110% 이상이어야 합니다.` ·
  `기본 쌀 {minimumRice}은 거래할 수 없습니다.` · `기본 금 {minimumGold}은 거래할 수 없습니다.` ·
  `아직 경매가 끝나지 않았습니다.` · `최소 경매 금액은 {inheritItemUniqueMinPoint}입니다.` ·
  `경매를 시작할 포인트가 부족합니다.` · `구매할 수 있는 아이템입니다.` · `이미 경매가 진행중입니다.` ·
  `이미 가진 아이템이 있습니다.` · `그 유니크를 더 얻을 수 없습니다.` ·
  `경매를 시작했지만, 첫 입찰에 실패했습니다: {failReason}`
- **Tests:** `AuctionOpenHandlerTest` (BuyRice/SellRice gates + resource mutation + detail-JSON
  shape; Unique gates + self-bid + inheritance record), mapper unit, serializer round-trip. **No RNG.**

### W5d — Diplomacy letters (DETERMINISTIC · Josa parity · unit-gateable)

- **Handler body:** `app/game-engine/.../engine/intake/DiplomacyLetterHandler.kt` (port
  `j_diplomacy_send_letter.php` / `_rollback_letter.php` / `_destroy_letter.php`; mirror
  `BoardHandler`+`VoteHandler`). Side effects via `ChangeRecorder`: `ng_diplomacy` INSERT (send) +
  state UPDATE (`proposed→replaced` on prevNo; `→cancelled` on rollback/destroy), `message` INSERT
  (diplomacy type, both parties), `general.refresh_score += 1` (외교부). `aux` jsonb INSERT order
  preserved (LinkedHashMap; `reason`/`src`/`dest`). Destroy is two-phase: `state_opt` =
  `try_destroy_src`/`try_destroy_dest`; → `cancelled` ONLY when BOTH set (do NOT cascade prev_no
  chain — Q-W5d1).
- **Controller:** flows through EXISTING `CommandController` intake (3 codes).
- **Exact log strings (verbatim, with Josa):**
  `외교부에서 서신을 발송했습니다.` · `외교 서신(#{letterNo})이 회수되었습니다.` ·
  `외교 서신(#{letterNo})을 파기했습니다.` · `외교 서신(#{letterNo})을 파기 요청합니다.` ·
  message body: `새로운 외교 문서 #{newLetterNo}이(가) 준비되었습니다. 외교부에서 확인해주세요.`
  (Josa `JosaUtil.pick(newLetterNo, '이')` → 이/가; use `logic.util.KoreanJosaUtil`).
- **Exact deny strings (verbatim):**
  `접속 제한입니다.` · `자국으로 보낼 수 없습니다.` · `올바르지 않은 입력입니다.` ·
  `요약문이 비어있습니다` · `권한이 부족합니다. 수뇌부가 아닙니다.` · `이전 문서가 없습니다.` ·
  `해당 문서에 대한 새로운 문서가 이미 있습니다.` · `올바르지 않은 국가입니다.` · `서신이 없습니다.` ·
  `이미 파기 신청을 했습니다.`
- **Tests:** `DiplomacyLetterHandlerTest` (send/rollback/destroy + all deny + prevNo replace +
  two-phase destroy), `DiplomaticLetterControllerIT`, Josa byte-parity, `aux` jsonb order test.
  **Deterministic** (no RNG draw — `refresh_score` increment is a count).

### W6e — Command queue (DETERMINISTIC · REST-only · NO wire)

- **No wire / no dispatcher / no handler.** This slice is REST-only and served IN the game-api
  process (general_turn/nation_turn ring writes), NOT the daemon command path. It does **NOT** touch
  any of the 4 shared foundation files.
- **New files:**
  `app/game-api/.../reserve/CommandQueueService.kt`,
  `app/game-api/.../reserve/CommandQueueTest.kt`.
- **Controller:** extend `CommandController` (`web/CommandController.kt`) with
  `@PostMapping("/bulk")` `/push` `/repeat` + nation twins (`/nation/bulk` `/nation/push`
  `/nation/repeat`). Ownership check (userId vs generalId via resolver); nation endpoints precheck
  `officer_level≥5` + `nation_id>0` in the API layer (TOCTOU: cache the fetched general/officer
  state, do not re-read in service).
- **Repository:** `ReservedTurnRepository` add `pushGeneralTurn`, `repeatGeneralTurn`, nation twins
  (`pullGeneralTurn`/`pullNationTurn` already exist). Ring: `MAX_GENERAL_TURNS=30`,
  `MAX_CHIEF_TURNS=12`.
- **Exact deny strings (verbatim, `{idx}`-prefixed variants for bulk per-row):**
  `턴이 입력되지 않았습니다` · `{idx}: 턴이 입력되지 않았습니다` · `사용할 수 없는 커맨드입니다.` ·
  `{idx}: 사용할 수 없는 커맨드입니다.` · `올바른 arg 형태가 아닙니다.` ·
  `{idx}: 올바른 arg 형태가 아닙니다.` · `0은 불가능합니다` · `올바르지 않은 장수입니다.` ·
  `국가에 소속되어 있지 않습니다.` · `수뇌가 아닙니다.`
- **Tests:** `CommandQueueTest` (bulk valid/invalid; push ±/zero/overflow; repeat bounds; nation
  prechecks), controller IT (202/200, 403 ownership, ring index via ChangeRecorder delta).
  **Deterministic** — fixtures pin initial ring → run op → byte-compare final ring.
- **Risk:** brief-builder must come from `:logic` per-action `getBrief()`; if absent, briefs are wrong.

### W6f — Select-pool pick/update (🔴 RNG-BEARING · golden → /parity-wave)

- **Handler body:** `app/game-engine/.../engine/intake/SelectPoolHandler.kt` (port `j_pick_general.php`
  / `j_update_picked_general.php`). RNG = weighted pick (`allStat^1.5`), `RandUtil(LiteHashDrbg)` —
  **draw-for-draw is a parity target → capture a real PHP golden, gate under `/parity-wave`.**
  Concurrency: mark-then-swap (`general_id = -generalID` then `= generalID`; affectedRows==0 ⇒ deny
  `동시성 제어에 문제가 발생했습니다. 버그 제보를 부탁드립니다.`). `next_change` cooldown in `general.aux`
  (`now + 12*turnterm`). Stat-sum strict `>` (not `>=`).
- **Controllers:** `app/game-api/.../controller/SelectPoolController.kt` (GET
  `/api/generals/select-pool` read; POST pick/update flow through `CommandController` intake) + DTOs
  `SelectPoolRequest.kt` / `SelectPoolResponse.kt`.
- **Exact log strings (verbatim, with color/Josa):**
  `<Y>{generalName}</>, <G>{cityName}</>에서 등장` ·
  `<G><b>{cityName}</b></>에서 <Y>{userName}</Y>{josaYi} <Y>{generalName}</Y>{josaRo} 등장합니다.` ·
  `장수를 <Y>{oldGeneralName}</>에서 <Y>{generalName}</Y>{josaRo} 변경` ·
  `<Y>{userName}</Y>{josaYi} 장수를 <Y>{oldGeneralName}</>에서 <Y>{generalName}</Y>{josaRo} 변경합니다.`
- **Exact deny strings (verbatim):**
  `선택 가능한 서버가 아닙니다` · `이미 다시 고를 수 없습니다` (NB: spec also lists
  `아직 다시 고를 수 없습니다` for the cooldown guard — confirm which against PHP, Q-F1) ·
  `유효한 장수 목록이 없습니다.` · `이미 장수를 생성했습니다.` · `더 이상 등록 할 수 없습니다.` ·
  `스탯의 총 합이 올바르지 않습니다.` · `올바르지 않은 성격입니다.` · `장수 등록에 실패했습니다.` ·
  `장수가 생성하지 않았습니다. 이미 사망하지 않았는지 확인해보세요.` ·
  `동시성 제어에 문제가 발생했습니다. 버그 제보를 부탁드립니다.` ·
  `장수 선택 과정에 문제가 발생했습니다.. 버그 제보를 부탁드립니다.` (note: double `.` is verbatim)
- **Tests:** unit deny gates (deterministic), **golden RNG parity (deferred to /parity-wave)**.

### W6d — Join / build-nation candidate (🔴 RNG-BEARING · golden → /parity-wave)

- **`join` = REST-only general-create** (NO wire variant). New:
  `app/game-api/.../controller/GeneralController.kt` (POST `/api/general/join`),
  `app/game-api/.../dto/CreateGeneralRequest.kt`,
  `logic/.../actions/intake/GeneralCreate.kt` (pure RNG: 8 draws in PHP order — genius% → city →
  stat-bonus(3-5 via `choiceUsingWeight`) → age → special2 → character → affinity(1-150) → turntime).
  Seed = `hiddenSeed + 'MakeGeneral' + userID + now(float)` → **LiteHashDrbg draw-for-draw golden,
  gate under /parity-wave.** Inserts: general, general_access_log, 12× general_turn(휴식),
  8× rank_data, inheritance_log (if required), game_env genius--, member_log.
- **`buildNationCandidate`** reuses the EXISTING wire variant; new
  `app/game-api/.../controller/NationController.kt` (POST `/api/nation/build-candidate` → publishes
  the wire command) + `app/game-engine/.../engine/intake/BuildNationCandidateHandler.kt` (foundation
  skeleton → real body: validate opening conditions, run `che_거병` inline, INSERT nation
  (color `#330000`, type `che_중립`, gennum=1) + diplomacy 2 rows/existing nation (state=2,term=0) +
  24× nation_turn). Reference `logic/.../actions/founding/CheGeobyeong.kt`.
- **Exact log strings (verbatim):**
  `삼국지 모의전투 PHP의 세계에 오신 것을 환영합니다 ^o^` · `통솔 <C>$pleadership</> 무력 <C>$pstrength</> 지력 <C>$pintel</> 의 보너스를 받으셨습니다.` ·
  `연령은 <C>$age</>세로 시작합니다.` · `축하합니다! 천재로 태어나 처음부터 <C>{speicalText}</> 특기를 가지게 됩니다!` ·
  `거병에 성공하였습니다. <1>{date}</>` · `<Y>{generalName}</>{josaYi} <G><b>{cityName}</b></>에 거병하였습니다.` ·
  `<Y><b>【거병】</b></><D><b>{generalName}</b></>{josaYi} 세력을 결성하였습니다.` · `<G><b>{cityName}</b></>에서 거병`
  (full set in the W6d slice spec — all are parity targets; `{speicalText}` typo is verbatim PHP).
- **Exact deny strings (verbatim, build-nation subset):**
  `장수가 없습니다` · `게임이 시작되었습니다.` · `이미 국가에 소속되어있습니다.` · `거병할 수 없는 모드입니다.`
  (join deny set — `이미 등록하셨습니다!`, `능력치가 48을 넘어섰습니다. 다시 가입해주세요!`, … — full list in slice spec).
- **Tests:** unit deny gates (deterministic), **8-draw RNG golden + che_거병 golden (deferred to
  /parity-wave)**. Nation-name dedup (`John`→`㉥John`→`㉥㉥John`) is deterministic → unit.

---

## 3. Build / gate order · risks · open questions

### Build & gate order

1. **Foundation commit** (ONE creator pass): §1.1-§1.5 — all wire variants + result classes +
   selector + mapper cases + dispatcher branches + **stub handler skeletons** + infra read-seam
   stubs. Gate: `:common:test` + `:app:game-engine:compileKotlin` + serializer round-trip green.
   `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :common:test :app:game-engine:compileKotlin --rerun-tasks 2>&1 | tail -40`
2. **Deterministic slices in parallel** (disjoint worktrees) — **W6e first** (zero shared-file
   touch, lowest risk), then **W6a → W6c → W5d**. Each: real handler body + controller + tests;
   gate by unit/IT (`:app:game-engine:test :app:game-api:test`). Land each as one logical commit.
3. **RNG-bearing slices** (**W6f, W6d**): land the deterministic deny-gate tests + handler skeleton
   now; **hand the RNG draw-for-draw gate to `/parity-wave`** (capture real PHP golden via
   `tools/php-golden`, replay draw-for-draw, gate green or quarantine-with-proof). Do **NOT**
   fabricate the join 8-draw seed, the weighted select-pool pick, or the che_거병 unique lottery.
4. **Full check** before ship: `./gradlew :common:test :logic:test :infra:test :app:game-engine:test :app:game-api:test`.

### Recommended FIRST slice to implement

**W6e (Command-queue)** — it is the only slice that touches **none** of the 4 shared foundation
files (REST-only, served in game-api), fully deterministic, and unblocks the most-used UI path
(turn reservation bulk/push/repeat). It can even start *before* the foundation commit lands. After
W6e, do the **foundation commit**, then **W6a** (messaging) as the first foundation-dependent slice
(highest user value, fully deterministic, cleanest handler shape mirroring `BoardHandler`).

### Risks

- **R1 (W6f/W6d RNG).** Join seeds 8 draws in exact PHP order; select-pool uses `allStat^1.5`
  weighting; che_거병 runs `tryUniqueItemLottery`. Any extra/missing/reordered draw desyncs the
  golden. **Mitigation:** /parity-wave with a real capture; never invent.
- **R2 (W6d result collapse).** `buildNationCandidate` is currently inside `BOOLEAN_OK_TYPES`. Adding
  `BuildNationCandidateResult` requires removing it there AND in the selector — a co-edit of the
  result file. Keep it collapsed unless `nationId` echo is load-bearing (Q-D1).
- **R3 (W5d aux jsonb order + Josa).** `ng_diplomacy.aux` must preserve `reason`/`src`/`dest`
  insertion order (LinkedHashMap); message Josa `이(가)` must match PHP morphology byte-for-byte.
- **R4 (W6a one-row public).** Public message writes only the receiver row (`sendToSender()=[0,0]`);
  a stray sender row breaks parity. Cover explicitly in test.
- **R5 (W6e brief-builder).** Bulk reserve needs `:logic` per-action `getBrief(arg)`; missing builder
  ⇒ wrong briefs. Confirm the builder seam exists before implementing.
- **R6 (infra read seams).** New `DiplomacyRepository`/`SelectPoolRepository`/contact-reader must be
  nullable-injected (stub-empty fallback) so engine IT runs DB-free, exactly like `votePollRepository?`.

### Open questions (blocking flagged ⚠️)

- **Q-D1 ⚠️ (W6d).** Does the FE need `nationId` echoed from `buildNationCandidate`? If yes →
  remove from `BOOLEAN_OK_TYPES` + add `BuildNationCandidateResult` (result-file co-edit). If no →
  keep `GeneralBoolResult`, drop `BuildNationCandidateResult` from the foundation. **Decide before
  foundation commit** (it changes §1.2).
- **Q-F1 (W6f).** The slice spec lists BOTH `이미 다시 고를 수 없습니다` (exactLogStrings) and
  `아직 다시 고를 수 없습니다` (denyStrings) for the cooldown guard. Confirm the exact verbatim string
  in `j_update_picked_general.php` before writing the deny — one is wrong.
- **Q-A3 (W6a).** Is `삭제된 메시지입니다.` a golden log or a runtime client message? Assume runtime
  (not gated). Confirm against `Message::invalidate($hideMsg=false)`.
- **Q-A1 (W6a).** `GetContactList` reads live `general WHERE npc<2` — needs a general-reader seam
  (or stub-empty for IT). Confirm whether it reads live rows vs a cache.
- **Q-W5d1 (W5d).** `j_diplomacy_destroy_letter.php` lines 95-107 has a `while(true)` prev_no chain
  loop whose `deleteAux` is never written back (dead code?). Proposal: do NOT cascade the chain;
  two-phase destroy only flips the TOP letter to `cancelled` when both nations agree. Confirm with
  PHP grand truth before implementing destroy cascade.
- **Q-C1 (W6c).** UniqueItem `availableCnt` (`GameConst.allItems` loop) is config-driven — verify the
  count formula against PHP exactly; and confirm whether the open action emits an action/global log
  or only the first-bid attempt does.
- **Q-E1 (W6e).** ReserveBulk turn-index validation: `setGeneralCommand` accepts `-1/-2/-3`
  (odd/even/all) but bulk does not — confirm the exact deny string for an out-of-range idx
  (`0≤idx<MAX`); the spec is silent on the verbatim message.
