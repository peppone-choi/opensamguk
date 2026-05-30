package opensamguk.logic.domain

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.valueFit

/**
 * The 은퇴 rebirth subsystem — faithful port of `General::rebirth()` (`General.php:602-639`).
 *
 * The succession bookkeeping at the top of PHP `rebirth()` (owner inherit-point merge via
 * InheritancePointManager, and the RankColumn-cases reset) is the succession subsystem (OQ7 / P6 gap).
 * In P2 it is a GUARDED STUB: the rank-var reset / inherit merge is NOT modeled (the rank vars live in a
 * separate rank_data table, not on the general row), so [rebirth] performs ONLY the on-general stat math
 * + the action-stream logs. `che_은퇴` itself never bumps the inheritance point (the plan's "no
 * inheritance bump"). CheckHall is likewise stubbed by the caller.
 *
 * `age`/`specage`/`specage2`/`dex1..5` are NOT modeled as `General` fields here; they ride the `meta`
 * jsonb (GeneralBase var allow-list). The stat fields (leadership/strength/intel) are Int, so the PHP
 * `multiplyVarWithLimit(stat, 0.85, 10)` (float, lower-clamp 10) is applied as
 * `valueFit(stat*0.85, min=10).toInt()` — clamp on the float, then the int-column truncation toward zero
 * the D1 flush would apply. experience/dedication stay Double (raw accumulators).
 */

/** The action-stream logs rebirth emits: a PLAIN general-action line + a global-action line. */
data class RebirthLogs(
    val plainLogs: List<String>,
    val globalActionLogs: List<String>,
)

/**
 * Apply `General::rebirth()`'s stat math + logs. Returns the reborn general + the logs the caller routes
 * (`pushGeneralActionLog(..., PLAIN)` → addPlainLog; `pushGlobalActionLog(...)` → addGlobalActionLog; the
 * general-history line is a GATE-RUNTIME seam, not modeled).
 *
 * @param affectTrigger reserved for parity with the stat folds; rebirth does NOT fold through the pipeline
 *   (the multiplies are raw), so [pipeline] is accepted only to keep the helper's signature uniform.
 */
fun rebirth(general: General, @Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): Pair<General, RebirthLogs> {
    val generalName = (general.meta["name"] as? String) ?: ""

    // stat math: leadership/strength/intel *= 0.85 (lower-clamp 10); injury 0; exp/ded *= 0.5; age 20;
    // specage/specage2 0; dex1..5 *= 0.5. (RankColumn-cases → 0 is the succession seam — not modeled.)
    val nextLeadership = valueFit(general.leadership * 0.85, 10.0).toInt()
    val nextStrength = valueFit(general.strength * 0.85, 10.0).toInt()
    val nextIntel = valueFit(general.intel * 0.85, 10.0).toInt()

    var meta = withMeta(
        general.meta,
        "age" to 20,
        "specage" to 0,
        "specage2" to 0,
    )
    // dex1..5 *= 0.5 (absent → 0). Keep them as Double accumulators on meta (truncated at flush).
    for (i in 1..5) {
        val key = "dex$i"
        meta = withMeta(meta, key to metaDouble(meta, key) * 0.5)
    }

    val g = general.copy(
        leadership = nextLeadership,
        strength = nextStrength,
        intel = nextIntel,
        injury = 0,
        experience = general.experience * 0.5,
        dedication = general.dedication * 0.5,
        meta = meta,
    )

    val josaYi = JosaUtil.pick(generalName, "이")
    val logs = RebirthLogs(
        plainLogs = listOf("나이가 들어 <R>은퇴</>하고 자손에게 자리를 물려줍니다."),
        globalActionLogs = listOf("<Y>$generalName</>$josaYi <R>은퇴</>하고 그 자손이 유지를 이어받았습니다."),
    )
    return g to logs
}
