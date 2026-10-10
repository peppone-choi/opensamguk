package opensamguk.gameapi.security

import io.jsonwebtoken.Jwts
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.common.auth.GatewayJwtContract
import opensamguk.gameapi.controller.BoardController
import opensamguk.gameapi.council.FrozenBoardAccessFixture
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Optional

/** Actual RSA/JWT chain, ownership classifier/resolver and BoardController over stored read fixtures. */
@ExtendWith(SpringExtension::class)
@WebAppConfiguration
@ContextConfiguration(classes = [BoardIdentitySecurityChainTest.Config::class, GameApiSecurityConfig::class])
class BoardIdentitySecurityChainTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    open class Config {
        @Bean open fun admissionPolicy() = ServerAdmissionTestFixture.publicPolicy()
        @Bean open fun verifier() = GameApiJwtVerifier(Base64.getEncoder().encodeToString(keys.public.encoded), "", "")
        @Bean open fun filter(verifier: GameApiJwtVerifier) = JwtVerifyFilter(verifier)
        @Bean open fun owners(): GeneralOwnerRepository = mock(GeneralOwnerRepository::class.java)
        @Bean open fun generals(): GeneralReadRepository = mock(GeneralReadRepository::class.java)
        @Bean open fun nations(): NationReadRepository = mock(NationReadRepository::class.java)
        @Bean open fun worlds(): WorldStateReadRepository = mock(WorldStateReadRepository::class.java)
        @Bean open fun posts(): BoardPostReadRepository = mock(BoardPostReadRepository::class.java)
        @Bean open fun comments(): BoardCommentReadRepository = mock(BoardCommentReadRepository::class.java)
        @Bean open fun reads(): BoardPostReadLogRepository = mock(BoardPostReadLogRepository::class.java)
        @Bean open fun polls(): VotePollReadRepository = mock(VotePollReadRepository::class.java)
        @Bean open fun votes(): VoteReadRepository = mock(VoteReadRepository::class.java)
        @Bean open fun resolver(owners: GeneralOwnerRepository, generals: GeneralReadRepository,
            nations: NationReadRepository) = GeneralResolver(owners, generals, nations)
        @Bean open fun controller(posts: BoardPostReadRepository, comments: BoardCommentReadRepository,
            resolver: GeneralResolver, generals: GeneralReadRepository, polls: VotePollReadRepository,
            votes: VoteReadRepository, reads: BoardPostReadLogRepository, worlds: WorldStateReadRepository) =
            BoardController(posts, comments, resolver, generals, polls, votes, reads, worlds, secretAccess = FrozenBoardAccessFixture.query())
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var owners: GeneralOwnerRepository
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var posts: BoardPostReadRepository
    @Autowired lateinit var comments: BoardCommentReadRepository
    @Autowired lateinit var reads: BoardPostReadLogRepository
    @Autowired lateinit var polls: VotePollReadRepository
    @Autowired lateinit var votes: VoteReadRepository
    private lateinit var mvc: MockMvc

    @BeforeEach fun prepare() {
        reset(owners, generals, nations, worlds, posts, comments, reads, polls, votes)
        mvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun owned(nation: Int = 1, office: Int = 5): GeneralReadEntity {
        val me = GeneralReadEntity(id = 10, worldId = 1, userId = "7", nationId = nation,
            name = "본인", officerLevel = office, npcState = 0, picture = "own.png")
        `when`(generals.findByUserId("7")).thenReturn(me)
        if (nation > 0) {
            `when`(nations.findById(nation)).thenReturn(Optional.of(NationReadEntity(id = nation, worldId = 1, level = 1)))
            `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(nation)).thenReturn(listOf(me))
        }
        return me
    }

    @Test fun `익명과 잘못된 RSA access는 정확401 JSON이며 저장소를 읽지 않는다`() {
        for (bearer in listOf(null, "invalid", access(tokenType = GatewayJwtClaims.REFRESH_TOKEN), access(expired = true))) {
            val req = get("/api/board").param("secret", "true")
            bearer?.let { req.header("Authorization", "Bearer $it") }
            mvc.perform(req).andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."))
        }
        verifyNoInteractions(owners, generals, nations, worlds, posts, comments, reads, polls, votes)
    }

    @Test fun `미구현 Council root와 하위는 모든 메서드에서 인증을 먼저 요구한다`() {
        for (path in listOf("/api/council", "/api/council/articles", "/api/council/articles/40/comments",
            "/api/council/articles/40/read", "/api/council/participants/90")) {
            for (method in listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
                mvc.perform(request(method, path)).andExpect(status().isUnauthorized)
                    .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
            }
            mvc.perform(get(path).header("Authorization", "Bearer ${access()}"))
                .andExpect(status().isNotFound)
        }
        for (path in listOf("/api/council-extra", "/api/board-extra", "/api/board/missing")) {
            mvc.perform(get(path)).andExpect(status().isNotFound)
        }
        verifyNoInteractions(owners, generals, nations, worlds, posts, comments, reads, polls, votes)
    }

    @Test fun `인증 계정도 실제 playable 소유 관계가 없으면403이고 JWT ADMIN은 우회하지 못한다`() {
        for (role in listOf("USER", "ADMIN")) {
            `when`(generals.findByUserId("7")).thenReturn(null)
            mvc.perform(get("/api/board").header("Authorization", "Bearer ${access(role)}"))
                .andExpect(status().isForbidden)
            `when`(generals.findByUserId("7")).thenReturn(GeneralReadEntity(id = 90, worldId = 1, userId = "8", npcState = 0))
            mvc.perform(get("/api/board").header("Authorization", "Bearer ${access(role)}"))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(nations, worlds, posts, comments, reads, polls, votes)
    }

    @Test fun `타세력 선택403은 본문과 타세력 장수 조회 전에 거절한다`() {
        owned()
        for (role in listOf("USER", "ADMIN")) for (secret in listOf(false, true)) {
            mvc.perform(get("/api/board").param("secret", secret.toString()).param("nationId", "2")
                .header("Authorization", "Bearer ${access(role)}")).andExpect(status().isForbidden)
        }
        verify(nations, never()).findById(2)
        verify(generals, never()).findByNationIdOrderByOfficerLevelDescIdAsc(anyInt())
        verify(generals, never()).findById(anyInt())
        verifyNoInteractions(worlds, posts, comments, reads, polls, votes)
    }

    @Test fun `본인 무소속 두방은200 빈INFO이며 세력 본문과 명부 조회가 없다`() {
        owned(nation = 0)
        for (secret in listOf(false, true)) {
            mvc.perform(get("/api/board").param("secret", secret.toString())
                .header("Authorization", "Bearer ${access()}")).andExpect(status().isOk)
                .andExpect(jsonPath("$.articles.length()").value(0))
                .andExpect(jsonPath("$.participants.length()").value(0))
                .andExpect(jsonPath("$.myPermission").value(-1))
                .andExpect(jsonPath("$.blockedReason").value("소속 세력이 없어 회의실을 이용할 수 없습니다."))
        }
        verify(generals, never()).findByNationIdOrderByOfficerLevelDescIdAsc(anyInt())
        verify(generals, never()).findById(anyInt())
        verifyNoInteractions(nations, worlds, posts, comments, reads, polls, votes)
    }

    @Test fun `본인 정상회의실과 기존기밀실은200이고 같은소속 원천만 읽는다`() {
        owned()
        for (secret in listOf(false, true)) {
            `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, secret)).thenReturn(emptyList())
            mvc.perform(get("/api/board").param("secret", secret.toString()).param("nationId", "1")
                .header("Authorization", "Bearer ${access()}")).andExpect(status().isOk)
                .andExpect(jsonPath("$.result").value(true)).andExpect(jsonPath("$.myGeneralId").value(10))
                .andExpect(jsonPath("$.myPermission").value(2))
                .andExpect(jsonPath("$.participants[0].generalId").value(10))
            verify(posts).findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, secret)
        }
        verify(posts, never()).findByIsSecretOrderByCreatedAtDescIdDesc(anyBoolean())
        verify(generals, never()).findById(anyInt())
    }

    @Test fun `기존일반장수 SECRET INFO 계약은 ADMIN도 바꾸지 못한다`() {
        owned(office = 1)
        mvc.perform(get("/api/board").param("secret", "true")
            .header("Authorization", "Bearer ${access("ADMIN")}")).andExpect(status().isOk)
            .andExpect(jsonPath("$.articles.length()").value(0))
            .andExpect(jsonPath("$.blockedReason").value("권한이 부족합니다. 수뇌부가 아닙니다."))
        verifyNoInteractions(worlds, posts, comments, reads, polls, votes)
        verify(generals, never()).findByNationIdOrderByOfficerLevelDescIdAsc(anyInt())
    }

    @Test fun `글 댓글 열람 표시는 현재 소속 명부밖 장수를 조회하거나 노출하지 않는다`() {
        owned()
        val foreign = GeneralReadEntity(id = 90, worldId = 1, userId = "8", nationId = 2,
            name = "타세력", picture = "private-foreign.png", imageServer = 9, officerLevel = 12)
        `when`(generals.findById(90)).thenReturn(Optional.of(foreign))
        `when`(posts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, true)).thenReturn(listOf(
            BoardPostReadEntity(id = 40, worldId = 1, nationId = 1, isSecret = true, authorGeneralId = 90,
                authorName = "저장된 작성자", title = "글", contentHtml = "본문")))
        `when`(comments.findByPostIdOrderByCreatedAtAscIdAsc(40)).thenReturn(listOf(
            BoardCommentReadEntity(id = 41, worldId = 1, nationId = 1, isSecret = true, postId = 40,
                authorGeneralId = 90, authorName = "저장된 댓글 작성자", contentText = "댓글")))
        `when`(reads.findByPostIds(listOf(40))).thenReturn(listOf(
            BoardPostReadLogEntity(id = 1, worldId = 1, postId = 40, generalId = 90),
            BoardPostReadLogEntity(id = 2, worldId = 1, postId = 40, generalId = 10)))
        mvc.perform(get("/api/board").param("secret", "true").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.articles[0].authorName").value("저장된 작성자"))
            .andExpect(jsonPath("$.articles[0].authorPicture").isEmpty)
            .andExpect(jsonPath("$.articles[0].authorOfficerLevelText").isEmpty)
            .andExpect(jsonPath("$.articles[0].comments[0].authorName").value("저장된 댓글 작성자"))
            .andExpect(jsonPath("$.articles[0].comments[0].authorPicture").isEmpty)
            .andExpect(jsonPath("$.articles[0].readers.read.length()").value(1))
            .andExpect(jsonPath("$.articles[0].readers.read[0].generalId").value(10))
            .andExpect(jsonPath("$.articles[0].readers.read[0].picture").value("own.png"))
        verify(generals, never()).findById(anyInt())
        verify(generals, never()).findByNationIdOrderByOfficerLevelDescIdAsc(2)
    }

    private fun access(role: String = "USER", tokenType: String = GatewayJwtClaims.ACCESS_TOKEN,
        expired: Boolean = false): String {
        val now = System.currentTimeMillis()
        return Jwts.builder().subject("7").issuer(GatewayJwtContract.ISSUER)
            .audience().add(GatewayJwtContract.GAME_API_AUDIENCE).and()
            .issuedAt(Date(now - 120_000)).expiration(Date(now + if (expired) -60_000 else 600_000))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType).claim(GatewayJwtClaims.ROLE, role)
            .signWith(keys.private).compact()
    }

    companion object {
        // Ephemeral test keys stay in memory; no environment, keys or JWTs are read or saved.
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
