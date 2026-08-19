package opensamguk.gateway.board

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.security.JwtTokenProvider
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Date

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class GatewayBoardReadSecurityTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var postRepository: GatewayBoardPostRepository

    @Autowired
    lateinit var commentRepository: GatewayBoardCommentRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Value("\${jwt.secret}")
    lateinit var jwtSecret: String

    private lateinit var author: UserEntity
    private lateinit var otherUser: UserEntity
    private lateinit var admin: UserEntity

    @BeforeEach
    fun resetData() {
        commentRepository.deleteAll()
        postRepository.deleteAll()
        userRepository.deleteAll()
        author = userRepository.saveAndFlush(UserEntity(username = "board-author", password = "encoded", nickname = "board-author"))
        otherUser = userRepository.saveAndFlush(UserEntity(username = "board-other", password = "encoded", nickname = "board-other"))
        admin = userRepository.saveAndFlush(UserEntity(username = "board-admin", password = "encoded", role = "ADMIN", nickname = "board-admin"))
    }

    @Test
    fun `public board list is readable without authentication`() {
        mockMvc.perform(get("/board/posts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray())
    }

    @Test
    fun `public board detail is readable without authentication`() {
        val post = post("public detail")

        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.id").value(post.id))
            .andExpect(jsonPath("$.post.title").value("public detail"))
    }

    @Test
    fun `malformed bearer leaves public board detail anonymous`() {
        val post = post("stale session")

        mockMvc.perform(
            get("/board/posts/${post.id}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(false))
    }

    @Test
    fun `expired signed bearer leaves public board detail anonymous`() {
        val post = post("expired session")
        val token = expiredAccessToken()

        assertFalse(jwtTokenProvider.validateAccessToken(token))
        mockMvc.perform(
            get("/board/posts/${post.id}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(false))
    }

    @Test
    fun `public board responses expose delete capability only for the owner or admin`() {
        val post = post("capability")
        commentRepository.saveAndFlush(
            GatewayBoardCommentEntity(
                postId = requireNotNull(post.id),
                authorAccountId = otherUser.id,
                authorName = otherUser.username,
                contentText = "reply",
            ),
        )

        mockMvc.perform(get("/board/posts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].canDelete").value(false))
            .andExpect { result ->
                assertTrue(
                    result.response.getHeaders(HttpHeaders.VARY)
                        .flatMap { it.split(',') }
                        .any { it.trim().equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) },
                )
            }
        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(false))
            .andExpect(jsonPath("$.comments[0].canDelete").value(false))
            .andExpect { result -> assertVaryAuthorization(result.response.getHeaders(HttpHeaders.VARY)) }
        mockMvc.perform(get("/board/posts/${post.id}").with(user(CustomUserDetails(author))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(true))
            .andExpect(jsonPath("$.comments[0].canDelete").value(false))
        mockMvc.perform(get("/board/posts/${post.id}").with(user(CustomUserDetails(otherUser))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(false))
            .andExpect(jsonPath("$.comments[0].canDelete").value(true))
        mockMvc.perform(get("/board/posts/${post.id}").with(user(CustomUserDetails(admin))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(true))
            .andExpect(jsonPath("$.comments[0].canDelete").value(true))
    }

    @Test
    fun `list pagination keeps pinned posts first with a stable category filter`() {
        postRepository.saveAllAndFlush(
            listOf(
                post("old", createdAt = "2026-01-01T00:00:00Z"),
                post("pinned", createdAt = "2026-01-02T00:00:00Z", pinned = true),
                post("new", createdAt = "2026-01-03T00:00:00Z"),
                post("notice", category = GatewayBoardCategory.NOTICE, createdAt = "2026-01-04T00:00:00Z"),
            ),
        )

        mockMvc.perform(get("/board/posts?category=FREE&page=0&size=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.content[0].title").value("pinned"))
            .andExpect(jsonPath("$.content[1].title").value("new"))
        mockMvc.perform(get("/board/posts?category=FREE&page=1&size=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].title").value("old"))
    }

    private fun assertVaryAuthorization(varyHeaders: List<String>) {
        assertTrue(
            varyHeaders.flatMap { it.split(',') }
                .any { it.trim().equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) },
        )
    }

    private fun post(
        title: String,
        category: GatewayBoardCategory = GatewayBoardCategory.FREE,
        createdAt: String? = null,
        pinned: Boolean = false,
    ): GatewayBoardPostEntity {
        val timestamp = createdAt?.let(Instant::parse)
        return GatewayBoardPostEntity(
            category = category,
            authorAccountId = author.id,
            authorName = author.username,
            title = title,
            contentHtml = title,
            pinned = pinned,
            pinnedAt = if (pinned) timestamp else null,
            createdAt = timestamp ?: Instant.now(),
            updatedAt = timestamp ?: Instant.now(),
        ).let(postRepository::saveAndFlush)
    }

    private fun expiredAccessToken(): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(author.id.toString())
            .issuedAt(Date.from(now.minusSeconds(120)))
            .expiration(Date.from(now.minusSeconds(60)))
            .claim(GatewayJwtClaims.TOKEN_TYPE, GatewayJwtClaims.ACCESS_TOKEN)
            .claim(GatewayJwtClaims.USERNAME, author.username)
            .claim(GatewayJwtClaims.ROLE, author.role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret)))
            .compact()
    }
}
