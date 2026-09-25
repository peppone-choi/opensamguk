package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class EnlistmentPolicyReaderTest {
    private val request = EnlistmentRequest(1, EnlistmentMode.NATION, 1)
    private fun general(id: Int, lord: Boolean = false, capacity: Int = 30) = TurnGeneral(
        id = id, name = "G$id", nationId = if (id == 1) 0 else 1, cityId = 1, troopId = 0,
        stats = GeneralStats(50, 50, 50, 50, 50), experience = 0, dedication = 0,
        officerLevel = 0, turnTime = Instant.EPOCH,
        meta = mapOf("hwihaLord" to lord, PersonPolicyState.META_KEY to
            PersonPolicyState(capacity, true, "fixture", "pin", id).toMetaValue()),
    )
    private fun card(id: Int, master: Int, general: Int?) = Retainer(
        id, master, if (general == null) "RECRUITED" else "EXISTING", general, "C$id", "guest")
    private fun world(generals: List<TurnGeneral> = listOf(general(1), general(10, true)),
                      cards: List<Retainer> = emptyList()): InMemoryTurnWorld {
        val hash = "b".repeat(64)
        val positions = generals.fold(GeneralPositionSnapshot("fixture-r1", hash, setOf("fixture-p1"), emptySet())) { snapshot, general ->
            snapshot.withState(GeneralPositionState("fixture-r1", hash, general.id,
                StrategicNodeRef.LandProvince("fixture-p1"), 1))
        }
        return InMemoryTurnWorld(WorldSnapshot(
            worldId = WorldId(1), state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH,
                config = mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA")),
            generals = generals, retainers = cards, generalPositionSnapshot = positions,
            cityLandProvinceById = mapOf(1 to "fixture-p1"),
        ))
    }
    @Test fun `direct person costs refresh while descendants are not charged twice`() {
        val world = world(listOf(general(1), general(10, true), general(2), general(3)),
            listOf(card(1, 10, 2), card(2, 2, 3)))
        val policy = EnlistmentPolicyReader(world)
        val first = assertIs<EnlistmentPolicyResult.Ready>(policy.current(request))
        assertEquals(25, first.policy.freeRenownByLord[10])
        assertEquals(5, first.policy.actorCardCost)
        world.createRetainer(card(3, 10, 3))
        assertEquals(20, assertIs<EnlistmentPolicyResult.Ready>(policy.current(request)).policy.freeRenownByLord[10])
    }
    @Test fun `actor source policy and negative stats fail explicitly`() {
        assertEquals(EnlistmentPolicyUnavailable.ACTOR_NOT_FOUND,
            assertIs<EnlistmentPolicyResult.Unavailable>(EnlistmentPolicyReader(world()).current(request.copy(actorId = 99))).reason)
        for ((actor, reason) in listOf(
            general(1).copy(meta = emptyMap()) to EnlistmentPolicyUnavailable.MISSING_PERSON_POLICY,
            general(1).copy(meta = mapOf(PersonPolicyState.META_KEY to null)) to EnlistmentPolicyUnavailable.INVALID_PERSON_POLICY,
            general(1).copy(stats = GeneralStats(-1, 50, 50)) to EnlistmentPolicyUnavailable.INVALID_STATS,
        )) assertEquals(reason, assertIs<EnlistmentPolicyResult.Unavailable>(
            EnlistmentPolicyReader(world(listOf(actor, general(10, true)))).current(request)).reason)
    }
    @Test fun `unavailable target does not hide another valid lord or grant free capacity`() {
        val world = world(listOf(general(1), general(10, true), general(20, true)), listOf(card(1, 10, null)))
        val result = assertIs<EnlistmentPolicyResult.Ready>(EnlistmentPolicyReader(world).current(request))
        assertEquals(mapOf(20 to 30), result.policy.freeRenownByLord)
        assertEquals(mapOf(10 to EnlistmentPolicyUnavailable.UNSUPPORTED_UNLINKED_CARD), result.unavailableLordReasons)
    }
    @Test fun `overcapacity and overflowing direct cost totals cannot become positive budgets`() {
        for (overflow in listOf(false, true)) {
            val children = (2..12).map { general(it).copy(stats = if (overflow)
                GeneralStats(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
                else GeneralStats(100, 100, 100, 100, 100)) }
            val world = world(listOf(general(1), general(20, true)) + children,
                children.map { card(it.id, 20, it.id) })
            val result = assertIs<EnlistmentPolicyResult.Ready>(EnlistmentPolicyReader(world).current(request))
            assertEquals(if (overflow) EnlistmentPolicyUnavailable.COST_OVERFLOW else EnlistmentPolicyUnavailable.CAPACITY_EXCEEDED,
                result.unavailableLordReasons[20])
            assertTrue(result.policy.freeRenownByLord.isEmpty())
        }
    }
    @Test fun `malformed lord status anywhere rejects before executor can read it`() {
        for (corruptId in listOf(1, 2, 10)) {
            val generals = listOf(general(1), general(2).copy(nationId = 0), general(10, true)).map {
                if (it.id == corruptId) it.copy(meta = it.meta + ("hwihaLord" to "true")) else it
            }
            val result = EnlistmentPolicyReader(world(generals)).current(request)
            assertEquals(EnlistmentPolicyUnavailable.INVALID_LORD_STATUS,
                assertIs<EnlistmentPolicyResult.Unavailable>(result).reason)
        }
    }

}
