package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.double
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommerceInvestment
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AREA G — Task G2: action-log byte-match PHP golden test (design §12 step 8 / §10.3).
 *
 * The PHP devsam capture (G1) is the BYTE ORACLE. For each non-blocked fixture this test:
 *   1. reconstructs the SIX-component per-action seed via serializeSeed(hiddenSeed, "generalCommand",
 *      year, month, generalId, key) and asserts it byte-equals the golden's captured seedString;
 *   2. constructs the general/city/env from the golden BEFORE state (full stats from the golden DB
 *      dump for general no.76 / city 1 — stats are unchanged by a che action);
 *   3. runs CommerceInvestment.resolve over a FIXED-seed RNG with context.date set from the golden;
 *   4. asserts the FULL ordered context.logs() == the golden's ordered action-log lines BYTE-FOR-BYTE
 *      (asserting the whole list, not just logs()[0], catches any stray/extra log — in P1 the
 *      no-level-cross golden produces exactly ONE primary line);
 *   5. asserts the resolved general/city post-state numbers (gold/exp/ded/commerce|agriculture/
 *      intel_exp/max_domestic_critical) == the golden AFTER state.
 *
 * Failure modes this pins (per plan): josa wrong (JosaUtil), missing space in action name (TS-trap),
 * scoreText not comma-grouped (numberFormat), wrong draw order (RNG), front-debuff applied before
 * scoreText (ordering trap). A RED here is a REAL parity bug to FIX in the implementation — never by
 * editing the golden or weakening the assertion.
 */
class CommerceActionLogGoldenTest {

    private val pipeline = GeneralActionPipeline()

    // ---- golden fixtures (committed; PHP-captured by G1) ----
    private val fixtures: GoldenFixtures by lazy {
        val text = javaClass.classLoader
            .getResourceAsStream("golden/p1/che-action-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val cases = root["cases"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            GoldenCase(
                name = o["case"]!!.jsonPrimitive.content,
                command = o["command"]!!.jsonPrimitive.content,
                generalId = o["generalId"]!!.jsonPrimitive.int,
                cityId = o["cityId"]!!.jsonPrimitive.int,
                env = o["env"]?.jsonObject?.let { e ->
                    GoldenEnv(
                        year = e["year"]!!.jsonPrimitive.int,
                        startYear = e["startYear"]!!.jsonPrimitive.int,
                        month = e["month"]!!.jsonPrimitive.int,
                        develCost = e["develCost"]!!.jsonPrimitive.int,
                    )
                },
                seedString = o["seedString"]?.jsonPrimitive?.contentOrNull,
                reqGold = o["reqGold"]?.jsonPrimitive?.intOrNull,
                before = o["before"]?.jsonObject?.let { parseState(it) },
                after = o["after"]?.jsonObject?.let { parseState(it) },
                logLines = o["logLines"]?.jsonArray?.map { it.jsonPrimitive.content },
                denyReason = o["denyReason"]?.jsonPrimitive?.contentOrNull,
            )
        }
        GoldenFixtures(hiddenSeed, cases)
    }

    private fun parseState(o: kotlinx.serialization.json.JsonObject): GoldenState {
        val g = o["general"]!!.jsonObject
        val c = o["city"]!!.jsonObject
        return GoldenState(
            gold = g["gold"]!!.jsonPrimitive.int,
            experience = g["experience"]!!.jsonPrimitive.int,
            dedication = g["dedication"]!!.jsonPrimitive.int,
            intelExp = g["intel_exp"]!!.jsonPrimitive.int,
            explevel = g["explevel"]!!.jsonPrimitive.int,
            maxDomesticCritical = g["max_domestic_critical"]!!.jsonPrimitive.double,
            comm = c["comm"]!!.jsonPrimitive.int,
            agri = c["agri"]!!.jsonPrimitive.int,
            commMax = c["comm_max"]!!.jsonPrimitive.int,
            agriMax = c["agri_max"]!!.jsonPrimitive.int,
            trust = c["trust"]!!.jsonPrimitive.double,
        )
    }

    // ---- general/city construction from the golden BEFORE state ----
    // Full stats are unchanged by a che action, so the golden DB AFTER dump (general no.76 / city 1)
    // supplies leadership/strength/intel/injury/officer_level/level/supply/front etc. faithfully.
    private fun generalFrom(c: GoldenCase): General {
        val b = c.before!!
        return General(
            id = c.generalId,
            nationId = 2,          // golden DB: general no.76 nation=2 (owned, supplied, non-front)
            cityId = c.cityId,
            leadership = 31,       // golden DB: general no.76 leadership=31
            strength = 68,         // golden DB: general no.76 strength=68
            intel = 49,            // golden DB: general no.76 intel=49
            injury = 0,            // golden DB: injury=0
            experience = b.experience.toDouble(),
            dedication = b.dedication.toDouble(),
            officerLevel = 1,      // golden DB: officer_level=1
            gold = b.gold,
            rice = 1000,           // golden DB: rice=1000 (che gold-only; rice unused)
            meta = linkedMapOf(
                "explevel" to b.explevel,
                "intel_exp" to b.intelExp,
                "max_domestic_critical" to b.maxDomesticCritical,
            ),
        )
    }

    private fun cityFrom(c: GoldenCase): City {
        val b = c.before!!
        return City(
            id = c.cityId,
            nationId = 2,          // golden DB: city 1 nation=2
            level = 8,             // golden DB: city 1 level=8
            commerce = b.comm, commerceMax = b.commMax,
            agriculture = b.agri, agricultureMax = b.agriMax,
            supplyState = 1,       // golden DB: supply=1
            frontState = 0,        // golden DB: front=0 — non-front, no debuff
            trust = b.trust,       // INTEGER-valued (G1 invariant) but typed Double (PHP FLOAT)
            meta = linkedMapOf(),
        )
    }

    private fun nationOf(c: GoldenCase) = Nation(id = 2, level = 2, capitalCityId = 99)

    private fun envOf(c: GoldenCase): WorldEnv {
        val e = c.env!!
        return WorldEnv(year = e.year, startYear = e.startYear, develCost = e.develCost)
    }

    private fun actionFor(c: GoldenCase): CommerceInvestment = when (c.command) {
        "che_상업투자" -> CommerceInvestment(
            pipeline = pipeline, cityKey = "comm", statKey = "intel",
            actionKey = "상업", name = "상업 투자")
        "che_농지개간" -> CommerceInvestment(
            pipeline = pipeline, cityKey = "agri", statKey = "intel",
            actionKey = "농업", name = "농지 개간")
        else -> error("unknown command ${c.command}")
    }

    private fun rngFor(c: GoldenCase): RandUtil {
        val e = c.env!!
        val action = actionFor(c)
        // SIX-component PHP seed shape: serializeSeed(hiddenSeed, "generalCommand", year, month, generalId, key)
        val seed = serializeSeed(fixtures.hiddenSeed, "generalCommand", e.year, e.month, c.generalId, action.key)
        // GUARD: the reconstructed seed must byte-equal the golden's captured PHP seed string.
        assertEquals(c.seedString, seed, "[${c.name}] seedString must match the golden PHP oracle")
        return RandUtil(LiteHashDrbg(seed))
    }

    private val nonBlockedCases get() = fixtures.cases.filter { it.denyReason == null }

    @Test
    fun `golden fixtures present — five cases incl one blocked, distinct success-normal-fail`() {
        val all = fixtures.cases.map { it.name }.toSet()
        assertEquals(
            setOf("commerce_success", "commerce_normal", "commerce_fail", "agri_normal", "blocked_notSupplied"),
            all, "all five golden cases present")
        assertEquals(4, nonBlockedCases.size, "four non-blocked fixtures drive the log byte-match")
    }

    @Test
    fun `each non-blocked che fixture byte-matches the PHP golden action log and post-state`() {
        for (c in nonBlockedCases) {
            val before = c.before!!
            val after = c.after!!
            val draft = GeneralActionDraft(generalFrom(c), cityFrom(c), nationOf(c))
            // date "21:47" matches the golden capture's <1>HH:MM</> turn-time suffix; month from the case env.
            val ctx = GeneralActionResolveContext(draft, rngFor(c), envOf(c), month = c.env!!.month, date = "21:47")

            actionFor(c).resolve(ctx)

            // (1) FULL ordered log list == golden, byte-for-byte (catches stray/extra logs).
            assertEquals(c.logLines!!, ctx.logs(), "[${c.name}] action-log byte-match")

            // (2) general post-state numbers.
            assertEquals(after.gold, draft.general.gold, "[${c.name}] general.gold")
            // experience/dedication are raw Double accumulators in-memory (PHP increaseVar adds the
            // float delta raw, no per-add round). At persist, the raw float binds into the `integer`
            // column and Postgres ROUNDS (half-away-from-zero) — NOT truncates. The golden AFTER is
            // that persisted Int (e.g. 3030+44*0.7=3060.8 → 3061; 3030+64*0.7=3074.8 → 3075). The D1
            // row mapper rounds via phpRound; this test mirrors that float→int rule.
            assertEquals(after.experience, phpRound(draft.general.experience), "[${c.name}] general.experience (round)")
            assertEquals(after.dedication, phpRound(draft.general.dedication), "[${c.name}] general.dedication (round)")
            assertEquals(after.intelExp, metaInt(draft.general.meta, "intel_exp"), "[${c.name}] meta.intel_exp")
            assertEquals(after.explevel, metaInt(draft.general.meta, "explevel"), "[${c.name}] meta.explevel (no level cross)")
            assertEquals(
                after.maxDomesticCritical, metaDouble(draft.general.meta, "max_domestic_critical"), 0.0,
                "[${c.name}] meta.max_domestic_critical")

            // (3) city post-state numbers (the developed stat moves; the other + *_max + trust hold).
            assertEquals(after.comm, draft.city.commerce, "[${c.name}] city.commerce")
            assertEquals(after.agri, draft.city.agriculture, "[${c.name}] city.agriculture")
            assertEquals(after.commMax, draft.city.commerceMax, "[${c.name}] city.commerce_max unchanged")
            assertEquals(after.agriMax, draft.city.agricultureMax, "[${c.name}] city.agriculture_max unchanged")
            assertEquals(after.trust, draft.city.trust, 0.0, "[${c.name}] city.trust unchanged")

            // sanity: BEFORE != AFTER for the developed stat (the action actually moved something).
            val developedBefore = if (c.command == "che_상업투자") before.comm else before.agri
            val developedAfter = if (c.command == "che_상업투자") after.comm else after.agri
            assertTrue(developedAfter > developedBefore, "[${c.name}] developed stat increased")
        }
    }

    // ---- fixture DTOs ----
    private data class GoldenFixtures(val hiddenSeed: String, val cases: List<GoldenCase>)
    private data class GoldenEnv(val year: Int, val startYear: Int, val month: Int, val develCost: Int)
    private data class GoldenState(
        val gold: Int, val experience: Int, val dedication: Int, val intelExp: Int, val explevel: Int,
        val maxDomesticCritical: Double, val comm: Int, val agri: Int, val commMax: Int, val agriMax: Int,
        val trust: Double,
    )
    private data class GoldenCase(
        val name: String, val command: String, val generalId: Int, val cityId: Int,
        val env: GoldenEnv?, val seedString: String?, val reqGold: Int?,
        val before: GoldenState?, val after: GoldenState?, val logLines: List<String>?, val denyReason: String?,
    )
}
