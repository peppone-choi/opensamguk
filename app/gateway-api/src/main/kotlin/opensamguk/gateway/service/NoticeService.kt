package opensamguk.gateway.service

import opensamguk.gateway.dto.NoticeResponse
import opensamguk.gateway.dto.NoticeUpsertRequest
import opensamguk.infra.entity.GatewayNoticeEntity
import opensamguk.infra.read.GatewayNoticeRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

class NoticeNotFoundException(id: Long) : RuntimeException("공지를 찾을 수 없습니다: $id")

/**
 * 게이트웨이 공지 — 계정 층(루트DB) 소유. 게임 월드가 아니므로 JPA 직접 쓰기가 정상이다(AdminMemberService 와 같은 근거).
 * 공개 피드는 고정 우선·최신순 최대 [PUBLIC_LIMIT] 건. 본문은 평문으로 저장하고 HTML 을 만들지 않는다.
 */
@Service
class NoticeService(private val repository: GatewayNoticeRepository) {
    @Transactional(readOnly = true)
    fun publicFeed(limit: Int = PUBLIC_LIMIT): List<NoticeResponse> =
        repository.findFeed(PageRequest.of(0, limit.coerceIn(1, MAX_LIMIT))).map { it.toResponse() }

    @Transactional(readOnly = true)
    fun adminList(limit: Int = ADMIN_LIMIT): List<NoticeResponse> =
        repository.findAllForAdmin(PageRequest.of(0, limit.coerceIn(1, MAX_LIMIT))).map { it.toResponse() }

    @Transactional
    fun create(request: NoticeUpsertRequest, actorAccountId: Long?): NoticeResponse {
        val now = Instant.now()
        val saved = repository.save(
            GatewayNoticeEntity(
                title = request.title.trim(),
                body = request.body.trim(),
                pinned = request.pinned,
                publishedAt = now,
                createdByAccountId = actorAccountId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return saved.toResponse()
    }

    @Transactional
    fun update(id: Long, request: NoticeUpsertRequest): NoticeResponse {
        val notice = repository.findById(id).orElseThrow { NoticeNotFoundException(id) }
        notice.title = request.title.trim()
        notice.body = request.body.trim()
        notice.pinned = request.pinned
        notice.updatedAt = Instant.now()
        return repository.save(notice).toResponse()
    }

    @Transactional
    fun setPinned(id: Long, pinned: Boolean): NoticeResponse {
        val notice = repository.findById(id).orElseThrow { NoticeNotFoundException(id) }
        notice.pinned = pinned
        notice.updatedAt = Instant.now()
        return repository.save(notice).toResponse()
    }

    /** soft-delete — 공개 피드에서 빠지고 관리 목록에는 「삭제됨」으로 남는다. */
    @Transactional
    fun delete(id: Long): NoticeResponse {
        val notice = repository.findById(id).orElseThrow { NoticeNotFoundException(id) }
        if (notice.deletedAt == null) {
            notice.deletedAt = Instant.now()
            notice.updatedAt = notice.deletedAt!!
        }
        return repository.save(notice).toResponse()
    }

    private fun GatewayNoticeEntity.toResponse() = NoticeResponse(
        id = id,
        title = title,
        body = body,
        pinned = pinned,
        publishedAt = publishedAt.toString(),
        deleted = deletedAt != null,
    )

    companion object {
        const val PUBLIC_LIMIT = 20
        const val ADMIN_LIMIT = 100
        const val MAX_LIMIT = 200
    }
}
