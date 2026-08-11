package opensamguk.engine.intake

import opensamguk.common.wire.MakeGeneralFail
import opensamguk.common.wire.MakeGeneralOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MakeGeneralHandlerTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun state() = TurnWorldState(
        id = 1,
        currentYear = 200,
        currentMonth = 1,
        tickSeconds = 3600,
        lastTurnTime = t0,
        meta = mapOf("hiddenSeed" to "join-test-seed"),
    )

    private fun command(userId: Int = 7) = TurnDaemonCommand.MakeGeneral(
        userId = userId,
        name = "테스트",
        leadership = 55,
        strength = 55,
        intel = 55,
        politics = 54,
        charm = 56,
        character = "Random",
    )

    private fun existingTypedGeneral(npcState: Int) = TurnGeneral(
        id = 10,
        name = "기존장수",
        nationId = 0,
        cityId = 10,
        troopId = 0,
        stats = GeneralStats(50, 50, 50),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        npcState = npcState,
        userId = "7",
        turnTime = t0,
    )

    private fun worldWithExistingTypedGeneral(npcState: Int) = InMemoryTurnWorld(
        WorldSnapshot(
            state = state(),
            generals = listOf(existingTypedGeneral(npcState)),
            cities = listOf(City(id = 10, name = "낙양", nationId = 0, level = 5)),
            worldId = opensamguk.common.world.WorldId((state()).id),
        ),
    )

    @Test
    fun `make general falls back to occupied level five-six cities when no neutral birth city exists`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state(),
                cities = listOf(
                    City(id = 10, name = "낙양", nationId = 1, level = 5),
                    City(id = 11, name = "허창", nationId = 2, level = 6),
                    City(id = 12, name = "관문", nationId = 0, level = 4),
                ),
                worldId = opensamguk.common.world.WorldId((state()).id),
            ),
        )

        val result = MakeGeneralHandler(world, ChangeRecorder()).handle(command())

        assertIs<MakeGeneralOk>(result)
        val created = world.getGeneralById(result.generalId)
        assertNotNull(created)
        assertTrue(
            created.cityId in setOf(10, 11),
            "PHP Join.php:278-283 falls back from neutral level 5-6 cities to all level 5-6 cities.",
        )
    }

    @Test
    fun `released npc states with stale typed user id do not block creation`() {
        for (npcState in listOf(2, 3)) {
            val result = MakeGeneralHandler(worldWithExistingTypedGeneral(npcState), ChangeRecorder()).handle(command())

            assertIs<MakeGeneralOk>(result, "npcState=$npcState is not a live player general")
        }
    }

    @Test
    fun `live typed player states still block creation`() {
        for (npcState in listOf(0, 1)) {
            val result = MakeGeneralHandler(worldWithExistingTypedGeneral(npcState), ChangeRecorder()).handle(command())

            assertEquals("이미 등록하셨습니다!", assertIs<MakeGeneralFail>(result).reason, "npcState=$npcState is live")
        }
    }

    @Test
    fun `created general carries drawn affinity into the flush payload`() {
        val recorder = ChangeRecorder()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state(),
                cities = listOf(City(id = 10, name = "낙양", nationId = 0, level = 5)),
                worldId = opensamguk.common.world.WorldId((state()).id),
            ),
        )

        val result = assertIs<MakeGeneralOk>(
            MakeGeneralHandler(world, recorder, nowProvider = { t0 }).handle(command(userId = 8)),
        )
        val created = world.getGeneralById(result.generalId)
        assertNotNull(created)
        assertEquals("8", created.userId)
        assertEquals(0, created.npcState)
        assertEquals(54, created.stats.politics)
        assertEquals(56, created.stats.charm)
        val affinity = created.meta["affinity"] as? Number
        assertNotNull(affinity, "PHP Join.php:392/413 stores the RNG-drawn affinity in general.affinity.")
        assertTrue(affinity.toInt() in 1..150)

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

        assertTrue(payload.createdGenerals.isNotEmpty())
        val columns = payload.createdGenerals.single().columns
        assertEquals("8", columns["user_id"])
        assertEquals(0, columns["npc_state"])
        assertEquals(54, columns["politics"])
        assertEquals(56, columns["charm"])
        assertTrue(columns["affinity"] is Int)
        assertTrue((columns["affinity"] as Int) in 1..150)
        assertEquals(8L, world.getAccessLog(result.generalId)?.userId)
        assertEquals(t0, world.getAccessLog(result.generalId)?.lastRefresh)
        assertEquals(result.generalId, payload.generalAccessLogUpserts.single().generalId)
        assertEquals(8L, payload.generalAccessLogUpserts.single().userId)
    }

    @Test
    fun `inheritance options choose exact values and spend previous points once`() {
        val recorder = ChangeRecorder()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state(),
                cities = listOf(
                    City(id = 10, name = "낙양", nationId = 0, level = 5),
                    City(id = 11, name = "허창", nationId = 1, level = 6),
                ),
                worldId = opensamguk.common.world.WorldId((state()).id),
            ),
        )
        val request = command(userId = 9).copy(
            name = "유산장수",
            character = "che_안전",
            picture = "custom.jpg",
            ownerName = "계정주인",
            imgsvr = 1,
            inheritSpecial = "che_귀병",
            inheritTurntimeZone = 12,
            inheritCity = 11,
            inheritBonusStat = listOf(3, 1, 1),
        )

        val result = assertIs<MakeGeneralOk>(
            MakeGeneralHandler(world, recorder, previousPointReader = { 20_000.0 }).handle(request),
        )

        val created = assertNotNull(world.getGeneralById(result.generalId))
        assertEquals(11, created.cityId)
        assertEquals(58, created.stats.leadership)
        assertEquals(56, created.stats.strength)
        assertEquals(56, created.stats.intelligence)
        assertEquals("che_귀병", created.role.specialWar)
        assertEquals("custom.jpg", created.meta["picture"])
        assertEquals(1, created.meta["image_server"])
        assertEquals("계정주인", created.meta["owner_name"])

        assertEquals(listOf(9_500.0, null), recorder.inheritanceKvWrites().single().value)
        val inheritLogs = recorder.inheritanceLogInserts().map { it.text }
        assertEquals("귀병 전투 특기를 가진 천재 생성", inheritLogs[0])
        assertEquals("허창에 장수 생성", inheritLogs[1])
        assertEquals("3, 1, 1 보너스 능력치로 생성", inheritLogs[2])
        assertTrue(inheritLogs[3].matches(Regex("턴 시간 12:[0-5][0-9] 로 지정")))
        assertEquals("장수 생성으로 포인트 10500 소모", inheritLogs[4])
        val spent = assertIs<RankDelta.Increment>(recorder.rankDeltas(result.generalId)[RankColumn.INHERIT_SPENT_DYN])
        assertEquals(10_500, spent.value)
    }

    @Test
    fun `inheritance request with insufficient points is denied before world mutation`() {
        val recorder = ChangeRecorder()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state(),
                cities = listOf(City(id = 10, name = "낙양", nationId = 0, level = 5)),
                worldId = opensamguk.common.world.WorldId((state()).id),
            ),
        )

        val result = assertIs<MakeGeneralFail>(
            MakeGeneralHandler(world, recorder, previousPointReader = { 999.0 }).handle(
                command().copy(inheritCity = 10),
            ),
        )

        assertEquals("유산 포인트가 부족합니다. 다시 가입해주세요!", result.reason)
        assertTrue(world.listGenerals().isEmpty())
        assertTrue(recorder.inheritanceKvWrites().isEmpty())
    }
}
