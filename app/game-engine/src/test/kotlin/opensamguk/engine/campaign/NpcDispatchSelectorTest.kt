package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class NpcDispatchSelectorTest {
    private fun person(id: Int, nation: Int = 0) = TurnGeneral(
        id = id, name = "G$id", nationId = nation, cityId = 1, troopId = 0,
        stats = GeneralStats(70, 70, 70, politics = 70, charm = 70),
        experience = 0, dedication = 0, officerLevel = if (nation > 0) 12 else 0,
        npcState = 2, userId = null, gold = 100, rice = 200, crew = 0, turnTime = Instant.EPOCH,
        meta = mapOf("lord" to (nation > 0), PersonPolicyState.META_KEY to
            PersonPolicyState(30, true, "synthetic-test", "v1", id).toMetaValue()))

    private fun world(issuer: TurnGeneral = person(10, 1), reverse: Boolean = false, countyIds: Set<Int> = setOf(1,2,3)): InMemoryTurnWorld {
        val persons = listOf(issuer) + listOf(1, 2).map { person(it, 1).copy(userId = "42", officerLevel = 1,
            meta = person(it, 1).meta + ("lord" to false)) }
        return InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, Instant.EPOCH,
                config = mapOf("mapName" to "han-world-v3", "ruleProfile" to "HWIHA")), worldId = WorldId(1),
            generals = if (reverse) persons.reversed() else persons,
            cities = listOf(City(2,"C2",1,1), City(1,"C1",1,1), City(3,"neutral",0,1)),
            administrativeCountyIds = countyIds, nations = listOf(Nation(1,"N1","#000")),
            retainers = listOf(1,2).map { Retainer(it,10,"EXISTING",it,"G$it","guest") },
            generalPositionSnapshot = persons.fold(GeneralPositionSnapshot("r1", "a".repeat(64), setOf("p1"), emptySet())) { positions, person ->
                positions.withState(GeneralPositionState("r1", "a".repeat(64), person.id, StrategicNodeRef.LandProvince("p1"), 1))
            }, cityLandProvinceById = mapOf(1 to "p1",2 to "p1",3 to "p1")))
    }
    private fun select(world: InMemoryTurnWorld) = NpcDispatchSelector.select(world,10,DispatchExecutor(world,ChangeRecorder()))

    @Test fun `first valid pair is deterministic and generates no human execution result`() {
        for (reverse in listOf(false,true)) {
            val world=world(reverse=reverse)
            assertEquals(DispatchRequest(10,1,1),select(world))
            val handler=CourtHandler(world,ChangeRecorder())
            handler.onIssuerTurn(10)
            assertEquals("npc-dispatch:1:10:1:200:1:1",DispatchState.read(world.getGeneralById(1)!!.meta)!!.dispatchId)
            assertNull(DispatchState.read(world.getGeneralById(2)!!.meta))
            assertTrue(handler.takeExecutions().isEmpty())
            assertEquals(1,world.peekLogs().single().generalId)
            assertEquals("담당 장수가 없는 아군 현의 첫 부임 대상으로 발령되었습니다.",world.peekLogs().single().text)
            assertEquals(opensamguk.logic.input.RecordKind.DISPATCH_RECEIVED,world.peekLogs().single().eventKind)
        }
    }
    @Test fun `owned special undeclared and landless issuers are excluded`() {
        val variants=listOf(0,1,3,5,6,7,9).map { person(10,1).copy(npcState=it) } +
            listOf("42","opaque").map { person(10,1).copy(userId=it) } +
            listOf(person(10,0),person(10,1).copy(meta=emptyMap()))
        for (issuer in variants) assertNull(select(world(issuer)))
    }
    @Test fun `any dispatch or assignment metadata prevents repeat including malformed and closed history`() {
        for (key in listOf(DispatchState.META_KEY,CountyAssignment.META_KEY)) {
            val world=world()
            for(id in listOf(1,2)) {
                val person=world.getGeneralById(id)!!
                world.applyGeneralDirtyFree(person.copy(meta=person.meta+(key to null)))
            }
            assertNull(select(world))
        }
    }
    @Test fun `closed dispatch history and accepted assignment prevent automatic reissue`() {
        for (status in listOf(DispatchStatus.REFUSED, DispatchStatus.CANCELLED, DispatchStatus.ACCEPTED)) {
            val world = world()
            val phase = Phase(200,1,1)
            val first = world.getGeneralById(1)!!
            world.applyGeneralDirtyFree(first.copy(meta = first.meta + (DispatchState.META_KEY to
                DispatchState("old",10,1,1,1,phase,phase.plus(12),status).toMetaValue())))
            val second = world.getGeneralById(2)!!
            world.applyGeneralDirtyFree(second.copy(meta = second.meta + (CountyAssignment.META_KEY to
                CountyAssignment("assigned",10,1,2).toMetaValue())))
            assertNull(select(world), "automatic repeat after $status")
        }
    }

    @Test fun `unowned NPC direct cards are selected but non direct targets are not`() {
        val world=world()
        for(id in listOf(1,2)) {
            val target=world.getGeneralById(id)!!
            world.applyGeneralDirtyFree(target.copy(userId=null))
        }
        assertEquals(DispatchRequest(10,1,1),select(world))
        val target=world.getGeneralById(1)!!
        world.applyGeneralDirtyFree(target.copy(nationId=0))
        world.removeRetainer(2)
        assertNull(select(world))
    }

    @Test fun `NPC order waits twelve phases then accepts and only repeats for another vacant county`() {
        val world = world()
        world.removeRetainer(2)
        for (id in listOf(1,2)) {
            val target = world.getGeneralById(id)!!
            world.applyGeneralDirtyFree(target.copy(userId = null))
        }
        val recorder = ChangeRecorder()
        val executor = DispatchExecutor(world, recorder)
        val request = assertNotNull(select(world))
        assertEquals(DispatchRequest(10,1,1), request)
        assertEquals(DispatchFailure.TARGET_NOT_HUMAN,
            assertIs<DispatchExecution.Rejected>(executor.issue("manual", request)).reason)
        assertIs<DispatchExecution.Applied>(executor.issue("npc-first", request,
            targetPolicy = DispatchTargetPolicy.NPC_AUTOMATED))
        assertEquals(DispatchFailure.TARGET_NOT_HUMAN,
            assertIs<DispatchExecution.Rejected>(executor.reply(DispatchReplyRequest(1,"npc-first",true))).reason)
        assertNull(select(world), "pending order cannot be repeated")
        world.setCurrentDate(200, 4, 1)
        assertNull(select(world), "deadline has not arrived")
        world.setCurrentDate(201, 1, 1)
        assertEquals(DispatchStatus.ACCEPTED,
            assertIs<DispatchExecution.Applied>(executor.expireDue().single()).dispatch.status)
        assertEquals(1, CountyAssignment.read(world.getGeneralById(1)!!.meta)?.countyId)
        assertNull(select(world), "a valid assignment stays in place")
        val assigned = world.getGeneralById(1)!!
        world.applyGeneralDirtyFree(assigned.copy(meta = assigned.meta - CountyAssignment.META_KEY))
        assertEquals(DispatchRequest(10,1,2), select(world), "the last county is not repeated")
        assertIs<DispatchExecution.Applied>(executor.issue("npc-second", assertNotNull(select(world)),
            targetPolicy = DispatchTargetPolicy.NPC_AUTOMATED))
        assertNull(select(world), "a second pending order cannot be repeated")
    }
    @Test fun `duplicate bindings exclude candidate even if one belongs to issuer`() {
        val world=world()
        world.createRetainer(Retainer(99,20,"EXISTING",1,"G1","guest"))
        assertEquals(DispatchRequest(10,2,1),select(world))
    }
    @Test fun `only neutral administrative county means no assignment`() {
        val world=world(countyIds=setOf(3))
        assertNull(select(world))
        CourtHandler(world,ChangeRecorder()).onIssuerTurn(10)
        assertTrue(world.peekLogs().isEmpty())
        assertNull(DispatchState.read(world.getGeneralById(1)!!.meta))
    }
    @Test fun `friendly strategic sites without administrative counties mean no assignment`() {
        val world=world(countyIds=emptySet())
        assertNull(select(world))
        CourtHandler(world,ChangeRecorder()).onIssuerTurn(10)
        assertTrue(world.peekLogs().isEmpty())
        assertNull(DispatchState.read(world.getGeneralById(1)!!.meta))
    }
    @Test fun `explicit queue suppresses automatic selection even when it will be rejected`() {
        val issuer=person(10,1).copy(meta=person(10,1).meta+(QueuedDispatch.META_KEY to
            QueuedDispatch("human-request",40,1,1).toMetaValue()))
        val world=world(issuer)
        val handler=CourtHandler(world,ChangeRecorder())
        handler.onIssuerTurn(10)
        assertNull(DispatchState.read(world.getGeneralById(1)!!.meta))
        assertEquals("FORBIDDEN",handler.takeExecutions().single().result.code)
    }
}
