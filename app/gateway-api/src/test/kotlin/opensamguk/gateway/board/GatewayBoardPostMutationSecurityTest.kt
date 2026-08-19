package opensamguk.gateway.board

import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
        author = userRepository.saveAndFlush(UserEntity(username = "board-author", password = "encoded", nickname = "board-author"))
        otherUser = userRepository.saveAndFlush(UserEntity(username = "board-other", password = "encoded", nickname = "board-other"))
        admin = userRepository.saveAndFlush(UserEntity(username = "board-admin", password = "encoded", role = "ADMIN", nickname = "board-admin"))
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

    @ParameterizedTest
    @ValueSource(strings = ["\\u0085", "&#x2060;"])
    fun `rich text containing only invisible Unicode content is rejected by the API`(content: String) {
        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"category":"FREE","title":"title","content":"<p>$content</p>","contentFormat":"RICH_HTML"}""",
                )
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `board author name is the public nickname, not the login id`() {
        val nicknamed = userRepository.saveAndFlush(
            UserEntity(username = "board-login-id", password = "encoded", nickname = "관도의영웅"),
        )

        mockMvc.perform(
            post("/board/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"category":"FREE","title":"title","content":"body"}""")
                .with(user(CustomUserDetails(nicknamed))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.authorName").value("관도의영웅"))

        val postId = postRepository.findAll().single().id
        mockMvc.perform(
            post("/board/posts/$postId/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"comment"}""")
                .with(user(CustomUserDetails(nicknamed))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.authorName").value("관도의영웅"))

        assertEquals("관도의영웅", commentRepository.findAll().single().authorName)
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
    fun `author display resolves live nickname and portrait, not the write-time snapshot`() {
        val post = storedPost("live author")

        // 작성 후에 닉네임과 전콘을 바꾼다 — 옛 글도 따라와야 한다.
        author.nickname = "새이름"
        author.picture = "warlord.png"
        author.imgsvr = false
        userRepository.saveAndFlush(author)

        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.authorName").value("새이름"))
            .andExpect(jsonPath("$.post.authorPicture").value("warlord.png"))
            .andExpect(jsonPath("$.post.authorImageServer").value(0))

        mockMvc.perform(get("/board/posts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].authorName").value("새이름"))
            .andExpect(jsonPath("$.content[0].authorPicture").value("warlord.png"))
    }

    @Test
    fun `author display falls back to the stored name when the account is gone`() {
        val post = storedPost("orphaned")
        postRepository.saveAndFlush(post.apply { authorAccountId = null })

        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.post.authorName").value(author.username))
            .andExpect(jsonPath("$.post.authorPicture").doesNotExist())
            .andExpect(jsonPath("$.post.authorImageServer").value(0))
    }

    @Test
    fun `non owner cannot delete a post`() {
        val post = storedPost("private title")

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(otherUser))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `owner deletion hides a post from detail and feed`() {
        val post = storedPost("private title")

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(author))))
            .andExpect(status().isNoContent)
        // 소프트딜리트는 감사 기록으로만 남는다 — 읽기 경로에서는 없는 글이다.
        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/board/posts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.id == ${post.id})]").isEmpty)
    }

    @Test
    fun `admin sees deleted posts unmasked via includeDeleted, others cannot ask`() {
        val post = storedPost("moderated title")
        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(author))))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/board/posts?includeDeleted=true").with(user(CustomUserDetails(admin))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].id").value(post.id))
            .andExpect(jsonPath("$.content[0].deleted").value(true))
            .andExpect(jsonPath("$.content[0].title").value("moderated title"))

        // 공개 피드가 이 문을 열 수 없다 — 일반 유저·비로그인 모두 거부.
        mockMvc.perform(get("/board/posts?includeDeleted=true").with(user(CustomUserDetails(otherUser))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/board/posts?includeDeleted=true"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `deleted post row survives in the database`() {
        val post = storedPost("audit trail")

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(author))))
            .andExpect(status().isNoContent)

        val stored = postRepository.findById(requireNotNull(post.id)).orElseThrow()
        assertNotNull(stored.deletedAt)
        assertEquals(author.id, stored.deletedByAccountId)
    }

    @Test
    fun `administrator can delete another account post`() {
        val post = storedPost("suggestion", GatewayBoardCategory.SUGGESTION)

        mockMvc.perform(delete("/board/posts/${post.id}").with(user(CustomUserDetails(admin))))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isNotFound)
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
