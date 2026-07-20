package opensamguk.engine.betting

import opensamguk.common.wire.PlaceBetFail
import opensamguk.common.wire.PlaceBetOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.SelectItem
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0-07 — [PlaceBetHandler]의 PHP `Betting::bet()`(Betting.php:100-183) 패러티 테스트.
 *
 * 검증/메시지/부수효과의 출처는 전부 PHP 소스 인용:
 *  - 마스터 부재 '해당 베팅이 없습니다: {id}' (Betting.php:45-47)
 *  - finished '이미 종료된 베팅입니다' (:104-110)
 *  - 마감 '이미 마감된 베팅입니다' / 미시작 '아직 시작되지 않은 베팅입니다' (:118-124,
 *    ym = Util::joinYearMonth = year*12+month-1, Util.php:709-711)
 *  - 선택 수 '필요한 선택 수를 채우지 못했습니다.' (:126-128)
 *  - purify '중복된 값이 있습니다.' / '올바른 후보가 아닙니다.'+print_r (:56-74)
 *  - 누적 1000 '{잔여}{금|유산포인트}까지만 베팅 가능합니다.' (:135-139)
 *  - '유산포인트가 충분하지 않습니다.' (:141-145) / '금이 부족합니다.'(500 예치, :146-151,
 *    GameConstBase.php:231)
 *  - 부수효과: insertUpdate + previous/[UserLogger inheritPoint]/inherit_spent_dyn 또는
 *    gold 차감/betgold (:162-182). PHP에 장수 액션 로그 push는 없다.
 */
class PlaceBetHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    // currentYear=200, currentMonth=3 → joinYearMonth = 200*12+3-1 = 2402.
    private fun state() =
        TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0, meta = emptyMap())

    private fun general(
        id: Int = 10,
        gold: Int = 2000,
        userId: Int? = 55,
    ) = TurnGeneral(
        id = id, name = "유비", nationId = 1, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = 12, gold = gold, turnTime = t0,
        role = opensamguk.engine.turn.GeneralRole(),
        // 소유 유저 = typed userId 채널 (PHP $session->userID 동형; NPC는 null).
        userId = userId?.toString(),
    )

    private fun world(general: TurnGeneral = general()): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = state(),
            generals = listOf(general),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000, meta = emptyMap())),
            worldId = opensamguk.common.world.WorldId((state()).id),
        ),
    )

    private fun master(
        id: Int = 7,
        finished: Boolean = false,
        selectCnt: Int = 2,
        reqInheritancePoint: Boolean = false,
        openYearMonth: Int = 2400,
        closeYearMonth: Int = 2500,
        candidates: Map<Int, SelectItem> = mapOf(1 to SelectItem("위"), 3 to SelectItem("오")),
    ) = BettingInfo(
        id = id, type = "bettingNation", name = "천통 베팅", finished = finished,
        selectCnt = selectCnt, isExclusive = null, reqInheritancePoint = reqInheritancePoint,
        openYearMonth = openYearMonth, closeYearMonth = closeYearMonth, candidates = candidates,
    )

    private fun handler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        info: BettingInfo? = master(),
        prevDb: Int = 0,
        previousPoint: Double = 0.0,
    ) = PlaceBetHandler(
        world, recorder,
        bettingInfoReader = { id -> info?.takeIf { it.id == id } },
        prevBetAmountDbReader = { _, _ -> prevDb },
        previousPointReader = { previousPoint },
    )

    private fun cmd(
        bettingId: Int = 7,
        generalId: Int = 10,
        bettingType: List<Int> = listOf(1, 3),
        amount: Int = 100,
    ) = TurnDaemonCommand.PlaceBet(
        requestId = "r", bettingId = bettingId, generalId = generalId,
        bettingType = bettingType, amount = amount,
    )

    private fun failReason(handlerResult: Any?): String = (handlerResult as PlaceBetFail).reason

    // ── 검증 게이트 (PHP 순서) ────────────────────────────────────────────────

    @Test
    fun `amount below API validator min 10 denied before master lookup`() {
        // PHP Bet.php:30 `min 10` — valitron '{field} ' prepend + lang/ko.php 'min' + ucwords('amount').
        // 마스터 reader가 호출되면 안 된다(검증이 Betting 생성자보다 앞 — PHP 관측 순서).
        val h = PlaceBetHandler(
            world(), ChangeRecorder(),
            bettingInfoReader = { error("validator가 마스터 조회보다 앞이어야 함") },
            prevBetAmountDbReader = { _, _ -> 0 },
            previousPointReader = { 0.0 },
        )
        assertEquals("Amount 은(는) 10 이상이어야 합니다.", failReason(h.handle(cmd(amount = 9))))
        assertEquals("Amount 은(는) 10 이상이어야 합니다.", failReason(h.handle(cmd(amount = -500))))
    }

    @Test
    fun `master absent fails with PHP constructor message`() {
        val world = world()
        val h = handler(world, ChangeRecorder(), info = null)
        assertEquals("해당 베팅이 없습니다: 7", failReason(h.handle(cmd())))
    }

    @Test
    fun `finished betting denied`() {
        val world = world()
        val h = handler(world, ChangeRecorder(), info = master(finished = true))
        assertEquals("이미 종료된 베팅입니다", failReason(h.handle(cmd())))
    }

    @Test
    fun `closeYearMonth at-or-before current yearMonth denied`() {
        // ym = 2402; close == ym → '이미 마감'(PHP `<=`).
        val h = handler(world(), ChangeRecorder(), info = master(closeYearMonth = 2402))
        assertEquals("이미 마감된 베팅입니다", failReason(h.handle(cmd())))
    }

    @Test
    fun `openYearMonth after current yearMonth denied`() {
        val h = handler(world(), ChangeRecorder(), info = master(openYearMonth = 2403))
        assertEquals("아직 시작되지 않은 베팅입니다", failReason(h.handle(cmd())))
    }

    @Test
    fun `selectCnt mismatch denied`() {
        val h = handler(world(), ChangeRecorder())
        assertEquals("필요한 선택 수를 채우지 못했습니다.", failReason(h.handle(cmd(bettingType = listOf(1)))))
    }

    @Test
    fun `duplicate selections denied after sort-unique`() {
        val h = handler(world(), ChangeRecorder())
        assertEquals("중복된 값이 있습니다.", failReason(h.handle(cmd(bettingType = listOf(3, 3)))))
    }

    @Test
    fun `non-candidate key denied with print_r dump`() {
        val h = handler(world(), ChangeRecorder())
        // [9,1] → sort → [1,9]; 9는 후보가 아님. PHP print_r($arr, true) byte-동형.
        assertEquals(
            "올바른 후보가 아닙니다.Array\n(\n    [0] => 1\n    [1] => 9\n)\n",
            failReason(h.handle(cmd(bettingType = listOf(9, 1)))),
        )
    }

    // ── 누적 1000 한도 (:135-139) ─────────────────────────────────────────────

    @Test
    fun `cumulative limit uses db sum and resKey gold`() {
        val h = handler(world(), ChangeRecorder(), prevDb = 700)
        assertEquals("300금까지만 베팅 가능합니다.", failReason(h.handle(cmd(amount = 400))))
    }

    @Test
    fun `cumulative limit message uses inheritance resKey`() {
        val h = handler(world(), ChangeRecorder(), info = master(reqInheritancePoint = true), prevDb = 900)
        assertEquals("100유산포인트까지만 베팅 가능합니다.", failReason(h.handle(cmd(amount = 200))))
    }

    @Test
    fun `cumulative limit includes same-run pending bets`() {
        val world = world()
        val recorder = ChangeRecorder()
        val h = handler(world, recorder)

        assertTrue(h.handle(cmd(amount = 600)) is PlaceBetOk)
        // 두 번째 베팅은 pending 600을 누적으로 본다 → 잔여 400.
        assertEquals("400금까지만 베팅 가능합니다.", failReason(h.handle(cmd(amount = 600))))
    }

    @Test
    fun `npc without user_id accumulates nothing (PHP user_id=NULL matches no rows)`() {
        val world = world(general(userId = null))
        val h = PlaceBetHandler(
            world, ChangeRecorder(),
            bettingInfoReader = { master() },
            prevBetAmountDbReader = { _, _ -> error("user_id 없음 — DB 합산 호출되면 안 됨") },
            previousPointReader = { 0.0 },
        )
        assertTrue(h.handle(cmd(amount = 400)) is PlaceBetOk)
    }

    // ── 재원 검사 + 부수효과 ──────────────────────────────────────────────────

    @Test
    fun `gold branch requires minGoldRequiredWhenBetting deposit`() {
        // gold 1000 < 500 + 600 → 거부 (GameConstBase.php:231 = 500).
        val h = handler(world(general(gold = 1000)), ChangeRecorder())
        assertEquals("금이 부족합니다.", failReason(h.handle(cmd(amount = 600))))
    }

    @Test
    fun `gold branch success deducts gold, bumps betgold, records sorted insertUpdate, no general log`() {
        val world = world(general(gold = 1100))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder)

        val res = h.handle(cmd(bettingType = listOf(3, 1), amount = 600))

        val ok = res as PlaceBetOk
        assertEquals(7, ok.bettingId)
        assertEquals(500, world.getGeneralById(10)!!.gold)
        assertEquals(RankDelta.Increment(600), recorder.rankDeltas(10)[RankColumn.BETGOLD])
        val insert = recorder.bettingInserts().single()
        assertEquals(7, insert.columns["betting_id"])
        assertEquals(10, insert.columns["general_id"])
        assertEquals(55, insert.columns["user_id"])
        assertEquals("[1,3]", insert.columns["betting_type"]) // purify가 정렬(:59)
        assertEquals(600, insert.columns["amount"])
        // PHP bet()엔 장수 액션 로그 push가 없다 — 비패러티 로그 금지.
        assertTrue(world.consumeDirtyState().logs.isEmpty())
        // 금 베팅은 inheritance 채널을 건드리지 않는다.
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
        assertTrue(recorder.inheritanceLogInserts().isEmpty())
    }

    @Test
    fun `inheritance second same-run bet sees the pending previous deduction`() {
        // PHP setValue('previous',[잔여,null])는 다음 bet()의 read에 즉시 가시(Betting.php:168→:142).
        val world = world(general(gold = 50, userId = 77))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, info = master(reqInheritancePoint = true), previousPoint = 500.0)

        assertTrue(h.handle(cmd(amount = 300)) is PlaceBetOk)
        // 잔여 200 < 300 → pending previous([200.0,null])를 본다(베이스 500 재독이면 잘못 통과).
        assertEquals("유산포인트가 충분하지 않습니다.", failReason(h.handle(cmd(amount = 300))))
        // 누적 KvWrite 최종값 = [200.0, null] (두 번째 베팅은 기록 안 됨).
        assertEquals(listOf(200.0, null), recorder.inheritanceKvWrites().last().value)
    }

    @Test
    fun `inheritance branch checks previous points`() {
        val h = handler(world(), ChangeRecorder(), info = master(reqInheritancePoint = true), previousPoint = 200.0)
        assertEquals("유산포인트가 충분하지 않습니다.", failReason(h.handle(cmd(amount = 300))))
    }

    @Test
    fun `inheritance branch without user_id denied`() {
        val world = world(general(userId = null))
        val h = handler(world, ChangeRecorder(), info = master(reqInheritancePoint = true), previousPoint = 999.0)
        assertEquals("유산포인트가 충분하지 않습니다.", failReason(h.handle(cmd(amount = 300))))
    }

    @Test
    fun `inheritance branch success writes previous, user log, inherit_spent_dyn, gold untouched`() {
        val world = world(general(gold = 50, userId = 77))
        val recorder = ChangeRecorder()
        val h = handler(world, recorder, info = master(reqInheritancePoint = true), previousPoint = 800.0)

        val res = h.handle(cmd(amount = 300))

        assertTrue(res is PlaceBetOk)
        // previous = [800-300, null] (Betting.php:168).
        val kv = recorder.inheritanceKvWrites().single()
        // "table" 판별자 = storage 이름 'inheritance' — 'game_kv' 오기록이면 reader와 영원히 어긋남(V15).
        assertEquals("inheritance", kv.table)
        assertEquals("inheritance_77", kv.namespace)
        assertEquals("previous", kv.key)
        assertEquals(listOf(500.0, null), kv.value)
        // UserLogger '{amount} 포인트를 베팅에 사용' tag inheritPoint (:169-171).
        val log = recorder.inheritanceLogInserts().single()
        assertEquals(77, log.ownerID)
        assertEquals("300 포인트를 베팅에 사용", log.text)
        assertEquals("inheritPoint", log.tag)
        // rank inherit_point_spent_dynamic += amount (:172-174).
        assertEquals(RankDelta.Increment(300), recorder.rankDeltas(10)[RankColumn.INHERIT_SPENT_DYN])
        // 유산포인트 베팅은 gold/betgold를 건드리지 않는다 (:167-175).
        assertEquals(50, world.getGeneralById(10)!!.gold)
        assertNull(recorder.rankDeltas(10)[RankColumn.BETGOLD])
        // ng_betting 기록은 동일.
        assertEquals(300, recorder.bettingInserts().single().columns["amount"])
    }
}
