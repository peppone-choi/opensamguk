package opensamguk.gameapi.council

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gameapi.controller.BoardController
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date

@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [LegacyCouncilBoundaryTest.Config::class, GameApiSecurityConfig::class])
class LegacyCouncilBoundaryTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun verifier() = GameApiJwtVerifier("", SECRET, "2099-01-01T00:00:00Z")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun resolver(): GeneralResolver = mock(GeneralResolver::class.java)
        @Bean open fun worlds(): WorldStateReadRepository = mock(WorldStateReadRepository::class.java)
        @Bean open fun generals(): GeneralReadRepository = mock(GeneralReadRepository::class.java)
        @Bean open fun posts(): BoardPostReadRepository = mock(BoardPostReadRepository::class.java)
        @Bean open fun comments(): BoardCommentReadRepository = mock(BoardCommentReadRepository::class.java)
        @Bean open fun reads(): BoardPostReadLogRepository = mock(BoardPostReadLogRepository::class.java)
        @Bean open fun polls(): VotePollReadRepository = mock(VotePollReadRepository::class.java)
        @Bean open fun votes(): VoteReadRepository = mock(VoteReadRepository::class.java)
        @Bean open fun controller(posts: BoardPostReadRepository, comments: BoardCommentReadRepository,
            resolver: GeneralResolver, generals: GeneralReadRepository, polls: VotePollReadRepository,
            votes: VoteReadRepository, reads: BoardPostReadLogRepository, worlds: WorldStateReadRepository) =
            BoardController(posts, comments, resolver, generals, polls, votes, reads, worlds)
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var resolver: GeneralResolver
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var comments: BoardCommentReadRepository
    @Autowired lateinit var reads: BoardPostReadLogRepository
    @Autowired lateinit var polls: VotePollReadRepository
    @Autowired lateinit var votes: VoteReadRepository
    private lateinit var mvc: MockMvc

    @BeforeEach fun prepare() {
        reset(resolver, worlds, generals, posts, comments, reads, polls, votes)
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun owned(nation: Int = 1, profile: String = "HWIHA") {
        val me = GeneralReadEntity(id = 10, worldId = 1, userId = "7", nationId = nation, officerLevel = 12)
        `when`(resolver.resolve(7L)).thenReturn(GeneralResolver.ResolvedGeneral(me, 12, 4, nation, 1))
        val config = if (profile == "HWIHA") mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN") else mapOf("ruleProfile" to opensamguk.logic.input.RuleProfile.fromWorldConfig(null).name)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = config))
    }

    @Test fun `익명은 실제 인증 체인에서401이고 private 저장소를 읽지 않는다`() {
        mvc.perform(get("/api/board")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
        verifyNoInteractions(resolver, worlds, generals, posts, comments, reads, polls, votes)
    }

    @Test fun `휘하 높은 직함과 ADMIN도 legacy 두 방403이며 본문 조회가 없다`() {
        owned()
        for (role in listOf("USER", "ADMIN")) for (secret in listOf(false, true)) {
            mvc.perform(get("/api/board").param("secret", secret.toString()).header("Authorization", "Bearer ${token(role)}"))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.result").value(false))
                .andExpect(jsonPath("$.articles.length()").value(0))
                .andExpect(jsonPath("$.blockedReason").value("새 회의실에서 이용해 주세요."))
                .andExpect(jsonPath("$.code").doesNotExist())
        }
        verifyNoInteractions(generals, posts, comments, reads, polls, votes)
    }

    @Test fun `본인 무소속은 기존200빈INFO이며 private 저장소를 읽지 않는다`() {
        owned(nation = 0)
        mvc.perform(get("/api/board").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles.length()").value(0))
            .andExpect(jsonPath("$.blockedReason").value("소속 세력이 없어 회의실을 이용할 수 없습니다."))
        verifyNoInteractions(worlds, generals, posts, comments, reads, polls, votes)
    }

    @Test fun `타소속 선택은 세계와 본문을 읽기 전에403이다`() {
        owned()
        mvc.perform(get("/api/board").param("nationId", "2").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(worlds, generals, posts, comments, reads, polls, votes)
    }

    @Test fun `세계 정책 누락과 잘못된 선언은503이며 본문 저장소를 읽지 않는다`() {
        owned()
        val configs: List<Map<String, Any?>?> = listOf(null, emptyMap(), mapOf("ruleProfile" to "unknown"),
            mapOf("worldFormat" to "bad"), mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "ruleProfile" to opensamguk.logic.input.RuleProfile.fromWorldConfig(null).name))
        for (config in configs) {
            `when`(worlds.findProcessWorld()).thenReturn(config?.let { WorldStateReadEntity(id = 1, config = it) })
            mvc.perform(get("/api/board").header("Authorization", "Bearer ${token()}"))
                .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.result").value(false))
                .andExpect(jsonPath("$.articles.length()").value(0))
                .andExpect(jsonPath("$.blockedReason").value("세계 규칙을 확인할 수 없습니다."))
        }
        `when`(worlds.findProcessWorld()).thenThrow(org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT, "선언 실패", IllegalArgumentException("worldFormat is missing from world config")))
        mvc.perform(get("/api/board").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isServiceUnavailable)
        verifyNoInteractions(generals, posts, comments, reads, polls, votes)
    }

    @Test fun `세계 DB 오류와 다른 충돌은 정책503으로 숨기지 않는다`() {
        owned()
        val controller = BoardController(posts, comments, resolver, generals, polls, votes, reads, worlds)
        val dbFailure = org.springframework.dao.DataAccessResourceFailureException("DB 읽기 실패 시험")
        doThrow(dbFailure).`when`(worlds).findProcessWorld()
        kotlin.test.assertSame(dbFailure, kotlin.test.assertFailsWith<org.springframework.dao.DataAccessResourceFailureException> {
            controller.board(false, 7L, null)
        })
        val conflict = org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
            "다른 충돌", IllegalArgumentException("다른 원천 오류"))
        doThrow(conflict).`when`(worlds).findProcessWorld()
        kotlin.test.assertSame(conflict, kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> {
            controller.board(false, 7L, null)
        })
        verifyNoInteractions(generals, posts, comments, reads, polls, votes)
    }

    @Test fun `삼모는 기존 소속 본문 조회와200 계약을 보존한다`() {
        owned(profile = opensamguk.logic.input.RuleProfile.fromWorldConfig(null).name)
        `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, false)).thenReturn(emptyList())
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(emptyList())
        mvc.perform(get("/api/board").header("Authorization", "Bearer ${token()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.result").value(true))
        verify(posts).findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, false)
        verifyNoInteractions(comments, reads, polls, votes)
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
