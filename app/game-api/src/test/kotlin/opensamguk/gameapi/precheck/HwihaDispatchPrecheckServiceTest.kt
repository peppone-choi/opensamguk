package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.HwihaDispatchReadController
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.world.HanStrategicRouteProjection
import java.util.Optional

class HwihaDispatchPrecheckServiceTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val service = HwihaDispatchPrecheckService(generals, retainers, resolver)
    private val now = HwihaPhase(200, 1, 1)
    private val dispatch = HwihaDispatchState("d1", 1, 2, 1, 7, now, now.plus(12))
    private fun person(id: Int, meta: Map<String, Any?> = emptyMap()) = GeneralReadEntity(
        id = id, worldId = 1, nationId = 1, userId = (40 + id).toString(), npcState = 2,
        meta = mapOf("hwihaLord" to (id == 1)) + meta)
    private fun setup(pending: Boolean = false, phase: HwihaPhase = now): List<GeneralReadEntity> {
        val people = listOf(person(1), person(2, if (pending) mapOf(HwihaDispatchState.META_KEY to dispatch.toMetaValue()) else emptyMap()), person(3))
        people.forEach { `when`(generals.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(generals.findAll()).thenReturn(people)
        `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(worldId = 1, id = 5, masterGeneralId = 1, generalId = 2)))
        val artifacts = mock(ResolvedHanWorldArtifacts::class.java)
        val projection = mock(HanStrategicRouteProjection::class.java)
        `when`(artifacts.projection).thenReturn(projection)
        `when`(projection.administrativeCountyIds).thenReturn(setOf(7))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
            WorldStateReadEntity(id = 1, currentYear = phase.year, currentMonth = phase.month, currentPhase = phase.phase,
                config = mapOf("ruleProfile" to "HWIHA")),
            listOf(CityReadEntity(id = 7, worldId = 1, nationId = 1), CityReadEntity(id = 8, worldId = 1, nationId = 1)), artifacts))
        return people
    }
    @Test fun `owner is checked before any world or roster read`() {
        `when`(generals.findById(1)).thenReturn(Optional.of(person(1)))
        assertFailsWith<DispatchReadForbidden> { service.assessDispatch(DispatchRequest(1,2,7), 42) }
        assertFailsWith<DispatchReadForbidden> { service.pending(1, 0) }
        verifyNoInteractions(resolver, retainers)
        verify(generals, never()).findAll()
    }
    @Test fun `shared dispatch rules use selected COUNTY set and human user binding`() {
        setup()
        assertIs<DispatchAssessment.Eligible>(service.assessDispatch(DispatchRequest(1,2,7), 41))
        assertEquals(DispatchAssessment.Rejected(DispatchFailure.INVALID_COUNTY), service.assessDispatch(DispatchRequest(1,2,8), 41))
        verify(resolver, times(2)).resolve()
    }
    @Test fun `reply uses world phase and shared refusal policy check`() {
        setup(true)
        assertEquals(DispatchAssessment.Rejected(DispatchFailure.POLICY_UNAVAILABLE), service.assessReply(DispatchReplyRequest(2,"d1",false),42))
        setup(true, now.plus(12))
        assertIs<DispatchAssessment.Eligible>(service.assessReply(DispatchReplyRequest(2,"d1",false),42))
    }
    @Test fun `only recipient and current issuer direct subordinate see typed latest state`() {
        setup(true)
        assertEquals(listOf("d1"), service.pending(1,41).dispatches.map { it.dispatchId })
        assertEquals(listOf("d1"), service.pending(2,42).dispatches.map { it.dispatchId })
        assertTrue(service.pending(3,43).dispatches.isEmpty())
        `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(worldId = 1,id = 5,masterGeneralId = 3,generalId = 2)))
        assertTrue(service.pending(1,41).dispatches.isEmpty())
        assertTrue(service.pending(3,43).dispatches.isEmpty())
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER, service.pending(2,42).dispatches.single().currentFailure)
    }
    @Test fun `malformed pending and invalid pins are unavailable not empty success`() {
        val people = setup(true)
        people[1].meta = mapOf("hwihaDispatch" to null)
        assertEquals(DispatchFailure.STATE_UNAVAILABLE, service.pending(2,42).code)
        assertFalse(service.pending(2,42).result)
        `when`(resolver.resolve()).thenThrow(IllegalArgumentException("pin mismatch"))
        assertEquals(DispatchAssessment.Rejected(DispatchFailure.STATE_UNAVAILABLE), service.assessDispatch(DispatchRequest(1,2,7),41))
    }
    @Test fun `missing world invalid profile and cross world people fail closed`() {
        val people = setup()
        val valid = resolver.resolve()!!
        `when`(resolver.resolve()).thenReturn(null)
        assertEquals(DispatchFailure.STATE_UNAVAILABLE, service.pending(1,41).code)
        `when`(resolver.resolve()).thenReturn(valid)
        valid.world.config = mapOf("ruleProfile" to null)
        assertEquals(DispatchFailure.STATE_UNAVAILABLE, service.pending(1,41).code)
        valid.world.config = emptyMap()
        assertEquals(DispatchFailure.WRONG_RULE_PROFILE, service.pending(1,41).code)
        valid.world.config = mapOf("ruleProfile" to "HWIHA")
        people[2].worldId = 2
        assertEquals(DispatchFailure.STATE_UNAVAILABLE, service.pending(1,41).code)
    }
    @Test fun `controller requires principal and preserves forbidden ownership`() {
        val controller = HwihaDispatchReadController(service)
        assertEquals(401, controller.pending(null,1).statusCode.value())
        assertEquals(403, controller.pending(0,1).statusCode.value())
        setup(true)
        assertEquals(403, controller.pending(42,1).statusCode.value())
        assertEquals(200, controller.pending(41,1).statusCode.value())
    }
}
