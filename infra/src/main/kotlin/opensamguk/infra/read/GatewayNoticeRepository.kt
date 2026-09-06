package opensamguk.infra.read

import opensamguk.infra.entity.GatewayNoticeEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface GatewayNoticeRepository : JpaRepository<GatewayNoticeEntity, Long> {
    /** 공개 피드: 삭제 안 된 것만, 고정 우선 → 최신순. */
    @Query(
        "select n from GatewayNoticeEntity n where n.deletedAt is null " +
            "order by n.pinned desc, n.publishedAt desc, n.id desc",
    )
    fun findFeed(pageable: Pageable): List<GatewayNoticeEntity>

    /** 관리 목록: 삭제된 것도 포함, 최신순. */
    @Query("select n from GatewayNoticeEntity n order by n.publishedAt desc, n.id desc")
    fun findAllForAdmin(pageable: Pageable): List<GatewayNoticeEntity>
}
