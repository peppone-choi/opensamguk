package opensamguk.logic.war.trigger

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.RandUtil

/**
 * Port target = PHP `sammo/BaseWarUnitTrigger.php:7-104`.
 *
 * The base class every concrete war trigger (필살/회피/계략/… 시도·발동·실패) extends. Holds a [WarUnit] and:
 *   - OVERRIDES [getUniqueID] to append `_{raiseType}` so an ITEM-injected trigger and a TYPE_NONE specialty
 *     trigger of the same class on the same unit COEXIST (do NOT dedup) — decision #4;
 *   - splits the threaded env into per-side `e_attacker`/`e_defender` sub-maps by [WarUnit.isAttacker],
 *     hands `actionWar` the self/oppose env slices (BY REFERENCE — the sub-maps are the live LinkedHashMaps),
 *     then re-stitches and sets `stopNextAction` if `actionWar` returned `false`;
 *   - [processConsumableItem] consumes a held item as a flush-DELTA (NEVER inline DB) gated on raiseType.
 */
abstract class BaseWarUnitTrigger(
    protected val unit: WarUnit,
    protected val raiseType: Int = TYPE_NONE,
) : ObjectTrigger() {

    companion object {
        const val TYPE_NONE: Int = 0
        const val TYPE_ITEM: Int = 1
        const val TYPE_CONSUMABLE_ITEM: Int = 1 or 2   // = 3
        const val TYPE_DEDUP_TYPE_BASE: Int = 1024
    }

    /** PHP `spl_object_id($this->object)` substitute — the stable per-unit id. */
    override val objectId: String get() = unit.getUnitId()

    /**
     * PHP `getUniqueID()` (:19-29): `"{priority}_{FQN}_{objID}_{raiseType}"`. The trailing `_{raiseType}`
     * is the coexistence key (decision #4) — TYPE_ITEM(+DEDUP*nnn) and TYPE_NONE do NOT collapse.
     */
    override fun getUniqueID(): String {
        val fqn = this::class.qualifiedName
        return "${priority}_${fqn}_${objectId}_${raiseType}"
    }

    /**
     * PHP `action()` (:36-72). Ensures e_attacker/e_defender exist, short-circuits on stopNextAction,
     * splits self/oppose env, calls [actionWar] (mutating the live sub-maps), re-stitches, propagates stop.
     */
    final override fun action(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv {
        @Suppress("UNCHECKED_CAST")
        val eAttacker = env.getOrPut("e_attacker") { TriggerEnv() } as TriggerEnv
        @Suppress("UNCHECKED_CAST")
        val eDefender = env.getOrPut("e_defender") { TriggerEnv() } as TriggerEnv

        if (env["stopNextAction"] == true) {
            return env
        }

        @Suppress("UNCHECKED_CAST")
        val pair = arg as List<WarUnit>
        // [attacker, defender] = arg — defender is the active opponent of THIS battle resolution.
        val attacker = pair[0]
        val defender = pair[1]

        val self = unit
        val isAttacker = self.isAttacker()
        val oppose = if (isAttacker) defender else attacker

        // selfEnv/opposeEnv are the LIVE sub-maps (PHP passes by reference); actionWar mutates them directly.
        val selfEnv = if (isAttacker) eAttacker else eDefender
        val opposeEnv = if (isAttacker) eDefender else eAttacker

        val callNextAction = actionWar(self, oppose, selfEnv, opposeEnv)

        // Re-stitch (PHP :64-65). The maps are the same references; this just re-asserts the slot mapping.
        env["e_attacker"] = if (isAttacker) selfEnv else opposeEnv
        env["e_defender"] = if (isAttacker) opposeEnv else selfEnv

        if (!callNextAction) {
            env["stopNextAction"] = true
        }
        return env
    }

    /**
     * The concrete trigger body. PHP `abstract protected function actionWar(WarUnit, WarUnit, array&, array&)`.
     * Returns `false` to set `stopNextAction` (halt subsequent triggers' draws). `selfEnv`/`opposeEnv` are
     * mutated in place.
     */
    protected abstract fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean

    /**
     * PHP `processConsumableItem()` (:75-104). Item-side consumption gate:
     *   - not an ITEM raiseType ⇒ false (no effect);
     *   - already used '아이템사용' ⇒ false;
     *   - else activate '아이템사용' + the item-name skill;
     *   - if not exactly TYPE_CONSUMABLE_ITEM ⇒ false (plain item, no consume);
     *   - already consumed '아이템소모' ⇒ false;
     *   - else activate '아이템소모', push the `<C>{name}</>{josa} 사용!` PLAIN log, and [WarUnit.deleteItem]
     *     (a flush-DELTA, NOT inline DB), returning true.
     */
    fun processConsumableItem(): Boolean {
        if ((raiseType and TYPE_ITEM) == 0) {
            return false
        }
        val self = unit
        if (self.hasActivatedSkill("아이템사용")) {
            return false
        }
        self.activateSkill("아이템사용")
        val itemName = self.getItemName()
        val itemRawName = self.getItemRawName()
        self.activateSkill(itemName)

        if (raiseType != TYPE_CONSUMABLE_ITEM) {
            return false
        }
        if (self.hasActivatedSkill("아이템소모")) {
            return false
        }
        self.activateSkill("아이템소모")
        val josaUl = JosaUtil.pick(itemRawName, "을")
        self.pushPlainActionLog("<C>${itemName}</>${josaUl} 사용!")
        self.deleteItem()
        return true
    }
}
