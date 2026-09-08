package opensamguk.logic.world

import kotlin.test.*

class BattlefieldMovementRulesTest {
    private val hash = "a".repeat(64)
    private val node = StrategicNodeRef.LandProvince("45776")
    private val catalog = BattlefieldCatalog(hash, listOf(BattlefieldCatalogEntry("changban", "장판", node, 405, BattlefieldRole.FIELD)))
    private val anchors = mapOf(405 to node)
    private fun request(position: GeneralPositionState? = null, city: Int = 405, expected: Long? = position?.revision, suppliedHash: String = hash) =
        BattlefieldMovementRequest("r1", hash, 1, city, position, expected, suppliedHash, anchors)
    private fun denial(result: BattlefieldMovementResult) = assertIs<BattlefieldMovementResult.Denied>(result).code

    @Test fun `entry and exit retain exact origin and consume turn`() {
        val entry = assertIs<BattlefieldMovementResult.Allowed>(BattlefieldMovementRules.enter(catalog, "changban", request()))
        assertTrue(entry.consumesMovementTurn)
        assertEquals(BattlefieldPresence("changban", hash, 405), entry.assessment.battlefield)
        val state = GeneralPositionState("r1", hash, 1, node, 1, entry.assessment.battlefield)
        val exit = assertIs<BattlefieldMovementResult.Allowed>(BattlefieldMovementRules.exit(catalog, request(state)))
        assertNull(exit.assessment.battlefield)
        assertEquals(node, exit.assessment.node)
    }
    @Test fun `stale and foreign positions cannot enter`() {
        assertEquals(BattlefieldMovementDenial.STALE_CATALOG, denial(BattlefieldMovementRules.enter(catalog,"changban",request(suppliedHash="b".repeat(64)))))
        assertEquals(BattlefieldMovementDenial.INVALID_ORIGIN, denial(BattlefieldMovementRules.enter(catalog,"changban",request(city=781))))
        val state = GeneralPositionState("r1",hash,1,StrategicNodeRef.LandProvince("other"),1)
        assertEquals(BattlefieldMovementDenial.WRONG_NODE,denial(BattlefieldMovementRules.enter(catalog,"changban",request(state))))
        assertEquals(BattlefieldMovementDenial.STALE_REVISION,denial(BattlefieldMovementRules.enter(catalog,"changban",request(state,expected=2))))
    }
    @Test fun `duplicate entry and invalid return denied`() {
        val state = GeneralPositionState("r1",hash,1,node,1,BattlefieldPresence("changban",hash,405))
        assertEquals(BattlefieldMovementDenial.ALREADY_DEPLOYED,denial(BattlefieldMovementRules.enter(catalog,"changban",request(state))))
        assertEquals(BattlefieldMovementDenial.INVALID_RETURN,denial(BattlefieldMovementRules.exit(catalog,request(state).copy(cityAnchors=emptyMap()))))
    }
    @Test fun `catalog rejects duplicates and retains withheld sites`() {
        assertFailsWith<IllegalArgumentException> { BattlefieldCatalog(hash, catalog.entries.values.toList()+catalog.entries.values.first()) }
        val withheld = BattlefieldCatalog(hash,listOf(BattlefieldCatalogEntry("hulaoguan","호뢰관",null,null,BattlefieldRole.PASS)))
        assertEquals(BattlefieldMovementDenial.WITHHELD,denial(BattlefieldMovementRules.enter(withheld,"hulaoguan",request())))
        assertEquals(BattlefieldMovementDenial.UNKNOWN_SITE,denial(BattlefieldMovementRules.enter(catalog,"missing",request())))
    }
    @Test fun `field gate allows only movement and rest`() {
        val presence = BattlefieldPresence("changban",hash,405)
        assertTrue(battlefieldAllowsCityLocalAction(null,BattlefieldAction.CITY_LOCAL))
        assertFalse(battlefieldAllowsCityLocalAction(presence,BattlefieldAction.CITY_LOCAL))
        assertTrue(battlefieldAllowsCityLocalAction(presence,BattlefieldAction.BATTLEFIELD_MOVEMENT))
        assertTrue(battlefieldAllowsCityLocalAction(presence,BattlefieldAction.REST))
    }
    @Test fun `catalog rejects malformed identity and role node mismatches`() {
        assertFailsWith<IllegalArgumentException> { BattlefieldCatalog("bad", emptyList()) }
        assertFailsWith<IllegalArgumentException> { BattlefieldCatalogEntry("Bad ID", "name", node, 405, BattlefieldRole.FIELD) }
        assertFailsWith<IllegalArgumentException> { BattlefieldCatalogEntry("site", "name", node, null, BattlefieldRole.FIELD) }
        assertFailsWith<IllegalArgumentException> { BattlefieldCatalogEntry("site", "name", node, 405, BattlefieldRole.NAVAL) }
        assertFailsWith<IllegalArgumentException> { BattlefieldCatalogEntry("site", "name", StrategicNodeRef.WaterZone("lake"), 405, BattlefieldRole.FIELD) }
    }
    @Test fun `foreign actor topology and exhausted revisions fail closed`() {
        val state = GeneralPositionState("r1",hash,2,node,1)
        assertEquals(BattlefieldMovementDenial.INVALID_POSITION,denial(BattlefieldMovementRules.enter(catalog,"changban",request(state))))
        assertEquals(BattlefieldMovementDenial.INVALID_POSITION,denial(BattlefieldMovementRules.enter(catalog,"changban",request(state.copy(generalId=1,topologyRevision="old")))))
        assertEquals(BattlefieldMovementDenial.INVALID_POSITION,denial(BattlefieldMovementRules.enter(catalog,"changban",request(state.copy(generalId=1,revision=Long.MAX_VALUE)))))
    }
    @Test fun `exit rejects stale presence wrong node and changed return anchor`() {
        val state = GeneralPositionState("r1",hash,1,node,1,BattlefieldPresence("changban",hash,405))
        assertEquals(BattlefieldMovementDenial.STALE_CATALOG,denial(BattlefieldMovementRules.exit(catalog,request(state.copy(battlefield=BattlefieldPresence("changban","b".repeat(64),405))))))
        assertEquals(BattlefieldMovementDenial.WRONG_NODE,denial(BattlefieldMovementRules.exit(catalog,request(state.copy(node=StrategicNodeRef.LandProvince("other"))))))
        assertEquals(BattlefieldMovementDenial.INVALID_RETURN,denial(BattlefieldMovementRules.exit(catalog,request(state).copy(cityAnchors=mapOf(405 to StrategicNodeRef.LandProvince("other"))))))
        assertEquals(BattlefieldMovementDenial.NOT_DEPLOYED,denial(BattlefieldMovementRules.exit(catalog,request())))
    }

}
