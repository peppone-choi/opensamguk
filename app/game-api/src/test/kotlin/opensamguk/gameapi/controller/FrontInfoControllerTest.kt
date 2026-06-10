package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
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
import java.time.Instant
import java.util.Optional

/**
 * F2 Wave 1 slice test for [FrontInfoController] — the §3 GameInfo + identity envelope. Asserts the
 * anonymous (no character) header-only shape, the resolved-general gating surface, and the `?generalId=`
 * transition fallback.
 */
class FrontInfoControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val ranks = mock(opensamguk.gameapi.read.RankDataReadRepository::class.java)
    private val auctions = mock(opensamguk.gameapi.read.AuctionCountReadRepository::class.java)
    private val votePolls = mock(opensamguk.gameapi.read.VotePollReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(FrontInfoController(resolver, world, generals, nations, cities, ranks, auctions, votePolls))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun seedWorld(config: Map<String, Any?> = emptyMap()) {
        `when`(world.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    tickSeconds = 3600,
                    config = config,
                ),
            ),
        )
        `when`(generals.count()).thenReturn(174L)
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, name = "위", color = "#00f")))
        `when`(cities.count()).thenReturn(42L)
        `when`(generals.countByNpcState(2)).thenReturn(150L)
        `when`(generals.countByNpcState(1)).thenReturn(10L)
    }

    @Test
    fun `anonymous caller gets header-only front-info with hasGeneral false`() {
        seedWorld()

        mockMvc().perform(get("/api/front-info")) // no principal, no generalId
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.global.year").value(200))
            .andExpect(jsonPath("$.global.month").value(3))
            .andExpect(jsonPath("$.global.turnterm").value(60)) // 3600s / 60
            .andExpect(jsonPath("$.global.generalCount").value(174))
            .andExpect(jsonPath("$.general.hasGeneral").value(false))
            .andExpect(jsonPath("$.nation").doesNotExist())
            .andExpect(jsonPath("$.city").doesNotExist())
    }

    @Test
    fun `global autorunUser preserves legacy limit and option shape`() {
        seedWorld(
            mapOf(
                "autorun_user" to mapOf(
                    "limit_minutes" to 120,
                    "options" to linkedMapOf(
                        "develop" to 1,
                        "recruit" to 0,
                        "recruit_high" to 2,
                    ),
                ),
            ),
        )

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.autorunUser.limit_minutes").value(120))
            .andExpect(jsonPath("$.global.autorunUser.options.develop").value(1))
            .andExpect(jsonPath("$.global.autorunUser.options.recruit").value(0))
            .andExpect(jsonPath("$.global.autorunUser.options.recruit_high").value(2))
    }

    @Test
    fun `global exposes registration mode gates for unowned character UI`() {
        seedWorld(
            mapOf(
                "npcmode" to 2,
                "block_general_create" to 1,
                "maxgeneral" to 500,
            ),
        )

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.npcMode").value(2))
            .andExpect(jsonPath("$.global.blockGeneralCreate").value(1))
            .andExpect(jsonPath("$.global.generalCntLimit").value(500))
    }

    @Test
    fun `resolved general exposes gating surface (permission derived from officer level)`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(10))
            .andExpect(jsonPath("$.general.officerLevel").value(5))
            .andExpect(jsonPath("$.general.permission").value(2))
            .andExpect(jsonPath("$.general.showSecret").value(true))
            .andExpect(jsonPath("$.nation.id").value(1))
            .andExpect(jsonPath("$.nation.level").value(7))
            .andExpect(jsonPath("$.city.name").value("허창"))
    }

    @Test
    fun `generalId query param resolves the general as a transition fallback`() {
        seedWorld()
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "관우", nationId = 0, officerLevel = 1)),
        )

        mockMvc().perform(get("/api/front-info?generalId=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(10))
            .andExpect(jsonPath("$.general.permission").value(0)) // officer_level 1 → 일반
    }
}
