package opensamguk.logic.ai

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.nation.NationCommand
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.util.clamp
import opensamguk.logic.util.phpRound

/**
 * F-INSTANCE — `AiInstanceState`, a faithful port of the per-AI-instance derived-state of
 * `legacy/devsam-core/hwe/sammo/GeneralAI.php` (GRAND TRUTH, read in full).
 *
 * Task FI1 ports `updateInstance` (`:86-145`), `maxResourceActionAmount` (`:128-137`), and
 * `calcDiplomacyState` (`:206-281`). `calcGenType` (the FIRST draw, `:175-204`) is Task FI2. The city/
 * general categorize buckets + `calcWarRoute` are F-FACADE (AiWorldView). This class is PURE `:logic`
 * (no Spring/DB): the world is supplied as plain-data lookups; the meta-KV side-effects are queued through
 * the [AiKvRecorder] seam (the ChangeRecorder delta path), NOT written inline and NOT held in a
 * module-static Map (M4 — the TS `last_attackable` static-Map is WRONG; PHP wins).
 *
 * ## The two cache regimes are NEVER coupled (decision #2)
 * `reqUpdateInstance` (default true at PHP `:69`) gates ONLY the [updateInstance] block (city/nation/
 * policy/genType/dipState/maxResource). A command sets it dirty (PHP `:1308/1509/1972/1990/2113`); the
 * block re-runs once per dirty. The per-field null-guards of `categorizeNationCities`/
 * `categorizeNationGeneral`/`calcWarRoute` (F-FACADE) are a SEPARATE regime — computed once, never
 * re-invalidated by a `reqUpdateInstance` flip. This class owns ONLY the `reqUpdateInstance` flag.
 *
 * ## NO draws (Task FI1)
 * `updateInstance` and `calcDiplomacyState` make ZERO RNG draws — there is no [opensamguk.common.rng.RandUtil]
 * surface on this class for FI1. They only materialize derived state + set candidate ORDER + queue meta-KV
 * deltas. (The FIRST draw is `calcGenType`, added in FI2.)
 */
class AiInstanceState(
    /** The acting general's nation id (PHP `$general->getNationID()`). */
    private val generalNationId: Int,
    /** The game env (`$this->env`) — year/month/startyear/develcost. */
    private val env: AiEnv,
    /** The merged nation policy (F-POLICY) — supplies `minimumResourceActionAmount`/`maximumResourceActionAmount`. */
    val nationPolicy: AutorunNationPolicy,
    /**
     * PHP `$db->queryFirstRow('SELECT ... FROM nation WHERE nation = %i', nationID)` (`:104-106`).
     * Returns `null` when the general is 재야 (nation 0 / no row) → the `??` neutral-default fires (`:107-119`).
     */
    private val nationRowLookup: () -> AiNationRow?,
    /**
     * The `nation_env` KV snapshot (PHP `KVStorage::getStorage($db, nationID, 'nation_env')`, `:120-121`,
     * `:256`). Reads `prev_income_gold`/`prev_income_rice` (`:126-127`, default 1000) and the PRE-TURN
     * `last_attackable` (`:276`). This is the pre-turn snapshot — the [kvRecorder] delta is visible only
     * NEXT turn, never re-read here within the same decision (M4).
     */
    private val nationStor: Map<String, Any?>,
    /**
     * PHP `SELECT you, state, term FROM diplomacy WHERE me = %i AND state IN (0,1)` (`:214-217`). The
     * returned rows feed BOTH the `warTarget` partition AND the `min(term) WHERE state=1` query (`:257`),
     * which this port derives from the same supplied list (state=1 subset).
     */
    private val diplomacyOf: () -> List<AiDiplomacyRow>,
    /**
     * PHP `SELECT max(front) FROM city WHERE nation=%i AND supply=1` (`:230`). `attackable = !!frontStatus`
     * (`:232`). Supplied as a lookup so this class stays PURE.
     */
    private val frontMaxOf: () -> Int,
    /** The ChangeRecorder delta seam (M4) — `last_attackable` writes route here, NOT inline. */
    private val kvRecorder: AiKvRecorder,
) {
    /** PHP `:69` `protected $reqUpdateInstance = true;` — runs the block once, then short-circuits. */
    private var reqUpdateInstance: Boolean = true

    // --- updateInstance outputs (the per-AI-instance snapshot every do<한글> reads) ---

    /** PHP `$this->baseDevelCost = env.develcost * 12` (`:95`). */
    var baseDevelCost: Int = 0
        private set

    /** The materialized nation row (or the neutral-default when the general is 재야). */
    lateinit var nation: AiNationRow
        private set

    /** PHP `$this->maxResourceActionAmount` (`:128-138`) — valueFit(phpRound(max(...),-2)) then hard-cap. */
    var maxResourceActionAmount: Int = 0
        private set

    // --- calcDiplomacyState outputs (NO draws; sets candidate ORDER + queues meta-KV deltas) ---

    /** PHP `$this->dipState` (one of [D_PEACE]/[D_DECLARED]/[D_CONSCRIPT]/[D_IMMINENT]/[D_WAR]). */
    var dipState: Int = D_PEACE
        private set

    /** PHP `$this->attackable` (`:225/232`) — `!!max(front over supplied cities)`, forced false early-phase. */
    var attackable: Boolean = false
        private set

    /**
     * PHP `$this->warTargetNation` (`:254`) — the `{nationID → 2|1}` war-target partition, with the `[0]=1`
     * sentinel when no active war. NULL after the early-phase EARLY RETURN (`:219-228` leaves it null).
     * Insertion-ordered (the diplomacy-row order is a candidate-order parity target for calcWarRoute).
     */
    var warTargetNation: Map<Int, Int>? = null
        private set

    /**
     * PHP `$this->genType` (`:144`, computed by [calcGenType], the FIRST draw). The bitwise OR of
     * [T_MUJANG]/[T_JIJANG]/[T_TONGSOLJANG]. `0` until [calcGenType] has run for the decision.
     */
    var genType: Int = 0
        private set

    /** Mark the instance dirty (PHP `$this->reqUpdateInstance = true` at the command dirty triggers). */
    fun markDirty() {
        reqUpdateInstance = true
    }

    /**
     * Faithful port of `updateInstance` (PHP `:86-145`). Guarded by [reqUpdateInstance]: runs the whole
     * block once, sets the flag false, and short-circuits on every subsequent call until [markDirty].
     */
    fun updateInstance(): Boolean {
        if (!reqUpdateInstance) {
            return false
        }
        reqUpdateInstance = false

        baseDevelCost = env.develCost * 12

        // PHP `:104-119` — nation row, or the `??` neutral-default literal when the general is 재야.
        nation = nationRowLookup() ?: AiNationRow(
            nation = 0,
            level = 0,
            capital = 0,
            gold = 0,
            rice = 0,
            type = GameConst.neutralNationType,
            name = "재야",
        )

        // PHP `:126-138` — maxResourceActionAmount = valueFit(round(max(...),-2), null, max) then hard-cap.
        val prevIncomeGold = (nationStor["prev_income_gold"] as? Number)?.toDouble() ?: 1000.0
        val prevIncomeRice = (nationStor["prev_income_rice"] as? Number)?.toDouble() ?: 1000.0
        val rawMax = maxOf(
            nationPolicy.minimumResourceActionAmount.toDouble(),
            prevIncomeGold / 10.0,
            prevIncomeRice / 10.0,
            nation.gold / 5.0,
            nation.rice / 5.0,
            (env.year - env.startYear - 3) * 1000.0,
        )
        // phpRound(...,-2) = half-AWAY at the 10² position (NEVER Math.round, NEVER phpRound(v/100)*100 — M5).
        var capped = clamp(
            phpRound(rawMax, -2).toDouble(),
            null,
            nationPolicy.maximumResourceActionAmount.toDouble(),
        ).toInt()
        // PHP `:136-138` — the GameConst hard-cap (distinct from the policy maximumResourceActionAmount).
        if (capped > GameConst.maxResourceActionAmount) {
            capped = GameConst.maxResourceActionAmount
        }
        maxResourceActionAmount = capped

        // PHP `:142` — calcDiplomacyState() (NO draws; queues last_attackable deltas).
        calcDiplomacyState()

        // PHP `:144` — genType = calcGenType($general) is Task FI2 (the FIRST draw); not in FI1.
        return true
    }

    /**
     * Faithful port of `calcDiplomacyState` (PHP `:206-281`). NO draws. WRITES the `last_attackable` meta-KV
     * via the [kvRecorder] delta (M4) at `:266/271/275`; the read at `:276` uses the PRE-TURN [nationStor]
     * snapshot. Sets [dipState]/[attackable]/[warTargetNation].
     */
    private fun calcDiplomacyState() {
        val nationID = generalNationId
        val yearMonth = NationCommand.joinYearMonth(env.year, env.month) // year*12+month-1 (-1 variant, H-HELPERS §1)

        // PHP `:214-217` — SELECT you, state, term FROM diplomacy WHERE me=nationID AND state IN (0,1).
        val warTarget = diplomacyOf().filter { it.state == 0 || it.state == 1 }

        // PHP `:219-228` — the early-phase gate; EARLY RETURN leaves warTargetNation null.
        if (yearMonth <= NationCommand.joinYearMonth(env.startYear + 2, 5)) {
            if (warTarget.isEmpty()) {
                dipState = D_PEACE
                attackable = false
            } else {
                dipState = D_DECLARED
                attackable = false
            }
            warTargetNation = null
            return
        }

        // PHP `:230-232` — attackable = !!max(front over supplied cities).
        attackable = frontMaxOf() != 0

        // PHP `:234-254` — the war-target partition (insertion-ordered, diplomacy-row order).
        var onWar = 0
        var onWarReady = 0
        val warTargetNationMap = LinkedHashMap<Int, Int>()
        for (row in warTarget) {
            when {
                row.state == 0 -> {
                    onWar += 1
                    warTargetNationMap[row.you] = 2
                }
                row.state == 1 && row.term < 5 -> {
                    onWarReady += 1
                    warTargetNationMap[row.you] = 1
                }
                else -> {
                    // onWarYet += 1 (counted but never read after :248)
                }
            }
        }
        // PHP `:250-252` — the [0]=1 sentinel when no active/ready war.
        if (onWar == 0 && onWarReady == 0) {
            warTargetNationMap[0] = 1
        }
        warTargetNation = warTargetNationMap

        // PHP `:256-267` — dipState off min(term) WHERE state=1 (distinct query from warTarget).
        val state1Terms = diplomacyOf().filter { it.state == 1 }.map { it.term }
        val minWarTerm: Int? = state1Terms.minOrNull()
        when {
            minWarTerm == null -> dipState = D_PEACE
            minWarTerm > 8 -> dipState = D_DECLARED
            minWarTerm > 5 -> dipState = D_CONSCRIPT
            else -> {
                dipState = D_IMMINENT
                kvRecorder.recordNationKv(nationID, "last_attackable", yearMonth) // PHP `:266`
            }
        }

        // PHP `:269-280` — the war-upgrade override to d전쟁.
        val lastAttackable = (nationStor["last_attackable"] as? Number)?.toInt() // PRE-TURN snapshot (:276)
        if (warTargetNationMap.containsKey(0) && attackable) {
            dipState = D_WAR
            kvRecorder.recordNationKv(nationID, "last_attackable", yearMonth) // PHP `:271`
        } else if (onWar != 0) {
            if (attackable) {
                dipState = D_WAR
                kvRecorder.recordNationKv(nationID, "last_attackable", yearMonth) // PHP `:275`
            } else if (lastAttackable != null && lastAttackable >= yearMonth - 5) {
                // 접경이 사라진지 아직 5개월 이내 (PHP `:276-279`) — READS the pre-turn snapshot, never writes.
                dipState = D_WAR
            }
        }
    }

    /**
     * Faithful port of `calcGenType` (PHP `:175-204`) — the AI decision's FIRST draw site. ONE or ZERO
     * `nextBool` draws off the SOLE per-general `"GeneralAI"` [rng] (threaded by reference, NEVER re-seeded):
     * the conditional draw fires ONLY inside the near-balance band (the weaker stat ≥ the stronger × 0.8),
     * and its presence/absence shifts EVERY later draw of the decision (the #1 desync trap).
     *
     * Stats are the `getX(false)` flavor (decision #7, G8): leadership via [AiSeed.genTypeLeadership]
     * (NO floor); strength/intel via [AiSeed.genTypeStrength]/[AiSeed.genTypeIntel] = `Util::valueFit(v, 1)`
     * = `clamp(v, 1.0)` (min 1, no max) → the denominators are ≥ 1 (no div-by-zero). `useFloor=true` already
     * truncated — do NOT re-round.
     *
     * `*0.8` and `/2` are **Double float division** (NEVER integer division); the resulting prob ∈ [0.4, 0.5).
     * The [statCalc] is the GREEN per-resolve stat calculator (PURE — no Spring/DB); the [rng] is the SOLE
     * decision stream. Assigns + returns [genType].
     */
    fun calcGenType(rng: RandUtil, statCalc: StatCalc): Int {
        // PHP `:177-179` — getLeadership(false)/getStrength(false)/getIntel(false), valueFit(strength/intel,1).
        val leadership = AiSeed.genTypeLeadership(statCalc)
        val strength = AiSeed.genTypeStrength(statCalc)
        val intel = AiSeed.genTypeIntel(statCalc)

        val computed: Int
        // PHP `:182-188` — 무장 branch (strength >= intel, the `>=` tie goes 무장).
        if (strength >= intel) {
            var t = T_MUJANG
            // PHP `:184` — near-balance band: intel >= strength * 0.8 (Double float). `:185` FIRST draw.
            if (intel >= strength * 0.8) {
                if (rng.nextBool(intel / strength / 2)) { // intel.toDouble()/strength/2, prob ∈ [0.4,0.5)
                    t = t or T_JIJANG
                }
            }
            computed = t
        } else {
            // PHP `:190-196` — 지장 branch.
            var t = T_JIJANG
            // PHP `:192` — near-balance band: strength >= intel * 0.8 (Double float). `:193` the draw.
            if (strength >= intel * 0.8) {
                if (rng.nextBool(strength / intel / 2)) { // strength.toDouble()/intel/2, prob ∈ [0.4,0.5)
                    t = t or T_MUJANG
                }
            }
            computed = t
        }

        // PHP `:199-202` — the 통솔장 flag (NO draw; a minNPCWarLeadership threshold), bitwise |=.
        val withTongsol = if (leadership >= nationPolicy.minNPCWarLeadership) {
            computed or T_TONGSOLJANG
        } else {
            computed
        }

        genType = withTongsol
        return withTongsol
    }

    companion object {
        // The genType flags (PHP `GeneralAI.php:76-78`), combined with bitwise OR.
        const val T_MUJANG = 1     // t무장
        const val T_JIJANG = 2     // t지장
        const val T_TONGSOLJANG = 4 // t통솔장

        // The dipState constants (PHP `GeneralAI.php:80-84`).
        const val D_PEACE = 0      // d평화
        const val D_DECLARED = 1   // d선포
        const val D_CONSCRIPT = 2  // d징병
        const val D_IMMINENT = 3   // d직전
        const val D_WAR = 4        // d전쟁
    }
}

/** The game env subset `updateInstance`/`calcDiplomacyState` read (PHP `$this->env`). */
data class AiEnv(
    val year: Int,
    val month: Int,
    val startYear: Int,
    val develCost: Int,
)

/**
 * The materialized `nation` row subset `updateInstance` reads (PHP `:104-119`). The neutral-default
 * (`nation=0/level=0/capital=0/type=neutral/name=재야`) fires when the lookup returns null.
 */
data class AiNationRow(
    val nation: Int,
    val level: Int,
    val capital: Int,
    val gold: Int,
    val rice: Int,
    val type: String = "",
    val name: String = "",
)

/** A diplomacy row as `calcDiplomacyState` reads it (PHP `SELECT you, state, term ...`, `:214-217`). */
data class AiDiplomacyRow(
    val you: Int,
    val state: Int,
    val term: Int,
)

/**
 * The ChangeRecorder delta seam (M4 / decision #12). The AI is READ-ONLY over GAME ENTITIES; meta-KV
 * side-effects (`last_attackable` here; `resp_assist_try`/`last천도Trial`/… in later families) queue
 * through this recorder as AI-side deltas — NOT inline, NOT a module-static Map. The engine adapter wires
 * a real ChangeRecorder-backed implementation; the logic layer only sees this interface.
 */
interface AiKvRecorder {
    /** Queue a nation `nation_env` KV write (`KVStorage::getStorage($db, nationID, 'nation_env')->key = value`). */
    fun recordNationKv(nationId: Int, key: String, value: Any?)
}

/** One queued meta-KV delta (the value a [AiKvRecorder] captures). Insertion order is the write order. */
data class KvDelta(val nationId: Int, val key: String, val value: Any?)
