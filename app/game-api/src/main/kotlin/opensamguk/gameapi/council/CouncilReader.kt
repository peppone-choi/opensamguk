package opensamguk.gameapi.council

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardCommentReadRepository
import opensamguk.gameapi.read.BoardPostReadLogRepository
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.sanitize.HtmlSanitizer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Locale

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class CouncilReader(
    private val resolver: GeneralResolver,
    private val worlds: WorldStateReadRepository,
    private val nations: NationReadRepository,
    private val generals: GeneralReadRepository,
    private val posts: BoardPostReadRepository,
    private val comments: BoardCommentReadRepository,
    private val reads: BoardPostReadLogRepository,
    private val authority: CouncilAuthoritySource,
    processWorld: GameApiProcessWorld,
    private val now: () -> Instant = Instant::now,
) {
    private val worldId = processWorld.worldId.value

    fun page(userId: Long, room: CouncilRoom, kind: CouncilArticleKind?, cursor: String?, limit: Int): CouncilPage {
        if (limit !in 1..50) fail(400, "INVALID_REQUEST", "한 번에 읽을 글 수가 올바르지 않습니다.")
        val session = session(userId)
        val actor = session.actor
        if (actor.nationId <= 0) {
            val reason = "소속 세력이 없어 회의실을 이용할 수 없습니다."
            return CouncilPage(room, CouncilAccess(false, false, false, "NO_AFFILIATION"),
                blockedReason = reason, membershipState = "READY")
        }
        val world = checkNotNull(session.world)
        val people = session.people
        val proof = session.authority
        val nationId = actor.nationId
        val secret = room == CouncilRoom.SECRET
        if (secret && actor.id !in proof.readers) {
            if (!proof.complete) unavailable()
            return CouncilPage(room, CouncilAccess(false, false, false, "SECRET_ACCESS_REQUIRED"),
                blockedReason = "기밀실 참여 권한이 없습니다.", membershipState = "READY")
        }
        val scope = CouncilScope(worldId, nationId, actor.id, room, kind)
        val position = try { CouncilCursor.decode(cursor, scope) } catch (_: InvalidCouncilCursor) {
            fail(400, "INVALID_CURSOR", "회의실 커서가 올바르지 않습니다.")
        }
        val kinds = kind?.let { listOf(it.name.lowercase(Locale.ROOT)) }
            ?: CouncilArticleKind.entries.map { it.name.lowercase(Locale.ROOT) }
        val candidates = posts.councilPage(nationId, secret, kinds, position?.createdAt, position?.id, limit + 1)
        if (candidates.any { it.worldId != worldId || it.nationId != nationId || it.isSecret != secret || it.kind !in kinds } ||
            candidates.zipWithNext().any { (a, b) -> a.createdAt < b.createdAt || (a.createdAt == b.createdAt && a.id <= b.id) } ||
            candidates.any { position != null && (it.createdAt > position.createdAt ||
                (it.createdAt == position.createdAt && it.id >= position.id)) }) unavailable()
        val selected = candidates.take(limit)
        val byId = people.associateBy { it.id }
        fun person(id: Int, historicalName: String? = null): CouncilPerson? {
            val row = byId[id]
            return if (row == null) historicalName?.let { CouncilPerson(id, it, null) }
            else CouncilPerson(row.id, row.name, portrait(row), proof.roles[row.id])
        }
        val logs = if (secret) reads.findByPostIds(selected.map { it.id }) else emptyList()
        if (logs.any { it.worldId != worldId || selected.none { post -> post.id == it.postId } }) unavailable()
        val articles = selected.map { post ->
            val commentRows = comments.findByPostIdOrderByCreatedAtAscIdAsc(post.id)
            if (commentRows.any { it.worldId != worldId || it.nationId != nationId || it.isSecret != secret || it.postId != post.id }) unavailable()
            CouncilArticle(post.id, CouncilArticleKind.valueOf(post.kind.uppercase(Locale.ROOT)), post.title,
                HtmlSanitizer.sanitize(post.contentHtml), checkNotNull(person(post.authorGeneralId, post.authorName)),
                post.createdAt, post.operationId, if (secret) CouncilReaders(
                    logs.filter { it.postId == post.id }.mapNotNull { person(it.generalId) }.distinctBy { it.generalId },
                    proof.readers.size.takeIf { proof.complete },
                ) else null,
                commentRows.map { row -> CouncilComment(row.id, row.contentText,
                    checkNotNull(person(row.authorGeneralId, row.authorName)), row.createdAt) })
        }
        val members = people.filter { it.npcState < 2 && (!secret || it.id in proof.readers) }
            .sortedBy { it.id }.map { row -> CouncilMember(row.id, row.name, portrait(row),
                world.tickSeconds.takeIf { it > 0 }?.let { seconds ->
                    row.turnTime?.let { !it.isBefore(now().minusSeconds(seconds.toLong())) }
                }) }
        return CouncilPage(room, CouncilAccess(true, !secret || actor.id in proof.writers,
            actor.id in proof.noticeWriters,
            canManageAccess = proof.rulerGeneralId == actor.id && proof.rulerRevision != null,
            designationRevision = proof.designationRevision.takeIf { proof.rulerGeneralId == actor.id }), members, articles,
            if (candidates.size > limit) selected.last().let { CouncilCursor.encode(scope, CouncilPosition(it.createdAt, it.id)) } else null,
            membershipState = if (!secret || proof.complete) "READY" else "PARTIAL")
    }

    fun session(userId: Long): CouncilSession {
        val resolved = resolver.resolve(userId) ?: fail(403, "FORBIDDEN", "본인의 장수가 필요합니다.")
        val actor = resolved.general
        if (actor.worldId != worldId || actor.id <= 0) unavailable()
        if (actor.userId?.toLongOrNull() != userId || actor.npcState == 5)
            fail(403, "FORBIDDEN", "본인의 현재 장수가 필요합니다.")
        if (actor.nationId <= 0) return CouncilSession(actor, null, emptyList(), CouncilAuthority.unavailable())
        val world = worlds.findProcessWorld() ?: unavailable()
        if (world.id != worldId) unavailable()
        val nation = nations.findById(actor.nationId).orElse(null) ?: unavailable()
        if (nation.worldId != worldId || nation.id != actor.nationId) unavailable()
        val people = generals.findByNationIdOrderByOfficerLevelDescIdAsc(nation.id)
        if (people.any { it.worldId != worldId || it.nationId != nation.id } ||
            people.map { it.id }.distinct().size != people.size) unavailable()
        val current = people.singleOrNull { it.id == actor.id } ?: unavailable()
        if (current.userId != actor.userId || current.npcState != actor.npcState) unavailable()
        val proof = authority.read(world, nation, people)
        if ((proof.readers + proof.writers + proof.noticeWriters).any { id -> people.none { it.id == id } }) unavailable()
        return CouncilSession(current, world, people, proof)
    }

    private fun portrait(general: GeneralReadEntity) = CouncilPortrait(general.picture, general.imageServer)
    private fun unavailable(): Nothing = fail(503, "STATE_UNAVAILABLE", "회의실 권한과 저장 상태를 확인할 수 없습니다.")
    private fun fail(status: Int, code: String, reason: String): Nothing = throw CouncilReadFailure(status, code, reason)
}
