package opensamguk.gameapi.read

import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.CompletedWork
import opensamguk.logic.domestic.CountyWorks

import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.domestic.DomesticEffects

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticFailure

import opensamguk.logic.domestic.DomesticDesign
import kotlin.test.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*

/** 조회 응답의 범위·가용 여부가 접수와 같은 규칙에서 나오는지 본다. DB 없음. */
class HwihaDomesticViewsTest {
    private val now = Phase(200, 1, 2)
    private fun person(id: Int, human: Boolean, lord: Boolean = false, level: Int = 0, node: String = "p$id",
        meta: Map<String, Any?> = emptyMap()) = DomesticPerson(id, "G$id", 1, human, if (human) 0 else 2, level,
        50, 50, 50, 50, 50, node, false, meta + ("hwihaLord" to lord))
    private val warehouse = mapOf(CountyWarehouse.META_KEY to
        CountyWarehouse(7, 3, Resources(money = 100_000)).toMetaValue())
    private fun snapshot(people: List<DomesticPerson>, counties: List<DomesticCounty> = listOf(
        DomesticCounty(7, "C7", 1, "p7", "甲郡", warehouse), DomesticCounty(8, "C8", 1, "p8", "甲郡", emptyMap()),
        DomesticCounty(9, "C9", 2, "p9", "乙郡", emptyMap()))) = HwihaDomesticSnapshot(
        DomesticProjection(RuleProfile.HWIHA, now, people, listOf(DomesticCard(5, 10, 20, "staff"), DomesticCard(6, 10, 30, "guest")),
            counties, listOf(DomesticNation(1, "N1", 7, emptyMap()), DomesticNation(2, "N2", 9, emptyMap())), setOf("p7", "p8", "p9")),
        countyNames = mapOf(7 to "갑현", 8 to "을현", 9 to "병현"), commanderyNames = mapOf("甲郡" to "갑군"),
        warehouseStocks = mapOf(7 to Resources(money = 100_000)))

    private val ruler = person(10, true, lord = true, level = 12)

    @Test fun `posts list my cards with lord gated seats and occupancy`() {
        val claimed = mapOf(PlacementState.META_KEY to PlacementState(
            ActivePlacement(PlacementOrder("r1", 10, 5, PlacementPost.MAGISTRATE, PlacementTarget.County(7), now), now, null),
            null).toMetaValue())
        val view = HwihaDomesticViews.posts(10, snapshot(listOf(ruler, person(20, false, meta = claimed), person(30, true))))
        assertEquals("READY", view.status)
        assertEquals(listOf(5, 6), view.cards.map { it.cardId })
        val card = view.cards.first()
        assertTrue(card.placeable)
        assertEquals("MOVING", card.active!!.state)
        assertEquals("갑현", card.active!!.target.label)
        assertEquals(DomesticFailure.HUMAN_CARD.name, view.cards[1].blocked!!.code)
        val magistrate = view.posts.single { it.post == "MAGISTRATE" }
        assertTrue(magistrate.available)
        assertEquals(listOf(7 to true, 8 to false), magistrate.targets!!.map { it.countyId to it.occupied })
        assertEquals(listOf(2), view.posts.single { it.post == "ENVOY" }.targets!!.map { it.nationId })
        val notLord = HwihaDomesticViews.posts(10, snapshot(listOf(person(10, true), person(20, false), person(30, true))))
        assertFalse(notLord.posts.single { it.post == "MAGISTRATE" }.available)
        assertEquals(DomesticFailure.NOT_LORD.name, notLord.posts.single { it.post == "MAGISTRATE" }.blocked!!.code)
        assertTrue(notLord.posts.single { it.post == "SCOUT" }.available)
    }

    @Test fun `policies are scoped to the ruler's nation or the seat holder and show the effective source`() {
        val view = HwihaDomesticViews.policies(10, snapshot(listOf(ruler, person(20, false), person(30, true))))
        assertEquals(listOf(7, 8), view.counties.map { it.countyId })
        assertTrue(view.counties.all { it.settable && it.effective!!.source == "DEFAULT" && it.effective!!.policy == "AGRICULTURE" })
        assertEquals(listOf("甲郡"), view.commanderies.map { it.commanderyId })
        assertEquals(listOf(7, 8), view.commanderies.single().countyIds)
        assertEquals(DomesticDesign.CONFIRMED, view.provisional)
        assertEquals(6, view.countyOptions.size); assertEquals(5, view.corpsOptions.size)
        // A human seat holder sees only its own county; a stranger sees none.
        val assigned = mapOf(CountyAssignment.META_KEY to CountyAssignment("d1", 10, 1, 8).toMetaValue())
        val holder = HwihaDomesticViews.policies(30, snapshot(listOf(ruler, person(20, false), person(30, true, meta = assigned))))
        assertEquals(listOf(8), holder.counties.map { it.countyId })
        assertTrue(holder.commanderies.isEmpty())
        assertTrue(HwihaDomesticViews.policies(20, snapshot(listOf(ruler, person(20, false), person(30, true)))).counties.isEmpty())
    }

    @Test fun `works show startable costs and active progress with stop reasons`() {
        val active = DomesticEffects.newWork(DomesticDesign.CANON, DomesticWork.ROAD, "w1", 10, Phase(200, 1, 1))
            .copy(progress = 150, charged = Resources(money = 10_000, timber = 500), stopReason = DomesticEffects.INSUFFICIENT_STOCK)
        val counties = listOf(DomesticCounty(7, "C7", 1, "p7", "甲郡", warehouse + (CountyWorks.META_KEY to
            CountyWorks(active, listOf(CompletedWork(DomesticWork.IRRIGATION, now))).toMetaValue())),
            DomesticCounty(8, "C8", 1, "p8", "甲郡", emptyMap()))
        val view = HwihaDomesticViews.works(10, snapshot(listOf(ruler, person(20, false), person(30, true)), counties))
        val c7 = view.counties.single { it.countyId == 7 }
        val row = c7.active!!
        assertEquals(25, row.percent)
        assertEquals(active.cost.money - 10_000, row.remainingCost.money)
        assertEquals("창고의 자재가 모자랍니다.", row.stopReasonText)
        assertFalse(row.startsAtNextBoundary)
        assertEquals(listOf("IRRIGATION"), c7.completed.map { it.work })
        assertTrue(c7.startable.none { it.available })
        assertEquals(DomesticFailure.WORK_IN_PROGRESS.name, c7.startable.first().blocked!!.code)
        assertEquals(100_000, c7.warehouse!!.money)
        val c8 = view.counties.single { it.countyId == 8 }
        assertEquals(DomesticFailure.WAREHOUSE_NOT_READY.name, c8.startable.first().blocked!!.code)
        assertEquals(listOf("IRRIGATION", "MILITARY_FARM", "FORTIFICATION", "ROAD", "POST_STATION", "WAREHOUSE",
            "WATCHTOWER_BEACON", "BARRACKS", "MARKET_WATERWAY"), c8.startable.map { it.work })
        assertEquals(DomesticDesign.CANON.works.getValue(DomesticWork.FORTIFICATION).cost.timber,
            c8.startable.single { it.work == "FORTIFICATION" }.cost.timber)
    }

    @Test fun `failures pass through without a projection`() {
        assertEquals("WRONG_RULE_PROFILE", HwihaDomesticViews.works(10, HwihaDomesticSnapshot(failure = "WRONG_RULE_PROFILE")).status)
        assertEquals("UNAVAILABLE", HwihaDomesticViews.posts(99, snapshot(listOf(ruler))).status)
    }
}
