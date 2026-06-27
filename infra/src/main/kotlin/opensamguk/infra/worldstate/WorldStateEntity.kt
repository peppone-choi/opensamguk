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

    @Column(name = "current_phase", nullable = false)
    var currentPhase: Int = 1,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Int? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    // FC1 (V4 calendar columns) — the source ServerClock / MonthlyPipeline / MonthScopedRng read.
    // `turnDate` (func.php:1250-1275) reads startyear/starttime/turnterm; `executeAllCommand` freezes
    // on isunited==2|3; the month-scoped RNG seeds with hidden_seed. Nullable (scenario import
    // populates them; the P1/P2 IT seeds omit them); `tick_seconds` is the canonical cadence in
    // SECONDS and `turnTerm` is its MINUTES view (turn_term = tick_seconds / 60).
    @Column(name = "start_year")
    var startYear: Int? = null,

    @Column(name = "start_time")
    var startTime: OffsetDateTime? = null,

    @Column(name = "turn_term")
    var turnTerm: Int? = null,

    @Column(name = "isunited", nullable = false)
    var isunited: Int = 0,

    @Column(name = "hidden_seed")
    var hiddenSeed: String? = null,
)
