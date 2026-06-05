package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_수몰 PHP-golden byte gate (F4-C3 chief, P2 strategic family).
 *
 * Drives the REAL Kotlin resolver via [CommandRegistry] for the che_수몰 golden
 * (`golden/p2/che_수몰-fixtures.json`, hiddenSeed a2db167c.., acting gid 152, city 17, arg destCityID=1),
 * seeded with the exact 6-component PHP seed, byte-comparing:
 *   - the registry-resolved key (registry resolution REQUIRED),
 *   - the GUARD-asserted reconstructed 6-token seedString (byte-equal the PHP oracle),
 *   - the RNG draw stream — che_수몰 is DETERMINISTIC (golden draw_count == 0): the resolver consumes
 *     ZERO draws off the seeded [RandUtil] (asserted via a draw-counting decorator),
 *   - the ordered acting `logLines` (`수몰 발동! <1>HH:MM</>`) + the `broadcastLines` (empty — the
 *     아국/dest-국가 PLAIN + national-history broadcasts go to OTHER generals' ActionLogger scopes,
 *     NOT the acting action log nor pushGlobalActionLog),
 *   - the general after-deltas (exp/ded += 5*(preReqTurn+1)=15; phpRound on exp/ded; no level change) and
 *     the actor city after-state (UNCHANGED — the def/wall*0.2 hits the DEST city, not the actor city 17).
 *
 * The fixture city carries def/wall/def_max/wall_max (the strategic surface), which the shared
 * [P2GoldenSupport.cityFrom] does NOT map — so this gate builds the City directly from the captured GState,
 * pinning every column the resolver reads/writes. The draw stream + seedString are parsed straight from the
 * committed fixture JSON (never fabricated); the draw-count decorator is local so this gate stays disjoint
 * from any P2GoldenSupport draw-surface extension owned by a sibling C3 family.
 */
class Che수몰GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    /** A draw-counting decorator over [RandUtil] — every draw method bumps the counter (consume-neutral). */
    private class CountingRandUtil(inner: LiteHashDrbg) : RandUtil(inner) {
        var draws = 0
            private set
        override fun nextFloat1(): Double { draws++; return super.nextFloat1() }
        override fun nextRange(min: Double, max: Double): Double { draws++; return super.nextRange(min, max) }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int { draws++; return super.nextRangeInt(minInclusive, maxInclusive) }
        override fun nextInt(minInclusive: Int, maxExclusive: Int): Int { draws++; return super.nextInt(minInclusive, maxExclusive) }
        override fun nextBit(): Boolean { draws++; return super.nextBit() }
        override fun nextBool(prob: Double): Boolean { draws++; return super.nextBool(prob) }
        override fun <T> shuffle(srcArray: List<T>): List<T> { draws++; return super.shuffle(srcArray) }
        override fun <T> choice(items: List<T>): T { draws++; return super.choice(items) }
        override fun choiceUsingWeight(items: Map<String, Double>): String { draws++; return super.choiceUsingWeight(items) }
        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T { draws++; return super.choiceUsingWeightPair(items) }
    }

    @Test
    fun `che_수몰 golden byte-matches the PHP action log + exp-ded + zero-draw stream`() {
        val command = "che_수몰"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        // REGISTRY RESOLUTION required — the wired key must be the seed-token / golden command.
        assertEquals(command, def.key, "$command registry key")

        // The committed fixture's per-case golden draw_count (the byte oracle; never fabricated).
        val goldenDrawCounts = goldenDrawCounts(command)

        for ((idx, c) in f.cases.withIndex()) {
            val general = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
            val city = cityWithDefenseSurface(c.cityId, c.before.city)
            val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
            val draft = GeneralActionDraft(general, city, nation)

            // GUARD-assert the reconstructed 6-token seed byte-equals the PHP oracle (the rngFor guard),
            // then build the draw-counting RNG over the SAME seed and the context manually.
            val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
            assertEquals(c.seedString, seed, "[$command/${c.name}] seedString must byte-equal the golden PHP oracle")
            val rng = CountingRandUtil(LiteHashDrbg(seed))

            val ctx = GeneralActionResolveContext(
                draft = draft,
                rng = rng,
                env = P2GoldenSupport.envOf(c),
                month = c.env.month,
                date = P2GoldenSupport.dateOf(c),
                args = c.arg ?: emptyMap(),
                generalName = P2GoldenSupport.nameOf(c.generalId),
            )

            def.resolve(ctx)

            // RNG draw stream — che_수몰 is deterministic: ZERO draws (golden draw_count == 0).
            assertEquals(0, goldenDrawCounts[idx], "[$command/${c.name}] golden draw_count must be 0")
            assertEquals(0, rng.draws, "[$command/${c.name}] resolver must consume 0 draws")

            // Acting log + broadcast byte-match.
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // General after-deltas (exp/ded += 15, phpRound) — no level change in the golden.
            assertAfterGeneral(command, c, draft.general)

            // Actor city (id 17) is UNCHANGED — the def/wall*0.2 hits the DEST city (id 1), not this one.
            assertAfterCity(command, c, draft.city)
        }
    }

    /** Parse each case's `draws.draw_count` straight from the committed fixture JSON (the byte oracle). */
    private fun goldenDrawCounts(command: String): List<Int> {
        val text = Che수몰GoldenTest::class.java.classLoader
            .getResourceAsStream("golden/p2/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray.map { el ->
            el.jsonObject["draws"]!!.jsonObject["draw_count"]!!.jsonPrimitive.int
        }
    }

    /**
     * Build a City from a captured GState INCLUDING the def/wall/secu strategic surface that the shared
     * [P2GoldenSupport.cityFrom] omits. Pins every column the che_수몰 resolver reads/writes.
     */
    private fun cityWithDefenseSurface(cityId: Int, gs: P2GoldenSupport.GState): City = City(
        id = cityId,
        nationId = gs.int("nation"),
        level = gs.intOrNull("level") ?: 8,
        commerce = gs.int("comm"), commerceMax = gs.int("comm_max"),
        agriculture = gs.int("agri"), agricultureMax = gs.int("agri_max"),
        supplyState = 1,
        frontState = gs.intOrNull("front") ?: 0,
        trust = gs.double("trust"),
        security = gs.intOrNull("secu") ?: 0, securityMax = gs.intOrNull("secu_max") ?: 0,
        defense = gs.int("def"), defenseMax = gs.int("def_max"),
        wall = gs.int("wall"), wallMax = gs.int("wall_max"),
        population = gs.intOrNull("pop") ?: 0, populationMax = gs.intOrNull("pop") ?: 0,
    )

    private fun assertAfterGeneral(command: String, c: P2GoldenSupport.Case, g: General) {
        val ag = c.after.general
        val tag = "[$command/${c.name}]"
        assertEquals(ag.int("gold"), g.gold, "$tag general.gold")
        if (ag.has("rice")) assertEquals(ag.int("rice"), g.rice, "$tag general.rice")
        assertEquals(ag.int("experience"), phpRound(g.experience), "$tag general.experience")
        assertEquals(ag.int("dedication"), phpRound(g.dedication), "$tag general.dedication")
        assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "$tag meta.explevel")
    }

    private fun assertAfterCity(command: String, c: P2GoldenSupport.Case, city: City) {
        val ac = c.after.city
        val tag = "[$command/${c.name}]"
        // The actor city is the def/wall SOURCE only when destCity==actorCity; here dest=1 != actor=17,
        // so EVERY captured actor-city column must be byte-identical before==after.
        assertEquals(ac.int("def"), city.defense, "$tag city.def")
        assertEquals(ac.int("wall"), city.wall, "$tag city.wall")
        assertEquals(ac.int("def_max"), city.defenseMax, "$tag city.def_max")
        assertEquals(ac.int("wall_max"), city.wallMax, "$tag city.wall_max")
        assertEquals(ac.int("comm"), city.commerce, "$tag city.comm")
        assertEquals(ac.int("agri"), city.agriculture, "$tag city.agri")
        if (ac.has("pop")) assertEquals(ac.int("pop"), city.population, "$tag city.pop")
        assertEquals(ac.double("trust"), city.trust, 1e-9, "$tag city.trust")
    }
}
