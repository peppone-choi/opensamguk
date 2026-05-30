package opensamguk.logic.domestic

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.domain.General
import opensamguk.logic.domain.dedlevel
import opensamguk.logic.domain.explevel
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat

/**
 * Level-change mechanics — a faithful port of `General.php`:
 *   - checkStatChange (753-785) : per-turn FINALIZE step (NOT per-command) — trades each stat's
 *     `{stat}_exp` accumulator for ±1 stat point + a PLAIN log when it crosses the upgradeLimit.
 *   - addExperience  (448-469)  : fold the increment through `onCalcStat('experience')` (the full
 *     getActionList stack, affectTrigger default TRUE — e.g. a personality *1.1) BEFORE increaseVar,
 *     then recompute the exp-level and emit a PLAIN 레벨업/레벨다운 on a non-zero `<=>` change.
 *   - addDedication  (471-495)  : the same shape over `onCalcStat('dedication')`, emitting 승급/강등.
 *
 * `General` is immutable, so each helper returns the updated snapshot + the PLAIN log(s) the caller
 * routes through [GeneralActionResolveContext.addPlainLog]. PHP `increaseVar(var, delta)` is raw
 * `current + delta` with NO per-add rounding (LazyVarUpdater.php:68) — `experience`/`dedication` stay
 * Double in-memory and are truncated to int only at the flush row mapper.
 */

/** Result of [checkStatChange]: the updated general + the PLAIN level-change logs (in 통솔/무력/지력 order). */
data class StatChangeResult(val general: General, val plainLogs: List<String>)

/** Result of [addExperience]/[addDedication]: the updated general + an optional PLAIN level log (null = unchanged). */
data class LevelChangeResult(val general: General, val plainLog: String?)

/** The (statNick, statName, statExpKey) rows checkStatChange iterates, in PHP order. */
private val STAT_CHANGE_TABLE: List<Triple<String, String, String>> = listOf(
    Triple("통솔", "leadership", "leadership_exp"),
    Triple("무력", "strength", "strength_exp"),
    Triple("지력", "intel", "intel_exp"),
)

/**
 * General.php:753-785 — per-turn finalize. For each of 통솔/무력/지력:
 *   - `{stat}_exp < 0`        : PLAIN `<R>{nick}</>이 <C>1</> 떨어졌습니다!`; exp += upgradeLimit; stat -= 1.
 *   - else if `>= upgradeLimit`: if stat < maxLevel → PLAIN `<S>{nick}</>이 <C>1</> 올랐습니다!` + stat += 1;
 *                               THEN exp -= upgradeLimit UNCONDITIONALLY (even when capped at maxLevel).
 * The down branch is tested FIRST; the stat decrement is unconditional (no floor in PHP).
 */
fun checkStatChange(general: General): StatChangeResult {
    val limit = UPGRADE_LIMIT
    var g = general
    val logs = mutableListOf<String>()

    for ((nick, statName, expKey) in STAT_CHANGE_TABLE) {
        val exp = metaDouble(g.meta, expKey)
        if (exp < 0.0) {
            logs.add("<R>$nick</>이 <C>1</> 떨어졌습니다!")
            g = g.copy(meta = withMeta(g.meta, expKey to exp + limit))
            g = setStat(g, statName, rawStat(g, statName) - 1)
        } else if (exp >= limit) {
            if (rawStat(g, statName) < MAX_LEVEL) {
                logs.add("<S>$nick</>이 <C>1</> 올랐습니다!")
                g = setStat(g, statName, rawStat(g, statName) + 1)
            }
            g = g.copy(meta = withMeta(g.meta, expKey to exp - limit))
        }
    }
    return StatChangeResult(g, logs)
}

private fun rawStat(g: General, statName: String): Int = when (statName) {
    "leadership" -> g.leadership
    "strength" -> g.strength
    "intel" -> g.intel
    else -> error("unknown stat $statName")
}

private fun setStat(g: General, statName: String, value: Int): General = when (statName) {
    "leadership" -> g.copy(leadership = value)
    "strength" -> g.copy(strength = value)
    "intel" -> g.copy(intel = value)
    else -> error("unknown stat $statName")
}

/**
 * General.php:448-469 — fold THROUGH `onCalcStat('experience')` (affectTrigger default TRUE) BEFORE the
 * raw increaseVar, recompute `getExpLevel(experience)`, and on a non-zero `<=>` vs the stored explevel
 * emit the PLAIN level log (with josa-로 on the level number string).
 */
fun addExperience(
    general: General,
    experience: Double,
    pipeline: GeneralActionPipeline,
    affectTrigger: Boolean = true,
): LevelChangeResult {
    val delta = if (affectTrigger) pipeline.onCalcStat(general, "experience", experience) else experience
    val nextExperience = general.experience + delta                  // increaseVar (raw)
    val nextLevel = getExpLevel(nextExperience)
    val comp = nextLevel.compareTo(general.explevel())               // PHP <=>

    if (comp == 0) {
        return LevelChangeResult(general.copy(experience = nextExperience), null)
    }

    val g = general.copy(experience = nextExperience, meta = withMeta(general.meta, "explevel" to nextLevel))
    val josaRo = JosaUtil.pick(nextLevel.toString(), "로")
    val log = if (comp > 0) {
        "<C>Lv $nextLevel</>$josaRo <C>레벨업</>!"
    } else {
        "<C>Lv $nextLevel</>$josaRo <R>레벨다운</>!"
    }
    return LevelChangeResult(g, log)
}

/**
 * General.php:471-495 — fold THROUGH `onCalcStat('dedication')` BEFORE increaseVar, recompute
 * `getDedLevel(dedication)`, and on a non-zero change emit the PLAIN 승급/강등 log. josa-로 is computed
 * on BOTH `dedLevelText` AND `number_format(getBillByLevel(level))`.
 */
fun addDedication(
    general: General,
    dedication: Double,
    pipeline: GeneralActionPipeline,
    affectTrigger: Boolean = true,
): LevelChangeResult {
    val delta = if (affectTrigger) pipeline.onCalcStat(general, "dedication", dedication) else dedication
    val nextDedication = general.dedication + delta                  // increaseVar (raw)
    val nextLevel = getDedLevel(nextDedication)
    val comp = nextLevel.compareTo(general.dedlevel())               // PHP <=>

    if (comp == 0) {
        return LevelChangeResult(general.copy(dedication = nextDedication), null)
    }

    val g = general.copy(dedication = nextDedication, meta = withMeta(general.meta, "dedlevel" to nextLevel))
    val dedLevelText = getDedLevelText(nextLevel)
    val billText = numberFormat(getBillByLevel(nextLevel))
    val josaRoDed = JosaUtil.pick(dedLevelText, "로")
    val josaRoBill = JosaUtil.pick(billText, "로")
    val log = if (comp > 0) {
        "<Y>$dedLevelText</>$josaRoDed <C>승급</>하여 봉록이 <C>$billText</>$josaRoBill <C>상승</>했습니다!"
    } else {
        "<Y>$dedLevelText</>$josaRoDed <R>강등</>되어 봉록이 <C>$billText</>$josaRoBill <R>하락</>했습니다!"
    }
    return LevelChangeResult(g, log)
}
