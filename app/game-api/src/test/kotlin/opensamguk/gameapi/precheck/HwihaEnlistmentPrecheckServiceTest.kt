package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.*
import opensamguk.logic.input.*

class HwihaEnlistmentPrecheckServiceTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val service = HwihaEnlistmentPrecheckService(generals, nations, retainers, worlds)
    private val request = EnlistmentRequest(1, EnlistmentMode.NATION, 1)
    private fun general(id: Int, lord: Boolean = false, cap: Int = 30) = GeneralReadEntity(
        id = id, worldId = 1, name = "G$id", nationId = if (lord) 1 else 0, officerLevel = if (lord) 12 else 0,
        npcState = 2, leadership = 20, strength = 20, intel = 20, politics = 100, charm = 100,
        meta = mapOf("hwihaLord" to lord, HwihaPersonPolicyState.META_KEY to
            HwihaPersonPolicyState(cap, true, "fixture", "v1", id).toMetaValue()))
    private fun setup(cap: Int = 30) {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA")))
        `when`(generals.findAll()).thenReturn(listOf(general(1), general(10, true, cap)))
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, worldId = 1)))
        `when`(retainers.findAll()).thenReturn(emptyList())
    }
    @Test fun `API reads all five columns and refreshes capacity for every precheck`() {
        setup(6)
        assertEquals(6, assertIs<EnlistmentAssessment.Eligible>(service.assess(request)).choices.single().masterRenownCost)
        setup(5)
        assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.INSUFFICIENT_RENOWN), service.assess(request))
    }
    @Test fun `unlinked recruited cards cannot disappear from the policy projection`() {
        setup()
        `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(
            worldId = 1, id = 1, masterGeneralId = 10, generalId = null, name = "recruit")))
        assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE), service.assess(request))
        verify(retainers).findAll()
        verify(retainers, never()).boundGeneralIds()
    }
    @Test fun `wrong or missing world rejects before querying person state`() {
        `when`(worlds.findProcessWorld()).thenReturn(null)
        assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE), service.assess(request))
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1))
        assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.WRONG_RULE_PROFILE), service.assess(request))
        verifyNoInteractions(generals, nations, retainers)
    }
    @Test fun `invalid profile fails closed without reading unrelated people`() {
        for (value in listOf<Any>(42, "UNRECOGNIZED")) {
            `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to value)))
            assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE), service.assess(request))
        }
        verifyNoInteractions(generals, nations, retainers)
    }

}
