package opensamguk.engine.campaign

import opensamguk.common.wire.AcceptDiplomaticMessageFail
import opensamguk.common.wire.AcceptDiplomaticMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.config.DaemonLoopConfig
import opensamguk.engine.intake.DiplomaticMessageHandler
import opensamguk.engine.intake.MessageSnapshot
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.Retainer
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.domestic.ActivePlacement
import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.PlacementPost
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PlacementTarget
import opensamguk.logic.input.DiplomacyInput
import opensamguk.logic.input.Phase
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.jsonDecode
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CourtOfferPeaceExecutorTest {
    private val fixture = CampaignWorldFixture()
    private val sentAt = Instant.parse("2026-10-08T12:00:00Z")

    @AfterEach fun clearResolvers() = NationActionResolverRegistry.clear()

    @Test fun `queued peace execution sends a proposal without ending the war`() {
        val route = fixture.route()
        val phase = Phase(200, 1, 1)
        val placement = PlacementOrder("envoy", 501, 51, PlacementPost.ENVOY, PlacementTarget.Nation(2), phase)
        val envoy = fixture.person(502, 1, route.startCity, lord = false).let {
            it.copy(meta = it.meta + (PlacementState.META_KEY to
                PlacementState(ActivePlacement(placement, phase, phase), null).toMetaValue()))
        }
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val recipient = fixture.person(503, 2, route.destinationCounty, userId = "43")
        val world = fixture.world(listOf(ruler to route.start, envoy to route.start, recipient to route.destination),
            nations = listOf(Nation(1, "아국", "#111111", capitalCityId = route.startCity, level = 2),
                Nation(2, "상대국", "#222222", capitalCityId = route.destinationCounty, level = 2)),
            retainers = listOf(Retainer(51, 501, "TEST", 502, envoy.name, "lieutenant")),
            cityChanges = { city -> when (city.id) {
                route.startCity -> city.copy(nationId = 1, supplyState = 1)
                route.destinationCounty -> city.copy(nationId = 2, supplyState = 1)
                else -> city
            } })
        val recorder = ChangeRecorder()

        assertNull(CourtActionExecutor(world, recorder, DomesticContext(),
            now = { sentAt }).execute(
            501, DiplomacyInput.OFFER_PEACE, """{"targetNationId":2}"""))

        assertEquals(0, world.getDiplomacy(1, 2)?.state)
        assertEquals(0, world.getDiplomacy(2, 1)?.state)
        assertFalse(recorder.diplomacyUpdateDirty().isNotEmpty())
        val rows = recorder.createdMessages()
        assertEquals(listOf(9002, 9001), rows.map { it.mailbox })
        assertEquals(listOf("diplomacy", "diplomacy"), rows.map { it.type })
        assertEquals("2026-10-08 12:00:00Z", rows.first().time)
        assertEquals("2026-10-08 15:00:00Z", rows.first().validUntil)
        val received = jsonDecode(rows.first().bodyJson)
        val sent = jsonDecode(rows.last().bodyJson)
        assertEquals("아국의 종전 제의 서신", received["text"])
        assertEquals("stop_war", (received["option"] as Map<*, *>)["action"])
        assertNull(sent["option"])

        @Suppress("UNCHECKED_CAST")
        val source = received["src"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val destination = received["dest"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val option = received["option"] as Map<String, Any?>
        val message = MessageSnapshot(
            id = rows.first().id, mailbox = rows.first().mailbox, hasAction = true, type = rows.first().type,
            srcGeneralId = 501, srcNationId = 1, destGeneralId = 0, destNationId = 2,
            time = Instant.parse(rows.first().time.replace(' ', 'T')),
            validUntil = Instant.parse(rows.first().validUntil.replace(' ', 'T')),
            text = received["text"] as String, srcArray = source, destArray = destination, option = option,
        )
        assertEquals(sentAt.plusSeconds(180 * 60), message.validUntil)

        val processor = ProcessNationCommand(world, recorder, "offer-peace-test",
            CommandRegistry(GeneralActionPipeline()), startYear = 184)
        val method = DaemonLoopConfig::class.java.getDeclaredMethod(
            "installNationActionResolvers", GeneralActionPipeline::class.java)
        method.isAccessible = true
        method.invoke(DaemonLoopConfig(), GeneralActionPipeline())
        fun handler(at: Instant) = DiplomaticMessageHandler(world, recorder, processor,
            messageReader = { id -> message.takeIf { it.id == id } }, nowProvider = { at })
        val command = TurnDaemonCommand.AcceptDiplomaticMessage(messageId = message.id, generalId = 503)
        assertIs<AcceptDiplomaticMessageFail>(handler(message.validUntil.plusSeconds(1)).handleAccept(command))
        assertIs<AcceptDiplomaticMessageFail>(handler(sentAt.plusSeconds(60)).handleAccept(
            TurnDaemonCommand.AcceptDiplomaticMessage(messageId = message.id, generalId = 502)))
        assertFalse(recorder.messageInvalidates().isNotEmpty())
        assertFalse(recorder.diplomacyUpdateDirty().isNotEmpty())

        val activeHandler = handler(sentAt.plusSeconds(60))
        assertIs<AcceptDiplomaticMessageOk>(activeHandler.handleAccept(command))
        assertEquals(DiplomacyState.TRADE, world.getDiplomacy(1, 2)?.state)
        assertEquals(DiplomacyState.TRADE, world.getDiplomacy(2, 1)?.state)
        assertEquals(2, recorder.diplomacyUpdateDirty().size)
        assertEquals(1, recorder.messageInvalidates().size)
        assertIs<AcceptDiplomaticMessageFail>(activeHandler.handleAccept(command))
        assertEquals(2, recorder.diplomacyUpdateDirty().size)
        assertEquals(1, recorder.messageInvalidates().size)
    }
}
