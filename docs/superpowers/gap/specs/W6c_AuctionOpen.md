# W6c Auction Open — AuctionOpenBuyRice / AuctionOpenSellRice / AuctionOpenUnique

**Slice ID**: W6c  
**Scope**: Auction open intake (openBuyRice / openSellRice / openUnique in PHP)  
**Type**: Immediate-intake command (F4 Wave C2 single-actor)  
**Deterministic**: Yes (no RNG; all input-driven)  
**Parity baseline**: PHP `legacy/devsam-core/hwe/sammo/API/Auction/{OpenBuyRiceAuction, OpenSellRiceAuction, OpenUniqueAuction}.php` + `{AuctionBasicResource, AuctionUniqueItem}.php`

---

## PHP Source Code

### Files
- `legacy/devsam-core/hwe/sammo/API/Auction/OpenBuyRiceAuction.php` (lines 1–98)
- `legacy/devsam-core/hwe/sammo/API/Auction/OpenSellRiceAuction.php` (lines 1–98)
- `legacy/devsam-core/hwe/sammo/API/Auction/OpenUniqueAuction.php` (lines 1–83)
- `legacy/devsam-core/hwe/sammo/AuctionBasicResource.php` (lines 20–93)
- `legacy/devsam-core/hwe/sammo/AuctionUniqueItem.php` (lines 21–133)

### Entry Points
1. **OpenBuyRiceAuction::launch()** — validateArgs → AuctionBuyRice::openResourceAuction()
2. **OpenSellRiceAuction::launch()** — validateArgs → AuctionSellRice::openResourceAuction()
3. **OpenUniqueAuction::launch()** — validateArgs → AuctionUniqueItem::openItemAuction()

---

## Intake Codes & Command Wire Variants

### Three New Intake Codes

| Code | Description | Wire Class |
|------|-------------|------------|
| `auctionOpenBuyRice` | Host sells rice; bidders buy with gold | `TurnDaemonCommand.AuctionOpenBuyRice` |
| `auctionOpenSellRice` | Host sells gold; bidders buy with rice | `TurnDaemonCommand.AuctionOpenSellRice` |
| `auctionOpenUnique` | Host offers unique item; bidders bid inheritance points | `TurnDaemonCommand.AuctionOpenUnique` |

### Wire Command Variants (TurnDaemonCommand.kt)

```kotlin
@Serializable
@SerialName("auctionOpenBuyRice")
data class AuctionOpenBuyRice(
    val requestId: String? = null,
    val generalId: Int,
    val amount: Int,           // rice amount to auction (100–10000)
    val closeTurnCnt: Int,     // 1–24 turns
    val startBidAmount: Int,   // gold starting bid
    val finishBidAmount: Int,  // gold instant-close bid
) : TurnDaemonCommand() {
    override val type: String get() = "auctionOpenBuyRice"
}

@Serializable
@SerialName("auctionOpenSellRice")
data class AuctionOpenSellRice(
    val requestId: String? = null,
    val generalId: Int,
    val amount: Int,           // gold amount to auction (100–10000)
    val closeTurnCnt: Int,     // 1–24 turns
    val startBidAmount: Int,   // rice starting bid
    val finishBidAmount: Int,  // rice instant-close bid
) : TurnDaemonCommand() {
    override val type: String get() = "auctionOpenSellRice"
}

@Serializable
@SerialName("auctionOpenUnique")
data class AuctionOpenUnique(
    val requestId: String? = null,
    val generalId: Int,
    val itemId: String,        // item class name (e.g., "Chomsungdo")
    val amount: Int,           // inheritance point starting bid (≥ GameConst::inheritItemUniqueMinPoint)
) : TurnDaemonCommand() {
    override val type: String get() = "auctionOpenUnique"
}
```

### Result Types (TurnDaemonCommandResult.kt)

```kotlin
@Serializable
data class AuctionOpenOk(
    override val type: String,
    override val ok: Boolean = true,
    val auctionId: Int,
) : TurnDaemonCommandResult()

@Serializable
data class AuctionOpenFail(
    override val type: String,
    override val ok: Boolean = false,
    val reason: String,        // deny string (Korean, byte-exact from PHP)
) : TurnDaemonCommandResult()
```

---

## CommandWireMapper Integration

### Changes to `intakeCodes` Set
```kotlin
val intakeCodes: Set<String> = setOf(
    // ... existing codes ...
    "auctionOpenBuyRice",
    "auctionOpenSellRice",
    "auctionOpenUnique",
)
```

### Changes to `toCommand()` when Branch
```kotlin
"auctionOpenBuyRice" -> TurnDaemonCommand.AuctionOpenBuyRice(
    requestId = requestId,
    generalId = generalId,
    amount = args.int("amount") ?: 0,
    closeTurnCnt = args.int("closeTurnCnt") ?: 0,
    startBidAmount = args.int("startBidAmount") ?: 0,
    finishBidAmount = args.int("finishBidAmount") ?: 0,
)
"auctionOpenSellRice" -> TurnDaemonCommand.AuctionOpenSellRice(
    requestId = requestId,
    generalId = generalId,
    amount = args.int("amount") ?: 0,
    closeTurnCnt = args.int("closeTurnCnt") ?: 0,
    startBidAmount = args.int("startBidAmount") ?: 0,
    finishBidAmount = args.int("finishBidAmount") ?: 0,
)
"auctionOpenUnique" -> TurnDaemonCommand.AuctionOpenUnique(
    requestId = requestId,
    generalId = generalId,
    itemId = args.str("itemId") ?: "",
    amount = args.int("amount") ?: 0,
)
```

---

## AuctionOpenHandler Implementation

### File Location
`app/game-engine/src/main/kotlin/opensamguk/engine/auction/AuctionOpenHandler.kt` (new file)

### Handler Class Structure
```kotlin
class AuctionOpenHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val auctionRepository: AuctionRepository,
) : TurnDaemonCommandHandler<TurnDaemonCommand.AuctionOpenBuyRice>  // (multi-dispatch below)

override fun handle(command: TurnDaemonCommand.AuctionOpenBuyRice): TurnDaemonCommandResult
override fun handle(command: TurnDaemonCommand.AuctionOpenSellRice): TurnDaemonCommandResult
override fun handle(command: TurnDaemonCommand.AuctionOpenUnique): TurnDaemonCommandResult
```

### Common Validation Flow (BuyRice & SellRice)

1. **Resolve general** → return Fail if null
2. **3-month gate**: `yearMonth >= initYearMonth + 3` else deny `"시작 후 3개월이 지나야 경매를 열 수 있습니다."`
3. **closeTurnCnt 1–24**: else deny `"종료기한은 1 ~ 24 턴 이어야 합니다."`
4. **amount 100–10000**: else deny `"거래량은 100 ~ 10000 이어야 합니다."`
5. **startBidAmount ratio** (50%–200%): `startBidAmount >= amount * 0.5 && startBidAmount <= amount * 2` else deny `"시작거래가는 50% ~ 200% 이어야 합니다."`
6. **finishBidAmount ratio** (110%–200%): `finishBidAmount >= amount * 1.1 && finishBidAmount <= amount * 2` else deny `"즉시거래가는 110% ~ 200% 이어야 합니다."`
7. **finishBidAmount ≥ startBidAmount × 1.1**: else deny `"즉시거래가는 시작판매가의 110% 이상이어야 합니다."`
8. **Resource check**: General must have `{hostRes} >= amount + minimumRes` (minimum is GameConst config). Deny: `"기본 {resourceName} {minimumRes}은 거래할 수 없습니다."`
9. **No existing auction**: `prevAuctionID === null` (for non-DummyGeneral). Deny: `"아직 경매가 끝나지 않았습니다."`

### BuyRice-Specific (`AuctionOpenBuyRice`)

- **hostRes** = `ResourceType.rice`
- **bidderRes** = `ResourceType.gold`
- **auctionType** = `AuctionType.BuyRice`
- **detail.amount** = rice amount
- **detail.startBidAmount** = gold starting bid
- **detail.finishBidAmount** = gold instant-close bid
- Deduct from general: `rice -= amount`

### SellRice-Specific (`AuctionOpenSellRice`)

- **hostRes** = `ResourceType.gold`
- **bidderRes** = `ResourceType.rice`
- **auctionType** = `AuctionType.SellRice`
- **detail.amount** = gold amount
- **detail.startBidAmount** = rice starting bid
- **detail.finishBidAmount** = rice instant-close bid
- Deduct from general: `gold -= amount`

### UniqueItem-Specific (`AuctionOpenUnique`)

1. **Resolve itemId to itemObj** via `buildItemClass(itemId)`
2. **Validation gates**:
   - `yearMonth >= initYearMonth + 3` (same 3-month check)
   - `amount >= GameConst::inheritItemUniqueMinPoint` else deny `"최소 경매 금액은 {min}입니다."`
   - `general.inheritancePoint >= amount` else deny `"경매를 시작할 포인트가 부족합니다."`
   - `!item.isBuyable()` (item must be non-purchasable, i.e., unique/heritage). Else deny `"구매할 수 있는 아이템입니다."`
   - No other auction on same item: `auctionIDonProgress === null` where `type === UniqueItem && target === itemKey`. Else deny `"이미 경매가 진행중입니다."`
   - No existing UniqueItem auction for this host: `prevAuctionID === null` where `type === UniqueItem`. Else deny `"아직 경매가 끝나지 않았습니다."`
   - Item availability: `availableCnt > 0` (count items across allItems configs, subtract occupied count from general table). Else deny `"그 유니크를 더 얻을 수 없습니다."` or `"이미 가진 아이템이 있습니다."` or `"그 유니크는 모두 점유되었습니다."`

3. **Create AuctionInfo** with:
   - `type = AuctionType.UniqueItem`
   - `target = itemKey`
   - `reqResource = ResourceType.inheritancePoint`
   - `detail.target = itemKey`
   - `detail.startAmount = amount`
   - `detail.hostName = genObfuscatedName(generalId)` (NOT the actual general name)
   - `detail.availableLatestBidCloseDate = closeDate + extension minutes`

4. **openAuction()** → ng_auction INSERT

5. **Immediate first bid** (host places opening bid at `amount` points):
   - Call `auction.bid(amount, tryExtendCloseDate=false)`
   - If fails: `closeAuction()` + return detailed fail reason
   - Log global action: `"{itemName} {josa(itemName, '라')}는 보물을 구한다는 소문이 들려옵니다."`

---

## ng_auction Column Mapping (Parity)

The handler calls `recorder.recordAuctionUpsert(id, columns)` where columns map to ng_auction:

| Column | BuyRice | SellRice | UniqueItem |
|--------|---------|----------|-----------|
| `id` | auto-inc | auto-inc | auto-inc |
| `type` | 'buyRice' | 'sellRice' | 'uniqueItem' |
| `finished` | 0 | 0 | 0 |
| `target` | "{amount}" (e.g., "500") | "{amount}" (e.g., "300") | itemKey (e.g., "Chomsungdo") |
| `host_general_id` | generalId | generalId | generalId |
| `req_resource` | 'gold' | 'rice' | 'inheritancePoint' |
| `open_date` | now | now | now |
| `close_date` | now + closeTurnCnt * turnTerm | now + closeTurnCnt * turnTerm | now + max(MIN, turnTerm * COEFF) |
| `detail` | (JSON below) | (JSON below) | (JSON below) |

### detail JSON Structure (AuctionInfoDetail::toArray)

**BuyRice / SellRice**:
```json
{
  "title": "{resourceName} {amount} 경매",
  "hostName": "{general.name}",
  "amount": amount,
  "isReverse": false,
  "startBidAmount": startBidAmount,
  "finishBidAmount": finishBidAmount,
  "remainCloseDateExtensionCnt": null,
  "availableLatestBidCloseDate": null
}
```

**UniqueItem**:
```json
{
  "title": "{itemObj.name} 경매",
  "hostName": "{obfuscatedName}",
  "amount": 1,
  "isReverse": false,
  "startBidAmount": amount,
  "finishBidAmount": null,
  "remainCloseDateExtensionCnt": 1,
  "availableLatestBidCloseDate": closeDate + extensionMinutes
}
```

---

## Resource Deduction

**For BuyRice/SellRice only** (NOT recorded as separate mutations — part of the open action):

```kotlin
val updatedGeneral = general.copy(
    rice = if (auctionType == BUY_RICE) general.rice - amount else general.rice,
    gold = if (auctionType == SELL_RICE) general.gold - amount else general.gold,
)
world.updateGeneral(updatedGeneral)
```

**For UniqueItem**:
- Inheritance points are handled via the first bid: `recorder.recordInheritancePointIncrease(generalId, "auction_bid", -amount, ...)`
- No direct general.rice / general.gold mutation

---

## TurnDaemonCommandDispatcher Integration

### Changes to Constructor
```kotlin
private val auctionOpen = AuctionOpenHandler(world, recorder, auctionRepository)
```

### Changes to dispatch() when Branch
```kotlin
is TurnDaemonCommand.AuctionOpenBuyRice -> auctionOpen.handle(command)
is TurnDaemonCommand.AuctionOpenSellRice -> auctionOpen.handle(command)
is TurnDaemonCommand.AuctionOpenUnique -> auctionOpen.handle(command)
```

---

## Exact Deny Strings (Korean, Byte-Exact)

All deny strings are **hardcoded Korean** — MUST match PHP verbatim:

1. `"시작 후 3개월이 지나야 경매를 열 수 있습니다."` (OpenBuyRiceAuction.php:78)
2. `"종료기한은 1 ~ 24 턴 이어야 합니다."` (AuctionBasicResource.php:23)
3. `"거래량은 100 ~ 10000 이어야 합니다."` (AuctionBasicResource.php:26)
4. `"시작거래가는 50% ~ 200% 이어야 합니다."` (AuctionBasicResource.php:29)
5. `"즉시거래가는 110% ~ 200% 이어야 합니다."` (AuctionBasicResource.php:32)
6. `"즉시거래가는 시작판매가의 110% 이상이어야 합니다."` (AuctionBasicResource.php:35)
7. `"기본 쌀 {minimumRice}은 거래할 수 없습니다."` (AuctionBasicResource.php:43, resource name interpolated)
8. `"기본 금 {minimumGold}은 거래할 수 없습니다."` (AuctionBasicResource.php:43, resource name interpolated)
9. `"아직 경매가 끝나지 않았습니다."` (AuctionBasicResource.php:54)
10. `"최소 경매 금액은 {inheritItemUniqueMinPoint}입니다."` (AuctionUniqueItem.php:24)
11. `"경매를 시작할 포인트가 부족합니다."` (AuctionUniqueItem.php:28)
12. `"구매할 수 있는 아이템입니다."` (AuctionUniqueItem.php:32)
13. `"이미 경매가 진행중입니다."` (AuctionUniqueItem.php:43)
14. `"이미 가진 아이템이 있습니다."` (AuctionUniqueItem.php:62)
15. `"그 유니크를 더 얻을 수 없습니다."` (AuctionUniqueItem.php:69)
16. `"경매를 시작했지만, 첫 입찰에 실패했습니다: {failReason}"` (AuctionUniqueItem.php:116)

---

## Test Plan

### Unit Tests
- ✅ Validation gates (3-month, closeTurnCnt, amount, bidAmount ratios, resource)
- ✅ BuyRice vs SellRice resource deduction (rice vs gold)
- ✅ UniqueItem validation (inheritancePoint, item availability, non-buyable)
- ✅ ng_auction INSERT correctness (detail JSON structure, all columns)
- ✅ UniqueItem first bid (attempt & catch failure)
- ✅ CommandWireMapper parsing (all 3 codes)
- ✅ TurnDaemonCommandDispatcher routing

### Integration Tests
- ✅ Full BuyRice flow: POST → daemon tick → ng_auction + general.rice
- ✅ Full SellRice flow: POST → daemon tick → ng_auction + general.gold
- ✅ Full UniqueItem flow: POST → daemon tick → ng_auction + ng_auction_bid + inheritance_point_record

### Parity Tests
- ✅ All deny strings byte-exact match PHP
- ✅ Validation order (3-month first, then ratios)
- ✅ detail JSON serialization
- ✅ UniqueItem self-bid flow

---

## Risk & Open Questions

### RNG
- **None**: Auction open is deterministic (no randomness).

### Parity Risks
1. **Detail JSON**: Must serialize exactly as PHP AuctionInfoDetail::toArray (nullable fields, boolean isReverse).
2. **Minimum resources**: GameConst must expose `$generalMinimumRice` / `$generalMinimumGold` in config.
3. **UniqueItem availability calc**: Complex logic in AuctionUniqueItem.php lines 56–70 (loop GameConst::$allItems, count occupants per general table). Must validate config parity.
4. **First bid fail**: If UniqueItem first bid fails, must closeAuction & return reason. Rare but critical.

### Open Questions
1. Should UniqueItem open produce an action log (in addition to the "보물수배" global log)?
2. Does finishBidAmount in the open command trigger instant-close on the opening auction, or only on later bids?
3. For BuyRice/SellRice, should the open produce an action log, or only the bids do?

---

## Files to Modify/Create

### Create
- [ ] `app/game-engine/src/main/kotlin/opensamguk/engine/auction/AuctionOpenHandler.kt`
- [ ] Add 3 TurnDaemonCommand variants to `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommand.kt`
- [ ] Add AuctionOpenOk / AuctionOpenFail to `common/src/main/kotlin/opensamguk/common/wire/TurnDaemonCommandResult.kt`

### Modify
- [ ] `app/game-api/src/main/kotlin/opensamguk/gameapi/reserve/CommandWireMapper.kt` (intakeCodes + toCommand)
- [ ] `app/game-engine/src/main/kotlin/opensamguk/engine/run/TurnDaemonCommandDispatcher.kt` (constructor + dispatch)

