package opensamguk.logic.domestic

import opensamguk.logic.input.*

import kotlin.test.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources

/** 공유 판정(접수 = 재검사). 수치 없이 권한·자리·상태만 본다. */
class DomesticRulesTest {
    private val now = Phase(200, 1, 1)
    private fun person(id: Int, nation: Int = 1, human: Boolean = false, lord: Boolean = false, level: Int = 0,
        node: String? = "p$id", npc: Int = 2, meta: Map<String, Any?> = emptyMap()) = DomesticPerson(id, "G$id", nation, human,
        if (human) 0 else npc, level, 60, 60, 60, 60, 60, node, false, meta + ("lord" to lord))

    /** 1 = 군주(사람), 2 = 1의 NPC 부장 카드(4), 3 = 1의 NPC 참모 카드(5), 6 = 다른 사람 장수(1이 거느림, 카드 8). */
    private fun state(
        people: List<DomesticPerson> = listOf(person(1, human = true, lord = true, level = 12), person(2), person(3),
            person(6, human = true)),
        cards: List<DomesticCard> = listOf(DomesticCard(4, 1, 2, "lieutenant"), DomesticCard(5, 1, 3, "staff"), DomesticCard(8, 1, 6, "guest")),
        counties: List<DomesticCounty> = listOf(county(10), county(11), county(20, nation = 2, commandery = "B郡")),
        nations: List<DomesticNation> = listOf(DomesticNation(1, "N1", 10, emptyMap()), DomesticNation(2, "N2", 20, emptyMap())),
    ) = DomesticProjection(RuleProfile.HWIHA, now, people, cards, counties, nations, setOf("p1", "p2", "p10", "p11", "p20"))

    private fun county(id: Int, nation: Int = 1, commandery: String = "A郡", meta: Map<String, Any?> = warehouse(id)) =
        DomesticCounty(id, "C$id", nation, "p$id", commandery, meta)
    private fun warehouse(id: Int) = mapOf(CountyWarehouse.META_KEY to CountyWarehouse(id, 0, Resources()).toMetaValue())

    private fun rejected(result: DomesticAssessment) = assertIs<DomesticAssessment.Rejected>(result).reason

    @Test fun `a road fort alone cannot be reduced as a county wall`() {
        val fort = CompletedWork(DomesticWork.FORTIFICATION, now, "road-piece", 1, 1)
        val withFort = county(10, meta = warehouse(10) +
            (CountyWorks.META_KEY to CountyWorks(null, listOf(fort)).toMetaValue()))
        val request = WorkRequest(1, 10, DomesticWork.FORTIFICATION)
        assertEquals(DomesticFailure.WORK_NOT_COMPLETED, rejected(DomesticRules.assessReduce(
            request, state(counties = listOf(withFort)))))
        val wall = CompletedWork(DomesticWork.FORTIFICATION, now.plus(1))
        val withWall = withFort.copy(meta = warehouse(10) + (CountyWorks.META_KEY to
            CountyWorks(null, listOf(fort, wall)).toMetaValue()))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessReduce(
            request, state(counties = listOf(withWall))))
    }

    @Test fun `lord places an owned npc card as magistrate but never a human`() {
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPlacement(
            PlacementRequest(1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(10)), state()))
        assertEquals(DomesticFailure.HUMAN_CARD, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 8, PlacementPost.MAGISTRATE, PlacementTarget.County(10)), state())))
        assertEquals(DomesticFailure.INVALID_COUNTY, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(20)), state())))
        assertEquals(DomesticFailure.CARD_NOT_FOUND, rejected(DomesticRules.assessPlacement(
            PlacementRequest(6, 4, PlacementPost.SCOUT, PlacementTarget.Province("p2")), state())))
    }

    @Test fun `a non lord may scout with its own card but not seat a magistrate`() {
        val s = state(people = listOf(person(1, human = true, lord = false), person(2), person(3), person(6, human = true)))
        assertEquals(DomesticFailure.NOT_LORD, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.MAGISTRATE, PlacementTarget.County(10)), s)))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.SCOUT, PlacementTarget.Province("p20")), s))
        assertEquals(DomesticFailure.INVALID_PROVINCE, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.SCOUT, PlacementTarget.Province("elsewhere")), s)))
        assertEquals(DomesticFailure.STATE_UNAVAILABLE, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.SCOUT, PlacementTarget.Province("p20")), s.copy(landProvinceIds = null))))
    }

    @Test fun `corps commander must be a lieutenant npc and envoys go to another capital`() {
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPlacement(
            PlacementRequest(1, 4, PlacementPost.CORPS_COMMANDER, PlacementTarget.None), state()))
        assertEquals(DomesticFailure.NOT_LIEUTENANT, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.CORPS_COMMANDER, PlacementTarget.None), state())))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.ENVOY, PlacementTarget.Nation(2)), state()))
        assertEquals(DomesticFailure.INVALID_NATION, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.ENVOY, PlacementTarget.Nation(1)), state())))
    }

    @Test fun `a seat claimed by a dispatch or another placement is occupied`() {
        val order = PlacementOrder("r1", 1, 5, PlacementPost.MAGISTRATE, PlacementTarget.County(10), now)
        val claimed = mapOf(PlacementState.META_KEY to PlacementState(null, order).toMetaValue())
        val s = state(people = listOf(person(1, human = true, lord = true, level = 12), person(2), person(3, meta = claimed),
            person(6, human = true)))
        assertEquals(DomesticFailure.COUNTY_OCCUPIED, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(10)), s)))
        val assigned = mapOf(CountyAssignment.META_KEY to CountyAssignment("d1", 1, 1, 11).toMetaValue())
        val t = state(people = listOf(person(1, human = true, lord = true, level = 12), person(2), person(3),
            person(6, human = true, meta = assigned)))
        assertEquals(DomesticFailure.COUNTY_OCCUPIED, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(11)), t)))
        // Red probe for the dispatch side: the same placement claim must also block a dispatch to that county.
        val dispatch = DispatchProjection(RuleProfile.HWIHA,
            s.people.map { DispatchPerson(it.id, it.nationId, LordStatus.read(it.meta), it.userOwned, it.meta) },
            listOf(DispatchRetainer(8, 1, 6, 50)), listOf(DispatchCounty(10, 1), DispatchCounty(11, 1)))
        assertEquals(DispatchFailure.COUNTY_OCCUPIED, assertIs<DispatchAssessment.Rejected>(
            DispatchRules.assess(DispatchRequest(1, 6, 10), dispatch)).reason)
        assertIs<DispatchAssessment.Eligible>(DispatchRules.assess(DispatchRequest(1, 6, 11), dispatch))
    }

    @Test fun `deployed or corrupt cards are not placed`() {
        val corps = DeploymentState(listOf(DeployedCorps("o1", 1, 2, 4, 1, listOf(7), now)))
        val s = state(people = listOf(person(1, human = true, lord = true, level = 12,
            meta = mapOf(DeploymentState.META_KEY to corps.toMetaValue())), person(2), person(3), person(6, human = true)))
        assertEquals(DomesticFailure.CARD_DEPLOYED, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 4, PlacementPost.SCOUT, PlacementTarget.Province("p2")), s)))
        val corrupt = state(people = listOf(person(1, human = true, lord = true, level = 12), person(2),
            person(3, meta = mapOf(PlacementState.META_KEY to "broken")), person(6, human = true)))
        assertEquals(DomesticFailure.STATE_UNAVAILABLE, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.SCOUT, PlacementTarget.Province("p2")), corrupt)))
    }

    @Test fun `release requires an existing placement and repeats are unchanged`() {
        assertEquals(DomesticFailure.NO_PLACEMENT, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.NONE, PlacementTarget.None), state())))
        val active = ActivePlacement(PlacementOrder("r1", 1, 5, PlacementPost.SCOUT, PlacementTarget.Province("p2"), now), now, null)
        val s = state(people = listOf(person(1, human = true, lord = true, level = 12), person(2),
            person(3, meta = mapOf(PlacementState.META_KEY to PlacementState(active, null).toMetaValue())), person(6, human = true)))
        assertEquals(DomesticFailure.UNCHANGED, rejected(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.SCOUT, PlacementTarget.Province("p2")), s)))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPlacement(
            PlacementRequest(1, 5, PlacementPost.NONE, PlacementTarget.None), s))
    }

    @Test fun `county policy belongs to the ruler or the seat holder within the commandery policy`() {
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPolicy(PolicyRequest(1, PolicyTarget.County(10), "COMMERCE"), state()))
        assertEquals(DomesticFailure.NOT_COUNTY_AUTHORITY, rejected(DomesticRules.assessPolicy(
            PolicyRequest(6, PolicyTarget.County(10), "COMMERCE"), state())))
        val assigned = mapOf(CountyAssignment.META_KEY to CountyAssignment("d1", 1, 1, 10).toMetaValue())
        val withSeat = state(people = listOf(person(1, human = true, lord = true, level = 12), person(2), person(3),
            person(6, human = true, meta = assigned)))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPolicy(PolicyRequest(6, PolicyTarget.County(10), "COMMERCE"), withSeat))
        assertEquals(DomesticFailure.NOT_COUNTY_AUTHORITY, rejected(DomesticRules.assessPolicy(
            PolicyRequest(6, PolicyTarget.County(11), "COMMERCE"), withSeat)))
        val upper = CommanderyPolicies(emptyList()).with("A郡",
            PolicySlot(PolicySetting("AGRICULTURE", "r1", 1, now), null))
        val ruled = withSeat.copy(nations = listOf(DomesticNation(1, "N1", 10, mapOf(CommanderyPolicies.META_KEY to upper.toMetaValue())),
            DomesticNation(2, "N2", 20, emptyMap())))
        assertEquals(DomesticFailure.UPPER_POLICY_IN_FORCE, rejected(DomesticRules.assessPolicy(
            PolicyRequest(6, PolicyTarget.County(10), "COMMERCE"), ruled)))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPolicy(PolicyRequest(1, PolicyTarget.County(10), "COMMERCE"), ruled))
    }

    @Test fun `commandery policy is the ruler's and needs a friendly county in it`() {
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPolicy(PolicyRequest(1, PolicyTarget.Commandery("A郡"), "LEVY"), state()))
        assertEquals(DomesticFailure.INVALID_COMMANDERY, rejected(DomesticRules.assessPolicy(
            PolicyRequest(1, PolicyTarget.Commandery("B郡"), "LEVY"), state())))
        assertEquals(DomesticFailure.NOTHING_TO_CLEAR, rejected(DomesticRules.assessPolicy(
            PolicyRequest(1, PolicyTarget.Commandery("A郡"), null), state())))
        val twoRulers = state(people = listOf(person(1, human = true, lord = true, level = 12), person(2), person(3),
            person(6, human = true, lord = true, level = 12)))
        assertEquals(DomesticFailure.NOT_RULER, rejected(DomesticRules.assessPolicy(
            PolicyRequest(1, PolicyTarget.Commandery("A郡"), "LEVY"), twoRulers)))
        val noGeography = state(counties = listOf(county(10).copy(commanderyId = null)))
        assertEquals(DomesticFailure.STATE_UNAVAILABLE, rejected(DomesticRules.assessPolicy(
            PolicyRequest(1, PolicyTarget.Commandery("A郡"), "LEVY"), noGeography)))
    }

    @Test fun `corps policy is set by the deployment owner`() {
        val corps = DeploymentState(listOf(DeployedCorps("o1", 1, 2, 4, 1, listOf(7), now)))
        val s = state(people = listOf(person(1, human = true, lord = true, level = 12,
            meta = mapOf(DeploymentState.META_KEY to corps.toMetaValue())), person(2), person(3), person(6, human = true)))
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessPolicy(PolicyRequest(1, PolicyTarget.Corps("o1"), "EVADE"), s))
        assertEquals(DomesticFailure.CORPS_NOT_FOUND, rejected(DomesticRules.assessPolicy(
            PolicyRequest(6, PolicyTarget.Corps("o1"), "EVADE"), s)))
    }

    @Test fun `works need authority a warehouse and a free slot`() {
        assertIs<DomesticAssessment.Eligible>(DomesticRules.assessWork(WorkRequest(1, 10, DomesticWork.IRRIGATION), state()))
        assertEquals(DomesticFailure.WAREHOUSE_NOT_READY, rejected(DomesticRules.assessWork(WorkRequest(1, 10, DomesticWork.IRRIGATION),
            state(counties = listOf(county(10, meta = emptyMap()))))))
        val active = DomesticEffects.newWork(DomesticDesign.CANON, DomesticWork.ROAD, "w1", 1, now)
        val busy = warehouse(10) + (CountyWorks.META_KEY to CountyWorks(active, emptyList()).toMetaValue())
        assertEquals(DomesticFailure.WORK_IN_PROGRESS, rejected(DomesticRules.assessWork(WorkRequest(1, 10, DomesticWork.IRRIGATION),
            state(counties = listOf(county(10, meta = busy))))))
        val done = warehouse(10) + (CountyWorks.META_KEY to CountyWorks(null,
            listOf(CompletedWork(DomesticWork.IRRIGATION, now))).toMetaValue())
        assertEquals(DomesticFailure.WORK_COMPLETED, rejected(DomesticRules.assessWork(WorkRequest(1, 10, DomesticWork.IRRIGATION),
            state(counties = listOf(county(10, meta = done))))))
        assertEquals(DomesticFailure.NOT_COUNTY_AUTHORITY, rejected(DomesticRules.assessWork(WorkRequest(6, 10, DomesticWork.ROAD), state())))
    }

    @Test fun `seated magistrate needs arrival and presence and drives the effective policy`() {
        val design = DomesticDesign.CANON
        val order = PlacementOrder("r1", 1, 4, PlacementPost.MAGISTRATE, PlacementTarget.County(10), now)
        fun with(arrived: Phase?, node: String) = state(people = listOf(person(1, human = true, lord = true, level = 12),
            person(2, node = node, meta = mapOf(PlacementState.META_KEY to
                PlacementState(ActivePlacement(order, now, arrived), null).toMetaValue())), person(3), person(6, human = true)),
            counties = listOf(county(10, meta = warehouse(10) + (CountyPolicyState.META_KEY to CountyPolicyState(
                PolicySlot(PolicySetting("COMMERCE", "r2", 1, now), null), null).toMetaValue()))))
        val seated = with(now, "p10")
        assertEquals(SeatedMagistrate(2, 1, true, 4), DomesticRules.seatedMagistrate(seated.county(10)!!, seated))
        assertEquals(EffectivePolicy(CountyPolicy.COMMERCE, PolicySource.COUNTY, SeatedMagistrate(2, 1, true, 4)),
            DomesticRules.effectivePolicy(seated.county(10)!!, seated, design))
        // Not arrived, or standing elsewhere: the seat is empty and the default policy runs.
        for (s in listOf(with(null, "p10"), with(now, "p11"))) {
            assertNull(DomesticRules.seatedMagistrate(s.county(10)!!, s))
            assertEquals(EffectivePolicy(design.defaultCountyPolicy, PolicySource.DEFAULT, null),
                DomesticRules.effectivePolicy(s.county(10)!!, s, design))
        }
    }
}
