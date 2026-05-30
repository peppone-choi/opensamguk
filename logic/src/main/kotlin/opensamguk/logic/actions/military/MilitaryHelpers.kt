package opensamguk.logic.actions.military

import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * Shared military-command helpers.
 *
 * addDex — General.php:420-446. The dex experience accumulator the recruit/train/morale commands feed:
 *   - armType CASTLE (0) folds to SIEGE (5);  armType < 0 → no-op.
 *   - WIZARD (4) / SIEGE (5) crew apply a 0.9 multiplier.
 *   - affectTrainAtmos scaling (`*= (train+atmos)/200`) is FALSE for every military command here, so omitted.
 *   - the value is folded through onCalcStat('addDex', …, ['armType'=>…]) (identity under no modules),
 *     then `increaseVar(dex{armType}, exp)` — the dex var rides `meta['dex{armType}']` (raw float accumulator).
 *
 * The crewType armType is resolved from [UnitSetTable]; an unknown crewType id is treated as no-op
 * (PHP getCrewTypeObj always resolves a valid unit, but a defensive guard keeps the resolver total).
 */
fun addDexForCrewType(
    pipeline: GeneralActionPipeline,
    general: General,
    crewTypeId: Int,
    exp0: Double,
): General {
    val unit = UnitSetTable.byId(crewTypeId) ?: return general
    return addDexForUnit(pipeline, general, unit, exp0)
}

fun addDexForUnit(
    pipeline: GeneralActionPipeline,
    general: General,
    crewType: UnitSetTable.UnitDetail,
    exp0: Double,
): General {
    var armType = crewType.armType
    if (armType == UnitSetTable.T_CASTLE) armType = UnitSetTable.T_SIEGE
    if (armType < 0) return general
    var exp = exp0
    if (armType == UnitSetTable.T_WIZARD || armType == UnitSetTable.T_SIEGE) exp *= 0.9
    exp = pipeline.onCalcStat(general, "addDex", exp, mapOf("armType" to armType))
    val dexKey = "dex$armType"
    return general.copy(meta = withMeta(general.meta, dexKey to metaDouble(general.meta, dexKey) + exp))
}
