package opensamguk.gameapi.council

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.gameapi.security.GameApiSecurityConfig
import opensamguk.gameapi.security.JwtVerifyFilter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [CouncilSecurityChainTest.Config::class, GameApiSecurityConfig::class])
class CouncilSecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver(): GeneralResolver = mock(GeneralResolver::class.java)
        @Bean open fun world(): WorldStateReadRepository = mock(WorldStateReadRepository::class.java)
        @Bean open fun nations(): NationReadRepository = mock(NationReadRepository::class.java)
        @Bean open fun generals(): GeneralReadRepository = mock(GeneralReadRepository::class.java)
        @Bean open fun posts(): BoardPostReadRepository = mock(BoardPostReadRepository::class.java)
        @Bean open fun comments(): BoardCommentReadRepository = mock(BoardCommentReadRepository::class.java)
        @Bean open fun reads(): BoardPostReadLogRepository = mock(BoardPostReadLogRepository::class.java)
        @Bean open fun authority(): CouncilAuthoritySource = mock(CouncilAuthoritySource::class.java)
        @Bean open fun reader(resolver: GeneralResolver, world: WorldStateReadRepository, nations: NationReadRepository,
            generals: GeneralReadRepository, posts: BoardPostReadRepository, comments: BoardCommentReadRepository,
            reads: BoardPostReadLogRepository, authority: CouncilAuthoritySource) = CouncilReader(
                resolver, world, nations, generals, posts, comments, reads, authority, GameApiProcessWorld(1))
        @Bean open fun controller(reader: CouncilReader) = CouncilController(reader)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var world: WorldStateReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var comments: BoardCommentReadRepository
    @Autowired lateinit var reads: BoardPostReadLogRepository
    @Autowired lateinit var authority: CouncilAuthoritySource
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun prepare() {
        reset(resolver, world, nations, generals, posts, comments, reads, authority)
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `익명과 잘못된 토큰은 방 글 댓글 열람 하위경로 전체에서 정확401이다`() {
        val requests = listOf(get("/api/council"), get("/api/council/articles/40"),
            post("/api/council/articles"), post("/api/council/articles/40/comments"),
            post("/api/council/articles/40/read"), put("/api/council/participants/102"),
            delete("/api/council/participants/102"))
        requests.forEach { request -> mvc.perform(request).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED")) }
        mvc.perform(get("/api/council").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
        verifyNoInteractions(resolver, world, nations, generals, posts, comments, reads, authority)
    }

    @Test
    fun `인증된 미소유자와 ADMIN도403이며 익명401과 구분된다`() {
        listOf("USER", "ADMIN").forEach { role ->
            mvc.perform(get("/api/council").header("Authorization", "Bearer ${token(role)}"))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
        }
        verifyNoInteractions(world, nations, generals, posts, comments, reads, authority)
    }

    @Test
    fun `본인 무소속은 두방200빈봉투를 받고 다른 세력 private쿼리가 없다`() {
        val me = GeneralReadEntity(id = 101, worldId = 1, userId = "7", nationId = 0)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(me, 1, 0, 0, 0))
        CouncilRoom.entries.forEach { room ->
            mvc.perform(get("/api/council").param("room", room.name).header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.articles.length()").value(0))
                .andExpect(jsonPath("$.members.length()").value(0)).andExpect(jsonPath("$.access.canRead").value(false))
                .andExpect(jsonPath("$.access.reason").value("NO_AFFILIATION"))
                .andExpect(jsonPath("$.blockedReason").isNotEmpty)
        }
        verifyNoInteractions(world, nations, generals, posts, comments, reads, authority)
    }

    @Test
    fun `client장수 세력 선택과 표결 종류는 새계약 입력으로 허용하지 않는다`() {
        listOf("nationId" to "4", "generalId" to "202", "kind" to "VOTE").forEach { (key, value) ->
            mvc.perform(get("/api/council").param(key, value).header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        verifyNoInteractions(resolver, world, nations, generals, posts, comments, reads, authority)
    }

    @Test
    fun `council 접두만 같은 공개 미정경로를 인증필수로 넓히지 않는다`() {
        mvc.perform(get("/api/council-extra")).andExpect(status().isNotFound)
        verifyNoInteractions(resolver, world, nations, generals, posts, comments, reads, authority)
    }

    private fun token(role: String = "USER"): String {
        val now = Date()
        return Jwts.builder().subject("7").issuedAt(Date(now.time - 120_000))
            .expiration(Date(now.time + 600_000)).claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.ROLE, role).signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact()
    }

    companion object {
        const val SECRET = "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    }
}
