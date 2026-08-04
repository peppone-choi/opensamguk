package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.GeneralBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AREA GATE-RUNTIME — Tier-0 B1 [GeneralBuilder] PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 시나리오/NPC mint 경로. 4 케이스(인재탐색/RegNPC/CreateManyNPC/특기형)를 실제 caller 형상대로
 * 재현해 [JoinDrawRecorder] 로 draw 스트림을 기록, PHP 골든(`golden/scenario/장수빌더-fixtures.json`)과
 * 메서드/결과/inner-DRBG 커서(stateIdx/bufferIdx)/choiceIndex 까지 일치하는지 검증한다.
 * 추가로 outcome(L/S/I·상성·성격·특기·도시·killturn·dex·생몰)을 단언한다.
 */
class GeneralBuilderGoldenTest {

    private val fixtureText = javaClass.classLoader
        .getResourceAsStream("golden/scenario/장수빌더-fixtures.json")!!
        .readBytes().toString(Charsets.UTF_8)
    private val root = Json.parseToJsonElement(fixtureText).jsonObject
    private val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
    private val env = root["env"]!!.jsonObject
    private val year = env["year"]!!.jsonPrimitive.int
    private val month = env["month"]!!.jsonPrimitive.int
    private val startYear = env["startyear"]!!.jsonPrimitive.int
    private val turnterm = env["turnterm"]!!.jsonPrimitive.int

    private val cases = root["fixtures"]!!.jsonArray.map { it.jsonObject }

    /** 도시 풀 — 골든 city-choice draw 의 items(런타임 city 행, id+nation) 그대로(도시 지정 케이스도 동일 풀 공유). */
    private val cityPool: List<GeneralBuilder.CityChoice> = run {
        val cityDraw = cases.asSequence()
            .flatMap { it["draws"]!!.jsonArray.asSequence().map { d -> d.jsonObject } }
            .first { it["method"]!!.jsonPrimitive.content == "choice" && it["args"]!!.jsonObject["items"] is JsonObject }
        cityDraw["args"]!!.jsonObject["items"]!!.jsonObject.values.map {
            val o = it.jsonObject
            GeneralBuilder.CityChoice(o["id"]!!.jsonPrimitive.int, o["nation"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `GeneralBuilder mint cases byte-match the PHP golden draw stream and outcome`() {
        for (c in cases) {
            val caseId = c["caseId"]!!.jsonPrimitive.content
            val seedString = c["seedString"]!!.jsonPrimitive.content
            val generalName = c["generalName"]!!.jsonPrimitive.content
            val nationID = c["nationID"]!!.jsonPrimitive.int
            val npcType = c["npcType"]!!.jsonPrimitive.int

            // seedString 바이트일치 — caller 별 simpleSerialize scope 재구성.
            val seed = expectedSeed(caseId)
            assertEquals(seedString, seed, "[$caseId] seedString byte-match")

            val rec = JoinDrawRecorder(LiteHashDrbg(seed))
            val builder = GeneralBuilder(rec, generalName, nationID).setNPCType(npcType)

            when (caseId) {
                "A" -> {
                    val age = 22
                    builder.setSpecial("None", "None")
                        .setMoney(1000, 1000)
                        .setLifeSpan(year - age, year + 30)
                        .setSpecYear(phpRound((80.0 - age) / 12) + age, phpRound((80.0 - age) / 6) + age)
                    builder.fillRemainSpecAsRandom(
                        linkedMapOf("무" to 6.0, "지" to 6.0, "무지" to 3.0),
                        avgGenDexTotal = 0.0, avgGenDex5 = 0, hasDexAvg = true,
                        year = year, startYear = startYear, isFiction = false,
                    )
                }
                "B" -> {
                    builder.setCityID(1)
                        .setStat(70, 65, 60)
                        .setOfficerLevel(0)
                        .setEgo(null)
                        .setSpecialSingle("")
                        .setNPCText("")
                        .setAffinity(77)
                        .setLifeSpan(year - 40, year + 40)
                        .setKillturn(0)
                    builder.fillRemainSpecAsZero(year, startYear)
                }
                "C" -> {
                    builder.setLifeSpan(year - 30, year + 50)
                    builder.fillRandomStat(linkedMapOf("무" to 0.333, "지" to 0.333, "무지" to 0.334))
                    builder.fillRemainSpecAsZero(year, startYear)
                }
                "D" -> {
                    builder.setStat(72, 70, 55)
                        .setLifeSpan(year - 28, year + 45)
                        .setSpecialOption("랜덤")
                    builder.fillRemainSpecAsRandom(
                        linkedMapOf("무" to 6.0, "지" to 6.0, "무지" to 3.0),
                        avgGenDexTotal = 0.0, avgGenDex5 = 0, hasDexAvg = true,
                        year = year, startYear = startYear, isFiction = false,
                    )
                }
                else -> error("unknown case $caseId")
            }

            val built = builder.build(year, month, turnterm, cityPool)
                ?: error("[$caseId] build returned null (reserved/dead — golden expects a row)")

            // draw-for-draw 비교
            val expected = c["draws"]!!.jsonArray.map { it.jsonObject }
            val actual = rec.drawStream()
            assertEquals(expected.size, actual.size, "[$caseId] draw_count")
            for (i in expected.indices) {
                val e = expected[i]; val a = actual[i]
                val m = e["method"]!!.jsonPrimitive.content
                assertEquals(m, a.method, "[$caseId] draw[$i] method")
                assertEquals(e["stateIdxBefore"]!!.jsonPrimitive.int.toLong(), a.stateIdxBefore, "[$caseId] draw[$i] stateIdxBefore")
                assertEquals(e["bufferIdxBefore"]!!.jsonPrimitive.int, a.bufferIdxBefore, "[$caseId] draw[$i] bufferIdxBefore")
                e["choiceIndex"]?.let {
                    assertEquals(it.jsonPrimitive.int, a.choiceIndex, "[$caseId] draw[$i] choiceIndex")
                }
                // 결과는 스칼라(문자열/숫자/불리언)만 비교 — choice(도시 map / dex array)는 choiceIndex+커서로 핀.
                val res = e["result"]
                if (res is JsonPrimitive) {
                    assertEquals(res.content, a.result, "[$caseId] draw[$i] result ($m)")
                }
            }

            // outcome 단언
            val o = c["outcome"]!!.jsonObject
            assertEquals(o["leadership"]!!.jsonPrimitive.int, built.leadership, "[$caseId] leadership")
            assertEquals(o["strength"]!!.jsonPrimitive.int, built.strength, "[$caseId] strength")
            assertEquals(o["intel"]!!.jsonPrimitive.int, built.intel, "[$caseId] intel")
            assertEquals(o["affinity"]!!.jsonPrimitive.int, built.affinity, "[$caseId] affinity")
            assertEquals(o["personal"]!!.jsonPrimitive.content, built.ego, "[$caseId] personal")
            assertEquals(o["special"]!!.jsonPrimitive.content, built.specialDomestic, "[$caseId] special(domestic)")
            assertEquals(o["special2"]!!.jsonPrimitive.content, built.specialWar, "[$caseId] special2(war)")
            assertEquals(o["specage"]!!.jsonPrimitive.int, built.specAge, "[$caseId] specage")
            assertEquals(o["specage2"]!!.jsonPrimitive.int, built.specAge2, "[$caseId] specage2")
            assertEquals(o["bornyear"]!!.jsonPrimitive.int, built.birth, "[$caseId] bornyear")
            assertEquals(o["deadyear"]!!.jsonPrimitive.int, built.death, "[$caseId] deadyear")
            assertEquals(o["age"]!!.jsonPrimitive.int, built.age, "[$caseId] age")
            assertEquals(o["officer_level"]!!.jsonPrimitive.int, built.officerLevel, "[$caseId] officer_level")
            assertEquals(o["killturn"]!!.jsonPrimitive.int, built.killturn, "[$caseId] killturn")
            assertEquals(o["city"]!!.jsonPrimitive.int, built.cityId, "[$caseId] city")
            assertEquals(o["experience"]!!.jsonPrimitive.int, built.experience, "[$caseId] experience")
            assertEquals(o["dedication"]!!.jsonPrimitive.int, built.dedication, "[$caseId] dedication")
            assertEquals(o["name"]!!.jsonPrimitive.content, built.name, "[$caseId] name(prefix)")
            if (caseId == "B") {
                assertEquals("", built.npcText, "[$caseId] explicit npcmsg")
            } else {
                assertNull(built.npcText, "[$caseId] unset npcmsg")
            }
            for (d in 1..5) {
                assertEquals(o["dex$d"]!!.jsonPrimitive.int, dexOf(built, d), "[$caseId] dex$d")
            }
        }
    }

    @Test
    fun `setAffinity mirrors PHP random preserve sentinel and reject branches`() {
        val recorder = JoinDrawRecorder(LiteHashDrbg(expectedSeed("A")))
        val randomBuilder = GeneralBuilder(recorder, "상성", 0).setAffinity(0)
        assertEquals(
            listOf(JoinDrawRecorder.Draw(0, "nextRangeInt", "138", 1, 0)),
            recorder.drawStream(),
            "setAffinity(0) must match the captured PHP draw exactly",
        )
        val randomAffinity = randomBuilder
            .setCityID(1)
            .setStat(70, 60, 50)
            .setEgo("che_유지")
            .setLifeSpan(year - 20, year + 40)
            .fillRemainSpecAsZero(year, startYear)
            .build(year, month, turnterm, cityPool)
            ?.affinity
        assertEquals(138, randomAffinity)

        assertEquals(1, buildWithAffinity(1).affinity)
        assertEquals(150, buildWithAffinity(150).affinity)
        assertEquals(999, buildWithAffinity(900).affinity)
        assertEquals(999, buildWithAffinity(999).affinity)

        assertFailsWith<IllegalArgumentException> { baseBuilder().setAffinity(151) }
        assertFailsWith<IllegalArgumentException> { baseBuilder().setAffinity(899) }
    }

    @Test
    fun `adult callback follows PHP build position before city turntime and killturn draws without new golden values`() {
        val recorder = JoinDrawRecorder(LiteHashDrbg(serializeSeed(hiddenSeed, "AdultCallbackOrder")))
        var callbackName: String? = null
        var callbackDrawMethods: List<String>? = null

        val built = GeneralBuilder(recorder, "성인", 1)
            .setStat(70, 60, 50)
            .setOfficerLevel(3)
            .setEgo("che_유지")
            .setSpecialSingle("")
            .setAffinity(42)
            .setLifeSpan(year - 14, year + 20)
            .fillRemainSpecAsZero(year, startYear)
            .build(
                year,
                month,
                turnterm,
                cityPool,
                isFictionMode = true,
                onAdultGeneral = { adultName ->
                    callbackName = adultName
                    callbackDrawMethods = recorder.drawStream().map { it.method }
                },
            )
            ?: error("adult callback order fixture should build a general")

        assertEquals("ⓝ성인", callbackName)
        assertEquals(
            emptyList(),
            callbackDrawMethods,
            "PHP GeneralBuilder.php:596-600 emits adult global action before picture/city/turntime/killturn work",
        )
        assertEquals(
            listOf("choice", "nextRangeInt", "nextRangeInt", "nextRangeInt"),
            recorder.drawStream().map { it.method },
            "post-callback draw shape is city choice, getRandTurn seconds, getRandTurn fraction, killturn",
        )
        assertEquals(0, built.nation, "env fiction truthy + newly adult general forces neutral nation")
        assertEquals(0, built.officerLevel, "newly adult fiction-neutral general receives neutral officer level")
    }

    private fun buildWithAffinity(affinity: Int): opensamguk.logic.world.BuiltGeneral =
        baseBuilder()
            .setAffinity(affinity)
            .fillRemainSpecAsZero(year, startYear)
            .build(year, month, turnterm, cityPool)
            ?: error("affinity branch fixture should build an adult active general")

    private fun baseBuilder(): GeneralBuilder =
        GeneralBuilder(JoinDrawRecorder(LiteHashDrbg(serializeSeed(hiddenSeed, "AffinityBranch"))), "상성", 0)
            .setCityID(1)
            .setStat(70, 60, 50)
            .setEgo("che_유지")
            .setLifeSpan(year - 20, year + 40)

    private fun dexOf(b: opensamguk.logic.world.BuiltGeneral, n: Int) = when (n) {
        1 -> b.dex1; 2 -> b.dex2; 3 -> b.dex3; 4 -> b.dex4; else -> b.dex5
    }

    /** caller 별 Util::simpleSerialize scope 재구성(capture_general_builder.php 와 동일). */
    private fun expectedSeed(caseId: String): String = when (caseId) {
        "A" -> serializeSeed(hiddenSeed, "GeneralBuilderScout", year, month, "A_랜덤장수")
        "B" -> serializeSeed(hiddenSeed, "RegNPC", "B고정장수", 0, 70, 65, 60)
        "C" -> serializeSeed(hiddenSeed, "CreateManyNPC", year, month)
        "D" -> serializeSeed(hiddenSeed, "GeneralBuilderRandSpec", year, month, "D특기장수")
        else -> error("unknown case $caseId")
    }
}
