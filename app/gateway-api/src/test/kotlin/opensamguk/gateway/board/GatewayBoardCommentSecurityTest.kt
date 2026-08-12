package opensamguk.gateway.board

import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProfileIconSecureStorageTestConfiguration::class)
class GatewayBoardCommentSecurityTest {
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
    fun `anonymous comment creation is rejected`() {
        val post = post()

        mockMvc.perform(
            post("/board/posts/${post.id}/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"reply"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated user can create a comment`() {
        val post = post()

        mockMvc.perform(
            post("/board/posts/${post.id}/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"reply"}""")
                .with(user(CustomUserDetails(otherUser))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.content").value("reply"))
            .andExpect(jsonPath("$.canDelete").value(true))
    }

    @Test
    fun `non owner cannot delete a comment`() {
        val post = post()
        val comment = commentRepository.saveAndFlush(comment(post))

        mockMvc.perform(
            delete("/board/posts/${post.id}/comments/${comment.id}")
                .with(user(CustomUserDetails(author))),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `administrator can delete another account comment`() {
        val post = post()
        val comment = commentRepository.saveAndFlush(comment(post))

        mockMvc.perform(
            delete("/board/posts/${post.id}/comments/${comment.id}")
                .with(user(CustomUserDetails(admin))),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `comment owner can soft delete their comment`() {
        val post = post()
        val comment = commentRepository.saveAndFlush(comment(post))

        mockMvc.perform(
            delete("/board/posts/${post.id}/comments/${comment.id}")
                .with(user(CustomUserDetails(otherUser))),
        ).andExpect(status().isNoContent)
        mockMvc.perform(get("/board/posts/${post.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments[0].deleted").value(true))
            .andExpect(jsonPath("$.comments[0].content").value("삭제된 댓글입니다."))
    }

    private fun post(): GatewayBoardPostEntity =
        postRepository.saveAndFlush(
            GatewayBoardPostEntity(
                category = GatewayBoardCategory.FREE,
                authorAccountId = author.id,
                authorName = author.username,
                title = "post",
                contentHtml = "body",
            ),
        )

    private fun comment(post: GatewayBoardPostEntity): GatewayBoardCommentEntity = GatewayBoardCommentEntity(
        postId = requireNotNull(post.id),
        authorAccountId = otherUser.id,
        authorName = otherUser.username,
        contentText = "reply",
    )
}
