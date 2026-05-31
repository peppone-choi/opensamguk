package opensamguk.logic.constraints

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR1 — byte-for-byte reason strings for the 5 NEW diplomacy/pathfinding presets
 * (existsDestNation / nearNation / hasRoute / hasRouteWithEnemy /
 * disallowDiplomacyBetweenStatus / allowDiplomacyBetweenStatus / allowDiplomacyWithTerm).
 *
 * Port oracle = PHP `Constraint/{ExistsDestNation,NearNation,HasRoute,HasRouteWithEnemy,
 * DisallowDiplomacyBetweenStatus,AllowDiplomacyBetweenStatus,AllowDiplomacyWithTerm}.php`
 * (reason strings, test order). DB/pathfinding-backed predicates are PRELOADED as lambdas
 * (the CD2 idiom: NO DB / searchDistance inside `test()`).
 */
class DiploPresetsTest {

    private fun general(id: Int = 1, nationId: Int = 1, cityId: Int = 5) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
        crew = 100, train = 50.0, atmos = 50.0, troop = 0,
        npcType = 0, meta = linkedMapOf(),
    )

    private fun nation(id: Int = 1, level: Int = 7, meta: Map<String, Any?> = linkedMapOf()) =
        Nation(id = id, level = level, capitalCityId = 5, gold = 1000, rice = 1000, meta = meta)

    private fun city(id: Int = 5, nationId: Int = 1) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 100, commerceMax = 1000, agriculture = 100, agricultureMax = 1000,
        supplyState = 1, frontState = 0, trust = 50.0, trade = 100,
    )

    private fun view(
        generals: Map<Int, General> = mapOf(1 to general()),
        cities: Map<Int, City> = mapOf(5 to city()),
        nations: Map<Int, Nation> = mapOf(1 to nation(1), 2 to nation(2)),
        diplomacy: List<Diplomacy> = emptyList(),
    ) = MemoryStateView(
        generals = generals, cities = cities, nations = nations, env = emptyMap(),
        diplomacy = diplomacy,
    )

    private fun ctx(
        actorId: Int = 1, cityId: Int? = 5, nationId: Int? = 1,
        destCityId: Int? = null, destNationId: Int? = null,
        args: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(
        actorId = actorId, cityId = cityId, nationId = nationId,
        destCityId = destCityId, destNationId = destNationId,
        args = args, env = emptyMap(), mode = ConstraintMode.FULL,
    )

    private fun deny(r: ConstraintResult): String {
        assertTrue(r is ConstraintResult.Deny, "expected Deny but was $r")
        return (r as ConstraintResult.Deny).reason
    }

    // --- existsDestNation ---
    @Test fun `existsDestNation allows live nation`() =
        assertEquals(
            ConstraintResult.Allow,
            existsDestNation().test(ctx(destNationId = 2), view()),
        )

    @Test fun `existsDestNation denies destroyed nation with PHP reason`() =
        assertEquals(
            // PHP setDestNation always stages a row; a destroyed nation's row has `nation` field 0.
            "멸망한 국가입니다.",
            deny(
                existsDestNation().test(
                    ctx(destNationId = 0),
                    view(nations = mapOf(0 to nation(0), 1 to nation(1), 2 to nation(2))),
                ),
            ),
        )

    // --- nearNation (isNeighbor-backed) ---
    @Test fun `nearNation allows when neighbor`() =
        assertEquals(
            ConstraintResult.Allow,
            nearNation { _, _ -> true }.test(ctx(destNationId = 2), view()),
        )

    @Test fun `nearNation denies non-neighbor with PHP reason`() =
        assertEquals(
            "인접 국가가 아닙니다.",
            deny(nearNation { _, _ -> false }.test(ctx(destNationId = 2), view())),
        )

    // --- hasRoute ---
    @Test fun `hasRoute allows when route exists`() =
        assertEquals(
            ConstraintResult.Allow,
            hasRoute { _, _ -> true }.test(ctx(destCityId = 6), view()),
        )

    @Test fun `hasRoute denies when no route with PHP reason`() =
        assertEquals(
            "경로에 도달할 방법이 없습니다.",
            deny(hasRoute { _, _ -> false }.test(ctx(destCityId = 6), view())),
        )

    // --- hasRouteWithEnemy (two-stage: enemy-city check THEN route check) ---
    @Test fun `hasRouteWithEnemy allows enemy dest with route`() =
        assertEquals(
            ConstraintResult.Allow,
            hasRouteWithEnemy(isAtWarDestCity = { _, _ -> true }, routeExists = { _, _ -> true })
                .test(ctx(destCityId = 6), view()),
        )

    @Test fun `hasRouteWithEnemy denies non-enemy dest with PHP reason`() =
        assertEquals(
            "교전중인 국가가 아닙니다.",
            deny(
                hasRouteWithEnemy(isAtWarDestCity = { _, _ -> false }, routeExists = { _, _ -> true })
                    .test(ctx(destCityId = 6), view()),
            ),
        )

    @Test fun `hasRouteWithEnemy denies no-route after enemy check with PHP reason`() =
        assertEquals(
            "경로에 도달할 방법이 없습니다.",
            deny(
                hasRouteWithEnemy(isAtWarDestCity = { _, _ -> true }, routeExists = { _, _ -> false })
                    .test(ctx(destCityId = 6), view()),
            ),
        )

    // --- disallowDiplomacyBetweenStatus (per-state errMsg from map) ---
    @Test fun `disallowDiplomacyBetweenStatus passes when no matching state`() =
        assertEquals(
            ConstraintResult.Allow,
            disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다."))
                .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 7)))),
        )

    @Test fun `disallowDiplomacyBetweenStatus passes when no diplomacy row`() =
        assertEquals(
            ConstraintResult.Allow,
            disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다."))
                .test(ctx(destNationId = 2), view(diplomacy = emptyList())),
        )

    @Test fun `disallowDiplomacyBetweenStatus denies matching state with map errMsg`() =
        assertEquals(
            "아국과 이미 교전중입니다.",
            deny(
                disallowDiplomacyBetweenStatus(linkedMapOf(0 to "아국과 이미 교전중입니다."))
                    .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 0)))),
            ),
        )

    // --- allowDiplomacyBetweenStatus (allow-list + caller errMsg) ---
    @Test fun `allowDiplomacyBetweenStatus passes when state in allow-list`() =
        assertEquals(
            ConstraintResult.Allow,
            allowDiplomacyBetweenStatus(listOf(0, 1), "허용되지 않는 상태입니다.")
                .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 1)))),
        )

    @Test fun `allowDiplomacyBetweenStatus denies state not in allow-list with errMsg`() =
        assertEquals(
            "허용되지 않는 상태입니다.",
            deny(
                allowDiplomacyBetweenStatus(listOf(0, 1), "허용되지 않는 상태입니다.")
                    .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 7)))),
            ),
        )

    @Test fun `allowDiplomacyBetweenStatus denies when no row with errMsg`() =
        assertEquals(
            "허용되지 않는 상태입니다.",
            deny(
                allowDiplomacyBetweenStatus(listOf(0, 1), "허용되지 않는 상태입니다.")
                    .test(ctx(destNationId = 2), view(diplomacy = emptyList())),
            ),
        )

    // --- allowDiplomacyWithTerm (state == code && term >= minTerm) ---
    @Test fun `allowDiplomacyWithTerm passes when state and term satisfied`() =
        assertEquals(
            ConstraintResult.Allow,
            allowDiplomacyWithTerm(2, 6, "조건이 맞지 않습니다.")
                .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 2, term = 6)))),
        )

    @Test fun `allowDiplomacyWithTerm denies when term too low with errMsg`() =
        assertEquals(
            "조건이 맞지 않습니다.",
            deny(
                allowDiplomacyWithTerm(2, 6, "조건이 맞지 않습니다.")
                    .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 2, term = 3)))),
            ),
        )

    @Test fun `allowDiplomacyWithTerm denies when state mismatched with errMsg`() =
        assertEquals(
            "조건이 맞지 않습니다.",
            deny(
                allowDiplomacyWithTerm(2, 6, "조건이 맞지 않습니다.")
                    .test(ctx(destNationId = 2), view(diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 7, term = 6)))),
            ),
        )
}
