package opensamguk.logic.golden

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_접경귀환(CheJeopgyeongGwihwan) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 접근하는 적/중립 도시 중 RNG.choice 로 한 곳을 골라 귀환한다. 성공/실패 분기 없음.
 * candidateCityIds 는 엔진 어댑터가 사전 적재(가장 가까운 적/중립 도시 목록) — 여기선 골든이 기록한
 * choice items [1,2,19,80] 를 그대로 주입한다(캡처 결과 80=관도 선택).
 *
 * Draw stream:
 *   DRAW0: choice([1,2,19,80]) → 80 (관도)
 */
class CheJeopgyeongGwihwanGoldenTest {

    private val registry = CommandRegistry(GeneralActionPipeline())

    @Test
    fun `che_접경귀환 golden byte-matches the PHP action log, 1-draw choice, and dest city`() {
        val command = "che_접경귀환"
        val root = SabotageGoldenSupport.fixtureRoot("military", command)
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val c = root["cases"]!!.jsonArray.first().jsonObject

        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        val generalId = c["generalId"]!!.jsonPrimitive.int
        val cityId = c["cityId"]!!.jsonPrimitive.int
        val env = c["env"]!!.jsonObject
        val year = env["year"]!!.jsonPrimitive.int
        val startYear = env["startYear"]!!.jsonPrimitive.int
        val month = env["month"]!!.jsonPrimitive.int
        val develCost = env["develCost"]!!.jsonPrimitive.int

        val bg = c["before"]!!.jsonObject["general"]!!.jsonObject
        // 접경귀환은 통무지를 읽지 않는다(골든 before 에도 미덤프) → 0 으로 구성.
        val actor = General(
            id = generalId,
            nationId = bg["nation"]!!.jsonPrimitive.int,
            cityId = cityId,
            leadership = 0, strength = 0, intel = 0, injury = 0,
            experience = bg["experience"]!!.jsonPrimitive.int.toDouble(),
            dedication = bg["dedication"]!!.jsonPrimitive.int.toDouble(),
            officerLevel = bg["officer_level"]!!.jsonPrimitive.int,
            gold = bg["gold"]!!.jsonPrimitive.int,
            rice = bg["rice"]!!.jsonPrimitive.int,
        )

        val actorCity = City(
            id = cityId, nationId = actor.nationId, level = 8,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 100.0,
        )
        val nation = Nation(actor.nationId, if (actor.nationId == 0) 0 else 2, 0, "", gold = 1000, rice = 1000)
        val draft = GeneralActionDraft(actor, actorCity, nation)

        val candidateCityIds = listOf(1, 2, 19, 80)

        val logLines = c["logLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val broadcastLines = c["broadcastLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val dateSuffix = logLines.firstOrNull { it.contains("<1>") }
            ?.let { Regex("<1>(\\d{2}:\\d{2})</>").find(it)?.groupValues?.get(1) } ?: ""

        val seed = serializeSeed(hiddenSeed, c["scope"]!!.jsonPrimitive.content, year, month, generalId, def.rawClassName)
        assertEquals(c["seedString"]!!.jsonPrimitive.content, seed, "[$command] seedString byte-match")
        val rng = RandUtil(LiteHashDrbg(seed))

        val ctx = GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = WorldEnv(year, startYear, develCost),
            month = month,
            date = dateSuffix,
            args = emptyMap(),
            candidateCityIds = candidateCityIds,
        )

        def.resolve(ctx)

        val ref = RandUtil(LiteHashDrbg(seed))
        ref.choice(candidateCityIds)   // DRAW0
        assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
            "[$command] resolve가 정확히 1회 draw 소비 (no extra/missing)")

        assertEquals(logLines, ctx.logs(), "[$command] action-log byte-match")
        assertEquals(broadcastLines, ctx.globalActionLogs(), "[$command] broadcast byte-match")

        val ag = c["after"]!!.jsonObject["general"]!!.jsonObject
        assertEquals(ag["city"]!!.jsonPrimitive.int, draft.general.cityId, "[$command] general.city (접경귀환 dest)")
    }
}
