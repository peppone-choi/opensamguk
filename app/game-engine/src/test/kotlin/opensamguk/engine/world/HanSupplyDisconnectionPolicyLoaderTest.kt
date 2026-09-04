package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.SupplyDisconnectionDecision
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HanSupplyDisconnectionPolicyLoaderTest {
    private val mapper = ObjectMapper()

    private fun fixture(ledger: String): HanSupplyDisconnectionPolicyLoader {
        val dir = createTempDirectory("han-supply-policy")
        val map = dir.resolve("tiles.json")
        val runtime = dir.resolve("han.json")
        val ledgerPath = dir.resolve("ledger.json")
        map.writeText(
            """{"provinceRecords":[
              {"id":"PR0","jurisdictionId":"J0"},
              {"id":"PR1","jurisdictionId":"J1"}
            ],"jurisdictionRecords":[{"id":"J0"},{"id":"J1"}]}""",
        )
        runtime.writeText(
            """{"cities":[
              {"id":1,"physicalPlaceId":"P1","provinceId":0},
              {"id":2,"physicalPlaceId":"P2","provinceId":1}
            ]}""",
        )
        ledgerPath.writeText(ledger)
        return HanSupplyDisconnectionPolicyLoader(mapper, ledgerPath.toString(), map.toString(), runtime.toString())
    }

    private fun row(
        decision: String = "PROTECT_GEOMETRY_DEFECT",
        physicalPlaceId: String = "P2",
        jurisdictionId: String = "J1",
        from: Int = 1020,
        to: Int = 1030,
    ) = """{
      "runtimeCityId":2,"physicalPlaceId":"$physicalPlaceId","jurisdictionId":"$jurisdictionId",
      "decision":"$decision","sourceLedgerRow":"R0@1:1",
      "effectiveScenarioFrom":$from,"effectiveScenarioTo":$to
    }"""

    private fun ledger(vararg rows: String) =
        """{"schemaVersion":1,"decisions":[${rows.joinToString(",")}]}"""

    @Test
    fun `active reviewed rows map deterministically by runtime city`() {
        val loader = fixture(ledger(row(), row(decision = "UPHOLD_WATER_ROUTE_ONLY", from = 1040, to = 1040)))

        val policies = loader.load(1020, mapOf(2 to 1, 1 to 0))

        assertEquals(listOf(2), policies.keys.toList())
        assertEquals(SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT, policies.getValue(2).decision)
        assertEquals("R0@1:1", policies.getValue(2).sourceLedgerRow)
        assertEquals(emptyMap(), loader.load(1031, mapOf(1 to 0, 2 to 1)))
    }

    @Test
    fun `malformed schema and unknown decisions fail startup`() {
        assertFailsWith<IllegalStateException> { fixture("""{"schemaVersion":2,"decisions":[]}""").load(1020, mapOf(1 to 0)) }
        assertFailsWith<IllegalStateException> { fixture(ledger(row(decision = "GUESS"))).load(1020, mapOf(2 to 1)) }
    }

    @Test
    fun `duplicate active rows fail startup`() {
        val loader = fixture(ledger(row(), row()))
        assertFailsWith<IllegalStateException> { loader.load(1020, mapOf(2 to 1)) }
    }

    @Test
    fun `physical jurisdiction and live province drift fail startup`() {
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(physicalPlaceId = "OLD"))).load(1020, mapOf(2 to 1))
        }
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(jurisdictionId = "OLD"))).load(1020, mapOf(2 to 1))
        }
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row())).load(1020, mapOf(2 to 0))
        }
    }
}
