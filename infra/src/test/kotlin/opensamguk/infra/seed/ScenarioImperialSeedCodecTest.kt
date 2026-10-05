package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.imperial.ImperialLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
