package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import opensamguk.logic.economy.Resources

class CountyProductionJsonTest {
    private fun document(rows: String, version: Int = 1) =
        """{"schemaVersion":$version,"counties":[$rows]}"""

    @Test
    fun `committed runtime table matches the generated ledger totals`() {
        val table = CountyProductionJson.table()
        val ledger = ObjectMapper().readTree(Path.of("../data/curated/han/hwiha-resource-production-v1.json").toFile())
        assertEquals(ledger.path("counties").map { it.path("countyId").asInt() }.toSet(), table.keys)
        assertEquals(34_000, table.values.sumOf { it.iron })
        assertEquals(600, table.values.sumOf { it.horses })
        assertEquals(197_460, table.values.sumOf { it.timber })
        assertTrue(table.values.all { it.money == 0L && it.grain == 0L },
            "전·곡은 식이 만든다 — 이 표에 들어오면 이중 계산이다")
        assertTrue(table.keys.all { it > 0 })
    }

    @Test
    fun `목재는 분산이고 철 말은 희소하다`() {
        val table = CountyProductionJson.table()
        assertTrue(table.count { it.value.timber > 0 } > 1_000, "목재는 거의 모든 縣에서 난다")
        assertEquals(34, table.count { it.value.iron > 0 })
        assertEquals(6, table.count { it.value.horses > 0 })
    }

    @Test
    fun `자원 없는 리소스는 빈 표다`() {
        assertEquals(emptyMap(), CountyProductionJson.load("hwiha/not-a-real-resource.json"))
    }

    @Test
    fun `세 자원만 받는다`() {
        val parsed = CountyProductionJson.parse(
            document("""{"countyId":7,"monthly":{"iron":1,"timber":2,"horses":3}}""")
        )
        assertEquals(mapOf(7 to Resources(iron = 1, timber = 2, horses = 3)), parsed)
    }

    @Test
    fun `빠진 자원은 0 이다`() {
        val parsed = CountyProductionJson.parse(document("""{"countyId":7,"monthly":{"timber":2}}"""))
        assertEquals(mapOf(7 to Resources(timber = 2)), parsed)
    }

    @Test
    fun `전 곡 을 실으면 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            CountyProductionJson.parse(document("""{"countyId":7,"monthly":{"money":1}}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            CountyProductionJson.parse(document("""{"countyId":7,"monthly":{"grain":1}}"""))
        }
    }

    @Test
    fun `음수 소수 중복 빈 행 잘못된 판을 거절한다`() {
        val invalid = listOf(
            document("""{"countyId":7,"monthly":{"iron":-1}}"""),
            document("""{"countyId":7,"monthly":{"iron":1.5}}"""),
            document("""{"countyId":7,"monthly":{"iron":1}},{"countyId":7,"monthly":{"iron":2}}"""),
            document("""{"countyId":7,"monthly":{}}"""),
            document("""{"countyId":7,"monthly":{"iron":0,"timber":0,"horses":0}}"""),
            document("""{"countyId":0,"monthly":{"iron":1}}"""),
            document("""{"countyId":"7","monthly":{"iron":1}}"""),
            document("""{"countyId":7}"""),
            document("""{"countyId":7,"monthly":{"iron":1}}""", version = 2),
        )
        for (json in invalid) {
            assertFailsWith<IllegalArgumentException>(json) { CountyProductionJson.parse(json) }
        }
    }
}
