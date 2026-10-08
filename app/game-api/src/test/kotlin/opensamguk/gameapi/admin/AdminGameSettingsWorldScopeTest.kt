package opensamguk.gameapi.admin

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.controller.AdminReadController
import opensamguk.gameapi.read.AdminGeneralLogReadRepository
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GameKvReadRawRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.ScenarioTitleResolver
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRawRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.infra.entity.GameKvEntity
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Optional

/** Actual process-world repositories and token verification, with synthetic rows and tokens only. */
class AdminGameSettingsWorldScopeTest {
    private val worlds = mock(WorldStateReadRawRepository::class.java)
    private val env = mock(GameKvReadRawRepository::class.java)
    private val title = mock(ScenarioTitleResolver::class.java)
    private val signingBytes = ByteArray(32) { (it + 1).toByte() }
    private val verifier = GameApiJwtVerifier("", Base64.getEncoder().encodeToString(signingBytes), "2099-01-01T00:00:00Z")

    private fun mvc(worldId: Int) = MockMvcBuilders.standaloneSetup(AdminReadController(
        verifier,
        mock(NationReadRepository::class.java),
        mock(GeneralReadRepository::class.java),
        mock(CityReadRepository::class.java),
        mock(RankDataReadRepository::class.java),
        mock(DiplomacyReadRepository::class.java),
        mock(AdminGeneralLogReadRepository::class.java),
        WorldStateReadRepository(worlds, GameApiProcessWorld(worldId)),
        GameKvReadRepository(env, GameApiProcessWorld(worldId)),
        mock(GeneralTurnReadRepository::class.java), title,
    )).build()

    private fun token(role: String = "ADMIN"): String = Jwts.builder().subject("7")
        .issuedAt(Date.from(Instant.now().minusSeconds(10)))
        .expiration(Date.from(Instant.now().plusSeconds(300)))
        .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
        .claim(GatewayJwtClaims.ROLE, role)
        .signWith(Keys.hmacShaKeyFor(signingBytes)).compact()

    private fun row(id: Int, year: Int, month: Int, phase: Int, tick: Int) = WorldStateReadEntity(
        id = id, scenarioCode = "scenario_$id", currentYear = year, currentMonth = month,
        currentPhase = phase, tickSeconds = tick,
        config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "mapName" to "han-780-v1"),
    )

    @Test fun `world 160 reads its settings and notice while world 1 also exists`() {
        `when`(worlds.findById(160)).thenReturn(Optional.of(row(160, 211, 8, 3, 3600)))
        `when`(worlds.findById(1)).thenReturn(Optional.of(row(1, 180, 1, 1, 600)))
        `when`(title.titleOf("scenario_160")).thenReturn("합성 월드 160")
        `when`(env.findScoped(160, "game_env", "global", "msg")).thenReturn(
            GameKvEntity("game_env", "global", "msg", "\"월드 160 공지\"", worldId = 160),
        )
        mvc(160).perform(get("/api/admin/game-settings").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scenarioCode").value("scenario_160"))
            .andExpect(jsonPath("$.scenarioText").value("합성 월드 160"))
            .andExpect(jsonPath("$.year").value(211))
            .andExpect(jsonPath("$.month").value(8))
            .andExpect(jsonPath("$.turnPhase").value(3))
            .andExpect(jsonPath("$.turnPhaseText").value("하순"))
            .andExpect(jsonPath("$.turnterm").value(60))
            .andExpect(jsonPath("$.msg").value("월드 160 공지"))
        verify(worlds).findById(160)
        verify(worlds, never()).findById(1)
        verify(env).findScoped(160, "game_env", "global", "msg")
        verifyNoMoreInteractions(worlds, env)
    }

    @Test fun `world 1 remains supported without reading another process world`() {
        `when`(worlds.findById(1)).thenReturn(Optional.of(row(1, 180, 1, 2, 1800)))
        mvc(1).perform(get("/api/admin/game-settings").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scenarioCode").value("scenario_1"))
            .andExpect(jsonPath("$.turnterm").value(30))
        verify(worlds).findById(1)
        verifyNoMoreInteractions(worlds)
    }

    @Test fun `request world selector cannot override configured process world`() {
        `when`(worlds.findById(160)).thenReturn(Optional.of(row(160, 211, 8, 3, 3600)))
        mvc(160).perform(get("/api/admin/game-settings").param("worldId", "1")
            .header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.scenarioCode").value("scenario_160"))
        verify(worlds).findById(160)
        verifyNoMoreInteractions(worlds)
    }

    @Test fun `absent process world preserves empty display contract without borrowing world 1`() {
        `when`(worlds.findById(160)).thenReturn(Optional.empty())
        `when`(worlds.findById(1)).thenReturn(Optional.of(row(1, 180, 1, 1, 600)))
        mvc(160).perform(get("/api/admin/game-settings").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scenarioCode").doesNotExist())
            .andExpect(jsonPath("$.year").doesNotExist())
            .andExpect(jsonPath("$.turnterm").doesNotExist())
            .andExpect(jsonPath("$.startyear").value(180))
        verify(worlds).findById(160)
        verifyNoMoreInteractions(worlds)
    }

    @Test fun `invalid process world format remains a conflict rather than another world fallback`() {
        `when`(worlds.findById(160)).thenReturn(Optional.of(WorldStateReadEntity(id = 160)))
        mvc(160).perform(get("/api/admin/game-settings").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isConflict)
        verify(worlds).findById(160)
        verifyNoMoreInteractions(worlds)
        verifyNoInteractions(env, title)
    }

    @Test fun `missing malformed and invalid tokens are unauthorized before any read`() {
        val api = mvc(160)
        for (header in listOf(null, "Basic ignored", "Bearer ", "Bearer invalid")) {
            val request = get("/api/admin/game-settings")
            header?.let { request.header("Authorization", it) }
            api.perform(request).andExpect(status().isUnauthorized)
        }
        verifyNoInteractions(worlds, env, title)
    }

    @Test fun `verified user role cannot access process or requested foreign world settings`() {
        mvc(160).perform(get("/api/admin/game-settings").param("worldId", "1")
            .header("Authorization", "Bearer ${token("USER")}"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(worlds, env, title)
    }
}
