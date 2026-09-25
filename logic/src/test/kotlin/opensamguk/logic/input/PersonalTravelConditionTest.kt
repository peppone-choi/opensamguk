package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import opensamguk.logic.world.*

class PersonalTravelConditionTest {
    @Test
    fun `forced cost follows physical distance and does not round each phase independently`() {
        val a = StrategicNodeRef.LandProvince("A")
        val b = StrategicNodeRef.LandProvince("B")
        val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
            listOf(TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 1,
                RiskBand.LOW, SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"),
                confidence = EvidenceConfidence.REVIEWED)), emptyList(),
            mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
        val metrics = LandMarchMetricSnapshot(topology, "a".repeat(64),
            listOf(LandMarchEdgeMetric("ab", 30_000_000, 45_000_000)))
        val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(a, b, 1), LandPassageState.read(mapOf(
                LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology)), topology)!!,
            metrics)).path
        val start = LandMarchCursor(path.pathHash)
        val middle = LandMarchCursor(path.pathHash, 0, 15_000_000)
        val end = LandMarchCursor(path.pathHash, 1)
        val first = PersonalTravelCondition.INITIAL.afterForcedMarch(start, middle, path, metrics)
        assertEquals(PersonalTravelCondition(3, 99, 10_000_000), first)
        val final = first.afterForcedMarch(middle, end, path, metrics)
        assertEquals(PersonalTravelCondition(10, 95), final)
        assertEquals(10_000_000, PersonalTravelDistance.at(path, middle, metrics))
        assertEquals(final, PersonalTravelCondition.read(mapOf(
            PersonalTravelCondition.META_KEY to final.toMetaValue())))
        val separateTrips = (1..3).fold(PersonalTravelCondition.INITIAL) { condition, _ ->
            condition.afterForcedMarch(start, middle, path, metrics)
        }
        assertEquals(final, separateTrips)
    }

    @Test
    fun `invalid personal condition does not silently reset`() {
        assertFailsWith<IllegalArgumentException> {
            PersonalTravelCondition.read(mapOf(PersonalTravelCondition.META_KEY to mapOf(
                "version" to 1, "fatigue" to 101, "morale" to 50)))
        }
    }

    @Test
    fun `idle recovery is bounded and preserves partial distance accounting`() {
        val tired = PersonalTravelCondition(14, 92, 10_000_000)
        assertEquals(PersonalTravelCondition(4, 97, 10_000_000), tired.afterRest())
        assertEquals(PersonalTravelCondition(0, 100, 10_000_000), tired.afterRest().afterRest())
    }
}
