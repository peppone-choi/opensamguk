package opensamguk.engine.hwiha

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEntry
import opensamguk.logic.renown.RenownEventKind
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.world.*

class HwihaDispatchExecutorTest {
    private fun person(id: Int, lord: Boolean = false) = TurnGeneral(id=id, name="G$id", nationId=1, cityId=10,
        userId=if(lord) null else "42", npcState=2, troopId=0, stats=GeneralStats(70,70,70), experience=500,
        dedication=600, officerLevel=0, gold=1000, rice=2000, crew=300, turnTime=Instant.EPOCH,
        meta=mapOf("hwihaLord" to lord, "keep" to "preserved", HwihaPersonPolicyState.META_KEY to
            HwihaPersonPolicyState(30,true,"synthetic-test","1",id).toMetaValue()))
    private fun world(): InMemoryTurnWorld {
        val hash="b".repeat(64)
        val positions=listOf(1,2).fold(GeneralPositionSnapshot("r1",hash,setOf("p1"),emptySet())) { s,id ->
            s.withState(GeneralPositionState("r1",hash,id,StrategicNodeRef.LandProvince("p1"),1)) }
        return InMemoryTurnWorld(WorldSnapshot(worldId=WorldId(1),
            state=TurnWorldState(1,200,12,3600,Instant.EPOCH,config=mapOf("ruleProfile" to "HWIHA","mapName" to "han-world-v3")),
            generals=listOf(person(1,true),person(2)), nations=listOf(Nation(1,"N1","#000")),
            cities=listOf(City(10,"縣",1,1)), retainers=listOf(Retainer(4,1,"EXISTING",2,"G2","guest",loyalty=50)),
            generalPositionSnapshot=positions, cityLandProvinceById=mapOf(10 to "p1"), administrativeCountyIds=setOf(10)))
    }
    private fun issue(world: InMemoryTurnWorld, recorder: ChangeRecorder) = assertIs<DispatchExecution.Applied>(
        HwihaDispatchExecutor(world,recorder).issue("dispatch-1",DispatchRequest(1,2,10))).dispatch
    @Test fun `owned NPC dispatch and acceptance preserve position time reservations and assets`() {
        val world=world(); val recorder=ChangeRecorder(); val before=world.getGeneralById(2)!!
        val position=world.positionOf(2); val cards=world.listRetainers()
        val pending=issue(world,recorder)
        assertEquals(DispatchStatus.PENDING,pending.status)
        val accepted=assertIs<DispatchExecution.Applied>(HwihaDispatchExecutor(world,recorder)
            .reply(DispatchReplyRequest(2,pending.dispatchId,true)))
        assertEquals(DispatchStatus.ACCEPTED,accepted.dispatch.status)
        val after=world.getGeneralById(2)!!
        assertEquals(before,after.copy(meta=before.meta)); assertEquals(position,world.positionOf(2)); assertEquals(cards,world.listRetainers())
        assertEquals(10,HwihaCountyAssignment.read(after.meta)!!.countyId)
        val payload=DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState())
        assertEquals(listOf(2),payload.updatedGenerals.map { it.id })
        assertEquals(DispatchFailure.ALREADY_RESOLVED,assertIs<DispatchExecution.Rejected>(
            HwihaDispatchExecutor(world,recorder).reply(DispatchReplyRequest(2,pending.dispatchId,false))).reason)
        assertEquals(after,world.getGeneralById(2))
    }
    @Test fun `refusal consumes loyalty at once and tallies renown for the monthly assessment`() {
        val world=world(); val recorder=ChangeRecorder(); val before=world.getGeneralById(2)!!
        issue(world,recorder)
        val executor=HwihaDispatchExecutor(world,recorder)
        assertEquals(DispatchStatus.REFUSED,assertIs<DispatchExecution.Applied>(executor.reply(DispatchReplyRequest(2,"dispatch-1",false))).dispatch.status)
        assertEquals(45,world.getRetainerById(4)!!.loyalty)
        // Renown is not charged here any more (2026-09-23): the assessment applies the tallied -4 once.
        assertEquals(30,HwihaPersonPolicyState.read(world.getGeneralById(2)!!.meta)!!.renownCapacity)
        assertEquals(listOf(RenownEntry(RenownEventKind.DISPATCH_REFUSAL,"0200-12",RenownEventSource.DISPATCH_REFUSAL)),
            RenownEvents.entries(world.getGeneralById(2)!!.meta))
        assertEquals(before,world.getGeneralById(2)!!.copy(meta=before.meta))
        assertIs<DispatchExecution.Rejected>(executor.reply(DispatchReplyRequest(2,"dispatch-1",false)))
        assertEquals(45,world.getRetainerById(4)!!.loyalty)
    }
    @Test fun `a second refusal in the same month costs loyalty again but tallies renown once`() {
        val world=world(); val recorder=ChangeRecorder(); val executor=HwihaDispatchExecutor(world,recorder)
        issue(world,recorder); assertIs<DispatchExecution.Applied>(executor.reply(DispatchReplyRequest(2,"dispatch-1",false)))
        assertIs<DispatchExecution.Applied>(executor.issue("dispatch-2",DispatchRequest(1,2,10)))
        assertIs<DispatchExecution.Applied>(executor.reply(DispatchReplyRequest(2,"dispatch-2",false)))
        assertEquals(40,world.getRetainerById(4)!!.loyalty)
        assertEquals(1,RenownEvents.entries(world.getGeneralById(2)!!.meta).size)
        val renownRecords=world.peekLogs().filter { it.eventKind==HwihaRecordKind.RENOWN_EVENT }
        assertEquals(listOf(2),renownRecords.map { it.generalId },"only the newly tallied event is announced")
        // Next month: a new refusal is a new event.
        world.setCurrentDate(201,1,1)
        assertIs<DispatchExecution.Applied>(executor.issue("dispatch-3",DispatchRequest(1,2,10)))
        assertIs<DispatchExecution.Applied>(executor.reply(DispatchReplyRequest(2,"dispatch-3",false)))
        assertEquals(listOf("0200-12","0201-01"),RenownEvents.entries(world.getGeneralById(2)!!.meta).map { it.stamp })
    }
    @Test fun `dispatch records reach only issuer and target with kinds and refs`() {
        val world=world(); val recorder=ChangeRecorder(); val executor=HwihaDispatchExecutor(world,recorder)
        world.applyGeneralDirtyFree(world.getGeneralById(1)!!.copy(userId="41"))  // a player lord keeps records
        issue(world,recorder); executor.reply(DispatchReplyRequest(2,"dispatch-1",true))
        val records=world.peekLogs().map { Triple(it.generalId,it.eventKind,(it.meta?.get(HwihaRecordKind.REFS_META_KEY) as Map<*,*>)["dispatchId"]) }
        assertEquals(listOf(
            Triple(2,HwihaRecordKind.DISPATCH_RECEIVED,"dispatch-1"), Triple(1,HwihaRecordKind.DISPATCH_ISSUED,"dispatch-1"),
            Triple(2,HwihaRecordKind.DISPATCH_ACCEPTED,"dispatch-1"), Triple(1,HwihaRecordKind.DISPATCH_ACCEPTED,"dispatch-1"),
        ),records)
        assertTrue(world.peekLogs().all { it.scope=="general" && it.category=="action" })
        // The kind reaches the flush row (log_entry.event_kind), refs stay in meta.
        val rows=DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState()).logEntries
        assertEquals(records.map { it.second },rows.map { it.eventKind })
        assertEquals("dispatch-1",(rows.first().meta[HwihaRecordKind.REFS_META_KEY] as Map<*,*>)["dispatchId"])
        assertEquals(listOf(12),rows.map { it.month }.distinct()); assertEquals(listOf(1),rows.map { it.phase }.distinct())
    }
    @Test fun `an NPC lord keeps no dispatch record`() {
        val world=world(); val recorder=ChangeRecorder()
        issue(world,recorder); HwihaDispatchExecutor(world,recorder).reply(DispatchReplyRequest(2,"dispatch-1",false))
        assertEquals(setOf(2),world.peekLogs().map { it.generalId }.toSet())
    }
    @Test fun `deadline is twelve world phases across year and late refusal accepts without cost`() {
        val world=world(); val recorder=ChangeRecorder(); val pending=issue(world,recorder)
        assertEquals(HwihaPhase(201,4,1),pending.dueAt)
        val executor=HwihaDispatchExecutor(world,recorder)
        world.setCurrentDate(201,3,3); assertTrue(executor.expireDue().isEmpty())
        world.setCurrentDate(201,4,1)
        assertEquals(DispatchStatus.ACCEPTED,assertIs<DispatchExecution.Applied>(executor.reply(DispatchReplyRequest(2,"dispatch-1",false))).dispatch.status)
        assertEquals(50,world.getRetainerById(4)!!.loyalty); assertTrue(executor.expireDue().isEmpty())
    }
    @Test fun `changed relationship cancels expiry without relocating or penalizing target`() {
        val world=world(); val recorder=ChangeRecorder(); val before=world.getGeneralById(2)!!
        issue(world,recorder); world.removeRetainer(4); world.setCurrentDate(201,4,1)
        val executor=HwihaDispatchExecutor(world,recorder)
        assertIs<DispatchExecution.Rejected>(executor.expireDue().single())
        assertEquals(DispatchStatus.CANCELLED,HwihaDispatchState.read(world.getGeneralById(2)!!.meta)!!.status)
        assertEquals(before,world.getGeneralById(2)!!.copy(meta=before.meta)); assertTrue(executor.expireDue().isEmpty())
    }
    @Test fun `pending dispatch cannot be overwritten and missing refusal policy makes no partial writes`() {
        val world=world(); val recorder=ChangeRecorder(); issue(world,recorder)
        val target=world.getGeneralById(2)!!; world.applyGeneralDirtyFree(target.copy(meta=target.meta-HwihaPersonPolicyState.META_KEY))
        val before=world.getGeneralById(2); val executor=HwihaDispatchExecutor(world,recorder)
        assertEquals(DispatchFailure.ALREADY_PENDING,assertIs<DispatchExecution.Rejected>(executor.issue("second",DispatchRequest(1,2,10))).reason)
        assertEquals(DispatchFailure.POLICY_UNAVAILABLE,assertIs<DispatchExecution.Rejected>(executor.reply(DispatchReplyRequest(2,"dispatch-1",false))).reason)
        assertEquals(before,world.getGeneralById(2)); assertEquals(50,world.getRetainerById(4)!!.loyalty)
    }
    @Test fun `unanswered dispatch accepts at deadline once without changing personal time`() {
        val world=world(); val recorder=ChangeRecorder(); val before=world.getGeneralById(2)!!
        issue(world,recorder); world.setCurrentDate(201,4,1)
        val executor=HwihaDispatchExecutor(world,recorder)
        assertEquals(DispatchStatus.ACCEPTED,assertIs<DispatchExecution.Applied>(executor.expireDue().single()).dispatch.status)
        assertEquals(before,world.getGeneralById(2)!!.copy(meta=before.meta))
        assertEquals(50,world.getRetainerById(4)!!.loyalty)
        assertEquals(10,HwihaCountyAssignment.read(world.getGeneralById(2)!!.meta)!!.countyId)
        assertTrue(executor.expireDue().isEmpty())
    }

}
