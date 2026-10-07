package opensamguk.engine.intake

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Optional
import opensamguk.common.auth.GatewayPrincipal
import opensamguk.engine.turn.TurnUnitExecutor
import opensamguk.gameapi.member.MemberProfile
import opensamguk.gameapi.member.MemberProfileClient
import opensamguk.gameapi.owner.GeneralOwnershipClassifier
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.gameapi.web.JoinController
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

import opensamguk.common.wire.MakeGeneralFail
import opensamguk.common.wire.MakeGeneralOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.campaign.DelegationPhase
import opensamguk.engine.campaign.OfflineDelegationLease
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
    @Test fun `HWIHA creation adds truthful policy without changing the five stat draw`() {
        fun fresh(profile: String) = InMemoryTurnWorld(WorldSnapshot(
            state = state().copy(config = mapOf("ruleProfile" to profile, "mapName" to "han-world-v3", "maxgeneral" to 50)),
            worldId = opensamguk.common.world.WorldId(1),
            cities = listOf(City(10, "낙양", 0, level = 5)),
            generalPositionSnapshot = opensamguk.logic.world.GeneralPositionSnapshot("fixture", "a".repeat(64), setOf("p"), emptySet()),
            cityLandProvinceById = mapOf(10 to "p"),
        ))
        val sammo = fresh("SAMMO"); val hwiha = fresh("HWIHA")
        val recorder = ChangeRecorder()
        val a = assertIs<MakeGeneralOk>(MakeGeneralHandler(sammo, ChangeRecorder(), nowProvider = { t0 }).handle(command()))
        val b = assertIs<MakeGeneralOk>(MakeGeneralHandler(hwiha, recorder, nowProvider = { t0 }).handle(command()))
        val legacy = sammo.getGeneralById(a.generalId)!!
        val created = hwiha.getGeneralById(b.generalId)!!
        assertEquals(legacy.stats, created.stats)
        assertEquals(legacy.turnTime, created.turnTime)
        assertEquals(legacy.role, created.role)
        assertEquals(legacy.meta, created.meta - setOf("lord", "personPolicy", OfflineDelegationLease.META_KEY))
        assertEquals(false, created.meta["lord"])
        assertEquals(OfflineDelegationLease(1, b.generalId, 7, DelegationPhase(200, 1, 1)),
            OfflineDelegationLease.read(created.meta))
        val policy = opensamguk.logic.input.PersonPolicyState.read(created.meta)!!
        assertEquals(opensamguk.logic.input.PersonPolicyState(30, false, "opensamguk:created-general", "v1", b.generalId), policy)
        val stats = created.stats
        val expectedCost = ((listOf(stats.leadership, stats.strength, stats.intelligence, stats.politics, stats.charm).sumOf { it.toLong() } + 49) / 50).toInt()
        val budget = assertIs<opensamguk.engine.campaign.EnlistmentPolicyResult.Ready>(
            opensamguk.engine.campaign.EnlistmentPolicyReader(hwiha).current(opensamguk.logic.input.EnlistmentRequest(b.generalId, opensamguk.logic.input.EnlistmentMode.RANDOM)))
        assertEquals(expectedCost, budget.policy.actorCardCost)
        assertTrue(DatabaseHooks.toFlushPayload(hwiha, recorder, hwiha.consumeDirtyState()).createdGenerals.isNotEmpty())
    }


    private fun hwihaWorld(maxGeneral: Int = 2) = InMemoryTurnWorld(WorldSnapshot(
        state = state().copy(config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3",
            "block_general_create" to 0, "maxgeneral" to maxGeneral)),
        worldId = opensamguk.common.world.WorldId(1),
        cities = listOf(City(10, "낙양", 0, level = 5)),
        generalPositionSnapshot = opensamguk.logic.world.GeneralPositionSnapshot(
            "fixture", "a".repeat(64), setOf("p"), emptySet()),
        cityLandProvinceById = mapOf(10 to "p"),
    ))

    /** Real API admission and published daemon command; SQL/Redis edges use fixture state. */
    private fun acceptedJoin(world: InMemoryTurnWorld, request: TurnDaemonCommand.MakeGeneral): TurnDaemonCommand.MakeGeneral {
        val generals = Mockito.mock(GeneralReadRepository::class.java)
        val worlds = Mockito.mock(WorldStateReadRepository::class.java)
        val reserve = Mockito.mock(CommandReserveService::class.java)
        val ownership = Mockito.mock(GeneralOwnershipClassifier::class.java)
        val members = Mockito.mock(MemberProfileClient::class.java)
        val userId = request.userId.toLong()
        Mockito.`when`(worlds.findById(1)).thenReturn(Optional.of(
            WorldStateReadEntity(id = 1, config = LinkedHashMap(world.getState().config))))
        Mockito.`when`(generals.countByNpcStateLessThan(2))
            .thenReturn(world.listGenerals().count { it.npcState < 2 }.toLong())
        Mockito.`when`(ownership.classify(userId))
            .thenReturn(GeneralOwnershipClassifier.Ownership.None)
        Mockito.`when`(members.get(userId))
            .thenReturn(MemberProfile("계정주인", 1, null, 0))
        var published: TurnDaemonCommand.MakeGeneral? = null
        Mockito.`when`(reserve.publishImmediate(
            ArgumentMatchers.any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause(),
            ownerUserId = ArgumentMatchers.eq(request.userId),
        )).thenAnswer {
            published = it.getArgument<TurnDaemonCommand.MakeGeneral>(0)
            CommandReserveService.ReserveResult("accepted-${request.userId}", 0)
        }
        val controller = JoinController(generals, worlds, reserve,
            Mockito.mock(GameKvReadRepository::class.java),
            Mockito.mock(CityReadRepository::class.java),
            ObjectMapper(), ownership, members)
        val servlet = MockHttpServletRequest().apply {
            setAttribute(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE,
                GatewayPrincipal(userId, "USER"))
        }
        val response = controller.join(userId, servlet, JoinController.JoinRequest(
            request.name, request.leadership, request.strength, request.intel, request.politics,
            request.charm, request.character, pic = false))
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals("AVAILABLE", response.body?.status)
        return assertNotNull(published)
    }

    @Test fun `HWIHA queued join is denied if direct creation closes before execution`() {
        for (block in listOf<Any>(1, "1")) {
            val world = hwihaWorld()
            val recorder = ChangeRecorder()
            val acceptedRequest = acceptedJoin(world, command())
            assertEquals(0, world.getState().config["block_general_create"])
            assertTrue(world.listGenerals().size < 2)
            world.applyAdminWorldSettings(null, mapOf("block_general_create" to block), null)
            val before = world.getState()

            val result = MakeGeneralHandler(world, recorder, nowProvider = { t0 }).handle(acceptedRequest)

            assertEquals("장수 직접 생성이 불가능한 모드입니다.", assertIs<MakeGeneralFail>(result).reason)
            assertEquals(before, world.getState())
            assertTrue(world.listGenerals().isEmpty())
            val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
            assertTrue(payload.createdGenerals.isEmpty())
            assertTrue(payload.generalAccessLogUpserts.isEmpty())
            assertTrue(recorder.inheritanceKvWrites().isEmpty())
        }
    }

    @Test fun `HWIHA queued joins cannot consume the same remaining player slot`() {
        val world = hwihaWorld(maxGeneral = 1)
        val acceptedFirst = acceptedJoin(world, command(userId = 8).copy(name = "선행장수"))
        val acceptedSecond = acceptedJoin(world, command(userId = 7))
        assertTrue(world.listGenerals().isEmpty()) // Both requests see one free slot at intake.
        val firstRecorder = ChangeRecorder()
        assertIs<MakeGeneralOk>(MakeGeneralHandler(world, firstRecorder, nowProvider = { t0 }).handle(acceptedFirst))
        val before = world.listGenerals().toList()
        world.consumeDirtyState()
        val secondRecorder = ChangeRecorder()

        val result = MakeGeneralHandler(world, secondRecorder, nowProvider = { t0 }).handle(acceptedSecond)

        assertEquals("더이상 등록할 수 없습니다!", assertIs<MakeGeneralFail>(result).reason)
        assertEquals(before, world.listGenerals())
        val payload = DatabaseHooks.toFlushPayload(world, secondRecorder, world.consumeDirtyState())
        assertTrue(payload.createdGenerals.isEmpty())
        assertTrue(payload.generalAccessLogUpserts.isEmpty())
        assertTrue(secondRecorder.inheritanceKvWrites().isEmpty())
    }

    @Test fun `HWIHA failed creation unit rolls back before another account uses the slot`() {
        val world = hwihaWorld(maxGeneral = 1)
        val recorder = ChangeRecorder()
        val handler = MakeGeneralHandler(world, recorder, nowProvider = { t0 })
        val failed = TurnUnitExecutor(world, recorder).run {
            assertIs<MakeGeneralOk>(handler.handle(command(userId = 7)))
            throw IllegalStateException("fixture failure after creation")
        }
        assertIs<TurnUnitExecutor.Outcome.Failed>(failed)
        assertTrue(world.listGenerals().isEmpty())
        val rolledBack = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertTrue(rolledBack.createdGenerals.isEmpty())
        assertTrue(rolledBack.generalAccessLogUpserts.isEmpty())
        assertTrue(recorder.inheritanceKvWrites().isEmpty())

        val created = assertIs<MakeGeneralOk>(handler.handle(command(userId = 8).copy(name = "다른계정")))
        assertEquals("8", world.getGeneralById(created.generalId)?.userId)
        val committed = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals(listOf("8"), committed.createdGenerals.map { it.columns["user_id"] })
        assertEquals(listOf(8L), committed.generalAccessLogUpserts.map { it.userId })
    }

    @Test fun `HWIHA creation requires an exact positive numeric player cap`() {
        for (cap in listOf(null, "50", 0, -1, 1.5)) {
            val world = hwihaWorld()
            world.applyAdminWorldSettings(null, mapOf("maxgeneral" to cap), null)
            val recorder = ChangeRecorder()
            assertEquals("장수 생성 정책을 확인할 수 없습니다.",
                assertIs<MakeGeneralFail>(MakeGeneralHandler(world, recorder).handle(command())).reason)
            assertTrue(world.listGenerals().isEmpty())
            val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
            assertTrue(payload.createdGenerals.isEmpty())
            assertTrue(payload.generalAccessLogUpserts.isEmpty())
            assertTrue(recorder.inheritanceKvWrites().isEmpty())
        }
    }

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
