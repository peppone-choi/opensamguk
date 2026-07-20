package opensamguk.engine.turn

import opensamguk.engine.world.WorldEventContextFactory
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.EventTarget
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 월간 world-event 디스패치 seam — prod 턴 동결(매 월경계
 * `IllegalArgumentException: UpdateCitySupply requires UpdateCitySupplyContext` 크래시-루프)의 회귀 가드.
 *
 * 엔진 월간 정산(MonthlyPipeline → [EventDispatcher])은 P6/#9 이래로 bare `Context(env)`만 넘겨,
 * cast-ctx leaf(UpdateCitySupply/ProcessIncome/ProcessWarIncome/…)가 `ctx as? XContext`에서 throw하거나
 * env-read leaf(RaiseDisaster/특기/유산)가 무음 no-op했다. P3 게이트는 leaf를 test-double로 직접 테스트해
 * 이 엔진 어댑터 경로를 한 번도 밟지 않았다(`WorldActionContext`는 prod 코드에서 생성된 적이 없었다).
 *
 * 이 테스트는 [WorldEventContextFactory]가 만든 ctx로 **실제 [EventStore.withDefaults] 기본 이벤트**
 * (정사 `GameConstBase.php`: pre_month = UpdateCitySupply + ProcessWarIncome)를 디스패치해 **throw 없음**을
 * 확인하고, 반대로 bare Context(팩토리 없음)로는 UpdateCitySupply가 throw함을 확인한다(= 팩토리가 필수).
 */
class MonthlyWorldEventSeamTest {

    private val pipeline = GeneralActionPipeline()
    private val hiddenSeed = "00000000000000000000000000000000"
    private val startYear = 184
    private val t0 = Instant.parse("0190-07-01T08:30:00Z")

    private fun world() = InMemoryTurnWorld(
        WorldSnapshot(
            TurnWorldState(id = 1, currentYear = 190, currentMonth = 7, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(
                TurnGeneral(
                    id = 42, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(leadership = 80, strength = 70, intelligence = 75),
                    experience = 0, dedication = 0, officerLevel = 1, gold = 100, rice = 100,
                    injury = 0, npcState = 0, turnTime = t0,
                    meta = linkedMapOf("name" to "유비", "officer_city" to 5),
                ),
            ),
            cities = listOf(
                City(
                    id = 5, name = "성도", nationId = 1, level = 5, commerce = 100, commerceMax = 100,
                    agriculture = 100, agricultureMax = 100, supplyState = 1, frontState = 0,
                    // 경제 max를 비-0으로 — ProcessSemiAnnual(반년 1·7월 발화)의
                    // `secu/secuMax/10` 나눗셈이 max=0이면 NaN → phpRound(NaN)이 throw한다.
                    // prod scenario_1010은 전 도시 max>0(min secu_max=2000)이라 실제로는 안 터지지만,
                    // 이 seam 테스트가 MONTH 전 leaf를 end-to-end로 밟도록 현실값을 준다.
                    security = 900, securityMax = 1000,
                    defence = 900, defenceMax = 1000,
                    wall = 900, wallMax = 1000,
                    population = 50_000, populationMax = 100_000,
                    meta = linkedMapOf("trust" to 50),
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0a0", level = 2, capitalCityId = 5)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 190, currentMonth = 7, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    private fun dispatcher() =
        EventDispatcher(EventStore.withDefaults(), WorldActions.register(EventActionFactory()))

    private fun factoryFor(world: InMemoryTurnWorld): (MutableMap<String, Any?>) -> EventActionContext =
        WorldEventContextFactory.create(
            world = world,
            recorder = ChangeRecorder(),
            pipeline = pipeline,
            hiddenSeed = hiddenSeed,
            startYear = startYear,
            mapName = "che",
            eventStore = EventStore.withDefaults(),
        )

    @Test
    fun `pre_month UpdateCitySupply dispatch does NOT throw (the prod crash regression)`() {
        val w = world()
        // 크래시 가드 — 예외 없이 완주해야 한다(was UpdateCitySupply requires UpdateCitySupplyContext, 매 틱).
        dispatcher().run(
            EventTarget.PRE_MONTH,
            contextFactory = factoryFor(w),
            envSupplier = { mutableMapOf<String, Any?>("year" to 190, "month" to 7) },
        )
    }

    @Test
    fun `month dispatch does NOT throw — DateRelative reads lowercase startyear (the 2nd prod crash)`() {
        // 2차 prod 크래시 회귀 가드: withDefaults()의 MONTH 이벤트는 전부 DateRelative 조건이고
        // (EventStore.kt:165/173/181/189/197/215…), DateRelative.eval은 env["startyear"](소문자)를 요구한다.
        // 팩토리가 camelCase "startYear"만 심던 시절 → `IllegalArgumentException: env에 startyear가 없습니다.`로
        // 매 월경계(MonthlyPipeline:119 = dispatcher.run(EventTarget.MONTH, …)) 크래시-루프 → 턴 0진행.
        // year/month만 주는 supplier로 MONTH를 디스패치해 EventCondition 평가가 throw 없이 완주해야 한다.
        val w = world()
        dispatcher().run(
            EventTarget.MONTH,
            contextFactory = factoryFor(w),
            envSupplier = { mutableMapOf<String, Any?>("year" to 190, "month" to 7) },
        )
    }

    @Test
    fun `factory seeds BOTH startyear casings (lowercase canonical + camelCase compat)`() {
        // 팩토리가 두 케이싱을 모두 심는지 직접 검증 — 소문자(EventCondition)와 camelCase(event-action leaf)
        // 양쪽 리더를 동시에 만족시켜야 한다.
        val env = mutableMapOf<String, Any?>("year" to 190, "month" to 7)
        factoryFor(world())(env)
        assertEquals(startYear, (env["startyear"] as Number).toInt(), "소문자 startyear (PHP 정본)")
        assertEquals(startYear, (env["startYear"] as Number).toInt(), "camelCase startYear (관행)")
    }

    @Test
    fun `bare-context default makes UpdateCitySupply throw (proves the factory is required)`() {
        // worldContextFactory 없이(기본 bare Context) PRE_MONTH 디스패치 → UpdateCitySupply의 `ctx as?
        // UpdateCitySupplyContext ?: throw`가 발화. 이게 prod에서 났던 정확한 크래시 — 팩토리가 필수임을 증명.
        var threw = false
        try {
            dispatcher().run(EventTarget.PRE_MONTH) { mutableMapOf("year" to 190, "month" to 7) }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(
            threw,
            "bare Context로는 UpdateCitySupply가 throw해야 한다 — WorldEventContextFactory가 없으면 prod가 크래시",
        )
    }
}
