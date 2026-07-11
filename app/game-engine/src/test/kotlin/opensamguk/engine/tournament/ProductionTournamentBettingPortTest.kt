package opensamguk.engine.tournament

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.tournament.TournamentEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductionTournamentBettingPortTest {

    private val t0 = Instant.parse("0200-03-01T00:00:00Z")

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 3,
                tickSeconds = 3600,
                lastTurnTime = t0,
                meta = mapOf("startYear" to 200, "hiddenSeed" to "seed"),
            ),
            generals = listOf(
                general(1, "유비", npc = 0, gold = 2000, userId = "11"),
                general(2, "관우", npc = 2, gold = 2000, userId = null),
                general(3, "장비", npc = 2, gold = 400, userId = null),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 0)),
        ),
    )

    private fun general(id: Int, name: String, npc: Int, gold: Int, userId: String?): TurnGeneral =
        TurnGeneral(
            id = id,
            name = name,
            nationId = 1,
            cityId = 1,
            troopId = 0,
            stats = GeneralStats(80 + id, 70 + id, 60 + id),
            experience = 0,
            dedication = 0,
            officerLevel = 1,
            gold = gold,
            turnTime = t0,
            npcState = npc,
            userId = userId,
            role = opensamguk.engine.turn.GeneralRole(),
        )

    private fun finalist(id: Int, name: String, group: Int, groupNo: Int): TournamentEntry =
        TournamentEntry(
            id = id,
            npc = 0,
            name = name,
            leadership = 80 + id,
            strength = 70 + id,
            intel = 60 + id,
            level = 10,
            group = group,
            groupNo = groupNo,
        )

    @Test
    fun `open creates betting master and eligible npc bet inserts via ChangeRecorder`() {
        val world = world()
        val recorder = ChangeRecorder()
        val port = ProductionTournamentBettingPort(
            world = world,
            recorder = recorder,
            bettingInfoReader = { null },
            bettingRowsReader = { emptyList() },
            nextBettingIdReader = { 6 },
            previousPointReader = { 0.0 },
        )

        val bettingId = port.open(
            type = 2,
            unitSeconds = 120,
            finalists = listOf(finalist(1, "유비", 20, 0), finalist(2, "관우", 20, 1)),
        )

        assertEquals(7, bettingId)
        assertEquals(7, recorder.kvDirty()[opensamguk.engine.turn.KvKey("game_env", "game_env", "last_betting_id")])
        assertEquals(7, recorder.kvDirty()[opensamguk.engine.turn.KvKey("game_env", "game_env", "last_tournament_betting_id")])

        val raw = recorder.kvDirty()[opensamguk.engine.turn.KvKey("betting", "betting", "id_7")] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val info = BettingInfo.fromKvMap(raw as Map<String, Any?>)!!
        assertEquals("tournament", info.type)
        assertEquals("일기토", info.name)
        assertEquals(2402, info.openYearMonth)
        assertEquals(2522, info.closeYearMonth)
        assertEquals(listOf(1, 2), info.candidates.keys.toList())
        assertEquals("무력: 71", info.candidates[1]!!.info)

        val inserts = recorder.bettingInserts()
        assertEquals(1, inserts.size)
        assertEquals(7, inserts.single().columns["betting_id"])
        assertEquals(2, inserts.single().columns["general_id"])
        assertEquals(null, inserts.single().columns["user_id"])
        val npcBetAmount = inserts.single().columns["amount"] as Int
        assertTrue(npcBetAmount >= 10)
        assertEquals(2000 - npcBetAmount, world.getGeneralById(2)!!.gold)
        assertEquals(400, world.getGeneralById(3)!!.gold)
    }

    @Test
    fun `close and payout update existing betting master through kv and reward winner`() {
        val world = world()
        val recorder = ChangeRecorder()
        val info = BettingInfo(
            id = 7,
            type = "tournament",
            name = "일기토",
            finished = false,
            selectCnt = 1,
            reqInheritancePoint = false,
            openYearMonth = 2402,
            closeYearMonth = 2522,
            candidates = linkedMapOf(
                1 to opensamguk.logic.betting.SelectItem("유비"),
                2 to opensamguk.logic.betting.SelectItem("관우"),
            ),
        )
        val port = ProductionTournamentBettingPort(
            world = world,
            recorder = recorder,
            bettingInfoReader = { info },
            bettingRowsReader = {
                listOf(TournamentBettingRow(generalId = 1, userId = 11, bettingType = "[1]", amount = 100))
            },
            nextBettingIdReader = { 7 },
            previousPointReader = { 0.0 },
        )

        port.close(7)
        val closed = recorder.kvDirty()[opensamguk.engine.turn.KvKey("betting", "betting", "id_7")] as Map<*, *>
        assertEquals(2402, closed["closeYearMonth"])

        port.payout(7, winnerId = 1)
        val paidRaw = recorder.kvDirty()[opensamguk.engine.turn.KvKey("betting", "betting", "id_7")] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val paid = BettingInfo.fromKvMap(paidRaw as Map<String, Any?>)!!
        assertTrue(paid.finished)
        assertEquals(listOf(1), paid.winner)
        assertEquals(2100, world.getGeneralById(1)!!.gold)
    }
}
