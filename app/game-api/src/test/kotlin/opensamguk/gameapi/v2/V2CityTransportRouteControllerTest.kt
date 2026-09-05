package opensamguk.gameapi.v2

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2CommandRegistry
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.core.MethodParameter
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V2CityTransportRouteControllerTest {
    private val reserve = mock(CommandReserveService::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val precheck = mock(V2CommandPrecheckService::class.java)
    private val controller = V2CityTransportController(reserve, resolver, precheck, GameApiProcessWorld(8))
    private val args = V2CityTransportArgs(1, 2, 100, 0, 0, null)
    private val json = """{"fromCityId":1,"toCityId":2,"gold":100}"""

    init { `when`(resolver.resolveGeneralId(11)).thenReturn(10) }

    @Test
    fun `route endpoint exposes complete V3 path without reserving transport`() {
        `when`(precheck.previewTransport(10, args)).thenReturn(V2CityTransportRoutePreview(
            "AVAILABLE", route = V2CityTransportRoute(listOf("land:a", "land:b"), listOf("dry:ab"),
                listOf("LAND"), 1, 1000, "v3:abc", "topology:abc", "path:123"),
        ))
        val mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(
            object : HandlerMethodArgumentResolver {
                override fun supportsParameter(parameter: MethodParameter): Boolean =
                    parameter.hasParameterAnnotation(AuthenticationPrincipal::class.java)
                override fun resolveArgument(parameter: MethodParameter, container: ModelAndViewContainer?,
                    request: NativeWebRequest, binder: WebDataBinderFactory?): Any = 11L
            },
        ).build()
        mvc.perform(post("/api/v2/city-transport/route").param("generalId", "10")
            .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.worldId").value(8))
            .andExpect(jsonPath("$.route.nodeKeys[0]").value("land:a"))
            .andExpect(jsonPath("$.route.edgeIds[0]").value("dry:ab"))
            .andExpect(jsonPath("$.route.modes[0]").value("LAND"))
            .andExpect(jsonPath("$.route.totalCost").value(1))
            .andExpect(jsonPath("$.route.capacity").value(1000))
            .andExpect(jsonPath("$.route.topologyRevision").value("v3:abc"))
            .andExpect(jsonPath("$.route.topologyHash").value("topology:abc"))
            .andExpect(jsonPath("$.route.pathHash").value("path:123"))
        verifyNoInteractions(reserve)
    }

    @Test
    fun `route preview enforces authentication and ownership before querying state`() {
        listOf<Long?>(null, 0, -1, Int.MAX_VALUE.toLong() + 1).forEach { userId ->
            assertEquals(HttpStatus.UNAUTHORIZED, controller.route(userId, 10, json).statusCode)
        }
        assertEquals(HttpStatus.FORBIDDEN, controller.route(11, 99, json).statusCode)
        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `preview returns domain denial without enqueue and malformed amounts never query state`() {
        val blocked = V2CityTransportRoutePreview("BLOCKED", "NO_LAND_CONNECTION", "연결된 육로가 없습니다.")
        `when`(precheck.previewTransport(10, args)).thenReturn(blocked)
        assertEquals(blocked.copy(worldId = 8), controller.route(11, 10, json).body)
        assertEquals("INVALID_ARGUMENTS", controller.route(11, 10, """{"fromCityId":1,"toCityId":2,"gold":"100"}""").body?.code)
        verifyNoInteractions(reserve)
    }

    @Test
    fun `legacy AVAILABLE response keeps explicit route null under NON_NULL serialization`() {
        `when`(precheck.previewTransport(10, args)).thenReturn(V2CityTransportRoutePreview("AVAILABLE"))
        val response = controller.route(11, 10, json)
        assertEquals(HttpStatus.OK, response.statusCode)
        val mapper = ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val body = mapper.readTree(mapper.writeValueAsString(response.body))
        assertEquals("AVAILABLE", body["status"].asText())
        assertEquals(8, body["worldId"].asInt())
        assertTrue(body.has("route"))
        assertTrue(body["route"].isNull)
        verifyNoInteractions(reserve)
    }

    @Test
    fun `legacy transport endpoint rejects contextual stale path before reservation`() {
        val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, args)
        `when`(precheck.precheck(10, available)).thenReturn(V2CommandAvailability.Blocked("ROUTE_PATH_HASH_STALE", "경로가 변경되었습니다."))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, controller.transport(11, 10, json).statusCode)
        verifyNoInteractions(reserve)
    }

    @Test
    fun `transport rejects a route preview from another world before state reads or reserve`() {
        for (worldId in listOf(0, -1, 9)) {
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, controller.transport(11, 10, json, worldId).statusCode)
        }
        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `matching preview world preserves accepted not applied acknowledgement`() {
        val available = V2CommandAvailability.Available(V2CommandRegistry.cityTransportSchema, args)
        `when`(precheck.precheck(10, available)).thenReturn(available)
        `when`(reserve.reserveForOwner(10, "v2CityTransport", 0, json, 11))
            .thenReturn(CommandReserveService.ReserveResult("request-world-8", 0))
        assertEquals(HttpStatus.ACCEPTED, controller.transport(11, 10, json, 8).statusCode)
        verify(reserve).reserveForOwner(10, "v2CityTransport", 0, json, 11)
    }
}
