package opensamguk.logic.ai

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant

enum class ExternalSqlRandBranch {
    SEONYANG_DEST_GENERAL,
    ORANKAE_RULER_NATION,
}

fun interface ExternalSqlRandSelector {
    fun select(
        branch: ExternalSqlRandBranch,
        actorGeneralId: Int,
        year: Int,
        month: Int,
        candidateIds: List<Int>,
    ): Int?
}

/**
 * F-DISPATCH consumer seam — `GeneralAiContext`, the per-general AI INPUT bundle every world-driven
 * `do<한글>` body reads to assemble its candidate set, pull its draws, gate its emit, and route its
 * meta-KV deltas. It is the faithful Kotlin stand-in for the PHP `GeneralAI` object's per-instance
 * state (`legacy/devsam-core/hwe/sammo/GeneralAI.php`, GRAND TRUTH): the SOLE `"GeneralAI"` [rng]
 * threaded by reference, the derived [instance]/[world] facades, the merged policies, the env, the
 * acting general's identity, the F-BRIDGE gate, the meta-KV delta sink, and the per-general accessor
 * lambdas for the scalars that are NOT plain logic-model columns (turn-time / `last발령` aux /
 * `onCalcDomestic('징집인구')` pipeline score / `getLeadership(false,…)` no-injury flavor).
 *
 * **This context is the SHAPE every later body task reuses** — it is intentionally minimal and stable:
 * each family's `bodies(ctx)` builder closes over ONE [GeneralAiContext] and returns the
 * `Map<String, (LastTurn?) -> ChosenCommand?>` the [GeneralAiFactory] registers into the dispatch. The
 * body itself NEVER edits [GeneralAI]'s loop, the policies, the bridge, or the registry — it only READS
 * this context (READ-ONLY over GAME ENTITIES) and emits `(actionCode, RAW args)`; any side-effect routes
 * through [recordGeneralKv] (decision #12 / M4).
 *
 * PURE `:logic` — no Spring, no DB. The engine adapter (F-SEAM `AiTurnAdapter`) materialises every field
 * over the live in-memory world; tests build it directly over a deterministic fixture world.
 *
 * @property rng the SOLE per-general-per-decision [RandUtil] (F-SEED `AiSeed.rng`), threaded BY REFERENCE
 *   through the whole decision; NEVER re-seeded. The draw ORDER/COUNT/METHOD off this stream is the #1
 *   parity target (decision #1).
 * @property instance the derived [AiInstanceState] — `nation`/`dipState`/`maxResourceActionAmount`/
 *   `genType`. The bodies read `instance.nation.capital` (PHP `$this->nation['capital']`) and
 *   `instance.dipState` (the d평화/d선포/d직전/d전쟁 branches).
 * @property world the read-only [AiWorldView] categorize/derive facade — `frontCities`/`supplyCities`/
 *   `backupCities`/`nationCities` (PK-ascending buckets) + `troopLeaders`/`userWarGenerals`/`lostGenerals`/
 *   `npcWarGenerals`/`userCivilGenerals`/`npcCivilGenerals` (the 9 general buckets) + `warRoute`.
 * @property generalPolicy the merged general policy (F-POLICY) — the bodies read its can-flags.
 * @property nationPolicy the merged nation policy (F-POLICY) — the bodies read `combatForce`/`supportForce`/
 *   `safeRecruitCityPopulationRatio`/`minWarCrew`/`properWarTrainAtmos`/`minNPCRecruitCityPopulation`.
 * @property env the game env (`year`/`month`/`turnterm`-equivalent via [turnTerm]).
 * @property turnTerm the env `turnterm` (`cutTurn` floor + `calcRecentWarTurn` divisor).
 * @property selfGeneralId the acting general's id (PHP `$this->general->getID()`) — the no-self-leak
 *   exclusion target in the 부대유저장후방/유저장후방/NPC후방 candidate loops.
 * @property selfCityId the acting general's city id (PHP `$this->city['city']`) — excluded as a dest in
 *   the 유저장후방/NPC후방 recruitable-city loops (`:705/1006`).
 * @property candidateAllowed the F-BRIDGE gate `(actionCode, rawArgs) -> Boolean` (`hasFullConditionMet`,
 *   PHP `:392/481/…`) — argTest THEN `evaluateConstraints(FULL)`. The bodies call this on the emit.
 * @property recordGeneralKv the meta-KV delta sink `(generalId, key, value) -> Unit` (decision #12).
 * @property chiefTurnTime the acting chief's `cutTurn(getTurnTime(), turnterm)` formatted string (PHP
 *   `:305/408`) — the lexicographic compare base for the one-deploy-per-turn skip ([NationDeployFamily.oneDeployPerTurnSkip]).
 * @property turnTimeOf a general's `cutTurn(getTurnTime(), turnterm)` formatted string (PHP `:322/431`) —
 *   the troop-leader / general turn-bucket compare value. Engine wall-clock math (NOT a logic column).
 * @property last발령Of a general's `getAuxVar('last발령')` (PHP `:320/429`) — null/0 when never deployed.
 * @property recruitPopScoreOf a general's `onCalcDomestic('징집인구','score',100)` pipeline score (PHP
 *   `:585/681/981`) — the `<= 1` recruit-viability gate. Pipeline-derived (NOT a logic column).
 * @property leadershipNoInjuryOf a general's `getLeadership(false,true,true,true)` (PHP `:697/997`) — the
 *   no-injury full-leadership flavor (G8) feeding the `minRecruitPop` formula. Defaults to the
 *   [AiGeneralView.fullLeadership] already on the bucket view.
 * @property unitCostWithTechOf a general's `getCrewTypeObj()->costWithTech(nation['tech'],
 *   Util::toInt(getLeadership(false)))` (PHP reward `:1260/1364/1460/1557`) — the per-general unit-cost base
 *   the 포상 reqMoney ladder multiplies by `100 * <method-mult>`. Pipeline/nation-tech-derived (NOT a logic
 *   column); the engine adapter threads `nation['tech']` + the crew-type table, tests build it directly.
 *   Default 0.0 (the non-reward bodies never read it).
 *
 * ## L-GENDOM (GenDomesticFamily) per-general scalars (defaulted; only the domestic bodies read them)
 * @property leadershipWithInjury the acting general's `$this->leadership` = `getLeadership()` WITH injury
 *   (PHP `:161/2120/2226/2260` — the weight numerator in do일반내정/do전쟁내정/do긴급내정). DISTINCT from
 *   [fullLeadership] (`getLeadership(false)`, no-injury). Default 0.0.
 * @property strengthWithInjury the acting general's `$this->strength` = `getStrength()` WITH injury (PHP
 *   `:162/2121/2261`) — the 무장 develop-command weight numerator in do일반내정/do전쟁내정. DISTINCT from [fullStrength].
 * @property intelWithInjury the acting general's `$this->intel` = `getIntel()` WITH injury (PHP `:163/2122/2262`)
 *   — the 지장 develop-command weight numerator in do일반내정/do전쟁내정. DISTINCT from [fullIntel].
 * @property fullLeadership the acting general's `$this->fullLeadership` = `getLeadership(false)` (PHP `:165`) —
 *   the `crew = fullLeadership * 100` recruit base (do징병 `:2611`) + the recruit-skip `remainPop` term (`:2505`).
 * @property nationTech the nation's `$nation['tech']` (PHP `:2190/2327`) — the `nextTech = tech%1000 + 1` 기술연구
 *   weight base. Default 0.
 * @property selfCrew the acting general's `getVar('crew')` (PHP `:2500`) — the do징병 war-floor early-return
 *   (`crew >= minWarCrew → null`, BEFORE any draw). Default 0 (the common recruit case).
 * @property fullStrength the acting general's `$this->fullStrength` = `getStrength(false)` (PHP `:166`) — the
 *   do징병 armType gate `fullStrength > fullIntel*0.9` (`:2545`).
 * @property fullIntel the acting general's `$this->fullIntel` = `getIntel(false)` (PHP `:167`) — the do징병
 *   armType gate `fullIntel > fullStrength*0.9` (`:2550`).
 * @property selfCity the acting general's city as the AI reads it (PHP `$this->city`) — trust/pop/pop_max +
 *   the dev-bearing row feeding [cityDevelRate]. Default null (재야/lost — the domestic bodies then null-guard).
 * @property cityDevelRate the acting city's `Util::squeezeFromArray(calcCityDevelRate(city), 0)` (PHP
 *   `:2131/2275/3965-3976`) — the 7 develop ratios keyed `trust/pop/agri/comm/secu/def/wall` (index-0 of the
 *   `[ratio, typeFlag]` pairs; the typeFlag is discarded by `squeezeFromArray(...,0)`). The candidate-set
 *   assembly reads these thresholds in PHP order; the adapter computes the ratios over the live city row.
 * @property chiefStatMin `GameConst::$chiefStatMin` (scenario-tunable, default 65 — `d_setting/GameConst.php:11`)
 *   — the do긴급내정 `nextBool(leadership/chiefStatMin)` prob denominator. Scenario-threaded, NOT a Kotlin GameConst.
 * @property techLimited the `TechLimit(startyear, year, nation.tech)` boolean (PHP `:2187/2324`, H-HELPERS §4)
 *   — gates the che_기술연구 append. Default true (no tech context → no 기술연구 candidate; null-safe).
 * @property techLimitedNextGrade the `TechLimit(startyear, year, nation.tech + 1000)` boolean (PHP `:2191/2328`)
 *   — when false, the general is ≥1 grade behind → the higher 기술연구 weight. Default true.
 * @property recruitArmType the acting general's preset `getAuxVar('armType')` AFTER the genType filter (PHP
 *   `:2526-2533`) — null when no usable preset (→ the do징병 `choiceUsingWeight($availableArmType)` draw fires).
 * @property recruitArmTypeWeights the do징병 `$availableArmType` weight map (PHP `:2544-2552`) in
 *   FOOTMAN/ARCHER/CAVALRY/WIZARD insertion order. Read ONLY when [recruitArmType] is null.
 * @property recruitCrewScoresFor the do징병 `$types` pickScore map per chosen armType (PHP `:2571-2577`) in
 *   `GameUnitConst::byType(armType)` iteration order — the `choiceUsingWeight($types)` candidate list.
 * @property recruitFinalize the do징병 deterministic post-pick cost ladder (PHP `:2585-2650`): given the chosen
 *   crew-type id, it returns the emitted `(che_징병|che_모병, RAW args)` or null (insufficient gold/rice). NO draws.
 * @property tradeDecision the do금쌀구매 deterministic buy/sell ladder (PHP `:2367-2480`): returns the emitted
 *   `(che_군량매매, RAW args)` or null. ZERO draws (decision #9). Default null → the body returns null.
 *
 * ## L-GENWAR (GenWarMoveFamily) per-general scalars + world lambdas (defaulted; only the war/move bodies read them)
 * @property selfTrain the acting general's `getVar('train')` (PHP `:2661/2729`) — the do전투준비 che_훈련 candidate
 *   gate (`< properWarTrainAtmos`) + its weight base + the do출병 `:2729` train early-return. Default 0.0.
 * @property selfAtmos the acting general's `getVar('atmos')` (PHP `:2662/2732`) — the do전투준비 che_사기진작
 *   candidate gate + weight + the do출병 `:2732` atmos early-return. Default 0.0.
 * @property selfGold the acting general's `getVar('gold')` (PHP `:2797`) — the doNPC헌납 gold resource amount.
 * @property selfRice the acting general's `getVar('rice')` (PHP `:2797`) — the doNPC헌납 rice resource amount.
 * @property selfNpcType the acting general's `getNPCType()` (PHP `:2720/3115`) — do출병 `&&` npc>=2 + do집합 npc==5.
 * @property selfKillturn the acting general's `getVar('killturn')` (PHP `:3116`) — the do집합 killturn reroll base.
 * @property attackableCitiesOf the do출병 `SELECT city, nation FROM city WHERE nation IN %li AND city IN %li`
 *   (PHP `:2759-2763`) — given the `array_keys(CityConst::byID(cityID)->path)` near-city ids AND the
 *   attackable-nation list (both built in PHP order by the body), returns the PK-ascending DB-row city ids
 *   (the `choice` candidate list, `:2769`). The adapter runs the query; the body owns the input ORDER.
 * @property cityDevelRateOf the do내정워프 `calcCityDevelRate($city)` (PHP `:3033/3062`) — per cityId, the
 *   `(develKey, develVal, develType)` triples in PHP iteration order (the `warpProp`/`realDevelRate` product
 *   walks them filtered by genType). The adapter computes the ratios + the develType flags.
 * @property cityGeneralCountOf the do내정워프 `count($candidate['generals'] ?? [])` (PHP `:3076`) — per cityId,
 *   the attached-general count feeding the `1/(realDevelRate*sqrt(gens+1))` weight.
 * @property wanderOccupiedCities the do방랑군이동 `$occupiedCities` key set (PHP `:3146-3152`) — lord/nation
 *   cities; a target/neighbour in this set is skipped. The adapter runs the two SELECTs; insertion is not read.
 * @property movingTargetCityId the do방랑군이동 `getAuxVar('movingTargetCityID')` (PHP `:3154`) — the cached
 *   wander target; null when unset (→ the `:3180` target draw fires). Reconciled against [wanderOccupiedCities]/
 *   the current city by the body (`:3157-3161`); a fresh pick is queued via [recordGeneralKv] (`:3181`).
 * @property dupLordAtSelfCity the do방랑군이동 `SELECT COUNT(*) ... officer_level=12 AND city=cityID` (PHP `:3131`).
 * @property selfCityLevel the do방랑군이동 `getRawCity()['level']` (PHP `:3137`) — the {5,6} 건국-possible gate.
 */
data class GeneralAiContext(
    val rng: RandUtil,
    val instance: AiInstanceState,
    val world: AiWorldView,
    val generalPolicy: AutorunGeneralPolicy,
    val nationPolicy: AutorunNationPolicy,
    val env: AiEnv,
    val turnTerm: Int,
    val selfGeneralId: Int,
    val selfCityId: Int,
    val cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    val selfOfficerLevel: Int = 12,
    val candidateAllowed: (actionCode: String, rawArgs: Map<String, Any?>) -> Boolean = { _, _ -> true },
    val recordGeneralKv: (generalId: Int, key: String, value: Any?) -> Unit = { _, _, _ -> },
    val chiefTurnTime: String = "",
    val turnTimeOf: (AiGeneralView) -> String = { "" },
    val last발령Of: (AiGeneralView) -> Int? = { null },
    val recruitPopScoreOf: (AiGeneralView) -> Double = { 0.0 },
    val leadershipNoInjuryOf: (AiGeneralView) -> Double = { it.fullLeadership },
    val reservedIsRecruitOf: (AiGeneralView) -> Boolean = { false },
    val unitCostWithTechOf: (AiGeneralView) -> Double = { 0.0 },
    // --- L-GENDOM (GenDomesticFamily) per-general scalars ---
    val leadershipWithInjury: Double = 0.0,
    val strengthWithInjury: Double = 0.0,
    val intelWithInjury: Double = 0.0,
    val fullLeadership: Double = 0.0,
    val fullStrength: Double = 0.0,
    val fullIntel: Double = 0.0,
    val nationTech: Int = 0,
    val selfCrew: Int = 0,
    val selfCity: City? = null,
    val cityDevelRate: Map<String, Double> = emptyMap(),
    val chiefStatMin: Int = 65,
    val techLimited: Boolean = true,
    val techLimitedNextGrade: Boolean = true,
    val recruitArmType: Int? = null,
    val recruitArmTypeWeights: List<Pair<Int, Double>> = emptyList(),
    val recruitCrewScoresFor: (armType: Int) -> List<Pair<Int, Double>> = { emptyList() },
    val recruitFinalize: (crewTypeId: Int) -> ChosenCommand? = { null },
    val tradeDecision: () -> ChosenCommand? = { null },
    // --- L-GENWAR (GenWarMoveFamily) per-general scalars + world lambdas ---
    val selfTrain: Double = 0.0,
    val selfAtmos: Double = 0.0,
    val selfGold: Int = 0,
    val selfRice: Int = 0,
    val selfNpcType: Int = 0,
    val selfKillturn: Int = 0,
    val attackableCitiesOf: (nearCityIds: List<Int>, attackableNations: List<Int>) -> List<Int> = { _, _ -> emptyList() },
    val cityDevelRateOf: (cityId: Int) -> List<Triple<String, Double, Int>> = { emptyList() },
    val cityGeneralCountOf: (cityId: Int) -> Int = { 0 },
    val wanderOccupiedCities: Set<Int> = emptySet(),
    val movingTargetCityId: Int? = null,
    val dupLordAtSelfCity: Int = 0,
    val selfCityLevel: Int = 0,
    // --- L-GENFOUND (GenFoundFamily) per-general scalars + world inputs (defaulted; only the founding bodies read them) ---
    /**
     * The acting general's `getVar('makelimit')` (PHP do거병 `:3221`) — non-zero → the 거병 early-return BEFORE
     * any draw. Default 0 (the foundable case).
     */
    val selfMakeLimit: Int = 0,
    /**
     * The acting general's `getName()` (PHP do건국 `:3307` `mb_substr(getName(),1)`) — the 건국 nationName base
     * (`"㉿" + name.drop(1)`). Default "" (the non-founding bodies never read it).
     */
    val selfGeneralName: String = "",
    /**
     * The acting general's `getVar('affinity')` (PHP do국가선택 `:3359`) — `== 999` aborts the 임관 sub-tree
     * with NO further draw (after the `:3358` 0.3 gate). Default 0.
     */
    val selfAffinity: Int = 0,
    /**
     * The do거병 `$occupiedCities` key set (PHP `:3238-3247`): lord cities (officer_level=12 AND city.nation=0)
     * ∪ nation cities (nation!=0). The BFS dist-3 scan skips a candidate in this set (`:3251`). The body only
     * does membership tests; the adapter runs the two SELECTs (identical to the do방랑군이동 occupied set).
     */
    val foundOccupiedCities: Set<Int> = emptySet(),
    /**
     * The do거병 stat-threshold midpoint `(GameConst::$defaultStatNPCMax + GameConst::$chiefStatMin) / 2` (PHP
     * `:3268`, = 70.0 in 1010) — the adapter's effective per-server value; the family never hard-codes it.
     */
    val foundStatMidpoint: Double = 70.0,
    /**
     * The do거병 `$more = Util::valueFit(3 - year + init_year, 1, 3)` ∈ {1,2,3} (PHP `:3277`) — the final-gate
     * `nextBool(0.0075 * more)` factor. The env `init_year` math is the adapter's job (F-INSTANCE owns AiEnv).
     */
    val foundDeadlineMore: Int = 1,
    /**
     * The do국가선택 early-임관-period `$nationCnt = SELECT count(nation) FROM nation` (PHP `:3365`). When 0 (with
     * [notFullNationCount]) the 임관 aborts with NO draw (`:3367`). Default 0.
     */
    val nationCount: Int = 0,
    /**
     * The do국가선택 `$notFullNationCnt = SELECT count(nation) FROM nation WHERE gennum < initialNationGenLimit`
     * (PHP `:3366`) — feeds the `:3371` early-abort prob `pow(1/(nationCnt+1)/pow(notFullNationCnt,3),1/4)`. 0
     * (with [nationCount]) aborts with NO draw. Default 0.
     */
    val notFullNationCount: Int = 0,
    val externalSqlRandSelector: ExternalSqlRandSelector? = null,
    /**
     * The do선양 `ORDER BY RAND()` candidate pool (PHP `:3324` `SELECT no FROM general WHERE nation=%i AND npc!=5`)
     * — the deterministic `min(no)` substitute ([GenFoundFamily.seonyangDestGeneralId]) runs over this set.
     * The adapter supplies the nation's general rows; the substitute applies the WHERE filter. Default empty
     * (→ a null destGeneralID, which the gate then rejects).
     */
    val seonyangCandidates: List<opensamguk.logic.domain.General> = emptyList(),
    /**
     * The do국가선택-오랑캐 `ORDER BY RAND()` candidate pool (PHP `:3345` `SELECT nation FROM general WHERE
     * officer_level=12 AND npc=9 AND nation`) — the deterministic nation-of-`min(no)` substitute
     * ([GenFoundFamily.orankaeRulerNation]) runs over this set. Reached only on the npc==9 오랑캐 branch
     * (unreachable in 1010, F-QUAR). Default empty.
     */
    val orankaeRulerCandidates: List<opensamguk.logic.domain.General> = emptyList(),
)
