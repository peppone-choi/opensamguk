package opensamguk.logic.war

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.util.phpToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BC2 — the COLLAPSE branch (cityCount==1, the per-general draw sub-stream) + the SURVIVE / capital-move
 * branch. Port target = PHP `process_war.php:607-755`, `func.php:1713-1805` (deleteNation).
 *
 * ## COLLAPSE per-general draw sub-stream (process_war.php:628-664) — the parity-critical order
 * For each oldNationGeneral, IN ORDER (other generals ascending PK + lord appended LAST):
 *  1. `loseGold = Util::toInt(gold * rng.nextRange(0.2,0.5))`   — draw
 *  2. `loseRice = Util::toInt(rice * rng.nextRange(0.2,0.5))`   — draw
 *  3. `addExperience(-experience*0.1, false)` (suppress flag — NO onCalcStat, value = exp*0.9, NOT inline)
 *  4. `addDedication(-dedication*0.5, false)`
 *  5. IF join_mode != 'onlyRandom': `nextBool(0.5)` scout  [CONDITIONAL — short-circuit AND]
 *  6. IF join_mode != 'onlyRandom' && 2<=npcType<=8 && npcType!=5: `nextBool(joinRuinedNPCProp)`; IF true
 *     `nextRangeInt(0,12)` joinTurn  [CONDITIONAL]
 *
 * Winner reward (process_war.php:667-680): loseNationGold = valueFit(gold-basegold,0)+Σ loseGold; /2 intdiv;
 * nation gold/rice += via delta. markNationDeleted cascade (generals → 재야; NO markGeneralDeleted).
 *
 * ## SURVIVE / capital-move (process_war.php:703-755) — NO rng. Demote officer_city governors to 재야
 * (officer_level=1, officer_city=0); on a capital loss, capital-move to findNextCapital (BC3) + gold/rice ×0.5.
 */
class ConquerCityCollapseTest {

    private val hidden = "cd".repeat(16)

    private fun attacker(id: Int = 1, nationId: Int = 10): General = General(
        id = id, nationId = nationId, cityId = 100,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5,
        gold = 1000, rice = 10000,
    )

    private fun city(id: Int = 200, nationId: Int = 20, level: Int = 5): City = City(
        id = id, nationId = nationId, level = level,
        commerce = 1000, commerceMax = 9999,
        agriculture = 1000, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = 1000, securityMax = 9999,
        defense = 800, defenseMax = 2000,
        wall = 800, wallMax = 2000,
        population = 100000, populationMax = 999999,
        conflict = "{}",
    )

    private fun gen(
        id: Int, nationId: Int = 20, gold: Int = 1000, rice: Int = 2000,
        experience: Double = 5000.0, dedication: Double = 4000.0,
        officerLevel: Int = 1, npcType: Int = 0, officerCity: Int = 0,
    ): General = General(
        id = id, nationId = nationId, cityId = 200,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = experience, dedication = dedication, officerLevel = officerLevel,
        gold = gold, rice = rice, npcType = npcType, officerCity = officerCity,
    )

    private fun collapseInput(
        lordId: Int,
        otherGenerals: List<General>,
        joinMode: String = "normal",
        defenderNation: Nation = Nation(id = 20, level = 5, capitalCityId = 200, gold = 5000, rice = 6000),
        defenderConflict: String = """{"10":100.0}""",
    ): ConquerCityInput {
        val lord = gen(lordId, officerLevel = 12)
        return ConquerCityInput(
            admin = ConquerAdmin(hiddenSeed = hidden, year = 200, month = 6, joinMode = joinMode),
            attacker = attacker(),
            defenderCity = city(nationId = 20).copy(conflict = defenderConflict),
            defenderNation = defenderNation,
            defenderCityGenerals = emptyList(),
            defenderNationCityCount = 1, // ⇒ COLLAPSE
            defenderNationGenerals = otherGenerals + lord, // resolver re-orders: others asc PK + lord LAST
            allCitiesForBfs = emptyList(),
        )
    }

    /** A scripted rng: nextRange/nextBool/nextRangeInt return queued values so the draw order is exact. */
    private class ScriptedRng(
        private val ranges: ArrayDeque<Double>,
        private val bools: ArrayDeque<Boolean>,
        private val rangeInts: ArrayDeque<Int>,
        val trace: MutableList<String> = mutableListOf(),
    ) : RandUtil(opensamguk.common.rng.LiteHashDrbg("00")) {
        override fun nextRange(min: Double, max: Double): Double {
            trace.add("nextRange($min,$max)"); return ranges.removeFirst()
        }
        override fun nextBool(prob: Double): Boolean {
            trace.add("nextBool($prob)"); return bools.removeFirst()
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            trace.add("nextRangeInt($minInclusive,$maxInclusive)"); return rangeInts.removeFirst()
        }
    }

    @Test
    fun `collapse per-general draw order is loseGold then loseRice then conditional scout then npc-join`() {
        // One NPC general (npcType=3) so BOTH the scout AND the npc-join branches fire.
        val npc = gen(id = 5, gold = 1000, rice = 2000, npcType = 3)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(npc))
        // Per general: nextRange (gold), nextRange (rice), nextBool (scout), nextBool (npc), nextRangeInt (joinTurn).
        val rng = ScriptedRng(
            ranges = ArrayDeque(listOf(0.3, 0.4, /*lord*/ 0.3, 0.4)),
            bools = ArrayDeque(listOf(true, true, /*lord (npcType 1 → no npc branch)*/ true)),
            rangeInts = ArrayDeque(listOf(7)),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)

        // npc (id 5) FIRST: gold, rice, scout(true), npc(true), joinTurn. Lord (id 9) LAST: gold, rice, scout(true).
        assertEquals(
            listOf(
                "nextRange(0.2,0.5)", "nextRange(0.2,0.5)", "nextBool(0.5)",
                "nextBool(${GameConst.joinRuinedNPCProp})", "nextRangeInt(0,12)",
                "nextRange(0.2,0.5)", "nextRange(0.2,0.5)", "nextBool(0.5)",
            ),
            rng.trace,
        )
        assertEquals(
            listOf(ScoutInviteIntent(1, 5), ScoutInviteIntent(1, 9)),
            res.scoutInvites,
        )
        assertEquals((0..6).toList(), res.turnSlotWrites.dropLast(1).map { it.turnIdx })
        assertEquals(
            GeneralTurnSlotIntent(5, 7, "che_임관", """{"destNationID":10}""", "임관"),
            res.turnSlotWrites.last(),
        )
        assertTrue(res.destroyNationEvent)
        assertEquals(
            listOf(
                ConquerLogScope.GENERAL,
                ConquerLogScope.GENERAL,
                ConquerLogScope.GLOBAL,
                ConquerLogScope.GLOBAL,
                ConquerLogScope.NATION,
                ConquerLogScope.NATION,
            ),
            res.conquerLogs.take(6).map { it.scope },
        )
    }

    @Test
    fun `an onlyRandom general skips both the scout and the npc-join draws`() {
        val npc = gen(id = 5, npcType = 3)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(npc), joinMode = "onlyRandom")
        val rng = ScriptedRng(
            ranges = ArrayDeque(listOf(0.3, 0.4, 0.3, 0.4)),
            bools = ArrayDeque(emptyList()),
            rangeInts = ArrayDeque(emptyList()),
        )
        ConquerCity.resolve(input, rngOverride = rng)
        // onlyRandom: the `join_mode != 'onlyRandom'` short-circuit makes nextBool NEVER evaluate.
        assertEquals(
            listOf("nextRange(0.2,0.5)", "nextRange(0.2,0.5)", "nextRange(0.2,0.5)", "nextRange(0.2,0.5)"),
            rng.trace,
        )
    }

    @Test
    fun `oldNationGenerals process other generals ascending PK with the lord LAST`() {
        val g8 = gen(id = 8, npcType = 0)
        val g3 = gen(id = 3, npcType = 0)
        // input order deliberately shuffled; resolver must emit 3, 8, then lord 9 LAST.
        val input = collapseInput(lordId = 9, otherGenerals = listOf(g8, g3))
        val rng = ScriptedRng(
            ranges = ArrayDeque(List(6) { 0.3 }),
            bools = ArrayDeque(listOf(false, false, false)),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)
        assertEquals(listOf(3, 8, 9), res.collapseGeneralOrder, "others asc PK + lord appended LAST")
    }

    @Test
    fun `collapse applies experience times 0_9 and dedication times 0_5 with the suppress flag - no inline 0_9`() {
        val g = gen(id = 5, experience = 5000.0, dedication = 4000.0, npcType = 0)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(g))
        val rng = ScriptedRng(
            ranges = ArrayDeque(listOf(0.3, 0.4, 0.3, 0.4)),
            bools = ArrayDeque(listOf(false, false)),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)
        val post = res.generalDeltas.first { it.post.id == 5 }.post
        // addExperience(-exp*0.1, false): newExp = 5000 - 5000*0.1 = 4500.0; addDedication(-ded*0.5): 4000 - 2000.
        assertEquals(4500.0, post.experience)
        assertEquals(2000.0, post.dedication)
    }

    @Test
    fun `collapse gold-rice loss is Util toInt truncated and applied as a delta`() {
        val g = gen(id = 5, gold = 1000, rice = 2000, npcType = 0)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(g))
        val rng = ScriptedRng(
            ranges = ArrayDeque(listOf(0.37, 0.41, 0.3, 0.4)),
            bools = ArrayDeque(listOf(false, false)),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)
        val post = res.generalDeltas.first { it.post.id == 5 }.post
        val loseGold = phpToInt(1000 * 0.37)   // 370
        val loseRice = phpToInt(2000 * 0.41)   // 820
        assertEquals(1000 - loseGold, post.gold)
        assertEquals(2000 - loseRice, post.rice)
    }

    @Test
    fun `winner reward halves the captured gold-rice via intdiv and adds to attacker nation`() {
        val g = gen(id = 5, gold = 1000, rice = 2000, npcType = 0)
        val attackerNation = Nation(id = 10, level = 5, capitalCityId = 100, gold = 9000, rice = 8000)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(g))
            .copy(attackerNation = attackerNation)
        // loseGold = toInt(1000*0.3)=300; lord loseGold = toInt(1000*0.3)=300 → Σ loseGold = 600.
        val rng = ScriptedRng(
            ranges = ArrayDeque(listOf(0.3, 0.3, 0.3, 0.3)),
            bools = ArrayDeque(listOf(false, false)),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)
        // defenderNation gold=5000 rice=6000; basegold=0 baserice=2000.
        // loseNationGold = valueFit(5000-0,0) + Σ loseGold(600) = 5600; /2 = 2800.
        // loseNationRice = valueFit(6000-2000,0) + Σ loseRice(2*600=1200) = 5200; /2 = 2600.
        val sumGold = phpToInt(1000 * 0.3) * 2
        val sumRice = phpToInt(2000 * 0.3) * 2
        val expectGold = (valueFitInt(5000 - GameConst.basegold) + sumGold) / 2
        val expectRice = (valueFitInt(6000 - GameConst.baserice) + sumRice) / 2
        val nationDelta = res.nationDeltas.first { it.post.id == 10 }
        assertEquals(9000 + expectGold, nationDelta.post.gold)
        assertEquals(8000 + expectRice, nationDelta.post.rice)
    }

    @Test
    fun `collapse marks the defender nation deleted and survives generals as 재야 - no markGeneralDeleted`() {
        val g = gen(id = 5, npcType = 0)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(g))
        val rng = ScriptedRng(
            ranges = ArrayDeque(List(4) { 0.3 }),
            bools = ArrayDeque(listOf(false, false)),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)
        assertEquals(20, res.deletedNationId, "the defender nation is tombstoned (markNationDeleted)")
        assertTrue(res.deletedGeneralIds.isEmpty(), "generals SURVIVE as 재야 — markGeneralDeleted is NOT used")
    }

    /**
     * deleteNation 멸망 로그 3종 (func.php:1729, :1772-1773). 순서도 패리티 대상:
     * 정복 nation-history(process_war.php:621) → global 【멸망】(func.php:1729) → deleteNation 장수 루프
     * 전체(장수별 action + history, 타 장수 asc PK + 군주 LAST) → 그 다음에야 process_war 의 도주 루프.
     */
    @Test
    fun `collapse emits the deleteNation destroy logs before the 도주 loop in PHP order`() {
        val g3 = gen(id = 3, npcType = 0)
        val g8 = gen(id = 8, npcType = 0)
        val input = collapseInput(lordId = 9, otherGenerals = listOf(g8, g3))
            .copy(defenderNationName = "촉")
        val rng = ScriptedRng(
            ranges = ArrayDeque(List(6) { 0.3 }),
            bools = ArrayDeque(listOf(false, false, false)),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)

        val destroyLog = "<D><b>촉</b></>이 <R>멸망</>했습니다."
        val destroyHistory = "<D><b>촉</b></>이 <R>멸망</>"
        // conquerLogs[0..5] = the 공략/지배 logs; the collapse cascade starts at index 6.
        val expected = listOf(
            ConquerLog.nationHistory(10, "<D><b>촉</b></>을 정복"),
            ConquerLog.globalHistory("<R><b>【멸망】</b></><D><b>촉</b></>은 <R>멸망</>했습니다."),
            ConquerLog.generalAction(3, 0, destroyLog),
            ConquerLog.generalHistory(3, 0, destroyHistory),
            ConquerLog.generalAction(8, 0, destroyLog),
            ConquerLog.generalHistory(8, 0, destroyHistory),
            ConquerLog.generalAction(9, 0, destroyLog),
            ConquerLog.generalHistory(9, 0, destroyHistory),
            ConquerLog.generalAction(3, 0, "도주하며 금<C>300</> 쌀<C>600</>을 분실했습니다."),
            ConquerLog.generalAction(8, 0, "도주하며 금<C>300</> 쌀<C>600</>을 분실했습니다."),
            ConquerLog.generalAction(9, 0, "도주하며 금<C>300</> 쌀<C>600</>을 분실했습니다."),
        )
        assertEquals(expected, res.conquerLogs.drop(6).take(expected.size))
    }

    // --- SURVIVE / capital-move branch (cityCount > 1) --------------------------------------------------

    @Test
    fun `survive demotes officer_city governors to 재야 as deltas with no rng`() {
        val gov = gen(id = 5, officerLevel = 4, officerCity = 200) // governs the lost city
        val other = gen(id = 6, officerLevel = 2, officerCity = 0)
        val input = ConquerCityInput(
            admin = ConquerAdmin(hiddenSeed = hidden, year = 200, month = 6, joinMode = "normal"),
            attacker = attacker(),
            defenderCity = city(nationId = 20),
            defenderNation = Nation(id = 20, level = 5, capitalCityId = 999), // NOT the lost city → no capital-move
            defenderCityGenerals = emptyList(),
            defenderNationCityCount = 3, // ⇒ SURVIVE
            defenderNationGenerals = listOf(gov, other),
            allCitiesForBfs = emptyList(),
        )
        val res = ConquerCity.resolve(input)
        // gov (officer_city == lost city) demoted: officer_level=1, officer_city=0.
        val govPost = res.generalDeltas.first { it.post.id == 5 }.post
        assertEquals(1, govPost.officerLevel)
        assertEquals(0, govPost.officerCity)
        // a general whose officer_city != lost city is untouched (no delta).
        assertFalse(res.generalDeltas.any { it.post.id == 6 })
        assertNull(res.deletedNationId, "survive does NOT delete the nation")
        assertEquals(0, res.collapseLoopDraws, "the survive branch makes NO rng draw")
    }

    // --- PHP collapse GOLDEN (real capture) ------------------------------------------------------------

    private fun loadGolden(name: String): JsonObject = Json.parseToJsonElement(
        ConquerCityCollapseTest::class.java.classLoader
            .getResourceAsStream("golden/p4/$name")!!
            .readBytes().toString(Charsets.UTF_8),
    ).jsonObject

    /** `general_record.text` carries the ActionLogger flush prefix (`<C>●</>` + `Y년 M월:` for history). */
    private fun stripLogPrefix(text: String): String =
        text.removePrefix("<C>●</>").replace(Regex("^\\d+년 \\d+월:"), "")

    /**
     * 정복-멸망 골든 게이트 — `conquercity-collapse-full-01.json` (실제 PHP `processWar()→ConquerCity` 캡처,
     * `tools/php-golden/capture_conquercity.php`, scenario_1010, 황건적(nation 2) 마지막 도시 업(1) 함락).
     *
     * 골든에서 직접 뽑은 멸망 로그 3종(func.php:1729 global 【멸망】, :1772 장수 action, :1773 장수 history)의
     * **문자열과 순서**를 [ConquerCity.resolve] 산출과 대조한다. OPENSAM-186 이전에는 이 3종이 통째로 누락돼
     * 있었고 collapse 골든이 없어 게이트가 통과시켰다.
     *
     * 금/쌀 분실 수치와 draw 는 PHP 의 LOCAL rng(process_war.php:589)에서 나와 캡처 불가(backlog CC-1)이므로
     * 여기서 단언하지 않는다 — 로그 문자열·발행 순서·장수 순서만 골든 대조한다.
     */
    @Test
    fun `collapse destroy logs byte-match the PHP collapse golden - strings and general order`() {
        val golden = loadGolden("conquercity-collapse-full-01.json")
        val records = golden["conquest_records"]!!.jsonArray.map { it.jsonObject }
        val nationName = golden["db_delta"]!!.jsonObject["nation"]!!.jsonObject["deleted"]!!
            .jsonObject["2"]!!.jsonObject["name"]!!.jsonPrimitive.content

        fun type(r: JsonObject) = r["log_type"]!!.jsonPrimitive.content
        fun gid(r: JsonObject) = r["general_id"]!!.jsonPrimitive.int
        fun body(r: JsonObject) = stripLogPrefix(r["text"]!!.jsonPrimitive.content)

        // 골든이 실제로 담은 멸망 로그 3종 (하드코딩 아님 — 캡처에서 추출).
        val goldenDestroyAction = records
            .filter { type(it) == "action" && body(it).endsWith("<R>멸망</>했습니다.") }
        val goldenDestroyHistory = records
            .filter { type(it) == "history" && body(it).endsWith("<R>멸망</>") }
        val goldenGlobal = golden["db_delta"]!!.jsonObject["world_history"]!!.jsonObject["created"]!!
            .jsonObject.values.map { stripLogPrefix(it.jsonObject["text"]!!.jsonPrimitive.content) }
            .single { it.contains("【멸망】") }

        val destroyLog = goldenDestroyAction.map { body(it) }.distinct().single()
        val destroyHistoryLog = goldenDestroyHistory.map { body(it) }.distinct().single()
        // 장수 순서: 타 장수 asc PK + 군주 LAST (func.php:1732-1735).
        val goldenOrder = goldenDestroyAction.map { gid(it) }
        assertEquals(goldenOrder.size, goldenDestroyHistory.map { gid(it) }.size)
        assertTrue(goldenOrder.size > 1, "collapse golden must carry the whole nation's generals")

        // 골든 자체의 장수별 발행 순서: 멸망 action 이 도주 action 보다 앞선다 (deleteNation → 도주 루프).
        for (id in goldenOrder) {
            val own = records.filter { gid(it) == id && type(it) == "action" }.map { body(it) }
            val destroyIdx = own.indexOf(destroyLog)
            val escapeIdx = own.indexOfFirst { it.startsWith("도주하며 금") }
            assertTrue(destroyIdx >= 0 && escapeIdx > destroyIdx, "general $id: 멸망 로그가 도주 로그보다 앞서야 한다")
        }

        // Kotlin 재현 — 골든과 동일한 국가명/장수 집합(군주 = 골든의 마지막)으로 collapse 를 돌린다.
        val lordId = goldenOrder.last()
        val input = collapseInput(
            lordId = lordId,
            otherGenerals = goldenOrder.dropLast(1).map { gen(id = it, npcType = 0) },
            joinMode = "onlyRandom", // 금/쌀 draw 만 남긴다 (scout/npc-join 은 CC-1 미캡처)
        ).copy(defenderNationName = nationName)
        val rng = ScriptedRng(
            ranges = ArrayDeque(List(goldenOrder.size * 2) { 0.3 }),
            bools = ArrayDeque(emptyList()),
            rangeInts = ArrayDeque(emptyList()),
        )
        val res = ConquerCity.resolve(input, rngOverride = rng)

        assertEquals(goldenOrder, res.collapseGeneralOrder, "장수 순서가 골든과 다르다")
        assertEquals(
            listOf(ConquerLog.globalHistory(goldenGlobal)),
            res.conquerLogs.filter { it.text.contains("【멸망】") },
            "global history 【멸망】 이 골든과 바이트 일치해야 한다",
        )
        assertEquals(
            goldenOrder.flatMap {
                listOf(ConquerLog.generalAction(it, 0, destroyLog), ConquerLog.generalHistory(it, 0, destroyHistoryLog))
            },
            res.conquerLogs.filter { it.text == destroyLog || it.text == destroyHistoryLog },
            "장수별 멸망 action/history 로그가 골든 문자열·순서와 일치해야 한다",
        )
    }

    /**
     * `join_mode == 'onlyRandom'` 단락(process_war.php:645, :656)의 실증 — 같은 멸망을 두 join_mode 로 캡처한
     * 골든 쌍이 등용장(message) 발부 유무로 갈린다.
     */
    @Test
    fun `the onlyRandom collapse golden issues no scout message while the full golden does`() {
        fun createdMessages(name: String) =
            loadGolden(name)["db_delta"]!!.jsonObject["message"]!!.jsonObject["created"]!!

        assertTrue(createdMessages("conquercity-collapse-full-01.json").jsonObject.isNotEmpty())
        assertTrue(createdMessages("conquercity-collapse-only-random-01.json").jsonArray.isEmpty())
    }

    private fun valueFitInt(v: Int): Int = maxOf(0, v)
}
