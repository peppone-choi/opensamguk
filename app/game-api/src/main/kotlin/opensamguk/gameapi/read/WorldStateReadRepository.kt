package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Read-only JPA mapping of the singleton `world_state` row for the PRECHECK path
 * (game-api ONLY — §7).
 *
 * Carries the per-turn clock (`current_year`/`current_month`) the precheck env-builder keys on, plus
 * the `scenario_code` + `config`/`meta` jsonb bags (where the scenario `startYear` lives — resolved by
 * the E2 [PrecheckStateViewFactory], which calls the SAME shared `WorldEnvBuilder` the daemon uses).
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

    @Column(name = "start_year")
    var startYear: Int? = null,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "config", columnDefinition = "jsonb")
    var config: Map<String, Any?> = linkedMapOf(),

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "meta", columnDefinition = "jsonb")
    var meta: Map<String, Any?> = linkedMapOf(),

    @Column(name = "status")
    var status: String = "OPEN",
)

interface WorldStateReadRepository : JpaRepository<WorldStateReadEntity, Int>
