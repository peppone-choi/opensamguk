package opensamguk.logic.office

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficeCapabilityResolverTest {
    private val catalog = OfficeCatalog.loadClasspath()
    private val rules = OfficeRules.loadClasspath()
    private val prefect = requireNotNull(catalog.definition("office.commandery-prefect"))
    private val inspector = requireNotNull(catalog.definition("office.provincial-inspector"))
    private val governor = requireNotNull(catalog.definition("office.provincial-governor"))
    private val tenure = OfficeTenure(
        id = "tenure-1", officeId = prefect.id, jurisdictionId = "hhs-group:109:京兆尹",
        holderId = 10, issuerId = 1, nationId = 1, origin = OfficeClaimOrigin.POLITY_APPOINTMENT,
        appointedTurn = 5, acceptedTurn = 6, assumedTurn = 8, seatCountyId = 100,
    )
    private val snapshot = OfficeJurisdictionSnapshot(
        jurisdictionId = tenure.jurisdictionId, countyIds = setOf(100, 101, 102, 103),
        seatCountyId = 100, holderCountyId = 100,
        ownedCountyIds = setOf(100, 101), warehouseConnectedCountyIds = setOf(100, 101),
        seatedMagistrateCountyIds = setOf(100), stationedCorpsCountyIds = emptySet(),
    )

    @Test
    fun `appointed and assumed prefect controls only owned supplied counties`() {
        val result = OfficeCapabilityResolver.resolve(prefect, tenure, snapshot, rules, OfficeCapability.COUNTY_WORK_START, 101)
        val allowed = assertIs<OfficeCapabilityDecision.Allow>(result)
        assertEquals(listOf(100, 101), allowed.countyIds)
        assertEquals(OfficeCapabilityDecision.Deny(OfficeDenial.TARGET_OUTSIDE_CONTROL),
            OfficeCapabilityResolver.resolve(prefect, tenure, snapshot, rules, OfficeCapability.COUNTY_WORK_START, 102))
    }

    @Test
    fun `nominal prefect cannot set commandery policy`() {
        val result = OfficeCapabilityResolver.resolve(prefect, tenure.copy(assumedTurn = null, seatCountyId = null), snapshot, rules, OfficeCapability.COMMANDERY_POLICY)
        val denied = assertIs<OfficeCapabilityDecision.Deny>(result)
        assertEquals(OfficeDenial.NOMINAL_ONLY, denied.reason)
        assertTrue(OfficeEvidence.ASSUMED_SEAT in denied.missing)
        val posthumous = assertIs<OfficeCapabilityDecision.Deny>(OfficeCapabilityResolver.resolve(
            prefect, tenure.copy(origin = OfficeClaimOrigin.POSTHUMOUS), snapshot, rules, OfficeCapability.COMMANDERY_POLICY))
        assertTrue(OfficeEvidence.LIVING_CLAIM in posthumous.missing)
    }

    @Test
    fun `losing seat or supply removes authority`() {
        val lostSeat = snapshot.copy(ownedCountyIds = setOf(101))
        val deniedSeat = assertIs<OfficeCapabilityDecision.Deny>(OfficeCapabilityResolver.resolve(prefect, tenure, lostSeat, rules, OfficeCapability.COMMANDERY_POLICY))
        assertTrue(OfficeEvidence.SEAT_OWNED in deniedSeat.missing)
        val cutSupply = snapshot.copy(warehouseConnectedCountyIds = emptySet())
        val deniedSupply = assertIs<OfficeCapabilityDecision.Deny>(OfficeCapabilityResolver.resolve(prefect, tenure, cutSupply, rules, OfficeCapability.COMMANDERY_POLICY))
        assertTrue(OfficeEvidence.WAREHOUSE_CONNECTION in deniedSupply.missing)
    }

    @Test
    fun `inspector may inspect but cannot issue prefect work`() {
        val inspection = tenure.copy(officeId = inspector.id, jurisdictionId = "zhou:冀州")
        val province = snapshot.copy(jurisdictionId = "zhou:冀州")
        assertIs<OfficeCapabilityDecision.Allow>(OfficeCapabilityResolver.resolve(inspector, inspection, province, rules, OfficeCapability.PROVINCIAL_INSPECTION))
        assertEquals(OfficeCapabilityDecision.Deny(OfficeDenial.CAPABILITY_UNAVAILABLE),
            OfficeCapabilityResolver.resolve(inspector, inspection, province, rules, OfficeCapability.COUNTY_WORK_START, 101))
        assertEquals(OfficeCapabilityDecision.Deny(OfficeDenial.WRONG_JURISDICTION),
            OfficeCapabilityResolver.resolve(inspector, inspection.copy(jurisdictionId = "zhou:司隸"), province.copy(jurisdictionId = "zhou:司隸"), rules, OfficeCapability.PROVINCIAL_INSPECTION))
    }

    @Test
    fun `county office cannot gain a second stored tenure and local concurrency is bounded`() {
        val countyTenure = tenure.copy(id = "county", officeId = "office.county-magistrate")
        assertFailsWith<IllegalArgumentException> { OfficeCapabilityResolver.validateTenures(listOf(countyTenure), catalog, rules) }
        val three = listOf(
            tenure,
            tenure.copy(id = "second", officeId = inspector.id, jurisdictionId = "zhou:冀州"),
            tenure.copy(id = "third", jurisdictionId = "hhs-group:110:魏郡"),
        )
        assertFailsWith<IllegalArgumentException> { OfficeCapabilityResolver.validateTenures(three, catalog, rules) }
    }

    @Test
    fun `rival nations may hold competing tenures for the same jurisdiction`() {
        val rival = tenure.copy(id = "rival", holderId = 11, nationId = 2)
        OfficeCapabilityResolver.validateTenures(listOf(tenure, rival), catalog, rules)
    }

    @Test
    fun `one nation cannot hold duplicate local jurisdiction tenures`() {
        val secondPrefect = tenure.copy(id = "second-prefect", holderId = 11)
        val commanderyConflict = assertFailsWith<IllegalArgumentException> {
            OfficeCapabilityResolver.validateTenures(listOf(tenure, secondPrefect), catalog, rules)
        }
        assertEquals("conflicting local jurisdiction tenure", commanderyConflict.message)

        val provincialInspector = tenure.copy(id = "inspector", officeId = inspector.id, jurisdictionId = "zhou:冀州")
        val provincialGovernor = tenure.copy(id = "governor", officeId = governor.id, jurisdictionId = "zhou:冀州", holderId = 11)
        val provincialConflict = assertFailsWith<IllegalArgumentException> {
            OfficeCapabilityResolver.validateTenures(listOf(provincialInspector, provincialGovernor), catalog, rules)
        }
        assertEquals("conflicting local jurisdiction tenure", provincialConflict.message)
    }

    @Test
    fun `meta codec sorts and rejects damaged state`() {
        val second = tenure.copy(id = "tenure-2", holderId = 11, jurisdictionId = "hhs-group:110:魏郡")
        val encoded = OfficeTenureCodec.encode(listOf(second, tenure))
        assertEquals(listOf(tenure, second), OfficeTenureCodec.decode(encoded))
        assertEquals(emptyList(), OfficeTenureCodec.decode(null))
        assertFailsWith<IllegalArgumentException> { OfficeTenureCodec.decode(encoded.replace("\"version\":1", "\"version\":9")) }
        assertFailsWith<IllegalArgumentException> { OfficeTenureCodec.decode(encoded.replace("\"holderId\":10", "\"holderId\":null")) }
        val acting = OfficeCredential("acting-1", OfficeCredentialKind.ACTING_XING, 1, 5)
        val concurrent = OfficeCredential("concurrent-1", OfficeCredentialKind.CONCURRENT_LING, 1, 6)
        val credentialWire = OfficeCredentialCodec.encode(listOf(concurrent, acting))
        assertEquals(listOf(acting, concurrent), OfficeCredentialCodec.decode(credentialWire))
        assertFailsWith<IllegalArgumentException> {
            OfficeCapabilityResolver.validateTenures(listOf(tenure.copy(credentialIds = listOf("missing"))), catalog, rules)
        }
    }
}
