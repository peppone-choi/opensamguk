package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME (F4-C3) — che_몰수 (chief seizure) byte gate.
 *
 * che_몰수: 사령(chief)이 아군(friendly) dest 장수에게서 금/쌀(amount)을 국고로 몰수한다. dest 자원 -= amount,
 * nation 국고 += amount; actor 본인 자원은 불변. dest PLAIN action 라인 + 몰수 발동 actor 로그. crossGeneral.
 *
 * RNG (che_몰수.php:174): dest NPCType>=2 일 때만 `rng->nextBool(npcSeizureMessageProb=0.01)`가 1회 발동
 * (메시지 발동 여부), hit이면 `rng->choice(npcTexts)` 추가. 골든 케이스는 NPCType>=2 라서 nextBool이 발동하지만
 * 결과 false → 메시지/choice 없음 (draw_count=1). 그 외 추첨 없음.
 *
 * 골든(che_몰수-fixtures.json): hiddenSeed a2db167c.., 사령 gid 152(하진), dest gid 14(공융), 금 500 몰수.
 * 드로 스트림 [nextBool(0.01)=false], draw_count=1.
 */
class Che몰수GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `seizure byte-matches the PHP golden — draw stream, acting+dest log, state delta`() {
        val f = P2GoldenSupport.load("che_몰수")
        val def = registry.resolve("che_몰수")
        // REGISTRY RESOLUTION required — rawClassName = 6th seed token (= key), default, NOT overridden.
        assertEquals("che_몰수", def.key, "registry key")

        val c = f.cases.first()
        val isGold = c.arg!!["isGold"] as Boolean
        val argAmount = (c.arg!!["amount"] as Number).toInt()
        val destId = (c.arg!!["destGeneralID"] as Number).toInt()
        val dg = c.destGenerals.first()
        assertEquals(destId, dg.generalId, "dest id")

        // nation 국고 (몰수 수령처) — 큰 잔고로 초기화. 후한(nation 1).
        val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
        val actor = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
        val draft = GeneralActionDraft(
            actor,
            P2GoldenSupport.cityFrom(c.cityId, c.before.city),
            nation,
        ).also {
            // dest gid 14 (공융) — golden after gold=500 = before 1000 - 500. NPCType>=2 라야 nextBool 발동
            // (draw_count=1). dest-after general subset에서 before를 복원: gold += argAmount (몰수 환원),
            // 나머지 컬럼은 after = before (몰수는 자원만 변동).
            val af = dg.after.general
            it.destGeneral = General(
                id = destId,
                nationId = af.int("nation"),
                cityId = af.int("city"),
                leadership = P2GoldenSupport.RAW_STATS.getValue(destId).leadership,
                strength = P2GoldenSupport.RAW_STATS.getValue(destId).strength,
                intel = P2GoldenSupport.RAW_STATS.getValue(destId).intel,
                injury = 0,
                experience = af.double("experience"),
                dedication = af.double("dedication"),
                officerLevel = af.int("officer_level"),
                gold = if (isGold) af.int("gold") + argAmount else af.int("gold"),
                rice = if (isGold) af.int("rice") else af.int("rice") + argAmount,
                npcType = 2,  // NPCType>=2 — golden draw stream에서 nextBool(0.01)이 발동하므로.
                meta = linkedMapOf("name" to P2GoldenSupport.nameOf(destId)),
            )
        }

        val ctx = P2GoldenSupport.ctxFor(
            f, c, draft, def.rawClassName,
            destGeneralName = P2GoldenSupport.nameOf(destId),
        )
        def.resolve(ctx)

        // --- acting + dest log byte-match ---
        assertEquals(c.logLines, ctx.logs(), "[몰수] acting action-log byte-match")
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[몰수] broadcast byte-match")
        assertEquals(dg.logLines, ctx.plainLogsTo(destId), "[몰수] dest action-log byte-match")

        // --- RNG draw stream: draw_count=1, [nextBool(0.01)=false], no choice (false branch) ---
        // nextBool false → 메시지 미발동 → sendMessage 호출 없음. 그게 단일 추첨 소비의 증거.
        assertEquals(0, ctx.messages().size, "[몰수] nextBool=false → no NPC message (single draw consumed, no choice)")

        // --- state delta: dest 자원 -= amount, actor 불변, nation 국고 += amount ---
        val destAmount = if (isGold) draft.destGeneral!!.gold else draft.destGeneral!!.rice
        assertEquals(if (isGold) dg.after.general.int("gold") else dg.after.general.int("rice"),
            destAmount, "[몰수] dest resource -= amount")
        // actor 본인 자원 불변 (국고로 들어감).
        assertEquals(c.after.general.int("gold"), draft.general.gold, "[몰수] actor gold unchanged")
        assertEquals(c.after.general.int("rice"), draft.general.rice, "[몰수] actor rice unchanged")
        // nation 국고 += amount.
        val seized = argAmount  // valueFit(500, 0, 1000) = 500
        assertEquals(if (isGold) 100000 + seized else 100000,
            draft.nation!!.gold, "[몰수] nation gold += amount")
        assertEquals(if (isGold) 100000 else 100000 + seized,
            draft.nation!!.rice, "[몰수] nation rice += amount")
    }
}
