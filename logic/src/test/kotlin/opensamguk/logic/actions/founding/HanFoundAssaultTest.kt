package opensamguk.logic.actions.founding

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.constraints.constructableCity
import opensamguk.logic.constraints.recruitableCity
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.actions.military.RecruitAlgorithm
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.FOUND_ASSAULT_RATIO
import opensamguk.logic.world.foundAssaultCrewCost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * han 전용 건국 수비병 돌파 — **패러티 아님 · 게임 밸런스 divergence**.
 *
 * PHP devsam/core 의 건국에는 전투가 없다(공백지에 서 있기만 하면 나라가 선다). han 맵에서만 공백지의
 * 수비병(city.def)을 `ceil(def * [FOUND_ASSAULT_RATIO])` 병력으로 뚫어야 건국이 성사되며, 성사되면
 * 그만큼 crew 를 잃고 그 城의 def 가 0 이 된다. che·miniche 는 비용이 항상 0 이라 판정·차감·def 초기화가
 * 전부 사라진다 — 이 파일의 마지막 두 테스트가 그 무변을 못 박는다.
 */
class HanFoundAssaultTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 7
    private val NATION_ID = 7
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    private fun lord(crew: Int) = General(
        id = 42, nationId = NATION_ID, cityId = 5,
        leadership = 80, strength = 70, intel = 75, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 100, rice = 100,
        npcType = 0, crew = crew,
        meta = linkedMapOf("name" to "유비", "explevel" to 10, "dedlevel" to 4, "officer_city" to 5),
    )

    private fun wanderingNation() = Nation(
        id = NATION_ID, level = 0, capitalCityId = 0,
        name = "유비", color = "#330000", typeCode = "che_중립",
        gold = 0, rice = 2000, gennum = 2,
        meta = linkedMapOf("gennum" to 2, "aux" to linkedMapOf<String, Any?>()),
    )

    /** 공백지 縣 (han 영현 등급 10) with `def = 1500` — CityConst.hanBuildInit 의 영현 초기 수비. */
    private fun neutralCity(defense: Int = 1500, level: Int = 10) = City(
        id = 5, nationId = 0, level = level,
        commerce = 100, commerceMax = 100, agriculture = 100, agricultureMax = 100,
        supplyState = 1, frontState = 0, trust = 50.0,
        defense = defense, defenseMax = defense,
    )

    private fun view(g: General, c: City) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = mapOf(c.id to c),
        nations = mapOf(NATION_ID to wanderingNation()),
        env = emptyMap(),
    )

    private fun ctx(mapName: String) = ConstraintContext(
        actorId = 42, cityId = 5, nationId = NATION_ID,
        env = mapOf("mapName" to mapName), mode = ConstraintMode.FULL,
    )

    private fun resolveCtx(draft: GeneralActionDraft, mapName: String) = GeneralActionResolveContext(
        draft,
        RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "generalCommand", 190, MONTH, 42, "che_건국"))),
        env, MONTH, "08:30",
        args = linkedMapOf(
            "nationName" to "촉", "nationType" to "che_명사", "colorType" to 5, "mapName" to mapName,
        ),
        generalName = "유비",
    )

    private fun deny(r: ConstraintResult): String {
        assertTrue(r is ConstraintResult.Deny, "expected Deny but was $r")
        return (r as ConstraintResult.Deny).reason
    }

    @Test
    fun `foundAssaultCrewCost is ceil of def times the ratio on han and zero elsewhere`() {
        assertEquals(3000, foundAssaultCrewCost("han", 1500))
        assertEquals(2002, foundAssaultCrewCost("han", 1001), "ceil, not round")
        assertEquals(0, foundAssaultCrewCost("han", 0))
        assertEquals(0, foundAssaultCrewCost("che", 1500))
        assertEquals(0, foundAssaultCrewCost("miniche", 3000))
        assertEquals(0, foundAssaultCrewCost(null, 3000))
    }

    @Test
    fun `han denies founding when the crew cannot break the garrison`() {
        val g = lord(crew = 2999)
        val c = neutralCity()
        assertEquals(
            "수비병을 뚫을 병력이 부족합니다. (필요 3000명)",
            deny(constructableCity().test(ctx("han"), view(g, c))),
        )
    }

    @Test
    fun `han allows founding when the crew covers the garrison`() {
        val g = lord(crew = 3000)
        val result = constructableCity().test(ctx("han"), view(g, neutralCity()))
        assertTrue(result is ConstraintResult.Allow, "expected Allow but was $result")
    }

    @Test
    fun `han founding spends the assault crew and wipes the garrison`() {
        val draft = GeneralActionDraft(lord(crew = 5000), neutralCity(), wanderingNation())
        val command = CheGeonguk(pipeline)
        command.resolve(resolveCtx(draft, "han"))

        assertEquals(3000, command.lastAssaultCrewCost)
        assertEquals(2000, draft.general.crew, "5000 - ceil(1500 * 2.0)")
        assertEquals(0, draft.city.defense, "수비병 전멸")
        assertEquals(NATION_ID, draft.city.nationId, "건국은 성사된다")
        assertEquals(1, draft.nation!!.level)
    }

    // --- che 무변 (패러티 골든 보호) ---

    @Test
    fun `che founding is untouched by the assault rule`() {
        val draft = GeneralActionDraft(lord(crew = 100), neutralCity(defense = 2000, level = 5), wanderingNation())
        val command = CheGeonguk(pipeline)
        command.resolve(resolveCtx(draft, "che"))

        assertEquals(0, command.lastAssaultCrewCost)
        assertEquals(100, draft.general.crew, "che 는 crew 차감 없음")
        assertEquals(2000, draft.city.defense, "che 는 def 무변")
        assertEquals(NATION_ID, draft.city.nationId)
    }

    // --- 방랑군 징병 (건국 데드락 해소) ---

    private fun viewWithNation(g: General, c: City, n: Nation) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = mapOf(c.id to c),
        nations = mapOf(n.id to n),
        env = emptyMap(),
    )

    @Test
    fun `han lets a wandering-nation general recruit on a neutral city`() {
        val g = lord(crew = 0)
        val result = recruitableCity().test(ctx("han"), viewWithNation(g, neutralCity(), wanderingNation()))
        assertTrue(result is ConstraintResult.Allow, "expected Allow but was $result")
    }

    @Test
    fun `che still denies a wandering-nation general on a neutral city`() {
        val g = lord(crew = 0)
        assertEquals(
            "아국이 아닙니다.",
            deny(recruitableCity().test(ctx("che"), viewWithNation(g, neutralCity(level = 5), wanderingNation()))),
            "che 는 완화 분기가 죽어 OccupiedCity 와 동일하게 막힌다",
        )
    }

    @Test
    fun `han still denies a settled nation general on a neutral city`() {
        val g = lord(crew = 0)
        val settled = wanderingNation().copy(level = 3)
        assertEquals(
            "아국이 아닙니다.",
            deny(recruitableCity().test(ctx("han"), viewWithNation(g, neutralCity(), settled))),
            "완화 대상은 방랑군(level 0)뿐이다",
        )
    }

    @Test
    fun `han still denies a 재야 general recruiting on a neutral city`() {
        val wildGeneral = lord(crew = 0).copy(nationId = 0)
        val view = viewWithNation(wildGeneral, neutralCity(), wanderingNation())
        val ctxWild = ConstraintContext(
            actorId = 42, cityId = 5, nationId = 0,
            env = mapOf("mapName" to "han"), mode = ConstraintMode.FULL,
        )
        val denies = RecruitAlgorithm.cheJingbyeong(pipeline).buildMinConstraints(ctxWild)
            .map { it.test(ctxWild, view) }
            .filterIsInstance<ConstraintResult.Deny>()
            .map { it.reason }
        assertTrue("재야입니다." in denies, "재야는 NotBeNeutral 로 계속 막힌다: $denies")
    }

    @Test
    fun `che allows founding with zero crew against a garrisoned neutral city`() {
        val result = constructableCity().test(ctx("che"), view(lord(crew = 0), neutralCity(defense = 2000, level = 5)))
        assertTrue(result is ConstraintResult.Allow, "expected Allow but was $result")
    }
}
