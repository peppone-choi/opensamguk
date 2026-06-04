package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME (F4-C3) — che_필사즉생 (chief 전략 사령) byte gate.
 *
 * che_필사즉생: 사령(chief)이 아국 교전중일 때 발동. 모든 아국 장수(본인 포함)의 train/atmos를 100으로
 * 끌어올린다. RNG 미사용(deterministic, draw_count=0). 무인자(zero-arg). actor action 로그 1줄
 * ("필사즉생 발동! …"), exp/ded += 15, 아국 장수(본인 제외) 각자에게 PLAIN broadcast 라인. crossGeneral.
 *
 * RNG: 추첨 없음 — golden draws.draw_count=0, draw_stream=[]. 단 한 번의 draw도 추가하면 안 됨.
 *
 * 골든(che_필사즉생-fixtures.json): hiddenSeed a2db167c.., 사령 gid 152(ⓝ하진), 후한(nation 1).
 * actor exp 4582→4597(+15), ded 4120→4135(+15), train/atmos 0→100. 36명 dest 장수 각자
 * train/atmos 0→100 + PLAIN broadcast "<Y>ⓝ하진</>이 <M>필사즉생</>을 발동하였습니다.".
 */
class Che필사즉생GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `pilsa byte-matches the PHP golden — zero draws, actor+dest log, train atmos delta`() {
        val f = P2GoldenSupport.load("che_필사즉생")
        val def = registry.resolve("che_필사즉생")
        // REGISTRY RESOLUTION required — rawClassName = 6th seed token (= key), default, NOT overridden.
        assertEquals("che_필사즉생", def.key, "registry key")

        val c = f.cases.first()
        assertEquals(152, c.generalId, "acting general gid")
        // 추첨 0회 (deterministic) — golden draw_count=0.
        assertEquals(0, c.arg?.size ?: 0, "zero-arg")

        // actor 본인 (gid 152 ⓝ하진). before train/atmos=0.
        val actor = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
        val nation = P2GoldenSupport.nationFrom(c, gold = 1000, rice = 1000)

        // dest 장수 후보 — golden destGenerals 각각을 before(train/atmos=0)로 복원. 스탯(L/S/I)은 이 커맨드가
        // 읽지 않으며 캡처에도 없음 → 0 placeholder. 이름도 안 읽음(broadcast는 actor 이름 사용).
        val candidates: List<General> = c.destGenerals.map { dg ->
            val af = dg.after.general
            General(
                id = dg.generalId,
                nationId = af.int("nation"),
                cityId = af.int("city"),
                leadership = 0, strength = 0, intel = 0, injury = 0,
                experience = af.double("experience"),
                dedication = af.double("dedication"),
                officerLevel = af.int("officer_level"),
                gold = af.int("gold"),
                rice = af.int("rice"),
                crew = af.int("crew"),
                train = 0.0,   // before — golden after=100 라야 0→100 flip 검증.
                atmos = 0.0,
            )
        }

        val draft = GeneralActionDraft(
            actor,
            P2GoldenSupport.cityFrom(c.cityId, c.before.city),
            nation,
        )

        val ctx = P2GoldenSupport.ctxFor(
            f, c, draft, def.rawClassName,
            candidateGenerals = candidates,
            generalName = P2GoldenSupport.nameOf(c.generalId),  // ⓝ하진 — broadcast <Y>{name}</> 토큰.
        )
        def.resolve(ctx)

        // --- acting action-log byte-match (1줄: "필사즉생 발동! …") ---
        assertEquals(c.logLines, ctx.logs(), "[필사즉생] acting action-log byte-match")
        // --- broadcast(pushGlobalActionLog)는 없음 → broadcastLines=[] ---
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[필사즉생] broadcast byte-match (empty)")

        // --- RNG: 추첨 0회 — 추첨 발생의 부수효과(message)가 전혀 없어야 함 ---
        assertEquals(0, ctx.messages().size, "[필사즉생] zero draws → no message side-effect")

        // --- dest PLAIN broadcast 라인 byte-match (장수 36명 각자, actor 이름 사용) ---
        for (dg in c.destGenerals) {
            assertEquals(
                dg.logLines, ctx.plainLogsTo(dg.generalId),
                "[필사즉생] dest ${dg.generalId} action-log byte-match",
            )
        }

        // --- actor 상태 델타: train/atmos 0→100, exp +15, ded +15 ---
        assertEquals(c.after.general.int("train"), draft.general.train.toInt(), "[필사즉생] actor train→100")
        assertEquals(c.after.general.int("atmos"), draft.general.atmos.toInt(), "[필사즉생] actor atmos→100")
        assertEquals(c.after.general.int("experience"), draft.general.experience.toInt(), "[필사즉생] actor exp +15")
        assertEquals(c.after.general.int("dedication"), draft.general.dedication.toInt(), "[필사즉생] actor ded +15")
        // 자원/도시/소속 불변.
        assertEquals(c.after.general.int("gold"), draft.general.gold, "[필사즉생] actor gold unchanged")
        assertEquals(c.after.general.int("rice"), draft.general.rice, "[필사즉생] actor rice unchanged")
        assertEquals(c.after.general.int("city"), draft.general.cityId, "[필사즉생] actor city unchanged")

        // --- dest 상태 델타: 모든 아국 장수 train/atmos→100 (cascadeGenerals) ---
        assertEquals(c.destGenerals.size, draft.cascadeGenerals.size, "[필사즉생] cascadeGenerals count = dest count")
        val cascadeById = draft.cascadeGenerals.associateBy { it.id }
        for (dg in c.destGenerals) {
            val moved = cascadeById.getValue(dg.generalId)
            assertEquals(dg.after.general.int("train"), moved.train.toInt(), "[필사즉생] dest ${dg.generalId} train→100")
            assertEquals(dg.after.general.int("atmos"), moved.atmos.toInt(), "[필사즉생] dest ${dg.generalId} atmos→100")
            // dest exp/ded 불변(필사즉생은 dest train/atmos만 변경).
            assertEquals(dg.after.general.int("experience"), moved.experience.toInt(), "[필사즉생] dest ${dg.generalId} exp unchanged")
            assertEquals(dg.after.general.int("dedication"), moved.dedication.toInt(), "[필사즉생] dest ${dg.generalId} ded unchanged")
        }

        // --- nation strategic_cmd_limit = 9 (golden 미캡처지만 패러티 대상 — meta에 기록) ---
        assertEquals(9, draft.nation!!.meta["strategic_cmd_limit"], "[필사즉생] nation strategic_cmd_limit=9")
    }
}
