package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BattlePlanReadEntity
import opensamguk.gameapi.read.BattlePlanReadRepository
import opensamguk.gameapi.read.BattleReplayReadEntity
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
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

/** spec v4.1 §8 — 401 / 403 타국 / 200 공격국·수비국·본인 / 목록 scope / rules(태세 2종 활성 + 3종 disabled 사유) / 페이즈 JSON 파싱. */
class BattlePlanReadControllerTest {

    @BeforeEach fun before() = SecurityContextHolder.clearContext()
    @AfterEach fun after() = SecurityContextHolder.clearContext()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER"))); req
    }

    private fun gen(id: Int, nationId: Int) = GeneralReadEntity(id = id, name = "장수$id", nationId = nationId, cityId = 1, officerLevel = 1, crew = 300, crewTypeId = 1100)

    private fun replay(id: Int, attackerId: Int = 10, attackerNation: Int = 1, defenderNation: Int = 2, plan: Boolean = true) = BattleReplayReadEntity(
        worldId = 1, id = id, battlePlanId = if (plan) 5 else null, operationId = null, attackerGeneralId = attackerId, attackerName = "장수$attackerId", attackerNationId = attackerNation,
        defenderCityId = 31, defenderCityName = "허창", defenderNationId = defenderNation, year = 200.toShort(), month = 3.toShort(), phase = 2.toShort(),
        warSeed = "0".repeat(32), inputHash = "a".repeat(64), replayHash = "b".repeat(64), schemaVersion = 1.toShort(),
        battlePhasesJson = "{\"phases\":[{\"contact\":true,\"crewA\":900,\"deadA\":100,\"deadD\":50,\"def\":\"화웅\",\"defId\":201,\"defKind\":\"general\",\"hpD\":150,\"i\":1}],\"stop\":{\"atPhase\":1,\"kind\":\"probe\"},\"v\":1}",
        attackerCrewBefore = 1000, attackerCrewAfter = 900, attackerDead = 100, defenderDead = 50, riceUsed = 10, result = "retreat", planStop = if (plan) "probe" else null,
        planStance = if (plan) "probe" else null, planRetreatLossPct = if (plan) 30.toShort() else null, planRetreatMoraleBelow = null,
    )

    private fun harness(me: GeneralReadEntity, plans: List<BattlePlanReadEntity> = emptyList(), replays: List<BattleReplayReadEntity> = emptyList()): MockMvc {
        val resolver = mock(GeneralResolver::class.java)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(me, me.officerLevel, 0, me.nationId, 3))
        val cities = mock(CityReadRepository::class.java); `when`(cities.findById(anyInt())).thenReturn(Optional.of(CityReadEntity(id = 31, name = "허창", nationId = 2)))
        val repo = mock(BattlePlanReadRepository::class.java)
        `when`(repo.openPlansOf(me.id)).thenReturn(plans)
        `when`(repo.replaysOfNation(me.nationId)).thenReturn(replays.filter { it.attackerNationId == me.nationId || it.defenderNationId == me.nationId })
        `when`(repo.replaysOfGeneral(me.id)).thenReturn(replays.filter { it.attackerGeneralId == me.id })
        for (r in replays) `when`(repo.findReplay(r.id)).thenReturn(r)
        return MockMvcBuilders.standaloneSetup(BattlePlanController(resolver, cities, repo, ObjectMapper())).setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver()).build()
    }

    @Test
    fun `my plans — 401 anonymous, 200 with open plans and rules`() {
        val plan = BattlePlanReadEntity(worldId = 1, id = 5, generalId = 10, targetCityId = 31, stance = "probe", retreatLossPct = 30.toShort(), sealedAt = java.time.Instant.parse("2026-09-06T12:00:00Z"), sealedYear = 200.toShort(), sealedMonth = 3.toShort(), sealedPhase = 2.toShort(), version = 2)
        val m = harness(gen(10, 1), plans = listOf(plan))
        m.perform(get("/api/my-battle-plans")).andExpect(status().isUnauthorized)
        m.perform(get("/api/my-battle-plans").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generalId").value(10))
            .andExpect(jsonPath("$.plans[0].targetCityName").value("허창"))
            .andExpect(jsonPath("$.plans[0].stanceLabel").value("탐색"))
            .andExpect(jsonPath("$.plans[0].sealed").value(true))
            .andExpect(jsonPath("$.plans[0].sealedDate.phase").value(2))
            .andExpect(jsonPath("$.plans[0].retreatLossPct").value(30))
            .andExpect(jsonPath("$.rules.provisional").value(true))
            .andExpect(jsonPath("$.rules.stances[0].enabled").value(true))
            .andExpect(jsonPath("$.rules.stances[2].enabled").value(false))
            .andExpect(jsonPath("$.rules.stances[2].reason").value("이 절편에서는 지원하지 않습니다."))
            .andExpect(jsonPath("$.rules.retreatLossPctMin").value(10))
    }

    @Test
    fun `replays — nation scope sees attacker or defender side, detail is 403 for a third nation and 200 for self`() {
        val rows = listOf(replay(1), replay(2, attackerId = 20, attackerNation = 3, defenderNation = 4, plan = false))
        val attackerSide = harness(gen(11, 1), replays = rows)
        attackerSide.perform(get("/api/battles/replays")).andExpect(status().isUnauthorized)
        attackerSide.perform(get("/api/battles/replays").with(principal(7L)))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].resultLabel").value("퇴각")).andExpect(jsonPath("$[0].hasPlan").value(true)).andExpect(jsonPath("$[0].planStopLabel").value("탐색 완료"))
        attackerSide.perform(get("/api/battles/replays/1").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.battlePhases[0].def").value("화웅"))
            .andExpect(jsonPath("$.battlePhases[0].hpD").value(150))
            .andExpect(jsonPath("$.plan.stopAtPhase").value(1))
            .andExpect(jsonPath("$.settlement.conquered").value(false))
            .andExpect(jsonPath("$.seed.replayHash").value("b".repeat(64)))
        attackerSide.perform(get("/api/battles/replays/2").with(principal(7L))).andExpect(status().isForbidden)
        attackerSide.perform(get("/api/battles/replays/99").with(principal(7L))).andExpect(status().isNotFound)
        val defenderSide = harness(gen(12, 2), replays = rows)
        defenderSide.perform(get("/api/battles/replays/1").with(principal(7L))).andExpect(status().isOk)
        // 재야 본인(공격자) — scope 와 무관하게 mine
        val self = harness(gen(20, 0), replays = rows)
        self.perform(get("/api/battles/replays?scope=nation").with(principal(7L))).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(2))
        self.perform(get("/api/battles/replays/2").with(principal(7L))).andExpect(status().isOk).andExpect(jsonPath("$.plan").doesNotExist())
        self.perform(get("/api/battles/replays/1").with(principal(7L))).andExpect(status().isForbidden)
    }
}
