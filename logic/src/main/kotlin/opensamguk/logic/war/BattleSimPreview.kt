package opensamguk.logic.war

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitCatalog
import opensamguk.common.constants.GameUnitDetail
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline
import java.security.SecureRandom

/**
 * B3 / BS1 — the raw-input engine-REUSE adapter. Port target = PHP `j_simulate_battle.php:366-476`
 * (`simulateBattle` DB-free, logger.rollback capture) + `:243-249` (a POSTed seed forces `repeatCnt=1`).
 * Research Unit 8.
 *
 * Lives on the P7 read/preview side but depends only on F3 (the [processWarNG] phase machine) + B1 (the
 * OUTER [processWar] wrapper). **It REUSES the engine — it does NOT fork it.** The whole point of the
 * preview is parity: the FIXED-seed single replay must produce a draw stream value-for-value IDENTICAL to a
 * real `che_출병` battle for the same `warSeed` + armies. Reuse of [processWar] (the SAME function the real
 * resolver calls, which owns the SINGLE `RandUtil(LiteHashDrbg(warSeed))` creation site) makes that identity
 * structural, not coincidental.
 *
 * **Pure / no-flush.** The preview never writes a ChangeRecorder delta — [processWar] returns the mutated
 * working units ([ProcessWarResult.attacker] / [ProcessWarResult.city]) and the computed deltas as plain
 * values; the preview only READS those to aggregate killed/dead/rice/phase/skill averages and DISCARDS the
 * rest (PHP `logger.rollback()` on each defender — the per-defender battle log is thrown away, not applied).
 *
 * **Construction is draw-neutral.** Building the [WarUnitGeneral] / [WarUnitCity] working units + running
 * the input validation consumes ZERO rng (PHP `WarUnitGeneral.__construct` + the Validator pass draw
 * nothing) — see [buildUnitsOnly]. The ONLY draws happen inside [processWarNG], off the single shared rng.
 *
 * **Seed semantics (`:243-249`, `:500-503`).** A non-empty POSTed `warSeed` forces `repeatCnt=1`
 * (deterministic single replay) — the golden harness MUST drive with a FIXED seed. An ABSENT seed (null)
 * Monte-Carlos: each repeat draws a fresh `random_bytes(16)` seed ([randomSeedFn]) and the aggregate is the
 * per-repeat mean. `repeatCnt` is clamped to `[1, 1000]` (PHP Validator `in` rule), defaulting to 1.
 */

/** RAW general input — the `j_simulate_battle.php` `$generalCheck` payload (NO DB row; plain numbers). */
data class BattleSimGeneralInput(
    val no: Int,
    val nationId: Int,
    val cityId: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val explevel: Int,
    val experience: Int,
    val dedication: Int,
    val officerLevel: Int,
    val gold: Int,
    val rice: Int,
    val crew: Int,
    val train: Int,
    val atmos: Int,
    val crewTypeId: Int,
    val horse: String = "None",
    val weapon: String = "None",
    val book: String = "None",
    val item: String = "None",
    /** carries explevel/defence_train and any WAR_* inheritBuff folds (validated to [0,5] by the adapter). */
    val meta: Map<String, Any?> = linkedMapOf(),
) {
    /** Materialize the immutable logic [General] (explevel rides meta, P1 convention). NO draw. */
    fun toGeneral(): General {
        val m = LinkedHashMap<String, Any?>(meta)
        m["explevel"] = explevel
        return General(
            id = no, nationId = nationId, cityId = cityId,
            leadership = leadership, strength = strength, intel = intel, injury = injury,
            experience = experience.toDouble(), dedication = dedication.toDouble(),
            officerLevel = officerLevel, gold = gold, rice = rice,
            crew = crew, train = train.toDouble(), atmos = atmos.toDouble(), crewTypeId = crewTypeId,
            horse = horse, weapon = weapon, book = book, item = item,
            meta = m,
        )
    }
}

/** The full DB-free preview payload (`simulateBattle` parameters). */
data class BattleSimInput(
    val attacker: BattleSimGeneralInput,
    val attackerCity: City,
    val attackerNation: Nation,
    val defenders: List<BattleSimGeneralInput>,
    val defenderCity: City,
    val defenderNation: Nation,
    val year: Int,
    val month: Int,
    val startYear: Int,
    /** non-null → deterministic single replay (`repeatCnt` forced to 1); null → Monte-Carlo. */
    val warSeed: String?,
    val attackerCrewType: GameUnitDetail,
    val defenderCrewType: GameUnitDetail,
    /** [1,1000]; IGNORED (forced to 1) when [warSeed] is non-null. */
    val repeatCnt: Int = 1,
)

/** The aggregated preview output (`j_simulate_battle.php:565-582` Json::die payload, DB-free). */
data class BattleSimResult(
    /** The effective repeat count (1 when a fixed seed was supplied). */
    val repeatCnt: Int,
    val avgPhase: Double,
    val avgWar: Double,
    val attackerKilled: Double,
    val attackerDead: Double,
    val attackerMaxKilled: Int,
    val attackerMinKilled: Int,
    val attackerMaxDead: Int,
    val attackerMinDead: Int,
    val attackerSkills: Map<String, Double>,
    val defendersSkills: List<Map<String, Double>>,
    /** The LAST replay's mutated attacker working unit (the snapshot delta source — NOT flushed). */
    val attacker: WarUnitGeneral,
    /** The LAST replay's mutated defender-city working unit (NOT flushed). */
    val city: WarUnitCity,
)

class BattleSimPreview(
    private val pipeline: GeneralActionPipeline,
    /** Builds the F3 hooks for a replay; the golden harness injects a recording variant. Default = NOOP. */
    private val hooksFactory: () -> WarBattleHooks = { WarBattleHooks.NOOP },
    /** PHP `bin2hex(random_bytes(16))` — the per-repeat Monte-Carlo seed source (injectable for tests). */
    private val randomSeedFn: () -> String = ::secureRandomSeed,
) {

    /**
     * PHP `simulateBattle` (`:366-476`), DB-free. REUSES [processWar] so the draw stream is identical to a
     * real battle. A non-null [BattleSimInput.warSeed] forces a single deterministic replay; a null seed
     * Monte-Carlos over `repeatCnt` repeats, each with a fresh [randomSeedFn] seed.
     */
    fun simulateBattle(input: BattleSimInput): BattleSimResult {
        validate(input)

        val repeatCnt = if (input.warSeed != null) 1 else input.repeatCnt.coerceIn(1, 1000)

        var avgPhase = 0.0
        var avgWar = 0.0
        var attackerKilled = 0.0
        var attackerDead = 0.0
        var maxKilled = 0
        var minKilled = Int.MAX_VALUE
        var maxDead = 0
        var minDead = Int.MAX_VALUE
        val attackerSkills = LinkedHashMap<String, Double>()
        val defendersSkills = mutableListOf<MutableMap<String, Double>>()

        var lastResult: ProcessWarResult? = null

        for (repeatIdx in 0 until repeatCnt) {
            val seed = input.warSeed ?: randomSeedFn()
            val (result, defenderUnits) = runOneBattle(input, seed)
            lastResult = result

            val phase = result.attacker.getPhase()
            avgPhase += phase / repeatCnt.toDouble()

            val killed = result.attacker.getKilled()
            val dead = result.attacker.getDead()
            attackerKilled += killed / repeatCnt.toDouble()
            attackerDead += dead / repeatCnt.toDouble()
            maxKilled = maxOf(maxKilled, killed)
            minKilled = minOf(minKilled, killed)
            maxDead = maxOf(maxDead, dead)
            minDead = minOf(minDead, dead)

            avgWar += defenderUnits.size / repeatCnt.toDouble()

            for ((skill, cnt) in result.attacker.getActivatedSkillLog()) {
                attackerSkills[skill] = (attackerSkills[skill] ?: 0.0) + cnt / repeatCnt.toDouble()
            }
            defenderUnits.forEachIndexed { idx, unit ->
                while (idx >= defendersSkills.size) defendersSkills.add(LinkedHashMap())
                val skills = defendersSkills[idx]
                for ((skill, cnt) in unit.getActivatedSkillLog()) {
                    skills[skill] = (skills[skill] ?: 0.0) + cnt / repeatCnt.toDouble()
                }
            }
        }

        val result = lastResult!!
        return BattleSimResult(
            repeatCnt = repeatCnt,
            avgPhase = avgPhase,
            avgWar = avgWar,
            attackerKilled = attackerKilled,
            attackerDead = attackerDead,
            attackerMaxKilled = maxKilled,
            attackerMinKilled = if (minKilled == Int.MAX_VALUE) 0 else minKilled,
            attackerMaxDead = maxDead,
            attackerMinDead = if (minDead == Int.MAX_VALUE) 0 else minDead,
            attackerSkills = attackerSkills,
            defendersSkills = defendersSkills,
            attacker = result.attacker,
            city = result.city,
        )
    }

    /**
     * PHP `'reorder'` action (`:321-338`) — `usort` DESC by [extractBattleOrder]. Uses [NoRNG] in PHP, i.e.
     * NO rng draw. Returns the ordered defender ids. The throwaway attacker/defender build draws nothing.
     */
    fun reorder(input: BattleSimInput): List<Int> {
        validate(input)
        // a throwaway rng (never drawn from — reorder consumes ZERO rng, PHP NoRNG).
        val units = buildUnitsOnly(input)
        return orderDefenders(units.defenders, units.attacker, city = null)
            .filterIsInstance<WarUnitGeneral>()
            .map { it.no }
    }

    /**
     * Build the working units (+ validate) WITHOUT running the battle — the draw-neutral construction pass.
     * Used by [reorder] and by the construction-is-draw-neutral test. Consumes ZERO rng.
     */
    fun buildUnitsOnly(input: BattleSimInput): BuiltUnits {
        validate(input)
        // a fresh, NEVER-DRAWN-FROM rng — construction must not touch it.
        val rng = opensamguk.common.rng.RandUtil(opensamguk.common.rng.LiteHashDrbg("0".repeat(32)))
        val attacker = WarUnitGeneral(
            rng = rng, state = WarUnitGeneralState(input.attacker.toGeneral()), pipeline = pipeline,
            crewType = input.attackerCrewType, tech = input.attackerNation.tech.toInt(),
            isAttacker = true, cityLevel = input.attackerCity.level, isCapital = false,
        )
        val defenders = input.defenders.map { d ->
            WarUnitGeneral(
                rng = rng, state = WarUnitGeneralState(d.toGeneral()), pipeline = pipeline,
                crewType = input.defenderCrewType, tech = input.defenderNation.tech.toInt(),
                isAttacker = false, cityLevel = input.defenderCity.level, isCapital = false,
            )
        }
        val city = WarUnitCity(
            rng = rng, state = WarUnitCityState(input.defenderCity),
            year = input.year, startYear = input.startYear,
        )
        return BuiltUnits(attacker, defenders, city)
    }

    /** The throwaway working units a build-only pass produces. */
    data class BuiltUnits(
        val attacker: WarUnitGeneral,
        val defenders: List<WarUnitGeneral>,
        val city: WarUnitCity,
    )

    // --- one replay, delegating to the SHARED engine path ([processWar]) --------------------------------

    private fun runOneBattle(input: BattleSimInput, seed: String): Pair<ProcessWarResult, List<WarUnitGeneral>> {
        // the defender working units captured by the runInner closure (PHP $battleResult; logger.rollback'd).
        val defenderUnits = mutableListOf<WarUnitGeneral>()
        val hooks = hooksFactory()

        val result = processWar(
            warSeed = seed,
            attackerGeneral = input.attacker.toGeneral(),
            attackerNation = input.attackerNation,
            defenderCity = input.defenderCity,
            defenderCandidates = input.defenders.map { it.toGeneral() },
            attackerCrewType = input.attackerCrewType,
            attackerTech = input.attackerNation.tech.toInt(),
            defenderCrewType = input.defenderCrewType,
            defenderTech = input.defenderNation.tech.toInt(),
            pipeline = pipeline,
            year = input.year,
            startYear = input.startYear,
            env = ProcessWarEnv(
                defenderNationTech = input.defenderNation.tech,
                defenderNationRice = input.defenderNation.rice,
                defenderNationCapitalCityId = input.defenderNation.capitalCityId ?: 0,
                attackerCityId = input.attackerCity.id,
                defenderCityId = input.defenderCity.id,
            ),
            hooks = hooks,
            attackerCityLevel = input.attackerCity.level,
            attackerIsCapital = false,
            runInner = { rng, attacker, gnd, city ->
                // capture each defender as it is pulled (the PHP $battleResult append on rollback).
                val capturingGnd: (WarUnit?, Boolean) -> WarUnit? = { prev, reqNext ->
                    (prev as? WarUnitGeneral)?.let { defenderUnits.add(it) }
                    gnd(prev, reqNext)
                }
                processWarNG(rng, attacker, capturingGnd, city, hooks)
            },
        )
        return result to defenderUnits
    }

    // --- input validation (PHP $generalCheck / $cityCheck / $nationCheck) -------------------------------

    private fun validate(input: BattleSimInput) {
        // 이 프리뷰 실행 전체가 한 unitSet 안에서만 돈다 — attackerCrewType 이 속한 세트를 기준으로 삼는다.
        val unitSet = UnitCatalog.setOf(input.attackerCrewType.id) ?: UnitCatalog.CHE
        validateGeneral(input.attacker, "출병자", input.attackerNation, unitSet)
        input.defenders.forEachIndexed { idx, d -> validateGeneral(d, "수비자${idx + 1}", input.defenderNation, unitSet) }
        require(input.month in 1..12) { "month out of range [1,12]" }
        require(input.year >= 0) { "year must be >= 0" }
        require(input.repeatCnt in 1..1000) { "repeatCnt out of range [1,1000]" }
    }

    private fun validateGeneral(g: BattleSimGeneralInput, who: String, nation: Nation, unitSet: String) {
        require(g.no >= 1) { "[$who] no must be >= 1" }
        require(g.crew >= 0) { "[$who] crew must be >= 0" }
        require(g.train in 40..GameConst.maxTrainByWar) { "[$who] train ${g.train} out of [40,${GameConst.maxTrainByWar}]" }
        require(g.atmos in 40..GameConst.maxAtmosByWar) { "[$who] atmos ${g.atmos} out of [40,${GameConst.maxAtmosByWar}]" }
        require(g.injury in 0..80) { "[$who] injury ${g.injury} out of [0,80]" }
        require(g.explevel in 0..300) { "[$who] explevel ${g.explevel} out of [0,300]" }
        require(g.officerLevel in 1..12) { "[$who] officer_level ${g.officerLevel} out of [1,12]" }
        require(g.experience >= 0) { "[$who] experience must be >= 0" }
        require(g.gold >= 0) { "[$who] gold must be >= 0" }
        require(g.rice >= 0) { "[$who] rice must be >= 0" }
        // che 대역은 PHP GameUnitConst::byID() 던지는 원문 그대로 — sub-1000 id 는 별개 오류다.
        require(unitSet != UnitCatalog.CHE || g.crewTypeId >= 1000) { "적절한 id는 1000이상이어야합니다:${g.crewTypeId}" }
        require(UnitCatalog.byId(unitSet, g.crewTypeId) != null) { "[$who] unknown crewtype ${g.crewTypeId}" }
        require(isAllowedItem("horse", g.horse)) { "[$who] horse ${g.horse} not in allowlist" }
        require(isAllowedItem("weapon", g.weapon)) { "[$who] weapon ${g.weapon} not in allowlist" }
        require(isAllowedItem("book", g.book)) { "[$who] book ${g.book} not in allowlist" }
        require(isAllowedItem("item", g.item)) { "[$who] item ${g.item} not in allowlist" }
        validateInheritBuff(g, who)
    }

    /** PHP `$inheritBuffCheck` — every WAR / OPPOSE_WAR inheritBuff fold is bounded to [0,5]. */
    private fun validateInheritBuff(g: BattleSimGeneralInput, who: String) {
        for (key in INHERIT_BUFF_KEYS) {
            val v = (g.meta[key] as? Number)?.toDouble() ?: continue
            require(v in 0.0..5.0) { "[$who] inheritBuff $key $v out of [0,5]" }
        }
    }

    companion object {
        /** PHP `$inheritBuffCheck` keys (TriggerInheritBuff WAR_ and OPPOSE_WAR_ folds). */
        val INHERIT_BUFF_KEYS: List<String> = listOf(
            "WAR_AVOID_RATIO", "WAR_CRITICAL_RATIO", "WAR_MAGIC_TRIAL_PROB",
            "OPPOSE_WAR_AVOID_RATIO", "OPPOSE_WAR_CRITICAL_RATIO", "OPPOSE_WAR_MAGIC_TRIAL_PROB",
        )

        private val secureRandom = SecureRandom()

        /** PHP `bin2hex(random_bytes(16))` — 32 lowercase hex chars. */
        fun secureRandomSeed(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * PHP `array_merge(array_keys(GameConst::$allItems[$slot]), ['None'])` — the equip allowlist gate.
         *
         * The logic module carries no GameConst static item catalog (the P2 [ItemRegistry] is a declarative
         * parser, not a closed key list), so the gate is faithful to the PHP **convention** rather than a
         * frozen key set: every catalogued equip code is a `che_<category>_<NN>_<name>` class-name token
         * (e.g. `che_명마_01_노기`) — see [opensamguk.logic.items] ItemModules token parsing. 'None' is always
         * allowed; any other code must match that token shape. Free text ("NotAnItem", "weapon with space")
         * is rejected — exactly what the PHP `array_keys(...)` membership check would reject for a non-key.
         */
        fun isAllowedItem(slot: String, code: String): Boolean {
            if (code == "None") return true
            return ITEM_CODE_REGEX.matches(code)
        }

        /** `che_<category>_<digits>_<name>` — the declarative item class-name convention (no whitespace). */
        private val ITEM_CODE_REGEX = Regex("""^che_[^\s_]+_\d+_[^\s_]+$""")
    }
}
