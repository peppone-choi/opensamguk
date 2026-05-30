package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound
import opensamguk.logic.war.trigger.WarUnit as WarUnitContract

/**
 * F2 — port target = PHP `sammo/WarUnitCity.php:4-196`.
 *
 * The城벽 (wall) battle unit substituting a `DummyGeneral(false)` for the absent general. Makes ZERO own
 * RNG draws — every draw flows through the inherited [WarUnit] sites (the city never calls nextBool/
 * nextRange itself). Holds a [WarUnitCityState] (the mutable working copy of the immutable [City]).
 *
 * Formulas (PHP grand truth):
 *  - `HP = def * 10` (`:30`);
 *  - `getComputedAttack() == getComputedDefence() == (def + wall*9) / 500 + 200` FLOAT, no round (`:56-62`);
 *  - `cityTrainAtmos = clamp(year - startYear + 59, 60, 110)` — the LITERAL +59 (`:24`);
 *  - `getComputedTrain() = cta + trainBonus`, `getComputedAtmos() = cta + atmosBonus`;
 *    `trainBonus += 5` when level ∈ {1,3} (two `else if`, NEVER +10) (`:33-38`);
 *  - `getDex() = (cta - 60) * 7200` (`:97-99`);
 *  - `decreaseHP` STRICT order: `min(damage,hp)` → `dead+=` → `deadCurr+=` → `hp-=` →
 *    `wall increaseVarWithLimit(-damage/20.0, min 0)` float division (`:101-109`);
 *  - `finishBattle`: `def = round(hp/10)`, `wall = round(wall)`, THEN the early-return makes the
 *    `killed/20` wealth block DEAD CODE (decision #9 — port faithfully unreachable) (`:134-150`);
 *  - `applyDB` calls `logger.rollback()` so the defender CITY's battle logs are DISCARDED (`:184-195`) —
 *    modeled as [suppressLog] = true so the engine emits NO city-side battle log.
 *
 * `addConflict` is A4's task (the ConflictMap ordered-map) and lands ON this class later — NOT in F2.
 */
class WarUnitCity(
    rng: RandUtil,
    val state: WarUnitCityState,
    year: Int,
    startYear: Int,
) : WarUnit(rng) {

    private var hp: Int = state.defense * 10
    private val cityTrainAtmos: Int = clamp((year - startYear + 59).toDouble(), 60.0, 110.0).toInt()
    private var onSiege: Boolean = false
    private val castle: GameUnitDetail = GameUnitConst.byId(GameUnitConst.CREWTYPE_CASTLE)!!
    private val unitToken: String = "C${state.city.id}"

    init {
        isAttackerFlag = false
        // 수비자 보정 (PHP :32-38): level 1 or 3 → +5 trainBonus (two else-if, never +10)
        if (state.city.level == 1) trainBonus += 5
        else if (state.city.level == 3) trainBonus += 5
    }

    fun getCityTrainAtmos(): Int = cityTrainAtmos

    override fun unitIdToken(): String = unitToken

    override fun getName(): String = "C${state.city.id}"

    override fun getCrewType(): GameUnitDetail = castle

    override fun isGeneral(): Boolean = false
    override fun isCity(): Boolean = true

    // --- formulas (PHP :56-99) --------------------------------------------------------------------------

    override fun getComputedAttack(): Double = (state.defense + state.wall * 9) / 500.0 + 200
    override fun getComputedDefence(): Double = (state.defense + state.wall * 9) / 500.0 + 200

    override fun getComputedTrain(): Double = (cityTrainAtmos + trainBonus).toDouble()
    override fun getComputedAtmos(): Double = (cityTrainAtmos + atmosBonus).toDouble()

    override fun getDex(crewType: GameUnitDetail): Double = (cityTrainAtmos - 60).toDouble() * 7200

    override fun getHP(): Int = hp

    // --- siege flag (PHP :82-95) ------------------------------------------------------------------------

    fun setSiege() {
        onSiege = true
        currPhase = 0
        prePhaseField = 0
        bonusPhase = 0
        isFinished = false
    }

    fun isSiege(): Boolean = onSiege

    // --- HP / killed (PHP :64-109) ----------------------------------------------------------------------

    /** PHP `increaseKilled` (`:64-68`): killed/killedCurr only — the city does NOT spend rice/dex/exp. */
    override fun increaseKilled(damage: Int): Int {
        killedField += damage
        killedCurrField += damage
        return killedField
    }

    /** PHP `decreaseHP` (`:101-109`) — the STRICT mutation order; wall float division `-damage/20`, min 0. */
    override fun decreaseHP(damage: Int): Int {
        val dmg = minOf(damage, hp)
        deadField += dmg
        deadCurrField += dmg
        hp -= dmg
        state.increaseWallWithLimit(-dmg / 20.0, 0.0)
        return hp
    }

    /** PHP `continueWar` (`:111-126`): a non-siege city eats exactly one hit (false); a siege city continues while HP>0. */
    override fun continueWar(): WarContinuation {
        if (!onSiege) return WarContinuation(canContinue = false, noRice = false)
        if (getHP() <= 0) return WarContinuation(canContinue = false, noRice = false)
        return WarContinuation(canContinue = true, noRice = false)
    }

    // --- finishBattle (PHP :134-150) — def=round(hp/10), wall=round(wall); wealth block is DEAD CODE ----

    fun finishBattle() {
        clearActivatedSkill()
        isFinished = true

        state.updateDefense(phpRound(getHP() / 10.0))
        state.updateWall(phpRound(state.wall).toDouble())

        // PHP :141 `if($this->isFinished || !$this->onSiege){ return; }` — isFinished was just set true,
        // so this ALWAYS returns → the killed/20 agri/comm/secu wealth block below is UNREACHABLE (decision
        // #9; port faithfully). Transcribed but gated false so it never runs.
        if (isFinished || !onSiege) return
        @Suppress("UNREACHABLE_CODE")
        run {
            val decWealth = getKilled() / 20.0
            state.increaseWealthWithLimit(-decWealth, 0.0)
        }
    }

    // --- applyDB log suppression (PHP :184-195) ---------------------------------------------------------

    /** True — the defender city's battle logs are rolled back (NOT emitted). The engine reads this. */
    fun suppressLog(): Boolean = true

    // --- F1 contract leftovers (a city never triggers 필살/회피/계략 — it has no general self) -----------

    override fun getComputedCriticalRatio(): Double = castle.getCriticalRatio(0.0, 0.0, 0.0)  // CASTLE → 0
    override fun getComputedAvoidRatio(): Double = castle.avoid / 100.0
    override fun pushBattleDetailLog(message: String) {}
    override fun pushPlainActionLog(message: String) {}
    override fun getItemName(): String = ""
    override fun getItemRawName(): String = ""
    override fun deleteItem() {}
    override fun getMagicTrialProb(oppose: WarUnitContract): Double = 0.0
    override fun getMagicSuccessProb(oppose: WarUnitContract): Double = 0.7
    override fun foldMagicSuccessDamage(oppose: WarUnitContract, magic: String, raw: Double): Double = raw
    override fun foldMagicFailDamage(oppose: WarUnitContract, magic: String, raw: Double): Double = raw
}
