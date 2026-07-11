package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.world.MakeGeneral
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 장수생성(MakeGeneral) PHP-골든 게이트 — draw-for-draw byte parity.
 *
 * 오라클: `golden/entrance/장수생성-fixtures.json` (실 PHP `Join.php:225-392` 인라인 create-general 을
 * `tools/php-golden/capture_join.php` 가 RandUtilDrawRecorder 로 캡처, 2회 byte-identical 검증).
 * 14 픽스처 / 180 드로우 (천재 3 케이스 자연발생 포함, N∈{3,4,5}).
 *
 * 단언:
 *  1. 시드 재구성 byte-equal — serializeSeed(hiddenSeed,"MakeGeneral",userID,now) == 골든 seedString.
 *  2. 드로우-포-드로우 — [JoinDrawRecorder] 가 기록한 method/result/choiceIndex 및 inner DRBG 커서
 *     (stateIdxBefore/bufferIdxBefore)가 골든 draws[] 와 1:1 일치(+ 드로우 개수).
 *  3. 산출물 — genius/city/N/bonus/L·S·I/age/special2/personal/affinity/turntime 이 골든 outcome 과 일치.
 */
class MakeGeneralGoldenTest {

    @Test
    fun `MakeGeneral draw-for-draw byte parity (entrance 장수생성)`() {
        val text = javaClass.classLoader
            .getResourceAsStream("golden/entrance/장수생성-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject

        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val turnterm = root["env"]!!.jsonObject["turnterm"]!!.jsonPrimitive.int
        val fixtures = root["fixtures"]!!.jsonArray

        assertEquals(14, fixtures.size, "fixture count")

        for (fxEl in fixtures) {
            val fx = fxEl.jsonObject
            val userID = fx["userID"]!!.jsonPrimitive.int
            val now = fx["now"]!!.jsonPrimitive.content
            val tag = "[uid=$userID now=$now]"

            // ── 1. 시드 재구성 byte-equal ──────────────────────────────────────────────
            val seed = serializeSeed(hiddenSeed, "MakeGeneral", userID, now)
            assertEquals(fx["seedString"]!!.jsonPrimitive.content, seed, "$tag seedString 재구성")

            val form = fx["formStats"]!!.jsonObject
            val cityPool = fx["cityPool"]!!.jsonArray.map { it.jsonPrimitive.int }
            val personalities = fx["availablePersonality"]!!.jsonArray.map { it.jsonPrimitive.content }
            val character = fx["character"]!!.jsonPrimitive.content

            val rec = JoinDrawRecorder(LiteHashDrbg(seed))
            val result = MakeGeneral.draw(
                rng = rec,
                formLeadership = form["leadership"]!!.jsonPrimitive.int,
                formStrength = form["strength"]!!.jsonPrimitive.int,
                formIntel = form["intel"]!!.jsonPrimitive.int,
                formPolitics = 61,
                formCharm = 62,
                cityPool = cityPool,
                availablePersonality = personalities,
                character = character,
                turnterm = turnterm,
            )

            // ── 2. 드로우-포-드로우 + 커서 byte-parity ─────────────────────────────────
            val expDraws = fx["draws"]!!.jsonArray
            val actDraws = rec.drawStream()
            assertEquals(expDraws.size, actDraws.size, "$tag 드로우 개수")
            for (i in expDraws.indices) {
                val e = expDraws[i].jsonObject
                val a = actDraws[i]
                val dtag = "$tag draw#$i"
                assertEquals(e["method"]!!.jsonPrimitive.content, a.method, "$dtag method")
                assertEquals(e["result"]!!.jsonPrimitive.content, a.result, "$dtag result")
                assertEquals(e["stateIdxBefore"]!!.jsonPrimitive.long, a.stateIdxBefore, "$dtag stateIdxBefore")
                assertEquals(e["bufferIdxBefore"]!!.jsonPrimitive.int, a.bufferIdxBefore, "$dtag bufferIdxBefore")
                e["choiceIndex"]?.let {
                    assertEquals(it.jsonPrimitive.int, a.choiceIndex, "$dtag choiceIndex")
                }
            }

            // ── 3. 산출물 일치 ────────────────────────────────────────────────────────
            val o = fx["outcome"]!!.jsonObject
            assertEquals(o["genius"]!!.jsonPrimitive.content.toBoolean(), result.genius, "$tag genius")
            assertEquals(o["pickedCity"]!!.jsonPrimitive.int, result.cityId, "$tag pickedCity")
            assertEquals(o["N"]!!.jsonPrimitive.int, result.bonusCount, "$tag N")
            val bonus = o["bonus"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(bonus, listOf(result.bonusLeadership, result.bonusStrength, result.bonusIntel), "$tag bonus")
            assertEquals(o["leadership"]!!.jsonPrimitive.int, result.leadership, "$tag leadership")
            assertEquals(o["strength"]!!.jsonPrimitive.int, result.strength, "$tag strength")
            assertEquals(o["intel"]!!.jsonPrimitive.int, result.intel, "$tag intel")
            assertEquals(61, result.politics, "$tag politics")
            assertEquals(62, result.charm, "$tag charm")
            assertEquals(o["age"]!!.jsonPrimitive.int, result.age, "$tag age")
            assertEquals(o["personal"]!!.jsonPrimitive.content, result.personal, "$tag personal")
            assertEquals(o["affinity"]!!.jsonPrimitive.int, result.affinity, "$tag affinity")
            assertEquals(o["turntimeSecond"]!!.jsonPrimitive.int, result.turntimeSecond, "$tag turntimeSecond")
            assertEquals(o["turntimeFraction"]!!.jsonPrimitive.int, result.turntimeFraction, "$tag turntimeFraction")
            // 천재면 special2 캡처, 아니면 "None"
            val expSpecial2 = o["special2"]?.jsonPrimitive?.content ?: "None"
            assertEquals(expSpecial2, result.special2, "$tag special2")
        }
    }
}
