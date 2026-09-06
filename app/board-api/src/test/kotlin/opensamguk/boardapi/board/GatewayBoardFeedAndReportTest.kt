package opensamguk.boardapi.board

import opensamguk.boardapi.security.BoardUserDetails
import opensamguk.infra.entity.UserEntity
import opensamguk.infra.read.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ADR-LITE-049 13 커뮤니티 확장 — 분류 6종 카운트 · 최신/인기/내 글 · 검색 · 조회수 · 신고(생성·중복·관리자 처리·권한).
 * 값은 전부 저장된 행에서만 나온다(날조 없음).
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class GatewayBoardFeedAndReportTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var postRepository: GatewayBoardPostRepository
    @Autowired lateinit var commentRepository: GatewayBoardCommentRepository
    @Autowired lateinit var reportRepository: GatewayBoardReportRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var author: UserEntity
    private lateinit var reader: UserEntity
    private lateinit var admin: UserEntity

    @BeforeEach
    fun resetData() {
        reportRepository.deleteAll()
        commentRepository.deleteAll()
        postRepository.deleteAll()
        userRepository.deleteAll()
        author = userRepository.saveAndFlush(
            UserEntity(username = "feed-author", password = "encoded", nickname = "글쓴이").apply {
                representativeWorldId = 1; representativeGeneralId = 77; representativeGeneralName = "하후돈"
            },
        )
        reader = userRepository.saveAndFlush(UserEntity(username = "feed-reader", password = "encoded", nickname = "독자"))
        admin = userRepository.saveAndFlush(UserEntity(username = "feed-admin", password = "encoded", role = "ADMIN", nickname = "운영자"))
    }

    private fun savePost(title: String, category: GatewayBoardCategory = GatewayBoardCategory.FREE, by: UserEntity = author, ageDays: Long = 0, views: Int = 0) =
        postRepository.saveAndFlush(
            GatewayBoardPostEntity(
                category = category, authorAccountId = by.id, authorName = by.username, title = title, contentHtml = "<p>본문 $title</p>",
                createdAt = Instant.now().minus(ageDays, ChronoUnit.DAYS), updatedAt = Instant.now(), viewCount = views,
            ),
        )

    @Test
    fun `categories count public posts across all six categories`() {
        savePost("전략 글", GatewayBoardCategory.STRATEGY)
        savePost("서버 글", GatewayBoardCategory.SERVER)
        savePost("서버 글 2", GatewayBoardCategory.SERVER)
        mockMvc.perform(get("/board/categories"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(6))
            .andExpect(jsonPath("$[?(@.category == 'SERVER')].count").value(2))
            .andExpect(jsonPath("$[?(@.category == 'CREATIVE')].count").value(0))
    }

    @Test
    fun `popular sorts by views plus comments inside the seven day window and search filters by title`() {
        val quiet = savePost("조용한 글", views = 1)
        val loud = savePost("시끄러운 글", views = 3)
        val old = savePost("옛 인기 글", ageDays = 10, views = 100)
        commentRepository.saveAndFlush(GatewayBoardCommentEntity(postId = requireNotNull(quiet.id), authorAccountId = reader.id, authorName = "독자", contentText = "댓글"))
        // quiet = 1 + 5×1 = 6 > loud = 3; old 는 창 밖
        mockMvc.perform(get("/board/posts?sort=popular"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].id").value(quiet.id))
            .andExpect(jsonPath("$.content[0].commentCount").value(1))
            .andExpect(jsonPath("$.content[0].viewCount").value(1))
            .andExpect(jsonPath("$.content[0].authorGeneralName").value("하후돈"))
            .andExpect(jsonPath("$.content[0].authorWorldId").value(1))
            .andExpect(jsonPath("$.content[1].id").value(loud.id))
        mockMvc.perform(get("/board/posts?q=시끄러운"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(loud.id))
        mockMvc.perform(get("/board/posts?sort=mine").with(user(BoardUserDetails(reader))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
        mockMvc.perform(get("/board/posts?sort=mine"))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/board/posts?sort=latest"))
            .andExpect(jsonPath("$.content.length()").value(3))
            .andExpect(jsonPath("$.content[2].id").value(old.id))
    }

    @Test
    fun `detail increments the view count atomically`() {
        val target = savePost("조회 글")
        repeat(2) { mockMvc.perform(get("/board/posts/${target.id}")).andExpect(status().isOk) }
        mockMvc.perform(get("/board/posts/${target.id}"))
            .andExpect(jsonPath("$.post.viewCount").value(3))
    }

    @Test
    fun `reports need login, refuse duplicates, and only admins list and handle them`() {
        val target = savePost("신고 대상")
        val body = """{"reason":"광고성 글"}"""
        mockMvc.perform(post("/board/posts/${target.id}/report").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(post("/board/posts/${target.id}/report").contentType(MediaType.APPLICATION_JSON).content(body).with(user(BoardUserDetails(reader))))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.targetSummary").value("신고 대상"))
            .andExpect(jsonPath("$.reporterName").value("독자"))
        mockMvc.perform(post("/board/posts/${target.id}/report").contentType(MediaType.APPLICATION_JSON).content(body).with(user(BoardUserDetails(reader))))
            .andExpect(status().isConflict)
        mockMvc.perform(get("/board/admin/reports").with(user(BoardUserDetails(reader))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/board/admin/reports"))
            .andExpect(status().isForbidden)
        val id = reportRepository.findAll().single().id
        mockMvc.perform(get("/board/admin/reports?status=OPEN").with(user(BoardUserDetails(admin))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(id))
        mockMvc.perform(patch("/board/admin/reports/$id").contentType(MediaType.APPLICATION_JSON).content("""{"status":"HANDLED"}""").with(user(BoardUserDetails(admin))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HANDLED"))
            .andExpect(jsonPath("$.handledAt").exists())
        mockMvc.perform(get("/board/admin/reports?status=OPEN").with(user(BoardUserDetails(admin))))
            .andExpect(jsonPath("$.length()").value(0))
        // 처리된 뒤에는 같은 사람이 다시 신고할 수 있다(OPEN 중복만 막는다).
        mockMvc.perform(post("/board/posts/${target.id}/report").contentType(MediaType.APPLICATION_JSON).content(body).with(user(BoardUserDetails(reader))))
            .andExpect(status().isCreated)
    }
}
