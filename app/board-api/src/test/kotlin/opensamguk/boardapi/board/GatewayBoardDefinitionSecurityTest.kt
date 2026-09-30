package opensamguk.boardapi.board

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.boardapi.security.BoardUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GatewayBoardDefinitionSecurityTest {
    @Autowired lateinit var entityManager: jakarta.persistence.EntityManager
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var definitions: GatewayBoardDefinitionRepository
    @Autowired lateinit var posts: GatewayBoardPostRepository
    @Autowired lateinit var comments: GatewayBoardCommentRepository
    @Autowired lateinit var reports: GatewayBoardReportRepository
    private lateinit var admin: BoardUserDetails
    private lateinit var member: BoardUserDetails

    @BeforeEach
    fun principals() {
        admin = BoardUserDetails(users.saveAndFlush(UserEntity(username = "definitions-admin", password = "encoded", role = "ADMIN")))
        member = BoardUserDetails(users.saveAndFlush(UserEntity(username = "definitions-member", password = "encoded")))
    }

    private fun create(key: String): Long {
        val result = mvc.perform(post("/board/admin/boards").with(user(admin))
            .contentType(MediaType.APPLICATION_JSON).content("""{"key":"$key","name":"새 게시판"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.key").value(key)).andReturn()
        return json.readTree(result.response.contentAsString)["boardId"].asLong()
    }

    @Test
    fun `board management needs authentication and administrator authority`() {
        val request = """{"key":"NEW","name":"새 게시판"}"""
        mvc.perform(post("/board/admin/boards").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/board/admin/boards").with(user(member)).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden)
        val id = create("NEW")
        for (authenticated in listOf(false, true)) {
            val patchRequest = patch("/board/admin/boards/$id").contentType(MediaType.APPLICATION_JSON).content("""{"name":"변경"}""")
            val deleteRequest = delete("/board/admin/boards/$id")
            if (authenticated) {
                mvc.perform(patchRequest.with(user(member))).andExpect(status().isForbidden)
                mvc.perform(deleteRequest.with(user(member))).andExpect(status().isForbidden)
            } else {
                mvc.perform(patchRequest).andExpect(status().isUnauthorized)
                mvc.perform(deleteRequest).andExpect(status().isUnauthorized)
            }
        }
    }

    @Test
    fun `new category keeps string wire and definition controls display order and writing`() {
        val id = create("NEW")
        mvc.perform(post("/board/posts").with(user(member)).contentType(MediaType.APPLICATION_JSON)
            .content("""{"category":"NEW","title":"새 글","content":"본문"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.category").value("NEW"))
        mvc.perform(get("/board/posts?category=NEW")).andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].category").value("NEW"))
        mvc.perform(patch("/board/admin/boards/$id").with(user(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"읽기 전용","sortOrder":-1,"writable":false}"""))
            .andExpect(status().isOk)
        mvc.perform(get("/board/categories")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].boardId").value(id)).andExpect(jsonPath("$[0].category").value("NEW"))
            .andExpect(jsonPath("$[0].count").value(1)).andExpect(jsonPath("$[0].name").value("읽기 전용"))
            .andExpect(jsonPath("$[0].writable").value(false))
        mvc.perform(post("/board/posts").with(user(admin)).contentType(MediaType.APPLICATION_JSON)
            .content("""{"category":"NEW","title":"새 글","content":"본문"}"""))
            .andExpect(status().isForbidden)
        val postId = posts.findByCategory(GatewayBoardCategory("NEW"), org.springframework.data.domain.PageRequest.of(0, 20)).content.single().id
        mvc.perform(post("/board/posts/$postId/comments").with(user(member)).contentType(MediaType.APPLICATION_JSON)
            .content("""{"content":"댓글"}""")).andExpect(status().isForbidden)
    }

    @Test
    fun `definition deletion requires move destination and preserves active and deleted posts comments reports`() {
        val source = create("SOURCE")
        val destination = create("DESTINATION")
        val active = posts.saveAndFlush(GatewayBoardPostEntity(GatewayBoardCategory("SOURCE"), member.id, "작성자", "살아 있는 글", "본문"))
        val archived = posts.saveAndFlush(GatewayBoardPostEntity(GatewayBoardCategory("SOURCE"), member.id, "작성자", "지운 글", "본문", deletedAt = Instant.now()))
        val comment = comments.saveAndFlush(GatewayBoardCommentEntity(requireNotNull(active.id), member.id, "작성자", "댓글"))
        val report = reports.saveAndFlush(GatewayBoardReportEntity(postId = active.id, reporterAccountId = member.id, reason = "신고"))
        mvc.perform(delete("/board/admin/boards/$source").with(user(admin))).andExpect(status().isConflict)
        mvc.perform(delete("/board/admin/boards/$source?moveTo=$source").with(user(admin))).andExpect(status().isBadRequest)
        mvc.perform(delete("/board/admin/boards/$source?moveTo=999999").with(user(admin))).andExpect(status().isNotFound)
        mvc.perform(delete("/board/admin/boards/$source?moveTo=$destination").with(user(admin))).andExpect(status().isNoContent)
        // Bulk category move leaves the persistence context's previously loaded post objects stale.
        entityManager.clear()
        mvc.perform(get("/board/posts?category=DESTINATION")).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].category").value("DESTINATION"))
        assertEquals(2L, posts.countByCategory(GatewayBoardCategory("DESTINATION")))
        assertTrue(definitions.findById(source).isEmpty)
        assertNotNull(comments.findById(requireNotNull(comment.id)).orElse(null))
        assertNotNull(reports.findById(requireNotNull(report.id)).orElse(null))
        assertNotNull(posts.findById(requireNotNull(archived.id)).orElse(null)?.deletedAt)
        mvc.perform(get("/board/posts").with(user(admin))).andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.deleted==true)]").isEmpty)
    }

    @Test
    fun `unregistered category fails closed and empty board can be deleted`() {
        mvc.perform(post("/board/posts").with(user(member)).contentType(MediaType.APPLICATION_JSON)
            .content("""{"category":"UNKNOWN","title":"글","content":"본문"}"""))
            .andExpect(status().isNotFound)
        val id = create("EMPTY")
        mvc.perform(delete("/board/admin/boards/$id").with(user(admin))).andExpect(status().isNoContent)
    }
}
