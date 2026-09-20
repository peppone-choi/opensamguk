package opensamguk.engine.intake

import kotlin.test.*
import java.time.Instant
import opensamguk.common.wire.*
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.world.*

class HwihaDeploymentEditGuardTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")
    private val corps = HwihaDeployedCorps("deployed",1,2,4,1,listOf(7),HwihaPhase(200,1,1))
    private fun world(profile: String = "HWIHA", deployment: Any? = HwihaDeploymentState(listOf(corps)).toMetaValue(), absent: Boolean = false): InMemoryTurnWorld {
        val generals = listOf(1,2,3).map { id -> TurnGeneral(id=id, name="G$id", nationId=1, cityId=10, troopId=0, stats=GeneralStats(70,70,70), experience=0, dedication=0, officerLevel=0,
            npcState=if(id==1) 0 else 2, crew=1000,rice=2000,crewTypeId=1100,turnTime=now,
            meta=if(id==1 && !absent) mapOf(HwihaDeploymentState.META_KEY to deployment) else emptyMap()) }
        val positions = generals.fold(GeneralPositionSnapshot("qa","a".repeat(64),setOf("p"),emptySet())) { acc,g ->
            acc.withState(GeneralPositionState("qa","a".repeat(64),g.id,StrategicNodeRef.LandProvince("p"),1)) }
        return InMemoryTurnWorld(WorldSnapshot(TurnWorldState(1,200,1,3600,now,
            config=mapOf("ruleProfile" to profile,"mapName" to "han-world-v3")),worldId=WorldId(1),generals=generals,
            generalPositionSnapshot=positions,cityLandProvinceById=mapOf(10 to "p"),
            retainers=listOf(Retainer(4,1,"EXISTING",2,"G2","lieutenant"),Retainer(5,1,"EXISTING",3,"G3","lieutenant")),
            bugoks=listOf(Bugok(7,1,"B",200,1100,70,60,provisions=300,commanderRetainerId=4,commanderBonusApplied=true))))
    }
    private fun operations() = listOf<(RetainerHandler)->TurnDaemonCommandResult>(
        { it.handleBugokDisband(TurnDaemonCommand.BugokDisband(generalId=1,bugokId=7)) },
        { it.handleBugokAssignCommander(TurnDaemonCommand.BugokAssignCommander(generalId=1,bugokId=7,retainerId=5)) },
        { it.handleRelease(TurnDaemonCommand.RetainerRelease(generalId=1,retainerId=4)) })
    private fun unchanged(w: InMemoryTurnWorld, operation: (RetainerHandler)->TurnDaemonCommandResult) {
        val generals=w.listGenerals();val units=w.listBugoks();val cards=w.listRetainers();val recorder=ChangeRecorder()
        val result=assertIs<RetainerActionResult>(operation(RetainerHandler(w,recorder,nowProvider={now})))
        assertFalse(result.ok);assertTrue(result.reason.orEmpty().contains("출전"))
        assertEquals(generals,w.listGenerals());assertEquals(units,w.listBugoks());assertEquals(cards,w.listRetainers())
        assertTrue(recorder.generalPatches().isEmpty())
    }
    @Test fun `deployed unit disband commander change and commander release preserve all resources`() {
        operations().forEach { unchanged(world(),it) }
    }
    @Test fun `same commander is an allowed no op`() {
        val w=world();val before=w.listBugoks()
        val result=RetainerHandler(w,ChangeRecorder(),nowProvider={now}).handleBugokAssignCommander(
            TurnDaemonCommand.BugokAssignCommander(generalId=1,bugokId=7,retainerId=4))
        assertTrue(assertIs<RetainerActionResult>(result).ok);assertEquals(before,w.listBugoks())
    }
    @Test fun `undeployed and SAMMO existing edits remain available`() {
        for(profile in listOf("HWIHA","SAMMO")) for(op in operations()) {
            val w=world(profile,absent=profile=="HWIHA")
            assertTrue(assertIs<RetainerActionResult>(op(RetainerHandler(w,ChangeRecorder(),nowProvider={now}))).ok)
        }
    }
    @Test fun `malformed and foreign owner binding cannot become an empty deployment`() {
        for(raw in listOf(null,emptyMap<String,Any>(),HwihaDeploymentState(listOf(corps.copy(ownerGeneralId=3))).toMetaValue()))
            operations().forEach { unchanged(world(deployment=raw),it) }
    }
}
