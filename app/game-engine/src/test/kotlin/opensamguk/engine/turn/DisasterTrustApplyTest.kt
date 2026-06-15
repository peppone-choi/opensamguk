package opensamguk.engine.turn

import opensamguk.engine.world.WorldActionContext
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.DisasterCityEffect
import opensamguk.logic.world.RaiseDisasterResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WorldActionContext.applyDisaster — trust(FLOAT) 곱 적용 회귀 가드.
 *
 * 버그(#2/#11): RaiseDisaster 가 city.trust 를 한 번도 곱하지 않았다. PHP 는 trust 를 pop·agri·comm·secu·def·wall
 * 과 SAME city-update 에서 같은 affectRatio 로 곱한다(`legacy/devsam-core/hwe/sammo/Event/Action/RaiseDisaster.php`,
 * GameConstBase.php 가 4회 참조하는 실제 발화 leaf):
 *   - 재난(bad)   RaiseDisaster.php:122-135 → affectRatio = 0.8 + valueFit(secu/secu_max/0.8,0,1)*0.15,
 *                 `'trust' => $db->sqleval('trust * %d', $affectRatio)`  ← UNCAPPED, least() 없음.
 *   - 호황(good)  RaiseDisaster.php:147-160 → affectRatio = 1.01 + valueFit(...)*0.04,
 *                 `'trust' => $db->sqleval('least(trust * %d, 100)', $affectRatio)` ← 리터럴 100 캡(trust_max 아님).
 * PHP 는 trust 에 round() 를 걸지 않는 생 float 곱이므로 phpRound 미경유(생 Double 곱)여야 한다.
 *
 * 엔진 City 는 trust 전용 컬럼이 없고 meta["trust"](Double, PerTurnOverlay.toLogicCity)에 보관한다.
 * 그래서 in-memory 갱신은 meta 에, diffCity 는 LogicCity.trust 에 동일 값으로 기록되어야 한다.
 */
class DisasterTrustApplyTest {

    private val t0 = Instant.parse("0190-07-01T08:30:00Z")

    private fun worldWith(trust: Number, secu: Int = 900, secuMax: Int = 1000) = InMemoryTurnWorld(
        WorldSnapshot(
            TurnWorldState(id = 1, currentYear = 190, currentMonth = 7, tickSeconds = 3600, lastTurnTime = t0),
            generals = emptyList(),
            cities = listOf(
                City(
                    id = 5, name = "성도", nationId = 1, level = 5,
                    commerce = 100, commerceMax = 100, agriculture = 100, agricultureMax = 100,
                    security = secu, securityMax = secuMax, defence = 900, defenceMax = 1000,
                    wall = 900, wallMax = 1000, population = 50_000, populationMax = 100_000,
                    supplyState = 1, frontState = 0,
                    meta = linkedMapOf("trust" to trust),
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0a0", level = 2, capitalCityId = 5)),
        ),
    )

    private fun ctx(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        WorldActionContext(
            env = mutableMapOf<String, Any?>(),
            world = world,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
        )

    // ── 재난(bad, capped=false): trust * affectRatio, 무캡 ──────────────────────────────────────────
    @Test
    fun `재난 branch multiplies trust by affectRatio uncapped`() {
        val world = worldWith(trust = 80.0)
        val recorder = ChangeRecorder()
        val affectRatio = 0.9 // 재난 범위(0.8~0.95) 내 임의값
        ctx(world, recorder).applyDisaster(
            RaiseDisasterResult(
                stateResets = emptyMap(),
                skippedByYearGate = false,
                isGood = false,
                targetCityIds = listOf(5),
                logLine = null,
                stateCode = 4,
                effects = listOf(DisasterCityEffect(cityId = 5, stateCode = 4, affectRatio = affectRatio, capped = false)),
            ),
        )
        // 생 float 곱: 80.0 * 0.9 = 72.0 (round 없음).
        val expected = 80.0 * affectRatio
        val after = metaDouble(world.getCityById(5)!!.meta, "trust")
        assertEquals(expected, after, 1e-9, "재난: trust 는 trust*affectRatio 무캡 곱이어야 한다")
        // diffCity 가 trust 변화를 기록했는지(=영속 경로) 확인.
        assertTrue(recorder.dirtyCityIds().contains(5), "trust 변화가 ChangeRecorder 에 기록되어야 한다")
    }

    // ── 호황(good, capped=true): least(trust * affectRatio, 100) ──────────────────────────────────
    @Test
    fun `호황 branch caps trust at 100 not trust_max`() {
        // trust 99 * 1.05 = 103.95 > 100 → 리터럴 100 으로 클램프(trust_max 가 아님).
        val world = worldWith(trust = 99.0)
        val recorder = ChangeRecorder()
        ctx(world, recorder).applyDisaster(
            RaiseDisasterResult(
                stateResets = emptyMap(),
                skippedByYearGate = false,
                isGood = true,
                targetCityIds = listOf(5),
                logLine = null,
                stateCode = 2,
                effects = listOf(DisasterCityEffect(cityId = 5, stateCode = 2, affectRatio = 1.05, capped = true)),
            ),
        )
        val after = metaDouble(world.getCityById(5)!!.meta, "trust")
        assertEquals(100.0, after, 1e-9, "호황: least(trust*ratio, 100) — 리터럴 100 캡")
    }

    @Test
    fun `호황 branch below cap keeps raw float product`() {
        // trust 50 * 1.02 = 51.0 < 100 → 캡 미발동, 생 float 곱 유지.
        val world = worldWith(trust = 50.0)
        val recorder = ChangeRecorder()
        ctx(world, recorder).applyDisaster(
            RaiseDisasterResult(
                stateResets = emptyMap(),
                skippedByYearGate = false,
                isGood = true,
                targetCityIds = listOf(5),
                logLine = null,
                stateCode = 2,
                effects = listOf(DisasterCityEffect(cityId = 5, stateCode = 2, affectRatio = 1.02, capped = true)),
            ),
        )
        val after = metaDouble(world.getCityById(5)!!.meta, "trust")
        assertEquals(50.0 * 1.02, after, 1e-9, "호황: 100 미만이면 least() 가 trust*ratio 그대로 유지")
    }
}
