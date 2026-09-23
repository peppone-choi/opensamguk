package opensamguk.logic.input

import kotlin.test.*

class HwihaVisionSourcesTest {
    private val now = HwihaPhase(200, 1, 1)
    private fun scout(arrived: HwihaPhase?, post: PlacementPost = PlacementPost.SCOUT) = mapOf(HwihaPlacementState.META_KEY to
        HwihaPlacementState(HwihaActivePlacement(HwihaPlacementOrder("r1", 1, 4, post,
            if (post == PlacementPost.SCOUT) PlacementTarget.Province("p9") else PlacementTarget.County(10), now), now, arrived), null).toMetaValue())

    @Test fun `only arrived scouts and completed watchtowers give vision`() {
        val tower = mapOf(HwihaCountyWorks.META_KEY to HwihaCountyWorks(null, listOf(
            HwihaCompletedWork(DomesticWork.IRRIGATION, now), HwihaCompletedWork(DomesticWork.WATCHTOWER_BEACON, now.plus(1)))).toMetaValue())
        val result = HwihaVisionSources.read(
            listOf(HwihaVisionGeneral(3, 1, scout(null)), HwihaVisionGeneral(2, 1, scout(now.plus(2))),
                HwihaVisionGeneral(5, 1, scout(now, PlacementPost.MAGISTRATE)), HwihaVisionGeneral(6, 1, emptyMap())),
            listOf(HwihaVisionCounty(10, 2, "p10", tower), HwihaVisionCounty(11, 2, "p11", emptyMap())))
        assertEquals(listOf(
            HwihaVisionSource(HwihaVisionKind.SCOUT, "p9", 1, null, 1, 2, now.plus(2)),
            HwihaVisionSource(HwihaVisionKind.WATCHTOWER_BEACON, "p10", 2, 10, null, null, now.plus(1)),
        ), result.sources)
        assertTrue(result.unreadableGeneralIds.isEmpty() && result.unreadableCountyIds.isEmpty())
    }

    @Test fun `corrupt records are reported not treated as no vision`() {
        val result = HwihaVisionSources.read(listOf(HwihaVisionGeneral(2, 1, mapOf(HwihaPlacementState.META_KEY to 1))),
            listOf(HwihaVisionCounty(10, 1, "p10", mapOf(HwihaCountyWorks.META_KEY to "x")),
                HwihaVisionCounty(11, 1, null, mapOf(HwihaCountyWorks.META_KEY to HwihaCountyWorks(null,
                    listOf(HwihaCompletedWork(DomesticWork.WATCHTOWER_BEACON, now))).toMetaValue()))))
        assertEquals(listOf(2), result.unreadableGeneralIds)
        assertEquals(listOf(10, 11), result.unreadableCountyIds)
        assertTrue(result.sources.isEmpty())
    }
}
