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
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_화계(CheHwagye) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 계략(sabotage) 커맨드. 적 dest 도시의 농업/상업을 불태우고 주둔 적 장수를 부상시킨다.
 * 캡처된 골든은 성공 분기(draw_count=11, 적 장수 3명 전원 부상).
 *
 * Draw stream (success branch):
 *   DRAW0:  nextBool(0.22)        → true  (성공)
 *   DRAW1:  nextBool(0.3)         → true  } gid 8   부상 1
 *   DRAW2:  nextRangeInt(1,16)    → 1     }
 *   DRAW3:  nextBool(0.3)         → true  } gid 58  부상 11
 *   DRAW4:  nextRangeInt(1,16)    → 11    }
 *   DRAW5:  nextBool(0.3)         → true  } gid 106 부상 16
 *   DRAW6:  nextRangeInt(1,16)    → 16    }
 *   DRAW7:  nextRangeInt(100,800) → 537   (농업 피해)
 *   DRAW8:  nextRangeInt(100,800) → 227   (상업 피해)
 *   DRAW9:  nextRangeInt(201,300) → 222   (experience)
 *   DRAW10: nextRangeInt(141,210) → 169   (dedication)
 */
class CheHwagyeGoldenTest {

    private val registry = CommandRegistry(GeneralActionPipeline())

    @Test
    fun `che_화계 golden byte-matches the PHP action log, 11-draw RNG, injury, and post-state`() {
        val command = "che_화계"
        val root = SabotageGoldenSupport.fixtureRoot("p2", command)
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val c = root["cases"]!!.jsonArray.first().jsonObject

        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        val generalId = c["generalId"]!!.jsonPrimitive.int
        val cityId = c["cityId"]!!.jsonPrimitive.int
        val destCityId = c["destCityId"]!!.jsonPrimitive.int
        val cityDistance = c["cityDistance"]!!.jsonPrimitive.int
        val env = c["env"]!!.jsonObject
        val year = env["year"]!!.jsonPrimitive.int
        val startYear = env["startYear"]!!.jsonPrimitive.int
        val month = env["month"]!!.jsonPrimitive.int
        val develCost = env["develCost"]!!.jsonPrimitive.int

        val actor = SabotageGoldenSupport.generalFrom(generalId, cityId, c["before"]!!.jsonObject["general"]!!.jsonObject)
        val destCity = SabotageGoldenSupport.destCityFrom(destCityId, c["destCityBefore"]!!.jsonObject["city"]!!.jsonObject)
        val destGenerals = SabotageGoldenSupport.destGeneralsFrom(destCityId, c["destGenerals"]!!.jsonArray)

        val actorCity = City(
            id = cityId, nationId = actor.nationId, level = 8,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 100.0,
        )
        val nation = Nation(actor.nationId, if (actor.nationId == 0) 0 else 2, 0, "", gold = 1000, rice = 1000)

        val draft = GeneralActionDraft(actor, actorCity, nation)
        draft.destCity = destCity

        val logLines = c["logLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val broadcastLines = c["broadcastLines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val dateSuffix = Regex("<1>(\\d{2}:\\d{2})</>").find(logLines.first { it.contains("<1>") })?.groupValues?.get(1) ?: ""

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
            cityDistance = cityDistance,
        )

        def.resolve(ctx)

        // Draw-count 검증 — 11 draw 정확 소비
        val ref = RandUtil(LiteHashDrbg(seed))
        ref.nextBool(0.21999999999999997)               // DRAW0 성공
        repeat(3) { ref.nextBool(0.3); ref.nextRangeInt(1, 16) }  // DRAW1..6 부상 3명
        ref.nextRangeInt(100, 800)                       // DRAW7 농업
        ref.nextRangeInt(100, 800)                       // DRAW8 상업
        ref.nextRangeInt(201, 300)                       // DRAW9 exp
        ref.nextRangeInt(141, 210)                       // DRAW10 ded
        assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
            "[$command] resolve가 정확히 11회 draw 소비 (no extra/missing)")

        assertEquals(logLines, ctx.logs(), "[$command] action-log byte-match")
        assertEquals(broadcastLines, ctx.globalActionLogs(), "[$command] broadcast byte-match")

        // dest 장수 부상 (cascadeGenerals): gid 8→1, 58→11, 106→16
        val injuredById = draft.cascadeGenerals.associate { it.id to it.injury }
        assertEquals(mapOf(8 to 1, 58 to 11, 106 to 16), injuredById, "[$command] dest 장수 부상량")

        // actor after-state
        val ag = c["after"]!!.jsonObject["general"]!!.jsonObject
        val g = draft.general
        assertEquals(ag["experience"]!!.jsonPrimitive.int, phpRound(g.experience), "[$command] general.experience")
        assertEquals(ag["dedication"]!!.jsonPrimitive.int, phpRound(g.dedication), "[$command] general.dedication")
        assertEquals(ag["gold"]!!.jsonPrimitive.int, g.gold, "[$command] general.gold")
        assertEquals(ag["rice"]!!.jsonPrimitive.int, g.rice, "[$command] general.rice")
        assertEquals(ag["intel_exp"]!!.jsonPrimitive.int, metaInt(g.meta, "intel_exp"), "[$command] meta.intel_exp")
    }
}
