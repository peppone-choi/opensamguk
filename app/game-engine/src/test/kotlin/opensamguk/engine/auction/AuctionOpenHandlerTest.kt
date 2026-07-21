package opensamguk.engine.auction

import opensamguk.common.wire.AuctionOpenResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralItems
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
import opensamguk.logic.util.jsonDecode
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W6c 경매 개설 핸들러 단위 테스트 (DETERMINISTIC, no RNG).
 *
 * [AuctionOpenHandler]의 BuyRice/SellRice/Unique 3 경로를 검증한다:
 *  - 검증 게이트 ORDER (3개월 → closeTurnCnt → amount → startBid → finishBid → finishBid≥start*1.1 → 자원),
 *  - 자원 차감(openAuction 성공 AFTER), detail-JSON shape,
 *  - 유니크 게이트 + self-first-bid(ng_auction_bid INSERT) + 유산 포인트 KV record,
 *  - deny 문자열 byte-parity (PHP verbatim).
 */
class AuctionOpenHandlerTest {

    private val t0 = Instant.parse("0200-04-01T00:00:00Z")

    /**
     * year=200 / month=4, startYear=200(=init_year, init_month=1). joinYearMonth(y,m)=y*12+m-1.
     * initYearMonth=joinYearMonth(200,1)=2400, +3=2403. yearMonth=joinYearMonth(200,4)=2403 →
     * 2403 >= 2403 → 3개월 게이트 통과(가능).
     */
    private fun world(
        rice: Int = 100_000,
        gold: Int = 100_000,
        inheritancePoint: Int = 0,
        items: GeneralItems = GeneralItems(),
        extraGenerals: List<TurnGeneral> = emptyList(),
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 4, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("startYear" to 200, "turnterm" to 60),
            ),
            generals = listOf(
                TurnGeneral(
                    id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                    officerLevel = 12, gold = gold, rice = rice, turnTime = t0,
                    role = GeneralRole(items = items),
                    meta = mapOf("inheritancePoint" to inheritancePoint),
                ),
            ) + extraGenerals,
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 4, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("startYear" to 200, "turnterm" to 60),
            )).id),
        ),
    )

    /** world의 3개월 게이트를 month=2로 막은 변형(joinYearMonth(200,2)=2401 < 2403 → 거부). */
    private fun worldBeforeThreeMonths(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 2, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("startYear" to 200, "turnterm" to 60),
            ),
            generals = listOf(
                TurnGeneral(
                    id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                    officerLevel = 12, gold = 100_000, rice = 100_000, turnTime = t0,
                    meta = mapOf("inheritancePoint" to 100_000),
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 2, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("startYear" to 200, "turnterm" to 60),
            )).id),
        ),
    )

    /** 입찰/조회 메서드를 호출하지 않는 핸들러 경로용 no-op bid 레포 프록시. */
    private fun noopBidRepo(): AuctionBidRepository = Proxy.newProxyInstance(
        AuctionBidRepository::class.java.classLoader,
        arrayOf(AuctionBidRepository::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            else -> null
        }
    } as AuctionBidRepository

    /** findByFinishedFalse()가 주어진 active 경매 목록을 반환하는 fake 경매 레포. */
    private fun auctionRepo(active: List<AuctionEntity> = emptyList()): AuctionRepository = Proxy.newProxyInstance(
        AuctionRepository::class.java.classLoader,
        arrayOf(AuctionRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "findByFinishedFalse" -> active
            else -> when (method.returnType) {
                java.util.List::class.java -> emptyList<Any>()
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as AuctionRepository

    private fun handler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        active: List<AuctionEntity> = emptyList(),
    ) = AuctionOpenHandler(world, recorder, auctionRepo(active), noopBidRepo())

    private fun activeAuction(
        type: AuctionType,
        hostGeneralId: Int,
        target: String?,
    ): AuctionEntity = AuctionEntity(
        type = type,
        finished = false,
        target = target,
        hostGeneralId = hostGeneralId,
        reqResource = ResourceType.GOLD,
        openDate = t0,
        closeDate = t0.plusSeconds(3600),
        detail = "{}",
        id = 99,
    )

    private val buyRice = TurnDaemonCommand.AuctionOpenBuyRice(
        generalId = 10, amount = 1000, closeTurnCnt = 5, startBidAmount = 1000, finishBidAmount = 1500,
    )

    // ── BuyRice / SellRice 정상 경로 + 자원 차감 + detail-JSON ─────────────────────────────────────

    @Test
    fun `buyRice opens auction, deducts rice AFTER open, and records ng_auction INSERT`() {
        val world = world(rice = 100_000)
        val recorder = ChangeRecorder()

        val result = handler(world, recorder).handleBuyRice(buyRice) as AuctionOpenResult

        assertTrue(result.ok)
        assertEquals("auctionOpenBuyRice", result.type)
        assertEquals(10, result.generalId)
        assertNull(result.reason)
        assertEquals(1, result.auctionId) // 첫 INSERT → 1-based 할당 id echo
        // 쌀 차감 (100000 - 1000), 금은 불변.
        assertEquals(99_000, world.getGeneralById(10)!!.rice)
        assertEquals(100_000, world.getGeneralById(10)!!.gold)
        // ng_auction INSERT 1건 + 컬럼 패리티.
        val upserts = recorder.auctionUpserts()
        assertEquals(1, upserts.size)
        val cols = upserts.single().columns
        assertEquals("buyRice", cols["type"])
        assertEquals(false, cols["finished"])
        assertEquals("1000", cols["target"])
        assertEquals(10, cols["host_general_id"])
        assertEquals("gold", cols["req_resource"]) // bidderRes = gold
        // detail JSON shape (쌀 1000 경매).
        val detail = jsonDecode(cols["detail"] as String)
        assertEquals("쌀 1000 경매", detail["title"])
        assertEquals("유비", detail["hostName"])
        assertEquals(1000, (detail["amount"] as Number).toInt())
        assertEquals(false, detail["isReverse"])
        assertEquals(1000, (detail["startBidAmount"] as Number).toInt())
        assertEquals(1500, (detail["finishBidAmount"] as Number).toInt())
        // remainCloseDateExtensionCnt / availableLatestBidCloseDate 는 null → 키 OMIT.
        assertFalse(detail.containsKey("remainCloseDateExtensionCnt"))
        assertFalse(detail.containsKey("availableLatestBidCloseDate"))
        // 첫 입찰 없음(자원 경매), 유산포인트 record 없음.
        assertTrue(recorder.auctionBidInserts().isEmpty())
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
    }

    @Test
    fun `sellRice opens auction, deducts gold AFTER open, and req_resource is rice`() {
        val world = world(gold = 100_000)
        val recorder = ChangeRecorder()

        val cmd = TurnDaemonCommand.AuctionOpenSellRice(
            generalId = 10, amount = 2000, closeTurnCnt = 3, startBidAmount = 2000, finishBidAmount = 3000,
        )
        val result = handler(world, recorder).handleSellRice(cmd) as AuctionOpenResult

        assertTrue(result.ok)
        assertEquals("auctionOpenSellRice", result.type)
        // 금 차감 (100000 - 2000), 쌀 불변.
        assertEquals(98_000, world.getGeneralById(10)!!.gold)
        assertEquals(100_000, world.getGeneralById(10)!!.rice)
        val cols = recorder.auctionUpserts().single().columns
        assertEquals("sellRice", cols["type"])
        assertEquals("rice", cols["req_resource"]) // bidderRes = rice
        val detail = jsonDecode(cols["detail"] as String)
        assertEquals("금 2000 경매", detail["title"])
    }

    // ── BuyRice / SellRice 검증 게이트 (ORDER + verbatim deny) ────────────────────────────────────

    @Test
    fun `three-month gate denies before any other check`() {
        val world = worldBeforeThreeMonths()
        val recorder = ChangeRecorder()
        // closeTurnCnt 도 잘못됐지만 3개월 게이트가 먼저 잡혀야 한다.
        val cmd = buyRice.copy(closeTurnCnt = 99)
        val result = handler(world, recorder).handleBuyRice(cmd) as AuctionOpenResult
        assertFalse(result.ok)
        assertEquals("시작 후 3개월이 지나야 경매를 열 수 있습니다.", result.reason)
        assertTrue(recorder.auctionUpserts().isEmpty())
        // 자원도 차감되지 않았어야 한다.
        assertEquals(100_000, world.getGeneralById(10)!!.rice)
    }

    @Test
    fun `closeTurnCnt out of range denies`() {
        val recorder = ChangeRecorder()
        val r0 = handler(world(), recorder).handleBuyRice(buyRice.copy(closeTurnCnt = 0)) as AuctionOpenResult
        assertEquals("종료기한은 1 ~ 24 턴 이어야 합니다.", r0.reason)
        val r25 = handler(world(), ChangeRecorder()).handleBuyRice(buyRice.copy(closeTurnCnt = 25)) as AuctionOpenResult
        assertEquals("종료기한은 1 ~ 24 턴 이어야 합니다.", r25.reason)
    }

    @Test
    fun `amount out of range denies`() {
        val low = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(amount = 99, startBidAmount = 50, finishBidAmount = 110)) as AuctionOpenResult
        assertEquals("거래량은 100 ~ 10000 이어야 합니다.", low.reason)
        val high = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(amount = 10001, startBidAmount = 10000, finishBidAmount = 11500)) as AuctionOpenResult
        assertEquals("거래량은 100 ~ 10000 이어야 합니다.", high.reason)
    }

    @Test
    fun `startBid ratio out of range denies`() {
        // amount=1000 → start 범위 [500, 2000]. 499 미만 거부.
        val low = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(startBidAmount = 499)) as AuctionOpenResult
        assertEquals("시작거래가는 50% ~ 200% 이어야 합니다.", low.reason)
        val high = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(startBidAmount = 2001, finishBidAmount = 2001)) as AuctionOpenResult
        assertEquals("시작거래가는 50% ~ 200% 이어야 합니다.", high.reason)
    }

    @Test
    fun `finishBid ratio out of range denies`() {
        // amount=1000 → finish 범위 [1100, 2000]. 1099 거부.
        val low = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(finishBidAmount = 1099)) as AuctionOpenResult
        assertEquals("즉시거래가는 110% ~ 200% 이어야 합니다.", low.reason)
        val high = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(finishBidAmount = 2001)) as AuctionOpenResult
        assertEquals("즉시거래가는 110% ~ 200% 이어야 합니다.", high.reason)
    }

    @Test
    fun `finishBid below start times 1_1 denies`() {
        // start=2000, finish=2000(110%~200% 통과) 이지만 start*1.1=2200 > 2000 → 거부.
        val r = handler(world(), ChangeRecorder())
            .handleBuyRice(buyRice.copy(startBidAmount = 2000, finishBidAmount = 2000)) as AuctionOpenResult
        assertEquals("즉시거래가는 시작판매가의 110% 이상이어야 합니다.", r.reason)
    }

    @Test
    fun `insufficient rice denies with verbatim minimum string`() {
        // generalMinimumRice=500 → 필요 = 1000 + 500 = 1500. 1499 보유 → 거부.
        val world = world(rice = 1499)
        val r = handler(world, ChangeRecorder()).handleBuyRice(buyRice) as AuctionOpenResult
        assertEquals("기본 쌀 500은 거래할 수 없습니다.", r.reason)
    }

    @Test
    fun `insufficient gold for sellRice denies with verbatim minimum string`() {
        // generalMinimumGold=0 → 필요 = 1000 + 0 = 1000. 999 보유 → 거부.
        val world = world(gold = 999)
        val cmd = TurnDaemonCommand.AuctionOpenSellRice(
            generalId = 10, amount = 1000, closeTurnCnt = 5, startBidAmount = 1000, finishBidAmount = 1500,
        )
        val r = handler(world, ChangeRecorder()).handleSellRice(cmd) as AuctionOpenResult
        assertEquals("기본 금 0은 거래할 수 없습니다.", r.reason)
    }

    @Test
    fun `existing resource auction denies`() {
        val recorder = ChangeRecorder()
        val active = listOf(activeAuction(AuctionType.SELL_RICE, hostGeneralId = 10, target = "500"))
        val r = handler(world(), recorder, active).handleBuyRice(buyRice) as AuctionOpenResult
        assertEquals("아직 경매가 끝나지 않았습니다.", r.reason)
        assertTrue(recorder.auctionUpserts().isEmpty())
    }

    // ── UniqueItem 정상 경로 + self-first-bid + 유산 포인트 record ─────────────────────────────────

    // che_명마_15_적토마 = count 2(>0) 유니크. 적토마(+15) 표시명.
    private val uniqueItem = "che_명마_15_적토마"

    @Test
    fun `unique opens auction, records self first bid and inheritance point deduction`() {
        val world = world(inheritancePoint = 10_000)
        val recorder = ChangeRecorder()

        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 6000)
        val result = handler(world, recorder).handleUnique(cmd) as AuctionOpenResult

        assertTrue(result.ok)
        assertEquals("auctionOpenUnique", result.type)
        // ng_auction INSERT + 컬럼/디테일 패리티.
        val cols = recorder.auctionUpserts().single().columns
        assertEquals("uniqueItem", cols["type"])
        assertEquals(uniqueItem, cols["target"])
        assertEquals("inheritPoint", cols["req_resource"])
        val detail = jsonDecode(cols["detail"] as String)
        assertEquals("적토마(+15) 경매", detail["title"])
        assertEquals("(상인)", detail["hostName"])
        assertEquals(1, (detail["amount"] as Number).toInt())
        assertEquals(6000, (detail["startBidAmount"] as Number).toInt())
        assertFalse(detail.containsKey("finishBidAmount")) // null → OMIT
        assertEquals(1, (detail["remainCloseDateExtensionCnt"] as Number).toInt())
        assertTrue(detail.containsKey("availableLatestBidCloseDate"))
        // self first bid INSERT (amount==startAmount).
        val bid = recorder.auctionBidInserts().single().columns
        assertEquals(10, bid["general_id"])
        assertEquals(6000, bid["amount"])
        // 유산 포인트 차감 record (음수).
        val kv = recorder.inheritanceKvWrites().single()
        assertEquals("inheritance_10", kv.namespace)
        assertEquals("auction_bid", kv.key)
        // 전역 history 로그.
        // (로그는 world 내부 — 직접 assert 대신 deny-free 경로 확인으로 충분)
    }

    @Test
    fun `unique below minimum point denies`() {
        val world = world(inheritancePoint = 10_000)
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 4999)
        val r = handler(world, ChangeRecorder()).handleUnique(cmd) as AuctionOpenResult
        assertEquals("최소 경매 금액은 5000입니다.", r.reason)
    }

    @Test
    fun `unique insufficient inheritance point denies`() {
        val world = world(inheritancePoint = 5000)
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 6000)
        val r = handler(world, ChangeRecorder()).handleUnique(cmd) as AuctionOpenResult
        assertEquals("경매를 시작할 포인트가 부족합니다.", r.reason)
    }

    @Test
    fun `unique buyable item denies`() {
        // che_명마_01_노기 = count 0 → buyable.
        val world = world(inheritancePoint = 10_000)
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = "che_명마_01_노기", amount = 6000)
        val r = handler(world, ChangeRecorder()).handleUnique(cmd) as AuctionOpenResult
        assertEquals("구매할 수 있는 아이템입니다.", r.reason)
    }

    @Test
    fun `unique already has a non-buyable item in that slot denies`() {
        // 같은 horse 슬롯에 다른 유니크(적란마, count 2) 보유 → "이미 가진 아이템이 있습니다."
        val world = world(inheritancePoint = 10_000, items = GeneralItems(horse = "che_명마_14_적란마"))
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 6000)
        val r = handler(world, ChangeRecorder()).handleUnique(cmd) as AuctionOpenResult
        assertEquals("이미 가진 아이템이 있습니다.", r.reason)
    }

    @Test
    fun `unique same-item auction in progress denies`() {
        val world = world(inheritancePoint = 10_000)
        val active = listOf(activeAuction(AuctionType.UNIQUE_ITEM, hostGeneralId = 99, target = uniqueItem))
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 6000)
        val r = handler(world, ChangeRecorder(), active).handleUnique(cmd) as AuctionOpenResult
        assertEquals("이미 경매가 진행중입니다.", r.reason)
    }

    @Test
    fun `unique host already has an open unique auction denies`() {
        val world = world(inheritancePoint = 10_000)
        // 다른 유니크지만 같은 host가 연 진행중 유니크 경매가 있다.
        val active = listOf(activeAuction(AuctionType.UNIQUE_ITEM, hostGeneralId = 10, target = "che_무기_15_의천검"))
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 6000)
        val r = handler(world, ChangeRecorder(), active).handleUnique(cmd) as AuctionOpenResult
        assertEquals("아직 경매가 끝나지 않았습니다.", r.reason)
    }

    @Test
    fun `unique availableCnt exhausted denies`() {
        // che_명마_15_적토마 count = 2. 2명이 이미 소지 → availableCnt = 0 → "그 유니크를 더 얻을 수 없습니다."
        val others = listOf(
            TurnGeneral(
                id = 20, name = "관우", nationId = 1, cityId = 5, troopId = 0,
                stats = GeneralStats(70, 90, 50), experience = 0, dedication = 0, officerLevel = 1,
                turnTime = t0, role = GeneralRole(items = GeneralItems(horse = uniqueItem)),
            ),
            TurnGeneral(
                id = 21, name = "장비", nationId = 1, cityId = 5, troopId = 0,
                stats = GeneralStats(60, 95, 40), experience = 0, dedication = 0, officerLevel = 1,
                turnTime = t0, role = GeneralRole(items = GeneralItems(horse = uniqueItem)),
            ),
        )
        val world = world(inheritancePoint = 10_000, extraGenerals = others)
        val cmd = TurnDaemonCommand.AuctionOpenUnique(generalId = 10, itemId = uniqueItem, amount = 6000)
        val r = handler(world, ChangeRecorder()).handleUnique(cmd) as AuctionOpenResult
        assertEquals("그 유니크를 더 얻을 수 없습니다.", r.reason)
    }

    // ── 직렬화 round-trip (성공 경로 auctionId echo) ──────────────────────────────────────────────

    @Test
    fun `AuctionOpenResult ok with auctionId round-trips through WireJson`() {
        val world = world()
        val recorder = ChangeRecorder()
        val result = handler(world, recorder).handleBuyRice(buyRice) as AuctionOpenResult

        val envelope = TurnDaemonCommandEnvelope(
            requestId = "r", sentAt = t0.toString(), command = buyRice,
        )
        // 결과 직렬화 round-trip (collapsed AuctionOpenResult shape).
        val encoded = WireJson.encodeToString(AuctionOpenResult.serializer(), result)
        val decoded = WireJson.decodeFromString(AuctionOpenResult.serializer(), encoded)
        assertEquals(result, decoded)
        assertTrue(decoded.ok)
        assertEquals(result.auctionId, decoded.auctionId)
        // envelope 은 명령 쪽 round-trip 도 통과해야 한다.
        val cmdEnc = WireJson.encodeToString(TurnDaemonCommandEnvelope.serializer(), envelope)
        val cmdDec = WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), cmdEnc)
        assertEquals(buyRice, cmdDec.command)
    }
}
