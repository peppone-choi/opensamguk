package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.DispatchReadController
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.world.HanStrategicRouteProjection
import java.util.Optional

class DispatchPrecheckServiceTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val service = DispatchPrecheckService(generals, retainers, resolver)
    private val now = Phase(200, 1, 1)
    private val dispatch = DispatchState("d1", 1, 2, 1, 7, now, now.plus(12))
    private fun person(id: Int, meta: Map<String, Any?> = emptyMap()) = GeneralReadEntity(
        id = id, name = "G$id", worldId = 1, nationId = 1, userId = (40 + id).toString(), npcState = 2,
        meta = mapOf("hwihaLord" to (id == 1)) + meta)
    private fun setup(pending: Boolean = false, phase: Phase = now): List<GeneralReadEntity> {
        val people = listOf(person(1), person(2, if (pending) mapOf(DispatchState.META_KEY to dispatch.toMetaValue()) else emptyMap()), person(3))
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
            listOf(CityReadEntity(id = 7, name = "C7", worldId = 1, nationId = 1), CityReadEntity(id = 8, worldId = 1, nationId = 1)), artifacts))
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
        val row = service.pending(2,42).dispatches.single()
        assertEquals("G1",row.issuerLabel)
        assertEquals("G2",row.targetLabel)
        assertEquals("C7",row.countyLabel)
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
        assertEquals(DispatchFailure.STATE_UNAVAILABLE, service.pending(1,41).code)
        valid.world.config = mapOf("ruleProfile" to "HWIHA")
        people[2].worldId = 2
        assertEquals(DispatchFailure.STATE_UNAVAILABLE, service.pending(1,41).code)
    }
    @Test fun `controller requires principal and preserves forbidden ownership`() {
        val controller = DispatchReadController(service)
        assertEquals(401, controller.pending(null,1).statusCode.value())
        assertEquals(403, controller.pending(0,1).statusCode.value())
        setup(true)
        assertEquals(403, controller.pending(42,1).statusCode.value())
        assertEquals(200, controller.pending(41,1).statusCode.value())
    }
    @Test fun `options expose only direct human targets and selected administrative own counties`() {
        setup()
        val initial = service.options(1,41)
        assertTrue(initial.result)
        assertEquals(listOf(2), initial.targets.map { it.generalId })
        assertTrue(initial.counties.isEmpty())
        val selected = service.options(1,41,2)
        assertEquals(listOf(7), selected.counties.map { it.countyId })
        assertTrue(selected.counties.single().available)
        assertEquals(DispatchFailure.NOT_DIRECT_RETAINER,service.options(1,41,3).code)
        assertTrue(service.options(1,41,3).targets.isEmpty())
        assertEquals(DispatchFailure.NOT_LORD,service.options(2,42).code)
    }
    @Test fun `options share pending occupancy and live ownership refusal reasons with admission`() {
        val people = setup(true)
        val blocked = service.options(1,41,2).counties.single()
        assertFalse(blocked.available)
        assertEquals(DispatchFailure.ALREADY_PENDING,blocked.code)
        assertEquals(blocked.code!!.message,blocked.reason)
        people[1].meta = mapOf("hwihaLord" to false)
        people[2].meta = mapOf("hwihaLord" to false, CountyAssignment.META_KEY to
            CountyAssignment("other",1,1,7).toMetaValue())
        assertEquals(DispatchFailure.COUNTY_OCCUPIED,service.options(1,41,2).counties.single().code)
        val world = resolver.resolve()!!
        world.cities.single { it.id == 7 }.nationId = 2
        assertTrue(service.options(1,41,2).counties.isEmpty())
    }
    @Test fun `unowned foreign lord and duplicate binding targets are excluded`() {
        val people = setup()
        people[1].userId = null
        assertTrue(service.options(1,41).targets.isEmpty())
        people[1].userId = "42"
        people[1].nationId = 2
        assertTrue(service.options(1,41).targets.isEmpty())
        people[1].nationId = 1
        people[1].meta = mapOf("hwihaLord" to true)
        assertTrue(service.options(1,41).targets.isEmpty())
        people[1].meta = mapOf("hwihaLord" to false)
        val card = retainers.findAll().single()
        `when`(retainers.findAll()).thenReturn(listOf(card,GeneralRetainerReadEntity(worldId=1,id=6,masterGeneralId=1,generalId=2)))
        assertTrue(service.options(1,41).targets.isEmpty())
    }
    @Test fun `queued request is visible only to submitting current owner and blocks all new county options`() {
        val people = setup()
        people[0].meta += QueuedDispatch.META_KEY to QueuedDispatch("q1",41,2,7).toMetaValue()
        assertEquals("q1",service.pending(1,41).queued!!.requestId)
        assertEquals("q1",service.options(1,41,2).queued!!.requestId)
        assertEquals(DispatchFailure.ALREADY_QUEUED,service.options(1,41,2).counties.single().code)
        assertNull(service.pending(2,42).queued)
        people[0].userId = "44"
        assertNull(service.pending(1,44).queued)
        assertNull(service.options(1,44,2).queued)
        assertFalse(service.options(1,44,2).counties.single().available)
        assertFailsWith<DispatchReadForbidden> { service.options(1,41) }
    }
    @Test fun `options unavailable states stay explicit and corrupt queue never returns successful empty data`() {
        val people = setup()
        people[0].meta += QueuedDispatch.META_KEY to null
        assertEquals(DispatchAssessment.Rejected(DispatchFailure.STATE_UNAVAILABLE),
            service.assessDispatch(DispatchRequest(1,2,7),41))
        assertEquals(DispatchFailure.STATE_UNAVAILABLE,service.options(1,41).code)
        assertEquals(DispatchFailure.STATE_UNAVAILABLE,service.pending(1,41).code)
        assertFalse(service.pending(1,41).result)
        `when`(resolver.resolve()).thenThrow(IllegalArgumentException("pin"))
        assertFalse(service.options(1,41).result)
        assertEquals(DispatchFailure.STATE_UNAVAILABLE.message,service.options(1,41).reason)
    }
    @Test fun `options controller checks identity before projection`() {
        val controller = DispatchReadController(service)
        assertEquals(401,controller.options(null,1,null).statusCode.value())
        assertEquals(403,controller.options(0,1,null).statusCode.value())
        verifyNoInteractions(resolver,retainers)
        setup()
        assertEquals(403,controller.options(42,1,2).statusCode.value())
        assertEquals(200,controller.options(41,1,2).statusCode.value())
    }
}
