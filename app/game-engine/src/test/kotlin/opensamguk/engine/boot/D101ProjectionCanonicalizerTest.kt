package opensamguk.engine.boot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals

class D101ProjectionCanonicalizerTest {
    @Test
    fun `C8 synthetic golden is encoded byte for byte`() {
        val snapshot = D101ProjectionSnapshotReader.Snapshot(
            world = mapOf(
                "worldId" to 1, "generation" to "0", "scenarioCode" to "scenario_3190",
                "currentYear" to 190, "currentMonth" to 1, "currentPhase" to 1,
                "tickSeconds" to 3600, "lastTurnOffsetNanos" to "0",
                "nextBoundaryOffsetNanos" to "3600000000000",
            ),
            seedSettings = mapOf(
                "maxGeneralConfig" to 50, "maxGeneralGameEnv" to 50,
                "blockGeneralCreateConfig" to 1, "blockGeneralCreateGameEnv" to 1,
                "firstTurn" to "immediate", "extendedGeneral" to true,
            ),
            generals = listOf(listOf(1001, "합성장수", 1, 11, 2, false)),
            nations = listOf(listOf(1, "합성세력", 11, "fixture")),
            cities = listOf(listOf(11, "합성성", 1)),
            positions = listOf(listOf(1001, "synthetic-topology-v1", "e".repeat(64),
                "LAND_PROVINCE", "fixture-node", 1)),
            retainers = emptyList(),
            rawLastTurnTime = Instant.EPOCH,
            worldVersion = 0,
            writerEpoch = 0,
        )
        val pins = D101ProjectionCanonicalizer.SourcePins(
            "b".repeat(40), "c".repeat(64), "d".repeat(64), "e".repeat(64),
        )
        val actual = D101ProjectionCanonicalizer().canonicalBytes(snapshot, pins)
        val golden = requireNotNull(javaClass.classLoader.getResourceAsStream("d101/projection.synthetic.canonical.json"))
            .use { it.readBytes() }
        assertContentEquals(golden, actual)
    }
}
