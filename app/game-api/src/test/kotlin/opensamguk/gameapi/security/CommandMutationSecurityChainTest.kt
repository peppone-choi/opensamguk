package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [CommandMutationSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class CommandMutationSecurityChainTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun probe() = Probe()
    }
    @RestController
    class Probe {
        @PostMapping("/api/command/{*path}") fun mutate() = "ok"
        @GetMapping("/api/command/metadata") fun metadata() = "ok"
    }
    @Autowired lateinit var context: WebApplicationContext
    private lateinit var mvc: MockMvc
    @BeforeEach fun setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build() }
    @Test fun `real chain rejects anonymous and invalid bearer mutation but preserves public reads`() {
        for (path in listOf("che_전장이동", "bulk", "push", "repeat", "nation/push", "nation/repeat")) {
            mvc.perform(post("/api/command/$path")).andExpect(status().is4xxClientError)
            mvc.perform(post("/api/command/$path").header("Authorization", "Bearer invalid")).andExpect(status().is4xxClientError)
        }
        mvc.perform(get("/api/command/metadata")).andExpect(status().isOk)
    }
    @Test fun `real signed access token reaches mutation handler`() {
        val now = Date()
        val token = Jwts.builder().subject("7").issuedAt(now).expiration(Date(now.time + 60000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN).claim(GatewayJwtClaims.ROLE, "USER")
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
        mvc.perform(post("/api/command/bulk").header("Authorization", "Bearer $token")).andExpect(status().isOk)
    }
    companion object { const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU=" }
}
