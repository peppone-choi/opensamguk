package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * W3 FrontGlobalInfo `auctionCount` — process-world scoped (OPENSAM-127).
 */
@Entity
@Table(name = "ng_auction")
class AuctionCountReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "finished")
    var finished: Boolean = false,
)

interface AuctionCountReadRawRepository : SpringDataRepository<AuctionCountReadEntity, Int> {
    fun countByWorldIdAndFinished(worldId: Int, finished: Boolean): Long
}

@Repository
class AuctionCountReadRepository(
    private val raw: AuctionCountReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    /** 진행중(미종료) 경매 수 = PHP `count(*) FROM ng_auction WHERE finished = 0` within process world. */
    fun countByFinished(finished: Boolean): Long =
        raw.countByWorldIdAndFinished(worldId.value, finished)
}
