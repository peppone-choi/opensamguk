package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources

class DirectRulesTest {
    private val actor = DomesticPerson(7, "장수", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "province-a", false, emptyMap(), gold = 500)
    private val county = DomesticCounty(11, "현", 1, "province-a", "군", mapOf(
        CountyWarehouse.META_KEY to CountyWarehouse(11, 0, Resources(money = 500, grain = 500)).toMetaValue()))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(actor), emptyList(), listOf(county), emptyList(), setOf("province-a"))

    @Test fun `strict legacy and malformed captive markers block every direct request`() {
        val equipmentId = ItemCatalogJson.CANON.equipment.first().id
        val requests = listOf(
            DirectRequest.Convert(actor.id, 12, 2),
            DirectRequest.Equipment(actor.id, null, TradeSide.BUY, equipmentId),
            DirectRequest.Grain(actor.id, TradeSide.BUY, 1),
            DirectRequest.Transport(actor.id, 11, Cargo.GRAIN, 1),
        )
        val captive = CaptiveState(8, "province-a", state.now, "encounter-1").toMetaValue()
        for (marker in listOf(captive, mapOf("version" to 1), null)) {
            val held = state.copy(people = listOf(actor.copy(meta = mapOf(CaptiveState.META_KEY to marker))))
            for (request in requests) assertEquals(DirectFailure.STATE_UNAVAILABLE,
                assertIs<DirectAssessment.Rejected>(DirectRules.assess(request, held)).reason)
        }
    }

    @Test fun `free human and NPC keep the actual grain option and default profile rejection`() {
        val request = DirectRequest.Grain(actor.id, TradeSide.BUY, 1)
        assertIs<DirectAssessment.Eligible>(DirectRules.assess(request, state))
        assertIs<DirectAssessment.Eligible>(DirectRules.assess(request,
            state.copy(people = listOf(actor.copy(userOwned = false, npcState = 2)))))
        assertEquals(DirectFailure.WRONG_RULE_PROFILE, assertIs<DirectAssessment.Rejected>(
            DirectRules.assess(request, state.copy(profile = RuleProfile.entries.first { it != RuleProfile.HWIHA }))).reason)
    }
}
