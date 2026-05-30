package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/능력치변경.php:10-59`. Priority BEGIN+10. NO draws.
 * General-only (`assert $self instanceof WarUnitGeneral`). Applies a var op by [operator]
 * (`=`/`+`/`-`/`*`/`/` — `/` becomes `*` with `1/value`) via the impl's [WarUnit.applyVarChange]
 * delegate, then `processConsumableItem()`. The operator is validated at construction (PHP ctor).
 */
class NeungryeokchiByeongyeong(
    unit: WarUnit,
    raiseType: Int,
    private val variable: String,
    private val operator: String,
    private val value: Double,
    private val limitMin: Double? = null,
    private val limitMax: Double? = null,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 10

    init {
        require(operator in setOf("=", "+", "-", "*", "/")) { "올바르지 않은 operator : $operator" }
    }

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        // PHP `/` rewrites to multiply by 1/value (능력치변경.php:49-51).
        if (operator == "/") {
            self.applyVarChange(variable, "*", 1.0 / value, limitMin, limitMax)
        } else {
            self.applyVarChange(variable, operator, value, limitMin, limitMax)
        }
        processConsumableItem()
        return true
    }
}
