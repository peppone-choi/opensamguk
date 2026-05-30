package opensamguk.logic.war

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.constants.getTechCost
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.domain.explevel
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.war.trigger.WarUnit as WarUnitContract

/**
 * F2 — port target = PHP `sammo/WarUnitGeneral.php:7-378`.
 *
 * The general battle unit. Holds a [WarUnitGeneralState] (the mutable working copy of the immutable
 * [General] — the Immer-draft replacement: every battle mutation rides this holder and the resolver
 * records `state.snapshot()` as a ChangeRecorder delta, NEVER an inline DB write).
 *
 * Owns:
 *  - [computeWarPower] OVERRIDE (`:209-231`): parent core, then explevel `/600` vs a city / `/300` vs a
 *    general with the `max(0.01, …)` clamp, then the per-source `getWarPowerMultiplier` fold (F5 — NO draw);
 *  - [tryWound] (`:306-325`): 부상무효/퇴각부상무효 short-circuit BEFORE the `nextBool(0.05)`; a 2nd
 *    `nextRangeInt(10,80)` injury draw ONLY when the bool hits → 1 or 2 draws;
 *  - [finishBattle] (`:346-370`): idempotent guard, the rank counters, then the FIXED
 *    rice→experience→dedication half-away `Util::round` order;
 *  - the ctor cityLevel ±5 atmos/train bonuses (`:23-40`);
 *  - the computed-input surface (getComputedAttack/Defence/Train/Atmos/CriticalRatio/AvoidRatio, getDex)
 *    + the magic-prob delegates the F1 계략 trigger calls.
 *
 * `tech` (nation tech) and `crewType`/`cityLevel`/`isCapital` are threaded in by the caller (B1 builds them
 * from the engine context); the WarUnit never queries a DB.
 */
class WarUnitGeneral(
    rng: RandUtil,
    val state: WarUnitGeneralState,
    private val pipeline: GeneralActionPipeline,
    private val crewType: GameUnitDetail,
    private val tech: Int,
    isAttacker: Boolean,
    cityLevel: Int,
    isCapital: Boolean,
) : WarUnit(rng) {

    private var killedPerson: Int = 0
    private var deadPerson: Int = 0
    private val unitToken: String = "G${state.general.id}"

    init {
        isAttackerFlag = isAttacker
        // ctor cityLevel bonus (PHP :25-40)
        if (isAttacker) {
            if (cityLevel == 2) atmosBonus += 5
            if (isCapital) atmosBonus += 5
        } else {
            if (cityLevel == 1) trainBonus += 5
            else if (cityLevel == 3) trainBonus += 5
        }
    }

    fun getGeneral(): General = state.general

    override fun unitIdToken(): String = unitToken

    override fun getName(): String = "G${state.general.id}"

    override fun getCrewType(): GameUnitDetail = crewType

    override fun getHP(): Int = state.crew

    override fun isGeneral(): Boolean = true
    override fun isCity(): Boolean = false

    // --- stat resolution (PHP General::getStatValue, the iAction-flagged folds) -------------------------

    private fun statCalc(): StatCalc = StatCalc(state.general, pipeline)

    private fun resolveStat(name: String, withInjury: Boolean, withIAction: Boolean, withAdjust: Boolean, useFloor: Boolean): Double =
        statCalc().getStatValue(name, withInjury, withIAction, withAdjust, useFloor)

    // --- computed combat inputs (PHP WarUnit/WarUnitGeneral) --------------------------------------------

    override fun getComputedAttack(): Double {
        // crewType.getComputedAttack pulls getStrength/getIntel/getLeadership(true,true,true) (useFloor default true).
        val str = resolveStat("strength", true, true, true, true)
        val intel = resolveStat("intel", true, true, true, true)
        val lead = resolveStat("leadership", true, true, true, true)
        return crewType.getComputedAttack(str, intel, lead, tech)
    }

    override fun getComputedDefence(): Double = crewType.getComputedDefence(state.crew, tech)

    override fun getComputedTrain(): Double {
        val oppose = getOppose()
        var train = state.train
        train = pipeline.onCalcStat(state.general, "bonusTrain", train, mapOf("isAttacker" to isAttacker()))
        if (oppose is WarUnitGeneral) {
            train = pipeline.onCalcOpposeStat(oppose.state.general, "bonusTrain", train, mapOf("isAttacker" to isAttacker()))
        }
        train += trainBonus
        return train
    }

    override fun getComputedAtmos(): Double {
        val oppose = getOppose()
        var atmos = state.atmos
        atmos = pipeline.onCalcStat(state.general, "bonusAtmos", atmos, mapOf("isAttacker" to isAttacker()))
        if (oppose is WarUnitGeneral) {
            atmos = pipeline.onCalcOpposeStat(oppose.state.general, "bonusAtmos", atmos, mapOf("isAttacker" to isAttacker()))
        }
        atmos += atmosBonus
        return atmos
    }

    override fun getDex(crewType: GameUnitDetail): Double {
        val oppose = getOppose()
        var dex = state.getDex(crewType)
        val aux = mapOf("isAttacker" to isAttacker(), "opposeType" to (oppose?.getCrewType()))
        dex = pipeline.onCalcStat(state.general, "dex${crewType.armType}", dex, aux)
        if (oppose is WarUnitGeneral) {
            dex = pipeline.onCalcOpposeStat(oppose.state.general, "dex${crewType.armType}", dex, aux)
        }
        return dex
    }

    override fun getComputedCriticalRatio(): Double {
        val oppose = getOppose()
        // crewType.getCriticalRatio pulls getIntel/getLeadership/getStrength(false,true,true,false).
        val str = resolveStat("strength", false, true, true, false)
        val intel = resolveStat("intel", false, true, true, false)
        val lead = resolveStat("leadership", false, true, true, false)
        var ratio = crewType.getCriticalRatio(str, intel, lead)
        ratio = pipeline.onCalcStat(state.general, "warCriticalRatio", ratio, mapOf("isAttacker" to isAttacker()))
        if (oppose is WarUnitGeneral) {
            ratio = pipeline.onCalcOpposeStat(oppose.state.general, "warCriticalRatio", ratio, mapOf("isAttacker" to isAttacker()))
        }
        return ratio
    }

    override fun getComputedAvoidRatio(): Double {
        val oppose = getOppose()
        var avoid = crewType.avoid / 100.0
        avoid *= getComputedTrain() / 100.0
        avoid = pipeline.onCalcStat(state.general, "warAvoidRatio", avoid, mapOf("isAttacker" to isAttacker()))
        if (oppose is WarUnitGeneral) {
            avoid = pipeline.onCalcOpposeStat(oppose.state.general, "warAvoidRatio", avoid, mapOf("isAttacker" to isAttacker()))
        }
        if (oppose != null && oppose.getCrewType().armType == GameUnitConst.T_FOOTMAN) {
            avoid *= 0.75
        }
        return avoid
    }

    override fun getMaxPhase(): Int {
        var phase = crewType.speed
        phase = pipeline.onCalcStat(state.general, "initWarPhase", phase.toDouble(), mapOf("isAttacker" to isAttacker())).toInt()
        return phase + bonusPhase
    }

    // --- war power override (PHP :209-231) --------------------------------------------------------------

    override fun computeWarPower(): Pair<Double, Double> {
        val (basePower, baseOppMul) = super.computeWarPower()
        var warPower = basePower
        var opposeWarPowerMultiply = baseOppMul

        val expLevel = state.general.explevel()
        if (getOppose()?.isCity() == true) {
            warPower *= 1 + expLevel / 600.0
        } else {
            warPower /= maxOf(0.01, 1 - expLevel / 300.0)
            opposeWarPowerMultiply *= maxOf(0.01, 1 - expLevel / 300.0)
        }

        val (specialMy, specialOppose) = pipeline.getWarPowerMultiplier(this)
        warPower *= specialMy
        opposeWarPowerMultiply *= specialOppose

        this.warPowerField = warPower
        getOppose()?.setWarPowerMultiply(opposeWarPowerMultiply)
        return warPower to opposeWarPowerMultiply
    }

    // --- HP / killed (PHP :243-304) ---------------------------------------------------------------------

    override fun decreaseHP(damage: Int): Int {
        val dmg = minOf(damage, state.crew)
        deadField += dmg
        deadCurrField += dmg
        state.increaseCrew(-dmg)
        var addDex = dmg.toDouble()
        if (!isAttacker()) addDex *= 0.1
        val oppCrew = getOppose()?.getCrewType()
        if (oppCrew != null) state.addDex(oppCrew, addDex)
        if (getOppose() is WarUnitGeneral) deadPerson += dmg
        return state.crew
    }

    override fun increaseKilled(damage: Int): Int {
        addLevelExp(damage / 50.0)
        val rice = calcRiceConsumption(damage)
        state.increaseRiceWithLimit(-rice, 0.0)
        var addDex = damage.toDouble()
        if (!isAttacker()) addDex *= 0.8
        state.addDex(crewType, addDex)
        killedField += damage
        killedCurrField += damage
        if (getOppose() is WarUnitGeneral) killedPerson += damage
        return killedField
    }

    private fun calcRiceConsumption(damage: Int): Double {
        var rice = damage / 100.0
        if (!isAttacker()) rice *= 0.8
        if (getOppose()?.isCity() == true) rice *= 0.8
        rice *= crewType.rice
        rice *= getTechCost(tech)
        rice = pipeline.onCalcStat(state.general, "killRice", rice)
        return rice
    }

    private fun addLevelExp(value: Double) {
        var v = value
        if (!isAttacker()) v *= 0.8
        state.addExperience(v)
    }

    // --- continuation / wound (PHP :327-339, :306-325) --------------------------------------------------

    override fun continueWar(): WarContinuation {
        if (getHP() <= 0) return WarContinuation(canContinue = false, noRice = false)
        if (state.rice <= getHP() / 100.0) return WarContinuation(canContinue = false, noRice = true)
        return WarContinuation(canContinue = true, noRice = false)
    }

    override fun tryWound(): Boolean {
        if (hasActivatedSkillOnLog("부상무효") > 0) return false
        if (hasActivatedSkillOnLog("퇴각부상무효") > 0) return false
        if (!rng.nextBool(0.05)) return false
        activateSkill("부상")
        val amount = rng.nextRangeInt(10, 80)
        state.increaseInjuryWithLimit(amount, max = 80)
        pushPlainActionLog("전투중 <R>부상</>당했다!")
        return true
    }

    // --- win/lose (PHP :154-207) ------------------------------------------------------------------------

    override fun addWin() {
        state.increaseRank("killnum", 1)
        if (getOppose()?.isCity() == true) state.increaseRank("occupied", 1)
        if (isAttacker()) state.multiplyAtmosWithLimit(1.1, GameConst.maxAtmosByWar.toDouble())
        else state.multiplyAtmosWithLimit(1.05, GameConst.maxAtmosByWar.toDouble())
        addStatExp(1)
    }

    override fun addLose() {
        state.increaseRank("deathnum", 1)
        addStatExp(1)
    }

    private fun addStatExp(value: Int) {
        when (crewType.armType) {
            GameUnitConst.T_WIZARD -> state.increaseMetaExp("intel_exp", value)
            GameUnitConst.T_SIEGE -> state.increaseMetaExp("leadership_exp", value)
            else -> state.increaseMetaExp("strength_exp", value)
        }
    }

    // --- setOppose (PHP :53-71): warnum + recent_war ----------------------------------------------------

    override fun setOppose(oppose: WarUnit?) {
        super.setOppose(oppose)
        state.increaseRank("warnum", 1)
        // recent_war turn-time string is engine-supplied (turnTime not on the logic General); skip the
        // string-mangle here — the resolver (B1) sets recent_war. The rank counter increment is the
        // parity-relevant battle side-effect.
    }

    // --- finishBattle (PHP :346-370) — idempotent + the FIXED round order -------------------------------

    fun finishBattle() {
        if (isFinished) return
        clearActivatedSkill()
        isFinished = true

        state.increaseRank("killcrew", killedField)
        state.increaseRank("deathcrew", deadField)
        if (killedPerson != 0) state.increaseRank("killcrew_person", killedPerson)
        if (deadPerson != 0) state.increaseRank("deathcrew_person", deadPerson)

        // rice → experience → dedication, half-away Util::round, in THIS order (PHP :365-367)
        state.roundRice()
        state.roundExperience()
        state.roundDedication()
    }

    // --- F1 magic-prob delegates (NO draw; the draws live in the 계략 trigger) --------------------------

    override fun getMagicTrialProb(oppose: WarUnitContract): Double {
        var prob = resolveStat("intel", true, true, true, false) / 100.0
        prob *= crewType.magicCoef
        prob = pipeline.onCalcStat(state.general, "warMagicTrialProb", prob)
        if (oppose is WarUnitGeneral) {
            prob = pipeline.onCalcOpposeStat(oppose.state.general, "warMagicTrialProb", prob)
        }
        if (prob <= 0) return prob
        val rawIntel = resolveStat("intel", false, false, false, false)
        val allStat = resolveStat("leadership", false, false, false, false) +
            resolveStat("strength", false, false, false, false) + rawIntel
        if (getPhase() == 0 && rawIntel * 3 >= allStat) prob *= 3
        return prob
    }

    override fun getMagicSuccessProb(oppose: WarUnitContract): Double {
        var prob = 0.7
        prob = pipeline.onCalcStat(state.general, "warMagicSuccessProb", prob)
        if (oppose is WarUnitGeneral) {
            prob = pipeline.onCalcOpposeStat(oppose.state.general, "warMagicSuccessProb", prob)
        }
        return prob
    }

    override fun foldMagicSuccessDamage(oppose: WarUnitContract, magic: String, raw: Double): Double {
        var dmg = pipeline.onCalcStat(state.general, "warMagicSuccessDamage", raw, mapOf("magic" to magic))
        if (oppose is WarUnitGeneral) {
            dmg = pipeline.onCalcOpposeStat(oppose.state.general, "warMagicSuccessDamage", dmg, mapOf("magic" to magic))
        }
        return dmg
    }

    override fun foldMagicFailDamage(oppose: WarUnitContract, magic: String, raw: Double): Double {
        var dmg = pipeline.onCalcStat(state.general, "warMagicFailDamage", raw, mapOf("magic" to magic))
        if (oppose is WarUnitGeneral) {
            dmg = pipeline.onCalcOpposeStat(oppose.state.general, "warMagicFailDamage", dmg, mapOf("magic" to magic))
        }
        return dmg
    }

    // --- log / item delegates (engine-side; B1/A2 wire) ------------------------------------------------

    private val battleDetailLogs: MutableList<String> = mutableListOf()
    private val plainActionLogs: MutableList<String> = mutableListOf()

    override fun pushBattleDetailLog(message: String) { battleDetailLogs.add(message) }
    override fun pushPlainActionLog(message: String) { plainActionLogs.add(message) }
    fun getBattleDetailLogs(): List<String> = battleDetailLogs
    fun getPlainActionLogs(): List<String> = plainActionLogs

    override fun getItemName(): String = state.getItem()
    override fun getItemRawName(): String = state.getItem()
    override fun deleteItem() { state.deleteItem() }
}
