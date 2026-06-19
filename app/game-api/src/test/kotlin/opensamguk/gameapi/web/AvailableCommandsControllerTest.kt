package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
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
 * F2 Wave 6 slice test for [AvailableCommandsController] — MockMvc standalone over a mocked
 * [CommandPrecheckService] + [GeneralResolver] and a REAL [CommandRegistry] (so the catalog rows are
 * the actual `:logic` command definitions: category/name/argsSchema-derived reqArg/argType).
 *
 * Asserts the catalog shape (commandTable grouped by category), the REAL precheck wiring
 * (blocked → possible=false + reason), the registry-only fallback when there is no actor, the
 * argType derivation (che_출병 → city), and the Task 4 generalId-ownership 403.
 */
class AvailableCommandsControllerTest {

    private val resolver = mock(GeneralResolver::class.java)
    private val precheck = mock(CommandPrecheckService::class.java)
    private val registry = CommandRegistry(GeneralActionPipeline())

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(AvailableCommandsController(resolver, precheck, registry))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    @BeforeEach
    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    @Test
    fun `catalog is grouped by category and carries registry-sourced fields`() {
        // No actor resolvable → precheckAll returns null → registry-only catalog (possible=true).
        `when`(precheck.precheckAll(anyInt(), anyList<GeneralActionDefinition>())).thenReturn(null)

        mockMvc().perform(get("/api/commands/available").param("generalId", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            // grouped into categories (내정/군사/인사/국가/외교/자원교역/…)
            .andExpect(jsonPath("$.commandTable").isArray)
            .andExpect(jsonPath("$.commandTable[0].category").isNotEmpty)
            .andExpect(jsonPath("$.commandTable[0].values").isArray)
    }

    @Test
    fun `real precheck flows into possible-false plus reason for a blocked command`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)
        `when`(precheck.precheckAll(anyInt(), anyList<GeneralActionDefinition>())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val defs = inv.getArgument<List<GeneralActionDefinition>>(1)
            defs.associate { d ->
                d.key to if (d.key == "che_농지개간") {
                    PrecheckResult.Blocked("아국이 아닙니다.", "OccupiedCity")
                } else {
                    PrecheckResult.Available
                }
            }
        }

        mockMvc().perform(get("/api/commands/available").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.commandTable[*].values[?(@.value == 'che_농지개간')].possible").value(false),
            )
            .andExpect(
                jsonPath("$.commandTable[*].values[?(@.value == 'che_농지개간')].reason").value("아국이 아닙니다."),
            )
    }

    @Test
    fun `reqArg and argType are derived from the command argsSchema`() {
        `when`(precheck.precheckAll(anyInt(), anyList<GeneralActionDefinition>())).thenReturn(null)

        mockMvc().perform(get("/api/commands/available").param("generalId", "10"))
            .andExpect(status().isOk)
            // che_출병 declares argsSchema {destCityID} → reqArg true, argType city.
            .andExpect(
                jsonPath("$.commandTable[*].values[?(@.value == 'che_출병')].reqArg").value(true),
            )
            .andExpect(
                jsonPath("$.commandTable[*].values[?(@.value == 'che_출병')].argType").value("city"),
            )
            // che_상업투자 has no args → reqArg false.
            .andExpect(
                jsonPath("$.commandTable[*].values[?(@.value == 'che_상업투자')].reqArg").value(false),
            )
    }

    @Test
    fun `403 when authenticated caller passes a generalId that is not their own`() {
        `when`(resolver.resolveGeneralId(7L)).thenReturn(10)

        mockMvc().perform(get("/api/commands/available").param("generalId", "999").with(principal(7L)))
            .andExpect(status().isForbidden)
    }
}
