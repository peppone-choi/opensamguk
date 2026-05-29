package opensamguk.logic.actions

import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.domain.WorldEnv
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FS1 — action-substrate widening.
 *
 * Pins the new [GeneralActionDefinition] surface (min-vs-full constraint split, parseArgs,
 * argsSchema, reservable, category, the 6th-RNG-seed `rawClassName`, and the SEPARATE lottery
 * `lotteryActionName` token), the [GeneralActionDraft] dest/created-set/cascade carriers, and the
 * three new [GeneralActionResolveContext] log scopes (addLogTo / addGlobalActionLog / addPlainLog),
 * keeping the P1 addLog (MONTH prefix) verbatim.
 *
 * Faithful to PHP `BaseCommand.php` (getRawClassName=getClassNameFromObj, the 6th seed token) +
 * `ActionLogger.php:135-251` (MONTH `<C>●</>{m}월:` vs PLAIN `<C>●</>` vs the separate global bucket).
 */
class ActionSubstrateTest {

    private fun general(id: Int = 1, nationId: Int = 1, cityId: Int = 1) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 1000, rice = 1000,
    )

    private fun city(id: Int = 1) = City(
        id = id, nationId = 1, level = 5,
        commerce = 100, commerceMax = 1000, agriculture = 100, agricultureMax = 1000,
        supplyState = 1, frontState = 0, trust = 100.0,
    )

    private fun nation(id: Int = 1) = Nation(id = id, level = 1, capitalCityId = 1)

    private val env = WorldEnv(year = 190, startYear = 184, develCost = 100)

    private val allow = object : Constraint {
        override val name = "allow"
        override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
        override fun test(ctx: ConstraintContext, view: StateView) = ConstraintResult.Allow
    }
    private val deny = object : Constraint {
        override val name = "deny"
        override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
        override fun test(ctx: ConstraintContext, view: StateView) = ConstraintResult.Deny("nope", "deny")
    }

    private fun ctx() = ConstraintContext(actorId = 1, cityId = 1, nationId = 1, mode = ConstraintMode.FULL)

    // ---- DEFINITION: min-vs-full split ----

    @Test
    fun `default buildMinConstraints delegates to buildConstraints`() {
        val def = object : GeneralActionDefinition {
            override val key = "che_x"
            override val name = "x"
            override fun buildConstraints(ctx: ConstraintContext) = listOf(allow, deny)
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        assertEquals(def.buildConstraints(ctx()), def.buildMinConstraints(ctx()))
    }

    @Test
    fun `a definition may split min from full constraints`() {
        val def = object : GeneralActionDefinition {
            override val key = "che_split"
            override val name = "split"
            override fun buildMinConstraints(ctx: ConstraintContext) = listOf(allow)
            override fun buildConstraints(ctx: ConstraintContext) = listOf(allow, deny)
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        assertEquals(listOf(allow), def.buildMinConstraints(ctx()))
        assertEquals(listOf(allow, deny), def.buildConstraints(ctx()))
        assertTrue(def.buildMinConstraints(ctx()) != def.buildConstraints(ctx()))
    }

    // ---- DEFINITION: defaults (parseArgs identity, reservable, category, schema, tokens) ----

    @Test
    fun `parseArgs defaults to identity and other metadata has sane defaults`() {
        val def = object : GeneralActionDefinition {
            override val key = "che_무휴식"
            override val name = "무휴식"
            override fun buildConstraints(ctx: ConstraintContext) = emptyList<Constraint>()
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        val raw = mapOf<String, Any?>("destCityID" to 5, "amount" to 100)
        assertEquals(raw, def.parseArgs(raw))     // identity
        assertTrue(def.reservable)                 // default reservable
        assertEquals(emptyMap(), def.argsSchema)   // no declared args by default
        assertEquals("기타", def.category)          // default category
        // rawClassName defaults to key (most commands: class == che_+de-spaced-name == key)
        assertEquals("che_무휴식", def.rawClassName)
        // lottery token defaults to the (spaced) action name
        assertEquals("무휴식", def.lotteryActionName)
    }

    @Test
    fun `parseArgs can be overridden and reservable flipped`() {
        val def = object : GeneralActionDefinition {
            override val key = "che_등용수락"
            override val name = "등용 수락"
            override val reservable = false
            override val category = "인사"
            override val argsSchema = mapOf<String, Any?>("destGeneralID" to "int")
            override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> =
                mapOf("destGeneralID" to (raw["destGeneralID"] as? Number)?.toInt())
            override fun buildConstraints(ctx: ConstraintContext) = emptyList<Constraint>()
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        assertFalse(def.reservable)
        assertEquals("인사", def.category)
        assertEquals(mapOf("destGeneralID" to "int"), def.argsSchema)
        assertEquals(mapOf("destGeneralID" to 7), def.parseArgs(mapOf("destGeneralID" to 7.0)))
    }

    // ---- DEFINITION: rawClassName vs lotteryActionName divergence (the 6th-seed parity hook) ----

    @Test
    fun `divergent commands carry rawClassName distinct from de-spaced name and a separate lottery token`() {
        // che_랜덤임관: class name che_랜덤임관, but actionName (lottery token) = 무작위 국가로 임관
        val randomJoin = object : GeneralActionDefinition {
            override val key = "che_랜덤임관"
            override val name = "무작위 국가로 임관"
            override val rawClassName = "che_랜덤임관"
            override val lotteryActionName = "무작위 국가로 임관"
            override fun buildConstraints(ctx: ConstraintContext) = emptyList<Constraint>()
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        // rawClassName (6th seed token) is NOT "che_" + de-spaced name
        assertTrue(randomJoin.rawClassName != "che_${randomJoin.name.replace(" ", "")}")
        assertEquals("che_랜덤임관", randomJoin.rawClassName)
        assertEquals("무작위 국가로 임관", randomJoin.lotteryActionName)

        // cr_건국 is DISTINCT from che_건국 by rawClassName, same lottery token 건국
        val crFound = object : GeneralActionDefinition {
            override val key = "cr_건국"
            override val name = "건국"
            override val rawClassName = "cr_건국"
            override val lotteryActionName = "건국"
            override fun buildConstraints(ctx: ConstraintContext) = emptyList<Constraint>()
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        val cheFound = object : GeneralActionDefinition {
            override val key = "che_건국"
            override val name = "건국"
            override val rawClassName = "che_건국"
            override val lotteryActionName = "건국"
            override fun buildConstraints(ctx: ConstraintContext) = emptyList<Constraint>()
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        assertTrue(crFound.rawClassName != cheFound.rawClassName)
        assertEquals(crFound.lotteryActionName, cheFound.lotteryActionName)

        // cr_맹훈련: rawClassName cr_맹훈련, lottery token 맹훈련
        val crTrain = object : GeneralActionDefinition {
            override val key = "cr_맹훈련"
            override val name = "맹훈련"
            override val rawClassName = "cr_맹훈련"
            override val lotteryActionName = "맹훈련"
            override fun buildConstraints(ctx: ConstraintContext) = emptyList<Constraint>()
            override fun resolve(context: GeneralActionResolveContext) {}
        }
        assertEquals("cr_맹훈련", crTrain.rawClassName)

        // the 6th-seed token (rawClassName) feeds serializeSeed exactly — distinct class => distinct seed
        val seedCr = serializeSeed("00000000000000000000000000000000", "generalCommand", 190, 3, 42, crFound.rawClassName)
        val seedChe = serializeSeed("00000000000000000000000000000000", "generalCommand", 190, 3, 42, cheFound.rawClassName)
        assertTrue(seedCr != seedChe)
    }

    // ---- DRAFT: dest carriers + created-set + cascade ----

    @Test
    fun `draft carries a dest general and a created nation`() {
        val draft = GeneralActionDraft(general(), city(), nation())
        // defaults null / empty
        assertEquals(null, draft.destGeneral)
        assertEquals(null, draft.destCity)
        assertEquals(null, draft.destNation)
        assertTrue(draft.createdNations.isEmpty())
        assertTrue(draft.createdDiplomacy.isEmpty())
        assertTrue(draft.createdNationTurns.isEmpty())
        assertTrue(draft.cascadeGenerals.isEmpty())
        assertTrue(draft.cascadeCities.isEmpty())
        assertTrue(draft.cascadeDiplomacy.isEmpty())

        // mutate: a dest general (발령/포상) + a created nation (거병)
        draft.destGeneral = general(id = 2, nationId = 0, cityId = 9)
        val newNation = Nation(id = 99, level = 0, capitalCityId = 9, name = "촉")
        draft.createdNations.add(newNation)
        draft.createdDiplomacy.add(Diplomacy(me = 99, you = 1, state = 7))
        draft.createdNationTurns.add(NationTurn(nationId = 99, officerLevel = 12, turnIdx = 0, action = "휴식", brief = "휴식"))
        draft.cascadeGenerals.add(general(id = 3))
        draft.cascadeCities.add(city(id = 4))
        draft.cascadeDiplomacy.add(Diplomacy(me = 1, you = 99, state = 7))

        assertEquals(2, draft.destGeneral?.id)
        assertEquals(listOf(newNation), draft.createdNations)
        assertEquals(1, draft.createdDiplomacy.size)
        assertEquals(1, draft.createdNationTurns.size)
        assertEquals(3, draft.cascadeGenerals.single().id)
        assertEquals(4, draft.cascadeCities.single().id)
        assertEquals(1, draft.cascadeDiplomacy.size)
    }

    // ---- CONTEXT: three new log scopes are distinct buckets ----

    private fun freshContext(): GeneralActionResolveContext = GeneralActionResolveContext(
        draft = GeneralActionDraft(general(), city(), nation()),
        rng = RandUtil(LiteHashDrbg(serializeSeed("00000000000000000000000000000000", "generalCommand", 190, 3, 1, "che_x"))),
        env = env,
        month = 3,
        date = "12:34",
    )

    @Test
    fun `the three log scopes emit distinct buckets and addLog keeps the MONTH prefix`() {
        val c = freshContext()
        c.addLog("본인행동")                         // MONTH prefix (P1, verbatim)
        c.addLogTo(2, "대상행동")                     // dest-general scope (separate bucket)
        c.addLogTo(2, "대상행동2")
        c.addGlobalActionLog("전역방송")              // global bucket (general_id=0), MONTH-format
        c.addPlainLog("방랑군 세력이 이동했습니다. <1>12:34</>")  // PLAIN — no month prefix

        // addLog: MONTH prefix per ActionLogger.php:250 — <C>●</>{month}월:{body}
        assertEquals(listOf("<C>●</>3월:본인행동"), c.logs())

        // addLogTo: a per-target scope keyed by targetGeneralId, two lines bucketed under 2
        assertEquals(listOf("대상행동", "대상행동2"), c.logsTo(2))
        assertTrue(c.logsTo(3).isEmpty())

        // addGlobalActionLog: MONTH-format but a separate global bucket
        assertEquals(listOf("<C>●</>3월:전역방송"), c.globalActionLogs())

        // addPlainLog: PLAIN format <C>●</>{text} — NO month prefix (ActionLogger.php:238)
        assertEquals(listOf("<C>●</>방랑군 세력이 이동했습니다. <1>12:34</>"), c.plainLogs())

        // the self-action bucket is untouched by the other scopes
        assertEquals(1, c.logs().size)
    }

    @Test
    fun `empty text is dropped in every scope`() {
        val c = freshContext()
        c.addLog("")
        c.addLogTo(2, "")
        c.addGlobalActionLog("")
        c.addPlainLog("")
        assertTrue(c.logs().isEmpty())
        assertTrue(c.logsTo(2).isEmpty())
        assertTrue(c.globalActionLogs().isEmpty())
        assertTrue(c.plainLogs().isEmpty())
    }
}
