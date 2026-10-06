package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.imperial.ImperialAllegiance
import opensamguk.logic.imperial.ImperialAllegianceRelation
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialRecognition
import opensamguk.logic.imperial.ImperialWorldCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScenarioImperialSeedMaterializerTest {
    private val roster = listOf("황제" to 1009, "후계자" to 1013, "섭정" to 1001)
    private val house = ScenarioImperialHouse("test_line", "시험 계통", ImperialLineStatus.ACTIVE,
        "황제", "후계자", listOf("후계자"), "섭정", 5, 11, 70)
    private val allegiance = ImperialAllegiance("test_line", 5, ImperialAllegianceRelation.LOYAL,
        ImperialRecognition.RECOGNIZED, -20)
    private val seed = ScenarioImperialSeed(listOf(house), listOf(allegiance))

    @Test
    fun `payload uses actual sparse IDs and cold codec round trip keeps caller values`() {
        val payload = assertNotNull(ScenarioImperialSeedMaterializer.materialize(seed, roster, setOf(5), setOf(11)))
        val state = assertNotNull(ImperialWorldCodec.read(MetaJson.decode(
            MetaJson.encode(mapOf(ImperialWorldCodec.META_KEY to payload)))))
        assertEquals(1009, state.houses.single().holderGeneralId)
        assertEquals(1013, state.houses.single().designatedHeirGeneralId)
        assertEquals(listOf(1013), state.houses.single().dynasticCandidateIds)
        assertEquals(1001, state.houses.single().regentGeneralId)
        assertEquals(house.legitimacy, state.houses.single().legitimacy)
        assertEquals(listOf(allegiance), state.allegiances)
        assertEquals(emptyList(), state.transitions)
        assertEquals(payload, ScenarioImperialSeedMaterializer.materialize(seed, roster.reversed(), setOf(5), setOf(11)))
    }

    @Test
    fun `omission produces no payload and explicit seeded empty remains distinct`() {
        assertNull(ScenarioImperialSeedMaterializer.materialize(null, emptyList(), emptySet(), emptySet()))
        val payload = assertNotNull(ScenarioImperialSeedMaterializer.materialize(
            ScenarioImperialSeed(emptyList(), emptyList()), emptyList(), emptySet(), emptySet()))
        assertEquals(emptyList(), assertNotNull(ImperialWorldCodec.read(mapOf("imperialWorld" to payload))).houses)
    }

    @Test
    fun `court references must belong to actual target world seed rows`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioImperialSeedMaterializer.materialize(seed, roster, setOf(6), setOf(11))
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioImperialSeedMaterializer.materialize(seed, roster, setOf(5), setOf(12))
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioImperialSeedMaterializer.materialize(seed, roster, setOf(0, 5), setOf(11))
        }
    }

    @Test
    fun `allegiance cannot name a nation missing from the actual seeded universe`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioImperialSeedMaterializer.materialize(seed.copy(allegiances = listOf(allegiance.copy(nationId = 6))),
                roster, setOf(5), setOf(11))
        }
    }

    @Test
    fun `missing duplicate or aliased active people never resolve to invented IDs`() {
        for (people in listOf(roster.filterNot { it.first == "황제" }, roster + ("황제" to 1020),
                roster + ("다른 장수" to 1009), roster + ("불명" to 0))) {
            assertFailsWith<IllegalArgumentException> {
                ScenarioImperialSeedMaterializer.materialize(seed, people, setOf(5), setOf(11))
            }
        }
    }

    @Test
    fun `existing imperial invariants reject invalid lifecycle legitimacy and line references`() {
        for (invalid in listOf(seed.copy(houses = listOf(house.copy(status = ImperialLineStatus.VACANT))),
                seed.copy(houses = listOf(house.copy(legitimacy = 101))),
                seed.copy(houses = listOf(house, house.copy(code = "other_line"))),
                seed.copy(allegiances = listOf(allegiance.copy(lineCode = "absent_line"))))) {
            assertFailsWith<IllegalArgumentException> {
                ScenarioImperialSeedMaterializer.materialize(invalid, roster, setOf(5), setOf(11))
            }
        }
    }

    @Test
    fun `explicit unassigned court stays null without location or protection inference`() {
        val payload = assertNotNull(ScenarioImperialSeedMaterializer.materialize(
            seed.copy(houses = listOf(house.copy(courtNationId = null, courtCityId = null)), allegiances = emptyList()),
            roster, emptySet(), emptySet()))
        val resolved = assertNotNull(ImperialWorldCodec.read(mapOf("imperialWorld" to payload))).houses.single()
        assertNull(resolved.courtNationId)
        assertNull(resolved.courtCityId)
    }

    @Test
    fun `payload is detached from mutable source collections`() {
        val houses = mutableListOf(house)
        val allegiances = mutableListOf(allegiance)
        val people = roster.toMutableList()
        val payload = assertNotNull(ScenarioImperialSeedMaterializer.materialize(
            ScenarioImperialSeed(houses, allegiances), people, setOf(5), setOf(11)))
        val before = MetaJson.encode(payload)
        houses.clear()
        allegiances.clear()
        people.clear()
        assertEquals(before, MetaJson.encode(payload))
    }
}
