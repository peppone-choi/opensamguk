package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.economy.HwihaResources

class HwihaMilitaryRulesTest {
    private val person = DomesticPerson(7, "장수", 2, true, 2, 1, 50, 50, 50, 50, 50,
        "province-a", false, emptyMap())
    private val county = DomesticCounty(11, "현", 2, "province-a", "군", emptyMap())
    private val state = HwihaDomesticProjection(RuleProfile.HWIHA, HwihaPhase(200, 1, 1),
        listOf(person), emptyList(), listOf(county), emptyList(), setOf("province-a"))
    private val design = HwihaMilitaryDesign.CANON

    @Test fun `military arguments accept only empty objects`() {
        for (id in HwihaMilitaryInput.INPUT_IDS) {
            assertEquals(HwihaMilitaryRequest(7, id), HwihaMilitaryInput.parse(7, id, "{}"))
            assertNull(HwihaMilitaryInput.parse(7, id, "{\"troops\":10}"))
            assertNull(HwihaMilitaryInput.parse(7, id, "{} extra"))
            assertNull(HwihaMilitaryInput.parse(7, id, "{\"x\":1,\"x\":2}"))
        }
    }

    @Test fun `city conscription moves households to troops with one phase warehouse cost`() {
        assertEquals(HwihaMilitaryDesign.CONFIRMED, design.status)
        val request = HwihaMilitaryRequest(7, HwihaMilitaryInput.CONSCRIPT)
        val result = HwihaMilitaryRules.assessCity(request, state, 1000, 2000, 100,
            HwihaCityMilitaryState.INITIAL, HwihaResources(grain = 15_000), design)
        val plan = assertIs<HwihaCityMilitaryAssessment.Eligible>(result).plan
        assertEquals(950, plan.population)
        assertEquals(150, plan.troops)
        assertEquals(HwihaResources(grain = 15_000), plan.debit)
        assertEquals(HwihaMilitaryFailure.INSUFFICIENT_STOCK,
            assertIs<HwihaCityMilitaryAssessment.Rejected>(HwihaMilitaryRules.assessCity(request, state,
                1000, 2000, 100, HwihaCityMilitaryState.INITIAL, HwihaResources(grain = 14_999), design)).reason)
    }

    @Test fun `city troops migrate once from legacy garrison and remain separate from fortification`() {
        val migrated = HwihaCityMilitaryState.read(emptyMap(), 100)
        assertEquals(100, migrated.troops)
        assertEquals(100, HwihaCityMilitaryState.read(mapOf(HwihaCityMilitaryState.META_KEY to migrated.toMetaValue()), 900).troops)
        assertFailsWith<IllegalArgumentException> {
            HwihaCityMilitaryState.read(mapOf(HwihaCityMilitaryState.META_KEY to migrated.toMetaValue() + ("troops" to -1)))
        }
    }

    @Test fun `train morale and demobilize operate on separate city state and bounded households`() {
        val training = assertIs<HwihaCityMilitaryAssessment.Eligible>(HwihaMilitaryRules.assessCity(
            HwihaMilitaryRequest(7, HwihaMilitaryInput.TRAIN), state, 1000, 1010, 100,
            HwihaCityMilitaryState.INITIAL, null, design)).plan
        assertEquals(60, training.condition.training)
        assertEquals(50, training.condition.morale)
        val morale = assertIs<HwihaCityMilitaryAssessment.Eligible>(HwihaMilitaryRules.assessCity(
            HwihaMilitaryRequest(7, HwihaMilitaryInput.BOOST_MORALE), state, 1000, 1010, 100,
            training.condition, null, design)).plan
        assertEquals(60, morale.condition.morale)
        val demobilized = assertIs<HwihaCityMilitaryAssessment.Eligible>(HwihaMilitaryRules.assessCity(
            HwihaMilitaryRequest(7, HwihaMilitaryInput.DEMOBILIZE), state, 1000, 1005, 100,
            morale.condition, null, design)).plan
        assertEquals(1005, demobilized.population)
        assertEquals(95, demobilized.troops)
    }

    @Test fun `foreign county and battle reject before city effect`() {
        val request = HwihaMilitaryRequest(7, HwihaMilitaryInput.TRAIN)
        assertEquals(HwihaMilitaryFailure.FOREIGN_COUNTY,
            assertIs<HwihaCityMilitaryAssessment.Rejected>(HwihaMilitaryRules.assessCity(request,
                state.copy(counties = listOf(county.copy(nationId = 1))), 1000, 2000, 100,
                HwihaCityMilitaryState.INITIAL, null, design)).reason)
        assertEquals(HwihaMilitaryFailure.BATTLE_PENDING,
            assertIs<HwihaCityMilitaryAssessment.Rejected>(HwihaMilitaryRules.assessCity(request,
                state.copy(people = listOf(person.copy(inBattle = true))), 1000, 2000, 100,
                HwihaCityMilitaryState.INITIAL, null, design)).reason)
    }
}
