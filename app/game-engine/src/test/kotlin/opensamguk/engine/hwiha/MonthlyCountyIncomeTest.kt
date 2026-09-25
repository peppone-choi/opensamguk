package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.economy.Resources

class HwihaMonthlyCountyIncomeTest {
    private fun county(
        id: Int, nationId: Int = 1, population: Int = 1_000, supplyState: Int = 1,
        warehouse: CountyWarehouse? = CountyWarehouse(id, 0, Resources()),
    ) = City(
        id = id, name = "縣$id", nationId = nationId, level = 1, population = population,
        populationMax = population, agriculture = 100, agricultureMax = 100,
        commerce = 100, commerceMax = 100, supplyState = supplyState,
        meta = buildMap {
            put("keep", "preserved")
            if (warehouse != null) put(CountyWarehouse.META_KEY, warehouse.toMetaValue())
        },
    )

    private fun world(vararg cities: City, profile: String = "HWIHA"): InMemoryTurnWorld {
        val hash = "b".repeat(64)
        val provinces = cities.associate { it.id to "p${'$'}{it.id}" }
        return InMemoryTurnWorld(
            WorldSnapshot(
                worldId = WorldId(1),
                state = TurnWorldState(
                    1, 200, 12, 3600, Instant.EPOCH,
                    config = mapOf("ruleProfile" to profile, "mapName" to "han-world-v3"),
                ),
                generals = emptyList(), nations = listOf(Nation(1, "N1", "#000")),
                cities = cities.toList(), administrativeCountyIds = cities.map { it.id }.toSet(),
                generalPositionSnapshot = GeneralPositionSnapshot(
                    "r1", hash, provinces.values.toSet(), emptySet()
                ),
                cityLandProvinceById = provinces,
            )
        )
    }

    private fun stored(world: InMemoryTurnWorld, id: Int) =
        CountyWarehouse.read(world.getCityById(id)!!.meta, id)

    @Test
    fun `첫 월 경계가 소유되고 보급된 縣 창고에 세입을 넣는다`() {
        val world = world(county(10))
        val recorder = ChangeRecorder()
        val outcome = assertNotNull(HwihaMonthlyCountyIncome(world, recorder, emptyMap()).credit(200, 3))

        assertFalse(outcome.alreadyStamped)
        assertEquals("0200-03", outcome.stamp)
        assertEquals(1, outcome.creditedCounties)
        // 인구 1,000 → 200 호. 완전개발이라 전 200*20 · 곡 200*200.
        assertEquals(Resources(money = 4_000, grain = 40_000), outcome.total)
        val after = assertNotNull(stored(world, 10))
        assertEquals(Resources(money = 4_000, grain = 40_000), after.stock)
        assertEquals(1, after.revision, "적립은 revision 을 올린다")
        assertEquals("preserved", world.getCityById(10)!!.meta["keep"], "다른 meta 는 보존한다")
    }

    @Test
    fun `같은 달 재실행은 도장에 막혀 아무것도 바꾸지 않는다`() {
        val world = world(county(10))
        HwihaMonthlyCountyIncome(world, ChangeRecorder(), emptyMap()).credit(200, 3)
        val snapshot = world.getCityById(10)!!

        val recorder = ChangeRecorder()
        val again = assertNotNull(HwihaMonthlyCountyIncome(world, recorder, emptyMap()).credit(200, 3))

        assertTrue(again.alreadyStamped)
        assertEquals(0, again.creditedCounties)
        assertEquals(snapshot, world.getCityById(10)!!)
        assertTrue(recorder.kvDirty().isEmpty(), "막힌 실행은 kv 도 쓰지 않는다")
    }

    @Test
    fun `다음 달은 다시 넣는다`() {
        val world = world(county(10))
        HwihaMonthlyCountyIncome(world, ChangeRecorder(), emptyMap()).credit(200, 3)
        val next = assertNotNull(HwihaMonthlyCountyIncome(world, ChangeRecorder(), emptyMap()).credit(200, 4))

        assertFalse(next.alreadyStamped)
        assertEquals(1, next.creditedCounties)
        val after = assertNotNull(stored(world, 10))
        assertEquals(Resources(money = 8_000, grain = 80_000), after.stock)
        assertEquals(2, after.revision)
    }

    @Test
    fun `창고 없는 縣 은 손대지 않는다`() {
        val world = world(county(10, warehouse = null))
        val before = world.getCityById(10)!!
        val outcome = assertNotNull(HwihaMonthlyCountyIncome(world, ChangeRecorder(), emptyMap()).credit(200, 3))

        assertEquals(0, outcome.creditedCounties)
        assertEquals(1, outcome.skippedNoWarehouse)
        assertEquals(before, world.getCityById(10)!!)
    }

    @Test
    fun `무주 縣 과 보급이 끊긴 縣 은 생산하지 않는다`() {
        val world = world(county(10, nationId = 0), county(11, supplyState = 0))
        val before10 = world.getCityById(10)!!
        val before11 = world.getCityById(11)!!
        val outcome = assertNotNull(HwihaMonthlyCountyIncome(world, ChangeRecorder(), emptyMap()).credit(200, 3))

        assertEquals(0, outcome.creditedCounties)
        assertEquals(Resources(), outcome.total)
        assertEquals(before10, world.getCityById(10)!!)
        assertEquals(before11, world.getCityById(11)!!)
    }

    @Test
    fun `HWIHA 가 아니면 아무것도 하지 않는다`() {
        val world = world(county(10), profile = "SAMMO")
        val before = world.getCityById(10)!!
        val recorder = ChangeRecorder()

        assertNull(HwihaMonthlyCountyIncome(world, recorder, emptyMap()).credit(200, 3))
        assertEquals(before, world.getCityById(10)!!)
        assertTrue(recorder.kvDirty().isEmpty())
        assertNull(world.getState().meta[HwihaMonthlyCountyIncome.STAMP_KEY])
    }

    @Test
    fun `산지 표가 철 목재 말을 전 곡 위에 더한다`() {
        val world = world(county(10))
        val production = mapOf(10 to Resources(iron = 1_000, timber = 118, horses = 100))
        val outcome = assertNotNull(
            HwihaMonthlyCountyIncome(world, ChangeRecorder(), production).credit(200, 3)
        )

        assertEquals(
            Resources(money = 4_000, grain = 40_000, iron = 1_000, timber = 118, horses = 100),
            outcome.total,
        )
        assertEquals(outcome.total, assertNotNull(stored(world, 10)).stock)
    }

    @Test
    fun `표에 없는 縣 은 전 곡만 받는다`() {
        val world = world(county(10), county(11))
        val production = mapOf(10 to Resources(iron = 7))
        HwihaMonthlyCountyIncome(world, ChangeRecorder(), production).credit(200, 3)

        assertEquals(Resources(money = 4_000, grain = 40_000, iron = 7), assertNotNull(stored(world, 10)).stock)
        assertEquals(Resources(money = 4_000, grain = 40_000), assertNotNull(stored(world, 11)).stock)
    }

    @Test
    fun `보급이 끊긴 縣 은 산지 생산도 받지 않는다`() {
        val world = world(county(10, supplyState = 0))
        val before = world.getCityById(10)!!
        val outcome = assertNotNull(
            HwihaMonthlyCountyIncome(world, ChangeRecorder(), mapOf(10 to Resources(iron = 7)))
                .credit(200, 3)
        )

        assertEquals(0, outcome.creditedCounties)
        assertEquals(before, world.getCityById(10)!!)
    }

    @Test
    fun `기본 표는 생성된 런타임 산출물이다`() {
        // 표를 넘기지 않으면 커밋된 산출물을 쓴다 — 런타임이 수치를 추정하지 않는다.
        val world = world(county(35))
        val outcome = assertNotNull(HwihaMonthlyCountyIncome(world, ChangeRecorder()).credit(200, 3))
        val produced = assertNotNull(stored(world, 35)).stock
        assertEquals(1_000, produced.iron, "35 縣은 사료 철 산지다")
        assertTrue(produced.timber > 0, "목재는 분산이라 거의 모든 縣에서 난다")
        assertEquals(Resources(money = 4_000, grain = 40_000, iron = 1_000, timber = produced.timber),
            outcome.total)
    }

    @Test
    fun `도장은 메모리와 kv 채널 양쪽에 남는다`() {
        val world = world(county(10))
        val recorder = ChangeRecorder()
        HwihaMonthlyCountyIncome(world, recorder, emptyMap()).credit(200, 3)

        assertEquals("0200-03", world.getState().meta[HwihaMonthlyCountyIncome.STAMP_KEY])
        val stamped = recorder.kvDirty().entries.single { it.key.key == HwihaMonthlyCountyIncome.STAMP_KEY }
        assertEquals("0200-03", stamped.value)
    }
}
