package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

class HwihaNpcDispatchSelectorTest {
    private fun person(id: Int, nation: Int = 0) = TurnGeneral(
        id = id, name = "G$id", nationId = nation, cityId = 1, troopId = 0,
        stats = GeneralStats(70, 70, 70, politics = 70, charm = 70),
        experience = 0, dedication = 0, officerLevel = if (nation > 0) 12 else 0,
        npcState = 2, userId = null, gold = 100, rice = 200, crew = 0, turnTime = Instant.EPOCH,
        meta = mapOf("hwihaLord" to (nation > 0), HwihaPersonPolicyState.META_KEY to
            HwihaPersonPolicyState(30, true, "synthetic-test", "v1", id).toMetaValue()))

    private fun world(issuer: TurnGeneral = person(10, 1), reverse: Boolean = false, countyIds: Set<Int> = setOf(1,2,3)): InMemoryTurnWorld {
        val persons = listOf(issuer) + listOf(1, 2).map { person(it, 1).copy(userId = "42", officerLevel = 1,
            meta = person(it, 1).meta + ("hwihaLord" to false)) }
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
    private fun select(world: InMemoryTurnWorld) = HwihaNpcDispatchSelector.select(world,10,HwihaDispatchExecutor(world,ChangeRecorder()))

    @Test fun `first valid pair is deterministic and generates no human execution result`() {
        for (reverse in listOf(false,true)) {
            val world=world(reverse=reverse)
            assertEquals(DispatchRequest(10,1,1),select(world))
            val handler=HwihaCourtHandler(world,ChangeRecorder())
            handler.onIssuerTurn(10)
            assertEquals("npc-dispatch:1:10:1:200:1:1",HwihaDispatchState.read(world.getGeneralById(1)!!.meta)!!.dispatchId)
            assertNull(HwihaDispatchState.read(world.getGeneralById(2)!!.meta))
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
        for (key in listOf(HwihaDispatchState.META_KEY,HwihaCountyAssignment.META_KEY)) {
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
            val phase = HwihaPhase(200,1,1)
            val first = world.getGeneralById(1)!!
            world.applyGeneralDirtyFree(first.copy(meta = first.meta + (HwihaDispatchState.META_KEY to
                HwihaDispatchState("old",10,1,1,1,phase,phase.plus(12),status).toMetaValue())))
            val second = world.getGeneralById(2)!!
            world.applyGeneralDirtyFree(second.copy(meta = second.meta + (HwihaCountyAssignment.META_KEY to
                HwihaCountyAssignment("assigned",10,1,2).toMetaValue())))
            assertNull(select(world), "automatic repeat after $status")
        }
    }

    @Test fun `non human and non direct targets are never selected`() {
        val world=world()
        for(id in listOf(1,2)) {
            val target=world.getGeneralById(id)!!
            world.applyGeneralDirtyFree(target.copy(userId=null))
        }
        assertNull(select(world))
        val target=world.getGeneralById(1)!!
        world.applyGeneralDirtyFree(target.copy(userId="42",nationId=0))
        assertNull(select(world))
    }
    @Test fun `duplicate bindings exclude candidate even if one belongs to issuer`() {
        val world=world()
        world.createRetainer(Retainer(99,20,"EXISTING",1,"G1","guest"))
        assertEquals(DispatchRequest(10,2,1),select(world))
    }
    @Test fun `only neutral administrative county means no assignment`() {
        val world=world(countyIds=setOf(3))
        assertNull(select(world))
        HwihaCourtHandler(world,ChangeRecorder()).onIssuerTurn(10)
        assertTrue(world.peekLogs().isEmpty())
        assertNull(HwihaDispatchState.read(world.getGeneralById(1)!!.meta))
    }
    @Test fun `friendly strategic sites without administrative counties mean no assignment`() {
        val world=world(countyIds=emptySet())
        assertNull(select(world))
        HwihaCourtHandler(world,ChangeRecorder()).onIssuerTurn(10)
        assertTrue(world.peekLogs().isEmpty())
        assertNull(HwihaDispatchState.read(world.getGeneralById(1)!!.meta))
    }
    @Test fun `explicit queue suppresses automatic selection even when it will be rejected`() {
        val issuer=person(10,1).copy(meta=person(10,1).meta+(HwihaQueuedDispatch.META_KEY to
            HwihaQueuedDispatch("human-request",40,1,1).toMetaValue()))
        val world=world(issuer)
        val handler=HwihaCourtHandler(world,ChangeRecorder())
        handler.onIssuerTurn(10)
        assertNull(HwihaDispatchState.read(world.getGeneralById(1)!!.meta))
        assertEquals("FORBIDDEN",handler.takeExecutions().single().result.code)
    }
}
