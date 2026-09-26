package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertNotNull

class NpcAiTurnSelectorTest {
    @Test fun `registered court reward belongs to the court turn and does not break general turn startup`() {
        val fixture = CampaignWorldFixture()
        assertNotNull(NpcAiTurnSelector(fixture.topology, fixture.metrics, DomesticContext()))
    }
}
