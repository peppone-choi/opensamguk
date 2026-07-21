package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * Read-only `world_state` for precheck/read — process-world row only (OPENSAM-127).
 * Never default-guesses another world via findAll().
 */
@Entity
@Table(name = "world_state")
class WorldStateReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "scenario_code")
    var scenarioCode: String = "",

    @Column(name = "current_year")
    var currentYear: Int = 0,

    @Column(name = "current_month")
    var currentMonth: Int = 0,

    @Column(name = "tick_seconds")
    var tickSeconds: Int = 0,

    @Column(name = "current_phase")
    var currentPhase: Int = 1,

    @Column(name = "start_year")
    var startYear: Int? = null,

    @Column(name = "start_time")
    var startTime: Instant? = null,

    @Column(name = "isunited")
    var isunited: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "config", columnDefinition = "jsonb")
    var config: Map<String, Any?> = linkedMapOf(),

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),

    @Column(name = "status")
    var status: String = "OPEN",

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

interface WorldStateReadRawRepository : SpringDataRepository<WorldStateReadEntity, Int> {
    fun findById(id: Int): Optional<WorldStateReadEntity>
}

@Repository
class WorldStateReadRepository(
    private val raw: WorldStateReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findById(id: Int): Optional<WorldStateReadEntity> =
        if (id == worldId.value) raw.findById(id) else Optional.empty()

    /** Process world only — never returns rows from another world. */
    fun findAll(): List<WorldStateReadEntity> =
        raw.findById(worldId.value).map { listOf(it) }.orElse(emptyList())

    fun findProcessWorld(): WorldStateReadEntity? = raw.findById(worldId.value).orElse(null)
}
