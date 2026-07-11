package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.read.SelectPoolReadRow
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import opensamguk.common.wire.TurnDaemonCommand
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SelectPoolControllerTest {
    private val repository = mock(SelectPoolRepository::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val now = Instant.parse("2026-07-10T03:00:00Z")
    private val controller = SelectPoolController(repository, resolver, Clock.fixed(now, ZoneOffset.UTC))
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
        .build()

    @Test
    fun `authenticated owner receives candidate cards with uncapped raw five stats`() {
        `when`(resolver.resolveGeneralId(77L)).thenReturn(null)
        `when`(repository.listForUser(77, now)).thenReturn(
            listOf(
                SelectPoolReadRow(
                    uniqueName = "pool-a",
                    ownerUserId = 77,
                    statEditable = false,
                    reservedUntil = now.plusSeconds(120),
                    info = linkedMapOf(
                        "generalName" to "마초",
                        "picture" to "1042",
                        "imgsvr" to 0,
                        "leadership" to 91,
                        "strength" to 97,
                        "intel" to 74,
                        "politics" to 44,
                        "charm" to 88,
                        "dex" to listOf(1000, 2000, 3000, 4000, 5000),
                        "ego" to "che_의리",
                    ),
                ),
            ),
        )

        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            77L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        try {
            mvc.perform(get("/api/select-pool"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.generalId").doesNotExist())
                .andExpect(jsonPath("$.validUntil").value("2026-07-10T03:02:00Z"))
                .andExpect(jsonPath("$.pick[0].uniqueName").value("pool-a"))
                .andExpect(jsonPath("$.pick[0].generalName").value("마초"))
                .andExpect(jsonPath("$.pick[0].leadership").value(91))
                .andExpect(jsonPath("$.pick[0].strength").value(97))
                .andExpect(jsonPath("$.pick[0].intel").value(74))
                .andExpect(jsonPath("$.pick[0].politics").value(44))
                .andExpect(jsonPath("$.pick[0].charm").value(88))
                .andExpect(jsonPath("$.pick[0].statEditable").value(false))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `anonymous candidate read is rejected`() {
        SecurityContextHolder.clearContext()

        mvc.perform(get("/api/select-pool"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh publishes an owner bound daemon command`() {
        val reserve = mock(CommandReserveService::class.java)
        val refreshController = SelectPoolController(repository, resolver, Clock.fixed(now, ZoneOffset.UTC), reserve)
        val refreshMvc = MockMvcBuilders.standaloneSetup(refreshController)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        val expected = TurnDaemonCommand.SelectPoolRefresh(ownerUserId = 77, requestedAt = now.toString())
        `when`(reserve.publishImmediate(expected)).thenReturn(ReserveResult("refresh-1", 0))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            77L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        try {
            refreshMvc.perform(post("/api/select-pool/refresh"))
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.requestId").value("refresh-1"))

            verify(reserve).publishImmediate(expected)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
