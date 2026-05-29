package opensamguk.common.rng

import kotlin.math.floor

fun serializeSeed(vararg values: Any): String =
    values.joinToString("|") { v ->
        when (v) {
            is String -> "str(${v.length},$v)"
            is Int    -> "int($v)"
            is Long   -> "int($v)"
            is Double -> "int(${floor(v).toLong()})"
            is Float  -> "int(${floor(v.toDouble()).toLong()})"
            else      -> throw IllegalArgumentException("Unsupported seed value: $v")
        }
    }

data class TournamentRngContext(
    val openYear: Int, val openMonth: Int, val stage: Int, val phase: Int,
    val matchIndex: Int, val participantIndex: Int,
    val gameIndex: Int? = null, val extraSeed: Any? = null,
)

fun buildTournamentSeedKey(baseSeed: String, ctx: TournamentRngContext): String {
    val gameIndex = if (ctx.gameIndex != null) "|game:${ctx.gameIndex}" else ""
    val extraSeed = if (ctx.extraSeed != null) "|extra:${ctx.extraSeed}" else ""
    return listOf(
        "Tournament", "open:${ctx.openYear}-${ctx.openMonth}", "stage:${ctx.stage}", "phase:${ctx.phase}",
        "match:${ctx.matchIndex}", "participant:${ctx.participantIndex}", gameIndex, extraSeed, "seed:$baseSeed",
    ).filter { it.isNotEmpty() }.joinToString("|")
}
