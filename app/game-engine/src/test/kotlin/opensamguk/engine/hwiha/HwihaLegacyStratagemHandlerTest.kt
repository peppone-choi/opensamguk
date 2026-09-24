package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
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
    private fun stock(id: Int) = HwihaCountyWarehouse(id, 0,
        HwihaResources(money = 1000, grain = 1000, iron = 1000, timber = 1000, horses = 1000)).toMetaValue()
    private fun world() = fixture.world(listOf(fixture.person(501, 1, sourceId, userId = "42") to
        StrategicNodeRef.LandProvince(province)),
        nations = listOf(Nation(1, "N1", "#111111"), Nation(2, "N2", "#222222"), Nation(3, "N3", "#333333")),
        wars = listOf(1 to 2, 2 to 3),
        cityChanges = { city -> when (city.id) {
            sourceId -> city.copy(nationId = 1, meta = city.meta +
                (HwihaCountyWarehouse.META_KEY to stock(city.id)))
            targetId -> city.copy(nationId = 2, meta = city.meta +
                (HwihaCountyWarehouse.META_KEY to stock(city.id)))
            else -> city
        } })
    private fun args(inputId: String) = when (inputId) {
        HwihaLegacyStratagemInput.LAST_STAND -> "{}"
        HwihaLegacyStratagemInput.PROVOKE_RIVALRY -> """{"firstNationId":2,"secondNationId":3}"""
        in HwihaLegacyStratagemInput.OWN_COUNTY_IDS -> """{"targetCountyId":$sourceId}"""
        else -> """{"targetCountyId":$targetId}"""
    }

    @Test fun `every legacy stratagem mode resolves through one queue and consumes its named card`() {
        for (inputId in HwihaLegacyStratagemInput.INPUT_IDS.sorted()) {
            val world = world()
            val handler = HwihaCourtHandler(world, ChangeRecorder(), HwihaDomesticContext(cityConst = fixture.bundle.cityConst))
            val submitted = handler.handle(TurnDaemonCommand.HwihaCourtInput("play-$inputId", 501, 42, inputId, args(inputId)))
            assertTrue(submitted.ok, "$inputId: ${submitted.code}/${submitted.reason}")
            handler.onIssuerTurn(501)
            val execution = handler.takeExecutions().single()
            assertTrue(execution.result.ok, "$inputId: ${execution.result.code}/${execution.result.reason}")
            assertEquals("STRATAGEM", execution.result.commandKind)
            assertEquals(inputId, execution.result.inputResolved?.inputId)
            assertEquals("STRATAGEM", execution.result.inputResolved?.kind)
            val actor = world.getGeneralById(501)!!
            assertFalse(HwihaLegacyStratagemStock.forPhase(actor.meta, HwihaPhase(200, 1, 1)).available(inputId))
            val again = handler.handle(TurnDaemonCommand.HwihaCourtInput("again-$inputId", 501, 42, inputId, args(inputId)))
            assertEquals(HwihaLegacyStratagemFailure.CARD_UNAVAILABLE.name, again.code, inputId)
        }
    }

    @Test fun `steal transfers money after local card cost and failed target pays nothing`() {
        val world = world()
        val handler = HwihaCourtHandler(world, ChangeRecorder(), HwihaDomesticContext(cityConst = fixture.bundle.cityConst))
        val invalid = handler.handle(TurnDaemonCommand.HwihaCourtInput("bad-target", 501, 42,
            "stratagem.steal", """{"targetCountyId":$sourceId}"""))
        assertEquals(HwihaLegacyStratagemFailure.TARGET_UNAVAILABLE.name, invalid.code)
        assertEquals(1000, HwihaCountyWarehouse.read(world.getCityById(sourceId)!!.meta, sourceId)!!.stock.money)
        assertTrue(handler.handle(TurnDaemonCommand.HwihaCourtInput("steal", 501, 42,
            "stratagem.steal", args("stratagem.steal"))).ok)
        handler.onIssuerTurn(501)
        assertTrue(handler.takeExecutions().single().result.ok)
        assertEquals(1000, HwihaCountyWarehouse.read(world.getCityById(sourceId)!!.meta, sourceId)!!.stock.money)
        assertEquals(900, HwihaCountyWarehouse.read(world.getCityById(targetId)!!.meta, targetId)!!.stock.money)
    }
}
