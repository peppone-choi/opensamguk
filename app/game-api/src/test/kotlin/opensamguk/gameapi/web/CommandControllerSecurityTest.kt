package opensamguk.gameapi.web

import opensamguk.gameapi.config.GameApiProcessWorld

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandQueueService
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * F2 Wave 6 Task 4 — generalId ownership hardening for [CommandController]. MockMvc standalone over a
 * mocked precheck/reserve/resolver. Verifies:
 *   - an authenticated caller passing SOMEONE ELSE'S generalId → 403, precheck NEVER invoked;
 *   - an authenticated caller passing their OWN generalId → the normal flow runs;
 *   - the unauthenticated transition fallback (no principal) → the ?generalId= value is honored.
 */
class CommandControllerSecurityTest {

    private val precheck = mock(CommandPrecheckService::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val queue = mock(CommandQueueService::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val commandResults = mock(CommandResultRepository::class.java)

    // W0-4 결과 회신 시밍 — 이 테스트는 결과 엔드포인트를 호출하지 않으므로 redis는 미사용 mock.
    private val redis = mock(StringRedisTemplate::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                CommandController(
                    precheck, reserve, resolver, queue, generals, commandResults,
                    mock(CommandInboxRepository::class.java), redis,
                    ObjectMapper(), "che:scenario_2", GameApiProcessWorld(1),
                ),
            )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    @BeforeEach
    fun setup() = SecurityContextHolder.clearContext()

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    @Test
    fun `403 and no precheck when authenticated caller targets another generals id`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)

        mockMvc().perform(
            post("/api/command/{code}", "che_농지개간").param("generalId", "999").with(principal(7L)),
        )
            .andExpect(status().isForbidden)

        verify(precheck, never()).precheck(anyInt(), anyString())
    }

    @Test
    fun `authenticated caller with their own generalId can reserve a blocked forecast command`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)
        `when`(precheck.precheck(10, "che_농지개간")).thenReturn(
            PrecheckResult.Blocked("아국이 아닙니다.", "OccupiedCity"),
        )
        `when`(reserve.reserve(10, "che_농지개간", 0, null)).thenReturn(ReserveResult("req-1", 0))

        mockMvc().perform(
            post("/api/command/{code}", "che_농지개간").param("generalId", "10").with(principal(7L)),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("req-1"))
    }

    @Test
    fun `unauthenticated caller keeps the generalId transition fallback and can reserve blocked forecast commands`() {
        `when`(precheck.precheck(10, "che_농지개간")).thenReturn(
            PrecheckResult.Blocked("아국이 아닙니다.", "OccupiedCity"),
        )
        `when`(reserve.reserve(10, "che_농지개간", 0, null)).thenReturn(ReserveResult("req-2", 0))

        mockMvc().perform(
            post("/api/command/{code}", "che_농지개간").param("generalId", "10"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))

        verify(precheck).precheck(10, "che_농지개간")
    }

    @Test
    fun `unknown v2 id fails closed before the legacy registry`() {
        mockMvc().perform(
            post("/api/command/{code}", "personal.travel.teleport").param("generalId", "10"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.code").value("UNKNOWN_COMMAND"))

        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `legacy v2 alias rejects anonymous mutation`() {
        mockMvc().perform(
            post("/api/command/{code}", "v2GarrisonRecruit")
                .param("generalId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"cityId":1,"amount":100}"""),
        ).andExpect(status().isUnauthorized)

        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `legacy v2 alias rejects malformed arguments before reserve`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)

        mockMvc().perform(
            post("/api/command/{code}", "v2CityTransport")
                .param("generalId", "10")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fromCityId":1,"toCityId":2,"gold":"oops","rice":1}"""),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("INVALID_ARGUMENTS"))

        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `generic legacy transport alias accepts omitted route revision for authenticated owner`() {
        val args = """{"fromCityId":1,"toCityId":9,"gold":100}"""
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)
        `when`(reserve.reserveForOwner(10, "v2CityTransport", 0, args, 7))
            .thenReturn(ReserveResult("req-v2-transport", 0))

        mockMvc().perform(
            post("/api/command/{code}", "v2CityTransport")
                .param("generalId", "10")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(args),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.requestId").value("req-v2-transport"))

        verify(reserve).reserveForOwner(10, "v2CityTransport", 0, args, 7)
    }

    @Test
    fun `selected recruit args reach precheck while forecast reservation stays queued`() {
        val argJson = """{"crewType":1104,"amount":100}"""
        val args = linkedMapOf<String, Any?>("crewType" to 1104, "amount" to 100)
        `when`(precheck.precheck(10, "che_징병", args)).thenReturn(
            PrecheckResult.Blocked("현재 선택할 수 없는 병종입니다.", "AvailableRecruitCrewType"),
        )
        `when`(reserve.reserve(10, "che_징병", 0, argJson)).thenReturn(ReserveResult("req-recruit", 0))

        mockMvc().perform(
            post("/api/command/{code}", "che_징병")
                .param("generalId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(argJson),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("req-recruit"))

        verify(precheck).precheck(10, "che_징병", args)
        verify(reserve).reserve(10, "che_징병", 0, argJson)
    }

    @Test
    fun `immediate intake commands are accepted even when precheck has no catalog definition`() {
        `when`(precheck.precheck(10, "sendMessage")).thenReturn(PrecheckResult.Unknown(emptyList()))
        `when`(reserve.reserve(10, "sendMessage", 0, null)).thenReturn(ReserveResult("req-msg", 0))

        mockMvc().perform(
            post("/api/command/{code}", "sendMessage").param("generalId", "10"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("req-msg"))
    }

    @Test
    fun `unknown command is rejected before precheck and reservation`() {
        assertRejectedBeforePrecheckAndReservation("notACommand")
    }

    @Test
    fun `internal command is rejected before precheck and reservation`() {
        assertRejectedBeforePrecheckAndReservation("cr_건국")
    }

    @Test
    fun `chief only command is rejected before precheck and reservation`() {
        assertRejectedBeforePrecheckAndReservation("che_천도")
    }

    @Test
    fun `select pool pick reproduces the PHP fatal without reserving a command`() {
        mockMvc().perform(
            post("/api/command/{code}", "selectPoolPick")
                .param("generalId", "999")
                .with(principal(7L)),
        )
            .andExpect(status().isInternalServerError)
            .andExpect(content().string(""))

        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `select pool update remains reservable for the authenticated owner`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)
        `when`(precheck.precheck(10, "selectPoolUpdate")).thenReturn(PrecheckResult.Available)
        `when`(reserve.reserveForOwner(10, "selectPoolUpdate", 0, null, 7)).thenReturn(ReserveResult("req-update", 0))

        mockMvc().perform(
            post("/api/command/{code}", "selectPoolUpdate")
                .param("generalId", "10")
                .with(principal(7L)),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("req-update"))

        verify(precheck).precheck(10, "selectPoolUpdate")
        verify(reserve).reserveForOwner(10, "selectPoolUpdate", 0, null, 7)
    }

    @Test
    fun `select pool commands reject an unauthenticated account`() {
        mockMvc().perform(
            post("/api/command/{code}", "selectPoolPick").param("generalId", "0"),
        )
            .andExpect(status().isUnauthorized)

        verify(precheck, never()).precheck(anyInt(), anyString())
    }

    private fun assertRejectedBeforePrecheckAndReservation(code: String) {
        `when`(precheck.precheck(10, code)).thenReturn(PrecheckResult.Available)
        `when`(reserve.reserve(10, code, 0, null)).thenReturn(ReserveResult("req-$code", 0))
        clearInvocations(precheck, reserve)

        mockMvc().perform(
            post("/api/command/{code}", code).param("generalId", "10"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("BLOCKED"))
            .andExpect(jsonPath("$.reason").value("사용할 수 없는 커맨드입니다."))

        verifyNoInteractions(precheck, reserve)
    }
}
