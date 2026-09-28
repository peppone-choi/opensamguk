package opensamguk.engine.turn

import java.lang.management.ManagementFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import opensamguk.common.world.WorldId

/** Measured probe; it compares the same simple mutation with and without a unit savepoint. */
class TurnUnitCheckpointCostProbeTest {
    private val turnTime = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(WorldSnapshot(
        state = TurnWorldState(1, 200, 1, 3600, turnTime), worldId = WorldId(1),
        generals = (1..280).map { id -> TurnGeneral(
            id = id, name = "g$id", nationId = 1, cityId = 1, troopId = 0,
            stats = GeneralStats(70, 70, 70), experience = 0, dedication = 0,
            officerLevel = 0, turnTime = turnTime,
        ) },
        cities = (1..1447).map { id -> City(id, "c$id", 1, 1) },
        nations = (1..21).map { id -> Nation(id, "n$id", "#000") },
        retainers = (1..228).map { id -> Retainer(
            id = id, masterGeneralId = (id - 1) % 280 + 1, origin = "probe",
            name = "r$id", relation = "FAMILY",
        ) },
        troops = (1..42).map { id -> Troop(id, 1, "t$id") },
    ))

    private data class Cost(val nanos: Long, val allocatedBytes: Long?)

    private fun measure(block: () -> Unit): Cost {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (bean?.isThreadAllocatedMemorySupported == true && !bean.isThreadAllocatedMemoryEnabled) {
            bean.isThreadAllocatedMemoryEnabled = true
        }
        val threadId = Thread.currentThread().threadId()
        val beforeBytes = bean?.takeIf { it.isThreadAllocatedMemorySupported }
            ?.getThreadAllocatedBytes(threadId)
        val beforeNanos = System.nanoTime()
        block()
        val nanos = System.nanoTime() - beforeNanos
        val afterBytes = bean?.takeIf { it.isThreadAllocatedMemorySupported }
            ?.getThreadAllocatedBytes(threadId)
        return Cost(nanos, if (beforeBytes != null && afterBytes != null) afterBytes - beforeBytes else null)
    }

    @Test
    fun `3190 sized unit savepoint cost is visible beside the mutation baseline`() {
        fun mutate(world: InMemoryTurnWorld, id: Int) {
            val general = checkNotNull(world.getGeneralById(id))
            world.updateGeneral(general.copy(gold = general.gold + 1))
        }
        val baseline = world()
        val isolated = world()
        val executor = TurnUnitExecutor(isolated, ChangeRecorder())
        repeat(10) { id -> mutate(baseline, id + 1); executor.run { mutate(isolated, id + 1) } }
        val baselineCost = measure { (11..280).forEach { mutate(baseline, it) } }
        val isolatedCost = measure { (11..280).forEach { id -> executor.run { mutate(isolated, id) } } }
        assertEquals(baseline.listGenerals().map { it.gold }, isolated.listGenerals().map { it.gold })
        println("turn-unit-checkpoint-cost scenario3190-shape cities=1447 generals=280 nations=21 retainers=228 troops=42 units=270 " +
            "baselineNanos=${baselineCost.nanos} isolatedNanos=${isolatedCost.nanos} " +
            "baselineAllocatedBytes=${baselineCost.allocatedBytes} isolatedAllocatedBytes=${isolatedCost.allocatedBytes}")
    }
}
