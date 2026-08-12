package opensamguk.gateway.board

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface GatewayBoardPostRepository : JpaRepository<GatewayBoardPostEntity, Long> {
    fun findByCategory(category: GatewayBoardCategory, pageable: Pageable): Page<GatewayBoardPostEntity>
}

interface GatewayBoardCommentRepository : JpaRepository<GatewayBoardCommentEntity, Long> {
    fun findByPostIdOrderByCreatedAtAscIdAsc(postId: Long): List<GatewayBoardCommentEntity>
}
