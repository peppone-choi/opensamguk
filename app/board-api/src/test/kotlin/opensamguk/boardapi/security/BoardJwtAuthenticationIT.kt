package opensamguk.boardapi.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import opensamguk.boardapi.board.GatewayBoardCommentRepository
import opensamguk.boardapi.board.GatewayBoardPostRepository
import opensamguk.common.auth.GatewayJwtClaims
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Date

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class BoardJwtAuthenticationIT {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var postRepository: GatewayBoardPostRepository

    @Autowired
    lateinit var commentRepository: GatewayBoardCommentRepository

    @Value("\${jwt.legacy-secret}")
    lateinit var jwtSecret: String

    private lateinit var user: UserEntity

    @BeforeEach
    fun resetData() {
        commentRepository.deleteAll()
        postRepository.deleteAll()
        userRepository.deleteAll()
        user = userRepository.saveAndFlush(
            UserEntity(username = "board-auth-user", password = "encoded", nickname = "보드 사용자"),
        )
    }

    @Test
    fun `gateway signed access token authenticates a current database user for writes`() {
        mockMvc.perform(
            post("/board/posts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(user.id, "USER")}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"FREE","title":"독립 보드","content":"재시작 계속성"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.authorName").value("보드 사용자"))
    }

    @Test
    fun `valid signature for a missing database user is rejected`() {
        mockMvc.perform(
            post("/board/posts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(Long.MAX_VALUE, "USER")}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"FREE","title":"거부","content":"본문"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
    }

    @Test
    fun `refresh token cannot authorize a board write`() {
        mockMvc.perform(
            post("/board/posts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(user.id, GatewayJwtClaims.REFRESH_TOKEN, "USER")}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"FREE","title":"거부","content":"본문"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `token admin claim cannot override the current database user role`() {
        mockMvc.perform(
            patch("/board/posts/1/pin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(user.id, "ADMIN")}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"pinned":true}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.status").value(403))
    }

    @Test
    fun `malformed bearer remains anonymous for a public read`() {
        mockMvc.perform(
            get("/board/posts").header(HttpHeaders.AUTHORIZATION, "Bearer malformed"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    private fun accessToken(userId: Long, role: String): String =
        token(userId, GatewayJwtClaims.ACCESS_TOKEN, role)

    private fun token(userId: Long, tokenType: String, role: String): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(300)))
            .claim(GatewayJwtClaims.TOKEN_TYPE, tokenType)
            .claim(GatewayJwtClaims.ROLE, role)
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret)))
            .compact()
    }
}
