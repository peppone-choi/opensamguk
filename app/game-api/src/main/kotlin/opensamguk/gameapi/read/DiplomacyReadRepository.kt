package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.logic.domain.Diplomacy
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * Read-only `diplomacy` for precheck — process-world scoped (OPENSAM-127).
 */
@Entity
@Table(name = "diplomacy")
class DiplomacyReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "src_nation_id")
    var srcNationId: Int = 0,

    @Column(name = "dest_nation_id")
    var destNationId: Int = 0,

    @Column(name = "state_code")
    var stateCode: Int = 0,

    @Column(name = "term")
    var term: Int = 0,
) {
    fun toLogic(): Diplomacy = Diplomacy(
        me = srcNationId,
        you = destNationId,
        state = stateCode,
        term = term,
    )
}

interface DiplomacyReadRawRepository : SpringDataRepository<DiplomacyReadEntity, Int> {
    fun findByWorldId(worldId: Int): List<DiplomacyReadEntity>
    fun findByWorldIdAndSrcNationId(worldId: Int, srcNationId: Int): List<DiplomacyReadEntity>
}

@Repository
class DiplomacyReadRepository(
    private val raw: DiplomacyReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findAll(): List<DiplomacyReadEntity> = raw.findByWorldId(worldId.value)

    fun findBySrcNationId(srcNationId: Int): List<DiplomacyReadEntity> =
        raw.findByWorldIdAndSrcNationId(worldId.value, srcNationId)
}
