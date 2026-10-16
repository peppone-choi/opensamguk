package opensamguk.gameapi.precheck

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.decodeCommandEnvelope
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.*
import opensamguk.gameapi.reserve.*
import opensamguk.gameapi.web.CourtController
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand
import opensamguk.infra.persistence.CommandInboxRepository.InsertResult
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import kotlin.test.*

class CourtDeclareWarAdmissionTest {
    private val phase = Phase(200, 1, 1)
    private val args = """{"targetNationId":2}"""
    private val reader = mock(DomesticReader::class.java)
    private val catalog = InputCatalog.load()
    private val admission = CourtAdmission(mock(DispatchPrecheckService::class.java), catalog = catalog, reader = reader)

    private fun state(relation: Int = DiplomacyState.TRADE, withEnvoy: Boolean = true,
        ruler: Boolean = true): DomesticProjection {
        val order = PlacementOrder("envoy", 1, 5, PlacementPost.ENVOY, PlacementTarget.Nation(2), phase)
        val actor = DomesticPerson(1, "군주", 1, true, 2, if (ruler) 12 else 1,
            70, 70, 70, 70, 70, null, false, mapOf(LordStatus.META_KEY to ruler))
        val envoy = actor.copy(id = 3, officerLevel = 1, meta = mapOf(PlacementState.META_KEY to
            PlacementState(ActivePlacement(order, phase, phase), null).toMetaValue()))
        return DomesticProjection(RuleProfile.HWIHA, phase, listOf(actor, envoy),
            if (withEnvoy) listOf(DomesticCard(5, 1, 3, "TEST")) else emptyList(), emptyList(),
            listOf(DomesticNation(1, "아국", null, emptyMap()), DomesticNation(2, "상대국", null, emptyMap())),
            emptySet(), diplomacy = listOf(DomesticDiplomacy(1, 2, relation, 0), DomesticDiplomacy(2, 1, relation, 0)))
    }

    @Test fun `real catalog offers declaration and admits a canonical nation target`() {
        val relations = listOf(DiplomacyState.TRADE) +
            if (DeclareWarPolicy.ALLOW_DURING_NON_AGGRESSION) listOf(DiplomacyState.NON_AGGRESSION) else emptyList()
        for (relation in relations) {
            `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state(relation)))
            val options = CourtActionOptionsService(reader, catalog).options(1, 42L, DiplomacyInput.DECLARE_WAR)
            assertTrue(options.available)
            assertEquals(listOf(mapOf("targetNationId" to 2)), options.choices.map { it.arguments })
            assertEquals(args, admission.canonicalArguments(1, 42, DiplomacyInput.DECLARE_WAR, args))
        }
    }

    @Test fun `options and admission preserve exact rejection reasons`() {
        for ((projection, reason) in listOf(
            state(withEnvoy = false) to CourtFailure.ENVOY_REQUIRED,
            state(ruler = false) to CourtFailure.NOT_RULER,
            state(DiplomacyState.WAR) to CourtFailure.ALREADY_AT_WAR,
            state(DiplomacyState.DECLARATION) to CourtFailure.ALREADY_AT_WAR)) {
            `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection))
            val options = CourtActionOptionsService(reader, catalog).options(1, 42L, DiplomacyInput.DECLARE_WAR)
            assertFalse(options.available)
            assertEquals(reason.name, options.code)
            assertEquals(reason.name, assertFailsWith<AdmissionDenied> {
                admission.canonicalArguments(1, 42, DiplomacyInput.DECLARE_WAR, args)
            }.code)
        }
    }

    @Test fun `court controller returns 202 only after real admission and durable inbox acceptance`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state()))
        val inbox = mock(CommandInboxRepository::class.java)
        val turns = mock(ReservedTurnRepository::class.java)
        val accepted = ArgumentCaptor.forClass(AcceptedCommand::class.java)
        `when`(inbox.insertAccepted(any(AcceptedCommand::class.java))).thenReturn(InsertResult.Inserted)
        val controller = CourtController(service(inbox, turns))
        val response = controller.submit(42L, "declareWar", 1, args)
        assertEquals(202, response.statusCode.value())
        assertEquals("declare-war-admission", (response.body as Map<*, *>)["requestId"])
        verify(inbox).insertAccepted(accepted.capture())
        val row = accepted.value
        assertEquals(CommandInboxRepository.CommandKind.IMMEDIATE, row.commandKind)
        assertEquals(42, row.ownerUserId)
        val input = assertIs<TurnDaemonCommand.ImmediateInput>(decodeCommandEnvelope(row.payloadJson).command)
        assertEquals(DiplomacyInput.DECLARE_WAR, input.inputId)
        assertEquals(1, input.generalId)
        assertEquals(args, input.argJson)
        assertEquals("declare-war-admission", input.requestId)
        verifyNoInteractions(turns)
    }

    @Test fun `non ruler or foreign owner never reaches the inbox`() {
        val inbox = mock(CommandInboxRepository::class.java)
        val turns = mock(ReservedTurnRepository::class.java)
        val controller = CourtController(service(inbox, turns))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state(ruler = false)))
        val response = controller.submit(42L, "declareWar", 1, args)
        assertEquals("NOT_RULER", (response.body as Map<*, *>)["code"])
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(1, 43L)
        val foreign = controller.submit(43L, "declareWar", 1, args)
        assertEquals("FORBIDDEN", (foreign.body as Map<*, *>)["code"])
        verifyNoInteractions(inbox, turns)
    }

    @Suppress("UNCHECKED_CAST")
    private fun service(inbox: CommandInboxRepository, turns: ReservedTurnRepository): CommandReserveService {
        val redis = mock(StringRedisTemplate::class.java)
        `when`(redis.opsForStream<Any, Any>()).thenReturn(
            mock(StreamOperations::class.java) as StreamOperations<String, Any, Any>)
        return CommandReserveService(turns, inbox, mock(CommandResultRepository::class.java), redis,
            CommandRegistry(GeneralActionPipeline()), GameApiProcessWorld(1), "fixture",
            requestIds = { "declare-war-admission" }, transactions = object : TransactionOperations {
                override fun <T : Any?> execute(action: TransactionCallback<T>): T? =
                    action.doInTransaction(SimpleTransactionStatus())
            }, worldStates = mock(WorldStateReadRepository::class.java), courtAdmission = admission)
    }
}
