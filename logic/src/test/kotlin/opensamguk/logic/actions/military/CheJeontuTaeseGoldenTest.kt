package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.getTechCost
import opensamguk.common.rng.MustNotBeReachedException
import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GOLDEN — che_전투태세 ([CheJeontuTaese]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/General/che_전투태세.php` `run(RandUtil $rng)`(:66-130)는 RNG를 단
 * 한 번도 끌지 않는다(draw COUNT = 0). docker 골든 불필요 — 본 테스트가 골든을 대신한다. 로그/효과/제약은
 * run() 본문에서 verbatim 이식했다.
 *
 * preReqTurn=3 충전형. term 분기(php:81-92):
 *   - lastTurn.command != name      → term=1
 *   - lastTurn.term == 3            → term=1 (직전 완료 → 새 사이클)
 *   - lastTurn.term < 3             → term+1
 * term<3: "병사들을 열심히 훈련중... ({term}/3)" 로그 + 결과턴만, 효과 없음.
 * term==3: "전투태세 완료! (3/3)" + train/atmos LOWER-fit 95 + exp 300/ded 210 + dex(crew/100*3) +
 *          leadership_exp+3 + checkStatChange.
 *
 * 로그는 [GeneralActionResolveContext.addLog] sink(=`<C>●</>{month}월:{body}`)로 검증한다.
 */
class CheJeontuTaeseGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val DATE = "12:34"
    private val env = WorldEnv(year = 200, startYear = 190, develCost = 100)

    /** crew=1000, train=50/atmos=40 (둘 다 95 미만 → 완료 시 95로 LOWER-fit). 레벨 경계 비교차 fixture. */
    private fun general(
        lastTurn: LastTurn = LastTurn(),
        crew: Int = 1000,
        crewTypeId: Int = 1100,         // 보병(T_FOOTMAN) — addDex 0.9 배수 없음
        train: Double = 50.0,
        atmos: Double = 40.0,
    ) = General(
        id = 7, nationId = 1, cityId = 1,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        // explevel/dedlevel: +300/+210 해도 레벨이 그대로인 평탄 구간(getExpLevel(100300)=100, getDedLevel=30 clamp).
        experience = 100000.0, dedication = 100000.0, officerLevel = 5, gold = 100000, rice = 100000,
        crew = crew, train = train, atmos = atmos, crewTypeId = crewTypeId,
        lastTurn = lastTurn,
        meta = linkedMapOf(
            "explevel" to 100, "dedlevel" to 30,
            "leadership_exp" to 0, "strength_exp" to 0, "intel_exp" to 0,
        ),
    )

    private fun city() = City(
        id = 1, nationId = 1, level = 5,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun nation(tech: Double = 5500.0) =
        Nation(id = 1, level = 5, capitalCityId = 1, name = "촉", color = "#00ff00", tech = tech)

    private fun action() = CheJeontuTaese(pipeline)

    private fun context(g: General, rng: RandUtil = RandUtil(NoRng())): GeneralActionResolveContext {
        val draft = GeneralActionDraft(general = g, city = city(), nation = nation())
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = MONTH, date = DATE, generalName = "관우",
        )
    }

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws — charging path`() {
        action().resolve(context(general()))   // NoRng: 한 번이라도 draw하면 MustNotBeReachedException
    }

    @Test
    fun `resolve makes zero rng draws — complete path`() {
        // 직전 term==2 → 이번 term=3 (완료 경로)도 0-draw.
        action().resolve(context(general(lastTurn = LastTurn("전투태세", null, term = 2))))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(general())
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── key / name / category ─────────────────────────────────────────────────────────────────────
    @Test
    fun `key name category`() {
        assertEquals("che_전투태세", action().key)
        assertEquals("전투태세", action().name)
        assertEquals("군사", action().category)
    }

    // ── constraints (php:39-47) ─────────────────────────────────────────────────────────────────────
    @Test
    fun `full constraints — exact 8 in PHP order`() {
        val ctx = ConstraintContext(actorId = 7, cityId = 1, nationId = 1, mode = ConstraintMode.FULL)
        val names = action().buildConstraints(ctx).map { it.name }
        assertEquals(
            listOf(
                "NotBeNeutral", "NotWanderingNation", "OccupiedCity",
                "ReqGeneralCrew", "ReqGeneralGold", "ReqGeneralRice",
                "ReqGeneralTrainMargin", "ReqGeneralAtmosMargin",
            ),
            names, "got $names",
        )
    }

    // ── cost (php:52-56) — round(crew/100*3*techCost), 0 ─────────────────────────────────────────────
    @Test
    fun `cost gold = round(crew over 100 times 3 times techCost), rice 0`() {
        // crew=1000, tech=5500 → techLevel(5500)=floor(5.5)=5 → techCost=1+5*0.15=1.75.
        // gold = round(1000/100*3*1.75) = round(52.5) = 53 (PhpRound half-away).
        val tech = 5500
        assertEquals(1.75, getTechCost(tech), 1e-9)
        assertEquals(53, action().getCostGold(general(), tech))
        assertEquals(phpRound(1000 / 100.0 * 3 * 1.75), action().getCostGold(general(), tech))
    }

    // ── charging (term<3, php:99-105) ──────────────────────────────────────────────────────────────
    @Test
    fun `charging term 1 — log + result turn only, NO effect`() {
        val g0 = general(lastTurn = LastTurn("다른명령"))   // command != name → term=1
        val ctx = context(g0)
        action().resolve(ctx)

        // 로그 byte: month-prefix + "병사들을 열심히 훈련중... (1/3) <1>date</>"
        assertEquals(
            listOf("<C>●</>3월:병사들을 열심히 훈련중... (1/3) <1>12:34</>"),
            ctx.logs(),
        )
        // 효과 없음 — train/atmos/exp/ded/leadership_exp 그대로.
        val g = ctx.draft.general
        assertEquals(50.0, g.train, "train 미변경")
        assertEquals(40.0, g.atmos, "atmos 미변경")
        assertEquals(100000.0, g.experience, "exp 미변경")
        assertEquals(100000.0, g.dedication, "ded 미변경")
        assertEquals(0.0, metaDouble(g.meta, "leadership_exp"), "leadership_exp 미변경")
        // 결과턴 LastTurn(name, null, term=1)
        assertEquals("전투태세", g.lastTurn.command)
        assertEquals(1, g.lastTurn.term)
    }

    @Test
    fun `charging term 2 — increments from a prior term 1`() {
        val g0 = general(lastTurn = LastTurn("전투태세", null, term = 1))
        val ctx = context(g0)
        action().resolve(ctx)
        assertEquals(
            listOf("<C>●</>3월:병사들을 열심히 훈련중... (2/3) <1>12:34</>"),
            ctx.logs(),
        )
        assertEquals(2, ctx.draft.general.lastTurn.term)
        assertEquals(50.0, ctx.draft.general.train, "충전 중엔 효과 없음")
    }

    // ── complete (term==3, php:107-129) ─────────────────────────────────────────────────────────────
    @Test
    fun `complete term 3 — train atmos floored to 95, exp 300 ded 210, dex, leadership_exp plus 3`() {
        val g0 = general(lastTurn = LastTurn("전투태세", null, term = 2))   // term<3 → +1 = 3
        val ctx = context(g0)
        action().resolve(ctx)

        // 완료 로그(레벨/스탯 변동 없는 fixture라 PLAIN 로그 없음 → addLog 1건만)
        assertEquals(
            listOf("<C>●</>3월:전투태세 완료! (3/3) <1>12:34</>"),
            ctx.logs(),
        )
        // increaseVarWithLimit('train',0,max-5)=LOWER-fit 95, ('atmos',0,max-5)=LOWER-fit 95.
        val g = ctx.draft.general
        assertEquals((GameConst.maxTrainByCommand - 5).toDouble(), g.train, "train → 95")
        assertEquals((GameConst.maxAtmosByCommand - 5).toDouble(), g.atmos, "atmos → 95")
        assertEquals(95.0, g.train)
        assertEquals(95.0, g.atmos)
        // exp += 100*3, ded += 70*3 (identity pipeline, 레벨 비교차).
        assertEquals(100000.0 + 300.0, g.experience, 1e-9, "exp += 300")
        assertEquals(100000.0 + 210.0, g.dedication, 1e-9, "ded += 210")
        // addDex(crewTypeObj, crew/100*3, false): 보병(T_FOOTMAN=1) → dex1 += 30.0 (0.9 배수 없음).
        assertEquals(1000 / 100.0 * 3, metaDouble(g.meta, "dex1"), 1e-9, "dex1 += crew/100*3")
        // leadership_exp += 3.
        assertEquals(3.0, metaDouble(g.meta, "leadership_exp"), "leadership_exp += 3")
        // 결과턴 LastTurn(name, null, term=3).
        assertEquals("전투태세", g.lastTurn.command)
        assertEquals(3, g.lastTurn.term)
    }

    @Test
    fun `complete does NOT lower train atmos already above 95`() {
        // train=98/atmos=99 (둘 다 95 초과) → increaseVarWithLimit value=0 min=95 → 변경 없음(깎이지 않음).
        val g0 = general(lastTurn = LastTurn("전투태세", null, term = 2), train = 98.0, atmos = 99.0)
        val ctx = context(g0)
        action().resolve(ctx)
        assertEquals(98.0, ctx.draft.general.train, "95 초과 train 유지(깎이지 않음)")
        assertEquals(99.0, ctx.draft.general.atmos, "95 초과 atmos 유지(깎이지 않음)")
    }

    // ── cycle restart: 직전 term==3(완료) → 다시 term=1 (php:84-86) ─────────────────────────────────────
    @Test
    fun `cycle restart — prior term equals reqTurn resets to term 1 charging`() {
        val g0 = general(lastTurn = LastTurn("전투태세", null, term = 3))
        val ctx = context(g0)
        action().resolve(ctx)
        assertEquals(
            listOf("<C>●</>3월:병사들을 열심히 훈련중... (1/3) <1>12:34</>"),
            ctx.logs(),
        )
        assertEquals(1, ctx.draft.general.lastTurn.term)
        assertEquals(50.0, ctx.draft.general.train, "재시작 1턴차 — 효과 없음")
    }

    // ── determinism ─────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `determinism — complete path`() {
        val a = context(general(lastTurn = LastTurn("전투태세", null, term = 2)))
        action().resolve(a)
        val b = context(general(lastTurn = LastTurn("전투태세", null, term = 2)))
        action().resolve(b)
        assertEquals(a.draft.general.train, b.draft.general.train)
        assertEquals(a.draft.general.atmos, b.draft.general.atmos)
        assertEquals(a.draft.general.experience, b.draft.general.experience)
        assertEquals(metaDouble(a.draft.general.meta, "dex1"), metaDouble(b.draft.general.meta, "dex1"))
        assertEquals(a.logs(), b.logs())
    }
}
