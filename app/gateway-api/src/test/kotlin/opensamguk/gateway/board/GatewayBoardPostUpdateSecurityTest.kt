package opensamguk.gateway.board

import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class GatewayBoardPostUpdateSecurityTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var postRepository: GatewayBoardPostRepository

    @Autowired
    lateinit var commentRepository: GatewayBoardCommentRepository

    @Autowired
    lateinit var userRepository: UserRepository

    private lateinit var author: UserEntity
    private lateinit var otherUser: UserEntity
    private lateinit var admin: UserEntity

    @BeforeEach
    fun resetData() {
        commentRepository.deleteAll()
        postRepository.deleteAll()
        userRepository.deleteAll()
        author = userRepository.saveAndFlush(UserEntity(username = "board-author", password = "encoded"))
        otherUser = userRepository.saveAndFlush(UserEntity(username = "board-other", password = "encoded"))
        admin = userRepository.saveAndFlush(UserEntity(username = "board-admin", password = "encoded", role = "ADMIN"))
    }

    @Test
    fun `owner can update a live post with safe rendered content`() {
        val post = storedPost("old title", contentHtml = "old body")

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"SUGGESTION","title":" updated title ","content":"<script>alert(1)</script>\r\nnext"}""")
                .with(user(CustomUserDetails(author))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.category").value("SUGGESTION"))
            .andExpect(jsonPath("$.title").value("updated title"))
            .andExpect(jsonPath("$.contentHtml").value("&lt;script&gt;alert(1)&lt;/script&gt;<br>next"))
            .andExpect(jsonPath("$.canDelete").value(true))
            .andExpect(jsonPath("$.deleted").value(false))

        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.canDelete").value(false))
            .andExpect { result -> assertVaryAuthorization(result.response.getHeaders(HttpHeaders.VARY)) }
        val updated = postRepository.findById(requireNotNull(post.id)).orElseThrow()
        assertEquals("updated title", updated.title)
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;<br>next", updated.contentHtml)
    }

    @Test
    fun `anonymous post update is rejected with the gateway JSON 401 contract`() {
        val post = storedPost("private title")

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value(401))
    }

    @Test
    fun `non owner cannot update a post`() {
        val post = storedPost("private title")

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload())
                .with(user(CustomUserDetails(otherUser))),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `administrator can update another account post`() {
        val post = storedPost("private title")

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(title = "admin revision"))
                .with(user(CustomUserDetails(admin))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("admin revision"))
            .andExpect(jsonPath("$.canDelete").value(true))
    }

    @Test
    fun `non administrator cannot change a post category to notice`() {
        val post = storedPost("private title")

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(category = "NOTICE"))
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `administrator can change a post category to notice`() {
        val post = storedPost("private title")

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(category = "NOTICE"))
                .with(user(CustomUserDetails(admin))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.category").value("NOTICE"))
    }

    @Test
    fun `deleted post update is rejected without restoring its content`() {
        val post = storedPost("deleted title", contentHtml = "deleted body")
        post.deletedAt = Instant.parse("2026-08-13T00:00:00Z")
        postRepository.saveAndFlush(post)

        mockMvc.perform(
            patch("/board/posts/${post.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload(title = "attempted revision"))
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isConflict)

        val deletedPost = postRepository.findById(requireNotNull(post.id)).orElseThrow()
        assertEquals("deleted title", deletedPost.title)
        assertEquals("deleted body", deletedPost.contentHtml)
    }

    private fun storedPost(
        title: String,
        category: GatewayBoardCategory = GatewayBoardCategory.FREE,
        contentHtml: String = "body",
    ): GatewayBoardPostEntity =
        postRepository.saveAndFlush(
            GatewayBoardPostEntity(
                category = category,
                authorAccountId = author.id,
                authorName = author.username,
                title = title,
                contentHtml = contentHtml,
            ),
        )

    private fun updatePayload(
        category: String = "FREE",
        title: String = "updated title",
        content: String = "updated body",
    ): String = """{"category":"$category","title":"$title","content":"$content"}"""

    private fun assertVaryAuthorization(varyHeaders: List<String>) {
        assertTrue(
            varyHeaders.flatMap { it.split(',') }
                .any { it.trim().equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) },
        )
    }
}
