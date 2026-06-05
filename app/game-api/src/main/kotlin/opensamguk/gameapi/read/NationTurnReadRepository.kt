package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F4 READ-only JPA mapping of the `nation_turn` row (V1 baseline + V2 `brief`) for the 사령부
 * (chief-reserved) page — the reserved nation commands per officer level.
 *
 * game-api ONLY (§7); never written here. The reserved turns are keyed
 * `(nation_id, officer_level, turn_idx)`; the page renders each chief post's reserved-turn list.
 */
@Entity
@Table(name = "nation_turn")
class NationTurnReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "officer_level")
    var officerLevel: Int = 0,

    @Column(name = "turn_idx")
    var turnIdx: Int = 0,

    @Column(name = "action_code")
    var actionCode: String = "",

    // W3-ChiefCenter — 예약 국가 명령의 구조화 인자(`nation_turn.arg` jsonb, V1 baseline:126
    // `jsonb NOT NULL DEFAULT '{}'`). PHP `GetReservedCommand.php:99`가 슬롯마다 `Json::decode($arg)`로
    // 내려보내던 누락된 read 필드. 다른 read 엔티티(GeneralTurnReadEntity.arg)와 동일하게
    // MetaJsonConverter로 디코드(삽입순서 보존 맵). 인자 없는 슬롯은 빈 맵.
    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "arg", columnDefinition = "jsonb")
    var arg: Map<String, Any?> = linkedMapOf(),

    @Column(name = "brief")
    var brief: String = "",
)

interface NationTurnReadRepository : JpaRepository<NationTurnReadEntity, Int> {
    /** All reserved nation-command rows for a nation, ordered by officer level then turn index. */
    fun findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId: Int): List<NationTurnReadEntity>
}
