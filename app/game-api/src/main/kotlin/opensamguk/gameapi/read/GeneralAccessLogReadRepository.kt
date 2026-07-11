package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "general_access_log")
class GeneralAccessLogReadEntity(
    @Id
    @Column(name = "id")
    var id: Long = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(name = "last_refresh")
    var lastRefresh: Instant? = null,

    @Column(name = "refresh")
    var refresh: Int = 0,

    @Column(name = "refresh_total")
    var refreshTotal: Int = 0,

    @Column(name = "refresh_score")
    var refreshScore: Int = 0,

    @Column(name = "refresh_score_total")
    var refreshScoreTotal: Int = 0,
)

interface GeneralAccessLogReadRepository : JpaRepository<GeneralAccessLogReadEntity, Long> {
    fun findByGeneralId(generalId: Int): GeneralAccessLogReadEntity?
    fun findByGeneralIdIn(generalIds: Collection<Int>): List<GeneralAccessLogReadEntity>
}
