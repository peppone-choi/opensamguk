package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef

class HwihaLegacyStratagemHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val administrative = fixture.bundle.projection.administrativeCountyIds
    private val sourceId = administrative.sorted().first { source ->
        fixture.bundle.cityConst.byId(source)!!.path.keys.any { it in administrative }
    }
    private val targetId = fixture.bundle.cityConst.byId(sourceId)!!.path.keys.first { it in administrative }
    private val province = fixture.bundle.projection.bindingsByCityId[sourceId]!!.landProvinceId!!
    private fun stock(id: Int) = CountyWarehouse(id, 0,
        Resources(money = 1000, grain = 1000, iron = 1000, timber = 1000, horses = 1000)).toMetaValue()
    private fun world() = fixture.world(listOf(fixture.person(501, 1, sourceId, userId = "42") to
        StrategicNodeRef.LandProvince(province)),
        nations = listOf(Nation(1, "N1", "#111111"), Nation(2, "N2", "#222222"), Nation(3, "N3", "#333333")),
        wars = listOf(1 to 2, 2 to 3),
        cityChanges = { city -> when (city.id) {
            sourceId -> city.copy(nationId = 1, meta = city.meta +
                (CountyWarehouse.META_KEY to stock(city.id)))
            targetId -> city.copy(nationId = 2, meta = city.meta +
                (CountyWarehouse.META_KEY to stock(city.id)))
            else -> city
        } })
    private fun args(inputId: String) = when (inputId) {
        HwihaLegacyStratagemInput.LAST_STAND -> "{}"
        HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> """{"firstNationId":2,"secondNationId":3}"""
        in HwihaLegacyStratagemInput.OWN_COUNTY_IDS -> """{"targetCountyId":$sourceId}"""
        else -> """{"targetCountyId":$targetId}"""
    }

    @Test fun `all twelve stratagems reject before card ownership is implemented`() {
        for (inputId in HwihaLegacyStratagemInput.INPUT_IDS.sorted()) {
            val world = world()
            val handler = HwihaCourtHandler(world, ChangeRecorder(), HwihaDomesticContext(cityConst = fixture.bundle.cityConst))
            val submitted = handler.handle(TurnDaemonCommand.ImmediateInput("play-$inputId", 501, 42, inputId, args(inputId)))
            assertEquals(InputRejection.NOT_DELIVERED.name, submitted.code, inputId)
            handler.onIssuerTurn(501)
            assertTrue(handler.takeExecutions().isEmpty(), inputId)
            assertTrue(HwihaLegacyStratagemStock.forPhase(world.getGeneralById(501)!!.meta,
                HwihaPhase(200, 1, 1)).available(inputId), inputId)
        }
    }

    @Test fun `rejected steal does not move money`() {
        val world = world()
        val handler = HwihaCourtHandler(world, ChangeRecorder(), HwihaDomesticContext(cityConst = fixture.bundle.cityConst))
        val rejected = handler.handle(TurnDaemonCommand.ImmediateInput("steal", 501, 42,
            "stratagem.steal", args("stratagem.steal")))
        assertEquals(InputRejection.NOT_DELIVERED.name, rejected.code)
        handler.onIssuerTurn(501)
        assertEquals(1000, CountyWarehouse.read(world.getCityById(sourceId)!!.meta, sourceId)!!.stock.money)
        assertEquals(1000, CountyWarehouse.read(world.getCityById(targetId)!!.meta, targetId)!!.stock.money)
        assertTrue(handler.takeExecutions().isEmpty())
    }
}
