package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class HwihaReservedTurnRejectionTest {
    private fun world(profile: String, interval: Int = 3600) = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, 200, 1, interval, Instant.EPOCH,
            config = mapOf("mapName" to "han-world-v3", "ruleProfile" to profile)),
        worldId = WorldId(1),
        generals = listOf(TurnGeneral(id = 1, name = "actor", nationId = 0, cityId = 1, troopId = 0,
            stats = GeneralStats(60, 60, 60), experience = 300, dedication = 400, officerLevel = 0, turnTime = Instant.EPOCH, gold = 1000, rice = 2000,
            injury = 30, npcState = 2, meta = mapOf("killturn" to 80))),
        cities = listOf(City(1, "city", 0, 1)),
        generalPositionSnapshot = GeneralPositionSnapshot("r1", "a".repeat(64), setOf("p1"), emptySet())
            .withState(GeneralPositionState("r1", "a".repeat(64), 1, StrategicNodeRef.LandProvince("p1"), 1)),
        cityLandProvinceById = mapOf(1 to "p1"),
    ))

    @Test fun `undelivered hwiha input cannot run rest or legacy AI`() {
        val world = world("HWIHA")
        val before = world.getGeneralById(1)
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "00", 184,
            aiHook = { _, _ -> error("legacy AI must not run") })
        val result = handler.handle(1, ReservedTurn("action.enlist", "{}", requestId = "request"), 200, 1, "00:00")
        assertNull(result.definition)
        assertFalse(result.fellBack)
        assertEquals("아직 제공되지 않는 입력입니다.", result.denyReason)
        assertEquals(before, world.getGeneralById(1))
        assertFalse(handler.recorder.isDirty)
    }

    @Test fun `cross profile and unknown hwiha codes never resolve legacy definitions`() {
        for ((profile, code, reason) in listOf(
            Triple("HWIHA", "che_임관", "이 월드의 규칙에서 사용할 수 없는 입력입니다."),
            Triple("SAMMO", "action.enlist", "이 월드의 규칙에서 사용할 수 없는 입력입니다."),
            Triple("HWIHA", "action.missing", "등록되지 않은 입력입니다."),
            Triple("HWIHA", "?", "입력 식별자가 올바르지 않습니다."),
        )) {
            val world = world(profile)
            val before = world.getGeneralById(1)
            val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "00", 184)
            val result = handler.handle(1, code, 200, 1, "00:00")
            assertNull(result.definition)
            assertFalse(result.fellBack)
            assertEquals(reason, result.denyReason)
            assertEquals(before, world.getGeneralById(1))
            assertFalse(handler.recorder.isDirty)
        }
    }
    @Test fun `lifecycle rejects blocked ruler input without legacy effects and advances once`() {
        for ((profile, interval) in listOf("HWIHA" to 3600, "HWIHA" to 7200, "SAMMO" to 3600)) {
            val world = world(profile, interval)
            val original = world.getGeneralById(1)!!.copy(nationId = 1, officerLevel = 12,
                meta = mapOf("killturn" to 0, "block" to 2, "age" to 200))
            world.applyGeneralDirtyFree(original)
            val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "00", 184,
                aiHook = { _, _ -> error("legacy general AI") })
            var pulls = 0
            var observations = 0
            val lifecycle = TurnDaemonLifecycle(world, handler,
                beginGeneralTurn = { error("legacy AI initialization") },
                reservedNationActionOf = { _, _ -> error("legacy nation reservation") },
                pullNationTurnOf = { _, _ -> error("legacy nation ring pull") },
                pullGeneralTurnOf = { pulls++ },
                observeHandledTurn = { observations++ },
                reservedActionOf = { ReservedTurn("action.enlist", "{}", requestId = "blocked-request") })
            val result = lifecycle.runTick(Instant.EPOCH.plusSeconds(1)).single()
            assertFalse(result.fellBack)
            assertNotNull(result.hwihaOutcome)
            assertEquals("blocked-request", result.requestId)
            assertEquals("action.enlist", result.reservedActionCode)
            assertEquals(original.copy(turnTime = Instant.EPOCH.plusSeconds(interval.toLong())), world.getGeneralById(1))
            assertEquals(1, pulls)
            assertEquals(1, observations)
            assertTrue(lifecycle.runTick(Instant.EPOCH.plusSeconds(1)).isEmpty())
            assertEquals(1, pulls)
            assertTrue(handler.recorder.generalPatches().isNotEmpty())
        }
    }

}
