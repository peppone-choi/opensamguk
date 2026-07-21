package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import java.io.Serializable
import org.springframework.data.repository.Repository as SpringDataRepository

data class TroopReadId(var worldId: Int = 0, var troopLeader: Int = 0) : Serializable

/**
 * troop READ — process-world scoped (OPENSAM-127 residual). PK (world_id, troop_leader).
 */
@Entity
@Table(name = "troop")
@IdClass(TroopReadId::class)
class TroopReadEntity(
    @Id
    @Column(name = "world_id")
    var worldId: Int = 0,

    @Id
    @Column(name = "troop_leader")
    var troopLeader: Int = 0,

    @Column(name = "nation")
    var nation: Int = 0,

    @Column(name = "name")
    var name: String = "",
)

interface TroopReadRawRepository : SpringDataRepository<TroopReadEntity, TroopReadId> {
    fun findByWorldId(worldId: Int): List<TroopReadEntity>
    fun findByWorldIdAndNationOrderByTroopLeaderAsc(worldId: Int, nation: Int): List<TroopReadEntity>
    fun findByWorldIdAndTroopLeader(worldId: Int, troopLeader: Int): TroopReadEntity?
}

@Repository
class TroopReadRepository(
    private val raw: TroopReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findAll(): List<TroopReadEntity> = raw.findByWorldId(worldId.value)

    fun findByNationOrderByTroopLeaderAsc(nation: Int): List<TroopReadEntity> =
        raw.findByWorldIdAndNationOrderByTroopLeaderAsc(worldId.value, nation)

    fun findById(troopLeader: Int): java.util.Optional<TroopReadEntity> =
        java.util.Optional.ofNullable(raw.findByWorldIdAndTroopLeader(worldId.value, troopLeader))
}
