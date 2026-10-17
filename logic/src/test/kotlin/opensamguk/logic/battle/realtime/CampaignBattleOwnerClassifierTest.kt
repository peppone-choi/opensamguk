package opensamguk.logic.battle.realtime

import kotlin.test.*

class CampaignBattleOwnerClassifierTest {
    private fun classify(user: String?, npc: Int, others: List<BattleOwnerNativeGeneral> = emptyList()) =
        CampaignBattleOwnerClassifier.classify(1, setOf(7), listOf(BattleOwnerNativeGeneral(1, 7, user, npc)) + others)

    @Test fun `canonical human and AI facts are preserved without normalization`() {
        for (npc in listOf(0, 1)) for (user in listOf("1", "42", "2147483647")) {
            val result = classify(user, npc) as CampaignBattleOwnerClassifier.Accepted
            assertEquals(user.toInt(), result.owners.getValue(7).accountId)
            assertTrue(result.owners.getValue(7).playable)
        }
        for (npc in listOf(2, 9)) {
            val result = classify(null, npc) as CampaignBattleOwnerClassifier.Accepted
            assertNull(result.owners.getValue(7).accountId)
            assertFalse(result.owners.getValue(7).playable)
        }
    }
    @Test fun `missing human metadata and NPC account never become another owner class`() {
        for ((user, npc, detail) in listOf(Triple(null, 0, "HUMAN_METADATA_MISSING"),
            Triple(null, 1, "HUMAN_METADATA_MISSING"), Triple("42", 2, "NPC_WITH_ACCOUNT"))) {
            val result = classify(user, npc) as CampaignBattleOwnerClassifier.Rejected
            assertEquals(detail, result.detail)
            assertEquals(7, result.ownerGeneralId)
        }
    }
    @Test fun `noncanonical and overflowing account values fail for every NPC state`() {
        for (user in listOf("042", "", " 42", "42 ", "+42", "0", "-1", "4.2", "2147483648", "99999999999"))
            for (npc in listOf(0, 1, 2)) assertEquals("NONCANONICAL_ACCOUNT",
                (classify(user, npc) as CampaignBattleOwnerClassifier.Rejected).detail)
    }
    @Test fun `duplicate account outside the participant set is still rejected`() {
        val extra = BattleOwnerNativeGeneral(1, 99, "42", 0)
        assertEquals("DUPLICATE_ACCOUNT", (classify("42", 0, listOf(extra)) as CampaignBattleOwnerClassifier.Rejected).detail)
        assertEquals("DUPLICATE_ACCOUNT", (CampaignBattleOwnerClassifier.classify(1, setOf(7, 99),
            listOf(BattleOwnerNativeGeneral(1, 7, "42", 0), extra)) as CampaignBattleOwnerClassifier.Rejected).detail)
        assertIs<CampaignBattleOwnerClassifier.Accepted>(classify("42", 0, listOf(extra.copy(userId = "43"))))
    }
    @Test fun `missing foreign duplicate or negative native facts fail closed`() {
        for (rows in listOf(emptyList(), listOf(BattleOwnerNativeGeneral(2, 7, "42", 0)),
            listOf(BattleOwnerNativeGeneral(1, 7, "42", -1)),
            List(2) { BattleOwnerNativeGeneral(1, 7, "42", 0) }))
            assertEquals("BINDING_REJECTED", (CampaignBattleOwnerClassifier.classify(1, setOf(7), rows)
                as CampaignBattleOwnerClassifier.Rejected).detail)
    }
    @Test fun `sealed force reader verifies schema hash and original values without a live encounter`() {
        val force = opensamguk.logic.input.EncounterForces("a".repeat(64), listOf(
            opensamguk.logic.input.EncounterUnitForce(11, 1, 1, 1, 500, 20, 30, 40, 600, null)),
            listOf(opensamguk.logic.input.EncounterCommanderForce(1, 110, 0, 55, 60, 70)))
        val raw = force.toMetaValue()
        assertEquals(raw, opensamguk.logic.input.EncounterForces.readSealed(raw).toMetaValue())
        for (bad in listOf(raw + ("extra" to true), raw + ("version" to 1L), raw + ("snapshotId" to "bad")))
            assertFailsWith<IllegalArgumentException> { opensamguk.logic.input.EncounterForces.readSealed(bad) }
    }
}
