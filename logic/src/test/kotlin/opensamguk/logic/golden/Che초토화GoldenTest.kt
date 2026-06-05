package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME (F4-C3) — che_초토화 (chief scorched-earth) byte gate.
 *
 * che_초토화: 사령(chief)이 아국 비수도 보급 dest 도시를 공백지(중립, scorched)로 만든다. actor exp -= exp*0.1
 * (affectTrigger=false 레벨만 재계산) 후 exp/ded += 5*(preReqTurn+1)=15, actor betray+1, dest 도시 공백지화
 * (nation=0/front=0/conflict={}/스탯 감쇄), nation 국고 += calcReturnAmount, surlimit += 24. broadcast(=true).
 *
 * RNG (manifest_c3 "none/deterministic"): 추첨 미사용 — draw_count=0. context.rng 소비 금지.
 *
 * 골든(che_초토화-fixtures.json): hiddenSeed a2db167c.., 사령 gid 152(하진), city 17(낙양), dest city 4(장안),
 * nation 1(후한). before exp40/ded4120/betray0 → after exp51/ded4135/betray1. draw_count=0.
 *   - exp: 40 → 40-4(=-exp*0.1) → 36 → 36+15 → 51 (레벨 불변, 로그 없음)
 *   - ded: 4120 → 4120+15 → 4135 (레벨 불변)
 *   - betray: 0 → 1 (increaseVar, uncapped)
 * acting 로그 1줄 + broadcast 1줄이 byte-match 타겟.
 */
class Che초토화GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `scorched-earth byte-matches the PHP golden — no draws, acting+broadcast log, state delta`() {
        val f = P2GoldenSupport.load("che_초토화")
        val def = registry.resolve("che_초토화")
        // REGISTRY RESOLUTION required — rawClassName = 6th seed token (= key), default, NOT overridden.
        assertEquals("che_초토화", def.key, "registry key")

        val c = f.cases.first()
        val destCityId = (c.arg!!["destCityID"] as Number).toInt()
        assertEquals(4, destCityId, "dest city id (장안)")

        // 후한(nation 1) 국고는 큰 잔고로 초기화 (초토화 amount 환원 수령처).
        val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000)
        val actor = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
        // before betray=0 — meta에 betray 시드 (after=1 검증용). before.general.betray 컬럼 사용.
        val actorWithBetray = actor.copy(
            meta = opensamguk.logic.domain.withMeta(actor.meta, "betray" to c.before.general.int("betray")),
        )
        // dest 도시(장안, city 4) — 공백지화 입력. 골든 after.city는 ACTOR의 city(17 낙양)라 dest 스탯은
        // 검증 대상이 아님(스코어 밖). level=6(<8 → did_특성초토화 미발동) + 보급 도시로 시드.
        val destCity = City(
            id = destCityId,
            nationId = nation.id,
            level = 6,
            commerce = 5000, commerceMax = 8000,
            agriculture = 5000, agricultureMax = 7500,
            supplyState = 1,
            frontState = 0,
            trust = 80.0,
            security = 4000, securityMax = 6000,
            defense = 5000, defenseMax = 7800,
            wall = 5000, wallMax = 8100,
            population = 200000, populationMax = 300000,
        )

        val draft = GeneralActionDraft(
            actorWithBetray,
            P2GoldenSupport.cityFrom(c.cityId, c.before.city),
            nation,
        ).also { it.destCity = destCity }

        val ctx = P2GoldenSupport.ctxFor(
            f, c, draft, def.rawClassName,
            generalName = P2GoldenSupport.nameOf(c.generalId),
        )
        def.resolve(ctx)

        // --- acting + broadcast log byte-match ---
        assertEquals(c.logLines, ctx.logs(), "[초토화] acting action-log byte-match")
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[초토화] broadcast byte-match")
        // levelCross 미발생(exp 40→36→51 레벨 불변) → PLAIN 레벨 로그 없음.
        assertEquals(emptyList(), ctx.plainLogs(), "[초토화] no level-cross plain log")

        // --- RNG draw stream: draw_count=0 (deterministic) ---
        // 추첨이 없으므로 rng 미소비 → 메시지/캐스케이드 추첨 없음. 메시지 버퍼 비어 있어야 함.
        assertEquals(0, ctx.messages().size, "[초토화] no draws → no message (deterministic)")

        // --- state delta: actor exp/ded/betray; gold/rice 불변(actor) ---
        val ag = c.after.general
        assertEquals(ag.int("experience"), phpRound(draft.general.experience), "[초토화] actor experience (40→36→51)")
        assertEquals(ag.int("dedication"), phpRound(draft.general.dedication), "[초토화] actor dedication (4120→4135)")
        assertEquals(ag.int("betray"), metaInt(draft.general.meta, "betray"), "[초토화] actor betray (0→1)")
        // actor 본인 gold/rice 불변 (국고로 들어감).
        assertEquals(ag.int("gold"), draft.general.gold, "[초토화] actor gold unchanged")
        assertEquals(ag.int("rice"), draft.general.rice, "[초토화] actor rice unchanged")

        // --- nation: gold/rice += calcReturnAmount, surlimit += 24 (postReqTurn) ---
        val cheChotohwa = def as opensamguk.logic.actions.nation.CheChotohwa
        val amount = cheChotohwa.calcReturnAmount(destCity)
        assertEquals(100000 + amount, draft.nation!!.gold, "[초토화] nation gold += amount")
        assertEquals(100000 + amount, draft.nation!!.rice, "[초토화] nation rice += amount")
        assertEquals(24, metaInt(draft.nation!!.meta, "surlimit"), "[초토화] nation surlimit += postReqTurn(24)")

        // --- dest 도시 공백지화: nation=0, front=0, conflict={}, trust>=50 ---
        val sc = draft.destCity!!
        assertEquals(0, sc.nationId, "[초토화] dest city → neutral")
        assertEquals(0, sc.frontState, "[초토화] dest city front reset")
        assertEquals("{}", sc.conflict, "[초토화] dest city conflict reset")
        // greatest(*_max*0.1, *0.2) 감쇄: pop 200000*0.2=40000 vs 300000*0.1=30000 → 40000.
        assertEquals(maxOf(destCity.populationMax * 0.1, destCity.population * 0.2).toInt(), sc.population,
            "[초토화] dest pop scorched")
        assertEquals(maxOf(destCity.wallMax * 0.1, destCity.wall * 0.5).toInt(), sc.wall,
            "[초토화] dest wall scorched (*0.5)")
    }
}
