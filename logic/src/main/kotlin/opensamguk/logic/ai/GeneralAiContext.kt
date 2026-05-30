package opensamguk.logic.ai

import opensamguk.common.rng.RandUtil

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
    val candidateAllowed: (actionCode: String, rawArgs: Map<String, Any?>) -> Boolean = { _, _ -> true },
    val recordGeneralKv: (generalId: Int, key: String, value: Any?) -> Unit = { _, _, _ -> },
    val chiefTurnTime: String = "",
    val turnTimeOf: (AiGeneralView) -> String = { "" },
    val last발령Of: (AiGeneralView) -> Int? = { null },
    val recruitPopScoreOf: (AiGeneralView) -> Double = { 0.0 },
    val leadershipNoInjuryOf: (AiGeneralView) -> Double = { it.fullLeadership },
    val reservedIsRecruitOf: (AiGeneralView) -> Boolean = { false },
)
