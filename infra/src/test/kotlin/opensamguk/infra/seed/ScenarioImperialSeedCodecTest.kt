package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.imperial.ImperialLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ScenarioImperialSeedCodecTest {
    @Test
    fun `explicit declaration is decoded without inventing seeded general IDs`() {
        val decoded = assertNotNull(ScenarioImperialSeedCodec.read(MetaJson.decode("""
            {"imperialWorld":{"schemaVersion":1,"houses":[{
                "code":"test_line","name":"시험 계통","status":"ACTIVE",
                "holderName":"황제","designatedHeirName":null,"dynasticCandidateNames":[],
                "regentName":null,"courtNationId":5,"courtCityId":11,"legitimacy":70
            }],"allegiances":[]}}
        """.trimIndent())))
        assertEquals(ImperialLineStatus.ACTIVE, decoded.houses.single().status)
        assertEquals("황제", decoded.houses.single().holderName)
        assertEquals(11, decoded.houses.single().courtCityId)
    }

    @Test
    fun `missing declaration stays unseeded while explicit null or scalar is invalid`() {
        assertNull(ScenarioImperialSeedCodec.read(mapOf("title" to "unseeded")))
        for (value in listOf(null, "seed", emptyList<Any>())) {
            assertFailsWith<IllegalArgumentException> { ScenarioImperialSeedCodec.read(mapOf("imperialWorld" to value)) }
        }
    }

    @Test
    fun `schema and exact fields do not accept partial or future declarations`() {
        for (seed in listOf(declaration() + ("schemaVersion" to 2), declaration() - "allegiances",
                declaration() + ("transitions" to emptyList<Any>()))) {
            assertFailsWith<IllegalArgumentException> { ScenarioImperialSeedCodec.read(mapOf("imperialWorld" to seed)) }
        }
        for (row in listOf(house() - "regentName", house() + ("holderGeneralId" to 1001))) {
            assertFailsWith<IllegalArgumentException> { decodeHouse(row) }
        }
    }

    @Test
    fun `integer and enum declarations are strict without numeric coercion`() {
        for (bad in listOf<Any>(70L, 70.0, "70", true)) {
            assertFailsWith<IllegalArgumentException> { decodeHouse(house() + ("legitimacy" to bad)) }
        }
        assertFailsWith<IllegalArgumentException> { decodeHouse(house() + ("status" to "UNKNOWN")) }
        assertFailsWith<IllegalArgumentException> { decodeHouse(house() + ("courtCityId" to 11.0)) }
    }

    @Test
    fun `optional names remain null and blank or duplicate candidates fail`() {
        val decoded = assertNotNull(decodeHouse(house()))
        assertNull(decoded.houses.single().regentName)
        assertNull(decoded.houses.single().designatedHeirName)
        assertFailsWith<IllegalArgumentException> { decodeHouse(house() + ("holderName" to " ")) }
        assertFailsWith<IllegalArgumentException> {
            decodeHouse(house() + ("dynasticCandidateNames" to listOf("후계자", "후계자")))
        }
    }

    @Test
    fun `allegiance values are explicit and decoded lists do not retain source containers`() {
        val rawHouses = mutableListOf(house())
        val rawAllegiances = mutableListOf(mapOf<String, Any?>("lineCode" to "test_line", "nationId" to 5,
            "relation" to "LOYAL", "recognition" to "RECOGNIZED", "favor" to -20))
        val decoded = assertNotNull(ScenarioImperialSeedCodec.read(mapOf("imperialWorld" to
            (declaration() + mapOf("houses" to rawHouses, "allegiances" to rawAllegiances)))))
        rawHouses.clear()
        rawAllegiances.clear()
        assertEquals(1, decoded.houses.size)
        assertEquals(-20, decoded.allegiances.single().favor)
    }

    private fun decodeHouse(row: Map<String, Any?>): ScenarioImperialSeed? =
        ScenarioImperialSeedCodec.read(mapOf("imperialWorld" to (declaration() + ("houses" to listOf(row)))))

    private fun declaration(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1, "houses" to listOf(house()), "allegiances" to emptyList<Any>(),
    )

    private fun house(): Map<String, Any?> = mapOf(
        "code" to "test_line", "name" to "시험 계통", "status" to "ACTIVE", "holderName" to "황제",
        "designatedHeirName" to null, "dynasticCandidateNames" to emptyList<String>(), "regentName" to null,
        "courtNationId" to 5, "courtCityId" to 11, "legitimacy" to 70,
    )
}
