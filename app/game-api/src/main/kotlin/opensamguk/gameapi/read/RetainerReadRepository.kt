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

data class WorldRowId(var worldId: Int = 0, var id: Int = 0) : Serializable

/** Phase 4X-A 가신 READ — process-world scoped. PK (world_id, id), 엔진 할당 id(V55). */
@Entity
@Table(name = "general_retainers")
@IdClass(WorldRowId::class)
class GeneralRetainerReadEntity(
    @Id @Column(name = "world_id") var worldId: Int = 0,
    @Id @Column(name = "id") var id: Int = 0,
    @Column(name = "master_general_id") var masterGeneralId: Int = 0,
    @Column(name = "origin") var origin: String = "RECRUITED",
    @Column(name = "general_id") var generalId: Int? = null,
    @Column(name = "name") var name: String = "",
    @Column(name = "relation") var relation: String = "guest",
    @Column(name = "role") var role: String = "NONE",
    @Column(name = "has_own_bugok") var hasOwnBugok: Boolean = false,
    @Column(name = "release_policy") var releasePolicy: String = "MASTER_ONLY",
    @Column(name = "loyalty") var loyalty: Int = 50,
    @Column(name = "task") var task: String = "none",
)

/** Phase 4X-A 부곡 READ — process-world scoped. */
@Entity
@Table(name = "general_bugok")
@IdClass(WorldRowId::class)
class GeneralBugokReadEntity(
    @Id @Column(name = "world_id") var worldId: Int = 0,
    @Id @Column(name = "id") var id: Int = 0,
    @Column(name = "master_general_id") var masterGeneralId: Int = 0,
    @Column(name = "name") var name: String = "",
    @Column(name = "troops") var troops: Int = 0,
    @Column(name = "crew_type_id") var crewTypeId: Int = 0,
    @Column(name = "training") var training: Int = 0,
    @Column(name = "morale") var morale: Int = 0,
    @Column(name = "fatigue") var fatigue: Int = 0,
    @Column(name = "provisions") var provisions: Int = 0,
    @Column(name = "commander_retainer_id") var commanderRetainerId: Int? = null,
)

interface GeneralRetainerReadRawRepository : SpringDataRepository<GeneralRetainerReadEntity, WorldRowId> {
    fun findByWorldIdAndMasterGeneralIdOrderByIdAsc(worldId: Int, masterGeneralId: Int): List<GeneralRetainerReadEntity>
}

interface GeneralBugokReadRawRepository : SpringDataRepository<GeneralBugokReadEntity, WorldRowId> {
    fun findByWorldIdAndMasterGeneralIdOrderByIdAsc(worldId: Int, masterGeneralId: Int): List<GeneralBugokReadEntity>
}

@Repository
class RetainerReadRepository(
    private val retainers: GeneralRetainerReadRawRepository,
    private val bugoks: GeneralBugokReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun retainersOf(masterGeneralId: Int): List<GeneralRetainerReadEntity> =
        retainers.findByWorldIdAndMasterGeneralIdOrderByIdAsc(worldId.value, masterGeneralId)

    fun bugoksOf(masterGeneralId: Int): List<GeneralBugokReadEntity> =
        bugoks.findByWorldIdAndMasterGeneralIdOrderByIdAsc(worldId.value, masterGeneralId)
}
