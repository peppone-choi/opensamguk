package opensamguk.infra.worldstate

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "world_state")
class WorldStateEntity(
    @Column(name = "scenario_code", nullable = false)
    var scenarioCode: String,

    @Column(name = "current_year", nullable = false)
    var currentYear: Int,

    @Column(name = "current_month", nullable = false)
    var currentMonth: Int,

    @Column(name = "tick_seconds", nullable = false)
    var tickSeconds: Int,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
