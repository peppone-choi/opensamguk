package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralTurnReadEntity
import opensamguk.gameapi.read.GeneralTurnReadRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * F2 Wave 6 slice test for [ReservedCommandsController] — MockMvc standalone over a mocked
 * [GeneralTurnReadRepository] + [GeneralResolver]. Asserts the ring-slot shape
 * `{turnIdx, action, brief, arg}`, ordering, the no-id empty contract, and the Task 4 403.
 */
class ReservedCommandsControllerTest {

    private val resolver = mock(GeneralResolver::class.java)
    private val reservedTurns = mock(GeneralTurnReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(ReservedCommandsController(resolver, reservedTurns))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    @Test
    fun `returns the general reserved ring as turnIdx-action-brief-arg slots`() {
        `when`(reservedTurns.findByGeneralIdOrderByTurnIdxAsc(10)).thenReturn(
            listOf(
                GeneralTurnReadEntity(id = 1, generalId = 10, turnIdx = 0, actionCode = "che_농지개간", brief = "농지개간"),
                GeneralTurnReadEntity(
                    id = 2, generalId = 10, turnIdx = 1, actionCode = "che_출병",
                    arg = linkedMapOf("destCityID" to 5), brief = "출병",
                ),
            ),
        )

        mockMvc().perform(get("/api/reserved-commands").param("generalId", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.generalId").value(10))
            .andExpect(jsonPath("$.slots.length()").value(2))
            .andExpect(jsonPath("$.slots[0].turnIdx").value(0))
            .andExpect(jsonPath("$.slots[0].action").value("che_농지개간"))
            .andExpect(jsonPath("$.slots[0].brief").value("농지개간"))
            .andExpect(jsonPath("$.slots[1].action").value("che_출병"))
            .andExpect(jsonPath("$.slots[1].arg.destCityID").value(5))
    }

    @Test
    fun `no resolvable id returns an empty result-false ring`() {
        mockMvc().perform(get("/api/reserved-commands"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.slots.length()").value(0))
    }

    @Test
    fun `403 when authenticated caller requests a ring that is not their own`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)

        mockMvc().perform(get("/api/reserved-commands").param("generalId", "999").with(principal(7L)))
            .andExpect(status().isForbidden)
    }
}
