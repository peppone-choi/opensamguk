package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.repository.Repository as SpringDataRepository

/** Phase 4X-C 출병 계획 READ — process-world scoped. PK (world_id, id), 엔진 할당 id(V57). SMALLINT 열은 `Short`(validate). */
@Entity
@Table(name = "battle_plan")
@IdClass(WorldRowId::class)
class BattlePlanReadEntity(
    @Id @Column(name = "world_id") var worldId: Int = 0,
    @Id @Column(name = "id") var id: Int = 0,
    @Column(name = "general_id") var generalId: Int = 0,
    @Column(name = "target_city_id") var targetCityId: Int = 0,
    @Column(name = "stance") var stance: String = "assault",
    @Column(name = "retreat_loss_pct") var retreatLossPct: Short? = null,
    @Column(name = "retreat_morale_below") var retreatMoraleBelow: Short? = null,
    @Column(name = "sealed_at") var sealedAt: Instant? = null,
    @Column(name = "sealed_year") var sealedYear: Short? = null,
    @Column(name = "sealed_month") var sealedMonth: Short? = null,
    @Column(name = "sealed_phase") var sealedPhase: Short? = null,
    @Column(name = "resolved_year") var resolvedYear: Short? = null,
    @Column(name = "resolved_month") var resolvedMonth: Short? = null,
    @Column(name = "resolved_phase") var resolvedPhase: Short? = null,
    @Column(name = "version") var version: Int = 1,
)

/** `battle_replay` READ(INSERT 전용 기록). `battle_phases_json` 은 TEXT 그대로(저장 바이트 = 해시 입력). */
@Entity
@Table(name = "battle_replay")
@IdClass(WorldRowId::class)
class BattleReplayReadEntity(
    @Id @Column(name = "world_id") var worldId: Int = 0,
    @Id @Column(name = "id") var id: Int = 0,
    @Column(name = "battle_plan_id") var battlePlanId: Int? = null,
    @Column(name = "operation_id") var operationId: Int? = null,
    @Column(name = "attacker_general_id") var attackerGeneralId: Int? = null,
    @Column(name = "attacker_name") var attackerName: String = "",
    @Column(name = "attacker_nation_id") var attackerNationId: Int = 0,
    @Column(name = "defender_city_id") var defenderCityId: Int = 0,
    @Column(name = "defender_city_name") var defenderCityName: String = "",
    @Column(name = "defender_nation_id") var defenderNationId: Int = 0,
    @Column(name = "year") var year: Short = 0,
    @Column(name = "month") var month: Short = 1,
    @Column(name = "phase") var phase: Short = 1,
    @Column(name = "war_seed") var warSeed: String = "",
    @Column(name = "input_hash") var inputHash: String = "",
    @Column(name = "replay_hash") var replayHash: String = "",
    @Column(name = "schema_version") var schemaVersion: Short = 1,
    @Column(name = "battle_phases_json") var battlePhasesJson: String = "",
    @Column(name = "attacker_crew_before") var attackerCrewBefore: Int = 0,
    @Column(name = "attacker_crew_after") var attackerCrewAfter: Int = 0,
    @Column(name = "attacker_dead") var attackerDead: Int = 0,
    @Column(name = "defender_dead") var defenderDead: Int = 0,
    @Column(name = "rice_used") var riceUsed: Int = 0,
    @Column(name = "result") var result: String = "repelled",
    @Column(name = "plan_stop") var planStop: String? = null,
    @Column(name = "plan_stance") var planStance: String? = null,
    @Column(name = "plan_retreat_loss_pct") var planRetreatLossPct: Short? = null,
    @Column(name = "plan_retreat_morale_below") var planRetreatMoraleBelow: Short? = null,
    @Column(name = "created_at") var createdAt: Instant? = null,
)

interface BattlePlanReadRawRepository : SpringDataRepository<BattlePlanReadEntity, WorldRowId> {
    fun findByWorldIdAndGeneralIdAndResolvedYearIsNullOrderByIdAsc(worldId: Int, generalId: Int): List<BattlePlanReadEntity>
}

interface BattleReplayReadRawRepository : SpringDataRepository<BattleReplayReadEntity, WorldRowId> {
    fun findTop50ByWorldIdAndAttackerNationIdOrderByIdDesc(worldId: Int, nationId: Int): List<BattleReplayReadEntity>
    fun findTop50ByWorldIdAndDefenderNationIdOrderByIdDesc(worldId: Int, nationId: Int): List<BattleReplayReadEntity>
    fun findTop50ByWorldIdAndAttackerGeneralIdOrderByIdDesc(worldId: Int, generalId: Int): List<BattleReplayReadEntity>
    fun findByWorldIdAndId(worldId: Int, id: Int): BattleReplayReadEntity?
}

@Repository
class BattlePlanReadRepository(
    private val plans: BattlePlanReadRawRepository,
    private val replays: BattleReplayReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    /** 내 미소비 계획(초안·봉인). 소비된 행은 목록에 없다(spec v4.1 §6). */
    fun openPlansOf(generalId: Int): List<BattlePlanReadEntity> = plans.findByWorldIdAndGeneralIdAndResolvedYearIsNullOrderByIdAsc(worldId.value, generalId)

    /** 내 국가가 공격자 **또는** 수비자인 리플레이(최신순, id 중복 제거). */
    fun replaysOfNation(nationId: Int): List<BattleReplayReadEntity> =
        (replays.findTop50ByWorldIdAndAttackerNationIdOrderByIdDesc(worldId.value, nationId) + replays.findTop50ByWorldIdAndDefenderNationIdOrderByIdDesc(worldId.value, nationId))
            .distinctBy { it.id }.sortedByDescending { it.id }

    fun replaysOfGeneral(generalId: Int): List<BattleReplayReadEntity> = replays.findTop50ByWorldIdAndAttackerGeneralIdOrderByIdDesc(worldId.value, generalId)

    fun findReplay(id: Int): BattleReplayReadEntity? = replays.findByWorldIdAndId(worldId.value, id)
}
