package opensamguk.engine.intake

import opensamguk.common.wire.DiploLetterResult
import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.SelectPoolActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.read.DiplomacyLetterReadRow
import opensamguk.infra.read.DiplomacyLetterRepository
import opensamguk.infra.read.SelectPoolReadRow
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.logic.util.jsonDecode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.lang.reflect.Proxy
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveGapClosureHandlerTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(
        id: Int,
        name: String,
        userId: String? = null,
        nationId: Int = 1,
        cityId: Int = 5,
        officerLevel: Int = 1,
        strength: Int = 70,
        intel: Int = 70,
        politics: Int = 50,
        charm: Int = 50,
        gold: Int = 1000,
        rice: Int = 1000,
        troopId: Int = 0,
        meta: Map<String, Any?> = linkedMapOf("permission" to "normal", "officer_city" to 0, "killturn" to 10),
    ) = TurnGeneral(
        id = id,
        userId = userId,
        name = name,
        nationId = nationId,
        cityId = cityId,
        troopId = troopId,
        stats = GeneralStats(80, strength, intel, politics, charm),
        experience = 100,
        dedication = 100,
        officerLevel = officerLevel,
        gold = gold,
        rice = rice,
        turnTime = t0,
        meta = meta,
    )

    private fun world(
        generals: List<TurnGeneral>,
        nations: List<Nation> = listOf(Nation(1, "촉", "#0f0", gold = 100, rice = 200, level = 2, meta = linkedMapOf("gennum" to 3, "chief_set" to 0))),
        cities: List<City> = listOf(City(5, "성도", 1, level = 6, supplyState = 1), City(7, "한중", 1, level = 5, supplyState = 1)),
        troops: List<Troop> = emptyList(),
    ) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(1, 200, 3, 3600, t0, meta = linkedMapOf("npcmode" to 2, "maxgeneral" to 500, "turnterm" to 60, "hiddenSeed" to "test-seed")),
            generals = generals,
            cities = cities,
            nations = nations,
            troops = troops,
        ),
    )

    private fun flush(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

    private fun dispatcher(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        diplomacyRepo: DiplomacyLetterRepository? = null,
        selectPoolRepo: SelectPoolRepository? = null,
    ) = TurnDaemonCommandDispatcher(
        world,
        recorder,
        noopRepo(),
        noopRepo(),
        noopRepo(),
        diplomacyLetterRepository = diplomacyRepo,
        selectPoolRepository = selectPoolRepo,
    )

    @Test
    fun `appoint assigns a city officer and records general and city patches`() {
        val world = world(listOf(general(10, "유비", officerLevel = 12), general(20, "관우", strength = 80)))
        val recorder = ChangeRecorder()

        val result = assertIs<GeneralBoolResult>(
            dispatcher(world, recorder).dispatch(TurnDaemonCommand.Appoint( generalId = 10, destGeneralId = 20, destCityId = 5, officerLevel = 4)),
        )

        assertEquals(true, result.ok)
        assertEquals(4, world.getGeneralById(20)!!.officerLevel)
        assertEquals(5, world.getGeneralById(20)!!.meta["officer_city"])
        assertEquals(1 shl 4, world.getCityById(5)!!.officerSet)
        val payload = flush(world, recorder)
        assertEquals(listOf(20), payload.updatedGenerals.map { it.id })
        assertEquals(listOf(5), payload.updatedCities.map { it.id })
    }

    @Test
    fun `kick banishes a same-nation general and returns surplus resources to the nation`() {
        val world = world(
            listOf(
                general(10, "유비", officerLevel = 12),
                general(20, "장비", officerLevel = 1, gold = 1300, rice = 1600, troopId = 20),
                general(21, "병사", officerLevel = 1, troopId = 20),
            ),
            troops = listOf(Troop(20, 1, "장비대")),
        )
        val recorder = ChangeRecorder()

        val result = assertIs<GeneralBoolResult>(
            dispatcher(world, recorder).dispatch(TurnDaemonCommand.Kick(generalId = 10, destGeneralId = 20)),
        )

        assertEquals(true, result.ok)
        val target = world.getGeneralById(20)!!
        assertEquals(0, target.nationId)
        assertEquals(0, target.officerLevel)
        assertEquals(1000, target.gold)
        assertEquals(1000, target.rice)
        assertEquals("normal", target.meta["permission"])
        assertEquals(0, target.troopId)
        assertEquals(0, world.getGeneralById(21)!!.troopId)
        assertEquals(400, world.getNationById(1)!!.gold)
        assertEquals(800, world.getNationById(1)!!.rice)
        val payload = flush(world, recorder)
        assertEquals(setOf(20, 21), payload.updatedGenerals.map { it.id }.toSet())
        assertEquals(listOf(20), payload.deletedTroops)
    }

    @Test
    fun `changePermission resets old ambassadors then assigns up to two eligible new ambassadors`() {
        val world = world(listOf(
            general(10, "유비", officerLevel = 12),
            general(20, "관우", meta = linkedMapOf("permission" to "ambassador", "officer_city" to 0)),
            general(21, "장비"),
            general(22, "조운"),
        ))
        val recorder = ChangeRecorder()

        val result = assertIs<GeneralBoolResult>(
            dispatcher(world, recorder).dispatch(TurnDaemonCommand.ChangePermission(generalId = 10, isAmbassador = true, targetGeneralIds = listOf(21, 22))),
        )

        assertEquals(true, result.ok)
        assertEquals("normal", world.getGeneralById(20)!!.meta["permission"])
        assertEquals("ambassador", world.getGeneralById(21)!!.meta["permission"])
        assertEquals("ambassador", world.getGeneralById(22)!!.meta["permission"])
        assertEquals(setOf(20, 21, 22), flush(world, recorder).updatedGenerals.map { it.id }.toSet())
    }

    @Test
    fun `diploRespondLetter agree activates the letter, replaces ancestors, and sends diplomacy plus national messages`() {
        val world = world(
            generals = listOf(general(10, "유비", nationId = 2, officerLevel = 12)),
            nations = listOf(Nation(1, "촉", "#0f0"), Nation(2, "위", "#00f")),
        )
        val recorder = ChangeRecorder(messageIdAllocator = counter(), diplomacyLetterIdAllocator = counter())
        val repo = FakeDiploRepo(mapOf(
            7 to letter(7, src = 1, dest = 2, prevNo = 6),
            6 to letter(6, src = 1, dest = 2, prevNo = null),
        ))

        val result = assertIs<DiploLetterResult>(
            dispatcher(world, recorder, diplomacyRepo = repo).dispatch(TurnDaemonCommand.DiploRespondLetter(generalId = 10, letterNo = 7, isAgree = true)),
        )

        assertEquals(true, result.ok)
        assertEquals("ACTIVATED", recorder.diplomacyLetterUpdates().getValue(7)["state"])
        assertEquals(10, recorder.diplomacyLetterUpdates().getValue(7)["dest_signer"])
        assertEquals("REPLACED", recorder.diplomacyLetterUpdates().getValue(6)["state"])
        assertEquals(listOf("diplomacy", "diplomacy", "national", "national"), recorder.createdMessages().map { it.type })
        val text = jsonDecode(recorder.createdMessages().first().bodyJson)["text"]
        assertEquals("외교 서신( #7)이 승인되었습니다.", text)
    }

    @Test
    fun `selectPoolPick creates a general from a reserved pool row`() {
        val world = world(generals = emptyList())
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(
            SelectPoolReadRow(
                uniqueName = "pool-a",
                ownerUserId = 77,
                reservedUntil = t0,
                statEditable = true,
                info = linkedMapOf(
                    "generalName" to "마초",
                    "cityId" to 5,
                    "leadership" to 70,
                    "strength" to 80,
                    "intel" to 60,
                    "politics" to 33,
                    "charm" to 44,
                ),
            ),
        )

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(
                TurnDaemonCommand.SelectPoolPick(
                    generalId = 0,
                    ownerUserId = 77,
                    uniqueName = "pool-a",
                    leadership = 70,
                    strength = 55,
                    intel = 40,
                ),
            ),
        )

        assertEquals(true, result.ok)
        val created = world.listGenerals().single()
        assertEquals(result.generalId, created.id)
        assertEquals("마초", created.name)
        assertEquals(70, created.stats.leadership)
        assertEquals(55, created.stats.strength)
        assertEquals(40, created.stats.intelligence)
        assertEquals(33, created.stats.politics)
        assertEquals(44, created.stats.charm)
        assertEquals(77.toString(), created.userId)
        assertEquals("pool-a", recorder.selectPoolMutations().single().uniqueName)
        val payload = flush(world, recorder)
        assertEquals(listOf(created.id), payload.createdGenerals.map { it.columns["id"] })
        assertEquals(created.id, payload.generalAccessLogUpserts.single().generalId)
        assertEquals(77L, payload.generalAccessLogUpserts.single().userId)
    }

    @Test
    fun `selectPoolRefresh records fourteen deterministic random name candidates`() {
        val world = world(generals = emptyList())
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(null)

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(
                TurnDaemonCommand.SelectPoolRefresh(ownerUserId = 77, requestedAt = "2026-07-10T03:00:00Z"),
            ),
        )

        assertEquals(true, result.ok)
        val mutation = recorder.selectPoolMutations().single()
        assertEquals(14, mutation.candidates.size)
        assertEquals(14, mutation.candidates.map { it.uniqueName }.toSet().size)
        assertEquals(Instant.parse("2026-07-10T03:00:30Z"), mutation.reservedUntil)
        assertTrue(mutation.candidates.all { it.info["generalName"] == it.uniqueName })
    }

    @Test
    fun `selectPoolPick denies when owner user identity is absent`() {
        val world = world(generals = emptyList())
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(
            SelectPoolReadRow(
                uniqueName = "pool-a",
                ownerUserId = 77,
                reservedUntil = t0,
                statEditable = true,
                info = linkedMapOf("generalName" to "마초", "cityId" to 5, "leadership" to 70, "strength" to 80, "intel" to 60),
            ),
        )

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(
                TurnDaemonCommand.SelectPoolPick(generalId = 0, uniqueName = "pool-a"),
            ),
        )

        assertEquals(false, result.ok)
        assertEquals("멤버 정보를 가져오지 못했습니다.", result.reason)
        assertTrue(world.listGenerals().isEmpty())
    }

    @Test
    fun `selectPoolUpdate applies the selected pool info to an existing general`() {
        val world = world(listOf(general(30, "구명", userId = "77", officerLevel = 0, politics = 42, charm = 43, meta = linkedMapOf("permission" to "normal"))))
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(
            SelectPoolReadRow(
                uniqueName = "pool-b",
                ownerUserId = 77,
                reservedUntil = t0,
                statEditable = false,
                generalId = 30,
                info = linkedMapOf("generalName" to "신명", "leadership" to 77, "strength" to 66, "intel" to 55, "ego" to "che_패권"),
            ),
        )

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(TurnDaemonCommand.SelectPoolUpdate(generalId = 30, uniqueName = "pool-b")),
        )

        assertEquals(true, result.ok)
        val updated = world.getGeneralById(30)!!
        assertEquals("신명", updated.name)
        assertEquals(77, updated.stats.leadership)
        assertEquals(42, updated.stats.politics)
        assertEquals(43, updated.stats.charm)
        assertEquals("che_패권", updated.role.personality)
        assertEquals("pool-b", recorder.selectPoolMutations().single().uniqueName)
        assertEquals(listOf(30), flush(world, recorder).updatedGenerals.map { it.id })
    }

    @Test
    fun `selectPoolUpdate uses politics and charm from pool info when present`() {
        val world = world(listOf(general(30, "구명", userId = "77", officerLevel = 0, politics = 42, charm = 43, meta = linkedMapOf("permission" to "normal"))))
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(
            SelectPoolReadRow(
                uniqueName = "pool-c",
                ownerUserId = 77,
                reservedUntil = t0,
                statEditable = false,
                generalId = 30,
                info = linkedMapOf(
                    "generalName" to "신명",
                    "leadership" to 77,
                    "strength" to 66,
                    "intel" to 55,
                    "politics" to 31,
                    "charm" to 32,
                ),
            ),
        )

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(TurnDaemonCommand.SelectPoolUpdate(generalId = 30, uniqueName = "pool-c")),
        )

        assertEquals(true, result.ok)
        val updated = world.getGeneralById(30)!!
        assertEquals(31, updated.stats.politics)
        assertEquals(32, updated.stats.charm)
    }

    @Test
    fun `selectPoolPick denies an expired reserved token`() {
        val world = world(generals = emptyList())
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(
            SelectPoolReadRow(
                uniqueName = "expired",
                ownerUserId = 77,
                reservedUntil = t0.minusSeconds(1),
                statEditable = true,
                info = linkedMapOf("generalName" to "마초", "cityId" to 5, "leadership" to 70, "strength" to 80, "intel" to 60),
            ),
        )

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(
                TurnDaemonCommand.SelectPoolPick(generalId = 0, ownerUserId = 77, uniqueName = "expired"),
            ),
        )

        assertEquals(false, result.ok)
        assertEquals("유효한 장수 목록이 없습니다.", result.reason)
        assertTrue(world.listGenerals().isEmpty())
    }

    @Test
    fun `selectPoolUpdate denies an expired reserved token`() {
        val world = world(listOf(general(30, "구명", userId = "77", officerLevel = 0, meta = linkedMapOf("permission" to "normal"))))
        val recorder = ChangeRecorder()
        val repo = FakeSelectPoolRepo(
            SelectPoolReadRow(
                uniqueName = "expired",
                ownerUserId = 77,
                reservedUntil = t0.minusSeconds(1),
                statEditable = false,
                generalId = 30,
                info = linkedMapOf("generalName" to "신명", "leadership" to 77, "strength" to 66, "intel" to 55),
            ),
        )

        val result = assertIs<SelectPoolActionResult>(
            dispatcher(world, recorder, selectPoolRepo = repo).dispatch(
                TurnDaemonCommand.SelectPoolUpdate(generalId = 30, uniqueName = "expired"),
            ),
        )

        assertEquals(false, result.ok)
        assertEquals("유효한 장수 목록이 없습니다.", result.reason)
        assertEquals("구명", world.getGeneralById(30)!!.name)
    }

    private fun counter(): () -> Int {
        var next = 1
        return { next++ }
    }

    private fun letter(letterNo: Int, src: Int, dest: Int, prevNo: Int?) = DiplomacyLetterReadRow(
        letterNo = letterNo,
        srcNationId = src,
        destNationId = dest,
        prevNo = prevNo,
        state = "proposed",
        stateOpt = null,
        auxJson = "{\"src\":{\"nationName\":\"촉\"},\"dest\":{\"nationName\":\"위\"}}",
    )

    private class FakeDiploRepo(private val letters: Map<Int, DiplomacyLetterReadRow>) : DiplomacyLetterRepository(noopNamedJdbc()) {
        override fun findLetter(letterNo: Int): DiplomacyLetterReadRow? = letters[letterNo]
    }

    private class FakeSelectPoolRepo(private var row: SelectPoolReadRow?) : SelectPoolRepository(noopNamedJdbc()) {
        override fun findPoolEntry(uniqueName: String, ownerUserId: Int, now: Instant): SelectPoolReadRow? =
            row?.takeIf { it.uniqueName == uniqueName && it.ownerUserId == ownerUserId }

        override fun listForUser(ownerUserId: Int, now: Instant): List<SelectPoolReadRow> =
            listOfNotNull(row?.takeIf { it.ownerUserId == ownerUserId && it.reservedUntil?.isBefore(now) != true })

        override fun listUniqueNames(): Set<String> = setOfNotNull(row?.uniqueName)

        override fun targetGeneralPool(): String = "RandomNameGeneral"

    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> noopRepo(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T

    companion object {
        fun noopNamedJdbc(): NamedParameterJdbcTemplate {
            val ds = Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
            ) { _, _, _ -> null } as DataSource
            return NamedParameterJdbcTemplate(JdbcTemplate(ds))
        }
    }
}
