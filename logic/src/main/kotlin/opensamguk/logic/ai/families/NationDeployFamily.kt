package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil

/**
 * L-DEPLOY — the nation-deploy `do<한글>` command family: the 11 `do발령` methods, all emitting `che_발령`.
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do부대전방발령` (`:294-411`), `do부대후방발령` (`:413-499`), `do부대구출발령` (`:501-571`),
 *  - `do부대유저장후방발령` (`:573-685`), `do유저장후방발령` (`:687-789`), `do유저장구출발령` (`:791-848`),
 *  - `do유저장전방발령` (`:850-908`), `do유저장내정발령` (`:910-980`),
 *  - `doNPC후방발령` (`:982-1094`), `doNPC구출발령` (`:1097-1123`), `doNPC전방발령` (`:1125-1186`),
 *  - `doNPC내정발령` (`:1188-1254`).
 *
 * This file holds the PURE, draw-order-bearing primitives each `do발령` composes (it mirrors the
 * established `GenFoundFamily` pure-helper shape: the candidate-set construction — buckets / BFS / cutTurn /
 * policy CombatForce·SupportForce gates — is the foundations'/adapter's job; the family owns the per-method
 * DRAW ORDER on the shared `"GeneralAI"` [RandUtil] + the emitted RAW arg map). The F-BRIDGE
 * `candidateAllowed` gate (argTest then `evaluateConstraints(FULL)`) accepts/rejects the emitted
 * `(che_발령, RAW args)` — the family does NOT re-implement that gate.
 *
 * ## The two load-bearing parity facts (catalog §5.A/B)
 *  1. **General-pick `choice` BEFORE city-pick** — PHP evaluates the
 *     `['destGeneralID' => choice(generals), 'destCityID' => choice(cities)]` array literal LEFT-to-RIGHT,
 *     so the general pick draws FIRST, the city/weight pick SECOND ([pickGeneralThenCity]).
 *  2. **`do부대전방발령` emits the `destGenaralID` TYPO VERBATIM** (`:391`, extra `a`) — the ONLY method with
 *     the typo. Its variable BFS `choice` draws STILL happen ([troopForwardAdvance]); then the final
 *     `choice($troopCandidate)` ([troopForwardFinalPick]); THEN the F-BRIDGE argTest rejects the absent
 *     correct `destGeneralID` → it ALWAYS returns null. Port the latent bug; do NOT fix the typo
 *     (fixing it shifts every downstream draw — R-BRIDGE §4 / M9).
 *  3. **구출발령 draws = #qualifying + 1** — one `choice($cityPool)` per qualifying lostGeneral INSIDE the
 *     loop, then one final `choice($args)` ([rescueDeploy]; PHP `do유저장구출발령` `:791-848` /
 *     `doNPC구출발령` `:1097-1123`).
 *
 * ## cutTurn one-deploy-per-turn gate (do부대전방발령/do부대후방발령, H-HELPERS §2)
 * The per-troopLeader loop skips a leader whose `last발령` falls in the same turn-bucket as the chief:
 * `compYearMonth = yearMonth; if (chiefTurn < leaderTurn) compYearMonth += 1; if (compYearMonth === yearMonth) continue;`
 * — the `cutTurn`/`joinYearMonth` math is the adapter's; [oneDeployPerTurnSkip] is the verbatim boolean.
 *
 * NO draws happen outside the documented `choice`/`choiceUsingWeight` sites. PURE `:logic`.
 */
object NationDeployFamily {

    /**
     * The `destGeneralID`/`destCityID` RAW arg map the 10 CORRECT emitters build (PHP e.g. `:467-470`,
     * `:586-589`, `:864-867`, …). Insertion order = `destGeneralID` THEN `destCityID` (the array-literal
     * key order), a parity target for the canonicalization downstream.
     */
    fun deployArg(generalId: Int, cityId: Int): Map<String, Any?> =
        linkedMapOf("destGeneralID" to generalId, "destCityID" to cityId)

    /**
     * `do부대전방발령`'s LATENT-BUG arg map (PHP `:391`):
     * `['destGenaralID' => $leaderID, 'destCityID' => $targetCityID]` — the `destGenaralID` TYPO (extra `a`),
     * the ONLY method that misspells the key. The correct `destGeneralID` key is therefore ABSENT, so the
     * F-BRIDGE argTest rejects it → `do부대전방발령` ALWAYS returns null. Port VERBATIM; do NOT fix the typo.
     */
    fun troopForwardArg(leaderId: Int, destCityId: Int): Map<String, Any?> =
        linkedMapOf("destGenaralID" to leaderId, "destCityID" to destCityId)

    /**
     * The general-pick-THEN-city-pick draw order shared by the single-pick two-stage emitters
     * (`do부대후방발령`/`do부대구출발령`/`do부대유저장후방발령`/`do유저장전방발령`/`doNPC전방발령` etc.). PHP evaluates the
     * `['destGeneralID' => choice(generals), 'destCityID' => choice(cities)]` array literal LEFT-to-RIGHT:
     * the **general `choice` draws FIRST**, the **city `choice` draws SECOND**.
     *
     * @param generalCandidates the source-general candidate list (insertion = PK-ascending bucket order).
     * @param cityCandidates the dest-city candidate list (insertion = bucket order).
     * @return the `linkedMapOf("destGeneralID" to picked, "destCityID" to picked)` RAW emit.
     */
    fun pickGeneralThenCity(
        generalCandidates: List<Int>,
        cityCandidates: List<Int>,
        rng: RandUtil,
    ): Map<String, Any?> {
        val general = rng.choice(generalCandidates) // draw 1 — general FIRST (array-literal L->R)
        val city = rng.choice(cityCandidates)        // draw 2 — city SECOND
        return deployArg(general, city)
    }

    /**
     * `do부대전방발령`'s advance loop (PHP `:373-383`): walk from the source toward the front, drawing
     * `choice($nextCityCandidate)` ONLY at an ambiguous hop (`count > 1`); a single-candidate hop (`count == 1`)
     * advances with NO draw. The COUNT of draws is data-dependent on the BFS name-order candidate sets
     * ([hops]) — the adapter supplies the per-hop candidate lists in `CityConst.byID(target)->path` name order.
     *
     * @param hops the per-hop next-city candidate lists, in advance order (each non-empty; PHP `:374-377`
     *  throws `MustNotBeReachedException` on an empty hop — that throw is the adapter's responsibility).
     * @return the final target city id (the last hop's pick).
     */
    fun troopForwardAdvance(hops: List<List<Int>>, rng: RandUtil): Int {
        var target = -1
        for (candidates in hops) {
            target = if (candidates.size == 1) {
                candidates[0] // PHP `:378-380` count==1 → no draw, advance.
            } else {
                rng.choice(candidates) // PHP `:381` choice($nextCityCandidate) — only the ambiguous hop draws.
            }
        }
        return target
    }

    /**
     * `do부대전방발령`'s final pick (PHP `:391` `$this->rng->choice($troopCandidate)`): exactly ONE `choice`
     * over the accumulated troop-candidate arg maps. Each candidate already carries the `destGenaralID` TYPO
     * (built via [troopForwardArg] / the lost-route fallbacks `:337/343/349`).
     */
    fun troopForwardFinalPick(troopCandidates: List<Map<String, Any?>>, rng: RandUtil): Map<String, Any?> =
        rng.choice(troopCandidates)

    /**
     * The 구출 (rescue) deploy draw stream (PHP `do유저장구출발령` `:791-848`, `doNPC구출발령` `:1097-1123`):
     * ONE `choice($cityPool)` per QUALIFYING lostGeneral INSIDE the loop (building `$args[]`), THEN one final
     * `choice($args)`. **Total draws = #qualifying + 1.** Even discarded args consume their in-loop draw.
     *
     * @param lostGeneralIds the already-filtered qualifying lostGeneral ids, in `lostGenerals` insertion
     *  (PK-ascending / bucket) order — the adapter applies the per-method qualifying filters (npc gate,
     *  defence/crew gate, troop-escape gate, dipState/frontCities>2 front-vs-supply branch).
     * @param cityPoolFor the per-lostGeneral city-candidate pool (PHP `do유저장구출발령` picks
     *  `frontCities` when at-war with >2 fronts else `supplyCities` `:793/795`; `doNPC구출발령` always
     *  `supplyCities` `:1075`) — the branch is the adapter's, the per-loop `choice` draw is the family's.
     * @return the `linkedMapOf("destGeneralID" to lostGeneral, "destCityID" to cityPick)` RAW emit picked by
     *  the final `choice($args)`, or null when no lostGeneral qualifies (PHP `:803-805` `if(!$args) return null`).
     */
    fun rescueDeploy(
        lostGeneralIds: List<Int>,
        cityPoolFor: (lostGeneralId: Int) -> List<Int>,
        rng: RandUtil,
    ): Map<String, Any?>? {
        val args = ArrayList<Map<String, Any?>>(lostGeneralIds.size)
        for (lostGeneralId in lostGeneralIds) {
            val selCity = rng.choice(cityPoolFor(lostGeneralId)) // per-lostGeneral choice INSIDE the loop
            // PHP `:798-801` / `:1078-1081` — the CORRECT destGeneralID key (only do부대전방발령 has the typo).
            args.add(linkedMapOf("destGeneralID" to lostGeneralId, "destCityID" to selCity))
        }
        if (args.isEmpty()) {
            return null // PHP `:803-805` `if (!$args) return null;`
        }
        return rng.choice(args) // PHP `:807` / `:1085` — the one final choice($args).
    }

    /**
     * The "한턴마다 한번씩만 발령" same-turn-bucket skip (PHP `do부대전방발령` `:323-327`, `do부대후방발령` `:154-160`,
     * H-HELPERS §2 cutTurn). Given the chief's and the troopLeader's `cutTurn`-formatted timestamps and the
     * chief's `joinYearMonth`, returns true when the leader was already deployed this turn-bucket (→ skip):
     * `compYearMonth = yearMonth; if (chiefTurn < leaderTurn) compYearMonth += 1; return compYearMonth === yearMonth;`
     * The `<` is the PHP lexicographic string compare on the `Y-m-d H:i:s.u` cutTurn output (the adapter
     * supplies the cutTurn strings + the joinYearMonth int). Only consulted when `last발령` is set.
     */
    fun oneDeployPerTurnSkip(chiefCutTurn: String, leaderCutTurn: String, yearMonth: Int): Boolean {
        var compYearMonth = yearMonth
        if (chiefCutTurn < leaderCutTurn) {
            compYearMonth += 1
        }
        return compYearMonth == yearMonth
    }
}
