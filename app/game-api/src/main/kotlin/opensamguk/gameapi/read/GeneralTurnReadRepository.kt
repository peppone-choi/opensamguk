package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository as SpringDataRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

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

    @Column(name = "world_id")
    var worldId: Int = 0,

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

interface GeneralTurnReadRawRepository : SpringDataRepository<GeneralTurnReadEntity, Int> {
    fun findByWorldIdAndId(worldId: Int, id: Int): Optional<GeneralTurnReadEntity>
    fun findByWorldId(worldId: Int): List<GeneralTurnReadEntity>
    fun countByWorldId(worldId: Int): Long

    /** The general's reserved ring, ordered by slot (turn_idx ASC) for a stable 0..N-1 list. */
    fun findByWorldIdAndGeneralIdOrderByTurnIdxAsc(worldId: Int, generalId: Int): List<GeneralTurnReadEntity>

    /**
     * W3 GeneralList — 여러 장수의 첫 5 예약 슬롯을 1회 일괄 조회(N+1 방지). PHP `GeneralList.php:215-218`의
     * `SELECT general_id, turn_idx, action, arg, brief FROM general_turn WHERE general_id IN %li AND
     * turn_idx < 5 ORDER BY general_id asc, turn_idx asc`를 그대로 이식.
     *
     * 호출부는 결과를 `general_id`로 그룹핑하고 각 그룹 안의 turn_idx asc 순서로 reservedCommand 목록을
     * 만든다(PHP의 일괄 → general별 누적 순서 동일).
     */
    @Query(
        "select t from GeneralTurnReadEntity t " +
            "where t.worldId = :worldId and t.generalId in :generalIds and t.turnIdx < 5 " +
            "order by t.generalId asc, t.turnIdx asc",
    )
    fun findReservedByWorldIdAndGeneralIds(
        @Param("worldId") worldId: Int,
        @Param("generalIds") generalIds: Collection<Int>,
    ): List<GeneralTurnReadEntity>
}

@Repository
class GeneralTurnReadRepository(
    private val raw: GeneralTurnReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findById(id: Int): Optional<GeneralTurnReadEntity> = raw.findByWorldIdAndId(worldId.value, id)

    fun findAll(): List<GeneralTurnReadEntity> = raw.findByWorldId(worldId.value)

    fun count(): Long = raw.countByWorldId(worldId.value)

    fun findByGeneralIdOrderByTurnIdxAsc(generalId: Int): List<GeneralTurnReadEntity> =
        raw.findByWorldIdAndGeneralIdOrderByTurnIdxAsc(worldId.value, generalId)

    fun findReservedByGeneralIds(generalIds: Collection<Int>): List<GeneralTurnReadEntity> =
        raw.findReservedByWorldIdAndGeneralIds(worldId.value, generalIds)
}
