package opensamguk.logic.constraints

import opensamguk.common.constants.GameConst
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FND1 — founding-preset CONSUMPTION verification.
 *
 * The founding family (거병 / 건국 / cr_건국 / 무작위건국) reserves these presets, all OWNED by
 * C-PURE/C-DEST (Presets.kt — FND1 does NOT define them):
 *   - ConstructableCity()                  (che_건국 — level 5/6 neutral)            [C-PURE]
 *   - NeutralCity()                        (cr_건국 — neutral-only, no level)         [C-PURE]
 *   - WanderingNation()                    (건국 family — level==0, NO trailing period) [C-PURE]
 *   - ReqNationValue('gennum','수하 장수','>=',2)  (건국 family — 2+ retainers)         [C-PURE]
 *   - BeOpeningPart(relYear)               (all founding — opening-part window)       [C-PURE]
 *   - NotOpeningPart(relYear)              (방랑 — the inverse window)                [C-PURE]
 *   - CheckNationNameDuplicate(name)       (건국 family — runtime dup name)           [C-DEST]
 *   - AllowDiplomacyStatus(errMsg, …)      (방랑 — wanderable diplomacy)              [C-DEST]
 *
 * This test asserts each founding deny+allow against the byte-exact PHP reason strings (Constraint/
 * [Name].php grand truth), in particular the no-trailing-period `방랑군이어야 합니다` wandering reason.
 * It CONSUMES the C-PURE/C-DEST presets through the same StateView the daemon/precheck wire.
 */
class PresetsFoundingTest {

    private fun general(
        id: Int = 1,
        nationId: Int = 1,
        cityId: Int = 5,
        officerLevel: Int = 12,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = 1000, rice = 1000, meta = meta,
    )

    private fun city(
        id: Int = 5,
        nationId: Int = 0,
        level: Int = 5,
    ) = City(
        id = id, nationId = nationId, level = level,
        commerce = 100, commerceMax = 1000,
        agriculture = 100, agricultureMax = 1000,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun nation(
        id: Int = 1,
        level: Int = 0,
        gennum: Int = 2,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = Nation(id = id, level = level, capitalCityId = null, name = "방랑", gennum = gennum, meta = meta)

    private fun view(
        g: General = general(),
        c: City = city(),
        n: Nation = nation(),
        nations: Map<Int, Nation> = mapOf(n.id to n),
    ) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = mapOf(c.id to c),
        nations = nations,
        env = emptyMap(),
    )

    private fun ctx(
        actorId: Int = 1,
        cityId: Int? = 5,
        nationId: Int? = 1,
        args: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(actorId = actorId, cityId = cityId, nationId = nationId, args = args, mode = ConstraintMode.FULL)

    private fun deny(r: ConstraintResult): String {
        assertTrue(r is ConstraintResult.Deny, "expected Deny but was $r")
        return (r as ConstraintResult.Deny).reason
    }

    private fun allow(r: ConstraintResult) =
        assertTrue(r is ConstraintResult.Allow, "expected Allow but was $r")

    // --- ConstructableCity (che_건국) — PHP ConstructableCity.php ---

    @Test fun `constructableCity allows a neutral 중소 city`() =
        allow(constructableCity().test(ctx(), view(c = city(nationId = 0, level = 5))))

    @Test fun `constructableCity denies an occupied city with 공백지가 아닙니다`() =
        assertEquals("공백지가 아닙니다.", deny(constructableCity().test(ctx(), view(c = city(nationId = 9, level = 5)))))

    @Test fun `constructableCity denies a wrong-level city with 중 소 도시에만 가능합니다`() =
        assertEquals("중, 소 도시에만 가능합니다.", deny(constructableCity().test(ctx(), view(c = city(nationId = 0, level = 7)))))

    // --- NeutralCity (cr_건국) — PHP NeutralCity.php ---

    @Test fun `neutralCity allows a neutral city`() =
        allow(neutralCity().test(ctx(), view(c = city(nationId = 0))))

    @Test fun `neutralCity denies an occupied city with 공백지가 아닙니다`() =
        assertEquals("공백지가 아닙니다.", deny(neutralCity().test(ctx(), view(c = city(nationId = 9)))))

    // --- WanderingNation (건국 family) — the NO-trailing-period divergence ---

    @Test fun `wanderingNation allows a level-0 nation`() =
        allow(wanderingNation().test(ctx(), view(n = nation(level = 0))))

    @Test fun `wanderingNation denies a real nation with the no-trailing-period reason`() {
        val reason = deny(wanderingNation().test(ctx(), view(n = nation(level = 3))))
        assertEquals("방랑군이어야 합니다", reason)
        assertFalse(reason.endsWith("."), "WanderingNation reason must NOT end with a period (TS-divergent)")
    }

    // --- ReqNationValue('gennum','수하 장수','>=',2) (건국 family) ---

    @Test fun `reqNationValue gennum allows 2 or more retainers`() =
        allow(reqNationValue("gennum", "수하 장수", ">=", 2).test(ctx(), view(n = nation(gennum = 2))))

    @Test fun `reqNationValue gennum denies fewer than 2 retainers`() =
        assertEquals("수하 장수가 부족합니다.",
            deny(reqNationValue("gennum", "수하 장수", ">=", 2).test(ctx(), view(n = nation(gennum = 1)))))

    // --- BeOpeningPart / NotOpeningPart (founding window) ---

    @Test fun `beOpeningPart allows inside the opening window`() =
        allow(beOpeningPart { _, _ -> GameConst.openingPartYear - 1 }.test(ctx(), view()))

    @Test fun `beOpeningPart denies past the opening window with 초반이 지났습니다`() =
        assertEquals("초반이 지났습니다.",
            deny(beOpeningPart { _, _ -> GameConst.openingPartYear }.test(ctx(), view())))

    @Test fun `notOpeningPart denies inside the opening window with 초반 제한 중에는 불가능합니다`() =
        assertEquals("초반 제한 중에는 불가능합니다.",
            deny(notOpeningPart { _, _ -> 0 }.test(ctx(), view())))

    // --- CheckNationNameDuplicate (건국 family) — C-DEST ---

    @Test fun `checkNationNameDuplicate allows a fresh name`() =
        allow(checkNationNameDuplicate().test(
            ctx(args = mapOf("name" to "신생국")),
            view(nations = mapOf(1 to nation(id = 1), 9 to nation(id = 9, meta = linkedMapOf()).copy(name = "위"))),
        ))

    @Test fun `checkNationNameDuplicate denies a name another nation already carries`() =
        assertEquals("존재하는 국가명입니다.", deny(checkNationNameDuplicate().test(
            ctx(args = mapOf("name" to "위")),
            view(nations = mapOf(1 to nation(id = 1), 9 to nation(id = 9).copy(name = "위"))),
        )))

    // --- AllowDiplomacyStatus (방랑) — C-DEST preloaded-predicate form ---

    @Test fun `allowDiplomacyStatus allows when a wanderable diplomacy row exists`() =
        allow(allowDiplomacyStatus("방랑할 수 없는 외교상태입니다.") { _, _ -> true }.test(ctx(), view()))

    @Test fun `allowDiplomacyStatus denies with the caller errMsg when none exists`() =
        assertEquals("방랑할 수 없는 외교상태입니다.",
            deny(allowDiplomacyStatus("방랑할 수 없는 외교상태입니다.") { _, _ -> false }.test(ctx(), view())))
}
