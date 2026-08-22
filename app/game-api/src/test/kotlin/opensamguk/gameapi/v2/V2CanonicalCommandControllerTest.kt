package opensamguk.gameapi.v2

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.controller.InstantActionController.IntakeAcceptedResponse
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import opensamguk.logic.v2.command.V2GarrisonRecruitArgs
import opensamguk.logic.v2.command.V2CityTransportArgs
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class V2CanonicalCommandControllerTest {
    private val reserve = mock(CommandReserveService::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val contextual = mock(V2CommandPrecheckService::class.java)
    private val controller = V2CanonicalCommandController(reserve, resolver, contextual)

    init {
        `when`(resolver.resolveGeneralId(11)).thenReturn(7)
    }

    private val recruitAvailable = V2CommandAvailability.Available(
        V2CommandRegistry.garrisonRecruitSchema,
        V2GarrisonRecruitArgs(cityId = 4, amount = 100),
    )
    private val transportAvailable = V2CommandAvailability.Available(
        V2CommandRegistry.cityTransportSchema,
        V2CityTransportArgs(1, 9, 100, 0, 0, null),
    )

    @Test
    fun `unknown v2 id returns UNKNOWN_COMMAND without entering legacy reserve`() {
        val response = controller.submit(
            commandId = "personal.travel.teleport",
            userId = 11,
            generalId = 7,
            argJson = "{}",
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("UNKNOWN", response.body?.status)
        assertEquals("UNKNOWN_COMMAND", response.body?.code)
        verifyNoInteractions(reserve, contextual)
    }

    @Test
    fun `missing arguments return NEEDS_INPUT without enqueue`() {
        val response = controller.submit(
            commandId = "city.garrison.recruit",
            userId = 11,
            generalId = 7,
            argJson = "{\"cityId\":4}",
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("NEEDS_INPUT", response.body?.status)
        assertEquals(listOf("amount"), response.body?.missing)
        verifyNoInteractions(reserve, contextual)
    }

    @Test
    fun `accepted intake acknowledgement is distinct from terminal result`() {
        `when`(contextual.precheck(7, recruitAvailable)).thenReturn(recruitAvailable)
        `when`(reserve.reserveV2(7, V2CommandRegistry.garrisonRecruitSchema, recruitAvailable.args, 11))
            .thenReturn(CommandReserveService.ReserveResult("req-7", 0))

        val response = controller.submit(
            commandId = "city.garrison.recruit",
            userId = 11,
            generalId = 7,
            argJson = "{\"cityId\":4,\"amount\":100}",
        )

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals("ACCEPTED", response.body?.status)
        assertEquals("req-7", response.body?.requestId)
        assertFalse(response.body?.terminal ?: true)
        assertEquals("city.garrison.recruit", response.body?.commandId)
    }

    @Test
    fun `legacy v2 facade preserves frozen AVAILABLE acknowledgement`() {
        `when`(reserve.reserveForOwner(7, "v2GarrisonRecruit", 0, "{\"cityId\":4,\"amount\":100}", 11))
            .thenReturn(CommandReserveService.ReserveResult("req-legacy", 0))
        val legacy = V2GarrisonRecruitController(reserve, resolver)

        val response = legacy.recruit(11, 7, "{\"cityId\":4,\"amount\":100}")
        val body = response.body as IntakeAcceptedResponse

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals("AVAILABLE", body.status)
        assertEquals("v2GarrisonRecruit", body.code)
        assertEquals("req-legacy", body.requestId)
    }

    @Test
    fun `legacy v2 facade rejects anonymous mutation`() {
        val response = V2GarrisonRecruitController(reserve, resolver)
            .recruit(null, 7, "{\"cityId\":4,\"amount\":100}")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        verifyNoInteractions(reserve)
    }

    @Test
    fun `legacy transport facade preserves alias acknowledgement for authenticated owner`() {
        val args = "{\"fromCityId\":4,\"toCityId\":5,\"gold\":1}"
        `when`(reserve.reserveForOwner(7, "v2CityTransport", 0, args, 11))
            .thenReturn(CommandReserveService.ReserveResult("req-transport", 0))

        val response = V2CityTransportController(reserve, resolver).transport(11, 7, args)
        val body = response.body as IntakeAcceptedResponse

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals("AVAILABLE", body.status)
        assertEquals("v2CityTransport", body.code)
        assertEquals("req-transport", body.requestId)
    }

    @Test
    fun `canonical transport without route revision reaches typed reservation`() {
        `when`(contextual.precheck(7, transportAvailable)).thenReturn(transportAvailable)
        `when`(reserve.reserveV2(7, V2CommandRegistry.cityTransportSchema, transportAvailable.args, 11))
            .thenReturn(CommandReserveService.ReserveResult("req-canonical-transport", 0))

        val response = controller.submit(
            "city.resources.transport", 11, 7,
            "{\"fromCityId\":1,\"toCityId\":9,\"gold\":100}",
        )

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals("req-canonical-transport", response.body?.requestId)
    }

    @Test
    fun `anonymous canonical mutation is rejected before precheck and reserve`() {
        val response = controller.submit("city.garrison.recruit", null, 7, "{\"cityId\":4,\"amount\":100}")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        verifyNoInteractions(reserve, contextual)
    }

    @Test
    fun `quoted numeric canonical argument is rejected as mistyped`() {
        val response = controller.submit(
            "city.garrison.recruit", 11, 7, "{\"cityId\":4,\"amount\":\"100\"}",
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("INVALID_ARGUMENTS", response.body?.code)
        verifyNoInteractions(reserve, contextual)
    }

    @Test
    fun `contextual deny is returned unchanged and not reserved`() {
        `when`(contextual.precheck(7, recruitAvailable)).thenReturn(
            V2CommandAvailability.Blocked("CITY_GOLD_INSUFFICIENT", "도시의 금이 부족합니다."),
        )

        val response = controller.submit(
            "city.garrison.recruit", 11, 7, "{\"cityId\":4,\"amount\":100}",
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("CITY_GOLD_INSUFFICIENT", response.body?.code)
        verifyNoInteractions(reserve)
    }
}
