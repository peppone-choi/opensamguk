package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME (F4-C3) — che_허보 (chief 전략 사령) draw-for-draw 바이트 게이트.
 *
 * che_허보: 사령(chief)이 선포/전쟁중(state 0,1)인 적 dest 도시의 각 적 장수를 무작위 보급(supply=1) 적
 * 도시로 이동시킨다. dest 장수마다 `rng.choice(보급 적 도시)`를 1회 뽑고, 뽑힌 도시가 공격 대상(dest) 도시면
 * 1회 re-roll 한다(draw-for-draw 패러티 타겟). actor action 로그 1줄("허보 발동!"), exp/ded += 5, 각 dest
 * 장수에게 PLAIN "상대의 허보에 당했다!" 로그.
 *
 * 골든(che_허보-fixtures.json): hiddenSeed a2db167c.., 사령 gid 152(ⓝ하진, 후한 nation 1, city 17),
 * destCityID=1. draws.draw_count=3 (choice ×3, re-roll 미발동 — 결과 36/80/2 ≠ destCity 1). dest 장수
 * gid 108→city36, gid115→city80, gid137→city2.
 *
 * 보급 적 도시 리스트(SELECT city FROM city WHERE nation=destNation AND supply=1)는 월드 집계라 메모리
 * 드래프트에 없어, 골든 draw_stream의 `items`(= [1,2,18,19,36,38,39,78,80,81])를 그대로 documented fixture
 * input으로 주입한다(fabricate 아님 — 커밋된 골든에서 직접 가져옴).
 */
class Che허보GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    /** SELECT city FROM city WHERE nation=destNation AND supply=1 — 골든 draw_stream items에서 그대로. */
    private val suppliedEnemyCities = listOf(1, 2, 18, 19, 36, 38, 39, 78, 80, 81)

    @Test
    fun `che_허보 byte-matches the PHP golden draw-for-draw`() {
        val command = "che_허보"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)               // REGISTRY RESOLUTION
        assertEquals(command, def.key, "$command registry key")

        val c = f.cases.first()
        assertEquals(152, c.generalId, "acting general gid")
        val destCityId = (c.arg!!["destCityID"] as Number).toInt()
        assertEquals(1, destCityId, "dest city id")

        // dest 도시 적 장수 후보 — golden destGenerals 순서대로(=draw 순서). before city = 공격 대상(dest) 도시,
        // after city = 이동 결과(36/80/2). 스탯(L/S/I)은 이 커맨드가 읽지 않으며 캡처에도 없음 → 0 placeholder.
        val candidates: List<General> = c.destGenerals.map { dg ->
            val af = dg.after.general
            General(
                id = dg.generalId,
                nationId = af.int("nation"),
                cityId = destCityId,          // before — 공격 대상 도시에 있던 적 장수.
                leadership = 0, strength = 0, intel = 0, injury = 0,
                experience = af.double("experience"),
                dedication = af.double("dedication"),
                officerLevel = af.int("officer_level"),
                gold = af.int("gold"),
                rice = af.int("rice"),
                crew = af.int("crew"),
            )
        }

        val actor = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
        val nation = P2GoldenSupport.nationFrom(c, gold = 1000, rice = 1000)
        val draft = GeneralActionDraft(
            actor,
            P2GoldenSupport.cityFrom(c.cityId, c.before.city),
            nation,
        )

        // --- 레퍼런스 RNG: 동일 시드에서 choice ×destCount를 선재현해 골든 결과(36/80/2)를 단언 ---
        // (re-roll은 미발동 — 결과 어느 것도 destCity(1)가 아님 → 순수 choice 3회.)
        val refSeed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
        val ref = RandUtil(LiteHashDrbg(refSeed))
        val refResults = (c.destGenerals.indices).map { ref.choice(suppliedEnemyCities) }
        assertEquals(listOf(36, 80, 2), refResults, "[허보] golden choice 결과 = dest 이동 도시")

        // --- resolve (보급 적 도시 + destCityID 주입) ---
        val ctx = P2GoldenSupport.ctxFor(
            f, c, draft, def.rawClassName,
            args = linkedMapOf("destCityID" to destCityId, "__suppliedEnemyCities" to suppliedEnemyCities),
            candidateGenerals = candidates,
            generalName = P2GoldenSupport.nameOf(c.generalId),
        )
        def.resolve(ctx)

        // --- (b) actor 액션로그 byte-match ("허보 발동! …") ---
        assertEquals(c.logLines, ctx.logs(), "[허보] actor action-log byte-match")
        // --- broadcast(pushGlobalActionLog) 없음 → [] (발동 메시지는 own-nation 장수 스코프로 flush) ---
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[허보] broadcast byte-match (empty)")

        // --- (a) draw-for-draw: dest 장수 이동 도시 = 골든 choice 결과(순서대로) ---
        assertEquals(
            c.destGenerals.map { it.after.general.int("city") },
            draft.cascadeGenerals.map { it.cityId },
            "[허보] dest 장수 이동 도시 draw-for-draw",
        )
        // draw_count 정확성: resolve가 정확히 destCount회 choice를 소비했음을 probe로 검증.
        // (resolve 후 ctx.rng와 ref가 동일 커서 → 다음 draw가 일치하면 초과/누락 draw 없음.)
        assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
            "[허보] resolve가 정확히 ${c.destGenerals.size}회 draw 소비 (no extra/missing)")

        // --- dest 장수 PLAIN 로그 byte-match (각 장수 자신의 스코프) ---
        for (dg in c.destGenerals) {
            assertEquals(dg.logLines, ctx.plainLogsTo(dg.generalId),
                "[허보] dest ${dg.generalId} PLAIN log byte-match")
        }

        // --- actor 상태 델타: exp/ded += 5 (phpRound half-away), 도시/소속 불변 ---
        assertEquals(c.after.general.int("experience"), phpRound(draft.general.experience), "[허보] actor experience (+5)")
        assertEquals(c.after.general.int("dedication"), phpRound(draft.general.dedication), "[허보] actor dedication (+5)")
        assertEquals(c.after.general.int("city"), draft.general.cityId, "[허보] actor city unchanged")

        // --- nation strategic_cmd_limit = 9 (golden 미캡처 — 패러티 대상, meta 기록) ---
        assertEquals(9, draft.nation!!.meta["strategic_cmd_limit"], "[허보] nation strategic_cmd_limit=9")
    }
}
