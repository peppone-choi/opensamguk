package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F2 Wave 6 — read-only JPA mapping of the `general_turn` ring-buffer row (game-api READ path ONLY).
 *
 * The DAEMON writes this ring via the JDBC [opensamguk.infra.persistence.ReservedTurnRepository]
 * (the ONE write path — §0.1 #3); game-api only READS it here to render the reserved-turn panel.
 *
 * Column set = the V1 baseline `general_turn` + the V2 `brief text` column:
 *   `id, general_id, turn_idx, action_code, arg(jsonb), brief, created_at`.
 * `arg` maps as the raw jsonb text (the UI shows the brief; the arg is the structured target).
 */
@Entity
@Table(name = "general_turn")
class GeneralTurnReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "turn_idx")
    var turnIdx: Int = 0,

    @Column(name = "action_code")
    var actionCode: String = "휴식",

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "arg", columnDefinition = "jsonb")
    var arg: Map<String, Any?> = linkedMapOf(),

    @Column(name = "brief")
    var brief: String = "휴식",
)

interface GeneralTurnReadRepository : JpaRepository<GeneralTurnReadEntity, Int> {
    /** The general's reserved ring, ordered by slot (turn_idx ASC) for a stable 0..N-1 list. */
    fun findByGeneralIdOrderByTurnIdxAsc(generalId: Int): List<GeneralTurnReadEntity>
}
