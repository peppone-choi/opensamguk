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
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_첩보(CheCheobo) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 정탐 커맨드. 적 dest 도시의 내정/병력 정보를 거리(dist)에 따라 차등 노출한다.
 * 캡처된 골든은 dist==2 분기("어느 정도 얻었습니다", draw_count=2).
 * dist 는 CalcCityDistance.searchDistance(cityId, 2)[destCityId] 로 산출(RNG 무관).
 *
 * Draw stream:
 *   DRAW0: nextRangeInt(1,100) → 7   (experience)
 *   DRAW1: nextRangeInt(1,70)  → 24  (dedication)
 */
class CheCheoboGoldenTest {

    private val registry = CommandRegistry(GeneralActionPipeline())

    @Test
    fun `che_첩보 golden byte-matches the PHP action log, 2-draw RNG, and post-state`() {
        val command = "che_첩보"
        val root = SabotageGoldenSupport.fixtureRoot("military", command)
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val c = root["cases"]!!.jsonArray.first().jsonObject

        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        val generalId = c["generalId"]!!.jsonPrimitive.int
        val cityId = c["cityId"]!!.jsonPrimitive.int
        val destCityId = c["destCityId"]!!.jsonPrimitive.int
        val env = c["env"]!!.jsonObject
        val year = env["year"]!!.jsonPrimitive.int
        val startYear = env["startYear"]!!.jsonPrimitive.int
        val month = env["month"]!!.jsonPrimitive.int
        val develCost = env["develCost"]!!.jsonPrimitive.int

        val actor = SabotageGoldenSupport.generalFrom(generalId, cityId, c["before"]!!.jsonObject["general"]!!.jsonObject)
        val destCity = SabotageGoldenSupport.destCityFrom(destCityId, c["destCityBefore"]!!.jsonObject["city"]!!.jsonObject)

        // dest 도시 주둔 적 장수 1명 (로그 "장수:1, 병력:0") — capture 가 stats 미덤프, count/crew 만 노출되는 dist==2 분기.
        val destGenerals = listOf(
            General(
                id = 9001, nationId = destCity.nationId, cityId = destCityId,
                leadership = 0, strength = 0, intel = 0, injury = 0,
                experience = 0.0, dedication = 0.0, officerLevel = 1,
                gold = 0, rice = 0, crew = 0,
            ),
        )

        val actorCity = City(
            id = cityId, nationId = actor.nationId, level = 8,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 100.0,
        )
        val nation = Nation(actor.nationId, if (actor.nationId == 0) 0 else 2, 0, "", gold = 1000, rice = 1000)
        val destNation = Nation(destCity.nationId, 2, 0, "", gold = 0, rice = 0)

        val draft = GeneralActionDraft(actor, actorCity, nation)
        draft.destCity = destCity
        draft.destNation = destNation

        val logLines = c["logLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val broadcastLines = c["broadcastLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val dateSuffix = Regex("<1>(\\d{2}:\\d{2})</>").find((logLines + broadcastLines).first { it.contains("<1>") })?.groupValues?.get(1) ?: ""

        val seed = serializeSeed(hiddenSeed, c["scope"]!!.jsonPrimitive.content, year, month, generalId, def.rawClassName)
        assertEquals(c["seedString"]!!.jsonPrimitive.content, seed, "[$command] seedString byte-match")
        val rng = RandUtil(LiteHashDrbg(seed))

        val ctx = GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = WorldEnv(year, startYear, develCost),
            month = month,
            date = dateSuffix,
            args = linkedMapOf("destCityID" to destCityId),
            candidateGenerals = destGenerals,
        )

        def.resolve(ctx)

        val ref = RandUtil(LiteHashDrbg(seed))
        ref.nextRangeInt(1, 100)   // DRAW0 exp
        ref.nextRangeInt(1, 70)    // DRAW1 ded
        assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
            "[$command] resolve가 정확히 2회 draw 소비 (no extra/missing)")

        assertEquals(logLines, ctx.logs(), "[$command] action-log byte-match")
        assertEquals(broadcastLines, ctx.globalActionLogs(), "[$command] broadcast byte-match")

        val ag = c["after"]!!.jsonObject["general"]!!.jsonObject
        val g = draft.general
        assertEquals(ag["experience"]!!.jsonPrimitive.int, phpRound(g.experience), "[$command] general.experience")
        assertEquals(ag["dedication"]!!.jsonPrimitive.int, phpRound(g.dedication), "[$command] general.dedication")
        assertEquals(ag["gold"]!!.jsonPrimitive.int, g.gold, "[$command] general.gold")
        assertEquals(ag["rice"]!!.jsonPrimitive.int, g.rice, "[$command] general.rice")
        assertEquals(ag["leadership_exp"]!!.jsonPrimitive.int, metaInt(g.meta, "leadership_exp"), "[$command] meta.leadership_exp")
    }
}
