package opensamguk.engine.turn

import java.time.Instant

/**
 * Minimal turn-world domain skeleton (P0-B). Mirrors the TS domain entities
 * (`packages/logic/src/domain/entities.ts`) and turn-world types
 * (`app/game-engine/src/turn/types.ts`). The full ~70-column General is P1; here
 * only the columns the flush/dirty-state machinery touches are modelled. jsonb
 * columns are represented as `meta: Map<String, Any?>` bags (mirrors TS
 * `Record<string, unknown>`).
 */

data class GeneralStats(
    val leadership: Int,
    val strength: Int,
    val intelligence: Int,
    val politics: Int = 50,
    val charm: Int = 50,
)

data class GeneralItems(
    val horse: String? = null,
    val weapon: String? = null,
    val book: String? = null,
    val item: String? = null,
)

data class GeneralRole(
    val personality: String? = null,
    val specialDomestic: String? = null,
    val specialWar: String? = null,
    val items: GeneralItems = GeneralItems(),
)

data class GeneralAccessLog(
    val generalId: Int,
    val userId: Long? = null,
    val lastRefresh: Instant? = null,
    val refresh: Int = 0,
    val refreshTotal: Int = 0,
    val refreshScore: Int = 0,
    val refreshScoreTotal: Int = 0,
)

data class GeneralTurnSeed(
    val actionCode: String,
    val argJson: String = "{}",
    val brief: String = actionCode,
)

data class TurnGeneral(
    val id: Int,
    val userId: String? = null,
    val name: String,
    val nationId: Int,
    val cityId: Int,
    val troopId: Int,
    val stats: GeneralStats,
    val experience: Int,
    val dedication: Int,
    val officerLevel: Int,
    val role: GeneralRole = GeneralRole(),
    val injury: Int = 0,
    val gold: Int = 0,
    val rice: Int = 0,
    val crew: Int = 0,
    val crewTypeId: Int = 0,
    val train: Int = 0,
    val atmos: Int = 0,
    val age: Int = 0,
    val npcState: Int = 0,
    val turnTime: Instant,
    val recentWarTime: Instant? = null,
    val meta: Map<String, Any?> = emptyMap(),
    val initialTurns: List<GeneralTurnSeed> = emptyList(),
)

data class City(
    val id: Int,
    val name: String,
    val nationId: Int,
    val level: Int,
    val state: Int = 0,
    val population: Int = 0,
    val populationMax: Int = 0,
    val dead: Int = 0,
    val agriculture: Int = 0,
    val agricultureMax: Int = 0,
    val commerce: Int = 0,
    val commerceMax: Int = 0,
    val security: Int = 0,
    val securityMax: Int = 0,
    val supplyState: Int = 0,
    val frontState: Int = 0,
    val defence: Int = 0,
    val defenceMax: Int = 0,
    val wall: Int = 0,
    val wallMax: Int = 0,
    val trade: Int? = 100,   // V1 city.trade — NOT NULL DEFAULT 100 (95..105; null = disabled)
    val region: Int = 0,     // V1 city.region
    val term: Int = 0,
    val officerSet: Int = 0,
    val conflict: String = "{}",
    val meta: Map<String, Any?> = emptyMap(),
)

data class Nation(
    val id: Int,
    val name: String,
    val color: String,
    val capitalCityId: Int? = null,
    val chiefGeneralId: Int? = null,
    val gold: Int = 0,
    val rice: Int = 0,
    val power: Int = 0,
    // tech(기술력)는 nation.tech 전용 컬럼이다. 엔진 인메모리 Nation에 필드를 두지 않으면
    // 스냅샷 로더가 tech를 적재할 자리가 없어 toLogicNation이 tech=0.0을 만들고, 월틱 flush가
    // UPDATE nation SET tech=0 으로 시드값(예: 후한 1500)을 영구히 0으로 덮어쓴다. power와 동일하게
    // 전용 필드로 round-trip 시킨다.
    val tech: Double = 0.0,
    val level: Int = 0,
    val typeCode: String = "None",
    val meta: Map<String, Any?> = emptyMap(),
)

data class Troop(
    val id: Int,
    val nationId: Int,
    val name: String,
)

/** Phase 4X-A 가신(휘하 인물) — 세계 상태(troop 미러). id 는 엔진 할당. spec v3 §3. */
data class Retainer(
    val id: Int,
    val masterGeneralId: Int,
    val origin: String,
    val generalId: Int? = null,
    val name: String,
    val relation: String,
    val role: String = "NONE",
    val hasOwnBugok: Boolean = false,
    val releasePolicy: String = "MASTER_ONLY",
    val loyalty: Int = 50,
    val task: String = "none",
)

/** Phase 4X-A 부곡(장수 개인 사병) — 세계 상태. 병력은 편성 시 general.crew 에서 옮겨 온다(합 보존). */
data class Bugok(
    val id: Int,
    val masterGeneralId: Int,
    val name: String,
    val troops: Int,
    val crewTypeId: Int,
    val training: Int,
    val morale: Int,
    val fatigue: Int = 0,
    val provisions: Int = 0,
    val commanderRetainerId: Int? = null,
)

data class TurnDiplomacy(
    val fromNationId: Int,
    val toNationId: Int,
    val state: Int,
    val term: Int,
    val dead: Int = 0,
    val meta: Map<String, Any?> = emptyMap(),
)

/**
 * Engine-local log draft (inert in P0-B; finalize/convert is wired in a later phase).
 * Mirrors `LogEntryDraft` (`packages/logic/src/logging/types.ts`).
 */
data class LogEntryDraft(
    val scope: String,
    val category: String,
    val text: String,
    val generalId: Int? = null,
    val nationId: Int? = null,
    val userId: Int? = null,
    val subType: String? = null,
    val meta: Map<String, Any?>? = null,
    val format: Int? = null,
    val year: Int? = null,
    val month: Int? = null,
    val phase: Int? = null,
)

data class TurnWorldState(
    val id: Int,
    val currentYear: Int,
    val currentMonth: Int,
    val tickSeconds: Int,
    val lastTurnTime: Instant,
    val currentPhase: Int = 1,
    val meta: Map<String, Any?> = emptyMap(),
    val status: String = "OPEN",
    val config: Map<String, Any?> = emptyMap(),
    val serverId: String? = null,
    /** OPENSAM-131: committed world_version observed at load / last successful flush. */
    val worldVersion: Long = 0L,
    /** OPENSAM-131: active writer fence epoch observed at load. */
    val writerEpoch: Long = 0L,
)

fun buildDiplomacyKey(srcNationId: Int, destNationId: Int): String = "$srcNationId:$destNationId"

/**
 * The 37 `rank_data.type` columns — a faithful transcription of `sammo\Enums\RankColumn`
 * (`legacy/devsam-core/hwe/sammo/Enums/RankColumn.php`, `RankColumn::cases()` = 37). The enum
 * case backing VALUE is the rank_data `type` column name ([column]); the last 6 PHP case NAMES
 * differ from their backing values (e.g. `inherit_point_earned = 'inherit_earned'`) — the column
 * always uses the backing VALUE. Order matches the PHP enum declaration order (the `entries`
 * order the flush iterates and `RANK_ROWS_PER_GENERAL` counts).
 */
enum class RankColumn(val column: String) {
    FIRENUM("firenum"),
    WARNUM("warnum"),
    KILLNUM("killnum"),
    DEATHNUM("deathnum"),
    KILLCREW("killcrew"),
    DEATHCREW("deathcrew"),
    TTW("ttw"),
    TTD("ttd"),
    TTL("ttl"),
    TTG("ttg"),
    TTP("ttp"),
    TLW("tlw"),
    TLD("tld"),
    TLL("tll"),
    TLG("tlg"),
    TLP("tlp"),
    TSW("tsw"),
    TSD("tsd"),
    TSL("tsl"),
    TSG("tsg"),
    TSP("tsp"),
    TIW("tiw"),
    TID("tid"),
    TIL("til"),
    TIG("tig"),
    TIP("tip"),
    BETWIN("betwin"),
    BETGOLD("betgold"),
    BETWINGOLD("betwingold"),
    KILLCREW_PERSON("killcrew_person"),
    DEATHCREW_PERSON("deathcrew_person"),
    OCCUPIED("occupied"),
    INHERIT_EARNED("inherit_earned"),
    INHERIT_SPENT("inherit_spent"),
    INHERIT_EARNED_DYN("inherit_earned_dyn"),
    INHERIT_EARNED_ACT("inherit_earned_act"),
    INHERIT_SPENT_DYN("inherit_spent_dyn"),
    ;

    companion object {
        /** Resolve a rank_data `type` column name to its [RankColumn] case. */
        fun byColumn(column: String): RankColumn? = entries.firstOrNull { it.column == column }
    }
}

/**
 * One rank_data write for a single `(general, type)`: either an `Increment` (`value = value + n`,
 * `rankVarIncrease`) or a `Set` (`value = n`, `rankVarSet`). The 3-Map collapse in [ChangeRecorder]
 * keeps at most ONE of these per `(general, type)` — faithful to `General.php:641-670`.
 */
sealed interface RankDelta {
    data class Increment(val value: Int) : RankDelta
    data class Set(val value: Int) : RankDelta
}
