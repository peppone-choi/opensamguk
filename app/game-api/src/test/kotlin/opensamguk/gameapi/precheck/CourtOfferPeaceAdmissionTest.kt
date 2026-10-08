package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.CourtAdmission
import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.DomesticCard
import opensamguk.logic.domestic.DomesticDiplomacy
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.input.CourtFailure
import opensamguk.logic.input.DiplomacyInput
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.Phase
import opensamguk.logic.input.RuleProfile
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CourtOfferPeaceAdmissionTest {
    private val actorId = 1
    private val phase = Phase(200, 1, 1)
    private val reader = mock(DomesticReader::class.java)
    private val catalog = InputCatalog.parse("""{"schemaVersion":4,"catalogId":"test","status":"DRAFT","note":"test",
        "inputs":[{"inputId":"court.offerPeace","kind":"COURT_DECISION","layer":1,
        "actor":"GENERAL","authorityRule":"SUBJECT_OWNER","targetSchema":{"status":"PLANNED","source":"test"},
        "costSchema":{"status":"PLANNED","source":"test","money":null,"grain":null,"iron":null,"timber":null,"horses":null},
        "timing":{"phase":"DECISION_TURN","turnSlots":null,"perPhaseLimit":null},"effectScope":"DECISION_TARGET",
        "failureReasons":[],"resultType":"InputResolved","replayContract":{"status":"PLANNED","key":"requestId"},
        "aiPolicyId":"ai.test","helpTopicId":"help.test","tutorialObjectiveId":"N/A","tutorialNaReason":"E9_PENDING_U3",
        "evidence":{},"deliveryState":"HANDLER_READY"}]}""")

    private fun state(queued: Boolean = false): DomesticProjection {
        val order = PlacementOrder("envoy-1", actorId, 5, PlacementPost.ENVOY, PlacementTarget.Nation(2), phase)
        val envoyMeta = PlacementState(ActivePlacement(order, phase, phase), null).toMetaValue()
        val ruler = DomesticPerson(actorId, "군주", 1, true, 0, 12, 70, 70, 70, 70, 70,
            null, false, mapOf(LordStatus.META_KEY to true) + if (queued) mapOf("queuedCourt" to mapOf("requestId" to "prior")) else emptyMap())
        val envoy = ruler.copy(id = 3, name = "사자", officerLevel = 1, meta = mapOf(PlacementState.META_KEY to envoyMeta))
        return DomesticProjection(RuleProfile.HWIHA, phase, listOf(ruler, envoy),
            listOf(DomesticCard(5, actorId, envoy.id, "TEST")), emptyList(),
            listOf(DomesticNation(1, "아국", null, emptyMap()), DomesticNation(2, "상대국", null, emptyMap())),
            emptySet(), diplomacy = listOf(DomesticDiplomacy(1, 2, 0, 0), DomesticDiplomacy(2, 1, 0, 0)))
    }

    @Test fun `options expose the exact war target and intake admits its numeric id`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state()))
        val options = CourtActionOptionsService(reader, catalog).options(actorId, 42L, DiplomacyInput.OFFER_PEACE)
        assertTrue(options.available)
        assertEquals(listOf(mapOf("targetNationId" to 2)), options.choices.map { it.arguments })
        assertEquals("""{"targetNationId":2}""", CourtAdmission(mock(DispatchPrecheckService::class.java),
            catalog = catalog, reader = reader).canonicalArguments(actorId, 42, DiplomacyInput.OFFER_PEACE,
            """{"targetNationId":2}"""))
    }

    @Test fun `pending court action blocks a second peace proposal in options and intake`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state(queued = true)))
        val options = CourtActionOptionsService(reader, catalog).options(actorId, 42L, DiplomacyInput.OFFER_PEACE)
        assertEquals(CourtFailure.ALREADY_QUEUED.name, options.code)
        assertEquals(CourtFailure.ALREADY_QUEUED.name, assertFailsWith<AdmissionDenied> {
            CourtAdmission(mock(DispatchPrecheckService::class.java), catalog = catalog, reader = reader)
                .canonicalArguments(actorId, 42, DiplomacyInput.OFFER_PEACE, """{"targetNationId":2}""")
        }.code)
    }
}
