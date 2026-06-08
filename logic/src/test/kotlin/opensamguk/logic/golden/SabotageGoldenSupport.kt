package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.util.phpRound
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Wave 1 계략(sabotage) family 골든 게이트 공용 지원부.
 *
 * 계략 픽스처는 actor/dest 장수의 leadership/strength/intel 을 before.general 에 직접 담는다
 * (P2GoldenSupport.RAW_STATS 와 무관 — 자기완결). 여기서 픽스처 raw JSON 을 그대로 읽어
 * actor·destCity·destGenerals 를 구성하고, RNG seedString 바이트일치 + draw-for-draw 커서일치
 * (참조 RNG 재생) + 로그 바이트일치 + after-state 를 검증한다.
 */
object SabotageGoldenSupport {

    fun fixtureRoot(dir: String, command: String): JsonObject {
        val text = SabotageGoldenSupport::class.java.classLoader
            .getResourceAsStream("golden/$dir/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject
    }

    /** before/after.general JSON(통무지 + meta 포함)에서 General 을 구성한다. */
    fun generalFrom(gid: Int, cityId: Int, g: JsonObject): General {
        val meta = linkedMapOf<String, Any?>()
        for (k in listOf("explevel", "intel_exp", "leadership_exp", "strength_exp", "max_domestic_critical")) {
            if (g.containsKey(k)) meta[k] = g[k]!!.jsonPrimitive.double.let { d ->
                if (k.endsWith("_exp") || k == "explevel") d.toInt() else d
            }
        }
        return General(
            id = gid,
            nationId = g["nation"]!!.jsonPrimitive.int,
            cityId = cityId,
            leadership = g["leadership"]!!.jsonPrimitive.int,
            strength = g["strength"]!!.jsonPrimitive.int,
            intel = g["intel"]!!.jsonPrimitive.int,
            injury = g["injury"]?.jsonPrimitive?.int ?: 0,
            experience = g["experience"]!!.jsonPrimitive.double,
            dedication = g["dedication"]!!.jsonPrimitive.double,
            officerLevel = g["officer_level"]!!.jsonPrimitive.int,
            gold = g["gold"]!!.jsonPrimitive.int,
            rice = g["rice"]!!.jsonPrimitive.int,
            crew = g["crew"]?.jsonPrimitive?.int ?: 0,
            train = g["train"]?.jsonPrimitive?.double ?: 0.0,
            atmos = g["atmos"]?.jsonPrimitive?.double ?: 0.0,
            meta = meta,
        )
    }

    /** destCityBefore.city JSON 에서 City 를 구성한다(전 필드 — 첩보/계략 로그가 secu/def/wall 까지 읽음). */
    fun destCityFrom(destCityId: Int, city: JsonObject): City = City(
        id = destCityId,
        nationId = city["nation"]!!.jsonPrimitive.int,
        level = city["level"]!!.jsonPrimitive.int,
        commerce = city["comm"]!!.jsonPrimitive.int,
        commerceMax = city["comm_max"]!!.jsonPrimitive.int,
        agriculture = city["agri"]!!.jsonPrimitive.int,
        agricultureMax = city["agri_max"]!!.jsonPrimitive.int,
        security = city["secu"]!!.jsonPrimitive.int,
        securityMax = city["secu_max"]!!.jsonPrimitive.int,
        defense = city["def"]!!.jsonPrimitive.int,
        defenseMax = city["def_max"]!!.jsonPrimitive.int,
        wall = city["wall"]!!.jsonPrimitive.int,
        wallMax = city["wall_max"]!!.jsonPrimitive.int,
        supplyState = 1,
        frontState = city["front"]?.jsonPrimitive?.int ?: 0,
        trust = city["trust"]!!.jsonPrimitive.double,
        population = city["pop"]!!.jsonPrimitive.int,
        populationMax = city["pop"]!!.jsonPrimitive.int,
    )

    /** destGenerals[].before.general → 적 도시 주둔 장수(부상 계산 대상). */
    fun destGeneralsFrom(destCityId: Int, arr: kotlinx.serialization.json.JsonArray): List<General> =
        arr.map { dg ->
            val before = dg.jsonObject["before"]!!.jsonObject["general"]!!.jsonObject
            generalFrom(dg.jsonObject["generalId"]!!.jsonPrimitive.int, destCityId, before)
        }

    /** turn-time <1>HH:MM</> 추출(첫 로그 라인). */
    private fun dateOf(logLines: List<String>, broadcast: List<String>): String {
        val src = (logLines + broadcast).firstOrNull { it.contains("<1>") } ?: return ""
        return Regex("<1>(\\d{2}:\\d{2})</>").find(src)?.groupValues?.get(1) ?: ""
    }

    /**
     * 계략 실패 분기 공용 게이트(che_선동/che_탈취 — 3-draw: nextBool(prob)=false → exp/ded).
     */
    fun runFailBranch(
        command: String,
        dir: String,
        registry: CommandRegistry,
        failProb: Double,
        statExpKey: String,
    ) {
        val root = fixtureRoot(dir, command)
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val caseJson = root["cases"]!!.jsonArray.first().jsonObject

        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        val generalId = caseJson["generalId"]!!.jsonPrimitive.int
        val cityId = caseJson["cityId"]!!.jsonPrimitive.int
        val destCityId = caseJson["destCityId"]!!.jsonPrimitive.int
        val cityDistance = caseJson["cityDistance"]!!.jsonPrimitive.int
        val env = caseJson["env"]!!.jsonObject
        val year = env["year"]!!.jsonPrimitive.int
        val startYear = env["startYear"]!!.jsonPrimitive.int
        val month = env["month"]!!.jsonPrimitive.int
        val develCost = env["develCost"]!!.jsonPrimitive.int

        val beforeG = caseJson["before"]!!.jsonObject["general"]!!.jsonObject
        val actor = generalFrom(generalId, cityId, beforeG)
        val destCity = destCityFrom(destCityId, caseJson["destCityBefore"]!!.jsonObject["city"]!!.jsonObject)
        val destGenerals = destGeneralsFrom(destCityId, caseJson["destGenerals"]!!.jsonArray)

        val actorCity = City(
            id = cityId, nationId = actor.nationId, level = 8,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 100.0,
        )
        val nation = Nation(
            id = actor.nationId,
            level = if (actor.nationId == 0) 0 else 2,
            capitalCityId = 0,
            name = "",
            gold = 1000, rice = 1000,
        )

        val draft = GeneralActionDraft(actor, actorCity, nation)
        draft.destCity = destCity

        val logLines = caseJson["logLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val broadcastLines = caseJson["broadcastLines"]!!.jsonArray.map { it.jsonPrimitive.content }

        val seed = serializeSeed(hiddenSeed, caseJson["scope"]!!.jsonPrimitive.content, year, month, generalId, def.rawClassName)
        assertEquals(caseJson["seedString"]!!.jsonPrimitive.content, seed, "[$command] seedString byte-match")
        val rng = RandUtil(LiteHashDrbg(seed))

        val ctx = GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = WorldEnv(year, startYear, develCost),
            month = month,
            date = dateOf(logLines, broadcastLines),
            args = linkedMapOf("destCityID" to destCityId),
            candidateGenerals = destGenerals,
            generalName = "",
            cityDistance = cityDistance,
        )

        def.resolve(ctx)

        // Draw-count 검증 — 참조 RNG 동일 커서
        val ref = RandUtil(LiteHashDrbg(seed))
        ref.nextBool(failProb)       // DRAW1
        ref.nextRangeInt(1, 100)     // DRAW2 (exp)
        ref.nextRangeInt(1, 70)      // DRAW3 (ded)
        assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
            "[$command] resolve가 정확히 3회 draw 소비 (no extra/missing)")

        assertEquals(logLines, ctx.logs(), "[$command] action-log byte-match")
        assertEquals(broadcastLines, ctx.globalActionLogs(), "[$command] broadcast byte-match")

        val ag = caseJson["after"]!!.jsonObject["general"]!!.jsonObject
        val g = draft.general
        assertEquals(ag["experience"]!!.jsonPrimitive.int, phpRound(g.experience), "[$command] general.experience")
        assertEquals(ag["dedication"]!!.jsonPrimitive.int, phpRound(g.dedication), "[$command] general.dedication")
        assertEquals(ag["gold"]!!.jsonPrimitive.int, g.gold, "[$command] general.gold")
        assertEquals(ag["rice"]!!.jsonPrimitive.int, g.rice, "[$command] general.rice")
        assertEquals(ag[statExpKey]!!.jsonPrimitive.int, metaInt(g.meta, statExpKey), "[$command] meta.$statExpKey")
    }
}
