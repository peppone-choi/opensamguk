package opensamguk.gameapi.reserve

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.CommandResultRow
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandReserveServiceTest {
    @Test fun `hwiha reserve classifies ledger delivery before route availability`() {
        val turns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(turns, inbox, results, redis(), CommandRegistry(GeneralActionPipeline()),
            GameApiProcessWorld(1), "fixture", transactions = TestTransactions,
            worldStates = worlds(mapOf("ruleProfile" to "HWIHA")))
        for ((inputId, expected) in mapOf(
            "stratagem.play" to "NOT_DELIVERED",
            "action.unlisted" to "UNKNOWN_INPUT",
            "action.resign" to "NOT_DELIVERED",
            "action.randomEnlist" to "UNKNOWN_INPUT",
            "che_농지개간" to "WRONG_RULE_PROFILE",
            "v2CityTransport" to "WRONG_RULE_PROFILE",
            "v2GarrisonRecruit" to "WRONG_RULE_PROFILE",
            "court.dispatch" to "INVALID_INPUT_CHANNEL",
        )) {
            assertEquals(expected, assertFailsWith<HwihaAdmissionDenied> {
                service.reserveForOwner(10, inputId, 0, "{}", 42)
            }.code, inputId)
        }
        assertEquals(0, turns.reserves.size)
        assertEquals(0, inbox.accepted.size)
        assertEquals(0, results.rows.size)
    }

    @Test fun `delivered deploy reaches its reservation admission`() {
        val deploy = mock(HwihaDeployAdmission::class.java)
        `when`(deploy.canonicalArguments(10, 42, 0, "{}"))
            .thenThrow(HwihaAdmissionDenied("ADMISSION_REACHED", "배달된 입력은 전용 사전검사로 전달됩니다."))
        val service = CommandReserveService(RecordingReservedTurns(), RecordingInbox(), RecordingResults(), redis(),
            CommandRegistry(GeneralActionPipeline()), GameApiProcessWorld(1), "fixture", transactions = TestTransactions,
            worldStates = worlds(mapOf("ruleProfile" to "HWIHA")), hwihaDeployAdmission = deploy)

        assertEquals("ADMISSION_REACHED", assertFailsWith<HwihaAdmissionDenied> {
            service.reserveForOwner(10, "action.deploy", 0, "{}", 42)
        }.code)
    }

    private fun catalogFor(inputId: String, kind: String, state: String) =
        opensamguk.logic.input.HwihaInputCatalog.parse("""{"schemaVersion":3,"catalogId":"test","status":"DRAFT","note":"test",
            "inputs":[{"inputId":"$inputId","kind":"$kind","layer":1,
            "actor":"GENERAL","authorityRule":"SUBJECT_OWNER","targetSchema":{"status":"PLANNED","source":"test"},
            "costSchema":{"status":"PLANNED","source":"test","money":null,"grain":null,"iron":null,"timber":null,"horses":null},
            "timing":${if (kind == "GENERAL_ACTION") """{"phase":"FIELD","turnSlots":12,"perPhaseLimit":1}""" else
                """{"phase":"NEXT_CARD_TURN","turnSlots":null,"perPhaseLimit":null}"""},"effectScope":"ACTOR_LOCATION","failureReasons":[],"resultType":"InputResolved",
            "replayContract":{"status":"PLANNED","key":"requestId"},"aiPolicyId":"ai.test","helpTopicId":"help.test","tutorialObjectiveId":"N/A",
            "deliveryState":"$state"${if (kind == "GENERAL_ACTION") ",\"displayName\":\"테스트\"" else ""}}]}""")
    private fun worlds(config: Map<String, Any?>? = mapOf("ruleProfile" to "SAMMO")): opensamguk.gameapi.read.WorldStateReadRepository {
        val repo = mock(opensamguk.gameapi.read.WorldStateReadRepository::class.java)
        `when`(repo.findProcessWorld()).thenReturn(config?.let { opensamguk.gameapi.read.WorldStateReadEntity(config = it) })
        return repo
    }
    @Test fun `legacy direct reservation cannot enter hwiha slots and invalid world fails closed`() {
        for (config in listOf(null, mapOf("ruleProfile" to null), mapOf("ruleProfile" to "unknown"),
            mapOf("ruleProfile" to 1), mapOf("ruleProfile" to "HWIHA"))) {
            val turns = RecordingReservedTurns()
            val inbox = RecordingInbox()
            val results = RecordingResults()
            val redis = redis()
            val service = CommandReserveService(turns, inbox, results, redis, CommandRegistry(GeneralActionPipeline()),
                GameApiProcessWorld(1), "fixture", transactions = TestTransactions, worldStates = worlds(config))
            assertFailsWith<HwihaAdmissionDenied> { service.reserve(10, "che_농지개간", 29) }
            assertEquals(0, turns.reserves.size)
            assertEquals(0, inbox.accepted.size)
            assertEquals(0, results.rows.size)
            org.mockito.Mockito.verify(redis, org.mockito.Mockito.never()).opsForStream<Any, Any>()
        }
        val turns = RecordingReservedTurns()
        val service = CommandReserveService(turns, RecordingInbox(), RecordingResults(), redis(), CommandRegistry(GeneralActionPipeline()),
            GameApiProcessWorld(1), "fixture", transactions = TestTransactions, worldStates = worlds(emptyMap()))
        assertFailsWith<HwihaAdmissionDenied> { service.reserve(10, "che_농지개간", 29) }
        assertEquals(0, turns.reserves.size)
    }

    @Test fun `hwiha direct reservation validates authority and stores canonical owned request`() {
        val generals = mock(opensamguk.gameapi.read.GeneralReadRepository::class.java)
        val precheck = mock(opensamguk.gameapi.precheck.HwihaEnlistmentPrecheckService::class.java)
        val actor = opensamguk.gameapi.read.GeneralReadEntity(id = 10, userId = "42")
        `when`(generals.findById(10)).thenReturn(java.util.Optional.of(actor))
        val request = opensamguk.logic.input.EnlistmentRequest(10, opensamguk.logic.input.EnlistmentMode.NATION, 3)
        `when`(precheck.assess(request)).thenReturn(opensamguk.logic.input.EnlistmentAssessment.Eligible(listOf(opensamguk.logic.input.EnlistmentPlan(10, 20, 3, listOf(10), false, 5))))
        val catalog = catalogFor("action.enlist", "GENERAL_ACTION", "HANDLER_READY")
        val admission = HwihaEnlistmentAdmission(generals, precheck, catalog)
        for (failure in opensamguk.logic.input.EnlistmentFailure.entries) {
            `when`(precheck.assess(request)).thenReturn(opensamguk.logic.input.EnlistmentAssessment.Rejected(failure))
            val denied = assertFailsWith<HwihaAdmissionDenied> {
                admission.canonicalArguments(10, 42, 0, """{"mode":"NATION","targetId":3}""")
            }
            assertEquals(failure.name, denied.code)
            assertEquals(failure.message, denied.message)
        }
        val malformed = assertFailsWith<HwihaAdmissionDenied> { admission.canonicalArguments(10, 42, 0, "{}") }
        assertEquals(opensamguk.logic.input.EnlistmentFailure.INVALID_REQUEST.message, malformed.message)
        `when`(precheck.assess(request)).thenReturn(opensamguk.logic.input.EnlistmentAssessment.Eligible(listOf(opensamguk.logic.input.EnlistmentPlan(10, 20, 3, listOf(10), false, 5))))
        val plannedCatalog = catalogFor("action.enlist", "GENERAL_ACTION", "PLANNED")
        assertEquals("NOT_DELIVERED", assertFailsWith<HwihaAdmissionDenied> {
            HwihaEnlistmentAdmission(generals, precheck, plannedCatalog).canonicalArguments(10, 42, 0,
                """{"mode":"NATION","targetId":3}""")
        }.code)
        val turns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(turns, inbox, results, redis(), CommandRegistry(GeneralActionPipeline()),
            GameApiProcessWorld(1), "che:scenario_2", requestIds = { "hwiha-req" }, transactions = TestTransactions, worldStates = worlds(mapOf("ruleProfile" to "HWIHA")),
            hwihaAdmission = HwihaEnlistmentAdmission(generals, precheck, catalog), hwihaCatalog = catalog)
        val raw = """{ "targetId":3, "mode":"NATION" }"""
        assertEquals("UNAUTHORIZED", assertFailsWith<HwihaAdmissionDenied> { service.reserve(10, "action.enlist", 0, raw) }.code)
        assertEquals("FORBIDDEN", assertFailsWith<HwihaAdmissionDenied> { service.reserveForOwner(10, "action.enlist", 0, raw, 43) }.code)
        for (slot in listOf(-1, 12)) assertEquals("INVALID_TURN_SLOT",
            assertFailsWith<HwihaAdmissionDenied> { service.reserveForOwner(10, "action.enlist", slot, raw, 42) }.code)
        assertEquals("INVALID_REQUEST", assertFailsWith<HwihaAdmissionDenied> {
            service.reserveForOwner(10, "action.enlist", 0, """{"mode":"NATION","mode":"NATION","targetId":3}""", 42)
        }.code)
        `when`(precheck.assess(request)).thenReturn(opensamguk.logic.input.EnlistmentAssessment.Rejected(opensamguk.logic.input.EnlistmentFailure.WRONG_RULE_PROFILE))
        assertEquals("WRONG_RULE_PROFILE", assertFailsWith<HwihaAdmissionDenied> { service.reserveForOwner(10, "action.enlist", 0, raw, 42) }.code)
        assertEquals(0, inbox.accepted.size)
        assertEquals(0, turns.reserves.size)
        `when`(precheck.assess(request)).thenReturn(opensamguk.logic.input.EnlistmentAssessment.Eligible(listOf(opensamguk.logic.input.EnlistmentPlan(10, 20, 3, listOf(10), false, 5))))
        service.reserveForOwner(10, "action.enlist", 11, raw, 42)
        assertEquals(42, inbox.accepted.single().ownerUserId)
        assertEquals("hwiha-req", turns.reserves.single().requestId)
        assertEquals("테스트", turns.reserves.single().brief)
        assertEquals("""{"mode":"NATION","targetId":3}""", turns.reserves.single().argJson)
        assertEquals(11, turns.reserves.single().turnIdx)
        assertEquals("reservationAccepted", results.rows.single().resultType)
    }

    @Test fun `court request collision binds actor owner input and canonical arguments`() {
        val admission = mock(HwihaCourtAdmission::class.java)
        val base = opensamguk.common.wire.TurnDaemonCommand.ImmediateInput(
            "client-id", 10, 999, "court.dispatch", "{args}")
        val variants = listOf(base.copy(generalId = 11) to 42,
            base to 43, base.copy(inputId = "court.dispatchReply") to 42,
            base.copy(argJson = "{other}") to 42)
        for ((variant, owner) in variants) {
            `when`(admission.canonicalArguments(10, 42, base.inputId, base.argJson)).thenReturn(base.argJson)
            `when`(admission.canonicalArguments(variant.generalId, owner, variant.inputId, variant.argJson)).thenReturn(variant.argJson)
            val inbox = RecordingInbox()
            val service = CommandReserveService(RecordingReservedTurns(), inbox, RecordingResults(), redis(),
                CommandRegistry(GeneralActionPipeline()), GameApiProcessWorld(1), "fixture",
                requestIds = { "court-collision" }, transactions = TestTransactions,
                worldStates = worlds(mapOf("ruleProfile" to "HWIHA")), hwihaCourtAdmission = admission)
            service.publishImmediate(base, 42)
            service.publishImmediate(base.copy(requestId = "another-client-id", ownerUserId = 777), 42)
            assertFailsWith<IllegalStateException> { service.publishImmediate(variant, owner) }
            assertEquals(1, inbox.accepted.size)
            assertEquals(42, inbox.accepted.single().ownerUserId)
        }
    }

    @Test fun `domestic standing inputs publish canonical immediate commands without a turn slot`() {
        val reader = mock(opensamguk.gameapi.read.HwihaDomesticReader::class.java)
        val now = opensamguk.logic.input.HwihaPhase(200, 1, 1)
        fun person(id: Int, human: Boolean, lord: Boolean = false) = opensamguk.logic.input.DomesticPerson(id, "G$id", 1, human,
            if (human) 0 else 2, if (lord) 12 else 0, 50, 50, 50, 50, 50, "p$id", false, mapOf("hwihaLord" to lord))
        val state = opensamguk.logic.input.HwihaDomesticProjection(opensamguk.logic.input.RuleProfile.HWIHA, now,
            listOf(person(10, true, lord = true), person(20, false)), listOf(opensamguk.logic.input.DomesticCard(5, 10, 20, "staff")),
            listOf(opensamguk.logic.input.DomesticCounty(7, "C7", 1, "p7", "甲郡", emptyMap())),
            listOf(opensamguk.logic.input.DomesticNation(1, "N1", 7, emptyMap())), setOf("p7", "p10", "p20"))
        `when`(reader.snapshot()).thenReturn(opensamguk.gameapi.read.HwihaDomesticSnapshot(state))
        val catalog = opensamguk.logic.input.HwihaInputCatalog.load()
        val court = HwihaCourtAdmission(mock(opensamguk.gameapi.precheck.HwihaDispatchPrecheckService::class.java),
            HwihaDomesticAdmission(reader, catalog), catalog)
        val inbox = RecordingInbox()
        val turns = RecordingReservedTurns()
        val service = CommandReserveService(turns, inbox, RecordingResults(), redis(), CommandRegistry(GeneralActionPipeline()),
            GameApiProcessWorld(1), "fixture", requestIds = { "domestic-req" }, transactions = TestTransactions,
            worldStates = worlds(mapOf("ruleProfile" to "HWIHA")), hwihaCourtAdmission = court)
        service.publishImmediate(opensamguk.common.wire.TurnDaemonCommand.ImmediateInput("client", 10, 999,
            "placement.assign", """{ "countyId":7, "post":"MAGISTRATE", "cardId":5 }"""), 42)
        val stored = inbox.accepted.single()
        assertEquals(CommandInboxRepository.CommandKind.IMMEDIATE, stored.commandKind)
        assertEquals("HwihaCourtInput", stored.actionCode)
        assertEquals(42, stored.ownerUserId)
        assertEquals(0, turns.reserves.size)
        val envelope = opensamguk.common.wire.WireJson.decodeFromString(opensamguk.common.wire.TurnDaemonCommandEnvelope.serializer(),
            stored.payloadJson)
        assertEquals(opensamguk.common.wire.TurnDaemonCommand.ImmediateInput("domestic-req", 10, 42, "placement.assign",
            """{"cardId":5,"post":"MAGISTRATE","countyId":7}"""), envelope.command)
        // Shared-rule rejections and malformed bodies never reach the inbox.
        for ((input, body, code) in listOf(
            Triple("placement.assign", """{"cardId":5,"post":"MAGISTRATE","countyId":8}""", "INVALID_COUNTY"),
            Triple("policy.set", """{"scope":"COUNTY","countyId":7,"policy":"NONE"}""", "NOTHING_TO_CLEAR"),
            Triple("work.start", """{"countyId":7,"work":"ROAD"}""", "WAREHOUSE_NOT_READY"),
            Triple("work.start", """{"countyId":7,"work":"ROAD","x":1}""", "INVALID_REQUEST"),
        )) {
            assertEquals(code, assertFailsWith<HwihaAdmissionDenied> {
                service.publishImmediate(opensamguk.common.wire.TurnDaemonCommand.ImmediateInput("c", 10, 42, input, body), 42)
            }.code, body)
        }
        assertEquals(1, inbox.accepted.size)
        // A ledger that still says PLANNED keeps the input undelivered even when the rules pass.
        val planned = catalogFor("policy.set", "POLICY", "PLANNED")
        assertEquals("NOT_DELIVERED", assertFailsWith<HwihaAdmissionDenied> {
            HwihaDomesticAdmission(reader, planned).canonicalArguments(10, 42, "policy.set",
                """{"scope":"COUNTY","countyId":7,"policy":"COMMERCE"}""")
        }.code)
    }

    private class RecordingReservedTurns :
        ReservedTurnRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        data class ReserveCall(
            val worldId: WorldId,
            val generalId: Int,
            val turnIdx: Int,
            val actionCode: String?,
            val argJson: String?,
            val brief: String,
            val requestId: String?,
        )

        val reserves = mutableListOf<ReserveCall>()

        override fun reserve(
            worldId: WorldId,
            generalId: Int,
            turnIdx: Int,
            actionCode: String?,
            argJson: String?,
            brief: String,
            requestId: String?,
        ) {
            reserves += ReserveCall(worldId, generalId, turnIdx, actionCode, argJson, brief, requestId)
        }
    }

    private class RecordingInbox :
        CommandInboxRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val accepted = mutableListOf<AcceptedCommand>()
        val redisWakePublished = mutableListOf<Triple<WorldId, String, Instant>>()
        private val byRequestId = linkedMapOf<String, AcceptedCommand>()

        override fun insertAccepted(command: AcceptedCommand): InsertResult {
            val existing = byRequestId[command.requestId]
            if (existing != null) {
                return if (existing.intentFingerprint == command.intentFingerprint) {
                    InsertResult.ExistingSame
                } else {
                    InsertResult.Conflict(existing.intentFingerprint)
                }
            }
            accepted += command
            byRequestId[command.requestId] = command
            return InsertResult.Inserted
        }

        override fun markRedisWakePublished(worldId: WorldId, requestId: String, publishedAt: Instant) {
            redisWakePublished += Triple(worldId, requestId, publishedAt)
        }
    }

    private class RecordingResults :
        CommandResultRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val rows = mutableListOf<CommandResultRow>()

        override fun insertTerminalResult(
            worldId: WorldId,
            row: CommandResultRow,
            expectedInboxStatuses: Collection<String>,
        ) {
            rows += row
        }
    }

    private object TestTransactions : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T? =
            action.doInTransaction(SimpleTransactionStatus())

        override fun executeWithoutResult(action: java.util.function.Consumer<TransactionStatus>) {
            action.accept(SimpleTransactionStatus())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redis(): StringRedisTemplate {
        val redis = mock(StringRedisTemplate::class.java)
        val streamOps = mock(StreamOperations::class.java) as StreamOperations<String, Any, Any>
        `when`(redis.opsForStream<Any, Any>()).thenReturn(streamOps)
        return redis
    }

    @Test
    fun `turn-reserved commands store action definition name as brief for the reserved table`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-brief" },
            transactions = TestTransactions, worldStates = worlds(),
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)

        assertEquals(1, reservedTurns.reserves.size)
        assertEquals(WorldId(1), reservedTurns.reserves.single().worldId)
        assertEquals("che_견문", reservedTurns.reserves.single().actionCode)
        assertEquals("견문", reservedTurns.reserves.single().brief)
        assertEquals("req-brief", reservedTurns.reserves.single().requestId)
        assertEquals("req-brief", inbox.accepted.single().requestId)
        assertEquals(CommandInboxRepository.CommandKind.RESERVED_TURN, inbox.accepted.single().commandKind)
        assertEquals("reservationAccepted", results.rows.single().resultType)
    }

    @Test
    fun `shared mailbox intake works in hwiha and inserts inbox before publishing`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val redis = mock(StringRedisTemplate::class.java)
        `when`(redis.opsForStream<Any, Any>()).thenThrow(IllegalStateException("redis down"))
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis,
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-immediate" },
            transactions = TestTransactions, worldStates = worlds(mapOf("ruleProfile" to "HWIHA")),
        )

        val result = service.reserve(generalId = 10, actionCode = "sendMessage", turnIdx = 0, argJson = """{"msg":"x"}""")

        assertEquals("req-immediate", result.requestId)
        assertEquals(0, reservedTurns.reserves.size)
        assertEquals("req-immediate", inbox.accepted.single().requestId)
        assertEquals(CommandInboxRepository.CommandKind.IMMEDIATE, inbox.accepted.single().commandKind)
        assertEquals(emptyList(), results.rows)
        assertEquals(emptyList(), inbox.redisWakePublished)
    }

    @Test
    fun `same request id and same reserved intent does not rewrite ring`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-same" },
            transactions = TestTransactions, worldStates = worlds(),
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)
        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)

        assertEquals(1, inbox.accepted.size)
        assertEquals(1, reservedTurns.reserves.size)
        assertEquals(1, results.rows.size)
    }

    @Test
    fun `same request id and different reserved intent is rejected before ring rewrite`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-conflict" },
            transactions = TestTransactions, worldStates = worlds(),
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)
        assertFailsWith<IllegalStateException> {
            service.reserve(generalId = 10, actionCode = "che_농지개간", turnIdx = 0, argJson = null)
        }

        assertEquals(1, inbox.accepted.size)
        assertEquals(1, reservedTurns.reserves.size)
        assertEquals("che_견문", reservedTurns.reserves.single().actionCode)
        assertEquals(1, results.rows.size)
    }
}
