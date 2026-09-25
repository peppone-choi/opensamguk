package opensamguk.logic.input

import opensamguk.logic.vision.VisionRules
import opensamguk.logic.vision.VisionSourceKind

import opensamguk.logic.vision.ScoutPosts

import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.CompletedWork
import opensamguk.logic.domestic.CountyWorks

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.domestic.DomesticEffects

import opensamguk.logic.domestic.DomesticCard

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.logic.world.HanCommandery
import opensamguk.logic.world.HanCommanderyIndex
import kotlin.test.*

/**
 * 통합 계약: 내정 스트림이 **쓰는** 꼴(`hwihaScoutPosts` 장수 meta, `hwihaCountyWorks` 縣治 meta)을 시야 스트림의
 * reader([HwihaMetaVisionSourceReader])가 그대로 읽어 시야 투영에서 FULL 이 된다. 어느 한쪽이 키·꼴을 바꾸면
 * 여기서 빨개진다(두 스트림 각각의 단위 테스트는 자기 쪽 꼴만 본다).
 */
class HwihaDomesticVisionContractTest {
    private val hash = "a".repeat(64)
    // 0 — 1 — 2 — 3 — 4 (line); province pN belongs to commandery N.
    private val index = HanCommanderyIndex(hash, (0..4).map { HanCommandery(it, "PARENT-$it", "군$it", "郡$it") },
        (0..4).associate { "p$it" to it }, setOf(0 to 1, 1 to 2, 2 to 3, 3 to 4))
    private val now = HwihaPhase(200, 1, 1)
    private val reader: HwihaVisionSourceReader = HwihaMetaVisionSourceReader

    private fun scoutPlacement(retainer: Int, owner: Int, province: String, arrived: Boolean) = mapOf(PlacementState.META_KEY to
        PlacementState(ActivePlacement(PlacementOrder("r$retainer", owner, retainer, PlacementPost.SCOUT,
            PlacementTarget.Province(province), now), now, if (arrived) now else null), null).toMetaValue())

    private fun viewer(posts: List<HwihaScoutPost>, towers: List<Pair<Int, String>>) =
        VisionViewer(1, 1, null, emptyMap(), emptyMap(), emptySet(), posts, towers, null)

    private fun tiers(view: HwihaVisionView) = view.entries.map { it.tier }

    @Test fun `a domestic-written arrived scout post is FULL vision and a marching one is none`() {
        val cards = listOf(DomesticCard(5, 1, 50, "staff"), DomesticCard(6, 1, 60, "staff"))
        val placements = mapOf(50 to scoutPlacement(5, 1, "p2", arrived = true), 60 to scoutPlacement(6, 1, "p4", arrived = false))
        val ownerMeta = mapOf<String, Any?>(ScoutPosts.META_KEY to ScoutPosts.project(1, cards) { placements[it] })

        assertEquals(HwihaMetaVisionSourceReader.SCOUT_POSTS_KEY, ScoutPosts.META_KEY)
        val read = reader.scoutPosts(ownerMeta)
        assertEquals(SourceRead(listOf(HwihaScoutPost(5, "p2")), 0), read, "도착한 정찰만 시야, 행군 중(MOVING)은 무효가 아니라 무시")

        val view = HwihaVision.project(viewer(read.value, emptyList()), index, VisionRules.CANON, now)
        assertEquals(listOf(VisionTier.FOG, VisionTier.FULL, VisionTier.FULL, VisionTier.FULL, VisionTier.FOG), tiers(view))
        assertEquals(listOf(VisionSource(VisionSourceKind.SCOUT_POST, 2, VisionRules.CANON.radius(VisionSourceKind.SCOUT_POST), "p2", 5)),
            view.sources)
    }

    @Test fun `a domestic-written completed watchtower beacon is FULL vision and an unfinished one is none`() {
        assertEquals(HwihaMetaVisionSourceReader.COUNTY_WORKS_KEY, CountyWorks.META_KEY)
        assertEquals(HwihaMetaVisionSourceReader.WATCHTOWER_BEACON, DomesticWork.WATCHTOWER_BEACON.name)
        val done = mapOf<String, Any?>(CountyWorks.META_KEY to CountyWorks(
            DomesticEffects.newWork(DomesticDesign.CANON, DomesticWork.ROAD, "w", 1, now),
            listOf(CompletedWork(DomesticWork.WATCHTOWER_BEACON, now))).toMetaValue())
        val building = mapOf<String, Any?>(CountyWorks.META_KEY to CountyWorks(
            DomesticEffects.newWork(DomesticDesign.CANON, DomesticWork.WATCHTOWER_BEACON, "w", 1, now), emptyList()).toMetaValue())

        assertEquals(SourceRead(true, 0), reader.hasCompletedWatchtower(done))
        assertEquals(SourceRead(false, 0), reader.hasCompletedWatchtower(building), "진행 중 망루봉화는 시야가 아니다(무효도 아니다)")

        val view = HwihaVision.project(viewer(emptyList(), listOf(40 to "p4")), index, VisionRules.CANON, now)
        assertEquals(listOf(VisionTier.FOG, VisionTier.FOG, VisionTier.FOG, VisionTier.FULL, VisionTier.FULL), tiers(view))
    }
}
