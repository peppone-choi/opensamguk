package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*

class PeopleRulesTest {
    @Test fun `confirmed consent rates are bounded`() {
        val design = PeopleDesign.CANON
        assertEquals("CONFIRMED", design.status)
        assertEquals(50, design.acceptancePercent(60, 60, captive = false))
        assertEquals(30, design.acceptancePercent(60, 60, captive = true))
        assertEquals(95, design.acceptancePercent(100, 0, captive = false))
        assertEquals(5, design.acceptancePercent(0, 100, captive = true))
    }
    private val actor = DomesticPerson(7, "주인", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "province-a", false, mapOf(PersonPolicyState.META_KEY to
            PersonPolicyState(30, true, "test", "1", 7).toMetaValue()))
    private val free = actor.copy(id = 8, name = "재야", nationId = 0, userOwned = false, npcState = 2)
    private val county = DomesticCounty(11, "縣", 1, "province-a", "郡", emptyMap())
    private val base = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(actor, free), emptyList(), listOf(county), emptyList(), setOf("province-a"))

    @Test fun `search reveals only undiscovered existing free people in the current county`() {
        val available = assertIs<PeopleAssessment.Eligible>(PeopleRules.assess(
            PeopleRequest(7, PeopleInput.SEARCH, null), base))
        assertEquals(listOf(8), available.candidateIds)
        val met = actor.copy(meta = TalentDiscovery.add(actor.meta, 8))
        assertEquals(PeopleFailure.NO_CANDIDATE,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(
                PeopleRequest(7, PeopleInput.SEARCH, null), base.copy(people = listOf(met, free)))).reason)
    }

    @Test fun `employ requires a discovered free person at the live position`() {
        val request = PeopleRequest(7, PeopleInput.EMPLOY, 8)
        assertEquals(PeopleFailure.TARGET_NOT_DISCOVERED,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(request, base)).reason)
        val met = actor.copy(meta = TalentDiscovery.add(actor.meta, 8))
        assertEquals(free, assertIs<PeopleAssessment.Eligible>(PeopleRules.assess(request,
            base.copy(people = listOf(met, free)))).target)
        assertEquals(PeopleFailure.TARGET_UNAVAILABLE,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(request,
                base.copy(people = listOf(met, free.copy(node = "province-b"))))).reason)
        assertEquals(PeopleFailure.TARGET_NOT_DISCOVERED,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(
                PeopleRequest(7, PeopleInput.EMPLOY, 999), base)).reason)
    }

    @Test fun `captor alone may persuade the captive at their current location`() {
        val captive = free.copy(nationId = 2, meta = free.meta + ("hwihaCaptive" to mapOf("captorGeneralId" to 7)))
        val request = PeopleRequest(7, PeopleInput.PERSUADE_CAPTIVE, 8)
        assertEquals(captive, assertIs<PeopleAssessment.Eligible>(PeopleRules.assess(request,
            base.copy(people = listOf(actor, captive)))).target)
        assertNull(assertIs<PeopleAssessment.Eligible>(PeopleRules.assess(request,
            base.copy(people = listOf(actor, captive), counties = emptyList()))).county,
            "a captive can be addressed where the encounter happened, even outside a county seat")
        assertEquals(PeopleFailure.TARGET_NOT_CAPTIVE,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(request,
                base.copy(people = listOf(actor, captive.copy(meta = emptyMap()))))).reason)
        assertEquals(PeopleFailure.TARGET_NOT_CAPTIVE,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(request,
                base.copy(people = listOf(actor, captive.copy(userOwned = true))))).reason)
    }

    @Test fun `foreign lord captive requires nation resolution before persuasion`() {
        val request = PeopleRequest(7, PeopleInput.PERSUADE_CAPTIVE, 8)
        val captive = free.copy(nationId = 2, meta = free.meta + mapOf(
            "hwihaCaptive" to mapOf("captorGeneralId" to 7), LordStatus.META_KEY to true))
        assertEquals(PeopleFailure.TARGET_IS_LORD,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(request,
                base.copy(people = listOf(actor, captive)))).reason)
        assertEquals(PeopleFailure.STATE_UNAVAILABLE,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(request,
                base.copy(people = listOf(actor, captive.copy(meta = captive.meta + (LordStatus.META_KEY to "yes")))))).reason)
        assertIs<PeopleAssessment.Eligible>(PeopleRules.assess(request,
            base.copy(people = listOf(actor, captive.copy(meta = captive.meta + (LordStatus.META_KEY to false))))))
    }

    @Test fun `employ and captive persuasion share personal card capacity and unique name gates`() {
        val met = actor.copy(meta = TalentDiscovery.add(actor.meta, free.id))
        val noCapacity = met.copy(meta = met.meta + (PersonPolicyState.META_KEY to
            PersonPolicyState(0, true, "test", "1", 7).toMetaValue()))
        assertEquals(PeopleFailure.CAPACITY_UNAVAILABLE,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(
                PeopleRequest(7, PeopleInput.EMPLOY, 8), base.copy(people = listOf(noCapacity, free)))).reason)
        val namesake = free.copy(id = 9)
        assertEquals(PeopleFailure.DUPLICATE_RETAINER_NAME,
            assertIs<PeopleAssessment.Rejected>(PeopleRules.assess(
                PeopleRequest(7, PeopleInput.EMPLOY, 8), base.copy(
                    people = listOf(met, free, namesake), cards = listOf(DomesticCard(1, 7, 9, "guest"))))).reason)
        val captive = free.copy(nationId = 2, meta = free.meta + ("hwihaCaptive" to mapOf("captorGeneralId" to 7)))
        assertIs<PeopleAssessment.Eligible>(PeopleRules.assess(
            PeopleRequest(7, PeopleInput.PERSUADE_CAPTIVE, 8), base.copy(
                people = listOf(actor, captive), cards = listOf(DomesticCard(2, 9, 8, "guest")))))
    }
}
