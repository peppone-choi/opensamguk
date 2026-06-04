package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — F4-C3 che_물자원조(원조) chief-command byte gate.
 *
 * Drives the REAL Kotlin resolver via [CommandRegistry] for the che_물자원조 golden
 * (golden/p2/che_물자원조-fixtures.json), seeded with the exact 6-component PHP seed.
 *
 * che_물자원조 = `nationCommand`-scope diplomacy(외교) chief command (actor gid 152 = ⓝ하진, 후한 chief).
 * 다른 국가(destNationID 2 = 황건적)에 금/쌀을 이전한다. run()은 RNG를 사용하지 않는 deterministic
 * 커맨드(draw_count=0). actor ActionLog 2줄(broadcast MONTH + PLAIN), broadcastLines(pushGlobalActionLog)
 * 없음(pushGlobalHistoryLog는 HISTORY 싱크 — 이 골든에는 미캡처), exp/ded += 5(레벨 변동 없음).
 *
 * 골든 after는 general/city 서브셋만 캡처 — nation gold/rice/surlimit는 미캡처(자유 입력). 게이트는
 * (a) 6-토큰 시드 byte-equal(rngFor GUARD) + 골든 draw_count == 0 + resolve가 ctx.rng를 0회 소비(probe),
 * (b) actor logLines byte-match(broadcast + PLAIN),
 * (c) broadcastLines == [] byte-match,
 * (d) general exp +5 / ded +5(phpRound) after-state.
 */
class Che물자원조GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `che_물자원조 byte-matches the PHP golden draw-for-draw`() {
        val command = "che_물자원조"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)               // REGISTRY RESOLUTION
        assertEquals(command, def.key, "$command registry key")

        // fixtures.json의 per-case draws.draw_count(byte oracle)를 직접 읽어 단언한다.
        val drawCounts = drawCountsFromFixture(command)

        for (c in f.cases) {
            // 아국 국고는 valueFit 클램프를 통과시키도록 충분히 크게(amount 1000/1000 ≤ gold-basegold,
            // rice-baserice). 골든 미캡처 = 문서화된 자유 입력.
            val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
            val draft = GeneralActionDraft(
                P2GoldenSupport.generalFrom(c.generalId, c.before.general),
                P2GoldenSupport.cityFrom(c.cityId, c.before.city),
                nation,
            )
            // 상대국(destNationID) carrier — 가산 mutation 대상(골든 미캡처).
            val destNationID = (c.arg?.get("destNationID") as? Number)?.toInt() ?: 0
            draft.destNation = Nation(
                id = destNationID,
                level = 2,
                capitalCityId = P2GoldenSupport.capitalOf(destNationID),
                name = P2GoldenSupport.nationNameOf(destNationID),
                gold = 0, rice = 0,
            )

            // destGeneralName carrier는 dest *국가* 표시명(황건적)을 운반(broadcast의 $destNationName).
            val ctx = P2GoldenSupport.ctxFor(
                f, c, draft, def.rawClassName,
                generalName = P2GoldenSupport.nameOf(c.generalId),
                destGeneralName = P2GoldenSupport.nationNameOf(destNationID),
            )
            def.resolve(ctx)

            // (b) actor 로그 byte-match
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            // (c) broadcast(pushGlobalActionLog) byte-match (= [])
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // (a) RNG draw-for-draw. 골든 draw_count == 0 + resolve가 ctx.rng를 0회 소비했음을 probe로 검증:
            //     동일 시드의 신선한 RNG의 첫 draw가, resolve 후 ctx.rng의 다음 draw와 같으면 0회 소비.
            assertEquals(0, drawCounts[c.name], "[$command/${c.name}] golden draw_count must be 0 (deterministic)")
            val probeSeed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
            val probe = RandUtil(LiteHashDrbg(probeSeed))
            assertEquals(probe.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
                "[$command/${c.name}] resolve must consume 0 RNG draws (deterministic)")

            // (d) general exp/ded after-state (phpRound half-away)
            assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience),
                "[$command/${c.name}] general.experience (+5)")
            assertEquals(c.after.general.int("dedication"), phpRound(draft.general.dedication),
                "[$command/${c.name}] general.dedication (+5)")
        }
    }

    /** fixtures.json의 case명 → draws.draw_count 맵(byte oracle 직접 파싱). */
    private fun drawCountsFromFixture(command: String): Map<String, Int> {
        val text = javaClass.classLoader
            .getResourceAsStream("golden/p2/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        return cases.associate { el ->
            val o = el.jsonObject
            val name = o["case"]!!.jsonPrimitive.content
            val count = o["draws"]?.jsonObject?.get("draw_count")?.jsonPrimitive?.int ?: 0
            name to count
        }
    }
}
