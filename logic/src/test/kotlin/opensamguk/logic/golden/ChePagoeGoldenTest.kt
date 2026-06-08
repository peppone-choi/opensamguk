package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
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
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_파괴(ChePagoe) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 계략(sabotage) 커맨드. 적 dest 도시의 성벽과 수비를 파괴한다.
 * 현재 골든은 실패 분기만 캡처(draw_count=3).
 *
 * Draw stream (fail branch):
 *   DRAW1: nextBool(prob≈0.22) → false
 *   DRAW2: nextRangeInt(1,100) → 37
 *   DRAW3: nextRangeInt(1,70) → 25
 */
class ChePagoeGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `che_파괴 golden byte-matches the PHP action log, 3-draw RNG, and post-state`() {
        val command = "che_파괴"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        val c = f.cases.first()

        // P2GoldenSupport.Case에 없는 필드는 원시 JSON에서 직접 읽는다
        val fixtureText = javaClass.classLoader
            .getResourceAsStream("golden/p2/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(fixtureText).jsonObject
        val caseJson = root["cases"]!!.jsonArray.first().jsonObject
        val destCityId = caseJson["destCityId"]!!.jsonPrimitive.int
        val cityDistance = caseJson["cityDistance"]!!.jsonPrimitive.int
        val destCityBeforeJson = caseJson["destCityBefore"]!!.jsonObject["city"]!!.jsonObject
        val destGeneralsJson = caseJson["destGenerals"]!!.jsonArray

        // Actor
        val actor = P2GoldenSupport.generalFrom(c.generalId, c.before.general)

        // Actor city
        val city = P2GoldenSupport.cityFrom(c.cityId, c.before.city)

        // Dest city (수동 구성 — P2GoldenSupport.cityFrom는 supplyState=1, level=8 고정)
        val destCity = City(
            id = destCityId,
            nationId = destCityBeforeJson["nation"]!!.jsonPrimitive.int,
            level = destCityBeforeJson["level"]!!.jsonPrimitive.int,
            commerce = destCityBeforeJson["comm"]!!.jsonPrimitive.int,
            commerceMax = destCityBeforeJson["comm_max"]!!.jsonPrimitive.int,
            agriculture = destCityBeforeJson["agri"]!!.jsonPrimitive.int,
            agricultureMax = destCityBeforeJson["agri_max"]!!.jsonPrimitive.int,
            security = destCityBeforeJson["secu"]!!.jsonPrimitive.int,
            securityMax = destCityBeforeJson["secu_max"]!!.jsonPrimitive.int,
            defense = destCityBeforeJson["def"]!!.jsonPrimitive.int,
            defenseMax = destCityBeforeJson["def_max"]!!.jsonPrimitive.int,
            wall = destCityBeforeJson["wall"]!!.jsonPrimitive.int,
            wallMax = destCityBeforeJson["wall_max"]!!.jsonPrimitive.int,
            supplyState = 1,
            frontState = destCityBeforeJson["front"]!!.jsonPrimitive.int,
            trust = destCityBeforeJson["trust"]!!.jsonPrimitive.double,
            population = destCityBeforeJson["pop"]!!.jsonPrimitive.int,
            populationMax = destCityBeforeJson["pop"]!!.jsonPrimitive.int,
        )

        // Dest generals (수동 구성 — P2GoldenSupport.generalFrom은 RAW_STATS 제한)
        val destGenerals = destGeneralsJson.map { dg ->
            val before = dg.jsonObject["before"]!!.jsonObject["general"]!!.jsonObject
            General(
                id = dg.jsonObject["generalId"]!!.jsonPrimitive.int,
                nationId = before["nation"]!!.jsonPrimitive.int,
                cityId = destCityId,
                leadership = before["leadership"]!!.jsonPrimitive.int,
                strength = before["strength"]!!.jsonPrimitive.int,
                intel = before["intel"]!!.jsonPrimitive.int,
                injury = before["injury"]!!.jsonPrimitive.int,
                experience = before["experience"]!!.jsonPrimitive.double,
                dedication = before["dedication"]!!.jsonPrimitive.double,
                officerLevel = before["officer_level"]!!.jsonPrimitive.int,
                gold = before["gold"]!!.jsonPrimitive.int,
                rice = before["rice"]!!.jsonPrimitive.int,
                crew = before["crew"]!!.jsonPrimitive.int,
                train = before["train"]!!.jsonPrimitive.double,
                atmos = before["atmos"]!!.jsonPrimitive.double,
            )
        }

        // Nation
        val nation = P2GoldenSupport.nationFrom(c, gold = 1000, rice = 1000)

        // Draft
        val draft = GeneralActionDraft(actor, city, nation)
        draft.destCity = destCity

        // RNG
        val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
        assertEquals(c.seedString, seed, "[$command] seedString byte-match")
        val drbg = LiteHashDrbg(seed)
        val rng = RandUtil(drbg)

        // Context
        val ctx = GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = P2GoldenSupport.envOf(c),
            month = c.env.month,
            date = P2GoldenSupport.dateOf(c),
            args = c.arg ?: emptyMap(),
            candidateGenerals = destGenerals,
            generalName = P2GoldenSupport.nameOf(c.generalId),
            cityDistance = cityDistance,
        )

        def.resolve(ctx)

        // Draw count 검증 — 참조 RNG와 동일 커서인지 확인
        val ref = RandUtil(LiteHashDrbg(seed))
        ref.nextBool(0.21999999999999997)  // DRAW1
        ref.nextRangeInt(1, 100)            // DRAW2
        ref.nextRangeInt(1, 70)             // DRAW3
        assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
            "[$command] resolve가 정확히 3회 draw 소비 (no extra/missing)")

        // 로그 byte-match
        assertEquals(c.logLines, ctx.logs(), "[$command] action-log byte-match")
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command] broadcast byte-match")

        // After-state
        val ag = c.after.general
        val g = draft.general
        assertEquals(ag.int("experience"), phpRound(g.experience), "[$command] general.experience")
        assertEquals(ag.int("dedication"), phpRound(g.dedication), "[$command] general.dedication")
        assertEquals(ag.int("gold"), g.gold, "[$command] general.gold")
        assertEquals(ag.int("rice"), g.rice, "[$command] general.rice")
        assertEquals(ag.int("strength_exp"), metaInt(g.meta, "strength_exp"), "[$command] meta.strength_exp")
        assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "[$command] meta.explevel")
    }
}
