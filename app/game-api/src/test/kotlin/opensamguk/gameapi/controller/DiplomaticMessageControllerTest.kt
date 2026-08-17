package opensamguk.gameapi.controller

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiplomaticMessageControllerTest {
    private val reserve = mock(CommandReserveService::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val publishedCommands = mutableListOf<TurnDaemonCommand>()

    /** OPENSAM-197 — 발행에 함께 실린 제출 계정. */
    private val publishedOwners = mutableListOf<Int?>()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun mockMvc(): MockMvc {
        val commandMatcher = any(TurnDaemonCommand::class.java)
            ?: TurnDaemonCommand.AcceptDiplomaticMessage(messageId = 0, generalId = 0)
        // OPENSAM-197 — 발행에는 제출 계정이 함께 실린다(결과 조회 소유권의 유일한 증인).
        `when`(reserve.publishImmediate(commandMatcher, anyInt())).thenAnswer { invocation ->
            publishedCommands += invocation.getArgument<TurnDaemonCommand>(0)
            publishedOwners += invocation.getArgument<Int?>(1)
            CommandReserveService.ReserveResult(requestId = "test-request", turnIdx = 0)
        }
        return MockMvcBuilders.standaloneSetup(DiplomaticMessageController(reserve, resolver))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { request ->
        val authentication = UsernamePasswordAuthenticationToken(
            userId,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        SecurityContextHolder.getContext().authentication = authentication
        request.setUserPrincipal(authentication)
        request
    }

    @Test
    fun `accept publishes a typed immediate command for the matched owner`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(55)

        mockMvc().perform(
            post("/api/messages/{id}/accept", 42)
                .param("generalId", "55")
                .with(principal(7L)),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("test-request"))

        assertEquals(
            listOf<TurnDaemonCommand>(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = 42, generalId = 55),
            ),
            publishedCommands,
        )
        // OPENSAM-197 — 제출 계정이 실려야 본인이 결과를 읽는다(이 경로엔 general_id 증인이 없다).
        assertEquals(listOf<Int?>(7), publishedOwners)
    }

    @Test
    fun `decline publishes a typed immediate command for the matched owner`() {
        `when`(resolver.resolveGeneralId(8L)).thenReturn(77)

        mockMvc().perform(
            post("/api/messages/{id}/decline", 99)
                .param("generalId", "77")
                .with(principal(8L)),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("test-request"))

        assertEquals(
            listOf<TurnDaemonCommand>(
                TurnDaemonCommand.DeclineDiplomaticMessage(messageId = 99, generalId = 77),
            ),
            publishedCommands,
        )
        assertEquals(listOf<Int?>(8), publishedOwners)
    }

    @Test
    fun `accept rejects an anonymous caller`() {
        mockMvc().perform(post("/api/messages/{id}/accept", 42).param("generalId", "55"))
            .andExpect(status().isUnauthorized)

        assertTrue(publishedCommands.isEmpty())
    }

    @Test
    fun `decline rejects an anonymous caller`() {
        mockMvc().perform(post("/api/messages/{id}/decline", 99).param("generalId", "77"))
            .andExpect(status().isUnauthorized)

        assertTrue(publishedCommands.isEmpty())
    }

    @Test
    fun `accept rejects an authenticated caller who does not own the requested general`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(12)

        mockMvc().perform(
            post("/api/messages/{id}/accept", 42)
                .param("generalId", "55")
                .with(principal(7L)),
        ).andExpect(status().isForbidden)

        assertTrue(publishedCommands.isEmpty())
    }

    @Test
    fun `decline rejects an authenticated caller who does not own the requested general`() {
        `when`(resolver.resolveGeneralId(8L)).thenReturn(33)

        mockMvc().perform(
            post("/api/messages/{id}/decline", 99)
                .param("generalId", "77")
                .with(principal(8L)),
        ).andExpect(status().isForbidden)

        assertTrue(publishedCommands.isEmpty())
    }
}
