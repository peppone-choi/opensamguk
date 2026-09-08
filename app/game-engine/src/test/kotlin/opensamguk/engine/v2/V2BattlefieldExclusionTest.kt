package opensamguk.engine.v2

import opensamguk.common.wire.*
import opensamguk.common.world.WorldId
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.*
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import java.time.Instant
import kotlin.test.*

class V2BattlefieldExclusionTest {
    private fun world(): InMemoryTurnWorld {
        val hash = "a".repeat(64)
        return InMemoryTurnWorld(WorldSnapshot(
            TurnWorldState(1,200,1,60,Instant.EPOCH,config=mapOf("mapName" to "han-world-v3")),
            worldId=WorldId(1),
            generals=listOf(TurnGeneral(id=10,name="장수",nationId=1,cityId=405,troopId=0,stats=GeneralStats(80,80,80),experience=0,dedication=0,officerLevel=0,turnTime=Instant.EPOCH,crew=1000)),
            cities=listOf(City(405,"당양",1,5),City(406,"이웃",1,5)),
            generalPositionSnapshot=GeneralPositionSnapshot("r1",hash,setOf("45776"),emptySet(),
                listOf(GeneralPositionState("r1",hash,10,StrategicNodeRef.LandProvince("45776"),1,
                    BattlefieldPresence("changban","b".repeat(64),405)))),
        ))
    }
    private fun assertDenied(result: TurnDaemonCommandResult) {
        val denial = assertIs<CommandLifecycleResult>(result)
        assertFalse(denial.ok)
        assertEquals("BATTLEFIELD_LOCATION",denial.code)
    }
    @Test fun `deployed actor cannot recruit city garrison before ledger access`() {
        val world=world();val recorder=ChangeRecorder();val ledger=mock(V2CityLedgerStore::class.java)
        val before=world.getGeneralById(10)
        assertDenied(V2GarrisonRecruitHandler(world,recorder,ledger).handle(CityGarrisonRecruit(generalId=10,cityId=405,amount=100)))
        verifyNoInteractions(ledger)
        assertFalse(recorder.isDirty);assertEquals(before,world.getGeneralById(10))
    }
    @Test fun `deployed actor cannot escort transport from compatibility city`() {
        val world=world();val recorder=ChangeRecorder();val ledger=mock(V2CityLedgerStore::class.java)
        assertDenied(V2CityTransportHandler(world,recorder,ledger) { error("must not resolve a route") }
            .handle(CityTransport(generalId=10,fromCityId=405,toCityId=406,gold=1)))
        verifyNoInteractions(ledger);assertFalse(recorder.isDirty)
    }
    @Test fun `shared dispatcher rejects both immediate city actions even without ledger`() {
        val world=world();val recorder=ChangeRecorder()
        val dispatcher=TurnDaemonCommandDispatcher(world,recorder,
            mock(opensamguk.infra.read.AuctionRepository::class.java),
            mock(opensamguk.infra.read.AuctionBidRepository::class.java),
            mock(opensamguk.infra.read.BoardPostRepository::class.java),v2CityLedger=null)
        assertDenied(assertNotNull(dispatcher.dispatch(CityGarrisonRecruit(generalId=10,cityId=405,amount=100))))
        assertDenied(assertNotNull(dispatcher.dispatch(CityTransport(generalId=10,fromCityId=405,toCityId=406,gold=1))))
        assertFalse(recorder.isDirty)
    }
}
