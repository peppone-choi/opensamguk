package opensamguk.logic.input

import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticProjection

import kotlin.test.*
import opensamguk.logic.economy.Resources

class MilitaryRulesTest {
    private val person = DomesticPerson(7, "장수", 2, true, 2, 1, 50, 50, 50, 50, 50,
        "province-a", false, emptyMap())
    private val county = DomesticCounty(11, "현", 2, "province-a", "군", emptyMap())
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(person), emptyList(), listOf(county), emptyList(), setOf("province-a"))
    private val design = MilitaryDesign.CANON

    @Test fun `military arguments accept only empty objects`() {
        for (id in MilitaryInput.INPUT_IDS) {
            assertEquals(MilitaryRequest(7, id), MilitaryInput.parse(7, id, "{}"))
            assertNull(MilitaryInput.parse(7, id, "{\"troops\":10}"))
            assertNull(MilitaryInput.parse(7, id, "{} extra"))
            assertNull(MilitaryInput.parse(7, id, "{\"x\":1,\"x\":2}"))
        }
    }

    @Test fun `city conscription moves households to troops with one phase warehouse cost`() {
        assertEquals(MilitaryDesign.CONFIRMED, design.status)
        val request = MilitaryRequest(7, MilitaryInput.CONSCRIPT)
        val result = MilitaryRules.assessCity(request, state, 1000, 2000, 100,
            CityMilitaryState.INITIAL, Resources(grain = 15_000), design)
        val plan = assertIs<CityMilitaryAssessment.Eligible>(result).plan
        assertEquals(950, plan.population)
        assertEquals(150, plan.troops)
        assertEquals(Resources(grain = 15_000), plan.debit)
        assertEquals(MilitaryFailure.INSUFFICIENT_STOCK,
            assertIs<CityMilitaryAssessment.Rejected>(MilitaryRules.assessCity(request, state,
                1000, 2000, 100, CityMilitaryState.INITIAL, Resources(grain = 14_999), design)).reason)
    }

    @Test fun `city troops migrate once from legacy garrison and remain separate from fortification`() {
        val migrated = CityMilitaryState.read(emptyMap(), 100)
        assertEquals(100, migrated.troops)
        assertEquals(100, CityMilitaryState.read(mapOf(CityMilitaryState.META_KEY to migrated.toMetaValue()), 900).troops)
        assertFailsWith<IllegalArgumentException> {
            CityMilitaryState.read(mapOf(CityMilitaryState.META_KEY to migrated.toMetaValue() + ("troops" to -1)))
        }
        assertFailsWith<IllegalArgumentException> {
            CityMilitaryState.read(mapOf(CityMilitaryState.META_KEY to
                mapOf("version" to 1, "training" to 50, "morale" to 50)))
        }
    }

    @Test fun `train morale and demobilize operate on separate city state and bounded households`() {
        val training = assertIs<CityMilitaryAssessment.Eligible>(MilitaryRules.assessCity(
            MilitaryRequest(7, MilitaryInput.TRAIN), state, 1000, 1010, 100,
            CityMilitaryState.INITIAL, null, design)).plan
        assertEquals(60, training.condition.training)
        assertEquals(50, training.condition.morale)
        val morale = assertIs<CityMilitaryAssessment.Eligible>(MilitaryRules.assessCity(
            MilitaryRequest(7, MilitaryInput.BOOST_MORALE), state, 1000, 1010, 100,
            training.condition, null, design)).plan
        assertEquals(60, morale.condition.morale)
        val demobilized = assertIs<CityMilitaryAssessment.Eligible>(MilitaryRules.assessCity(
            MilitaryRequest(7, MilitaryInput.DEMOBILIZE), state, 1000, 1005, 100,
            morale.condition, null, design)).plan
        assertEquals(1005, demobilized.population)
        assertEquals(95, demobilized.troops)
    }

    @Test fun `foreign county and battle reject before city effect`() {
        val request = MilitaryRequest(7, MilitaryInput.TRAIN)
        assertEquals(MilitaryFailure.FOREIGN_COUNTY,
            assertIs<CityMilitaryAssessment.Rejected>(MilitaryRules.assessCity(request,
                state.copy(counties = listOf(county.copy(nationId = 1))), 1000, 2000, 100,
                CityMilitaryState.INITIAL, null, design)).reason)
        assertEquals(MilitaryFailure.BATTLE_PENDING,
            assertIs<CityMilitaryAssessment.Rejected>(MilitaryRules.assessCity(request,
                state.copy(people = listOf(person.copy(inBattle = true))), 1000, 2000, 100,
                CityMilitaryState.INITIAL, null, design)).reason)
    }

    @Test fun `a besieged county cannot change city troops and full households cannot demobilize`() {
        val request = MilitaryRequest(7, MilitaryInput.CONSCRIPT)
        assertEquals(MilitaryFailure.BESIEGED,
            assertIs<CityMilitaryAssessment.Rejected>(MilitaryRules.assessCity(request,
                state.copy(activeSiegeCountyIds = setOf(county.id)), 1000, 2000, 100,
                CityMilitaryState.INITIAL, Resources(grain = 99_999), design)).reason)
        assertEquals(MilitaryFailure.POPULATION_FULL,
            assertIs<CityMilitaryAssessment.Rejected>(MilitaryRules.assessCity(
                MilitaryRequest(7, MilitaryInput.DEMOBILIZE), state, 1000, 1000, 100,
                CityMilitaryState.INITIAL, null, design)).reason)
    }
}
