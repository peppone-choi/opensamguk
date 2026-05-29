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

data class TurnGeneral(
    val id: Int,
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
)

data class City(
    val id: Int,
    val name: String,
    val nationId: Int,
    val level: Int,
    val state: Int = 0,
    val population: Int = 0,
    val populationMax: Int = 0,
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
    val level: Int = 0,
    val typeCode: String = "None",
    val meta: Map<String, Any?> = emptyMap(),
)

data class Troop(
    val id: Int,
    val nationId: Int,
    val name: String,
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
)

data class TurnWorldState(
    val id: Int,
    val currentYear: Int,
    val currentMonth: Int,
    val tickSeconds: Int,
    val lastTurnTime: Instant,
    val meta: Map<String, Any?> = emptyMap(),
)

fun buildDiplomacyKey(srcNationId: Int, destNationId: Int): String = "$srcNationId:$destNationId"
