package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

/** Phase 4X-B 작전 READ — process-world scoped. PK (world_id, id), 엔진 할당 id(V56). */
@Entity
@Table(name = "operation")
@IdClass(WorldRowId::class)
class OperationReadEntity(
    @Id @Column(name = "world_id") var worldId: Int = 0,
    @Id @Column(name = "id") var id: Int = 0,
    @Column(name = "nation_id") var nationId: Int = 0,
    @Column(name = "kind") var kind: String = "",
    @Column(name = "target_city_id") var targetCityId: Int = 0,
    @Column(name = "title") var title: String = "",
    @Column(name = "fallback_text") var fallbackText: String? = null,
    @Column(name = "declared_by_general_id") var declaredByGeneralId: Int? = null,
    @Column(name = "declared_year") var declaredYear: Int = 0,
    @Column(name = "declared_month") var declaredMonth: Int = 0,
    @Column(name = "declared_phase") var declaredPhase: Int = 1,
    @Column(name = "deadline_year") var deadlineYear: Int = 0,
    @Column(name = "deadline_month") var deadlineMonth: Int = 0,
    @Column(name = "deadline_phase") var deadlinePhase: Int = 1,
    @Column(name = "status") var status: String = "declared",
    @Column(name = "m_departed") var departed: Boolean = false,
    @Column(name = "m_arrived") var arrived: Boolean = false,
    @Column(name = "m_supplied") var supplied: Boolean = false,
    @Column(name = "m_objective") var objective: Boolean = false,
    @Column(name = "closed_reason") var closedReason: String? = null,
)

@Entity
@Table(name = "operation_unit")
@IdClass(WorldRowId::class)
class OperationUnitReadEntity(
    @Id @Column(name = "world_id") var worldId: Int = 0,
    @Id @Column(name = "id") var id: Int = 0,
    @Column(name = "operation_id") var operationId: Int = 0,
    @Column(name = "general_id") var generalId: Int = 0,
    @Column(name = "bugok_id") var bugokId: Int? = null,
    @Column(name = "role") var role: String = "main",
    @Column(name = "joined_city_id") var joinedCityId: Int = 0,
)

interface OperationReadRawRepository : SpringDataRepository<OperationReadEntity, WorldRowId> {
    fun findByWorldIdAndNationIdOrderByIdDesc(worldId: Int, nationId: Int): List<OperationReadEntity>
    fun findByWorldIdAndId(worldId: Int, id: Int): OperationReadEntity?
}

interface OperationUnitReadRawRepository : SpringDataRepository<OperationUnitReadEntity, WorldRowId> {
    fun findByWorldIdAndOperationIdInOrderByIdAsc(worldId: Int, operationIds: Collection<Int>): List<OperationUnitReadEntity>
}

@Repository
class OperationReadRepository(
    private val operations: OperationReadRawRepository,
    private val units: OperationUnitReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun operationsOf(nationId: Int): List<OperationReadEntity> = operations.findByWorldIdAndNationIdOrderByIdDesc(worldId.value, nationId)
    fun findById(id: Int): OperationReadEntity? = operations.findByWorldIdAndId(worldId.value, id)
    fun unitsOf(operationIds: Collection<Int>): List<OperationUnitReadEntity> =
        if (operationIds.isEmpty()) emptyList() else units.findByWorldIdAndOperationIdInOrderByIdAsc(worldId.value, operationIds)
}
