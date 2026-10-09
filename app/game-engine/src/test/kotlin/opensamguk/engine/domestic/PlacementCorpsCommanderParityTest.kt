package opensamguk.engine.domestic

import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.DomesticContext
import opensamguk.engine.campaign.DomesticHandler
import opensamguk.engine.turn.*
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.read.DomesticViews
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.DomesticAdmission
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import kotlin.test.*

/** Synthetic world: actual options, admission and handler, with the API snapshot read stubbed. */
class PlacementCorpsCommanderParityTest {
    private val now = Phase(200, 1, 1)
    private val context = DomesticContext()
    private val raw = """{"cardId":4,"post":"CORPS_COMMANDER"}"""

    private fun general(id: Int, npc: Int = 2, human: Boolean = false, nation: Int = 1,
        meta: Map<String, Any?> = emptyMap()) = TurnGeneral(id = id, name = "인물$id", nationId = nation,
        cityId = 10, troopId = 0, stats = GeneralStats(60, 60, 60, 60, 60), experience = 0,
        dedication = 0, officerLevel = 0, userId = if (human) "42" else null, npcState = npc,
        turnTime = Instant.EPOCH, meta = meta)

    private fun world(relation: String = "lieutenant", npc: Int = 2, human: Boolean = false, nation: Int = 1,
        inBattle: Boolean = false, deployed: Boolean = false, generalId: Int? = 2, masterId: Int = 1,
        meta: Map<String, Any?> = emptyMap()): InMemoryTurnWorld {
        val hash = "a".repeat(64)
        val node = StrategicNodeRef.LandProvince("p1")
        val positions = GeneralPositionSnapshot("qa", hash, setOf("p1"), emptySet(), listOf(
            GeneralPositionState("qa", hash, 1, node, 1),
            GeneralPositionState("qa", hash, 2, node, 1,
                BattlefieldPresence("qa-battle", hash, 10).takeIf { inBattle }),
        ))
        val ownerMeta = if (!deployed) emptyMap() else mapOf(DeploymentState.META_KEY to
            DeploymentState(listOf(DeployedCorps("o1", 1, 2, 4, 1, listOf(7), now))).toMetaValue())
        return InMemoryTurnWorld(WorldSnapshot(worldId = WorldId(1),
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH, currentPhase = 1,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            generals = listOf(general(1, human = true, meta = ownerMeta), general(2, npc, human, nation, meta)),
            nations = listOf(Nation(1, "세력", "#000", capitalCityId = 10)),
            cities = listOf(City(10, "현", 1, 1,
                meta = mapOf(CityMilitaryState.META_KEY to CityMilitaryState.INITIAL.toMetaValue()))),
            retainers = listOf(Retainer(4, masterId, "EXISTING", generalId, "인물2", relation)),
            generalPositionSnapshot = positions, cityLandProvinceById = mapOf(10 to "p1"),
            administrativeCountyIds = setOf(10)))
    }

    private fun assertParity(world: InMemoryTurnWorld, failure: DomesticFailure?) {
        val before = context.projection(world)
        val beforeState = world.getState()
        val beforePositions = world.generalPositionSnapshot()
        val beforeDirty = world.consumeDirtyState()
        val option = DomesticViews.posts(1, DomesticSnapshot(state = before)).cards.single().corpsCommander
        assertEquals(failure == null, option.available)
        assertEquals(failure?.name, option.blocked?.code)
        assertEquals(failure?.message, option.blocked?.reason)
        val reader = mock(DomesticReader::class.java)
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = before))
        val admission = DomesticAdmission(reader)
        if (failure == null) {
            val canonical = admission.canonicalArguments(1, 42, DomesticInput.PLACEMENT, raw)
            assertEquals(PlacementRequest(1, 4, PlacementPost.CORPS_COMMANDER, PlacementTarget.None),
                DomesticInput.parsePlacement(1, canonical))
        } else {
            val denied = assertFailsWith<AdmissionDenied> {
                admission.canonicalArguments(1, 42, DomesticInput.PLACEMENT, raw)
            }
            assertEquals(option.blocked?.code, denied.code)
            assertEquals(option.blocked?.reason, denied.message)
        }
        assertEquals(before, context.projection(world), "options and admission must not mutate the world")
        val recorder = ChangeRecorder()
        val result = DomesticHandler(world, recorder, context).handle(ImmediateInput("qa-placement", 1, 42,
            DomesticInput.PLACEMENT, raw))
        assertEquals(option.available, result.ok)
        assertEquals(option.blocked?.code, result.code)
        assertEquals(option.blocked?.reason, result.reason)
        if (failure == null) {
            val placement = assertNotNull(PlacementState.read(world.getGeneralById(2)!!.meta))
            assertNull(placement.active)
            assertEquals(PlacementPost.CORPS_COMMANDER, placement.pending?.post)
            assertEquals(PlacementTarget.None, placement.pending?.target)
            assertEquals(setOf(2), recorder.dirtyGeneralIds())
        } else {
            assertEquals(before, context.projection(world))
            assertFalse(recorder.isDirty, "rejection must not record any writes")
            assertEquals(beforeDirty, world.consumeDirtyState(), "rejection must preserve all dirty and log channels")
        }
        assertEquals(beforeState, world.getState())
        assertEquals(beforePositions, world.generalPositionSnapshot())
        assertTrue(world.peekLogs().isEmpty())
    }

    @Test fun `selected card option agrees with admission and handler for all relation and npc combinations`() {
        for (relation in listOf("lieutenant", "staff", "guest")) {
            for (npc in listOf(0, 1, 2, 3)) {
                assertParity(world(relation, npc), DomesticFailure.NOT_LIEUTENANT
                    .takeUnless { relation == "lieutenant" && npc == 2 })
            }
        }
    }

    @Test fun `common constraints and unchanged reject identically without any writes`() {
        val order = PlacementOrder("old-order", 1, 4, PlacementPost.CORPS_COMMANDER, PlacementTarget.None, now)
        for ((world, reason) in listOf(
            world(human = true) to DomesticFailure.HUMAN_CARD,
            world(nation = 2) to DomesticFailure.DIFFERENT_NATION,
            world(inBattle = true) to DomesticFailure.CARD_IN_BATTLE,
            world(deployed = true) to DomesticFailure.CARD_DEPLOYED,
            world(generalId = null) to DomesticFailure.CARD_NOT_ON_MAP,
            world(generalId = 99) to DomesticFailure.CARD_NOT_FOUND,
            world(meta = mapOf(PlacementState.META_KEY to PlacementState(ActivePlacement(order, now, now), null)
                .toMetaValue())) to DomesticFailure.UNCHANGED,
        )) assertParity(world, reason)
    }

    @Test fun `wrong owner and another masters card cannot cause handler side effects`() {
        for ((world, owner, reason) in listOf(
            Triple(world(), 43, "FORBIDDEN"), Triple(world(), 0, "FORBIDDEN"),
            Triple(world(masterId = 99), 42, "CARD_NOT_FOUND"),
        )) {
            val before = context.projection(world)
            val beforeState = world.getState()
            val beforePositions = world.generalPositionSnapshot()
            val beforeDirty = world.consumeDirtyState()
            val recorder = ChangeRecorder()
            val result = DomesticHandler(world, recorder, context).handle(ImmediateInput("qa-placement", 1, owner,
                DomesticInput.PLACEMENT, raw))
            assertFalse(result.ok)
            assertEquals(reason, result.code)
            assertEquals(before, context.projection(world))
            assertEquals(beforeState, world.getState())
            assertEquals(beforePositions, world.generalPositionSnapshot())
            assertEquals(beforeDirty, world.consumeDirtyState())
            assertFalse(recorder.isDirty)
        }
    }
}
