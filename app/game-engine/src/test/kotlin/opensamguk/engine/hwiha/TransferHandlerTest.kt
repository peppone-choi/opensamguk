package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.logic.input.*

class HwihaTransferHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()

    @Test fun `gift debits only the owner and credits a co-located recipient once`() {
        val route = fixture.route()
        val donor = fixture.person(1101, 1, route.startCity, userId = "42").copy(gold = 120)
        val recipient = fixture.person(1102, 1, route.startCity, userId = "43").copy(gold = 10)
        val world = fixture.world(listOf(donor to route.start, recipient to route.start))
        val handler = HwihaTransferHandler(world, ChangeRecorder(), HwihaDomesticContext())
        val json = """{"targetGeneralId":1102,"resource":"MONEY","amount":40}"""
        val applied = assertIs<HwihaTurnOutcome.Applied>(
            handler.handle(TransferInput.GIFT, donor.id, json, "gift-1101", 42))
        assertEquals(80, world.getGeneralById(donor.id)!!.gold)
        assertEquals(50, world.getGeneralById(recipient.id)!!.gold)
        assertEquals(donor.experience, world.getGeneralById(donor.id)!!.experience)
        assertEquals(applied, handler.handle(TransferInput.GIFT, donor.id, json, "gift-1101", 42))
        assertEquals(TransferFailure.ALREADY_PROCESSED.name,
            assertIs<HwihaTurnOutcome.Rejected>(handler.handle(TransferInput.GIFT,
                donor.id, json.replace("40", "20"), "gift-other", 42)).code)
    }

    @Test fun `donation cannot move resources into an unused nation treasury`() {
        val route = fixture.route()
        val donor = fixture.person(1111, 1, route.startCity, userId = "42").copy(rice = 80)
        val world = fixture.world(listOf(donor to route.start),
            nations = listOf(Nation(1, "N1", "#111111"), Nation(2, "N2", "#222222", rice = 7)),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 2) else city })
        val json = """{"resource":"GRAIN","amount":30}"""
        assertEquals(InputRejection.NOT_DELIVERED.name,
            assertIs<HwihaTurnOutcome.Rejected>(HwihaTransferHandler(world, ChangeRecorder(), HwihaDomesticContext())
                .handle(TransferInput.DONATE, donor.id, json, "donate-1111", 42)).code)
        assertEquals(80, world.getGeneralById(donor.id)!!.rice)
        assertEquals(7, world.getNationById(2)!!.rice)
        assertEquals(0, world.getNationById(1)!!.rice)
    }
}
