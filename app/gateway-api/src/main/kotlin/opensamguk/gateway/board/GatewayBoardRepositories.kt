package opensamguk.gateway.board

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface GatewayBoardPostRepository : JpaRepository<GatewayBoardPostEntity, Long> {
    // 피드는 삭제분을 쿼리에서 걸러낸다. 응답에서만 가리면 묘비가 목록에 남고 페이지네이션 칸까지
    // 먹는다 — 소프트딜리트는 감사 기록용이지 노출용이 아니다.
    fun findByDeletedAtIsNull(pageable: Pageable): Page<GatewayBoardPostEntity>

    fun findByCategoryAndDeletedAtIsNull(
        category: GatewayBoardCategory,
        pageable: Pageable,
    ): Page<GatewayBoardPostEntity>

    // 어드민 감사용 — 삭제분까지 본다. 공개 피드는 위 두 쿼리만 쓴다.
    fun findByCategory(category: GatewayBoardCategory, pageable: Pageable): Page<GatewayBoardPostEntity>
}

interface GatewayBoardCommentRepository : JpaRepository<GatewayBoardCommentEntity, Long> {
    // 삭제된 댓글은 묘비도 남기지 않는다 — 글과 같은 규칙이다.
    fun findByPostIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(postId: Long): List<GatewayBoardCommentEntity>
}
