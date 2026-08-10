package opensamguk.logic.ai

import opensamguk.common.constants.GameUnitConst
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.util.phpRound

/**
 * F-POLICY — `AutorunNationPolicy`, a faithful port of
 * `legacy/devsam-core/hwe/sammo/AutorunNationPolicy.php` (291 lines, GRAND TRUTH, read in full; R-NATIONPOL).
 *
 * The nation-AI 4-layer policy merge. The merged [priority] list ORDER **is** the `do<한글>` dispatch
 * order in `chooseNationTurn`, which is the log order AND the per-decision RNG draw order. This class
 * itself makes ZERO draws — it only computes the priority spine + the `can*` gates + the value-keys the
 * dispatcher reads.
 *
 * PURE: no Spring, no DB. The PHP ctor takes `GeneralBase $general, $aiOptions, ?array $nationPolicy,
 * ?array $serverPolicy, array $nation, array $env`, but its body only dereferences
 * `$general->getNPCType()` (:259), `$aiOptions['chief']` key-existence (:263), `$nation['tech']`
 * (:230/231/242/243), and `$env['develcost']` (:224) — so this port takes only what it reads.
 *
 * **B1 / decision #5 (INVERTED from the old "dead-KV" draft — source-verified R-NATIONPOL §1):** the KV
 * `priority` override is LIVE for the nation policy too, but via a DIFFERENT mechanism than the general
 * policy. The nation ctor does a **whole-array replace** at construction (NO per-item filter, :197-199
 * server / :212-214 nation), then `if(!$this->priority) priority=defaultPriority` (:218-220). The
 * per-name validation happens at the CONSUMER loop in `chooseNationTurn` via
 * `property_exists($this->nationPolicy,'can'.$name)` (:3663-3666) — a typo/unknown name has no
 * `can<name>` prop → dropped there. The nation KV (applied SECOND) wins over the server KV. The
 * [hasCanFlagFor] predicate models that consumer-loop validity check (consumed by F-DISPATCH).
 *
 * **Derived defaults (R-NATIONPOL §3):** under STRICT `=== 0` guards, in dependency ORDER (#3 derives
 * before #4/#5). `Util::round(x,-2)` = half-AWAY-from-zero → [phpRound]`(x,-2)` (NEVER `Math.round`)
 * EXCEPT `reqNPCDevelGold` which is a RAW `develcost*30` (no round, :223-225). `costWithTech`/`riceWithTech`
 * are FLOAT (no round, H-HELPERS §4): `unit.cost|rice * getTechCost(tech) * crew / 100`.
 *
 * The 4 layers (:182-289):
 *  1. defaultPolicy — init every value-key (:184-186).
 *  2. serverPolicy (global KV) — per-key `values` merge (skip unknown) + whole-array `priority` replace (:188-200).
 *  3. nationPolicy (nation KV) — same, runs AFTER #2 so it WINS (:203-215).
 *  4. `if(!priority) priority=defaultPriority` (:218-220) → derived defaults (:223-257) → chief-gate (:259-288).
 */
class AutorunNationPolicy(
    npcType: Int,
    tech: Int,
    develcost: Int,
    aiOptions: Map<String, Any?>? = null,
    nationPolicy: Map<String, Any?>? = null,
    serverPolicy: Map<String, Any?>? = null,
) {
    // --- can* action gates (AutorunNationPolicy.php:88-113); all default true ---
    // NOT `private set` — mirrors AutorunGeneralPolicy: the chief-gate (init) sets them, and a test
    // / the consumer loop may flip one to model the live `can<name>` flag (guard-B).
    var can부대전방발령: Boolean = true
    var can부대후방발령: Boolean = true
    var can부대구출발령: Boolean = true

    var can부대유저장후방발령: Boolean = true
    var can유저장후방발령: Boolean = true
    var can유저장전방발령: Boolean = true
    var can유저장구출발령: Boolean = true
    var can유저장내정발령: Boolean = true

    var canNPC후방발령: Boolean = true
    var canNPC전방발령: Boolean = true
    var canNPC구출발령: Boolean = true
    var canNPC내정발령: Boolean = true

    var can유저장긴급포상: Boolean = true
    var can유저장포상: Boolean = true
    // can유저장몰수 commented out (:105) — fully dead.

    var canNPC긴급포상: Boolean = true
    var canNPC포상: Boolean = true
    var canNPC몰수: Boolean = true

    var can불가침제의: Boolean = true
    var can선전포고: Boolean = true
    var can천도: Boolean = true

    // --- value-keys (AutorunNationPolicy.php:116-180); init from $defaultPolicy (:152-180) ---
    var reqNationGold: Int = 10000; private set
    var reqNationRice: Int = 12000; private set
    var combatForce: Map<Any?, Any?> = emptyMap(); private set
    var supportForce: Map<Any?, Any?> = emptyMap(); private set
    var developForce: Map<Any?, Any?> = emptyMap(); private set
    var reqHumanWarUrgentGold: Int = 0; private set
    var reqHumanWarUrgentRice: Int = 0; private set
    var reqHumanWarRecommandGold: Int = 0; private set
    var reqHumanWarRecommandRice: Int = 0; private set
    var reqHumanDevelGold: Int = 10000; private set
    var reqHumanDevelRice: Int = 10000; private set
    var reqNPCWarGold: Int = 0; private set
    var reqNPCWarRice: Int = 0; private set
    var reqNPCDevelGold: Int = 0; private set
    var reqNPCDevelRice: Int = 500; private set

    var minimumResourceActionAmount: Int = 1000; private set
    var maximumResourceActionAmount: Int = 10000; private set

    var minNPCWarLeadership: Int = 40; private set
    var minWarCrew: Int = 1500; private set

    var minNPCRecruitCityPopulation: Int = 50000; private set
    var safeRecruitCityPopulationRatio: Double = 0.5; private set
    var properWarTrainAtmos: Int = 90; private set

    var cureThreshold: Int = 10; private set

    /** The merged dispatch/log/draw order spine (AutorunNationPolicy.php:86, set :218-220 fallback). */
    var priority: List<String> = DEFAULT_PRIORITY
        private set

    init {
        // Layer 1: defaultPolicy (:184-186) — fields are already initialized to the defaultPolicy values above.

        // Layer 2: serverPolicy (global) — values merge (:189-195) then whole-array priority replace (:197-199).
        applyValuesMerge(serverPolicy)
        applyPriorityReplace(serverPolicy)
        // Layer 3: nationPolicy (nation) — same, runs AFTER #2 → wins (:204-214).
        applyValuesMerge(nationPolicy)
        applyPriorityReplace(nationPolicy)

        // :218-220 — empty/falsy override ⇒ default.
        if (priority.isEmpty()) {
            priority = DEFAULT_PRIORITY
        }

        // --- Derived defaults, STRICT `=== 0` guards, in ORDER (R-NATIONPOL §3) ---
        // #1 reqNPCDevelGold (:223-225) — RAW multiply, NO round.
        if (reqNPCDevelGold == 0) {
            reqNPCDevelGold = develcost * 30
        }

        val defaultCrew = UnitSetTable.byId(GameUnitConst.DEFAULT_CREWTYPE)!!

        // #2 reqNPCWar gold/rice (:228-238) — crew = defaultStatNPCMax*100; *4; PhpRound(-2).
        if (reqNPCWarGold == 0 || reqNPCWarRice == 0) {
            val npcCrew = DEFAULT_STAT_NPC_MAX * 100
            val reqGold = defaultCrew.costWithTech(tech, npcCrew)
            val reqRice = defaultCrew.riceWithTech(tech, npcCrew)
            if (reqNPCWarGold == 0) {
                reqNPCWarGold = phpRound(reqGold * 4, -2)
            }
            if (reqNPCWarRice == 0) {
                reqNPCWarRice = phpRound(reqRice * 4, -2)
            }
        }

        // #3 reqHumanWarUrgent gold/rice (:240-250) — crew = defaultStatMax*100 (NOT NPC); *3*2; PhpRound(-2).
        if (reqHumanWarUrgentGold == 0 || reqHumanWarUrgentRice == 0) {
            val humanCrew = DEFAULT_STAT_MAX * 100
            val reqGold = defaultCrew.costWithTech(tech, humanCrew)
            val reqRice = defaultCrew.riceWithTech(tech, humanCrew)
            if (reqHumanWarUrgentGold == 0) {
                reqHumanWarUrgentGold = phpRound(reqGold * 3 * 2, -2)
            }
            if (reqHumanWarUrgentRice == 0) {
                reqHumanWarUrgentRice = phpRound(reqRice * 3 * 2, -2)
            }
        }

        // #4/#5 reqHumanWarRecommand gold/rice (:252-257) — DEPENDS on #3 (urgent already defaulted); *2; PhpRound(-2).
        if (reqHumanWarRecommandGold == 0) {
            reqHumanWarRecommandGold = phpRound(reqHumanWarUrgentGold * 2.0, -2)
        }
        if (reqHumanWarRecommandRice == 0) {
            reqHumanWarRecommandRice = phpRound(reqHumanWarUrgentRice * 2.0, -2)
        }

        // --- The chief-gate (:259-288) ---
        // PHP: `if(getNPCType()>=2){ return; }` (:259-261) then the non-chief disable block. Modelled as
        // a guard so the disable block runs ONLY for npcType<2 (init blocks forbid an early `return`).
        // npcType>=2 (pure NPC): ALL can* stay true.
        // npcType<2 & NOT a chief: disable the 18 flags (R-NATIONPOL §4). 부대구출발령/불가침제의 SURVIVE.
        if (npcType < 2 && (aiOptions == null || !aiOptions.containsKey("chief"))) {
            can부대전방발령 = false
            can부대후방발령 = false

            can부대유저장후방발령 = false
            can유저장후방발령 = false
            can유저장전방발령 = false
            can유저장구출발령 = false
            can유저장내정발령 = false

            canNPC후방발령 = false
            canNPC전방발령 = false
            canNPC구출발령 = false
            canNPC내정발령 = false

            can유저장긴급포상 = false
            can유저장포상 = false

            canNPC긴급포상 = false
            canNPC포상 = false
            canNPC몰수 = false

            can선전포고 = false
            can천도 = false
        }
    }

    /**
     * The `chooseNationTurn` consumer-loop validity check (`property_exists($this,'can'.$name)`, :3663) —
     * a valid bare-action name has a matching `can<name>` prop; a typo/unknown name does not, so it is
     * dropped at the consumer loop. F-DISPATCH consumes this predicate. Mirrors the live can* prop set.
     */
    fun hasCanFlagFor(actionName: String): Boolean = "can$actionName" in CAN_FLAG_NAMES

    /**
     * The `chooseNationTurn` (`:3663-3669`) / `chooseInstantNationTurn` (`:3698`) consumer-loop two-step
     * `can<name>` resolution, mirroring `AutorunGeneralPolicy.canFor`:
     *  - `null`  ⇒ guard-A: NO `can<name>` property (`property_exists` false at :3663) → the loop drops the
     *    name with `trigger_error(...)+continue`. A typo/unknown bare-action name lands here.
     *  - `false` ⇒ guard-B: the live `can<name>` flag is false (`!$this->{'can'.$name}` at :3667) → continue.
     *  - `true`  ⇒ the flag is on; the loop proceeds to guard-C / the `do{X}` dispatch.
     *
     * The 유저장몰수 name has NO `can` prop (commented out :105) → `null` (correctly dropped at guard-A).
     */
    fun canFor(actionName: String): Boolean? = when (actionName) {
        "부대전방발령" -> can부대전방발령
        "부대후방발령" -> can부대후방발령
        "부대구출발령" -> can부대구출발령
        "부대유저장후방발령" -> can부대유저장후방발령
        "유저장후방발령" -> can유저장후방발령
        "유저장전방발령" -> can유저장전방발령
        "유저장구출발령" -> can유저장구출발령
        "유저장내정발령" -> can유저장내정발령
        "NPC후방발령" -> canNPC후방발령
        "NPC전방발령" -> canNPC전방발령
        "NPC구출발령" -> canNPC구출발령
        "NPC내정발령" -> canNPC내정발령
        "유저장긴급포상" -> can유저장긴급포상
        "유저장포상" -> can유저장포상
        "NPC긴급포상" -> canNPC긴급포상
        "NPC포상" -> canNPC포상
        "NPC몰수" -> canNPC몰수
        "불가침제의" -> can불가침제의
        "선전포고" -> can선전포고
        "천도" -> can천도
        else -> null // guard-A: no `can<name>` property → the consumer loop drops it with a notice
    }

    /**
     * The `values` per-key merge (AutorunNationPolicy.php:189-195 / :204-210 — byte-identical blocks).
     * `property_exists($this,$policy)` skips unknown keys (E_USER_NOTICE + continue in PHP). The nation
     * `values` (applied SECOND) wins. Only the `$defaultPolicy` numeric/map value-keys are settable here.
     */
    private fun applyValuesMerge(policy: Map<String, Any?>?) {
        if (policy == null) return
        @Suppress("UNCHECKED_CAST")
        val values = (policy["values"] as? Map<String, Any?>) ?: return
        for ((key, value) in values) {
            setValueKey(key, value)
        }
    }

    /** Set one declared value-key; an unknown key is skipped (PHP `property_exists` false → continue). */
    private fun setValueKey(key: String, value: Any?) {
        when (key) {
            "reqNationGold" -> reqNationGold = asInt(value)
            "reqNationRice" -> reqNationRice = asInt(value)
            "CombatForce" -> combatForce = asMap(value)
            "SupportForce" -> supportForce = asMap(value)
            "DevelopForce" -> developForce = asMap(value)
            "reqHumanWarUrgentGold" -> reqHumanWarUrgentGold = asInt(value)
            "reqHumanWarUrgentRice" -> reqHumanWarUrgentRice = asInt(value)
            "reqHumanWarRecommandGold" -> reqHumanWarRecommandGold = asInt(value)
            "reqHumanWarRecommandRice" -> reqHumanWarRecommandRice = asInt(value)
            "reqHumanDevelGold" -> reqHumanDevelGold = asInt(value)
            "reqHumanDevelRice" -> reqHumanDevelRice = asInt(value)
            "reqNPCWarGold" -> reqNPCWarGold = asInt(value)
            "reqNPCWarRice" -> reqNPCWarRice = asInt(value)
            "reqNPCDevelGold" -> reqNPCDevelGold = asInt(value)
            "reqNPCDevelRice" -> reqNPCDevelRice = asInt(value)
            "minimumResourceActionAmount" -> minimumResourceActionAmount = asInt(value)
            "maximumResourceActionAmount" -> maximumResourceActionAmount = asInt(value)
            "minNPCWarLeadership" -> minNPCWarLeadership = asInt(value)
            "minWarCrew" -> minWarCrew = asInt(value)
            "minNPCRecruitCityPopulation" -> minNPCRecruitCityPopulation = asInt(value)
            "safeRecruitCityPopulationRatio" -> safeRecruitCityPopulationRatio = asDouble(value)
            "properWarTrainAtmos" -> properWarTrainAtmos = asInt(value)
            "cureThreshold" -> cureThreshold = asInt(value)
            else -> Unit // unknown key → skip (PHP property_exists false + E_USER_NOTICE + continue)
        }
    }

    /**
     * The LIVE whole-array `priority` replace (AutorunNationPolicy.php:197-199 / :212-214). NO per-item
     * filtering at construction (the typo/unknown drop is at the consumer loop — decision #5, R-NATIONPOL §1).
     * The nation block (applied SECOND) wins over the server block.
     */
    private fun applyPriorityReplace(policy: Map<String, Any?>?) {
        if (policy == null || !policy.containsKey("priority")) return
        @Suppress("UNCHECKED_CAST")
        val items = (policy["priority"] as? List<String>) ?: return
        priority = items
    }

    companion object {
        /**
         * PHP `AutorunNationPolicy::$defaultPolicy`
         * (`legacy/devsam-core/hwe/sammo/AutorunNationPolicy.php:152-180`).
         *
         * This is the pre-derived "초깃값으로" source. Do not add global env keys here; keys absent from
         * PHP (for example `autorun_user`) are not nation policy fields.
         */
        val DEFAULT_POLICY: Map<String, Any?> = linkedMapOf(
            "reqNationGold" to 10000,
            "reqNationRice" to 12000,
            "CombatForce" to emptyList<Any>(),
            "SupportForce" to emptyList<Any>(),
            "DevelopForce" to emptyList<Any>(),
            "reqHumanWarUrgentGold" to 0,
            "reqHumanWarUrgentRice" to 0,
            "reqHumanWarRecommandGold" to 0,
            "reqHumanWarRecommandRice" to 0,
            "reqHumanDevelGold" to 10000,
            "reqHumanDevelRice" to 10000,
            "reqNPCWarGold" to 0,
            "reqNPCWarRice" to 0,
            "reqNPCDevelGold" to 0,
            "reqNPCDevelRice" to 500,
            "minimumResourceActionAmount" to 1000,
            "maximumResourceActionAmount" to 10000,
            "minNPCWarLeadership" to 40,
            "minWarCrew" to 1500,
            "minNPCRecruitCityPopulation" to 50000,
            "safeRecruitCityPopulationRatio" to 0.5,
            "properWarTrainAtmos" to 90,
            "cureThreshold" to 10,
        )

        /**
         * `$defaultPriority` (AutorunNationPolicy.php:38-68) — the EXACT ordered 20-list.
         * `//'유저장몰수'` (:53) is commented out → NOT in the array. THIS ORDER is the dispatch/log/draw spine.
         */
        val DEFAULT_PRIORITY: List<String> = listOf(
            "불가침제의",
            "선전포고",
            "천도",
            "유저장긴급포상",
            "부대전방발령",
            "유저장구출발령",
            "유저장후방발령",
            "부대유저장후방발령",
            "유저장전방발령",
            "유저장포상",
            // '유저장몰수' (:53) commented out
            "부대구출발령",
            "부대후방발령",
            "NPC긴급포상",
            "NPC구출발령",
            "NPC후방발령",
            "NPC포상",
            "NPC전방발령",
            "유저장내정발령",
            "NPC내정발령",
            "NPC몰수",
        )

        /**
         * `$availableInstantTurn` (AutorunNationPolicy.php:71-84) — the 12-key key-existence map (all
         * `=>true`; comment :70 "순서는 중요하지 않음"). The 8 EXCLUDED priority entries are the 군주
         * actions (불가침제의/선전포고/천도) + the 부대* (전방/유저장후방/구출/후방) + NPC몰수 (R-NATIONPOL §2).
         */
        val AVAILABLE_INSTANT_TURN: Set<String> = linkedSetOf(
            "유저장긴급포상",
            "유저장구출발령",
            "유저장후방발령",
            "유저장전방발령",
            "유저장내정발령",
            "유저장포상",
            "NPC긴급포상",
            "NPC구출발령",
            "NPC후방발령",
            "NPC내정발령",
            "NPC포상",
            "NPC전방발령",
        )

        /** `GameConst::$defaultStatNPCMax` (d_setting/GameConst.php:9) — the NPC-war crew scale base. */
        const val DEFAULT_STAT_NPC_MAX: Int = 75

        /** `GameConst::$defaultStatMax` (d_setting/GameConst.php:7) — the human-war crew scale base. */
        const val DEFAULT_STAT_MAX: Int = 80

        /**
         * The live `can<name>` prop set (AutorunNationPolicy.php:88-113) the consumer-loop
         * `property_exists($this,'can'.$name)` (:3663) checks. 유저장몰수 is commented out → absent.
         */
        private val CAN_FLAG_NAMES: Set<String> = setOf(
            "can부대전방발령", "can부대후방발령", "can부대구출발령",
            "can부대유저장후방발령", "can유저장후방발령", "can유저장전방발령", "can유저장구출발령", "can유저장내정발령",
            "canNPC후방발령", "canNPC전방발령", "canNPC구출발령", "canNPC내정발령",
            "can유저장긴급포상", "can유저장포상",
            "canNPC긴급포상", "canNPC포상", "canNPC몰수",
            "can불가침제의", "can선전포고", "can천도",
        )
    }
}

/** PHP loose-int coercion for `values` map entries (numbers arrive as Int/Long/Double/String). */
private fun asInt(value: Any?): Int = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> value.toInt()
    is String -> value.toInt()
    else -> error("value-key expected a numeric: $value")
}

private fun asDouble(value: Any?): Double = when (value) {
    is Double -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is String -> value.toDouble()
    else -> error("value-key expected a numeric: $value")
}

@Suppress("UNCHECKED_CAST")
private fun asMap(value: Any?): Map<Any?, Any?> = (value as? Map<Any?, Any?>) ?: emptyMap()
