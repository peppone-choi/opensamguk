package opensamguk.gameapi.controller

import opensamguk.common.auth.GatewayPrincipal
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.member.MemberProfile
import opensamguk.gameapi.member.MemberProfileClient
import opensamguk.gameapi.member.MemberProfileUnavailableException
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.infra.read.SelectPoolReadRow
import opensamguk.infra.read.SelectPoolRepository
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
import java.util.Optional
import java.time.ZoneOffset

class SelectPoolControllerTest {
    private val repository = mock(SelectPoolRepository::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val memberProfiles = mock(MemberProfileClient::class.java)
    private val now = Instant.parse("2026-07-10T03:00:00Z")
    private val controller = SelectPoolController(repository, resolver, memberProfiles, Clock.fixed(now, ZoneOffset.UTC))
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
        .build()

    @Test
    fun `authenticated owner receives candidate cards with uncapped raw five stats`() {
        seedMemberRow()
        `when`(resolver.resolveGeneralId(77L)).thenReturn(null)
        `when`(repository.allowedCustomOptions()).thenReturn(setOf("stat", "ego", "picture"))
        `when`(repository.showImageLevel()).thenReturn(3)
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
            mvc.perform(
                get("/api/select-pool")
                    .requestAttr(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, profile()),
            )
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
                .andExpect(jsonPath("$.customOptions.stat").value(true))
                .andExpect(jsonPath("$.customOptions.personality").value(true))
                .andExpect(jsonPath("$.customOptions.picture").value(true))
                .andExpect(jsonPath("$.member.name").value("테스터"))
                .andExpect(jsonPath("$.member.canUsePicture").value(true))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    /** 삭제된 계정 등 `users` 행이 없으면 표시 정보를 지어내지 않고 401 로 끊는다. */
    @Test
    fun `candidate read is rejected when the member row is gone`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            77L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        try {
            mvc.perform(
                get("/api/select-pool")
                    .requestAttr(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, profile()),
            )
                .andExpect(status().isUnauthorized)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `candidate read returns 503 when gateway profile is unavailable on a cache miss`() {
        `when`(memberProfiles.get(77L)).thenThrow(MemberProfileUnavailableException())
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            77L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        try {
            mvc.perform(
                get("/api/select-pool")
                    .requestAttr(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, profile()),
            ).andExpect(status().isServiceUnavailable)
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
    fun `candidate read rejects a forged principal that disagrees with verified JWT profile`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            77L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        try {
            mvc.perform(
                get("/api/select-pool")
                    .requestAttr(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, profile(userId = 88)),
            )
                .andExpect(status().isUnauthorized)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `refresh publishes an owner bound daemon command`() {
        val reserve = mock(CommandReserveService::class.java)
        val refreshController = SelectPoolController(repository, resolver, memberProfiles, Clock.fixed(now, ZoneOffset.UTC), reserve)
        val refreshMvc = MockMvcBuilders.standaloneSetup(refreshController)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        val expected = TurnDaemonCommand.SelectPoolRefresh(ownerUserId = 77, requestedAt = now.toString())
        // OPENSAM-197 — 제출 계정이 함께 기록돼야 본인이 결과를 읽는다.
        `when`(reserve.publishImmediate(expected, 77)).thenReturn(ReserveResult("refresh-1", 0))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            77L,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        try {
            refreshMvc.perform(
                post("/api/select-pool/refresh")
                    .requestAttr(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, profile()),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.requestId").value("refresh-1"))

            verify(reserve).publishImmediate(expected, 77)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun profile(userId: Long = 77L) = GatewayPrincipal(userId = userId, role = "USER")

    private fun seedMemberRow(userId: Long = 77L) {
        `when`(memberProfiles.get(userId)).thenReturn(
            MemberProfile("테스터", 1, "member.png", 1),
        )
    }
}
