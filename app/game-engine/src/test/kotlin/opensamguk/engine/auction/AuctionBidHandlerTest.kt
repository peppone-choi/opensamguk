package opensamguk.engine.auction

import opensamguk.common.rng.serializeSeed
import opensamguk.common.wire.AuctionBidFail
import opensamguk.common.wire.AuctionBidOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralItems
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.AuctionBidEntity
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ObfuscatedNamePool
import opensamguk.logic.auction.ResourceType
import opensamguk.logic.util.jsonDecode
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 바퀴 26 (W-4) — [AuctionBidHandler]의 PHP `Auction::_bid()`/`bidInheritPoint()`/`refundBid()`
 * 환불·차액 규칙 패러티 테스트.
 *
 * 도출 규칙(전부 PHP 인용 — 핸들러 KDoc R1-R4 참조):
 *  - R1 무효화: 내 이전 입찰이 현재 최고가 행(`no` 동일)이 아니면 이미 환불됨 → 무효
 *    (Auction.php:399-403 / :293-297)
 *  - R2 차액: morePoint = amount - (유효 이전입찰액) 만 차감 (Auction.php:405,437 / :299,340)
 *  - R3 조건부 환불: `highestBid != null && myPrevBid == null` 일 때만 전액 환불
 *    (Auction.php:450-452 / :343-345, refundBid :224-228)
 *  - R4 에스크로 보존: 현재 최고 입찰자만 highestBid.amount 누적 지불, 나머지 순지불 0
 *
 * 종전 구현(무조건 환불 + stale myPrevBid 차액)은 자기 상회입찰 시 자원 복제,
 * 환불완료 입찰 재기준 시 미달 차감을 일으켰다 — 본 스위트의 (iii)/(v)가 그 회귀 가드다.
 */
class AuctionBidHandlerTest {

    private val t0 = Instant.parse("0200-04-01T00:00:00Z")

    /** 연장이 절대 발화하지 않는 먼 미래 마감(결정적 테스트용). */
    private val farClose = Instant.parse("2998-01-01T00:00:00Z")
    private val farLatest = "2999-01-01T00:00:00Z"

    private fun general(
        id: Int,
        name: String,
        gold: Int = 5000,
        rice: Int = 5000,
        userId: Int? = null,
        npc: Int = 0,
        meta: Map<String, Any?> = emptyMap(),
        items: GeneralItems = GeneralItems(),
    ) = TurnGeneral(
        id = id, name = name, nationId = 1, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = 1, gold = gold, rice = rice, turnTime = t0,
        userId = userId?.toString(), npcState = npc, meta = meta,
        role = GeneralRole(items = items),
    )

    private fun world(
        vararg generals: TurnGeneral,
        worldMeta: Map<String, Any?> = emptyMap(),
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1, currentYear = 200, currentMonth = 4, tickSeconds = 3600, lastTurnTime = t0,
                meta = mapOf("turnterm" to 60) + worldMeta,
            ),
            generals = generals.toList(),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0")),
        ),
    )

    /** BUY_RICE(입찰 통화 금) / SELL_RICE(쌀) 자원 경매 — AuctionBuyRice.php:12-13 / AuctionSellRice.php:12-13. */
    private fun resourceAuction(
        type: AuctionType = AuctionType.BUY_RICE,
        reqResource: ResourceType = ResourceType.GOLD,
        hostGeneralId: Int = 99,
        finished: Boolean = false,
        openDate: Instant = t0,
        closeDate: Instant = farClose,
        finishBidAmount: Int = 1500,
    ) = AuctionEntity(
        type = type,
        finished = finished,
        target = "1000",
        hostGeneralId = hostGeneralId,
        reqResource = reqResource,
        openDate = openDate,
        closeDate = closeDate,
        detail = """{"title":"쌀 1000 경매","hostName":"호스트","amount":1000,"isReverse":false,""" +
            """"startBidAmount":1000,"finishBidAmount":$finishBidAmount}""",
        id = 5,
    )

    /** 유산포인트(유니크) 경매 — reqResource=inheritPoint, availableLatestBidCloseDate 보유. */
    private fun uniqueAuction(
        closeDate: Instant = farClose,
        finished: Boolean = false,
        id: Int = 5,
        target: String = "che_명마_15_적토마",
    ) = AuctionEntity(
        type = AuctionType.UNIQUE_ITEM,
        finished = finished,
        target = target,
        hostGeneralId = 10,
        reqResource = ResourceType.INHERITANCE_POINT,
        openDate = t0,
        closeDate = closeDate,
        detail = """{"title":"적토마(+15) 경매","hostName":"(상인)","amount":1,"isReverse":false,""" +
            """"startBidAmount":6000,"remainCloseDateExtensionCnt":1,""" +
            """"availableLatestBidCloseDate":"$farLatest"}""",
        id = id,
    )

    private fun bidRow(no: Int, generalId: Int, amount: Int, owner: Int? = null, auctionId: Int = 5) = AuctionBidEntity(
        auctionId = auctionId, owner = owner, generalId = generalId, amount = amount,
        date = t0, aux = """{"generalName":"g$generalId"}""", no = no,
    )

    private fun auctionRepo(entity: AuctionEntity): AuctionRepository = Proxy.newProxyInstance(
        AuctionRepository::class.java.classLoader,
        arrayOf(AuctionRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "findById" -> if (args?.get(0) == entity.id) Optional.of(entity) else Optional.empty<AuctionEntity>()
            else -> when (method.returnType) {
                java.util.List::class.java -> emptyList<Any>()
                else -> null
            }
        }
    } as AuctionRepository

    private fun bidRepo(bids: List<AuctionBidEntity> = emptyList()): AuctionBidRepository = Proxy.newProxyInstance(
        AuctionBidRepository::class.java.classLoader,
        arrayOf(AuctionBidRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "findByAuctionIdOrderByAmountDesc" -> bids.sortedByDescending { it.amount }
            else -> when (method.returnType) {
                java.util.List::class.java -> emptyList<Any>()
                else -> null
            }
        }
    } as AuctionBidRepository

    /** 복수 경매 프록시 — findById + findByFinishedFalseAndType (유니크 래퍼 부위 가드용). */
    private fun auctionRepoMulti(vararg entities: AuctionEntity): AuctionRepository = Proxy.newProxyInstance(
        AuctionRepository::class.java.classLoader,
        arrayOf(AuctionRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "findById" -> Optional.ofNullable(entities.firstOrNull { it.id == args?.get(0) })
            "findByFinishedFalseAndType" -> entities.filter { !it.finished && it.type == args?.get(0) }
            else -> when (method.returnType) {
                java.util.List::class.java -> emptyList<Any>()
                else -> null
            }
        }
    } as AuctionRepository

    /** 경매별 입찰 프록시 — auctionId 인자를 실제 키로 쓴다(복수 경매 시나리오용). */
    private fun bidRepoByAuction(bids: Map<Int, List<AuctionBidEntity>>): AuctionBidRepository = Proxy.newProxyInstance(
        AuctionBidRepository::class.java.classLoader,
        arrayOf(AuctionBidRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "findByAuctionIdOrderByAmountDesc" ->
                (bids[args?.get(0)] ?: emptyList<AuctionBidEntity>()).sortedByDescending { it.amount }
            else -> when (method.returnType) {
                java.util.List::class.java -> emptyList<Any>()
                else -> null
            }
        }
    } as AuctionBidRepository

    private fun handler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        auction: AuctionEntity,
        dbBids: List<AuctionBidEntity> = emptyList(),
        points: Map<Int, Double> = emptyMap(),
    ) = AuctionBidHandler(
        world, recorder, auctionRepo(auction), bidRepo(dbBids),
        previousPointReader = { ownerId -> points[ownerId] ?: 0.0 },
    )

    private fun cmd(generalId: Int, amount: Int) = TurnDaemonCommand.AuctionBid(
        requestId = "r", auctionId = 5, generalId = generalId, amount = amount,
    )

    private fun failReason(result: Any?): String = (result as AuctionBidFail).reason

    // ── (i) 첫 입찰 = 전액 차감 (Auction.php:405 — myPrevBid 없음 → morePoint = amount) ─────────

    @Test
    fun `fresh bid deducts the FULL amount and records the bid row with owner`() {
        val world = world(general(20, "관우", gold = 5000, userId = 66))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, resourceAuction())

        val result = h.handle(cmd(generalId = 20, amount = 1100))

        assertTrue(result is AuctionBidOk)
        // 전액 1100 차감 (morePoint = 1100 - 0).
        assertEquals(3900, world.getGeneralById(20)!!.gold)
        // ng_auction_bid INSERT — owner = 소유 유저(PHP Auction.php:421 `getVar('owner')`).
        val cols = recorder.auctionBidInserts().single().columns
        assertEquals(5, cols["auction_id"])
        assertEquals(66, cols["owner"])
        assertEquals(20, cols["general_id"])
        assertEquals(1100, cols["amount"])
        // 먼 미래 마감 → 연장 미발화 → ng_auction UPDATE 없음 (PHP :445-448 — 변할 때만 applyDB).
        assertTrue(recorder.auctionUpserts().isEmpty())
        // PHP bid 경로는 무로그(바퀴 23) — 위조 로그 금지.
        assertTrue(world.consumeDirtyState().logs.isEmpty())
    }

    @Test
    fun `sellRice auction deducts rice not gold`() {
        val world = world(general(20, "관우", gold = 5000, rice = 5000, userId = 66))
        val recorder = ChangeRecorder()
        val h = handler(
            world, recorder,
            resourceAuction(type = AuctionType.SELL_RICE, reqResource = ResourceType.RICE),
        )

        assertTrue(h.handle(cmd(generalId = 20, amount = 1100)) is AuctionBidOk)
        assertEquals(3900, world.getGeneralById(20)!!.rice)
        assertEquals(5000, world.getGeneralById(20)!!.gold)
    }

    // ── (ii) 타인 상회입찰 → 직전 최고가 1회 전액 환불 + 신규 전액 차감 (Auction.php:450-452) ──

    @Test
    fun `outbid by another general refunds the previous bidder exactly once and charges full`() {
        val world = world(
            general(10, "유비", gold = 5000, userId = 55),
            general(20, "관우", gold = 4000, userId = 66),
        )
        val recorder = ChangeRecorder()
        // DB: 관우(20)가 1000으로 현재 최고가.
        val h = handler(world, recorder, resourceAuction(), dbBids = listOf(bidRow(1, 20, 1000, owner = 66)))

        assertTrue(h.handle(cmd(generalId = 10, amount = 1100)) is AuctionBidOk)

        // 유비: 전액 1100 차감. 관우: 1000 전액 환불(refundBid :228) — 정확히 1회.
        assertEquals(3900, world.getGeneralById(10)!!.gold)
        assertEquals(5000, world.getGeneralById(20)!!.gold)
    }

    // ── (iii) 자기 상회입찰 → 환불 없음 + 차액만 차감 (Auction.php:399-405,437,450) ─────────────

    @Test
    fun `self re-raise deducts only the difference and refunds NOTHING`() {
        // 종전 구현(무조건 환불)은 본인 에스크로 1000이 환급돼 +900 복제가 일어났다 — 회귀 가드.
        val world = world(general(10, "유비", gold = 5000, userId = 55))
        val recorder = ChangeRecorder()
        // DB: 유비 본인이 1000으로 현재 최고가 (highestBid.no == myPrevBid.no → 유효 에스크로).
        val h = handler(world, recorder, resourceAuction(), dbBids = listOf(bidRow(1, 10, 1000, owner = 55)))

        assertTrue(h.handle(cmd(generalId = 10, amount = 1100)) is AuctionBidOk)

        // morePoint = 1100 - 1000 = 100 만 차감. 환불 0.
        assertEquals(4900, world.getGeneralById(10)!!.gold)
    }

    // ── (v) stale 이전입찰(이미 환불됨)은 다시는 환불·차액 기준이 되지 않는다 (Auction.php:399-403) ──

    @Test
    fun `already-refunded previous bid is invalidated - full charge, no double refund`() {
        // DB: 유비 1000(no=1, 관우 1100에 밀려 이미 환불된 행) / 관우 1100(no=2, 현재 최고가).
        // 종전 구현은 stale myPrevBid(1000)로 morePoint=300 만 차감(1000 미달) — 회귀 가드.
        val world = world(
            general(10, "유비", gold = 5000, userId = 55),
            general(20, "관우", gold = 4000, userId = 66),
        )
        val recorder = ChangeRecorder()
        val h = handler(
            world, recorder, resourceAuction(),
            dbBids = listOf(bidRow(1, 10, 1000, owner = 55), bidRow(2, 20, 1100, owner = 66)),
        )

        assertTrue(h.handle(cmd(generalId = 10, amount = 1300)) is AuctionBidOk)

        // 유비: R1 무효화 → 전액 1300 차감 (stale 차액 300이 아니라).
        assertEquals(3700, world.getGeneralById(10)!!.gold)
        // 관우: 현재 최고가 1100 전액 1회 환불. (유비의 no=1 행은 재환불되지 않는다.)
        assertEquals(5100, world.getGeneralById(20)!!.gold)
    }

    // ── (iv) 같은 런 핑퐁 — 자원 총량 보존 (R4 에스크로 불변식) ────────────────────────────────

    @Test
    fun `same-run outbid ping-pong conserves total gold - escrow equals final highest bid`() {
        val world = world(
            general(10, "유비", gold = 5000, userId = 55),
            general(20, "관우", gold = 5000, userId = 66),
        )
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, resourceAuction(), dbBids = emptyList())

        // PHP는 bid마다 즉시 INSERT(:432) — pending 입찰 merge가 같은 런 정합을 보장해야 한다.
        assertTrue(h.handle(cmd(10, 1000)) is AuctionBidOk) // 유비 -1000
        assertTrue(h.handle(cmd(20, 1100)) is AuctionBidOk) // 관우 -1100, 유비 +1000
        assertTrue(h.handle(cmd(10, 1300)) is AuctionBidOk) // 유비 -1300(전액, stale 무효), 관우 +1100
        assertTrue(h.handle(cmd(20, 1500)) is AuctionBidOk) // 관우 -1500(전액), 유비 +1300

        val goldA = world.getGeneralById(10)!!.gold
        val goldB = world.getGeneralById(20)!!.gold
        // 최종 최고 입찰자(관우)만 1500 에스크로, 유비 순지불 0.
        assertEquals(5000, goldA)
        assertEquals(3500, goldB)
        // 총량 보존: before(10000) == after(8500) + 에스크로(1500).
        assertEquals(10000, goldA + goldB + 1500)
        // 4건 모두 기록.
        assertEquals(4, recorder.auctionBidInserts().size)
    }

    // ── 검증 거부 (PHP verbatim) ──────────────────────────────────────────────────────────────

    @Test
    fun `gold shortfall uses morePoint plus default reserve`() {
        // PHP :412 `getVar(res) < morePoint + GameConst::$defaultGold(1000)` → '금이 부족합니다.'
        val short = world(general(20, "관우", gold = 2099, userId = 66))
        val h1 = handler(short, ChangeRecorder(), resourceAuction())
        assertEquals("금이 부족합니다.", failReason(h1.handle(cmd(20, 1100))))

        // 정확히 2100(=1100+1000)이면 통과.
        val exact = world(general(20, "관우", gold = 2100, userId = 66))
        val h2 = handler(exact, ChangeRecorder(), resourceAuction())
        assertTrue(h2.handle(cmd(20, 1100)) is AuctionBidOk)
    }

    @Test
    fun `bid above finish amount denied`() {
        // PHP :369-370 '즉시판매가보다 높을 수 없습니다.'
        val h = handler(world(general(20, "관우", userId = 66)), ChangeRecorder(), resourceAuction())
        assertEquals("즉시판매가보다 높을 수 없습니다.", failReason(h.handle(cmd(20, 1501))))
    }

    @Test
    fun `bid not above current highest denied`() {
        // PHP :389-390 '현재입찰가보다 높게 입찰해야 합니다.' (strict 초과).
        val h = handler(
            world(general(20, "관우", userId = 66)), ChangeRecorder(), resourceAuction(),
            dbBids = listOf(bidRow(1, 30, 1000)),
        )
        assertEquals("현재입찰가보다 높게 입찰해야 합니다.", failReason(h.handle(cmd(20, 1000))))
    }

    @Test
    fun `host cannot bid own resource auction`() {
        // AuctionBasicResource.php:235-237 — finished 검사보다도 앞.
        val h = handler(world(general(10, "유비", userId = 55)), ChangeRecorder(), resourceAuction(hostGeneralId = 10))
        assertEquals("자신이 연 경매에 입찰할 수 없습니다.", failReason(h.handle(cmd(10, 1100))))
    }

    @Test
    fun `finished auction denied with PHP verbatim`() {
        // PHP _bid :355-357 '경매가 이미 끝났습니다.'
        val h = handler(world(general(20, "관우", userId = 66)), ChangeRecorder(), resourceAuction(finished = true))
        assertEquals("경매가 이미 끝났습니다.", failReason(h.handle(cmd(20, 1100))))
    }

    // ── close_date — 연장은 무조건·detail 불변, 즉시거래는 단축 ─────────────────────────────────

    @Test
    fun `per-bid extension updates close_date WITHOUT touching remainCloseDateExtensionCnt`() {
        // 종전 구현은 bid마다 remainCloseDateExtensionCnt를 -1 했다(null→-1 부활 포함) —
        // PHP extendCloseDate(force=true)는 카운트를 우회한다(Auction.php:182-194). 회귀 가드.
        val world = world(general(20, "관우", userId = 66))
        val recorder = ChangeRecorder()
        // 마감이 가깝다(now+1분) → 연장(now+60분 초과분) 발화. 과거 마감은 wall-clock 거부
        // (Auction.php:361-363) — 별도 테스트에서 핀.
        val h = handler(world, recorder, resourceAuction(closeDate = Instant.now().plusSeconds(60)))

        val ok = h.handle(cmd(20, 1100)) as AuctionBidOk

        val cols = recorder.auctionUpserts().single().columns
        assertEquals(ok.closeAt, cols["close_date"])
        val detail = jsonDecode(cols["detail"] as String)
        // detail 불변 — 카운트 키는 입력에 없었으니 출력에도 없어야 한다(-1 부활 금지).
        assertTrue(!detail.containsKey("remainCloseDateExtensionCnt"))
        assertTrue(Instant.parse(ok.closeAt) > t0)
    }

    @Test
    fun `instant-buy at finishBidAmount shrinks close_date to one turn`() {
        // AuctionBasicResource.php:244-251 — amount == finishBidAmount → shrinkCloseDate(now+1턴).
        val world = world(general(20, "관우", gold = 5000, userId = 66))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, resourceAuction(closeDate = farClose, finishBidAmount = 1500))

        val ok = h.handle(cmd(20, 1500)) as AuctionBidOk

        // 먼 미래 마감이 now+60분으로 단축됐다.
        assertTrue(Instant.parse(ok.closeAt) < farClose)
        assertEquals(1, recorder.auctionUpserts().size)
        assertEquals(3500, world.getGeneralById(20)!!.gold)
    }

    // ── 유산포인트(유니크) 경매 — KV 'previous' + rank, 동일 R1-R4 (Auction.php:278-348) ───────

    @Test
    fun `unique fresh bid sets previous KV minus full amount and bumps inherit_spent_dyn`() {
        val world = world(general(10, "유비", userId = 55))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, uniqueAuction(), points = mapOf(55 to 10_000.0))

        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk)

        // PHP :340 increaseInheritancePoint(previous, -6000) — 바퀴 20 정본 seam(SET previous).
        val kv = recorder.inheritanceKvWrites().single()
        assertEquals("inheritance", kv.table) // V15 판별자 — 'game_kv' 오기록이면 reader와 어긋남
        assertEquals("inheritance_55", kv.namespace)
        assertEquals("previous", kv.key)
        assertEquals(listOf(4000.0, null), kv.value)
        // PHP :341 rank inherit_point_spent_dynamic += morePoint.
        assertEquals(RankDelta.Increment(6000), recorder.rankDeltas(10)[RankColumn.INHERIT_SPENT_DYN])
        // 금/쌀 불변.
        assertEquals(5000, world.getGeneralById(10)!!.gold)
        // owner = 유저 id.
        assertEquals(55, recorder.auctionBidInserts().single().columns["owner"])
    }

    @Test
    fun `unique outbid refunds previous owner KV in full exactly once`() {
        val world = world(
            general(10, "유비", userId = 55),
            general(20, "관우", userId = 66),
        )
        val recorder = ChangeRecorder()
        // DB: 관우(owner 66)가 6000으로 현재 최고가.
        val h = handler(
            world, recorder, uniqueAuction(),
            dbBids = listOf(bidRow(1, 20, 6000, owner = 66)),
            points = mapOf(55 to 10_000.0, 66 to 8_000.0),
        )

        assertTrue(h.handle(cmd(10, 6100)) is AuctionBidOk)

        val writes = recorder.inheritanceKvWrites()
        assertEquals(2, writes.size)
        // 차감 먼저 (PHP :340), 환불 다음 (:343-345 refundBid :224-225).
        assertEquals("inheritance_55", writes[0].namespace)
        assertEquals(listOf(3900.0, null), writes[0].value) // 10000 - 6100 전액
        assertEquals("inheritance_66", writes[1].namespace)
        assertEquals(listOf(14_000.0, null), writes[1].value) // 8000 + 6000 전액 환불
        // rank: 입찰자 +6100 (:341), 피환불자 -6000 (refundBid :226).
        assertEquals(RankDelta.Increment(6100), recorder.rankDeltas(10)[RankColumn.INHERIT_SPENT_DYN])
        assertEquals(RankDelta.Increment(-6000), recorder.rankDeltas(20)[RankColumn.INHERIT_SPENT_DYN])
    }

    @Test
    fun `unique self re-raise deducts only the difference with no refund`() {
        val world = world(general(10, "유비", userId = 55))
        val recorder = ChangeRecorder()
        // DB: 유비 본인이 6000으로 현재 최고가 (잔액 10000은 이미 6000 에스크로 차감 후 값).
        val h = handler(
            world, recorder, uniqueAuction(),
            dbBids = listOf(bidRow(1, 10, 6000, owner = 55)),
            points = mapOf(55 to 10_000.0),
        )

        assertTrue(h.handle(cmd(10, 6100)) is AuctionBidOk)

        // morePoint = 100 만 차감 — 환불 KV 없음(단일 write).
        val kv = recorder.inheritanceKvWrites().single()
        assertEquals("inheritance_55", kv.namespace)
        assertEquals(listOf(9900.0, null), kv.value)
        assertEquals(RankDelta.Increment(100), recorder.rankDeltas(10)[RankColumn.INHERIT_SPENT_DYN])
    }

    @Test
    fun `unique same-run ping-pong conserves inheritance points`() {
        val world = world(
            general(10, "유비", userId = 55),
            general(20, "관우", userId = 66),
        )
        val recorder = ChangeRecorder()
        val h = handler(
            world, recorder, uniqueAuction(),
            points = mapOf(55 to 10_000.0, 66 to 20_000.0),
        )

        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk) // 유비 -6000
        assertTrue(h.handle(cmd(20, 6100)) is AuctionBidOk) // 관우 -6100, 유비 +6000
        assertTrue(h.handle(cmd(10, 6300)) is AuctionBidOk) // 유비 -6300(전액), 관우 +6100

        // 네임스페이스별 마지막 write = 유효 잔액 (same-run pending 우선 read의 결과).
        val last55 = recorder.inheritanceKvWrites().last { it.namespace == "inheritance_55" }
        val last66 = recorder.inheritanceKvWrites().last { it.namespace == "inheritance_66" }
        assertEquals(listOf(3700.0, null), last55.value)  // 10000 - 6300 (최종 에스크로)
        assertEquals(listOf(20_000.0, null), last66.value) // 순지불 0
        // 보존: before(30000) == after(3700+20000) + 에스크로(6300).
        assertEquals(30_000.0, 3700.0 + 20_000.0 + 6300.0)
    }

    @Test
    fun `unique raise gates use PHP verbatim messages`() {
        val world = world(general(10, "유비", userId = 55))
        // 1%: 6059 < 6000*1.01(6060) (Auction.php:286-288).
        val h1 = handler(
            world, ChangeRecorder(), uniqueAuction(),
            dbBids = listOf(bidRow(1, 20, 6000, owner = 66)), points = mapOf(55 to 100_000.0),
        )
        assertEquals("현재입찰가보다 1% 높게 입찰해야 합니다.", failReason(h1.handle(cmd(10, 6059))))
        // +10: 105 >= 100*1.01(101) 통과, 105 < 110 (Auction.php:289-291).
        val h2 = handler(
            world, ChangeRecorder(), uniqueAuction(),
            dbBids = listOf(bidRow(1, 20, 100, owner = 66)), points = mapOf(55 to 100_000.0),
        )
        assertEquals("현재입찰가보다 10 포인트 높게 입찰해야 합니다.", failReason(h2.handle(cmd(10, 105))))
    }

    @Test
    fun `unique insufficient point and npc without owner denied`() {
        // PHP :300-303 — currPoint null(저장소 부재 = NPC) 또는 morePoint 미달 → '유산포인트가 부족합니다.'
        val short = handler(
            world(general(10, "유비", userId = 55)), ChangeRecorder(), uniqueAuction(),
            points = mapOf(55 to 50.0),
        )
        assertEquals("유산포인트가 부족합니다.", failReason(short.handle(cmd(10, 6000))))

        val npc = handler(
            world(general(10, "유비", userId = null)), ChangeRecorder(), uniqueAuction(),
            points = mapOf(55 to 100_000.0),
        )
        assertEquals("유산포인트가 부족합니다.", failReason(npc.handle(cmd(10, 6000))))
    }

    // ── 바퀴 26 재채점 반영 — 유니크 래퍼 부위 가드 (AuctionUniqueItem.php:140-226) ─────────────

    @Test
    fun `unique bid rejected when same part already equips a non-buyable item`() {
        // :190-195 — 명마 부위에 비구매성(절영, allItems cnt>0 ⟺ !isBuyable) 장착 중 → 거부.
        val world = world(general(10, "유비", userId = 55, items = GeneralItems(horse = "che_명마_13_절영")))
        val h = handler(world, ChangeRecorder(), uniqueAuction(), points = mapOf(55 to 100_000.0))
        assertEquals("이미 가진 아이템이 있습니다.", failReason(h.handle(cmd(10, 6000))))
    }

    @Test
    fun `unique bid allowed when same part equips only a buyable item`() {
        // 구매성(노기, cnt 0 = isBuyable) 장착은 가드를 발화하지 않는다 (:193-195).
        val world = world(general(10, "유비", userId = 55, items = GeneralItems(horse = "che_명마_01_노기")))
        val h = handler(world, ChangeRecorder(), uniqueAuction(), points = mapOf(55 to 100_000.0))
        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk)
    }

    @Test
    fun `unique bid rejected when first bidder on another open auction of same part`() {
        // :202-226 — 다른 열린 유니크 경매(적로, 같은 명마 부위)의 1순위 입찰자 → 거부.
        val world = world(general(10, "유비", userId = 55))
        val target = uniqueAuction(id = 5, target = "che_명마_15_적토마")
        val other = uniqueAuction(id = 7, target = "che_명마_13_적로")
        val h = AuctionBidHandler(
            world, ChangeRecorder(),
            auctionRepoMulti(target, other),
            bidRepoByAuction(mapOf(7 to listOf(bidRow(1, 10, 6000, owner = 55, auctionId = 7)))),
            previousPointReader = { 100_000.0 },
        )
        assertEquals("1순위 입찰자인 경매중에 같은 부위가 있습니다.", failReason(h.handle(cmd(10, 6000))))
    }

    @Test
    fun `unique bid allowed when outbid on the other same-part auction`() {
        // 다른 경매 1순위가 내가 아니면(밀려남) 가드 미발화 (:216-218).
        val world = world(general(10, "유비", userId = 55))
        val target = uniqueAuction(id = 5, target = "che_명마_15_적토마")
        val other = uniqueAuction(id = 7, target = "che_명마_13_적로")
        val h = AuctionBidHandler(
            world, ChangeRecorder(),
            auctionRepoMulti(target, other),
            bidRepoByAuction(
                mapOf(
                    7 to listOf(
                        bidRow(1, 10, 6000, owner = 55, auctionId = 7),
                        bidRow(2, 20, 7000, owner = 66, auctionId = 7), // 관우가 상회 — 유비는 1순위 아님
                    ),
                ),
            ),
            previousPointReader = { 100_000.0 },
        )
        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk)
    }

    @Test
    fun `unique finished message differs from resource path`() {
        // AuctionUniqueItem.php:143-144 — 래퍼 자체 검사 '경매가 종료되었습니다.'
        // (_bid 의 '경매가 이미 끝났습니다.'(:355-357)가 아니라).
        val h = handler(
            world(general(10, "유비", userId = 55)), ChangeRecorder(), uniqueAuction(finished = true),
            points = mapOf(55 to 100_000.0),
        )
        assertEquals("경매가 종료되었습니다.", failReason(h.handle(cmd(10, 6000))))
    }

    // ── 바퀴 26 재채점 반영 — wall-clock / aux / isunited / 환불 owner 재해석 ────────────────────

    @Test
    fun `wall-clock rejects bids on expired or not-yet-open auctions`() {
        // Auction.php:361-366 — closeDate < now → '경매가 이미 끝났습니다.', openDate > now →
        // '경매가 아직 시작되지 않았습니다.' (마감 경과~AuctionExpiryDaemon 틱 사이 창 차단).
        val expired = handler(world(general(20, "관우", userId = 66)), ChangeRecorder(), resourceAuction(closeDate = t0))
        assertEquals("경매가 이미 끝났습니다.", failReason(expired.handle(cmd(20, 1100))))

        val notOpen = handler(
            world(general(20, "관우", userId = 66)), ChangeRecorder(),
            resourceAuction(openDate = farClose, closeDate = farClose),
        )
        assertEquals("경매가 아직 시작되지 않았습니다.", failReason(notOpen.handle(cmd(20, 1100))))
    }

    @Test
    fun `aux records ownerName and obfuscated bidder name for unique auctions`() {
        // Auction.php:316 owner_name(aux 첫 필드) + :305 genObfuscatedName — hiddenSeed 풀 디코드.
        val world = world(
            general(10, "유비", userId = 55, meta = mapOf("owner_name" to "현덕")),
            worldMeta = mapOf("hiddenSeed" to "test-seed"),
        )
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, uniqueAuction(), points = mapOf(55 to 100_000.0))
        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk)

        val aux = jsonDecode(recorder.auctionBidInserts().single().columns["aux"] as String)
        assertEquals("현덕", aux["ownerName"])
        val expected = ObfuscatedNamePool.decode(
            10,
            ObfuscatedNamePool.buildPool(serializeSeed("test-seed", ObfuscatedNamePool.KV_KEY)),
        )
        assertEquals(expected, aux["generalName"]) // 실명 '유비' 누출 금지
        assertEquals(false, aux["tryExtendCloseDate"]) // BidUniqueAuction.php:41 — 미지정 기본 false
    }

    @Test
    fun `resource bid forces tryExtendCloseDate true ignoring wire false`() {
        // BidBuyRiceAuction.php:44 — 자원 경매 API 는 클라이언트 입력 무시, 항상 true 하드코딩.
        val recorder = ChangeRecorder()
        val h = handler(world(general(20, "관우", userId = 66)), recorder, resourceAuction())
        val command = TurnDaemonCommand.AuctionBid(
            requestId = "r", auctionId = 5, generalId = 20, amount = 1100, tryExtendCloseDate = false,
        )
        assertTrue(h.handle(command) is AuctionBidOk)
        val aux = jsonDecode(recorder.auctionBidInserts().single().columns["aux"] as String)
        assertEquals(true, aux["tryExtendCloseDate"])
        assertEquals("관우", aux["generalName"]) // 자원 경매는 실명 getName()(:427)
    }

    @Test
    fun `isunited world records NO inheritance KV but still bumps rank`() {
        // InheritancePointManager.php:241-244 — 통일 후 KV 증감 전면 무기록; rank 는 별도
        // 문장이라 게이트 밖(Auction.php:341).
        val world = world(general(10, "유비", userId = 55), worldMeta = mapOf("isunited" to 2))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, uniqueAuction(), points = mapOf(55 to 100_000.0))
        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk)
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
        assertEquals(RankDelta.Increment(6000), recorder.rankDeltas(10)[RankColumn.INHERIT_SPENT_DYN])
    }

    @Test
    fun `unique refund resolves CURRENT owner not the stale bid-row snapshot`() {
        // Auction.php:218-221 — refundBid 는 createObjFromDB 로 환불 시점의 현재 general 행에서
        // owner 를 재해석한다. 행 owner=66 은 stale, 현재 owner=77.
        val world = world(
            general(10, "유비", userId = 55),
            general(20, "관우", userId = 77), // 입찰 후 소유 유저가 66→77로 바뀐 상황
        )
        val recorder = ChangeRecorder()
        val h = handler(
            world, recorder, uniqueAuction(),
            dbBids = listOf(bidRow(1, 20, 6000, owner = 66)),
            points = mapOf(55 to 100_000.0, 77 to 1_000.0),
        )
        assertTrue(h.handle(cmd(10, 6100)) is AuctionBidOk)
        val refund = recorder.inheritanceKvWrites().last()
        assertEquals("inheritance_77", refund.namespace) // 66(stale) 금지
        assertEquals(listOf(7_000.0, null), refund.value)
    }

    @Test
    fun `unique refund is a full no-op when the old bidder no longer exists`() {
        // Auction.php:231-233 DummyGeneral — KV/rank 모두 무기록.
        val world = world(general(10, "유비", userId = 55)) // 99는 월드에 없다
        val recorder = ChangeRecorder()
        val h = handler(
            world, recorder, uniqueAuction(),
            dbBids = listOf(bidRow(1, 99, 6000, owner = 66)),
            points = mapOf(55 to 100_000.0),
        )
        assertTrue(h.handle(cmd(10, 6100)) is AuctionBidOk)
        // 차감 1건만 — 환불 KV/rank 없음.
        assertEquals(listOf("inheritance_55"), recorder.inheritanceKvWrites().map { it.namespace })
        assertTrue(recorder.rankDeltas(99).isEmpty())
    }

    @Test
    fun `npc bidder with userId is denied by zero point`() {
        // InheritancePointManager.php:109-116 — npc>=2 면 getInheritancePoint=0 →
        // '유산포인트가 부족합니다.'(Auction.php:300-303). KV 차감 스킵+수락(무비용 입찰) 금지.
        val h = handler(
            world(general(10, "여포", userId = 55, npc = 2)), ChangeRecorder(), uniqueAuction(),
            points = mapOf(55 to 100_000.0),
        )
        assertEquals("유산포인트가 부족합니다.", failReason(h.handle(cmd(10, 6000))))
    }

    @Test
    fun `unique bid without hiddenSeed uses neutral placeholder not the real name`() {
        // PHP 유니크 경로는 어떤 경우에도 실명을 aux 에 쓰지 않는다(Auction.php:305) —
        // 시드 미주입 월드 폴백도 AuctionOpenHandler '(상인)' 동형.
        val recorder = ChangeRecorder()
        val h = handler(
            world(general(10, "유비", userId = 55)), recorder, uniqueAuction(),
            points = mapOf(55 to 100_000.0),
        )
        assertTrue(h.handle(cmd(10, 6000)) is AuctionBidOk)
        val aux = jsonDecode(recorder.auctionBidInserts().single().columns["aux"] as String)
        assertEquals("(상인)", aux["generalName"])
    }

    @Test
    fun `unique refund skips KV for npc bidder but still adjusts rank`() {
        // InheritancePointManager.php:264-267 npc>=2 무기록; rank 보정은 실존 장수면 무조건(Auction.php:226).
        val world = world(
            general(10, "유비", userId = 55),
            general(20, "여포", userId = 66, npc = 2),
        )
        val recorder = ChangeRecorder()
        val h = handler(
            world, recorder, uniqueAuction(),
            dbBids = listOf(bidRow(1, 20, 6000, owner = 66)),
            points = mapOf(55 to 100_000.0),
        )
        assertTrue(h.handle(cmd(10, 6100)) is AuctionBidOk)
        // KV: 차감(55) 1건만 — 66 환불 KV 없음.
        assertEquals(listOf("inheritance_55"), recorder.inheritanceKvWrites().map { it.namespace })
        // rank 보정은 기록된다.
        assertEquals(RankDelta.Increment(-6000), recorder.rankDeltas(20)[RankColumn.INHERIT_SPENT_DYN])
    }
}
