package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticCard

import opensamguk.logic.domestic.DomesticDesign
import kotlin.test.*

/**
 * 공개 꼴 계약: 시야 스트림의 reader(`HwihaMetaVisionSourceReader`)는 최상위 키가 정확히 {version, posts}, version == 1,
 * 항목 키가 정확히 {retainerId(Int), provinceId(String ≤128), status(String)} 이어야 읽고 `ACTIVE` 만 시야로 센다.
 */
class HwihaScoutPostsTest {
    private val now = HwihaPhase(200, 1, 1)
    private fun placed(retainer: Int, owner: Int, post: PlacementPost, arrived: Boolean) = mapOf(HwihaPlacementState.META_KEY to
        HwihaPlacementState(HwihaActivePlacement(HwihaPlacementOrder("r$retainer", owner, retainer, post,
            if (post == PlacementPost.SCOUT) PlacementTarget.Province("p$retainer") else PlacementTarget.County(10), now), now,
            if (arrived) now else null), null).toMetaValue())

    @Test fun `projection publishes the vision contract shape from placements`() {
        val cards = listOf(DomesticCard(5, 1, 50, "staff"), DomesticCard(4, 1, 40, "staff"), DomesticCard(6, 1, 60, "staff"),
            DomesticCard(7, 2, 70, "staff"), DomesticCard(8, 1, null, "staff"))
        val metas = mapOf(50 to placed(5, 1, PlacementPost.SCOUT, arrived = true), 40 to placed(4, 1, PlacementPost.SCOUT, arrived = false),
            60 to placed(6, 1, PlacementPost.MAGISTRATE, arrived = true), 70 to placed(7, 2, PlacementPost.SCOUT, arrived = true))
        val projected = assertNotNull(HwihaScoutPosts.project(1, cards) { metas[it] })
        assertEquals(setOf("version", "posts"), projected.keys)
        assertEquals(1, projected["version"])
        val posts = projected["posts"] as List<*>
        assertEquals(listOf(mapOf("retainerId" to 4, "provinceId" to "p4", "status" to "MOVING"),
            mapOf("retainerId" to 5, "provinceId" to "p5", "status" to "ACTIVE")), posts)
        posts.forEach { assertEquals(setOf("retainerId", "provinceId", "status"), (it as Map<*, *>).keys) }
        assertNull(HwihaScoutPosts.project(3, cards) { metas[it] })
        // A card whose placement names another owner is not published for this owner.
        assertNull(HwihaScoutPosts.project(1, listOf(DomesticCard(7, 1, 70, "staff"))) { metas[it] })
    }

    @Test fun `county works publish kind and status for the watchtower reader`() {
        val works = HwihaCountyWorks(HwihaDomesticEffects.newWork(DomesticDesign.CANON, DomesticWork.ROAD, "w", 1, now),
            listOf(HwihaCompletedWork(DomesticWork.WATCHTOWER_BEACON, now)))
        val raw = works.toMetaValue()
        assertEquals(1, raw["version"])
        val rows = raw["works"] as List<*>
        assertEquals(listOf("WATCHTOWER_BEACON" to "COMPLETE", "ROAD" to "IN_PROGRESS"),
            rows.map { (it as Map<*, *>)["kind"] to it["status"] })
        assertEquals(works, HwihaCountyWorks.read(mapOf(HwihaCountyWorks.META_KEY to raw)))
        // Reordering the in-progress entry ahead of a completed one is corruption, not a new county state.
        assertFailsWith<IllegalArgumentException> {
            HwihaCountyWorks.read(mapOf(HwihaCountyWorks.META_KEY to linkedMapOf("version" to 1, "works" to rows.reversed())))
        }
    }
}
