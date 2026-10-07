package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection

class CourtReleaseCorpsTest {
    private val corps = DeployedCorps("release-test", 1, 2, 5, 1, listOf(7), Phase(200, 1, 1))
    private fun state(inBattle: Boolean = false, commanderMeta: Map<String, Any?> = emptyMap()): DomesticProjection {
        fun person(id: Int, ruler: Boolean, meta: Map<String, Any?>, battle: Boolean = false) =
            DomesticPerson(id, "P$id", 1, ruler, 2, if (ruler) 12 else 1,
                70, 70, 70, 70, 70, "province", battle, meta + (LordStatus.META_KEY to ruler))
        return DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
            listOf(person(1, true, mapOf(DeploymentState.META_KEY to DeploymentState(listOf(corps)).toMetaValue())),
                person(2, false, commanderMeta, inBattle)),
            emptyList(), emptyList(), listOf(DomesticNation(1, "N1", null, emptyMap())), setOf("province"))
    }
    private fun assess(state: DomesticProjection) = CourtRules.assess(1, CourtExpansionInput.RELEASE_CORPS,
        """{"targetGeneralId":2}""", state)

    @Test fun `physical battlefield prevents release`() {
        val result = assertIs<CourtAssessment.Rejected>(assess(state(inBattle = true)))
        assertEquals("BATTLE_PENDING", result.reason.name)
    }

    @Test fun `durable encounter prevents release even without physical battlefield`() {
        val result = assertIs<CourtAssessment.Rejected>(assess(state(commanderMeta = mapOf(CorpsEncounter.META_KEY to emptyMap<String, Any?>()))))
        assertEquals("BATTLE_PENDING", result.reason.name)
    }

    @Test fun `ordinary deployed corps can be released`() {
        assertEquals(corps, assertIs<CourtAssessment.Eligible>(assess(state())).ready.corps)
    }
}
