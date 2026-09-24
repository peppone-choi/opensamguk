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
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "SAMMO")))
        assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.WRONG_RULE_PROFILE), service.assess(request))
        verifyNoInteractions(generals, nations, retainers)
    }
    @Test fun `invalid profile fails closed without reading unrelated people`() {
        for (value in listOf<Any?>(null, 42, "UNRECOGNIZED")) {
            `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to value)))
            assertEquals(EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE), service.assess(request))
        }
        verifyNoInteractions(generals, nations, retainers)
    }

    @Test fun `owned options use one complete projection and the exact admission assessment`() {
        setup(5)
        `when`(generals.findById(1)).thenReturn(java.util.Optional.of(general(1).apply { userId = "42" }))
        val response = service.options(1, 42)
        assertEquals(listOf(EnlistmentMode.RANDOM, EnlistmentMode.NATION, EnlistmentMode.GENERAL), response.options.map { it.mode })
        assertEquals(listOf(null, 1, 10), response.options.map { it.targetId })
        assertEquals(listOf("NO_ELIGIBLE_NATION", "INSUFFICIENT_RENOWN", "INSUFFICIENT_RENOWN"), response.options.map { it.availability.code })
        response.options.forEach { assertFalse(it.availability.reason.isNullOrBlank()) }
        verify(generals, times(1)).findAll()
        verify(nations, times(1)).findAll()
        verify(retainers, times(1)).findAll()
        setup(6)
        assertTrue(service.options(1, 42).options.all { it.availability.status == "AVAILABLE" })
    }

    @Test fun `options deny another owner before reading world people or policy`() {
        `when`(generals.findById(1)).thenReturn(java.util.Optional.of(general(1).apply { userId = "43" }))
        assertFailsWith<EnlistmentOptionsForbidden> { service.options(1, 42) }
        verify(generals, never()).findAll()
        verifyNoInteractions(worlds, nations, retainers)
    }

    @Test fun `unavailable options retain an explanation without leaking target roster`() {
        `when`(generals.findById(1)).thenReturn(java.util.Optional.of(general(1).apply { userId = "42" }))
        `when`(worlds.findProcessWorld()).thenReturn(null)
        val row = service.options(1, 42).options.single()
        assertEquals("POLICY_UNAVAILABLE", row.availability.code)
        assertEquals(EnlistmentFailure.POLICY_UNAVAILABLE.message, row.availability.reason)
        assertNull(row.targetId)
        verify(generals, never()).findAll()
        verifyNoInteractions(nations, retainers)
    }

    @Test fun `general options preserve following a non lord to their nearest lord`() {
        setup()
        `when`(generals.findById(1)).thenReturn(java.util.Optional.of(general(1).apply { userId = "42" }))
        `when`(generals.findAll()).thenReturn(listOf(general(1), general(10, true), general(11).apply { nationId = 1 }))
        `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(
            worldId = 1, id = 5, masterGeneralId = 10, generalId = 11, name = "G11")))
        val option = service.options(1, 42).options.single { it.mode == EnlistmentMode.GENERAL && it.targetId == 11 }
        assertEquals("G11", option.label)
        assertEquals("AVAILABLE", option.availability.status)
        assertEquals(10, assertIs<EnlistmentAssessment.Eligible>(service.assess(
            EnlistmentRequest(1, EnlistmentMode.GENERAL, 11))).choices.single().masterId)
    }

    @Test fun `global person budget failure returns one explanatory option`() {
        setup()
        `when`(generals.findById(1)).thenReturn(java.util.Optional.of(general(1).apply { userId = "42" }))
        `when`(generals.findAll()).thenReturn(listOf(general(1).apply { meta = emptyMap() }, general(10, true)))
        val row = service.options(1, 42).options.single()
        assertEquals(EnlistmentMode.RANDOM, row.mode)
        assertEquals("POLICY_UNAVAILABLE", row.availability.code)
    }

}
