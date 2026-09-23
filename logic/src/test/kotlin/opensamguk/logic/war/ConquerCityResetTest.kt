package opensamguk.logic.war

import opensamguk.common.constants.GameConst
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.world.CalcCityDistance
import opensamguk.common.constants.HanWorldV3CityConst
import opensamguk.common.constants.HanWorldV3GateIndex
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant
import opensamguk.logic.world.HAN_WORLD_V3_MAP_NAME
import opensamguk.logic.world.HanCityConstVariant
import opensamguk.logic.world.HanWorldVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BC3 — findNextCapital BFS-ring max-pop (LAST-on-tie) + winner tie-break + city reset + SetNationFront.
 *
 * Port target = PHP `process_war.php:757-845`:
 *  - findNextCapital (`:810-845`): `searchDistance(capital,99,true)` distForm rings; walk outward; in the
 *    FIRST ring containing an owned city pick MAX pop, on a pop TIE the LAST max-pop city in ring-iteration
 *    order wins (`if($cityPop < $maxCityPop) continue;` overwrites on equality → LAST wins; decision #7);
 *  - winner (`:757`): `conquerNation = getConquerNation(city) = array_key_first(conflict)`; ==attacker →
 *    attacker general moves to cityID; else 분쟁협상/양도 logs only;
 *  - city reset (`:777-795`): supply=1, term=0, conflict='{}', agri/comm/secu ×0.7, nation=conquerNation,
 *    officer_set=0; level>3 ⇒ def=wall=defaultCityWall else def=def_max/2, wall=wall_max/2;
 *  - front recalc (`:798-807`): nearNationsID = distinct nation of path-neighbors ∪ conquerNation → SetNationFront.
 */
class ConquerCityResetTest {

    private val hidden = "ef".repeat(16)

    private fun attacker(id: Int = 1, nationId: Int = 10): General = General(
        id = id, nationId = nationId, cityId = 100,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5,
        gold = 1000, rice = 10000,
    )

    private fun city(
        id: Int = 200, nationId: Int = 20, level: Int = 5,
        agri: Int = 1000, comm: Int = 1000, secu: Int = 1000,
        defMax: Int = 2000, wallMax: Int = 2000, conflict: String = """{"10":100.0}""",
    ): City = City(
        id = id, nationId = nationId, level = level,
        commerce = comm, commerceMax = 9999,
        agriculture = agri, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = secu, securityMax = 9999,
        defense = 800, defenseMax = defMax,
        wall = 800, wallMax = wallMax,
        population = 100000, populationMax = 999999,
        term = 5, officerSet = 3, conflict = conflict,
    )

    // --- findNextCapital BFS-ring max-pop, LAST-on-tie ----------------------------------------------------

    @Test
    fun `findNextCapital walks BFS rings and picks max pop, NOT Euclidean`() {
        // Capital = city 1. Own a far city with HIGH pop and a near city with LOW pop: the NEAR ring wins
        // (ring-order, NOT raw max pop / NOT Euclidean distance).
        val rings = CalcCityDistance.searchDistanceRings(1, 99)
        val ring1 = rings.values.first()                // the nearest non-origin ring
        val nearCity = ring1.first()
        // a city in a strictly farther ring with a much larger pop.
        val farRingCity = rings.entries.drop(1).first().value.first()
        val owned = linkedMapOf(nearCity to 10, farRingCity to 9999)
        assertEquals(nearCity, ConquerCity.findNextCapital(1, owned), "the nearest ring wins regardless of pop")
    }

    @Test
    fun `findNextCapital LAST-discovered max-pop city wins on a tie in the same ring`() {
        val rings = CalcCityDistance.searchDistanceRings(1, 99)
        val ring1 = rings.values.first()
        // pick TWO cities in the same ring, both owned, with the SAME pop → the LAST in ring order wins.
        assertTrue(ring1.size >= 2, "ring-1 of capital 1 must have >=2 cities for the tie test")
        val first = ring1[0]
        val last = ring1[ring1.size - 1]
        val owned = linkedMapOf(first to 500, last to 500)  // a tie
        assertEquals(last, ConquerCity.findNextCapital(1, owned), "the LAST max-pop city in ring order wins")
    }

    @Test
    fun `Han lost capital relocates to an adjacent Han-only city`() {
        val han = CityConstRegistry.of("han")
        assertTrue(421 in checkNotNull(han.byId(3)).path)
        assertEquals(421, ConquerCity.findNextCapital(3, linkedMapOf(421 to 50_000), han))
    }

    // --- 끊긴 지도: 링으로 못 닿는 城 만 남아도 멈추지 않는다 -------------------------------------------

    /** 그 城들만 그래프에서 떼어 낸 han-world-v3 표 — 「링으로 못 닿는다」를 지도 결함 없이 만든다. */
    private fun v3WithCitiesCutOff(vararg ids: Int): CityConstVariant {
        val cut = ids.toSet()
        val names = HanWorldV3CityConst.initCity.filter { it.id in cut }.map { it.name }.toSet()
        val rows = HanWorldV3CityConst.initCity.map { row ->
            if (row.id in cut) row.copy(path = emptyList())
            else row.copy(path = row.path.filterNot { it in names })
        }
        return HanCityConstVariant(
            HAN_WORLD_V3_MAP_NAME, rows, HanWorldV3GateIndex::keys,
            nationLevelCityThresholds = listOf(0, 1, 5, 12, 20, 27, 40, 52, 70, 90),
        )
    }

    @Test
    fun `han-world-v3 는 고립 城 이 없고 1 번에서 전수 도달한다`() {
        // 2026-09-16 이전에는 305 서(下邳國)·548 무(會稽郡) 두 곳의 path 가 비어 있었다. 둘 다
        // 래스터에서 물에 갇힌 縣이라 省 인접 투영이 간선을 하나도 못 냈고, 도겸이 {305, 547} 만
        // 가진 채 547 을 잃자 findNextCapital 이 305 에 영영 못 닿아 프로덕션이 14시간 멈췄다.
        // 생성기(build_han_world.py)가 그런 城을 제 郡 治所와 직결하도록 고쳤다.
        //
        // 「고립 0곳」만 세지 않는다 — 두 덩어리로 갈라져도 path 는 비어 있지 않다. 전수 도달을 본다.
        // 2026-09-17 1133 판은 섬 郡·郡國 밖 취락이 뱃길(build_han_world V3_SEA_ROUTES)로 이어져야 전수 도달한다.
        // 2026-09-23: 현재 판은 결손 縣 56곳을 더한 1224 이다. 동결된 옛 판들은 그대로 같이 본다.
        for ((variant, size) in listOf(CityConstRegistry.of(HAN_WORLD_V3_MAP_NAME) to 1224,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_848) to 848,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1098) to 1098,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1133) to 1133,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1141) to 1141,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1341) to 1341,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1168) to 1168,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1194) to 1194,
                                       CityConstRegistry.hanWorld(HanWorldVariant.V3_1224) to 1224)) {
            val all = variant.all()
            assertEquals(size, all.size)
            assertEquals(emptyList(), all.keys.sorted().filter { checkNotNull(variant.byId(it)).path.isEmpty() },
                "고립 城 이 있다: ${variant.mapName}")
            val reached = mutableSetOf(1)
            val queue = ArrayDeque(listOf(1))
            while (queue.isNotEmpty()) {
                for (next in checkNotNull(variant.byId(queue.removeFirst())).path.keys) {
                    if (reached.add(next)) queue.addLast(next)
                }
            }
            assertEquals(all.keys, reached, "1 번에서 전수 도달해야 한다: ${variant.mapName}")
        }
    }

    @Test
    fun `링으로 못 닿는 城 만 남으면 던지지 않고 그 城 을 고른다`() {
        // 2026-09-16 pep 정지 재현. 지도는 고쳤지만 폴백 자체는 계속 지킨다 — 시나리오·리셋·
        // 장래 표가 다시 끊어진 그래프를 낼 수 있고, 그때 월드를 멈추면 안 된다.
        val cut = v3WithCitiesCutOff(305)
        assertEquals(305, ConquerCity.findNextCapital(547, linkedMapOf(305 to 12_345), cut))
    }

    @Test
    fun `못 닿는 城 이 여럿이면 최대 인구, 동수면 번호가 큰 쪽`() {
        val cut = v3WithCitiesCutOff(305, 548)
        assertEquals(548, ConquerCity.findNextCapital(547, linkedMapOf(305 to 900, 548 to 5_000), cut))
        // 동수면 城 번호 오름차순에서 나중 것 — 링 안의 LAST-on-tie 규칙과 같다.
        assertEquals(548, ConquerCity.findNextCapital(547, linkedMapOf(305 to 5_000, 548 to 5_000), cut))
    }

    @Test
    fun `진짜로 남은 城 이 없을 때만 던진다`() {
        val v3 = CityConstRegistry.hanWorld(HanWorldVariant.V3_848)
        assertFailsWith<IllegalStateException> { ConquerCity.findNextCapital(547, emptyMap(), v3) }
    }

    @Test
    fun `길이 이어져 있으면 폴백이 아니라 링 순서가 이긴다`() {
        // 폴백은 번호가 큰 쪽으로 기우는데, 링이 살아 있으면 가까운 쪽이 이겨야 한다 —
        // 폴백이 정상 경로를 가로채지 않는다는 증거다(기존 골든 보호).
        val cut = v3WithCitiesCutOff(305)
        val near = checkNotNull(cut.byId(547)).path.keys.first()
        assertEquals(near, ConquerCity.findNextCapital(547, linkedMapOf(near to 1, 305 to 9_999_999), cut))
    }

    // --- winner tie-break: attacker-match moves the general; mismatch = 양도 log only ----------------------

    @Test
    fun `winner equals array_key_first of the conflict map`() {
        // conflict = {"10":100, "30":50} → first key 10. The attacker (nation 10) IS the conquerer.
        val input = resetInput(conflict = """{"10":100.0,"30":50.0}""")
        val res = ConquerCity.resolve(input)
        assertEquals(10, res.conquerNationId)
    }

    @Test
    fun `attacker-match moves the attacker general to the conquered city as a delta`() {
        val input = resetInput(conflict = """{"10":100.0}""")    // conquer nation == attacker nation 10
        val res = ConquerCity.resolve(input)
        val moved = res.generalDeltas.first { it.post.id == 1 }
        assertEquals(200, moved.post.cityId, "attacker general moves to the conquered city")
        assertTrue(res.conquerLogs.none { it.contains("양도") }, "no 양도 log when the attacker wins outright")
    }

    @Test
    fun `mismatch emits a 양도 log and does NOT move the attacker general`() {
        // conflict first key 30 != attacker nation 10 → 분쟁협상/양도, NO general move.
        val input = resetInput(conflict = """{"30":100.0,"10":50.0}""")
        val res = ConquerCity.resolve(input)
        assertEquals(30, res.conquerNationId)
        assertTrue(res.generalDeltas.none { it.post.id == 1 }, "the attacker general is NOT moved on a mismatch")
        assertTrue(res.conquerLogs.any { it.contains("양도") }, "a 양도 log is emitted on a 분쟁협상")
    }

    // --- city reset: agri/comm/secu ×0.7 + level>3 defaultCityWall vs def_max/2 ---------------------------

    @Test
    fun `city reset scales agri comm secu by 0_7 and resets supply term conflict officer_set`() {
        val input = resetInput(
            cityOverride = city(id = 200, nationId = 20, level = 5, agri = 1000, comm = 800, secu = 600),
            conflict = """{"10":100.0}""",
        )
        val res = ConquerCity.resolve(input)
        val post = res.cityDeltas.first { it.post.id == 200 }.post
        assertEquals((1000 * 0.7).toInt(), post.agriculture)
        assertEquals((800 * 0.7).toInt(), post.commerce)
        assertEquals((600 * 0.7).toInt(), post.security)
        assertEquals(1, post.supplyState)
        assertEquals(0, post.term)
        assertEquals("{}", post.conflict)
        assertEquals(0, post.officerSet)
        assertEquals(10, post.nationId, "the city's new owner is the conquerNation")
    }

    @Test
    fun `level greater than 3 resets def and wall to defaultCityWall`() {
        val input = resetInput(
            cityOverride = city(id = 200, nationId = 20, level = 5, defMax = 4000, wallMax = 4000),
            conflict = """{"10":100.0}""",
        )
        val post = ConquerCity.resolve(input).cityDeltas.first { it.post.id == 200 }.post
        assertEquals(GameConst.defaultCityWall, post.defense)
        assertEquals(GameConst.defaultCityWall, post.wall)
    }

    @Test
    fun `level 3 or below resets def to def_max over 2 and wall to wall_max over 2`() {
        val input = resetInput(
            cityOverride = city(id = 200, nationId = 20, level = 3, defMax = 4000, wallMax = 3000),
            conflict = """{"10":100.0}""",
        )
        val post = ConquerCity.resolve(input).cityDeltas.first { it.post.id == 200 }.post
        assertEquals(4000 / 2, post.defense)
        assertEquals(3000 / 2, post.wall)
    }

    // --- front recalc over path-neighbors ∪ conquerNation -------------------------------------------------

    @Test
    fun `front recalc runs SetNationFront over path-neighbor nations plus the conquerNation`() {
        val input = resetInput(conflict = """{"10":100.0}""")
        val res = ConquerCity.resolve(input)
        // The conquerNation (10) is always in the recompute set (process_war.php:803).
        assertTrue(res.frontResults.any { it.nationId == 10 }, "the conquerNation gets a front recompute")
    }

    private fun resetInput(
        conflict: String = """{"10":100.0}""",
        cityOverride: City? = null,
    ): ConquerCityInput {
        val theCity = cityOverride ?: city(id = 200, nationId = 20, conflict = conflict)
        // SURVIVE branch (cityCount>1) so the flow reaches the winner/city-reset/front blocks.
        return ConquerCityInput(
            admin = ConquerAdmin(hiddenSeed = hidden, year = 200, month = 6, joinMode = "normal"),
            attacker = attacker(id = 1, nationId = 10),
            defenderCity = theCity.copy(conflict = conflict),
            defenderNation = Nation(id = 20, level = 5, capitalCityId = 999), // not the lost city → no capital-move
            attackerNation = Nation(id = 10, level = 5, capitalCityId = 100),
            defenderCityGenerals = emptyList(),
            defenderNationCityCount = 3,
            defenderNationGenerals = emptyList(),
            allCitiesForBfs = listOf(
                City(
                    id = 200, nationId = 10, level = 5,
                    commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
                    supplyState = 1, frontState = 0, trust = 0.0,
                ),
            ),
            diplomacyForFront = emptyList<Diplomacy>(),
            cityConstVariant = CityConstRegistry.of("che"),
        )
    }
}
