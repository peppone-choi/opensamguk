package opensamguk.engine.intake

import opensamguk.common.wire.AcceptDiplomaticMessageFail
import opensamguk.common.wire.AcceptDiplomaticMessageOk
import opensamguk.common.wire.DeclineDiplomaticMessageFail
import opensamguk.common.wire.DeclineDiplomaticMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.config.DaemonLoopConfig
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.jsonDecode
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiplomaticMessageHandlerTest {

    private val now = Instant.parse("0200-03-01T12:00:00Z")

    @AfterTest
    fun resetResolvers() = NationActionResolverRegistry.clear()

    @Test
    fun `accept maps all stored actions to their instant nation effects`() {
        installDaemonResolvers()
        val cases = listOf(
            Triple("no_aggression", DiplomacyState.TRADE, DiplomacyState.NON_AGGRESSION),
            Triple("cancel_na", DiplomacyState.NON_AGGRESSION, DiplomacyState.TRADE),
            Triple("stop_war", DiplomacyState.WAR, DiplomacyState.TRADE),
        )

        for ((action, before, expected) in cases) {
            val world = world(diplomacyState = before)
            val recorder = ChangeRecorder()
            val message = snapshot(
                option = linkedMapOf(
                    "action" to action,
                    "year" to 200,
                    "month" to 3,
                ),
            )

            val result = handler(world, recorder, message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = message.id, generalId = 10),
            )

            assertIs<AcceptDiplomaticMessageOk>(result, action)
            assertEquals(expected, world.getDiplomacy(1, 2)?.state, action)
            assertEquals(expected, world.getDiplomacy(2, 1)?.state, action)
            assertEquals(2, recorder.diplomacyUpdateDirty().size, action)
            assertEquals(1, recorder.messageInvalidates().size, action)
            if (action == "no_aggression") {
                assertEquals(1, world.getDiplomacy(1, 2)?.term)
            }
        }
    }

    @Test
    fun `full constraint denial uses PHP getFailString for all three actions and leaves the message usable`() {
        installDaemonResolvers()
        val cases = listOf(
            ActionDenialCase(
                action = "no_aggression",
                option = linkedMapOf("action" to "no_aggression", "year" to 201, "month" to 3),
                diplomacyState = DiplomacyState.WAR,
                reason = "아국과 이미 교전중입니다. 불가침 수락 실패.",
            ),
            ActionDenialCase(
                action = "cancel_na",
                option = linkedMapOf("action" to "cancel_na"),
                diplomacyState = DiplomacyState.TRADE,
                reason = "불가침 중인 상대국에게만 가능합니다. 불가침 파기 수락 실패.",
            ),
            ActionDenialCase(
                action = "stop_war",
                option = linkedMapOf("action" to "stop_war"),
                diplomacyState = DiplomacyState.TRADE,
                reason = "상대국과 선포, 전쟁중이지 않습니다. 종전 수락 실패.",
            ),
        )

        for (case in cases) {
            val world = world(diplomacyState = case.diplomacyState)
            val recorder = ChangeRecorder()
            val message = snapshot(option = case.option)

            val result = handler(world, recorder, message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = message.id, generalId = 10),
            )

            val failure = assertIs<AcceptDiplomaticMessageFail>(result, case.action)
            assertEquals(case.reason, failure.reason, case.action)
            assertTrue(recorder.messageInvalidates().isEmpty(), case.action)
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), case.action)
        }
    }

    @Test
    fun `decline records the exact hidden body without changing its payload order`() {
        val world = world(diplomacyState = DiplomacyState.WAR)
        val recorder = ChangeRecorder()
        val message = snapshot(
            text = "종전 제안",
            option = linkedMapOf("action" to "stop_war", "trace" to "opaque"),
        )

        val result = handler(world, recorder, message, processor = null).handleDecline(
            TurnDaemonCommand.DeclineDiplomaticMessage(messageId = message.id, generalId = 10),
        )

        assertIs<DeclineDiplomaticMessageOk>(result)
        val invalidation = recorder.messageInvalidates().single()
        assertEquals("2000-12-31 00:00:00", invalidation.validUntil)
        assertEquals(
            """{"src":{"id":20,"name":"조조","nation_id":2},"dest":{"id":0,"name":"","nation_id":1},"text":"종전 제안","option":{"action":"stop_war","trace":"opaque","used":true,"invalid":true}}""",
            invalidation.bodyJson,
        )
        val body = jsonDecode(invalidation.bodyJson)
        @Suppress("UNCHECKED_CAST")
        val option = body["option"] as Map<String, Any?>
        assertEquals(listOf("action", "trace", "used", "invalid"), option.keys.toList())
        assertEquals(message.srcArray, body["src"])
        assertEquals(message.destArray, body["dest"])
        assertEquals(message.text, body["text"])
    }

    @Test
    fun `common validation follows PHP expiry mailbox and authority order`() {
        val valid = snapshot(option = linkedMapOf("action" to "stop_war"))
        val unauthorized = actor(officerLevel = 1, meta = linkedMapOf("permission" to "auditor"))
        val cases = listOf(
            ValidationCase(
                "expired wins before mailbox",
                world(actor = unauthorized),
                valid.copy(
                    mailbox = 9999,
                    validUntil = now.minusSeconds(1),
                    option = linkedMapOf("action" to "no_aggression"),
                ),
                "유효하지 않은 외교서신입니다.",
            ),
            ValidationCase(
                "used",
                world(),
                valid.copy(option = linkedMapOf("action" to "stop_war", "used" to true)),
                "유효하지 않은 외교서신입니다.",
            ),
            ValidationCase(
                "mailbox wins before authority",
                world(actor = unauthorized),
                valid.copy(mailbox = 9002, option = linkedMapOf("action" to "no_aggression")),
                "송신자가 외교서신을 처리할 수 없습니다.",
            ),
            ValidationCase(
                "authority wins before accept-only args",
                world(actor = unauthorized),
                valid.copy(option = linkedMapOf("action" to "no_aggression")),
                "해당 국가의 외교권자가 아닙니다.",
            ),
            ValidationCase(
                "actor belongs to another nation",
                world(actor = actor(nationId = 2)),
                valid,
                "해당 국가의 외교권자가 아닙니다.",
            ),
            ValidationCase(
                "actor is missing",
                world(actor = null),
                valid,
                "해당 국가의 외교권자가 아닙니다.",
            ),
            ValidationCase(
                "wrong message type",
                world(),
                valid.copy(type = "private"),
                "유효하지 않은 외교서신입니다.",
            ),
            ValidationCase(
                "unsupported diplomatic action",
                world(),
                valid.copy(option = linkedMapOf("action" to "unknown")),
                "유효하지 않은 외교서신입니다.",
            ),
        )

        for (case in cases) {
            val declineRecorder = ChangeRecorder()
            val decline = handler(case.world, declineRecorder, case.message, processor = null).handleDecline(
                TurnDaemonCommand.DeclineDiplomaticMessage(messageId = case.message.id, generalId = 10),
            )
            val acceptRecorder = ChangeRecorder()
            val accept = handler(case.world, acceptRecorder, case.message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = case.message.id, generalId = 10),
            )

            assertEquals(case.reason, assertIs<DeclineDiplomaticMessageFail>(decline, case.label).reason, case.label)
            assertEquals(case.reason, assertIs<AcceptDiplomaticMessageFail>(accept, case.label).reason, case.label)
            assertTrue(declineRecorder.messageInvalidates().isEmpty(), case.label)
            assertTrue(acceptRecorder.messageInvalidates().isEmpty(), case.label)
            assertTrue(acceptRecorder.diplomacyUpdateDirty().isEmpty(), case.label)
        }
    }

    @Test
    fun `decline ignores the legacy invalid marker and accept-only no-aggression args`() {
        val world = world(actor = actor(officerLevel = 1, meta = linkedMapOf("permission" to "ambassador")))
        val recorder = ChangeRecorder()
        val message = snapshot(
            validUntil = now,
            option = linkedMapOf(
                "action" to "no_aggression",
                "year" to 0,
                "month" to 13,
                "invalid" to true,
            ),
        )

        val result = handler(world, recorder, message, processor = null).handleDecline(
            TurnDaemonCommand.DeclineDiplomaticMessage(messageId = message.id, generalId = 10),
        )

        assertIs<DeclineDiplomaticMessageOk>(result)
        assertEquals(1, recorder.messageInvalidates().size)
    }

    @Test
    fun `accept formats PHP argTest failures with the resolved command name`() {
        val valid = snapshot(option = linkedMapOf("action" to "stop_war"))
        val cases = listOf(
            InvalidArgCase(
                label = "non-positive proposer nation",
                message = valid.copy(
                    srcNationId = 0,
                    srcArray = linkedMapOf("id" to 20, "name" to "?", "nation_id" to 0),
                ),
                reason = "인자가 올바르지 않습니다. 종전 수락 실패.",
            ),
            InvalidArgCase(
                label = "non-positive proposer",
                message = valid.copy(
                    srcGeneralId = 0,
                    srcArray = linkedMapOf("id" to 0, "name" to "?", "nation_id" to 2),
                ),
                reason = "인자가 올바르지 않습니다. 종전 수락 실패.",
            ),
            InvalidArgCase(
                label = "self proposer",
                message = valid.copy(
                    srcGeneralId = 10,
                    srcNationId = 1,
                    srcArray = linkedMapOf("id" to 10, "name" to "유비", "nation_id" to 1),
                ),
                reason = "인자가 올바르지 않습니다. 종전 수락 실패.",
            ),
            InvalidArgCase(
                label = "pre-start no-aggression year",
                message = valid.copy(
                    option = linkedMapOf("action" to "no_aggression", "year" to 183, "month" to 12),
                ),
                reason = "인자가 올바르지 않습니다. 불가침 수락 실패.",
            ),
            InvalidArgCase(
                label = "missing no-aggression year",
                message = valid.copy(
                    option = linkedMapOf("action" to "no_aggression", "month" to 3),
                ),
                reason = "인자가 올바르지 않습니다. 불가침 수락 실패.",
            ),
            InvalidArgCase(
                label = "missing no-aggression month",
                message = valid.copy(
                    option = linkedMapOf("action" to "no_aggression", "year" to 201),
                ),
                reason = "인자가 올바르지 않습니다. 불가침 수락 실패.",
            ),
            InvalidArgCase(
                label = "invalid no-aggression month",
                message = valid.copy(
                    option = linkedMapOf("action" to "no_aggression", "year" to 201, "month" to 13),
                ),
                reason = "인자가 올바르지 않습니다. 불가침 수락 실패.",
            ),
            InvalidArgCase(
                label = "decimal no-aggression year",
                message = valid.copy(
                    option = linkedMapOf("action" to "no_aggression", "year" to 201.0, "month" to 3),
                ),
                reason = "인자가 올바르지 않습니다. 불가침 수락 실패.",
            ),
        )

        for (case in cases) {
            val caseWorld = world()
            val recorder = ChangeRecorder()
            val result = handler(caseWorld, recorder, case.message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = case.message.id, generalId = 10),
            )

            val failure = assertIs<AcceptDiplomaticMessageFail>(result, case.label)
            assertEquals(case.reason, failure.reason, case.label)
            assertTrue(recorder.messageInvalidates().isEmpty(), case.label)
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), case.label)
        }
    }

    @Test
    fun `accept returns ordered PHP full reasons for missing and mismatched proposers`() {
        val valid = snapshot(option = linkedMapOf("action" to "stop_war"))
        val cases = listOf(
            FullReasonCase(
                label = "missing proposer nation",
                message = valid.copy(srcNationId = 99),
                reason = "멸망한 국가입니다. 종전 수락 실패.",
            ),
            FullReasonCase(
                label = "missing proposer general",
                message = valid.copy(srcGeneralId = 99),
                reason = "없는 장수입니다. 종전 수락 실패.",
            ),
            FullReasonCase(
                label = "proposer nation mismatch",
                message = valid.copy(srcNationId = 3),
                reason = "제의 장수가 국가 소속이 아닙니다 종전 수락 실패.",
            ),
        )

        for (case in cases) {
            val recorder = ChangeRecorder()
            val result = handler(world(), recorder, case.message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = case.message.id, generalId = 10),
            )

            assertEquals(
                case.reason,
                assertIs<AcceptDiplomaticMessageFail>(result, case.label).reason,
                case.label,
            )
            assertTrue(recorder.messageInvalidates().isEmpty(), case.label)
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), case.label)
        }
    }

    @Test
    fun `accept preserves PHP arg stale and FULL ordering for an ambassador`() {
        val ambassador = actor(officerLevel = 1, meta = linkedMapOf("permission" to "ambassador"))
        val cases = listOf(
            FullReasonCase(
                label = "argTest precedes BeChief",
                message = snapshot(srcNationId = 0, option = linkedMapOf("action" to "stop_war")),
                reason = "인자가 올바르지 않습니다. 종전 수락 실패.",
            ),
            FullReasonCase(
                label = "stale no-aggression replaces the normal FULL list",
                message = snapshot(
                    option = linkedMapOf("action" to "no_aggression", "year" to 199, "month" to 12),
                ),
                reason = "이미 기한이 지났습니다. 불가침 수락 실패.",
            ),
            FullReasonCase(
                label = "BeChief precedes proposer existence",
                message = snapshot(srcNationId = 99, option = linkedMapOf("action" to "stop_war")),
                reason = "수뇌가 아닙니다. 종전 수락 실패.",
            ),
        )

        for (case in cases) {
            val recorder = ChangeRecorder()
            val result = handler(world(actor = ambassador), recorder, case.message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = case.message.id, generalId = 10),
            )

            assertEquals(
                case.reason,
                assertIs<AcceptDiplomaticMessageFail>(result, case.label).reason,
                case.label,
            )
            assertTrue(recorder.messageInvalidates().isEmpty(), case.label)
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), case.label)
        }
    }

    @Test
    fun `accept rejects previous displayed month and prior year with the PHP stale reason`() {
        val cases = listOf(200 to 2, 199 to 12)

        for ((year, month) in cases) {
            val world = world(diplomacyState = DiplomacyState.TRADE)
            val recorder = ChangeRecorder()
            val message = snapshot(
                option = linkedMapOf("action" to "no_aggression", "year" to year, "month" to month),
            )

            val result = handler(world, recorder, message).handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = message.id, generalId = 10),
            )

            val failure = assertIs<AcceptDiplomaticMessageFail>(result, "$year/$month")
            assertEquals("이미 기한이 지났습니다. 불가침 수락 실패.", failure.reason)
            assertTrue(recorder.messageInvalidates().isEmpty(), "$year/$month")
            assertTrue(recorder.diplomacyUpdateDirty().isEmpty(), "$year/$month")
        }
    }

    @Test
    fun `unknown message returns typed failures without mutations`() {
        val world = world()
        val recorder = ChangeRecorder()
        val handler = DiplomaticMessageHandler(
            world = world,
            recorder = recorder,
            processNationCommand = processor(world, recorder),
            messageReader = { null },
            nowProvider = { now },
        )

        val accept = handler.handleAccept(
            TurnDaemonCommand.AcceptDiplomaticMessage(messageId = 404, generalId = 10),
        )
        val decline = handler.handleDecline(
            TurnDaemonCommand.DeclineDiplomaticMessage(messageId = 404, generalId = 10),
        )

        assertEquals("존재하지 않는 메시지입니다.", assertIs<AcceptDiplomaticMessageFail>(accept).reason)
        assertEquals("존재하지 않는 메시지입니다.", assertIs<DeclineDiplomaticMessageFail>(decline).reason)
        assertTrue(recorder.messageInvalidates().isEmpty())
        assertTrue(recorder.diplomacyUpdateDirty().isEmpty())
    }

    @Test
    fun `same batch duplicate accepts only once and records one effect set`() {
        installDaemonResolvers()
        val world = world(diplomacyState = DiplomacyState.WAR)
        val recorder = ChangeRecorder()
        val message = snapshot(option = linkedMapOf("action" to "stop_war"))
        val handler = handler(world, recorder, message)
        val command = TurnDaemonCommand.AcceptDiplomaticMessage(messageId = message.id, generalId = 10)

        val first = handler.handleAccept(command)
        val second = handler.handleAccept(command)

        assertIs<AcceptDiplomaticMessageOk>(first)
        val duplicate = assertIs<AcceptDiplomaticMessageFail>(second)
        assertEquals("유효하지 않은 외교서신입니다.", duplicate.reason)
        assertEquals(1, recorder.messageInvalidates().size)
        assertEquals(2, recorder.diplomacyUpdateDirty().size)
    }

    private fun handler(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        message: MessageSnapshot,
        processor: ProcessNationCommand? = processor(world, recorder),
    ) = DiplomaticMessageHandler(
        world = world,
        recorder = recorder,
        processNationCommand = processor,
        messageReader = { id -> message.takeIf { it.id == id } },
        nowProvider = { now },
    )

    private fun processor(world: InMemoryTurnWorld, recorder: ChangeRecorder) = ProcessNationCommand(
        world = world,
        recorder = recorder,
        hiddenSeed = "unused-by-instant",
        registry = CommandRegistry(GeneralActionPipeline()),
        startYear = 184,
    )

    private fun snapshot(
        id: Int = 77,
        mailbox: Int = 9001,
        type: String = "diplomacy",
        srcGeneralId: Int = 20,
        srcNationId: Int = 2,
        destNationId: Int = 1,
        validUntil: Instant = now.plusSeconds(3600),
        text: String = "외교 제안",
        option: Map<String, Any?> = linkedMapOf("action" to "stop_war"),
        srcArray: Map<String, Any?> = linkedMapOf("id" to srcGeneralId, "name" to "조조", "nation_id" to srcNationId),
    ) = MessageSnapshot(
        id = id,
        mailbox = mailbox,
        hasAction = option["action"] != null,
        type = type,
        srcGeneralId = srcGeneralId,
        srcNationId = srcNationId,
        destGeneralId = 0,
        destNationId = destNationId,
        time = now.minusSeconds(60),
        validUntil = validUntil,
        text = text,
        srcArray = srcArray,
        destArray = linkedMapOf("id" to 0, "name" to "", "nation_id" to destNationId),
        option = option,
    )

    private fun world(
        actor: TurnGeneral? = actor(),
        diplomacyState: Int = DiplomacyState.WAR,
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 3,
                tickSeconds = 3600,
                lastTurnTime = now,
            ),
            generals = listOfNotNull(
                actor,
                actor(id = 20, name = "조조", nationId = 2, cityId = 8, officerLevel = 12),
            ),
            cities = listOf(
                City(id = 5, name = "업", nationId = 1, level = 6, supplyState = 1),
                City(id = 8, name = "허창", nationId = 2, level = 6, supplyState = 1),
            ),
            nations = listOf(
                Nation(id = 1, name = "촉", color = "#0f0"),
                Nation(id = 2, name = "위", color = "#00f"),
                Nation(id = 3, name = "오", color = "#f00"),
            ),
            diplomacy = listOf(
                TurnDiplomacy(1, 2, diplomacyState, 9),
                TurnDiplomacy(2, 1, diplomacyState, 9),
            ),
            worldId = WorldId(1),
        ),
    )

    private fun actor(
        id: Int = 10,
        name: String = "유비",
        nationId: Int = 1,
        cityId: Int = 5,
        officerLevel: Int = 12,
        meta: Map<String, Any?> = emptyMap(),
    ) = TurnGeneral(
        id = id,
        name = name,
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        turnTime = now,
        meta = meta,
    )

    private fun installDaemonResolvers() {
        val method = DaemonLoopConfig::class.java.getDeclaredMethod(
            "installNationActionResolvers",
            GeneralActionPipeline::class.java,
        )
        method.isAccessible = true
        method.invoke(DaemonLoopConfig(), GeneralActionPipeline())
    }

    private data class ValidationCase(
        val label: String,
        val world: InMemoryTurnWorld,
        val message: MessageSnapshot,
        val reason: String,
    )

    private data class ActionDenialCase(
        val action: String,
        val option: LinkedHashMap<String, Any?>,
        val diplomacyState: Int,
        val reason: String,
    )

    private data class InvalidArgCase(
        val label: String,
        val message: MessageSnapshot,
        val reason: String,
    )

    private data class FullReasonCase(
        val label: String,
        val message: MessageSnapshot,
        val reason: String,
    )
}
