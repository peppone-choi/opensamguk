package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralTurnReadEntity
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
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
    private val world = mock(opensamguk.gameapi.read.WorldStateReadRepository::class.java)
    private val generals = mock(opensamguk.gameapi.read.GeneralReadRepository::class.java)
    private val registry = CommandRegistry(GeneralActionPipeline())

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(ReservedCommandsController(resolver, reservedTurns, world, generals, registry))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun seedWorld(
        year: Int = 200,
        month: Int = 3,
        phase: Int = 1,
        tickSeconds: Int = 3600,
        config: Map<String, Any?> = emptyMap(),
        startTime: java.time.Instant? = null,
    ) {
        org.mockito.Mockito.`when`(world.findAll()).thenReturn(
            listOf(
                opensamguk.gameapi.read.WorldStateReadEntity(
                    id = 1, scenarioCode = "che_1010", currentYear = year, currentMonth = month,
                    currentPhase = phase, tickSeconds = tickSeconds, startTime = startTime, config = config,
                ),
            ),
        )
    }

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    @BeforeEach
    fun resetFixtures() {
        clearAuth()
        reset(resolver, reservedTurns, world, generals)
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
    fun `recovers display brief from action code when an existing reserved row still has rest brief`() {
        `when`(reservedTurns.findByGeneralIdOrderByTurnIdxAsc(10)).thenReturn(
            listOf(
                GeneralTurnReadEntity(id = 1, generalId = 10, turnIdx = 0, actionCode = "che_견문", brief = "휴식"),
            ),
        )

        mockMvc().perform(get("/api/reserved-commands").param("generalId", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.slots[0].action").value("che_견문"))
            .andExpect(jsonPath("$.slots[0].brief").value("견문"))
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

    // ── W0-2(P1-004) 메타 필드 — PHP GetReservedCommand.php:69-92 ────────────────────────────────────

    @Test
    fun `exposes turnTime turnTerm year month and blocked autorunLimit`() {
        seedWorld(
            year = 200,
            month = 3,
            phase = 2,
            tickSeconds = 3600,
            config = mapOf("turntime" to "2026-06-10 09:00:00"),
            startTime = java.time.Instant.now().minusSeconds(3600),
        )
        `when`(generals.findById(10)).thenReturn(
            java.util.Optional.of(
                opensamguk.gameapi.read.GeneralReadEntity(
                    id = 10, name = "순욱", nationId = 1,
                    turnTime = java.time.Instant.parse("2026-06-10T09:30:00Z"),
                ),
            ),
        )
        `when`(reservedTurns.findByGeneralIdOrderByTurnIdxAsc(10)).thenReturn(emptyList())

        // cutTurn(09:30,60분)=09:00 == cutTurn(09:00,60분)=09:00 → 월 전진 없음(PHP :74-81).
        mockMvc().perform(get("/api/reserved-commands").param("generalId", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.turnTime").value("2026-06-10 09:30:00"))
            .andExpect(jsonPath("$.turnTerm").value(60))
            .andExpect(jsonPath("$.year").value(200))
            .andExpect(jsonPath("$.month").value(3))
            .andExpect(jsonPath("$.turnPhase").value(2))
            .andExpect(jsonPath("$.turnPhaseText").value("중순"))
            .andExpect(jsonPath("$.date").isNotEmpty)
            // autorun_limit — general.aux 원천 부재(§2 BLOCKED, ChiefReservedResponse 동일) → 미노출.
            .andExpect(jsonPath("$.autorunLimit").doesNotExist())
    }

    @Test
    fun `advances the ten-day phase when the general turn already ran this tick`() {
        seedWorld(year = 200, month = 3, tickSeconds = 3600, config = mapOf("turntime" to "2026-06-10 09:59:00"))
        `when`(generals.findById(10)).thenReturn(
            java.util.Optional.of(
                opensamguk.gameapi.read.GeneralReadEntity(
                    id = 10, name = "순욱", nationId = 1,
                    turnTime = java.time.Instant.parse("2026-06-10T10:30:00Z"),
                ),
            ),
        )
        `when`(reservedTurns.findByGeneralIdOrderByTurnIdxAsc(10)).thenReturn(emptyList())

        mockMvc().perform(get("/api/reserved-commands").param("generalId", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.year").value(200))
            .andExpect(jsonPath("$.month").value(3))
            .andExpect(jsonPath("$.turnPhase").value(2))
            .andExpect(jsonPath("$.turnPhaseText").value("중순"))
    }

    @Test
    fun `phase advance rolls over 12 late phase to next year`() {
        seedWorld(year = 200, month = 12, phase = 3, tickSeconds = 3600, config = mapOf("turntime" to "2026-06-10 09:59:00"))
        `when`(generals.findById(10)).thenReturn(
            java.util.Optional.of(
                opensamguk.gameapi.read.GeneralReadEntity(
                    id = 10, name = "순욱", nationId = 1,
                    turnTime = java.time.Instant.parse("2026-06-10T10:30:00Z"),
                ),
            ),
        )
        `when`(reservedTurns.findByGeneralIdOrderByTurnIdxAsc(10)).thenReturn(emptyList())

        mockMvc().perform(get("/api/reserved-commands").param("generalId", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.year").value(201))
            .andExpect(jsonPath("$.month").value(1))
            .andExpect(jsonPath("$.turnPhase").value(1))
            .andExpect(jsonPath("$.turnPhaseText").value("상순"))
    }
}
