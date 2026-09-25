package opensamguk.gameapi.reserve

import opensamguk.logic.domestic.FieldInput

import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.InputRejection
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.v2.command.V2CommandArgs
import opensamguk.logic.v2.command.V2CommandSchema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Step 2 of the 8-step flow — reserve. Called by [opensamguk.gameapi.web.CommandController] for known
 * reservable commands; execution-time constraints are rechecked by the daemon.
 *
 * **Two intake models, selected by the command code** ([CommandWireMapper]):
 *
 *  A. **Turn-reserved `che_*` commands** (the default). Two effects, matching the TS split of "DB
 *     carries the reserved action, Redis carries the control signal" (devsam-core2026):
 *      1. **Durable reservation** — the action-code + arg is written to the `general_turn` ring buffer
 *         via the shared `:infra` [ReservedTurnRepository] (plain JDBC; `setGeneralTurn` faithful
 *         upsert). This is the SOURCE OF TRUTH the daemon reads when it processes the turn — NOT the
 *         Redis message.
 *      2. **Wake the daemon** — publish the EXISTING P0-B control signal to the MUTATION (command)
 *         stream. The reused wire variant is [TurnDaemonCommand.Run] with [RunReason.POKE] (the
 *         existing "wake/poke the daemon" control command — devsam-core2026 `daemon.poke()`).
 *     The reserve is ordered DB-first then publish: the durable reservation must exist before the
 *     daemon is woken, so a poke can never race ahead of its reserved action.
 *
 *  B. **Immediate daemon-command intake** (betting/auction + F4 Wave C2 single-actor commands). These
 *     are NOT turn-reserved: their engine handlers are driven by the
 *     [opensamguk.engine.run.TurnDaemonCommandDispatcher] off a TYPED [TurnDaemonCommand] on the
 *     command stream, NOT by the `general_turn` ring. For these we SKIP the ring write and publish the
 *     typed command itself (mapped from `{code, argJson, generalId}` by [CommandWireMapper]), so the
 *     daemon's `RedisCommandStream` → dispatcher → handler → `ChangeRecorder` → flush path executes
 *     the mutation. A `Run(POKE)` here would reach the dispatcher and return `null` (no handler),
 *     silently dropping the action.
 *
 * In BOTH models the envelope is the EXISTING [TurnDaemonCommandEnvelope], encoded into the one
 * `payload` field the engine-side `RedisCommandStream` consumer reads. NO new wire variant and NO
 * `:common`/wire change is introduced (OQ6 LEAD RULING) — game-api ONLY publishes; the daemon applies
 * (one-daemon-write rule).
 */
@Service
class CommandReserveService(
    private val reservedTurns: ReservedTurnRepository,
    private val commandInbox: CommandInboxRepository,
    private val commandResults: CommandResultRepository,
    private val redis: StringRedisTemplate,
    private val registry: CommandRegistry,
    processWorld: GameApiProcessWorld,
    @Value("\${opensamguk.profile:che:scenario_2}") profile: String,
    private val clock: Clock = Clock.systemUTC(),
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
    private val transactions: TransactionOperations,
    private val worldStates: opensamguk.gameapi.read.WorldStateReadRepository,
    private val hwihaAdmission: HwihaEnlistmentAdmission? = null,
    private val hwihaCourtAdmission: HwihaCourtAdmission? = null,
    private val hwihaDeployAdmission: HwihaDeployAdmission? = null,
    private val hwihaScoutAdmission: HwihaScoutAdmission? = null,
    private val hwihaTravelAdmission: HwihaTravelAdmission? = null,
    private val hwihaFieldAdmission: HwihaFieldAdmission? = null,
    private val hwihaMilitaryAdmission: HwihaMilitaryAdmission? = null,
    private val hwihaPersonalAdmission: HwihaPersonalAdmission? = null,
    private val hwihaRetireAdmission: HwihaRetireAdmission? = null,
    private val hwihaPeopleAdmission: HwihaPeopleAdmission? = null,
    private val hwihaPoliticalAdmission: HwihaPoliticalAdmission? = null,
    private val hwihaTransferAdmission: HwihaTransferAdmission? = null,
    private val hwihaLegacyDirectAdmission: HwihaLegacyDirectAdmission? = null,
    private val hwihaCatalog: InputCatalog = InputCatalog.load(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val worldId: WorldId = processWorld.worldId
    private val commandStreamKey: String = TurnDaemonStreamKeys.of(profile, worldId).commandStream

    /** The outcome of a successful reserve: the generated request id the controller returns as 202. */
    data class ReserveResult(val requestId: String, val turnIdx: Int)

    /**
     * Submit the AVAILABLE command. Selects the intake model from [actionCode] ([CommandWireMapper]):
     *
     *  - **immediate daemon-command** intake (betting/auction + C2): publish the TYPED command (mapped
     *    from `{actionCode, argJson, generalId}`) — NO `general_turn` ring write (the daemon dispatches
     *    the typed command directly to its handler).
     *  - **turn-reserved `che_*`**: write the reserved action into the `general_turn` ring FIRST (DB is
     *    the source of truth), then poke the daemon with `Run(POKE)`.
     *
     * Returns the generated [ReserveResult.requestId] (echoed to the UI as the 202 requestId) in both.
     */
    fun reserve(generalId: Int, actionCode: String, turnIdx: Int = 0, argJson: String? = null): ReserveResult =
        reserveInternal(generalId, actionCode, turnIdx, argJson, ownerUserId = null)

    fun reserveForOwner(
        generalId: Int,
        actionCode: String,
        turnIdx: Int = 0,
        argJson: String? = null,
        ownerUserId: Int,
    ): ReserveResult = reserveInternal(generalId, actionCode, turnIdx, argJson, ownerUserId)

    /** Reuse the controller's verified process-world snapshot for the same HWIHA request. */
    fun reserveWithRuleProfile(
        generalId: Int, actionCode: String, turnIdx: Int, argJson: String?, verifiedProfile: opensamguk.logic.input.RuleProfile,
    ): ReserveResult = reserveInternal(generalId, actionCode, turnIdx, argJson, null, verifiedProfile)

    fun reserveForOwnerWithRuleProfile(
        generalId: Int, actionCode: String, turnIdx: Int, argJson: String?, ownerUserId: Int,
        verifiedProfile: opensamguk.logic.input.RuleProfile,
    ): ReserveResult = reserveInternal(generalId, actionCode, turnIdx, argJson, ownerUserId, verifiedProfile)

    fun reserveV2(
        generalId: Int,
        schema: V2CommandSchema,
        args: V2CommandArgs,
        ownerUserId: Int,
    ): ReserveResult {
        val requestId = requestIds()
        val acceptedAt = Instant.now(clock)
        val command = CommandWireMapper.toV2Command(
            schema = schema,
            args = args,
            generalId = generalId,
            requestId = requestId,
            expiresAt = acceptedAt.plus(schema.expiry).toString(),
        )
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = acceptedAt.toString(),
            command = command,
        )
        val payload = encodeCommandPayload(envelope)
        transactions.executeWithoutResult {
            commandInbox.insertAccepted(
                AcceptedCommand(
                    worldId = worldId,
                    requestId = requestId,
                    commandKind = CommandKind.IMMEDIATE,
                    intentFingerprint = intentFingerprint(
                        CommandKind.IMMEDIATE,
                        generalId,
                        0,
                        schema.canonicalId,
                        args.toString(),
                        ownerUserId,
                    ),
                    generalId = generalId,
                    turnIdx = 0,
                    actionCode = schema.canonicalId,
                    payloadJson = payload,
                    ownerUserId = ownerUserId,
                ),
            ).throwIfConflict()
        }
        publishAfterCommit(envelope)
        return ReserveResult(requestId = requestId, turnIdx = 0)
    }

    private fun reserveInternal(
        generalId: Int,
        actionCode: String,
        turnIdx: Int,
        argJson: String?,
        ownerUserId: Int?,
        verifiedProfile: opensamguk.logic.input.RuleProfile? = null,
    ): ReserveResult {
        val worldProfile = (verifiedProfile ?: worldStates.processRuleProfile())
            ?: throw HwihaAdmissionDenied("POLICY_UNAVAILABLE", "세계 규칙을 확인할 수 없습니다.")
        if (worldProfile == RuleProfile.HWIHA && actionCode !in COMMON_INTAKE_COMMANDS) {
            // The sandbox V2 endpoints call this service directly. Their registered aliases belong
            // to another ruleset, even though their spelling is outside the HWIHA input grammar.
            if (opensamguk.logic.v2.command.V2CommandRegistry.resolve(actionCode) != null)
                throw HwihaAdmissionDenied(InputRejection.WRONG_RULE_PROFILE.name,
                    InputRejection.WRONG_RULE_PROFILE.message)
            val rejection = hwihaCatalog.rejectionFor(worldProfile, actionCode)
                ?: if (actionCode !in HWIHA_RESERVABLE_ACTIONS) InputRejection.INVALID_INPUT_CHANNEL else null
            if (rejection != null) throw HwihaAdmissionDenied(rejection.name, rejection.message)
        }
        val canonicalArgs = if (actionCode in opensamguk.logic.input.EnlistmentInput.INPUT_IDS) {
            (hwihaAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name, opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(generalId, ownerUserId, turnIdx, argJson, actionCode)
        } else if (actionCode == "action.deploy") {
            (hwihaDeployAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode == "action.scout") {
            (hwihaScoutAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.TravelInput.INPUT_IDS) {
            (hwihaTravelAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.domestic.FieldInput.INPUT_IDS) {
            (hwihaFieldAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.MilitaryInput.INPUT_IDS) {
            (hwihaMilitaryAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.PersonalInput.FIELD_IDS) {
            (hwihaPersonalAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode == opensamguk.logic.input.RetireInput.INPUT_ID) {
            (hwihaRetireAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.PeopleInput.INPUT_IDS) {
            (hwihaPeopleAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.PoliticalInput.INPUT_IDS) {
            (hwihaPoliticalAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.TransferInput.INPUT_IDS) {
            (hwihaTransferAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in opensamguk.logic.input.DirectInput.INPUT_IDS) {
            (hwihaLegacyDirectAdmission ?: throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                opensamguk.logic.input.InputRejection.NOT_DELIVERED.message))
                .canonicalArguments(actionCode, generalId, ownerUserId, turnIdx, argJson)
        } else if (actionCode in HWIHA_SIEGE_ACTIONS) {
            // 강공·항복 권고는 인자가 없다. 포위 여부는 실행 턴에 다시 본다(§4 — 조건이 안 맞으면 비용 없이 무효).
            if (ownerUserId == null || ownerUserId <= 0) throw HwihaAdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
            if (argJson != null && argJson.trim() !in setOf("", "{}")) throw HwihaAdmissionDenied("INVALID_REQUEST", "이 입력은 인자를 받지 않습니다.")
            if (opensamguk.logic.input.InputCatalog.load()[actionCode]?.deliveryState?.hasHandler != true)
                throw HwihaAdmissionDenied(opensamguk.logic.input.InputRejection.NOT_DELIVERED.name,
                    opensamguk.logic.input.InputRejection.NOT_DELIVERED.message)
            "{}"
        } else argJson
        val requestId = requestIds()
        val acceptedAt = Instant.now(clock)
        val v2Schema = opensamguk.logic.v2.command.V2CommandRegistry.resolve(actionCode)

        // Model B — immediate daemon-command intake: publish the typed command, NO ring reservation.
        val intake = CommandWireMapper.toCommand(
            actionCode,
            generalId,
            requestId,
            argJson,
            ownerUserId,
            expiresAt = v2Schema?.let { acceptedAt.plus(it.expiry).toString() },
        )
        if (intake != null) {
            val envelope = TurnDaemonCommandEnvelope(
                requestId = requestId,
                sentAt = acceptedAt.toString(),
                command = intake,
            )
            val payload = encodeCommandPayload(envelope)
            transactions.executeWithoutResult {
                commandInbox.insertAccepted(
                    AcceptedCommand(
                        worldId = worldId,
                        requestId = requestId,
                        commandKind = CommandKind.IMMEDIATE,
                        intentFingerprint = intentFingerprint(CommandKind.IMMEDIATE, generalId, turnIdx, actionCode, canonicalArgs, ownerUserId),
                        generalId = generalId,
                        turnIdx = turnIdx,
                        actionCode = actionCode,
                        payloadJson = payload,
                        // OPENSAM-197 — 결과 조회 소유권 검사의 근거. 장수선택처럼 아직 소유하지 않은
                        // 장수로 내는 명령은 이 값이 유일한 제출자 증거다.
                        ownerUserId = ownerUserId,
                    ),
                ).throwIfConflict()
            }
            publishAfterCommit(envelope)
            return ReserveResult(requestId = requestId, turnIdx = turnIdx)
        }

        // Model A — turn-reserved che_* command.
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = acceptedAt.toString(),
            command = TurnDaemonCommand.Run(reason = RunReason.POKE),
        )
        val payload = encodeCommandPayload(envelope)
        val fingerprint = intentFingerprint(CommandKind.RESERVED_TURN, generalId, turnIdx, actionCode, canonicalArgs, ownerUserId)
        var inserted = false
        transactions.executeWithoutResult {
            val result = commandInbox.insertAccepted(
                AcceptedCommand(
                    worldId = worldId,
                    requestId = requestId,
                    commandKind = CommandKind.RESERVED_TURN,
                    intentFingerprint = fingerprint,
                    generalId = generalId,
                    turnIdx = turnIdx,
                    actionCode = actionCode,
                    payloadJson = payload,
                    ownerUserId = ownerUserId,
                ),
            )
            result.throwIfConflict()
            inserted = result is CommandInboxRepository.InsertResult.Inserted
            if (inserted) {
                reservedTurns.reserve(
                    worldId = worldId,
                    generalId = generalId,
                    turnIdx = turnIdx,
                    actionCode = actionCode,
                    argJson = canonicalArgs,
                    brief = if (worldProfile == RuleProfile.HWIHA)
                        requireNotNull(hwihaCatalog[actionCode]?.displayName) { "missing action displayName: $actionCode" }
                    else registry.resolve(actionCode).name,
                    requestId = requestId,
                )
                commandResults.insertTerminalResult(
                    worldId = worldId,
                    row = CommandTerminalResultFactory.acceptedRow(
                        worldId = worldId,
                        requestId = requestId,
                        sentAt = Instant.now(clock),
                        type = "reservationAccepted",
                        commandKind = CommandKind.RESERVED_TURN,
                        actionCode = actionCode,
                        generalId = generalId,
                        turnIdx = turnIdx,
                    ),
                    expectedInboxStatuses = setOf("ACCEPTED"),
                )
            }
        }

        if (inserted) publishAfterCommit(envelope)
        return ReserveResult(requestId = requestId, turnIdx = turnIdx)
    }

    /**
     * Ring-less immediate publish — used by Join (B1) and other out-of-band daemon commands
     * that carry their own typed command (no general_turn reservation).
     *
     * OPENSAM-197 — [ownerUserId] is the submitting account. This path records no `general_id`,
     * so the account is the ONLY ownership witness: without it the returned `requestId` can never
     * be read back through `GET /api/command/result/{requestId}`. Pass it wherever a principal
     * exists; machine-to-machine publishes (profile-icon sync) and admin batch actions have no
     * user to attribute and stay null — their callers do not poll the result.
     */
    fun publishImmediate(command: TurnDaemonCommand): ReserveResult = publishImmediate(command, null)

    fun publishImmediate(command: TurnDaemonCommand, ownerUserId: Int?): ReserveResult {
        val requestId = requestIds()
        val boundCommand = if (command is TurnDaemonCommand.ImmediateInput) {
            val owner = ownerUserId?.takeIf { it > 0 }
                ?: throw HwihaAdmissionDenied("UNAUTHORIZED", "제출자 인증이 필요합니다.")
            val admission = hwihaCourtAdmission ?: throw HwihaAdmissionDenied("POLICY_UNAVAILABLE", "발령 정책을 확인할 수 없습니다.")
            command.copy(requestId = requestId, ownerUserId = owner,
                argJson = admission.canonicalArguments(command.generalId, owner, command.inputId, command.argJson))
        } else command
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = Instant.now(clock).toString(),
            command = boundCommand,
        )
        val payload = encodeCommandPayload(envelope)
        transactions.executeWithoutResult {
            commandInbox.insertAccepted(
                AcceptedCommand(
                    worldId = worldId,
                    requestId = requestId,
                    commandKind = CommandKind.IMMEDIATE,
                    intentFingerprint = if (boundCommand is TurnDaemonCommand.ImmediateInput) {
                        intentFingerprint(CommandKind.IMMEDIATE, boundCommand.generalId, 0,
                            boundCommand.inputId, boundCommand.argJson, boundCommand.ownerUserId)
                    } else intentFingerprint(CommandKind.IMMEDIATE, null, 0, command::class.simpleName, null, null),
                    generalId = null,
                    turnIdx = 0,
                    actionCode = if (boundCommand is TurnDaemonCommand.ImmediateInput) {
                        // Keep the existing inbox value until the storage identifier migration.
                        "HwihaCourtInput"
                    } else command::class.simpleName,
                    payloadJson = payload,
                    ownerUserId = ownerUserId,
                ),
            ).throwIfConflict()
        }
        publishAfterCommit(envelope)
        return ReserveResult(requestId = requestId, turnIdx = 0)
    }

    private fun publishAfterCommit(envelope: TurnDaemonCommandEnvelope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        publishBestEffort(envelope)
                    }
                },
            )
        } else {
            publishBestEffort(envelope)
        }
    }

    private fun publishBestEffort(envelope: TurnDaemonCommandEnvelope) {
        try {
            publish(envelope)
            commandInbox.markRedisWakePublished(worldId, envelope.requestId, Instant.now(clock))
        } catch (_: Exception) {
            log.warn(
                "command Redis wake publish or marker failed; requestId={}, worldId={}, commandStreamKey={}",
                envelope.requestId,
                worldId.value,
                commandStreamKey,
            )
        }
    }

    private fun publish(envelope: TurnDaemonCommandEnvelope) {
        val record: ObjectRecord<String, Map<String, String>> = StreamRecords
            .newRecord()
            .ofObject(mapOf(WIRE_PAYLOAD_FIELD to encodeCommandPayload(envelope)))
            .withStreamKey(commandStreamKey)
        @Suppress("UNCHECKED_CAST")
        redis.opsForStream<Any, Any>().add(record as ObjectRecord<String, Any>)
    }

    private fun CommandInboxRepository.InsertResult.throwIfConflict() {
        if (this is CommandInboxRepository.InsertResult.Conflict) {
            throw IllegalStateException("command_inbox request_id conflict")
        }
    }

    private fun intentFingerprint(
        kind: CommandKind,
        generalId: Int?,
        turnIdx: Int?,
        actionCode: String?,
        argJson: String?,
        ownerUserId: Int?,
    ): String {
        val canonical = listOf(
            "v1",
            worldId.value.toString(),
            kind.name,
            generalId?.toString().orEmpty(),
            turnIdx?.toString().orEmpty(),
            actionCode.orEmpty(),
            argJson.orEmpty(),
            ownerUserId?.toString().orEmpty(),
        ).joinToString("\u001f")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** HWIHA 강공·항복 권고 — 인자 없는 개인 행동. */
        val HWIHA_SIEGE_ACTIONS: Set<String> = setOf("action.assault", "action.demandSurrender")

        /** HWIHA 월드가 12순 목록에 받는 개인 행동. */
        val HWIHA_RESERVABLE_ACTIONS: Set<String> = setOf("action.deploy", "action.scout") +
            setOf("action.enlist") +
            opensamguk.logic.input.TravelInput.INPUT_IDS + opensamguk.logic.domestic.FieldInput.INPUT_IDS +
            opensamguk.logic.input.MilitaryInput.INPUT_IDS +
            opensamguk.logic.input.PersonalInput.FIELD_IDS +
            opensamguk.logic.input.RetireInput.INPUT_ID +
            opensamguk.logic.input.PeopleInput.INPUT_IDS +
            opensamguk.logic.input.PoliticalInput.INPUT_IDS +
            opensamguk.logic.input.TransferInput.INPUT_IDS +
            opensamguk.logic.input.DirectInput.INPUT_IDS + HWIHA_SIEGE_ACTIONS
        /** Shared board and mailbox intake, dispatched immediately outside the game turn ring. */
        val COMMON_INTAKE_COMMANDS: Set<String> = setOf(
            "boardArticle", "boardComment", "boardRead", "sendMessage", "deleteMessage", "readLatestMessage",
            "selectPoolUpdate",
        )
    }
}

/**
 * Bean wiring for reserve: the shared `:infra` [ReservedTurnRepository] is a plain JDBC class (no
 * `@Repository` stereotype), so it is published here against Boot's auto-configured
 * [NamedParameterJdbcTemplate]. The write path stays JDBC-only — no JPA `EntityManager`.
 */
@Configuration
class ReserveBeans {
    @Bean
    fun reservedTurnRepository(jdbc: NamedParameterJdbcTemplate): ReservedTurnRepository =
        ReservedTurnRepository(jdbc)

    @Bean
    fun commandInboxRepository(jdbc: NamedParameterJdbcTemplate): CommandInboxRepository =
        CommandInboxRepository(jdbc)

    @Bean
    fun commandResultRepository(jdbc: NamedParameterJdbcTemplate): CommandResultRepository =
        CommandResultRepository(jdbc)

    @Bean
    fun gameApiTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager)
}
