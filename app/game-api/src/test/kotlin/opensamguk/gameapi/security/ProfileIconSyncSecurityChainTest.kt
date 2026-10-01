package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import jakarta.servlet.Filter
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.web.ProfileIconSyncController
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date
import kotlin.test.assertEquals

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [ProfileIconSyncSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class ProfileIconSyncSecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", JWT_FIXTURE, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun reserve() = mock(CommandReserveService::class.java)
    }

    @Autowired lateinit var reserve: CommandReserveService
    @Autowired @Qualifier("springSecurityFilterChain") lateinit var securityFilter: Filter

    private fun anyCommand(): TurnDaemonCommand = any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    @BeforeEach fun setup() {
        reset(reserve)
        // A regression must fail with an unexpected acceptance, not a mock-result null error.
        `when`(reserve.publishImmediate(anyCommand())).thenReturn(
            CommandReserveService.ReserveResult(requestId = "synthetic-request", turnIdx = 0))
    }

    private fun mvc(configuredToken: String): MockMvc = MockMvcBuilders
        .standaloneSetup(ProfileIconSyncController(reserve, configuredToken))
        .apply<StandaloneMockMvcBuilder>(springSecurity(securityFilter)).build()

    @Test fun `real security filters cannot authorize a disabled sync intake`() {
        for (configured in listOf("", " ", "\t")) {
            val client = mvc(configured)
            for (header in listOf(null, "", configured, SYNC_FIXTURE)) {
                val request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                if (header != null) request.header("X-Profile-Sync-Token", header)
                client.perform(request).andExpect(status().isUnauthorized)
                    .andExpect(jsonPath("$.status").value("UNAUTHORIZED"))
            }
            for (bearer in listOf("invalid", userJwt())) {
                client.perform(post(PATH).header("Authorization", "Bearer $bearer")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized)
            }
        }
        verifyNoInteractions(reserve)
    }

    @Test fun `user identity invalid bearer and query credentials cannot replace the shared header`() {
        val client = mvc(SYNC_FIXTURE)
        for (header in listOf(null, "", "wrong", "$SYNC_FIXTURE ")) {
            val request = post(PATH).header("Authorization", "Bearer ${userJwt()}")
                .param("userId", "7").param("token", SYNC_FIXTURE)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)
            if (header != null) request.header("X-Profile-Sync-Token", header)
            client.perform(request).andExpect(status().isUnauthorized)
        }
        client.perform(post(PATH).header("Authorization", "Bearer invalid")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized)
        verifyNoInteractions(reserve)
    }

    @Test fun `trusted machine without user JWT retains exact typed publication and request identity`() {
        mvc(SYNC_FIXTURE).perform(post(PATH).header("X-Profile-Sync-Token", SYNC_FIXTURE)
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.requestId").value("synthetic-request"))
        val captor = ArgumentCaptor.forClass(TurnDaemonCommand::class.java)
        verify(reserve).publishImmediate(captor.capture() ?: TurnDaemonCommand.Pause())
        assertEquals(TurnDaemonCommand.ProfileIconSync(userId = 7, picture = "abcd1234.jpg", imgsvr = 1, grade = 5),
            captor.value)
    }

    private fun userJwt(): String {
        val now = Date()
        return Jwts.builder().subject("41").issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_FIXTURE))).compact()
    }

    companion object {
        private const val PATH = "/api/internal/profile-icon-sync"
        private const val BODY = """{"userId":7,"picture":"abcd1234.jpg","imgsvr":1,"grade":5}"""
        private const val SYNC_FIXTURE = "synthetic-sync-token"
        private const val JWT_FIXTURE = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}
