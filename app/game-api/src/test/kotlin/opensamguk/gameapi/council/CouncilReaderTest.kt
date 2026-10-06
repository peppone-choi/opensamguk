package opensamguk.gameapi.council

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.util.Optional

class CouncilReaderTest {
    private val resolver = mock(GeneralResolver::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val posts = mock(BoardPostReadRepository::class.java)
    private val comments = mock(BoardCommentReadRepository::class.java)
    private val reads = mock(BoardPostReadLogRepository::class.java)
    private val authority = mock(CouncilAuthoritySource::class.java)
    private val world = WorldStateReadEntity(id = 1, tickSeconds = 60)
    private val nation = NationReadEntity(id = 3, worldId = 1)
    private val me = GeneralReadEntity(id = 101, worldId = 1, nationId = 3, userId = "7", name = "본인",
        officerLevel = 12, meta = mapOf("lord" to true))
    private val other = GeneralReadEntity(id = 102, worldId = 1, nationId = 3, name = "동료")
    private val people = listOf(me, other)
    private val time = Instant.parse("2026-10-01T18:00:00Z")
    private val reader = CouncilReader(resolver, worlds, nations, generals, posts, comments, reads, authority,
        GameApiProcessWorld(1)) { time }

    init {
        resolve()
        `when`(worlds.findProcessWorld()).thenReturn(world)
        `when`(nations.findById(3)).thenReturn(Optional.of(nation))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(3)).thenReturn(people)
        `when`(authority.read(world, nation, people)).thenReturn(CouncilAuthority.unavailable())
    }

    private fun resolve() = `when`(resolver.resolve(7L)).thenReturn(
        GeneralResolver.ResolvedGeneral(me, me.officerLevel, 2, me.nationId, 1))

    private fun post(id: Int = 40, secret: Boolean = false, nationId: Int = 3, kind: String = "general") =
        BoardPostReadEntity(id = id, worldId = 1, nationId = nationId, isSecret = secret,
            authorGeneralId = 101, authorName = "본인", title = "글", contentHtml = "<p>본문</p>", createdAt = time, kind = kind)

    @Test
    fun `본인 무소속 두 방은 빈 봉투이고 비공개 원천을 조회하지 않는다`() {
        me.nationId = 0; resolve()
        CouncilRoom.entries.forEach { room ->
            val result = reader.page(7, room, null, null, 50)
            assertFalse(result.access.canRead); assertEquals("NO_AFFILIATION", result.access.reason)
            assertTrue(result.articles.isEmpty()); assertTrue(result.members.isEmpty()); assertNull(result.nextCursor)
            assertNotNull(result.blockedReason)
        }
        verifyNoInteractions(worlds, nations, generals, posts, comments, reads, authority)
    }

    @Test
    fun `옛 수뇌부 수치와 주공 표지만으로 기밀실에 접근할 수 없다`() {
        val failure = assertThrows(CouncilReadFailure::class.java) { reader.page(7, CouncilRoom.SECRET, null, null, 50) }
        assertEquals(503, failure.status); assertEquals("STATE_UNAVAILABLE", failure.code)
        verifyNoInteractions(posts, comments, reads)
    }

    @Test
    fun `정상 완전 원천에서 참가권 없는 사람은 빈 차단 봉투다`() {
        `when`(authority.read(world, nation, people)).thenReturn(CouncilAuthority(emptySet(), emptySet(), emptySet(), emptyMap(), true))
        val result = reader.page(7, CouncilRoom.SECRET, null, null, 50)
        assertFalse(result.access.canRead); assertEquals("SECRET_ACCESS_REQUIRED", result.access.reason)
        verifyNoInteractions(posts, comments, reads)
    }

    @Test
    fun `다른 선택 원천이 없어도 증명된 군주의 기밀실 권한은 유지하고 정원은 합성하지 않는다`() {
        `when`(authority.read(world, nation, people)).thenReturn(CouncilAuthority(setOf(101), setOf(101), setOf(101), mapOf(101 to "RULER"), false))
        `when`(posts.councilPage(3, true, listOf("general", "operation", "notice"), null, null, 51)).thenReturn(listOf(post(secret = true)))
        val result = reader.page(7, CouncilRoom.SECRET, null, null, 50)
        assertTrue(result.access.canRead); assertEquals("PARTIAL", result.membershipState)
        assertNull(result.articles.single().readers!!.total)
        assertEquals(listOf(101), result.members.map { it.generalId })
    }

    @Test
    fun `회의실 후보는 소속 방 종류와 커서 조건으로 제한하고 제목 동률은 id로 넘긴다`() {
        val scope = CouncilScope(1, 3, 101, CouncilRoom.MEETING, CouncilArticleKind.GENERAL)
        val after = CouncilCursor.encode(scope, CouncilPosition(time, 50))
        `when`(posts.councilPage(3, false, listOf("general"), time, 50, 2)).thenReturn(listOf(post(49), post(48)))
        val result = reader.page(7, CouncilRoom.MEETING, CouncilArticleKind.GENERAL, after, 1)
        assertEquals(49, result.articles.single().id)
        assertEquals(CouncilPosition(time, 49), CouncilCursor.decode(result.nextCursor, scope))
        verifyNoInteractions(reads)
    }

    @Test
    fun `저장소가 타국 후보를 잘못 반환해도 글 댓글 열람 이름을 노출하지 않는다`() {
        `when`(posts.councilPage(3, false, listOf("general", "operation", "notice"), null, null, 51)).thenReturn(listOf(post(nationId = 4)))
        val failure = assertThrows(CouncilReadFailure::class.java) { reader.page(7, CouncilRoom.MEETING, null, null, 50) }
        assertEquals("STATE_UNAVAILABLE", failure.code)
        verifyNoInteractions(comments, reads)
    }

    @Test
    fun `명부의 타국 장수와 다른 월드는 권한 평가와 글 조회 전에 거절한다`() {
        other.nationId = 4
        assertThrows(CouncilReadFailure::class.java) { reader.page(7, CouncilRoom.MEETING, null, null, 50) }
        verifyNoInteractions(authority, posts, comments, reads)
    }

    @Test
    fun `타인 장수를 역조회하지 않고 저장된 작성자 이름만 보존한다`() {
        val row = post().apply { authorGeneralId = 202; authorName = "당시 작성자"; contentHtml = "<script>bad()</script><p>안전</p>" }
        `when`(posts.councilPage(3, false, listOf("general", "operation", "notice"), null, null, 51)).thenReturn(listOf(row))
        val result = reader.page(7, CouncilRoom.MEETING, null, null, 50)
        assertEquals("당시 작성자", result.articles.single().author.name)
        assertNull(result.articles.single().author.portrait); assertFalse(result.articles.single().contentHtml.contains("script"))
        verify(generals, never()).findById(anyInt())
    }

    @Test
    fun `댓글의 세력 경계가 깨졌으면 응답에 내용을 싣지 않는다`() {
        `when`(posts.councilPage(3, false, listOf("general", "operation", "notice"), null, null, 51)).thenReturn(listOf(post()))
        `when`(comments.findByPostIdOrderByCreatedAtAscIdAsc(40)).thenReturn(listOf(BoardCommentReadEntity(
            id = 1, worldId = 1, postId = 40, nationId = 4, contentText = "타국 비밀")))
        assertThrows(CouncilReadFailure::class.java) { reader.page(7, CouncilRoom.MEETING, null, null, 50) }
    }

    @Test
    fun `회수한 다음 요청에는 같은 기밀실을 읽을 수 없고 옛 열람 기록을 권한으로 쓰지 않는다`() {
        val valid = CouncilAuthority(setOf(101), setOf(101), setOf(101), emptyMap(), true)
        val revoked = CouncilAuthority(emptySet(), emptySet(), emptySet(), emptyMap(), true)
        `when`(authority.read(world, nation, people)).thenReturn(valid, revoked)
        `when`(posts.councilPage(3, true, listOf("general", "operation", "notice"), null, null, 51)).thenReturn(listOf(post(secret = true)))
        assertTrue(reader.page(7, CouncilRoom.SECRET, null, null, 50).access.canRead)
        assertFalse(reader.page(7, CouncilRoom.SECRET, null, null, 50).access.canRead)
        verify(posts, times(1)).councilPage(3, true, listOf("general", "operation", "notice"), null, null, 51)
        verify(reads, times(1)).findByPostIds(listOf(40))
    }
}
