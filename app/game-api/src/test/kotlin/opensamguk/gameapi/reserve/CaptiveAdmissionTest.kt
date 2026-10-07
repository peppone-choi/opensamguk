package opensamguk.gameapi.reserve

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.Phase
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptiveAdmissionTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val admission = CaptiveAdmission(generals)

    private fun actor(meta: Map<String, Any?> = emptyMap()) {
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10,
            userId = "42", meta = meta)))
    }

    @Test fun `free owner can reserve but valid and old captive markers deny`() {
        actor()
        admission.requireFreeActor(10, 42)
        val marker = CaptiveState(20, "p10", Phase(200, 1, 1), "battle-10").toMetaValue()
        for (value in listOf(marker, mapOf("version" to 1, "captorGeneralId" to 20))) {
            actor(mapOf(CaptiveState.META_KEY to value))
            assertEquals("STATE_UNAVAILABLE", assertFailsWith<AdmissionDenied> {
                admission.requireFreeActor(10, 42)
            }.code)
        }
    }

    @Test fun `unknown or wrong owner fails closed`() {
        assertEquals("UNAUTHORIZED", assertFailsWith<AdmissionDenied> {
            admission.requireFreeActor(10, null)
        }.code)
        `when`(generals.findById(10)).thenReturn(Optional.empty())
        assertEquals("STATE_UNAVAILABLE", assertFailsWith<AdmissionDenied> {
            admission.requireFreeActor(10, 42)
        }.code)
        actor()
        assertEquals("FORBIDDEN", assertFailsWith<AdmissionDenied> {
            admission.requireFreeActor(10, 43)
        }.code)
    }
}
