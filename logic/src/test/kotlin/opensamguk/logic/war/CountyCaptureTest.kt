package opensamguk.logic.war

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CountyCaptureTest {
    private fun county(owner: Int = 1, population: Int = 1_000, garrison: Int = 300) =
        CountyCapture.CountyBefore(
            countyId = 42, ownerNationId = owner, population = population, garrisonTroops = garrison,
        )

    @Test
    fun `縣治 가 함락되면 縣 전체가 점령자에게 넘어간다`() {
        val out = CountyCapture.settle(county(owner = 1), captorNationId = 2)
        assertEquals(42, out.countyId)
        assertEquals(2, out.ownerNationId)
    }

    @Test
    fun `수비대는 무장 해제되어 동수가 현지 인구가 된다`() {
        val out = CountyCapture.settle(county(population = 1_000, garrison = 300), captorNationId = 2)
        assertEquals(1_300, out.population, "수비병 300 이 인구로 돌아갔다")
        assertEquals(0, out.garrisonTroops, "무장 해제 뒤 수비대는 남지 않는다")
        assertEquals(300, out.disarmedToCivilians)
    }

    @Test
    fun `수비대는 승자 군대에 편입되지 않는다`() {
        // 편입이 없다는 것은 결과가 병력을 만들지 않는다는 뜻이다 — 인구로만 돌아간다.
        val before = county(population = 500, garrison = 200)
        val out = CountyCapture.settle(before, captorNationId = 7)
        assertEquals(before.population + before.garrisonTroops, out.population)
        assertEquals(0, out.garrisonTroops)
    }

    @Test
    fun `중립 縣 도 점령할 수 있다`() {
        val out = CountyCapture.settle(county(owner = 0), captorNationId = 3)
        assertEquals(3, out.ownerNationId)
    }

    @Test
    fun `인구와 재고는 줄지 않는다 - 평화 항복 기준`() {
        val out = CountyCapture.settle(county(population = 1_000, garrison = 0), captorNationId = 2)
        assertEquals(1_000, out.population, "손실 없음")
    }

    @Test
    fun `이미 소유한 縣 을 점령하려 하면 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            CountyCapture.settle(county(owner = 5), captorNationId = 5)
        }
    }

    @Test
    fun `음수 입력은 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            CountyCapture.settle(county(population = -1), captorNationId = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            CountyCapture.settle(county(garrison = -1), captorNationId = 2)
        }
    }
}
