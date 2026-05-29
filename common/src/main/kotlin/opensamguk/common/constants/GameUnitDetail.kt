package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/GameUnitDetail.php` (18-field positional tuple).
 *
 * attackCoef/defenceCoef = Map<Int, Double> mixing armType ints 0-6 AND specific unit-ids like
 * 1106/1100; lookup resolves specific-id before armType (see [resolveAttackCoef]/[resolveDefenceCoef]).
 * `info` is the final resolved list: base info[] with each constraint's getInfo() appended (per
 * GameUnitConstBase::_generate() lines 445-448).
 */
data class GameUnitDetail(
    val id: Int,
    val armType: Int,
    val name: String,
    val attack: Int,
    val defence: Int,
    val speed: Int,
    val avoid: Int,
    val magicCoef: Double,
    val cost: Int,
    val rice: Int,
    val reqConstraints: List<UnitConstraint>,
    val attackCoef: Map<Int, Double>,
    val defenceCoef: Map<Int, Double>,
    val info: List<String>,
    val initSkillTrigger: List<String>?,
    val phaseSkillTrigger: List<String>?,
    val iActionList: List<String>?,
) {
    /** Resolve attack coefficient against a defender: specific unit-id wins over armType. */
    fun resolveAttackCoef(defenderId: Int, defenderArmType: Int): Double =
        attackCoef[defenderId] ?: attackCoef[defenderArmType] ?: 1.0

    /** Resolve defence coefficient against an attacker: specific unit-id wins over armType. */
    fun resolveDefenceCoef(attackerId: Int, attackerArmType: Int): Double =
        defenceCoef[attackerId] ?: defenceCoef[attackerArmType] ?: 1.0
}
