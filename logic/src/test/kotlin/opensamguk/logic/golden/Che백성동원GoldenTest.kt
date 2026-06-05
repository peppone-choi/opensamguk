package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_백성동원 PHP-golden byte gate (F4-C3 chief, P2 strategic family).
 *
 * Drives the REAL Kotlin resolver via [CommandRegistry] for the che_백성동원 golden
 * (`golden/p2/che_백성동원-fixtures.json`, hiddenSeed a2db167c.., acting gid 152, city 17),
 * seeded with the exact 6-component PHP seed, byte-comparing:
 *   - the registry-resolved key (registry resolution REQUIRED),
 *   - the GUARD-asserted reconstructed 6-token seedString (byte-equal the PHP oracle),
 *   - the RNG draw stream (this command is DETERMINISTIC → draw_count == 0, no draws),
 *   - the ordered acting `logLines` + the `broadcastLines` (pushGlobalActionLog) byte-match,
 *   - the general after-deltas (exp/ded += 5*(preReqTurn+1)=5; phpRound on exp/ded) and the
 *     dest city def/wall raise (def = max(def_max*0.8, def), wall = max(wall_max*0.8, wall)).
 *
 * The fixture city carries def/wall/def_max/wall_max (the strategic surface), which the shared
 * [P2GoldenSupport.cityFrom] does NOT map (it omits the defense surface) — so this gate builds the
 * City directly from the captured GState, pinning every column the resolver reads/writes.
 */
class Che백성동원GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `che_백성동원 golden byte-matches the PHP action log + def-wall raise + exp-ded`() {
        val command = "che_백성동원"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        // REGISTRY RESOLUTION required — the wired key must be the seed-token / golden command.
        assertEquals(command, def.key, "$command registry key")

        for (c in f.cases) {
            val general = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
            val city = cityWithDefenseSurface(c.cityId, c.before.city)
            val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
            val draft = GeneralActionDraft(general, city, nation)

            // ctxFor GUARD-asserts the reconstructed 6-token seed byte-equals c.seedString + builds the RNG.
            val ctx = P2GoldenSupport.ctxFor(
                f, c, draft, def.rawClassName,
                generalName = P2GoldenSupport.nameOf(c.generalId),
            )

            def.resolve(ctx)

            // RNG draw stream — che_백성동원 is DETERMINISTIC: ZERO draws (golden draw_count == 0).
            // RandUtil exposes no counter, so prove the stream was NOT advanced: a fresh RNG over the
            // SAME 6-token seed must yield the identical first draw as the post-resolve RNG (cursor at 0).
            val fresh = RandUtil(LiteHashDrbg(
                serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)))
            assertEquals(
                fresh.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
                "[$command/${c.name}] resolver must consume 0 draws (RNG stream unadvanced)")

            // Acting log + broadcast byte-match.
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // General after-deltas (exp/ded += 5, phpRound) — no level change in the golden.
            assertAfterGeneral(command, c, draft.general)

            // Dest city = actor city (cityId 17). def/wall raise.
            assertAfterCity(command, c, draft.city)
        }
    }

    /**
     * Build a City from a captured GState INCLUDING the def/wall/secu strategic surface that the shared
     * [P2GoldenSupport.cityFrom] omits. Pins every column the che_백성동원 resolver reads/writes.
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
        assertEquals(ac.int("def"), city.defense, "$tag city.def")
        assertEquals(ac.int("wall"), city.wall, "$tag city.wall")
        assertEquals(ac.int("def_max"), city.defenseMax, "$tag city.def_max")
        assertEquals(ac.int("wall_max"), city.wallMax, "$tag city.wall_max")
        // Unchanged columns (def/wall raise must not perturb the rest).
        assertEquals(ac.int("comm"), city.commerce, "$tag city.comm")
        assertEquals(ac.int("agri"), city.agriculture, "$tag city.agri")
        if (ac.has("pop")) assertEquals(ac.int("pop"), city.population, "$tag city.pop")
        assertEquals(ac.double("trust"), city.trust, 1e-9, "$tag city.trust")
    }
}
