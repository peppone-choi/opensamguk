package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME (F4-C3) — che_부대탈퇴지시 byte/draw gate vs the committed PHP golden
 * (golden/p2/che_부대탈퇴지시-fixtures.json, hiddenSeed a2db167c…, acting gid 152 = ⓝ하진, 후한 chief).
 *
 * 부대탈퇴지시는 deterministic (RNG 미사용) — golden draw_count=0. 액터(가 dest를 부대에서 강제 탈퇴)
 * 라인 1개 + dest action 라인 1개 + dest.troop=0 델타를 byte/state로 검증한다. dest gid 18 = ⓝ관우는
 * P2GoldenSupport.NAMES 밖이라 액터/관우 이름을 테스트에서 직접 주입한다(서포트 미수정).
 *
 * golden lines:
 *   actor : <C>●</>1월:<Y>ⓝ관우</>에게 부대 탈퇴를 지시했습니다.   (dest 이름)
 *   dest  : <C>●</>1월:<Y>ⓝ하진</>에게 부대 탈퇴를 지시 받았습니다. (actor 이름)
 */
class Che부대탈퇴지시GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    /** dest gid 18 = 관우 (golden <Y>ⓝ관우</> 토큰). 액터 gid 152 = 하진 (P2GoldenSupport.NAMES). */
    private val destName = "ⓝ관우"

    @Test
    fun `troop-detach byte-matches the PHP golden action + dest log and zero draws`() {
        val f = P2GoldenSupport.load("che_부대탈퇴지시")
        // REGISTRY RESOLUTION required — key 검증.
        val def = registry.resolve("che_부대탈퇴지시")
        assertEquals("che_부대탈퇴지시", def.key, "registry key")

        val c = f.cases.single()
        val dg = c.destGenerals.single()
        assertEquals(18, dg.generalId, "[부대탈퇴지시] dest gid")
        // golden = deterministic: draws.draw_count==0 + draw_stream empty (raw fixture에서 직접 검증).
        val draws = goldenDraws()
        assertEquals(0, draws.first, "[부대탈퇴지시] golden draw_count")
        assertEquals(0, draws.second, "[부대탈퇴지시] golden draw_stream size")

        // actor gid 152 = 하진 (후한 chief).
        val g = P2GoldenSupport.generalFrom(c.generalId, c.before.general,
            extraMeta = linkedMapOf("dedlevel" to 0))
        val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
        val draft = GeneralActionDraft(g, P2GoldenSupport.cityFrom(c.cityId, c.before.city), nation)

        // dest gid 18 = 관우, MEMBER 분기를 타도록 before troop = (non-zero, != 18) 부대장 gid.
        // golden after는 troop=0 만 보장; before는 dump에 없으므로 MEMBER 전제(troop != 0 && != id)를
        // 충족하는 임의 부대장 gid 152를 documented input으로 주입한다. PHP는 이 값을 0으로만 세팅.
        val destBeforeTroop = 152
        draft.destGeneral = General(
            id = dg.generalId, nationId = dg.after.general.int("nation"),
            cityId = dg.after.general.int("city"),
            leadership = 0, strength = 0, intel = 0, injury = 0,
            experience = dg.after.general.double("experience"),
            dedication = dg.after.general.double("dedication"),
            officerLevel = dg.after.general.int("officer_level"),
            gold = dg.after.general.int("gold"), rice = dg.after.general.intOrNull("rice") ?: 0,
            troop = destBeforeTroop,
            meta = linkedMapOf("name" to destName))

        // seedString GUARD-assert (ctxFor → rngFor) + RNG(0 draw 소비) build.
        val ctx = P2GoldenSupport.ctxFor(f, c, draft, def.rawClassName,
            generalName = P2GoldenSupport.nameOf(c.generalId), destGeneralName = destName)
        def.resolve(ctx)

        // 액터 라인 byte-match.
        assertEquals(c.logLines, ctx.logs(), "[부대탈퇴지시] action-log")
        // broadcast 없음.
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[부대탈퇴지시] broadcast (empty)")
        assertEquals(emptyList(), ctx.plainLogs(), "[부대탈퇴지시] plain (empty)")

        // dest action 라인: addLogTo(MONTH body, 접두사 없음) → dest 로거가 `<C>●</>{month}월:` 래핑.
        val monthDest = ctx.logsTo(dg.generalId).map { "<C>●</>${c.env.month}월:$it" }
        assertEquals(dg.logLines, monthDest, "[부대탈퇴지시] dest log")

        // state 델타: dest.troop=0 (강제 탈퇴), 그 외 dest after byte-state.
        val movedDest = draft.destGeneral!!
        assertEquals(0, movedDest.troop, "[부대탈퇴지시] dest troop reset")
        assertEquals(dg.after.general.int("troop"), movedDest.troop, "[부대탈퇴지시] dest troop vs golden")
        assertEquals(dg.after.general.int("experience"), phpRound(movedDest.experience), "[부대탈퇴지시] dest exp")
        assertEquals(dg.after.general.int("dedication"), phpRound(movedDest.dedication), "[부대탈퇴지시] dest ded")
        assertEquals(dg.after.general.int("officer_level"), movedDest.officerLevel, "[부대탈퇴지시] dest officer_level")
        assertEquals(dg.after.general.int("city"), movedDest.cityId, "[부대탈퇴지시] dest city")

        // actor 미변경 (before == after).
        assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience), "[부대탈퇴지시] actor exp")
        assertEquals(c.after.general.int("dedication"), phpRound(draft.general.dedication), "[부대탈퇴지시] actor ded")
        assertEquals(c.after.general.int("troop"), draft.general.troop, "[부대탈퇴지시] actor troop")
    }

    /** raw fixture에서 case[0].draws.{draw_count, draw_stream.size} 를 직접 읽는다(서포트 DTO 미수정). */
    private fun goldenDraws(): Pair<Int, Int> {
        val text = javaClass.classLoader
            .getResourceAsStream("golden/p2/che_부대탈퇴지시-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val draws = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
            .first().jsonObject["draws"]!!.jsonObject
        val drawCount = draws["draw_count"]!!.jsonPrimitive.int
        val streamSize = draws["draw_stream"]!!.jsonArray.size
        return drawCount to streamSize
    }
}
