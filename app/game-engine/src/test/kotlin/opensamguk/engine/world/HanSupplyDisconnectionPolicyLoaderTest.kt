package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.SupplyDisconnectionDecision
import opensamguk.logic.world.SupplyReachabilityExpectation
import opensamguk.infra.seed.MapJson
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
        val sourceLedgerPath = dir.resolve("source-ledger.json")
        map.writeText(
            """{"provinceRecords":[
              {"id":"PR0","jurisdictionId":"J0","parentRegionId":"R0"},
              {"id":"PR1","jurisdictionId":"J1","parentRegionId":"R0"}
            ],"jurisdictionRecords":[{"id":"J0"},{"id":"J1"}]}""",
        )
        runtime.writeText(
            """{"cities":[
              {"id":1,"physicalPlaceId":"P1","provinceId":0},
              {"id":2,"physicalPlaceId":"P2","provinceId":1}
            ]}""",
        )
        sourceLedgerPath.writeText(
            """{"schemaVersion":1,"adjudications":[
              {"componentKey":"R0@1:1","unitId":"R0","memberIds":["J1"],"verdict":"GEOMETRY_DEFECT"},
              {"componentKey":"WATER@1:1","unitId":"R0","memberIds":["J1"],"verdict":"WATER_SEPARATED"},
              {"componentKey":"OTHER-PARENT@9:9","unitId":"R9","memberIds":["J1"],"verdict":"HISTORICAL_EXCLAVE"},
              {"componentKey":"OTHER-MEMBER@9:9","unitId":"R0","memberIds":["J9"],"verdict":"HISTORICAL_EXCLAVE"}
            ]}""",
        )
        ledgerPath.writeText(ledger)
        return HanSupplyDisconnectionPolicyLoader(
            mapper,
            ledgerPath.toString(),
            map.toString(),
            runtime.toString(),
            sourceLedgerPath.toString(),
        )
    }

    private fun row(
        decision: String = "PROTECT_GEOMETRY_DEFECT",
        physicalPlaceId: String = "P2",
        jurisdictionId: String = "J1",
        from: Int = 1020,
        to: Int = 1030,
        rationale: String? = "Reviewed fixture decision.",
        expectedCurrentReachability: String? = "CITY_ONLY",
        sourceLedgerRow: String = "R0@1:1",
    ) = """{
      "runtimeCityId":2,"physicalPlaceId":"$physicalPlaceId","jurisdictionId":"$jurisdictionId",
      "decision":"$decision","sourceLedgerRow":"$sourceLedgerRow",
      ${rationale?.let { "\"rationale\":\"$it\"," } ?: ""}
      ${expectedCurrentReachability?.let { "\"expectedCurrentReachability\":\"$it\"," } ?: ""}
      "effectiveScenarioFrom":$from,"effectiveScenarioTo":$to
    }"""

    private fun ledger(vararg rows: String) =
        """{"schemaVersion":1,"decisions":[${rows.joinToString(",")}]}"""

    @Test
    fun `active reviewed rows map deterministically by runtime city`() {
        val loader = fixture(
            ledger(
                row(),
                row(
                    decision = "UPHOLD_WATER_ROUTE_ONLY",
                    sourceLedgerRow = "WATER@1:1",
                    from = 1040,
                    to = 1040,
                ),
            ),
        )

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

    @Test
    fun `arbitrary upheld policy without reviewed provenance fails startup`() {
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(decision = "UPHOLD_HISTORICAL_EXCLAVE"))).load(1020, mapOf(2 to 1))
        }
    }

    @Test
    fun `upheld policy source parent must match the city province parent`() {
        assertFailsWith<IllegalStateException> {
            fixture(
                ledger(
                    row(
                        decision = "UPHOLD_HISTORICAL_EXCLAVE",
                        sourceLedgerRow = "OTHER-PARENT@9:9",
                    ),
                ),
            ).load(1020, mapOf(2 to 1))
        }
    }

    @Test
    fun `upheld policy source members must contain the city jurisdiction`() {
        assertFailsWith<IllegalStateException> {
            fixture(
                ledger(
                    row(
                        decision = "UPHOLD_HISTORICAL_EXCLAVE",
                        sourceLedgerRow = "OTHER-MEMBER@9:9",
                    ),
                ),
            ).load(1020, mapOf(2 to 1))
        }
    }

    @Test
    fun `unknown source ledger row fails startup`() {
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(sourceLedgerRow = "UNKNOWN@0:0"))).load(1020, mapOf(2 to 1))
        }
    }

    @Test
    fun `missing rationale or expected reachability fails startup`() {
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(rationale = null))).load(1020, mapOf(2 to 1))
        }
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(expectedCurrentReachability = null))).load(1020, mapOf(2 to 1))
        }
        assertFailsWith<IllegalStateException> {
            fixture(ledger(row(expectedCurrentReachability = "BOTH_SUPPLIED"))).load(1020, mapOf(2 to 1))
        }
    }

    @Test
    fun `classpath runtime map location is supported`() {
        val dir = createTempDirectory("han-supply-classpath")
        val map = dir.resolve("tiles.json")
        val ledgerPath = dir.resolve("ledger.json")
        val sourceLedgerPath = dir.resolve("source-ledger.json")
        map.writeText("""{"provinceRecords":[],"jurisdictionRecords":[]}""")
        ledgerPath.writeText(ledger())
        sourceLedgerPath.writeText("""{"schemaVersion":1,"adjudications":[]}""")
        val loader = HanSupplyDisconnectionPolicyLoader(
            mapper,
            ledgerPath.toString(),
            map.toString(),
            "classpath:map/han.json",
            sourceLedgerPath.toString(),
        )

        assertEquals(emptyMap(), loader.load(1020, emptyMap()))
    }

    @Test
    fun `world v3 policies bind runtime physical and route identities while v2 stays legacy`() {
        val loader = HanSupplyDisconnectionPolicyLoader(
            objectMapper = mapper,
            ledgerPath = "../../data/curated/han/supply-disconnection-adjudications-v1.json",
            mapPath = "../../data/map/han-tiles.json",
            runtimeMapPath = "../../infra/src/main/resources/map/han.json",
            sourceLedgerPath = "../../data/curated/han/territory-disconnection-adjudications-v1.json",
            v3LedgerPath = "../../data/curated/han/supply-disconnection-adjudications-v3.json",
            v3RuntimeMapPath = "../../infra/src/main/resources/map/han-world-v3.json",
        )
        val liveCities = MapJson.loadFromClasspath("han-world-v3").cities.mapNotNull { city ->
            city.provinceId?.let {
                SpatialSupplyCity(
                    cityId = city.id,
                    provinceIndex = it,
                    nationId = 0,
                    physicalPlaceRef = city.physicalPlaceRef,
                    routeNodeKey = city.routeNodeKey,
                )
            }
        }

        val policies = loader.load("han-world-v3", 1030, liveCities)

        assertEquals(listOf(305, 548), policies.keys.toList())
        assertEquals(
            SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT,
            policies.getValue(305).decision,
        )
        assertEquals(
            SupplyReachabilityExpectation.BOTH_UNSUPPLIED,
            policies.getValue(305).expectedCurrentReachability,
        )
        assertEquals(
            SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT,
            policies.getValue(548).decision,
        )
        assertEquals(null, policies[364], "Zhu-a has no supply policy")

        val wrongRoute = liveCities.map { city ->
            if (city.cityId == 305) city.copy(routeNodeKey = "wrong-route") else city
        }
        assertFailsWith<IllegalStateException> {
            loader.load("han-world-v3", 1030, wrongRoute)
        }
        val wrongPhysicalPlace = liveCities.map { city ->
            if (city.cityId == 305) city.copy(physicalPlaceRef = "chgis:v6:cnty:WRONG") else city
        }
        assertFailsWith<IllegalStateException> {
            loader.load("han-world-v3", 1030, wrongPhysicalPlace)
        }
        val wrongRuntimeId = liveCities.map { city ->
            if (city.cityId == 305) city.copy(cityId = 9999) else city
        }
        assertFailsWith<IllegalStateException> {
            loader.load("han-world-v3", 1030, wrongRuntimeId)
        }
        val wrongProvince = liveCities.map { city ->
            if (city.cityId == 305) city.copy(provinceIndex = 0) else city
        }
        assertFailsWith<IllegalStateException> {
            loader.load("han-world-v3", 1030, wrongProvince)
        }

        val legacyLive = MapJson.loadFromClasspath("han-world-v2").cities.mapNotNull { city ->
            city.provinceId?.let {
                SpatialSupplyCity(city.id, it, 0, city.physicalPlaceRef, city.routeNodeKey)
            }
        }
        assertEquals(
            loader.load("han", 1030, legacyLive),
            loader.load("han-world-v2", 1030, legacyLive),
        )
    }
}
