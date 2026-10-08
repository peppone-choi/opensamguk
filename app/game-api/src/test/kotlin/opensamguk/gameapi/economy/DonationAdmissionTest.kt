package opensamguk.gameapi.economy

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.*
import opensamguk.gameapi.precheck.TransferOptionsService
import opensamguk.gameapi.reserve.*
import opensamguk.gameapi.web.TransferOptionsController
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.*
import opensamguk.logic.input.*

class DonationAdmissionTest {
    private val actor = DomesticPerson(7, "무영토 기부자", 2, true, 0, 1, 60, 60, 60, 60, 60,
        "p", false, emptyMap(), gold = 100, rice = 200)
    private val county = DomesticCounty(11, "DB 현", 1, "p", null, mapOf(CountyWarehouse.META_KEY to
        CountyWarehouse(11, 4, Resources(money = Long.MAX_VALUE - 30, grain = 5)).toMetaValue()))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), listOf(actor), emptyList(),
        listOf(county), listOf(DomesticNation(1, "수령국", 11, emptyMap())), setOf("p"))
    private val reader = mock(DomesticReader::class.java)
    private val options = TransferOptionsService(reader)
    private val admission = TransferAdmission(reader)
    private fun snapshot(state: DomesticProjection = this.state) {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state, countyNames = mapOf(11 to "수령현")))
    }
    private fun body(amount: String, resource: String = "MONEY") = """{"resource":"$resource","amount":$amount}"""
    @Test fun `landless donor options and canonical intake share current county warehouse limits`() {
        snapshot()
        val result = options.options(TransferInput.DONATE, 7, 42)
        assertTrue(result.available)
        assertEquals(30L, result.resources.single { it.resource == "MONEY" }.maxAmount)
        assertEquals(200L, result.resources.single { it.resource == "GRAIN" }.maxAmount)
        assertEquals("수령현", result.donationRecipient!!.countyName)
        assertEquals(1, result.donationRecipient!!.nationId)
        assertEquals(body("30"), admission.canonicalArguments(TransferInput.DONATE, 7, 42, 0, body("30")))
        assertEquals("STOCK_OVERFLOW", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(TransferInput.DONATE, 7, 42, 0, body("31"))
        }.code)
    }
    @Test fun `missing or invalid warehouse rejects both options and intake without legacy balance fallback`() {
        for (meta in listOf(emptyMap(), mapOf(CountyWarehouse.META_KEY to mapOf("version" to 1)))) {
            snapshot(state.copy(counties = listOf(county.copy(meta = meta))))
            val option = options.options(TransferInput.DONATE, 7, 42)
            assertFalse(option.available)
            assertEquals("STATE_UNAVAILABLE", option.code)
            assertTrue(option.resources.all { it.maxAmount == 0L })
            assertNull(option.donationRecipient)
            assertEquals(option.code, assertFailsWith<AdmissionDenied> {
                admission.canonicalArguments(TransferInput.DONATE, 7, 42, 0, body("1"))
            }.code)
        }
    }
    @Test fun `owner authentication turn slot and exact integer amount are enforced`() {
        snapshot()
        assertEquals("UNAUTHORIZED", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(TransferInput.DONATE, 7, null, 0, body("1"))
        }.code)
        assertEquals(401, TransferOptionsController(options).donate(null, 7).statusCode.value())
        for (slot in listOf(-1, 12)) assertEquals("INVALID_TURN_SLOT", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(TransferInput.DONATE, 7, 42, slot, body("1"))
        }.code)
        for (amount in listOf("0", "-1", "1.5", "\"1\"", "2147483648")) {
            assertEquals("INVALID_INPUT", assertFailsWith<AdmissionDenied> {
                admission.canonicalArguments(TransferInput.DONATE, 7, 42, 0, body(amount))
            }.code)
        }
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(7, 99)
        assertEquals(403, TransferOptionsController(options).donate(99, 7).statusCode.value())
        assertEquals("FORBIDDEN", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(TransferInput.DONATE, 7, 99, 0, body("1"))
        }.code)
    }
}
