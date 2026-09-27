package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourtSettlementTest {
    private val house = ImperialHouse("later_han", "後漢", ImperialLineStatus.ACTIVE, 1, null,
        emptyList(), null, courtNationId = 10, courtCityId = 46, legitimacy = 70)
    private val world = ImperialWorldState(listOf(house), emptyList(), emptyList())
    private val ready = CourtProtectionEvidence(
        courtCityId = 46, protectorNationId = 20, protectorControlsCourtCity = true,
        emperorSafelyArrived = true, courtGuardSupplied = true, courtProvisioned = true,
        secretariatOperating = true, sealAdministrationOperating = true,
    )

    @Test
    fun `capturing court city alone never creates a protectorate`() {
        assertFailsWith<IllegalArgumentException> {
            CourtSettlement.adopt(world, "later_han", ready.copy(emperorSafelyArrived = false),
                CourtSettlementStance.COERCIVE_CONTROL, "settle-1", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CourtSettlement.adopt(world, "later_han", ready.copy(courtProvisioned = false),
                CourtSettlementStance.COERCIVE_CONTROL, "settle-1", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CourtSettlement.adopt(world, "later_han", ready.copy(sealAdministrationOperating = false),
                CourtSettlementStance.COERCIVE_CONTROL, "settle-1", 1)
        }
        assertEquals(10, world.houses.single().courtNationId)
    }

    @Test
    fun `settlement is separate from imperial ownership and stance shifts one step at a time`() {
        val coerced = CourtSettlement.adopt(world, "later_han", ready,
            CourtSettlementStance.COERCIVE_CONTROL, "settle-1", 1)
        assertEquals(20, coerced.protectorNationId)
        assertEquals(10, world.houses.single().courtNationId)
        assertFailsWith<IllegalArgumentException> {
            CourtSettlement.change(coerced, world, ready, CourtSettlementStance.HONOR_AND_RESTORE, "settle-2", 2)
        }
        val coRegent = CourtSettlement.change(coerced, world, ready,
            CourtSettlementStance.COREGENT_PROTECTORATE, "settle-2", 2)
        val honored = CourtSettlement.change(coRegent, world, ready,
            CourtSettlementStance.HONOR_AND_RESTORE, "settle-3", 3)
        assertEquals(3, honored.history.size)
        assertEquals(CourtSettlementStance.HONOR_AND_RESTORE, honored.stance)
        assertNull(CourtSettlementCodec.read(emptyMap()))
        val encoded = CourtSettlementCodec.write(listOf(honored))
        assertEquals(listOf(honored), CourtSettlementCodec.read(mapOf(CourtSettlementCodec.META_KEY to encoded)))
        assertFailsWith<IllegalArgumentException> {
            CourtSettlementCodec.read(mapOf(CourtSettlementCodec.META_KEY to (encoded + ("schemaVersion" to 2))))
        }
        assertFailsWith<IllegalArgumentException> {
            CourtSettlement.change(honored, world, ready,
                CourtSettlementStance.COREGENT_PROTECTORATE, "settle-3", 4)
        }
    }

    @Test
    fun `each stance declares concrete protector benefits and recurring burdens`() {
        CourtSettlementStance.entries.forEach { stance ->
            val profile = CourtSettlementProfiles.forStance(stance)
            assertTrue(profile.benefits.isNotEmpty())
            assertTrue(CourtProtectorBurden.SUPPLY_COURT in profile.burdens)
            assertTrue(CourtProtectorBurden.PAY_COURT in profile.burdens)
        }
        assertTrue(CourtProtectorBurden.LEGITIMACY_EXPOSURE in
            CourtSettlementProfiles.forStance(CourtSettlementStance.COERCIVE_CONTROL).burdens)
    }
}
