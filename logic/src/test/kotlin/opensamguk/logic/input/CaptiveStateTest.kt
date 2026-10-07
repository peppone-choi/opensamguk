package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection

class CaptiveStateTest {
    private val phase = Phase(200, 1, 1)
    private val marker = CaptiveState(7, "province-a", phase, "battle-1")
    private val actor = DomesticPerson(7, "포획자", 1, true, 0, 0, 50, 50, 50, 50, 50,
        "province-a", false, emptyMap())
    private val target = actor.copy(id = 8, name = "포로", nationId = 2, userOwned = false,
        npcState = 2, meta = mapOf(CaptiveState.META_KEY to marker.toMetaValue()))
    private val state = DomesticProjection(RuleProfile.HWIHA, phase, listOf(actor, target),
        emptyList(), emptyList(), emptyList(), setOf("province-a", "province-b"))

    @Test fun `version two custody records the actual held location and explicit no expiry`() {
        val encoded = marker.toMetaValue()
        assertEquals("NONE", encoded["expiry"])
        assertEquals(marker, CaptiveState.read(mapOf(CaptiveState.META_KEY to encoded)))
        assertNull(CaptiveState.read(emptyMap()))
        assertFailsWith<IllegalArgumentException> { CaptiveState.read(mapOf(CaptiveState.META_KEY to
            mapOf("captorGeneralId" to 7, "capturedAt" to phase.toMetaValue()))) }
        assertFailsWith<IllegalArgumentException> { CaptiveState.read(mapOf(CaptiveState.META_KEY to
            encoded + ("expiry" to "NEXT_TURN"))) }
    }

    @Test fun `strict release argument and current captor location are required`() {
        val request = assertNotNull(CaptiveReleaseInput.parse(7, """{"targetGeneralId":8}"""))
        assertEquals("""{"targetGeneralId":8}""", CaptiveReleaseInput.canonicalJson(request))
        for (raw in listOf("{}", """{"targetGeneralId":"8"}""", """{"targetGeneralId":7}""",
            """{"targetGeneralId":8,"heldProvinceId":"province-a"}"""))
            assertNull(CaptiveReleaseInput.parse(7, raw))
        assertNull(CaptiveReleaseRules.assess(request, state))
        assertEquals(PeopleFailure.TARGET_UNAVAILABLE, CaptiveReleaseRules.assess(request,
            state.copy(people = listOf(actor.copy(node = "province-b"), target))))
        assertEquals(PeopleFailure.TARGET_UNAVAILABLE, CaptiveReleaseRules.assess(request,
            state.copy(people = listOf(actor, target.copy(node = "province-b")))))
        assertEquals(PeopleFailure.TARGET_NOT_CAPTIVE, CaptiveReleaseRules.assess(request,
            state.copy(people = listOf(actor, target.copy(meta = mapOf(CaptiveState.META_KEY to
                mapOf("captorGeneralId" to 7)))))))
        assertEquals(PeopleFailure.STATE_UNAVAILABLE, CaptiveReleaseRules.assess(request,
            state.copy(people = listOf(actor.copy(npcState = 5), target))))
    }
}
