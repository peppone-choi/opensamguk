package opensamguk.gateway.board

import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class GatewayBoardPostMutationSecurityTest {
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
    fun `anonymous board creation is rejected with the gateway JSON 401 contract`() {
        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"FREE","title":"test","content":"test"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value(401))
    }

    @Test
    fun `authenticated author stores escaped post HTML`() {
        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"FREE","title":"title","content":"<script>alert(1)</script>\nnext"}""")
                .with(user(CustomUserDetails(author))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.contentHtml").value("&lt;script&gt;alert(1)&lt;/script&gt;<br>next"))
            .andExpect(jsonPath("$.deleted").value(false))

        assertFalse(postRepository.findAll().single().contentHtml.contains("<script>"))
    }

    @Test
    fun `authenticated author preserves allowlisted rich text and removes active markup`() {
        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"category":"FREE","title":"title","content":"<p><strong>천하</strong><img src=x onerror=alert(1)><script>alert(1)</script></p>","contentFormat":"RICH_HTML"}""",
                )
                .with(user(CustomUserDetails(author))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.contentHtml").value("<p><strong>천하</strong></p>"))

        val contentHtml = postRepository.findAll().single().contentHtml
        assertFalse(contentHtml.contains("script"))
        assertFalse(contentHtml.contains("onerror"))
        assertFalse(contentHtml.contains("alert("))
    }

    @Test
    fun `rich text with no visible content is rejected`() {
        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"category":"FREE","title":"title","content":"<p><br></p><script>alert(1)</script>","contentFormat":"RICH_HTML"}""",
                )
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `rich text containing only an invisible format control is rejected by the API`() {
        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"category":"FREE","title":"title","content":"<p>&#x2060;</p>","contentFormat":"RICH_HTML"}""",
                )
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `notice creation is restricted to administrators`() {
        val notice = """{"category":"NOTICE","title":"notice","content":"body"}"""

        mockMvc.perform(
            post("/board/posts").contentType(MediaType.APPLICATION_JSON).content(notice)
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/board/posts").contentType(MediaType.APPLICATION_JSON).content(notice)
                .with(user(CustomUserDetails(admin))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.category").value("NOTICE"))
    }

    @Test
    fun `only an administrator can pin a post`() {
        val post = storedPost("pin me")

        mockMvc.perform(
            patch("/board/posts/${post.id}/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"pinned":true}""")
                .with(user(CustomUserDetails(otherUser))),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            patch("/board/posts/${post.id}/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"pinned":true}""")
                .with(user(CustomUserDetails(admin))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pinned").value(true))
    }

    @Test
    fun `pin request requires an explicit boolean`() {
        val post = storedPost("pin payload")

        mockMvc.perform(
            patch("/board/posts/${post.id}/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(user(CustomUserDetails(admin))),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `non owner cannot delete a post`() {
        val post = storedPost("private title")

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(otherUser))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `owner soft deletion masks a post`() {
        val post = storedPost("private title")

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(author))))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.deleted").value(true))
            .andExpect(jsonPath("$.post.title").value("삭제된 게시글입니다."))
            .andExpect(jsonPath("$.post.contentHtml").value("삭제된 게시글입니다."))
            .andExpect(jsonPath("$.comments").isEmpty())
    }

    @Test
    fun `administrator can delete another account post`() {
        val post = storedPost("suggestion", GatewayBoardCategory.SUGGESTION)

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(admin))))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.deleted").value(true))
    }

    private fun storedPost(title: String, category: GatewayBoardCategory = GatewayBoardCategory.FREE): GatewayBoardPostEntity =
        postRepository.saveAndFlush(
            GatewayBoardPostEntity(
                category = category,
                authorAccountId = author.id,
                authorName = author.username,
                title = title,
                contentHtml = "body",
            ),
        )
}
