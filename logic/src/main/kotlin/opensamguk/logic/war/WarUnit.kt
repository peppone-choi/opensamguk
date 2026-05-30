package opensamguk.logic.war

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.constants.getDexLog
import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import opensamguk.logic.war.trigger.WarUnit as WarUnitContract

/**
 * F2 base — port target = PHP `sammo/WarUnit.php:5-453`.
 *
 * The abstract battle-unit base every leaf (general / city) extends. Owns the per-battle MUTABLE state
 * (warPower / warPowerMultiply / killed / dead / phase counters / activated-skill maps / oppose) and the
 * draw-bearing math: [computeWarPower] (the `<100`-floor CONDITIONAL draw — the #1 desync trap),
 * [calcDamage] (`getWarPower()*nextRange(0.9,1.1)` then half-away `Util::round`), [criticalDamage]
 * (`nextRange(criticalDamageRange)`), [beginPhase] (`clearActivatedSkill + computeWarPower`).
 *
 * **Implements the F1 [WarUnitContract]** (the trigger-facing seam) so the F1 registry's `fire()` draw order
 * runs against the concrete unit. The COMPUTED-INPUT surface (getComputedAttack/Defence/Train/Atmos, getDex,
 * crewType, the onCalcStat folds, the magic probs) is abstract here and filled by [WarUnitGeneral] /
 * [WarUnitCity] — the base never reads a General/City directly, so it stays draw-order-pure.
 *
 * **The ONE shared `RandUtil(LiteHashDrbg(warSeed))` is threaded by reference** (built once in the OUTER
 * `processWar()`, BO1) — the base NEVER re-seeds. ATTACKER-BEFORE-DEFENDER ordering is enforced by the F3
 * phase loop, not here; this base only owns the per-unit draw SITES.
 *
 * Backing fields carry a `Field` suffix to dodge the Kotlin property-vs-PHP-getter JVM signature clash
 * (the PHP API names — getWarPower/getKilled/setWarPowerMultiply — are the public surface F3/A4/B1 read).
 */
abstract class WarUnit(
    final override val rng: RandUtil,
) : WarUnitContract {

    // --- per-battle mutable state (PHP WarUnit.php:13-33) ---
    protected var killedCurrField: Int = 0
    protected var killedField: Int = 0
    protected var deadCurrField: Int = 0
    protected var deadField: Int = 0

    protected var isAttackerFlag: Boolean = false

    protected var currPhase: Int = 0
    protected var prePhaseField: Int = 0
    protected var bonusPhase: Int = 0

    protected var atmosBonus: Int = 0
    protected var trainBonus: Int = 0

    private var opposeUnit: WarUnit? = null
    protected var warPowerField: Double = 0.0
    protected var warPowerMultiplyField: Double = 1.0

    /** PHP `$activatedSkill[name] = true/false`. Mutated by [activateSkill]/[deactivateSkill]/[setOppose]. */
    private val activatedSkill: MutableMap<String, Boolean> = LinkedHashMap()

    /** PHP `$logActivatedSkill[name] = count` — accrued by [clearActivatedSkill]. */
    private val logActivatedSkill: MutableMap<String, Int> = LinkedHashMap()

    protected var isFinished: Boolean = false

    // --- abstract computed-input surface (the General/City subclass fills) ------------------------------

    /** PHP `getCrewType()` — the [GameUnitDetail] (speed/avoid/magicCoef/coef tables). Contract method. */
    abstract override fun getCrewType(): GameUnitDetail

    /** PHP `getComputedAttack()` — crewType.getComputedAttack(resolved-stats, tech). */
    abstract fun getComputedAttack(): Double

    /** PHP `getComputedDefence()` — crewType.getComputedDefence(crew, tech). */
    abstract fun getComputedDefence(): Double

    /** PHP `getComputedTrain()` — train + onCalcStat('bonusTrain') cross-fold + trainBonus (general); cta+trainBonus (city). Contract method. */
    abstract override fun getComputedTrain(): Double

    /** PHP `getComputedAtmos()` — atmos + onCalcStat('bonusAtmos') cross-fold + atmosBonus (general); cta+atmosBonus (city). Contract method. */
    abstract override fun getComputedAtmos(): Double

    /** PHP `getDex(GameUnitDetail $crewType)` — the dex accumulator (general onCalcStat cross-fold; city (cta-60)*7200). */
    abstract fun getDex(crewType: GameUnitDetail): Double

    /** PHP `getHP()`. */
    abstract fun getHP(): Int

    /** PHP `getName()`. */
    abstract fun getName(): String

    // --- F1 trigger-facing contract ---------------------------------------------------------------------

    /** Stable per-unit id — the `spl_object_id` substitute (decision #4). Subclass supplies a stable token. */
    protected abstract fun unitIdToken(): String

    final override fun getUnitId(): String = unitIdToken()

    final override fun isAttacker(): Boolean = isAttackerFlag

    final override fun getPhase(): Int = currPhase

    fun getRealPhase(): Int = prePhaseField + currPhase

    // --- oppose / phase wiring (PHP :208-234) -----------------------------------------------------------

    open fun setOppose(oppose: WarUnit?) {
        this.opposeUnit = oppose
        this.killedCurrField = 0
        this.deadCurrField = 0
        clearActivatedSkill()
    }

    fun getOppose(): WarUnit? = opposeUnit

    fun setPrePhase(phase: Int) { prePhaseField = phase }
    final override fun addPhase(phase: Int) { currPhase += phase }
    fun addPhase() = addPhase(1)
    final override fun addBonusPhase(cnt: Int) { bonusPhase += cnt }

    override fun getMaxPhase(): Int = getCrewType().speed + bonusPhase

    // --- war power (PHP :236-306) -----------------------------------------------------------------------

    final override fun getWarPower(): Double = warPowerField * warPowerMultiplyField
    fun getRawWarPower(): Double = warPowerField
    fun getWarPowerMultiply(): Double = warPowerMultiplyField
    final override fun setWarPowerMultiply(multiply: Double) { warPowerMultiplyField = multiply }
    fun setWarPowerMultiply() = setWarPowerMultiply(1.0)
    final override fun multiplyWarPowerMultiply(multiply: Double) { warPowerMultiplyField *= multiply }

    fun addTrainBonus(value: Int) { trainBonus += value }
    fun addAtmosBonus(value: Int) { atmosBonus += value }

    /**
     * PHP `WarUnit::computeWarPower` (`:271-306`), EXACT sequence. The base computes the crew-vs-crew core;
     * [WarUnitGeneral] overrides to fold the explevel + per-source `getWarPowerMultiplier` ON TOP of this.
     *
     * THE conditional `<100` floor draw is the load-bearing parity site: `nextRangeInt((warPower+100)/2, 100)`
     * fires ONLY when raw `warPower<100`. The low bound is a FLOAT in PHP — coerced to Int by [RandUtil.nextRangeInt]
     * (truncate toward zero, matching PHP `(int)` cast on a non-negative value, since `(warPower+100)/2 >= 50`).
     *
     * Returns `[warPower, opposeWarPowerMultiply]` so the general override can continue folding.
     */
    open fun computeWarPower(): Pair<Double, Double> {
        val oppose = getOppose() ?: error("computeWarPower: oppose is null")

        val myAtt = getComputedAttack()
        val opDef = oppose.getComputedDefence()
        var warPower = GameConst.armperphase + myAtt - opDef
        var opposeWarPowerMultiply = 1.0

        if (warPower < 100) {
            // 최소 전투력 50 보장
            warPower = maxOf(0.0, warPower)
            warPower = (warPower + 100) / 2
            warPower = rng.nextRangeInt(warPower.toInt(), 100).toDouble()
        }

        warPower *= getComputedAtmos()
        warPower /= oppose.getComputedTrain()

        val genDexAtt = getDex(getCrewType())
        val oppDexDef = oppose.getDex(getCrewType())
        warPower *= getDexLog(genDexAtt.toInt(), oppDexDef.toInt())

        warPower *= getCrewType().getAttackCoef(oppose.getCrewType())
        opposeWarPowerMultiply *= getCrewType().getDefenceCoef(oppose.getCrewType())

        this.warPowerField = warPower
        oppose.setWarPowerMultiply(opposeWarPowerMultiply)
        return warPower to opposeWarPowerMultiply
    }

    /** PHP `beginPhase()` (`:366-370`) = clearActivatedSkill + computeWarPower. */
    open fun beginPhase() {
        clearActivatedSkill()
        computeWarPower()
    }

    /** PHP `calcDamage()` (`:413-418`): `Util::round(getWarPower() * nextRange(0.9,1.1))`. EXACTLY ONE draw. */
    open fun calcDamage(): Int {
        var wp = getWarPower()
        wp *= rng.nextRange(0.9, 1.1)
        return phpRound(wp)
    }

    /**
     * PHP `criticalDamage()` (`:437-447`): `nextRange(...range)`, range default `[1.3,2.0]`. For a
     * [WarUnitGeneral] the bounds may be REWRITTEN by `onCalcStat('criticalDamageRange')` (che_필살) — the
     * draw is fixed, the bounds are stat-derived. The base returns the default range; the general overrides
     * [criticalDamageRange].
     */
    final override fun criticalDamage(): Double {
        val (lo, hi) = criticalDamageRange()
        return rng.nextRange(lo, hi)
    }

    /** PHP `[1.3, 2.0]`; [WarUnitGeneral] overrides to apply the onCalcStat pair fold. */
    protected open fun criticalDamageRange(): Pair<Double, Double> = 1.3 to 2.0

    // --- HP / killed (deltas only; subclass applies to the backing General/City) ------------------------

    /** PHP `decreaseHP(int $damage)` — base accrues `dead`; subclass strict-order applies crew/wall. */
    abstract fun decreaseHP(damage: Int): Int

    /** PHP `increaseKilled(int $damage)` — base accrues `killed`; subclass applies rice/dex/exp. */
    abstract fun increaseKilled(damage: Int): Int

    fun getKilled(): Int = killedField
    fun getDead(): Int = deadField
    fun getKilledCurrentBattle(): Int = killedCurrField
    fun getDeadCurrentBattle(): Int = deadCurrField

    // --- battle continuation / wound (subclass) ---------------------------------------------------------

    /** PHP `continueWar(&$noRice)` — base never continues. Returns (canContinue, noRice). */
    open fun continueWar(): WarContinuation = WarContinuation(canContinue = false, noRice = false)

    /** PHP `tryWound()` — base never wounds. */
    open fun tryWound(): Boolean = false

    // --- skill state (PHP :102-394) ---------------------------------------------------------------------

    protected fun clearActivatedSkill() {
        for ((skillName, state) in activatedSkill) {
            if (!state) continue
            logActivatedSkill[skillName] = (logActivatedSkill[skillName] ?: 0) + 1
        }
        activatedSkill.clear()
    }

    fun getActivatedSkillLog(): Map<String, Int> = logActivatedSkill

    final override fun hasActivatedSkill(skillName: String): Boolean = activatedSkill[skillName] ?: false

    /** PHP `hasActivatedSkillOnLog($name)` = logCount + (currently-active ? 1 : 0). Contract method. */
    final override fun hasActivatedSkillOnLog(skillName: String): Int =
        (logActivatedSkill[skillName] ?: 0) + (if (hasActivatedSkill(skillName)) 1 else 0)

    final override fun activateSkill(vararg skillNames: String) {
        for (skillName in skillNames) activatedSkill[skillName] = true
    }

    final override fun deactivateSkill(vararg skillNames: String) {
        for (skillName in skillNames) activatedSkill[skillName] = false
    }

    open fun addWin() {}
    open fun addLose() {}

    // --- A3 contract var/dex/exp side-effect delegates (subclass fills; base is a safe no-op default) ----

    final override fun isGeneralUnit(): Boolean = isGeneral()

    /** Default no-op (City has no general var surface for these); [WarUnitGeneral] overrides each. */
    override fun applyVarChange(variable: String, operator: String, value: Double, limitMin: Double?, limitMax: Double?) {}
    override fun increaseAtmos(delta: Int) {}
    override fun increaseInjury(amount: Int) {}
    override fun decreaseAtmos(delta: Int, min: Int) {}
    override fun stealFrom(oppose: WarUnitContract, theftRatio: Double): Pair<Double, Double> = 0.0 to 0.0
    override fun clearInjury() {}
    override fun applyBlockReward(oppose: WarUnitContract) {}
    override fun getSiegeRamRemain(): Int = 0
    override fun setSiegeRamRemain(value: Int) {}
}

/** PHP `continueWar(&$noRice): bool` return pair — canContinue + the by-ref `$noRice` flag. */
data class WarContinuation(val canContinue: Boolean, val noRice: Boolean)
