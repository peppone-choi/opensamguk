package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.items.ItemRegistry
import opensamguk.logic.stats.GeneralActionModuleFactory
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.traits.PersonalityRegistry
import opensamguk.logic.traits.SpecialDomesticRegistry
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AREA GATE-TRAIT — Task GT1: the non-identity (module-bearing) 9-source stat-stack byte gate.
 *
 * The 28 committed P2 goldens are MODULE-FREE (the GR1 HARD assertion forced special/special2/personal ∈
 * {None,'',null} + every equipment slot a None item), so the empty `GeneralActionPipeline` folds as the
 * identity. That proves log/mutation fidelity but it can never exercise the real multi-source fold.
 *
 * This test consumes a DEDICATED module-BEARING golden (golden/p2/che_농지개간-nonidentity-fixtures.json,
 * captured by tools/php-golden/capture_nonidentity.php on its own install — its own `hiddenSeed` is the
 * byte oracle, NOT the cff8658592... module-free install). The actor is scenario_1010 gid 14 공융, who
 * NATURALLY carries THREE non-identity stack sources, folding across BOTH calc pipelines:
 *
 *   #1 nationType    che_유가 (CheYuga)            — onCalcDomestic('농업'): score×1.1, cost×0.8
 *   #2 officer       officer_level 1, nationLevel 7 — identity here (the +5% '농업' score buff gates on
 *                                                     officerLevel ∈ {12,11,9,7,5,3}; lvl 1 is excluded; the
 *                                                     leadership lbonus never touches intel/experience)
 *   #3 specialDomestic che_경작 (CheCultivate)     — onCalcDomestic('농업'): score×1.1, cost×0.8, success+0.1
 *   #5 personality   che_왕좌 (PersonalityWangjwa) — onCalcStat('experience'): value×1.1
 *
 * PHP grand-truth folds (legacy/devsam-core/hwe/sammo, verified live on the capture install):
 *   - cost   : develCost 20 → 유가 ×0.8 = 16 → 경작 ×0.8 = 12.8 → Util::round(12.8) = 13  (reqGold)
 *   - score  : base 100      → 유가 ×1.1 = 110 → 경작 ×1.1 = 121
 *   - success: base 0.5      → (유가 no success hook) → 경작 +0.1 = 0.6
 *   - exp    : score×0.7     → 왕좌 onCalcStat('experience') ×1.1   (via General::addExperience, affectTrigger)
 *
 * The byte oracle is the captured action log + the captured after-state (gold/exp/ded/intel_exp/agri/...).
 * The same resolver path as DevelopGoldenTest is driven, but the pipeline is built from the actor's REAL
 * module list via [GeneralActionModuleFactory] in PHP `getActionList` order (nationType→officer→
 * specialDomestic→personality→items). This proves the 9-source ordered fold + the rounding order
 * byte-faithfully — the F-PIPELINE / S-MODULES / TRAITS-* contract.
 */
class NonIdentityFoldGoldenTest {

    private val factory = GeneralActionModuleFactory(
        nationTypeRegistry = NationTypeRegistry,
        specialDomesticRegistry = SpecialDomesticRegistry,
        personalityRegistry = PersonalityRegistry(),
        itemRegistry = ItemRegistry(),
    )

    /** scenario_1010: gid 14 nation = 1 후한, nation level 7 (probed live on the capture install). */
    private val nationLevel = 7

    /** The actor's pinned non-identity sources (asserted against the fixture's top-level fields). */
    private val specialCode = "che_경작"
    private val personalCode = "che_왕좌"

    @Test
    fun `che_농지개간 by a 유가-경작-왕좌 general byte-matches the non-identity PHP golden`() {
        val f = P2GoldenSupport.load("che_농지개간-nonidentity")
        assertEquals("che_농지개간", f.command, "fixture command")
        // pin the fixture's module codes (the byte oracle's WHICH-modules contract).
        assertFixtureModules(specialCode, personalCode)

        for (c in f.cases) {
            // Build the REAL module list for the actor: nationType che_유가 + officer(lvl1,natlvl7) +
            // specialDomestic che_경작 + personality che_왕좌. Items stay None (identity).
            val actorGeneral = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
            val modules = factory.build(
                general = actorGeneral,
                nationTypeCode = "che_유가",
                specialDomesticCode = specialCode,   // che_경작 (pinned in the fixture)
                personalityCode = personalCode,      // che_왕좌
                nationLevel = nationLevel,
            )
            // sanity: exactly the four effective sources fold (nationType + officer + specialDomestic +
            // personality); the empty item slots add nothing.
            assertEquals(4, modules.size, "[${c.name}] effective module count (유가+officer+경작+왕좌)")

            val pipeline = GeneralActionPipeline(modules)
            val registry = CommandRegistry(pipeline)
            val def = registry.resolve("che_농지개간")

            val nation = Nation(
                id = c.before.general.int("nation"),
                level = nationLevel,
                capitalCityId = P2GoldenSupport.capitalOf(c.before.general.int("nation")),
                name = P2GoldenSupport.nationNameOf(c.before.general.int("nation")),
                typeCode = "che_유가",
                gold = 100000, rice = 100000,
                tech = P2GoldenSupport.techOf(c.before.general.int("nation")),
            )
            val draft = GeneralActionDraft(
                actorGeneral,
                P2GoldenSupport.cityFrom(c.cityId, c.before.city),
                nation,
            )
            val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName)
            def.resolve(ctx)

            // ── byte-match the action log + broadcast ──
            assertEquals(c.logLines, ctx.logs(), "[${c.name}] non-identity action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[${c.name}] broadcast byte-match")

            // ── byte-match the post-state (the multi-source fold's numeric footprint) ──
            val ag = c.after.general
            val ac = c.after.city
            val tag = "[${c.name}]"
            // reqGold = round(20 ×0.8(유가) ×0.8(경작)) = 13 — the cost fold proof.
            assertEquals(13, c.reqGold, "$tag cost-fold reqGold (유가×0.8×경작×0.8 round)")
            assertEquals(ag.int("gold"), draft.general.gold, "$tag general.gold (1000 - 13)")
            // experience carries 왕좌 ×1.1 (the onCalcStat fold) — the part DevelopGoldenTest can never reach.
            assertEquals(ag.int("experience"), phpRound(draft.general.experience), "$tag general.experience (왕좌 ×1.1)")
            assertEquals(ag.int("dedication"), phpRound(draft.general.dedication), "$tag general.dedication")
            assertEquals(ag.int("intel_exp"), metaInt(draft.general.meta, "intel_exp"), "$tag meta.intel_exp")
            assertEquals(ag.int("explevel"), metaInt(draft.general.meta, "explevel"), "$tag meta.explevel")
            assertEquals(ag.double("max_domestic_critical"), metaDouble(draft.general.meta, "max_domestic_critical"), 1e-9,
                "$tag meta.max_domestic_critical")
            // agri carries 유가×1.1 × 경작×1.1 on the score — the domestic fold proof.
            assertEquals(ac.int("agri"), draft.city.agriculture, "$tag city.agri (유가×1.1 × 경작×1.1 score)")
            assertEquals(ac.int("agri_max"), draft.city.agricultureMax, "$tag city.agri_max")
            assertEquals(ac.double("trust"), draft.city.trust, 1e-9, "$tag city.trust")
        }
    }

    /** Assert the fixture's top-level special/personal match the modules this test folds. */
    private fun assertFixtureModules(expectSpecial: String, expectPersonal: String) {
        val root = fixtureRoot()
        assertEquals(expectSpecial, (root["special"] as kotlinx.serialization.json.JsonPrimitive).content,
            "fixture special must pin the folded specialDomestic")
        assertEquals(expectPersonal, (root["personal"] as kotlinx.serialization.json.JsonPrimitive).content,
            "fixture personal must pin the folded personality")
    }

    private fun fixtureRoot(): kotlinx.serialization.json.JsonObject {
        val text = NonIdentityFoldGoldenTest::class.java.classLoader
            .getResourceAsStream("golden/p2/che_농지개간-nonidentity-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
    }

    /**
     * Guard the captured fixture's own module-fold observability: the dump recorded, per case, the
     * module-FREE after-state the SAME seed/state produces with an empty pipeline. Assert the captured
     * module-BEARING after-state DIFFERS from it — i.e. the golden genuinely exercises the 9-source fold
     * (a module-free re-capture, or a fold that collapsed to identity, would trip this RED). This reads the
     * `moduleFold` evidence block straight from the JSON (it is not on the parsed Case DTO).
     */
    @Test
    fun `the non-identity fixture's captured fold is observable (not identity)`() {
        val cases = (fixtureRoot()["cases"] as kotlinx.serialization.json.JsonArray)
        assertTrue(cases.isNotEmpty(), "fixture has cases")
        for (el in cases) {
            val o = el as kotlinx.serialization.json.JsonObject
            val mf = o["moduleFold"] as kotlinx.serialization.json.JsonObject
            val name = (o["case"] as kotlinx.serialization.json.JsonPrimitive).content
            val observable = (mf["observable"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean()
            assertTrue(observable, "[$name] captured module fold must be observable (golden tests the real stack)")
        }
    }
}
