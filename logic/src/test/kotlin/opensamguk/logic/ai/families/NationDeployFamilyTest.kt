package opensamguk.logic.ai.families

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-DEPLOY — NationDeployFamily (the 11 `do발령` `che_발령` emitters).
 *
 * GRAND TRUTH = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php` (read in full):
 *  - `do부대전방발령` (`:294-411`), `do부대후방발령` (`:413-499`), `do부대구출발령` (`:501-571`),
 *  - `do부대유저장후방발령` (`:573-685`), `do유저장후방발령` (`:687-789`), `do유저장구출발령` (`:791-848`),
 *  - `do유저장전방발령` (`:850-908`), `do유저장내정발령` (`:910-980`),
 *  - `doNPC후방발령` (`:982-1094`), `doNPC구출발령` (`:1097-1123`), `doNPC전방발령` (`:1125-1186`),
 *  - `doNPC내정발령` (`:1188-1254`).
 *
 * ## The two load-bearing parity facts (catalog §5.A/B)
 *  1. **General-pick `choice` BEFORE city-pick** — PHP evaluates the `['destGeneralID'=>choice(...), 'destCityID'=>choice(...)]`
 *     array literal LEFT-to-RIGHT, so the general pick draws FIRST, the city pick SECOND.
 *  2. **`do부대전방발령` emits the `destGenaralID` typo VERBATIM** (`:391`, the extra `a`) — the ONLY method
 *     with the typo. Its variable BFS `choice` draws STILL happen; then the F-BRIDGE argTest rejects the
 *     missing `destGeneralID` → it ALWAYS returns null. Port the latent bug; do NOT fix the typo.
 *  3. **구출발령 draws = #qualifying + 1** — one `choice` per qualifying lostGeneral INSIDE the loop, then one
 *     final `choice($args)`.
 *
 * The family is PURE (mirrors GenFoundFamily): each method takes the already-built candidate collections +
 * the threaded `rng` and returns the emitted `(che_발령, RAW args)` (or null on empty). The candidate-set
 * construction (buckets/BFS/cutTurn gates) is the foundations'/adapter's job; the family owns the per-method
 * DRAW ORDER + the emitted RAW arg map (incl. the typo).
 */
class NationDeployFamilyTest {

    /** A draw-recording RNG over a REAL [LiteHashDrbg] — records the ordered (method,args) draw stream. */
    private class RecordingRng(seed: String) : RandUtil(LiteHashDrbg(seed)) {
        data class Draw(val kind: String, val n: Int)
        val draws = ArrayList<Draw>()
        override fun <T> choice(items: List<T>): T {
            draws.add(Draw("choice", items.size)); return super.choice(items)
        }
    }

    // --------------------------------------------------------------------------------------------------
    // (1) General-pick BEFORE city-pick draw order — the single-pick two-stage emitters.
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `single-pick emitter draws general BEFORE city and emits destGeneralID then destCityID`() {
        val rng = RecordingRng("deploy-seed")
        val generals = listOf(101, 102, 103) // general candidates (insertion = PK-ascending)
        val cities = listOf(11, 12)           // city candidates (insertion = bucket order)

        val args = NationDeployFamily.pickGeneralThenCity(generals, cities, rng)

        // draw 1 = general choice over 3, draw 2 = city choice over 2 — general FIRST.
        assertEquals(
            listOf(RecordingRng.Draw("choice", 3), RecordingRng.Draw("choice", 2)),
            rng.draws,
            "general-pick choice draws BEFORE city-pick choice (array-literal L->R)",
        )
        assertTrue(args.keys.toList() == listOf("destGeneralID", "destCityID"), "RAW args destGeneralID THEN destCityID")
        assertTrue(args["destGeneralID"] in generals && args["destCityID"] in cities)
    }

    // --------------------------------------------------------------------------------------------------
    // (2) do부대전방발령 — the destGenaralID typo VERBATIM (the ONLY method with it).
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `do부대전방발령 emits the destGenaralID typo verbatim (the only method with it)`() {
        // PHP `:391` `['destGenaralID' => $leaderID, 'destCityID' => $targetCityID]` — the typo (extra `a`).
        val arg = NationDeployFamily.troopForwardArg(leaderId = 55, destCityId = 12)
        assertEquals(
            listOf("destGenaralID", "destCityID"),
            arg.keys.toList(),
            "do부대전방발령 emits the destGenaralID typo VERBATIM (NOT the correct destGeneralID)",
        )
        assertEquals(55, arg["destGenaralID"], "the typo key carries the leaderID")
        assertEquals(12, arg["destCityID"])
        assertNull(arg["destGeneralID"], "the CORRECT destGeneralID key is ABSENT → argTest will reject → always-null")
    }

    @Test
    fun `the other 10 emitters use the CORRECT destGeneralID key`() {
        val arg = NationDeployFamily.deployArg(generalId = 55, cityId = 12)
        assertEquals(listOf("destGeneralID", "destCityID"), arg.keys.toList())
        assertEquals(55, arg["destGeneralID"])
        assertNull(arg["destGenaralID"], "no typo key on the correct emitters")
    }

    // --------------------------------------------------------------------------------------------------
    // (3) do부대전방발령 ambiguous-hop BFS choice — variable count, then the final troopCandidate choice.
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `troopForwardAdvance draws one choice per ambiguous BFS hop and skips single-candidate hops`() {
        // PHP `:381` `$targetCityID = choice($nextCityCandidate)` ONLY when count>1; count==1 → no draw.
        val rng = RecordingRng("deploy-seed")
        // hop sequence: [a]=1 candidate (no draw, pick a), [b,c]=2 (draw), [d]=1 (no draw), reached front.
        val hops = listOf(listOf(91), listOf(92, 93), listOf(94))
        NationDeployFamily.troopForwardAdvance(hops, rng)
        assertEquals(
            listOf(RecordingRng.Draw("choice", 2)),
            rng.draws,
            "only the count>1 hop draws; count==1 hops consume ZERO draws (PHP :378-381)",
        )
    }

    @Test
    fun `troopForwardFinalPick draws exactly one choice over the troopCandidate list`() {
        val rng = RecordingRng("deploy-seed")
        val candidates = listOf(
            mapOf<String, Any?>("destGenaralID" to 1, "destCityID" to 10),
            mapOf<String, Any?>("destGenaralID" to 2, "destCityID" to 20),
        )
        val picked = NationDeployFamily.troopForwardFinalPick(candidates, rng)
        assertEquals(listOf(RecordingRng.Draw("choice", 2)), rng.draws, "exactly ONE final choice (PHP :391)")
        assertTrue(picked in candidates)
    }

    // --------------------------------------------------------------------------------------------------
    // (4) 구출발령 — draws = #qualifying + 1 (per-lostGeneral choice INSIDE the loop, then one final).
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `rescueDeploy draws one city-choice per qualifying lostGeneral then one final choice`() {
        val rng = RecordingRng("deploy-seed")
        // 3 qualifying lostGenerals; each draws choice($cityPool) (size 4); then the final choice($args) (size 3).
        val lostGeneralIds = listOf(201, 202, 203)
        val cityPool = listOf(31, 32, 33, 34)
        NationDeployFamily.rescueDeploy(lostGeneralIds, cityPoolFor = { cityPool }, rng = rng)
        assertEquals(
            listOf(
                RecordingRng.Draw("choice", 4), // lostGeneral 201 → city pick
                RecordingRng.Draw("choice", 4), // lostGeneral 202 → city pick
                RecordingRng.Draw("choice", 4), // lostGeneral 203 → city pick
                RecordingRng.Draw("choice", 3), // final choice over the 3 built args
            ),
            rng.draws,
            "draws = #qualifying (3) + 1 final (PHP :793/795/807, :1075/1085)",
        )
    }

    @Test
    fun `rescueDeploy with no qualifying lostGenerals returns null and draws nothing`() {
        val rng = RecordingRng("deploy-seed")
        val arg = NationDeployFamily.rescueDeploy(emptyList(), cityPoolFor = { listOf(1) }, rng = rng)
        assertNull(arg, "empty args → null (PHP :803-805)")
        assertTrue(rng.draws.isEmpty(), "no qualifying lostGeneral → ZERO draws")
    }

    @Test
    fun `rescueDeploy builds destGeneralID per lostGeneral (correct key) and the final emit carries it`() {
        // Each loop iteration appends ['destGeneralID'=>lostGeneral, 'destCityID'=>selCity['city']] (PHP :798-801).
        val rng = RecordingRng("deploy-seed")
        val arg = NationDeployFamily.rescueDeploy(
            lostGeneralIds = listOf(201, 202),
            cityPoolFor = { listOf(31, 32) },
            rng = rng,
        )!!
        assertEquals(listOf("destGeneralID", "destCityID"), arg.keys.toList(), "rescue uses the CORRECT key")
        assertTrue(arg["destGeneralID"] in listOf(201, 202))
        assertTrue(arg["destCityID"] in listOf(31, 32))
    }
}
