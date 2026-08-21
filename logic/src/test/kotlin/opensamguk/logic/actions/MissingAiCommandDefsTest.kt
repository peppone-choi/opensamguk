package opensamguk.logic.actions

import opensamguk.common.constants.CityConst
import opensamguk.logic.actions.develop.cheGyeonmun
import opensamguk.logic.actions.founding.CheHaesan
import opensamguk.logic.actions.founding.CheSeonyang
import opensamguk.logic.actions.military.CheGwihwan
import opensamguk.logic.actions.military.CheNpcNeungdong
import opensamguk.logic.actions.nation.cheBulgachimJeui
import opensamguk.logic.actions.nation.cheSeonjeonpogo
import opensamguk.logic.actions.personnel.CheInjaeTamsaek
import opensamguk.logic.actions.personnel.CheYoyang
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task FR2 — the 9 missing AI-emitted `che_*` command defs + registry registration (F-BRIDGE).
 *
 * Port oracle = PHP `Command/Nation/{che_불가침제의,che_선전포고}.php` +
 * `Command/General/{che_NPC능동,che_귀환,che_인재탐색,che_견문,che_해산,che_요양,che_선양}.php`.
 *
 * The AI emits these `che_*` codes; the bridge gate (FR3) evaluates each def's `buildConstraints`
 * (= PHP `fullConditionConstraints`, in PHP ORDER, first-deny-wins) AFTER the `argTest` gate
 * (canonicalize + reject on a missing required key with `'인자가 올바르지 않습니다.'`). FR2 ports the
 * defs + their argTest + their FULL packs and registers all 9 in `CommandRegistry.resolve`.
 *
 * NOTE (R-BRIDGE §1): `permissionConstraints` (e.g. NPC능동's MustBeNPC) are a SEPARATE reserve-time
 * gate (BaseCommand.php:450-451 hasPermissionToReserve) — NOT part of `hasFullConditionMet`
 * (BaseCommand.php:377-413 = isArgValid → testAll(fullConditionConstraints) → testPostReqTurn). So
 * NPC능동's FULL pack is EMPTY; the AI's bridge boolean passes on argValid alone.
 */
class MissingAiCommandDefsTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private fun ctx(
        args: Map<String, Any?> = emptyMap(),
        env: Map<String, Any?> = linkedMapOf<String, Any?>("year" to 200, "startYear" to 180, "startyear" to 180, "month" to 1),
    ) = ConstraintContext(
        actorId = 1, cityId = 1, nationId = 1, destNationId = 2, destGeneralId = 3, destCityId = 6,
        args = args, env = env, mode = ConstraintMode.FULL,
    )

    // ---------------- registry resolution ----------------

    @Test fun `all 9 missing defs resolve from CommandRegistry`() {
        assertEquals("che_불가침제의", registry.resolve("che_불가침제의").key)
        assertEquals("che_선전포고", registry.resolve("che_선전포고").key)
        assertEquals("che_NPC능동", registry.resolve("che_NPC능동").key)
        assertEquals("che_귀환", registry.resolve("che_귀환").key)
        assertEquals("che_인재탐색", registry.resolve("che_인재탐색").key)
        assertEquals("che_견문", registry.resolve("che_견문").key)
        assertEquals("che_해산", registry.resolve("che_해산").key)
        assertEquals("che_요양", registry.resolve("che_요양").key)
        assertEquals("che_선양", registry.resolve("che_선양").key)
    }

    @Test fun `none of the 9 fall through to RestAction`() {
        for (code in listOf("che_불가침제의", "che_선전포고", "che_NPC능동", "che_귀환", "che_인재탐색", "che_견문", "che_해산", "che_요양", "che_선양")) {
            assertTrue(registry.resolve(code) !== RestAction, "$code must not fall through to RestAction")
        }
    }

    // ---------------- che_불가침제의 (nation, reqArg year/month) ----------------

    @Test fun `bulgachimJeui FULL pack — over-6mo path is the 5-constraint diplomacy pack in PHP order`() {
        // reqMonth (year*12+month-1) >= currentMonth + 6 → the real pack (year 201, month 1 vs env 200/1).
        val names = cheBulgachimJeui(pipeline)
            .buildConstraints(ctx(args = linkedMapOf("destNationID" to 2, "year" to 201, "month" to 7)))
            .map { it.name }
        assertEquals(
            listOf("BeChief", "NotBeNeutral", "ExistsDestNation", "DifferentDestNation", "DisallowDiplomacyBetweenStatus"),
            names,
        )
    }

    @Test fun `bulgachimJeui FULL pack — under-6mo path is AlwaysFail`() {
        val cons = cheBulgachimJeui(pipeline)
            .buildConstraints(ctx(args = linkedMapOf("destNationID" to 2, "year" to 200, "month" to 2)))
        assertEquals(listOf("AlwaysFail"), cons.map { it.name })
    }

    @Test fun `bulgachimJeui argTest canonicalizes destNationID + year + month`() {
        val def = cheBulgachimJeui(pipeline)
        assertEquals(
            linkedMapOf<String, Any?>("destNationID" to 2, "year" to 201, "month" to 7),
            def.argTest(mapOf("destNationID" to 2, "year" to 201, "month" to 7, "extra" to "x"), startYear = 180),
        )
    }

    @Test fun `bulgachimJeui argTest rejects missing destNationID, bad month, pre-startyear`() {
        val def = cheBulgachimJeui(pipeline)
        assertNull(def.argTest(mapOf("year" to 201, "month" to 7), startYear = 180), "missing destNationID")
        assertNull(def.argTest(mapOf("destNationID" to 0, "year" to 201, "month" to 7), startYear = 180), "destNationID < 1")
        assertNull(def.argTest(mapOf("destNationID" to 2, "year" to 201, "month" to 13), startYear = 180), "month > 12")
        assertNull(def.argTest(mapOf("destNationID" to 2, "year" to 179, "month" to 7), startYear = 180), "year < startyear")
    }

    // ---------------- che_선전포고 (nation, reqArg destNationID) ----------------

    @Test fun `seonjeonpogo FULL pack in PHP order (incl ExistsDestNation + NearNation)`() {
        val names = cheSeonjeonpogo(pipeline)
            .buildConstraints(ctx(args = linkedMapOf("destNationID" to 2)))
            .map { it.name }
        assertEquals(
            listOf("BeChief", "NotBeNeutral", "OccupiedCity", "SuppliedCity", "ReqEnvValue", "ExistsDestNation", "NearNation", "DisallowDiplomacyBetweenStatus"),
            names,
        )
    }

    @Test fun `seonjeonpogo argTest canonicalizes to destNationID only`() {
        val def = cheSeonjeonpogo(pipeline)
        assertEquals(linkedMapOf<String, Any?>("destNationID" to 2), def.argTest(mapOf("destNationID" to 2, "junk" to 9)))
        assertNull(def.argTest(mapOf("destNationID" to 0)), "destNationID < 1")
        assertNull(def.argTest(emptyMap()), "missing destNationID")
    }

    // ---------------- che_NPC능동 (general, EMPTY fullCond) ----------------

    @Test fun `npcNeungdong FULL pack is EMPTY (MustBeNPC is permission-only, not the full gate)`() {
        assertEquals(listOf("ActiveMapDestCity"), CheNpcNeungdong(pipeline).buildConstraints(ctx()).map { it.name })
    }

    @Test fun `npcNeungdong argTest requires optionText 순간이동 + a real destCityID`() {
        val def = CheNpcNeungdong(pipeline)
        val anyCity = CityConst.all().keys.first()
        assertEquals(
            linkedMapOf<String, Any?>("optionText" to "순간이동", "destCityID" to anyCity),
            def.argTest(mapOf("optionText" to "순간이동", "destCityID" to anyCity)),
        )
        assertNull(def.argTest(mapOf("optionText" to "순간이동")), "missing destCityID")
        assertNull(def.argTest(mapOf("optionText" to "순간이동", "destCityID" to -999)), "unknown city")
        assertNull(def.argTest(mapOf("optionText" to "기타")), "non-순간이동 optionText")
        assertNull(def.argTest(emptyMap()), "missing optionText")
    }

    @Test fun `npcNeungdong accepts a Han-only destination id`() {
        val warp = "\uC21C\uAC04\uC774\uB3D9"
        val parsed = CheNpcNeungdong(pipeline).argTest(
            mapOf("optionText" to warp, "destCityID" to 421),
        )
        assertEquals(linkedMapOf<String, Any?>("optionText" to warp, "destCityID" to 421), parsed)
    }

    // ---------------- che_귀환 / 인재탐색 / 견문 / 요양 (no-arg) ----------------

    @Test fun `gwihwan FULL pack in PHP order`() {
        assertEquals(listOf("NotBeNeutral", "NotWanderingNation", "NotCapital"), CheGwihwan(pipeline).buildConstraints(ctx()).map { it.name })
    }

    @Test fun `injaeTamsaek FULL pack is ReqGeneralGold then ReqGeneralRice`() {
        assertEquals(listOf("ReqGeneralGold", "ReqGeneralRice"), CheInjaeTamsaek(pipeline).buildConstraints(ctx()).map { it.name })
    }

    @Test fun `gyeonmun FULL pack is EMPTY`() {
        assertEquals(emptyList(), cheGyeonmun(pipeline).buildConstraints(ctx()).map { it.name })
    }

    @Test fun `yoyang FULL pack is EMPTY and is gate-exempt-marked`() {
        val def = CheYoyang(pipeline)
        assertEquals(emptyList(), def.buildConstraints(ctx()).map { it.name })
        assertTrue(def.gateExempt, "che_요양 is dispatcher-direct, gate-exempt (G14)")
    }

    @Test fun `no-arg defs argTest returns empty canonical map`() {
        assertEquals(emptyMap(), CheGwihwan(pipeline).parseArgs(mapOf("x" to 1)))
        assertEquals(emptyMap(), CheInjaeTamsaek(pipeline).parseArgs(mapOf("x" to 1)))
        assertEquals(emptyMap(), cheGyeonmun(pipeline).parseArgs(mapOf("x" to 1)))
        assertEquals(emptyMap(), CheYoyang(pipeline).parseArgs(mapOf("x" to 1)))
    }

    // ---------------- che_해산 (BeLord + WanderingNation) ----------------

    @Test fun `haesan FULL pack is BeLord then WanderingNation`() {
        assertEquals(listOf("BeLord", "WanderingNation"), CheHaesan(pipeline).buildConstraints(ctx()).map { it.name })
    }

    // ---------------- che_선양 (BeLord + ExistsDestGeneral + FriendlyDestGeneral, ORDER BY RAND quarantine) ----------------

    @Test fun `seonyang FULL pack in PHP order`() {
        assertEquals(
            listOf("BeLord", "ExistsDestGeneral", "FriendlyDestGeneral"),
            CheSeonyang(pipeline).buildConstraints(ctx(args = linkedMapOf("destGeneralID" to 3))).map { it.name },
        )
    }

    @Test fun `seonyang argTest rejects self-target and non-positive`() {
        val def = CheSeonyang(pipeline)
        assertEquals(linkedMapOf<String, Any?>("destGeneralID" to 3), def.argTest(mapOf("destGeneralID" to 3, "junk" to 1), selfId = 1))
        assertNull(def.argTest(mapOf("destGeneralID" to 1), selfId = 1), "self-target")
        assertNull(def.argTest(mapOf("destGeneralID" to 0), selfId = 1), "destGeneralID <= 0")
        assertNull(def.argTest(emptyMap(), selfId = 1), "missing destGeneralID")
    }

    @Test fun `seonyang carries the ORDER BY RAND parity-quarantine note`() {
        assertTrue(CheSeonyang(pipeline).orderByRandQuarantine, "che_선양 do선양 uses MySQL ORDER BY RAND() — G4 quarantine")
    }
}
