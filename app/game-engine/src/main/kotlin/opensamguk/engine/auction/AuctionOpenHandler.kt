package opensamguk.engine.auction

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.common.wire.AuctionOpenResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.actions.nation.NationCommand
import opensamguk.logic.auction.AuctionBidItem
import opensamguk.logic.auction.AuctionBidItemData
import opensamguk.logic.auction.AuctionInfo
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ObfuscatedNamePool
import opensamguk.logic.auction.ResourceType
import opensamguk.logic.domain.metaInt
import java.time.Instant

/**
 * 경매 개설 핸들러 — `OpenBuyRiceAuction.php` / `OpenSellRiceAuction.php`(쌀/금 자원) +
 * `AuctionUniqueItem`(유니크) 개설 흐름의 faithful 포팅 (W6c 경매 개설). per-run
 * (world + recorder + auction/bid read repo), [AuctionBidHandler]를 미러링.
 *
 * PHP 진입점:
 *  - `AuctionBasicResource::openResourceAuction()` (BuyRice/SellRice 공용) — 검증 ORDER:
 *    3개월 게이트(API 레이어) → closeTurnCnt(1-24) → amount(100-10000) → startBid(50-200%)
 *    → finishBid(110-200%) → finishBid≥startBid*1.1 → 자원. `openAuction` 성공 **이후** 자원 차감.
 *  - `AuctionUniqueItem::openItemAuction()` — minPoint → 유산포인트 → !isBuyable → 동일아이템경매중
 *    → 동일host경매중 → availableCnt. 개설 후 host 첫 입찰(`bid(startAmount, false)`) + 전역 history 로그.
 *
 * 부수 효과는 [ChangeRecorder]를 통해서만 기록한다(one-daemon-write). ng_auction INSERT =
 * [ChangeRecorder.recordAuctionUpsert] (id=null → INSERT, 할당 id 반환); 자원 차감 =
 * [InMemoryTurnWorld.updateGeneral]; 유니크 첫 입찰 = ng_auction_bid INSERT + 유산 포인트 KV record.
 */
class AuctionOpenHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val auctionRepository: AuctionRepository,
    // 유니크 첫 입찰은 highestBid 조회가 필요 없어(최초 입찰) bid 레포를 읽지 않는다 — 시그니처 패리티 유지용.
    @Suppress("unused") private val auctionBidRepository: AuctionBidRepository,
) {

    // 거래량 범위 (PHP AuctionBasicResource::MIN/MAX_AUCTION_AMOUNT).
    private val minAuctionAmount = 100
    private val maxAuctionAmount = 10000

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // BuyRice / SellRice (자원 경매)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * 쌀 매수 경매 개설(`AuctionBuyRice`). host는 쌀을 내놓고 입찰자는 금으로 산다 →
     * hostRes=rice / bidderRes=gold / auctionType=BuyRice. PHP `OpenBuyRiceAuction.launch()`.
     */
    fun handleBuyRice(c: TurnDaemonCommand.AuctionOpenBuyRice): TurnDaemonCommandResult =
        openResourceAuction(
            type = "auctionOpenBuyRice",
            generalId = c.generalId,
            amount = c.amount,
            closeTurnCnt = c.closeTurnCnt,
            startBidAmount = c.startBidAmount,
            finishBidAmount = c.finishBidAmount,
            auctionType = AuctionType.BUY_RICE,
            hostRes = ResourceType.RICE,
            bidderRes = ResourceType.GOLD,
        )

    /**
     * 쌀 매도 경매 개설(`AuctionSellRice`). host는 금을 내놓고 입찰자는 쌀로 산다 →
     * hostRes=gold / bidderRes=rice / auctionType=SellRice. PHP `OpenSellRiceAuction.launch()`.
     */
    fun handleSellRice(c: TurnDaemonCommand.AuctionOpenSellRice): TurnDaemonCommandResult =
        openResourceAuction(
            type = "auctionOpenSellRice",
            generalId = c.generalId,
            amount = c.amount,
            closeTurnCnt = c.closeTurnCnt,
            startBidAmount = c.startBidAmount,
            finishBidAmount = c.finishBidAmount,
            auctionType = AuctionType.SELL_RICE,
            hostRes = ResourceType.GOLD,
            bidderRes = ResourceType.RICE,
        )

    /**
     * 자원 경매 공용 본문 — PHP `AuctionBasicResource::openResourceAuction()` 검증 순서 그대로.
     * 3개월 게이트는 API 레이어(`OpenBuyRiceAuction::launch`)가 먼저 보지만, 인테이크 경로에서는
     * precheck가 평가되지 않으므로 host측 게이트 전부를 핸들러가 byte-faithful하게 재현한다.
     */
    private fun openResourceAuction(
        type: String,
        generalId: Int,
        amount: Int,
        closeTurnCnt: Int,
        startBidAmount: Int,
        finishBidAmount: Int,
        auctionType: AuctionType,
        hostRes: ResourceType,
        bidderRes: ResourceType,
    ): TurnDaemonCommandResult {
        // 1. 장수 조회
        val general = world.getGeneralById(generalId)
            ?: return fail(type, generalId, "장수가 없습니다.")

        // 2. 시작 후 3개월 게이트 (OpenBuyRiceAuction.php:77-79). joinYearMonth = year*12 + month - 1.
        if (!afterThreeMonths()) {
            return fail(type, generalId, "시작 후 3개월이 지나야 경매를 열 수 있습니다.")
        }

        // 3. closeTurnCnt 1~24 (AuctionBasicResource.php:22-24)
        if (closeTurnCnt < 1 || closeTurnCnt > 24) {
            return fail(type, generalId, "종료기한은 1 ~ 24 턴 이어야 합니다.")
        }

        // 4. amount 100~10000 (AuctionBasicResource.php:25-27)
        if (amount < minAuctionAmount || amount > maxAuctionAmount) {
            return fail(type, generalId, "거래량은 $minAuctionAmount ~ $maxAuctionAmount 이어야 합니다.")
        }

        // 5. startBidAmount 50%~200% (AuctionBasicResource.php:28-30) — PHP는 float 비교.
        if (startBidAmount < amount * 0.5 || amount * 2 < startBidAmount) {
            return fail(type, generalId, "시작거래가는 50% ~ 200% 이어야 합니다.")
        }

        // 6. finishBidAmount 110%~200% (AuctionBasicResource.php:31-33)
        if (finishBidAmount < amount * 1.1 || amount * 2 < finishBidAmount) {
            return fail(type, generalId, "즉시거래가는 110% ~ 200% 이어야 합니다.")
        }

        // 7. finishBidAmount >= startBidAmount*1.1 (AuctionBasicResource.php:34-36)
        if (finishBidAmount < startBidAmount * 1.1) {
            return fail(type, generalId, "즉시거래가는 시작판매가의 110% 이상이어야 합니다.")
        }

        // 8. 자원 검증 (AuctionBasicResource.php:38-44): host자원 >= amount + minimumRes.
        val hostResName = resourceName(hostRes)
        val minimumRes = if (hostRes == ResourceType.RICE) GameConst.generalMinimumRice else GameConst.generalMinimumGold
        val hostResValue = if (hostRes == ResourceType.RICE) general.rice else general.gold
        if (hostResValue < amount + minimumRes) {
            return fail(type, generalId, "기본 $hostResName ${minimumRes}은 거래할 수 없습니다.")
        }

        // 9. 진행중인 자원 경매 부재 (AuctionBasicResource.php:46-56): host_general_id + finished=0 +
        //    type IN (buyRice, sellRice). DummyGeneral은 예외지만 인테이크 경로에는 등장하지 않는다.
        val hasOpenResourceAuction = auctionRepository
            .findByFinishedFalse()
            .any { it.hostGeneralId == generalId && it.type in RESOURCE_AUCTION_TYPES }
        if (hasOpenResourceAuction) {
            return fail(type, generalId, "아직 경매가 끝나지 않았습니다.")
        }

        // 10. openAuction INSERT (AuctionBasicResource.php:64-83). detail title = "{hostResName} {amount} 경매".
        val now = Instant.now()
        val closeDate = now.plusSeconds(closeTurnCnt.toLong() * resolveTurnTerm() * 60L)
        val info = AuctionInfo(
            id = null,
            type = auctionType,
            finished = false,
            target = "$amount",
            hostGeneralId = generalId,
            reqResource = bidderRes,
            openDate = now.toString(),
            closeDate = closeDate.toString(),
            detail = AuctionInfoDetail(
                title = "$hostResName $amount 경매",
                hostName = general.name,
                amount = amount,
                isReverse = false,
                startBidAmount = startBidAmount,
                finishBidAmount = finishBidAmount,
                remainCloseDateExtensionCnt = null,
                availableLatestBidCloseDate = null,
            ),
        )
        val auctionId = recorder.recordAuctionUpsert(id = null, columns = info.toArray())

        // 11. 자원 차감 — openAuction 성공 AFTER (AuctionBasicResource.php:89, increaseVarWithLimit lower=0).
        val deducted = (hostResValue - amount).coerceAtLeast(0)
        val updated = when (hostRes) {
            ResourceType.RICE -> general.copy(rice = deducted)
            ResourceType.GOLD -> general.copy(gold = deducted)
            ResourceType.INHERITANCE_POINT -> general
        }
        world.updateGeneral(updated)

        return AuctionOpenResult(type = type, ok = true, generalId = generalId, auctionId = auctionId)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // UniqueItem (유니크 아이템 경매)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * 유니크 아이템 경매 개설(`AuctionUniqueItem::openItemAuction`). reqResource=inheritancePoint,
     * host가 startAmount 포인트로 곧바로 첫 입찰을 넣는다. PHP `OpenUniqueAuction.launch()`.
     */
    fun handleUnique(c: TurnDaemonCommand.AuctionOpenUnique): TurnDaemonCommandResult {
        val type = "auctionOpenUnique"
        val generalId = c.generalId
        val itemKey = c.itemId
        val amount = c.amount

        // 1. 장수 조회
        val general = world.getGeneralById(generalId)
            ?: return fail(type, generalId, "장수가 없습니다.")

        // 2. 시작 후 3개월 게이트 (OpenUniqueAuction.php:68-70).
        if (!afterThreeMonths()) {
            return fail(type, generalId, "시작 후 3개월이 지나야 경매를 열 수 있습니다.")
        }

        // 3. 최소 경매 금액 (AuctionUniqueItem.php:23-25).
        if (amount < GameConst.inheritItemUniqueMinPoint) {
            return fail(type, generalId, "최소 경매 금액은 ${GameConst.inheritItemUniqueMinPoint}입니다.")
        }

        // 4. 유산포인트 충분 (AuctionUniqueItem.php:27-29). previous 키 = meta['inheritancePoint'].
        val inheritancePoint = metaInt(general.meta, "inheritancePoint")
        if (inheritancePoint < amount) {
            return fail(type, generalId, "경매를 시작할 포인트가 부족합니다.")
        }

        // 5. 구매 가능 아이템 거부 (AuctionUniqueItem.php:31-33): isBuyable() → 거부.
        if (isBuyable(itemKey)) {
            return fail(type, generalId, "구매할 수 있는 아이템입니다.")
        }

        // 6. 동일 아이템 경매 진행중 부재 (AuctionUniqueItem.php:36-44): finished=0 ∧ type=uniqueItem ∧ target=itemKey.
        val activeUnique = auctionRepository.findByFinishedFalse()
            .filter { it.type == AuctionType.UNIQUE_ITEM }
        if (activeUnique.any { it.target == itemKey }) {
            return fail(type, generalId, "이미 경매가 진행중입니다.")
        }

        // 7. host의 진행중 유니크 경매 부재 (AuctionUniqueItem.php:46-53).
        if (activeUnique.any { it.hostGeneralId == generalId }) {
            return fail(type, generalId, "아직 경매가 끝나지 않았습니다.")
        }

        // 8. availableCnt (AuctionUniqueItem.php:55-70, Q-C1 RESOLVED):
        //    allItems의 itemType별로 itemKey를 가진 맵을 순회 — 이미 비-구매 아이템 소지 시 거부,
        //    아니면 availableCnt += itemList[itemKey] - count(generals with slot==itemKey).
        var availableCnt = 0
        for ((itemType, itemList) in GameConst.allItems) {
            val perTypeCnt = itemList[itemKey] ?: continue
            val ownSlot = generalSlotValue(general, itemType)
            if (ownSlot != null && !isBuyable(ownSlot)) {
                return fail(type, generalId, "이미 가진 아이템이 있습니다.")
            }
            availableCnt += perTypeCnt
            availableCnt -= world.listGenerals().count { generalSlotValue(it, itemType) == itemKey }
        }
        if (availableCnt <= 0) {
            return fail(type, generalId, "그 유니크를 더 얻을 수 없습니다.")
        }

        // 9. openAuction INSERT (AuctionUniqueItem.php:86-110). hostName = genObfuscatedName(generalID)
        //    (PHP:97) — 입찰자 실명 은닉용 난독 이름. 풀은 hiddenSeed로부터 결정적 디코드.
        //    detail.amount = 1, startBidAmount = amount, finishBidAmount = null, remainCloseDateExtensionCnt = 1.
        val now = Instant.now()
        val turnTerm = resolveTurnTerm()
        val closeMinutes = maxOf(MIN_AUCTION_CLOSE_MINUTES, turnTerm * COEFF_AUCTION_CLOSE_MINUTES)
        val closeDate = now.plusSeconds(closeMinutes.toLong() * 60L)
        val extensionMinutes = maxOf(MIN_EXTENSION_MINUTES_LIMIT_BY_BID, (turnTerm * COEFF_EXTENSION_MINUTES_LIMIT_BY_BID).toInt())
        val availableLatestBidCloseDate = closeDate.plusSeconds(extensionMinutes.toLong() * 60L)

        val itemName = itemDisplayName(itemKey)
        val info = AuctionInfo(
            id = null,
            type = AuctionType.UNIQUE_ITEM,
            finished = false,
            target = itemKey,
            hostGeneralId = generalId,
            reqResource = ResourceType.INHERITANCE_POINT,
            openDate = now.toString(),
            closeDate = closeDate.toString(),
            detail = AuctionInfoDetail(
                title = "$itemName 경매",
                hostName = obfuscatedHostName(generalId),
                amount = 1,
                isReverse = false,
                startBidAmount = amount,
                finishBidAmount = null,
                remainCloseDateExtensionCnt = 1,
                availableLatestBidCloseDate = availableLatestBidCloseDate.toString(),
            ),
        )
        val auctionId = recorder.recordAuctionUpsert(id = null, columns = info.toArray())

        // 10. host 첫 입찰 (AuctionUniqueItem.php:111-123): bid(startAmount, tryExtendCloseDate=false).
        //     유니크 입찰가 검증은 host 본인이 최초 입찰이므로 항상 성공한다(highestBid 없음, 포인트 충분).
        //     ng_auction_bid INSERT + 유산 포인트 KV record(차감) — AuctionBidHandler 유니크 분기를 미러.
        val firstBid = AuctionBidItem(
            no = null,
            auctionId = auctionId,
            owner = null,
            generalId = generalId,
            amount = amount,
            date = now.toString(),
            aux = AuctionBidItemData(
                generalName = general.name,
                tryExtendCloseDate = false,
            ),
        )
        recorder.recordAuctionBidInsert(firstBid.toArray())
        recorder.recordInheritancePointIncrease(
            ownerID = generalId,
            key = "auction_bid",
            value = -amount.toDouble(),
            aux = mapOf("auctionId" to auctionId, "amount" to amount),
        )

        // 11. 전역 history 로그 (AuctionUniqueItem.php:125-130). Josa `라`는 getRawName() 기준.
        val itemRawName = itemRawName(itemKey)
        val josaRa = JosaUtil.pick(itemRawName, "라")
        world.pushLog(
            LogEntryDraft(
                scope = "global",
                category = "history",
                text = "<C><b>【보물수배】</b></>누군가가 <C>$itemName</>${josaRa}는 보물을 구한다는 소문이 들려옵니다.",
            ),
        )

        return AuctionOpenResult(type = type, ok = true, generalId = generalId, auctionId = auctionId)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun fail(type: String, generalId: Int, reason: String): AuctionOpenResult =
        AuctionOpenResult(type = type, ok = false, generalId = generalId, reason = reason)

    /**
     * 시작 후 3개월 경과 여부. PHP `yearMonth >= initYearMonth + 3` (`OpenBuyRiceAuction.php:77`).
     * `Util::joinYearMonth(y, m) = y*12 + m - 1`. opensamguk world_state.meta는 init_month을 별도로 두지
     * 않으며 시나리오는 month 1에서 시작하므로 init_month=1을 사용한다(state.meta 우선, 없으면 startYear/1).
     */
    private fun afterThreeMonths(): Boolean {
        val state = world.getState()
        val initYear = (state.meta["init_year"] as? Number)?.toInt()
            ?: (state.meta["startYear"] as? Number)?.toInt()
            ?: (state.meta["startyear"] as? Number)?.toInt()
            ?: state.currentYear
        val initMonth = (state.meta["init_month"] as? Number)?.toInt() ?: 1
        val initYearMonth = NationCommand.joinYearMonth(initYear, initMonth)
        val yearMonth = NationCommand.joinYearMonth(state.currentYear, state.currentMonth)
        return yearMonth >= initYearMonth + 3
    }

    /** world state에서 turnterm을 해석한다 (분 단위). [AuctionBidHandler.resolveTurnTerm]와 동일. */
    private fun resolveTurnTerm(): Int {
        val state = world.getState()
        return (state.meta["turnterm"] as? Number)?.toInt() ?: (state.tickSeconds / 60)
    }

    /** 자원 표시명 (PHP `ResourceType::getName()`): rice→쌀, gold→금. */
    private fun resourceName(res: ResourceType): String = when (res) {
        ResourceType.RICE -> "쌀"
        ResourceType.GOLD -> "금"
        ResourceType.INHERITANCE_POINT -> "유산포인트"
    }

    /**
     * 장수의 itemType 슬롯 값(none/blank → null). [TurnGeneral.role.items]가 horse/weapon/book/item을 보유.
     * PHP `$general->getItem($itemType)` → null/buyable 판정에 사용.
     */
    private fun generalSlotValue(general: TurnGeneral, itemType: String): String? {
        val raw = when (itemType) {
            "horse" -> general.role.items.horse
            "weapon" -> general.role.items.weapon
            "book" -> general.role.items.book
            "item" -> general.role.items.item
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() && it != "None" }
    }

    /**
     * 아이템이 구매 가능(buyable)한가 — PHP `BaseItem::$buyable`. `GameConst.allItems`에서 itemKey의
     * 모든 type-count가 0이면 기본(구매 가능) 아이템, 어느 하나라도 >0이면 한정 유니크(구매 불가).
     * 코드가 어느 맵에도 없으면 알 수 없으므로 buyable로 본다(개설 거부 게이트 보수적 통과).
     */
    private fun isBuyable(itemKey: String): Boolean {
        for ((_, itemList) in GameConst.allItems) {
            val cnt = itemList[itemKey] ?: continue
            if (cnt > 0) return false
        }
        // 어느 맵에도 없거나(알 수 없음) 모든 count가 0이면 buyable로 본다.
        return true
    }

    /**
     * 아이템 표시명 (PHP `BaseItem::getName()`): 명마/무기/서적(+stat) → `rawName(+statValue)`,
     * 그 외(`item` 카테고리 보물/유니크 등) → rawName(마지막 `_` 토큰). 코드 토큰을 직접 파싱한다.
     */
    private fun itemDisplayName(itemKey: String): String {
        val tokens = itemKey.split('_')
        if (tokens.size >= 3) {
            val category = tokens[tokens.size - 3]
            val statValue = tokens[tokens.size - 2].toIntOrNull()
            val rawName = tokens.last()
            if (statValue != null && category in STAT_ITEM_CATEGORIES) {
                return "$rawName(+$statValue)"
            }
        }
        return tokens.last()
    }

    /** 아이템 원형명 (PHP `BaseItem::getRawName()`) = 마지막 `_` 토큰. Josa 판정에 사용. */
    private fun itemRawName(itemKey: String): String = itemKey.split('_').last()

    /**
     * 유니크 경매 host 난독 이름 (PHP `Auction::genObfuscatedName(id)`). `state.meta['hiddenSeed']`로부터
     * `serializeSeed(hiddenSeed, KV_KEY)` 시드로 풀을 결정적으로 빌드한 뒤 id를 디코드한다(동일 시드 →
     * 동일 풀이라 PHP의 KV 캐시 유무와 무관하게 byte-faithful). hiddenSeed 부재 시 PHP의 중립화 placeholder를 쓴다.
     */
    private fun obfuscatedHostName(generalId: Int): String {
        val hiddenSeed = world.getState().meta["hiddenSeed"] as? String ?: return NEUTRAL_HOST_NAME
        val pool = ObfuscatedNamePool.buildPool(serializeSeed(hiddenSeed, ObfuscatedNamePool.KV_KEY))
        return ObfuscatedNamePool.decode(generalId, pool)
    }

    companion object {
        /** finished=0 자원 경매 진행중 판정에 쓰는 type 집합 (PHP `IN [buyRice, sellRice]`). */
        private val RESOURCE_AUCTION_TYPES = setOf(AuctionType.BUY_RICE, AuctionType.SELL_RICE)

        /** +stat 아이템 카테고리 (명마/무기/서적). `che_명마_15_적토마` → 적토마(+15). */
        private val STAT_ITEM_CATEGORIES = setOf("명마", "무기", "서적")

        /** hiddenSeed 부재 시 host 중립화 placeholder (PHP `setHostAsNeutral` `'(상인)'`). */
        private const val NEUTRAL_HOST_NAME = "(상인)"

        // 마감/연장 분 (PHP Auction 상수, AuctionUniqueItem 개설 경로에서 사용).
        private const val COEFF_AUCTION_CLOSE_MINUTES = 24
        private const val MIN_AUCTION_CLOSE_MINUTES = 30
        private const val COEFF_EXTENSION_MINUTES_LIMIT_BY_BID = 0.5
        private const val MIN_EXTENSION_MINUTES_LIMIT_BY_BID = 5
    }
}
