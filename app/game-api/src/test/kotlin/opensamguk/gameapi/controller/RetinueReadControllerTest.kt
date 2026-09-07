package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralBugokReadEntity
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralRetainerReadEntity
import opensamguk.gameapi.read.RetainerReadRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import java.util.Optional

/** Phase 4X-A spec v3 §8 — 401 익명 / 200 본인 / 200 같은 국가 / 403 타국 / 403 재야-타인 / rules.provisional / crewTypeName 가드. */
class RetinueReadControllerTest {

    // principal() 은 thread-local SecurityContext 를 남기므로 익명 요청 앞뒤로 비운다.
    @BeforeEach fun clearContextBefore() = SecurityContextHolder.clearContext()
    @AfterEach fun clearContextAfter() = SecurityContextHolder.clearContext()

    private fun mvc(vararg controllers: Any): MockMvc =
        MockMvcBuilders.standaloneSetup(*controllers)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun gen(id: Int, nationId: Int, crewTypeId: Int = 0) =
        GeneralReadEntity(id = id, name = "장수$id", nationId = nationId, cityId = 1, officerLevel = 5, crew = 500, rice = 800, gold = 900, crewTypeId = crewTypeId)

    private fun resolved(g: GeneralReadEntity) =
        GeneralResolver.ResolvedGeneral(general = g, officerLevel = g.officerLevel, permission = 2, nationId = g.nationId, nationLevel = 3)

    private fun harness(me: GeneralReadEntity, others: List<GeneralReadEntity> = emptyList()): MockMvc {
        val resolver = mock(GeneralResolver::class.java)
        `when`(resolver.resolve(7L)).thenReturn(resolved(me))
        val generals = mock(GeneralReadRepository::class.java)
        for (g in listOf(me) + others) `when`(generals.findById(g.id)).thenReturn(Optional.of(g))
        val retinue = mock(RetainerReadRepository::class.java)
        `when`(retinue.retainersOf(me.id)).thenReturn(
            listOf(GeneralRetainerReadEntity(worldId = 1, id = 3, masterGeneralId = me.id, name = "홍길동", relation = "lieutenant", role = "GUARD", loyalty = 51, task = "train")),
        )
        `when`(retinue.bugoksOf(me.id)).thenReturn(
            listOf(GeneralBugokReadEntity(worldId = 1, id = 2, masterGeneralId = me.id, name = "부곡 1", troops = 300, crewTypeId = me.crewTypeId, training = 70, morale = 66, fatigue = 5, provisions = 900, commanderRetainerId = 3)),
        )
        return mvc(RetinueController(resolver, generals, retinue))
    }

    @Test
    fun `my-retinue is 401 anonymous and 200 with rules for the owner`() {
        val me = gen(10, nationId = 1, crewTypeId = 0)
        val m = harness(me)
        m.perform(get("/api/my-retinue")).andExpect(status().isUnauthorized)
        m.perform(get("/api/my-retinue").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generalId").value(10))
            .andExpect(jsonPath("$.retainers[0].name").value("홍길동"))
            .andExpect(jsonPath("$.retainers[0].relationLabel").value("부장"))
            .andExpect(jsonPath("$.retainers[0].taskLabel").value("훈련"))
            .andExpect(jsonPath("$.bugoks[0].provisionMonths").value(3))
            .andExpect(jsonPath("$.bugoks[0].crewTypeName").value("-")) // crew_type_id 0 → 가드(N6)
            .andExpect(jsonPath("$.rules.provisional").value(true))
            .andExpect(jsonPath("$.rules.maxRetainers").isNumber)
            .andExpect(jsonPath("$.rules.relations[1].label").value("부장"))
    }

    @Test
    fun `generals retinue allows self and same nation, forbids other nation and 재야 target`() {
        val me = gen(10, nationId = 1)
        val ally = gen(11, nationId = 1)
        val enemy = gen(12, nationId = 2)
        val wanderer = gen(13, nationId = 0)
        val m = harness(me, listOf(ally, enemy, wanderer))
        m.perform(get("/api/generals/11/retinue")).andExpect(status().isUnauthorized)
        m.perform(get("/api/generals/10/retinue").with(principal(7L))).andExpect(status().isOk)
        m.perform(get("/api/generals/11/retinue").with(principal(7L))).andExpect(status().isOk)
        m.perform(get("/api/generals/12/retinue").with(principal(7L))).andExpect(status().isForbidden)
        m.perform(get("/api/generals/13/retinue").with(principal(7L))).andExpect(status().isForbidden)
        m.perform(get("/api/generals/99/retinue").with(principal(7L))).andExpect(status().isNotFound)
    }

    @Test
    fun `재야 caller may read only self`() {
        val me = gen(20, nationId = 0)
        val other = gen(21, nationId = 0)
        val m = harness(me, listOf(other))
        m.perform(get("/api/generals/20/retinue").with(principal(7L))).andExpect(status().isOk)
        m.perform(get("/api/generals/21/retinue").with(principal(7L))).andExpect(status().isForbidden)
    }
}
