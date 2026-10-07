package opensamguk.gameapi.reserve

import opensamguk.gameapi.dto.SiegeDto
import opensamguk.gameapi.dto.SiegePartyDto
import opensamguk.gameapi.dto.SiegePhaseDto
import opensamguk.gameapi.dto.SiegesResponse
import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.SiegeReader
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SiegeAssaultAdmissionTest {
    private val reader = mock(SiegeReader::class.java)
    private val admission = SiegeAssaultAdmission(reader)
    private val siege = SiegeDto(77, "익양현", "ACTIVE", null,
        SiegePartyDto(1, "공격", 1, "양"), 2, "여남", SiegePhaseDto(190, 1, 1), 3,
        null, 2500, 840, 40.0, false, 4000, true, true,
        canAssault = true, surrenderDemandAccepted = false, timeline = emptyList())

    @Test fun `accepted assault keeps its exact selected county`() {
        `when`(reader.sieges(1, 41L)).thenReturn(SiegesResponse("READY", listOf(siege)))
        assertEquals("""{"targetCountyId":77}""",
            admission.canonicalArguments(1, 41, 3, """{"targetCountyId":77}"""))
        assertEquals("INVALID_INPUT", blocked("{}"))
        assertEquals("TARGET_CHANGED", blocked("""{"targetCountyId":78}"""))
    }

    @Test fun `unready target and foreign actor are refused before reservation`() {
        `when`(reader.sieges(1, 41L)).thenReturn(SiegesResponse("READY", listOf(siege.copy(
            canAssault = false, assaultCode = "ASSAULT_NOT_READY", assaultReason = "3순 전"))))
        assertEquals("ASSAULT_NOT_READY", blocked("""{"targetCountyId":77}"""))
        `when`(reader.sieges(1, 41L)).thenReturn(SiegesResponse("READY", listOf(siege, siege.copy(countyId = 78))))
        assertEquals("STATE_UNAVAILABLE", blocked("""{"targetCountyId":77}"""))
        `when`(reader.sieges(1, 41L)).thenThrow(CampForbidden())
        assertEquals("FORBIDDEN", blocked("""{"targetCountyId":77}"""))
    }

    private fun blocked(raw: String): String = assertFailsWith<AdmissionDenied> {
        admission.canonicalArguments(1, 41, 3, raw)
    }.code
}
