package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F4 READ-only JPA mapping of the `inheritance_log` row (V1 baseline) for the 유산 page log list.
 *
 * Keyed by `user_id text` (the cross-season inheritance owner). The fresh seed has no inherit logs →
 * empty list, never 500. game-api ONLY (§7); never written here.
 */
@Entity
@Table(name = "inheritance_log")
class InheritanceLogReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "user_id")
    var userId: String = "",

    @Column(name = "year")
    var year: Int = 0,

    @Column(name = "month")
    var month: Int = 0,

    @Column(name = "text")
    var text: String = "",
)

interface InheritanceLogReadRepository : JpaRepository<InheritanceLogReadEntity, Int> {
    /** The most-recent inherit logs for a user (legacy LIMIT 30, newest first). */
    fun findByUserIdOrderByIdDesc(userId: String, pageable: Pageable): List<InheritanceLogReadEntity>
}
