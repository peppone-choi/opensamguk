package opensamguk.gameapi.creation

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.CreateGeneralResult
import opensamguk.common.wire.CreationCustomChoice
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.dto.GeneralCreationAcceptedDto
import opensamguk.gameapi.dto.GeneralCreationChoiceDto
import opensamguk.gameapi.dto.GeneralCreationRequestDto
import opensamguk.gameapi.member.MemberProfileClient
import opensamguk.gameapi.member.MemberProfileUnavailableException
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.read.processRuleProfile
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.logic.creation.CreationAdmission
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationRequestFingerprint
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.RuleProfile
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

class CreationAdmissionException(val code: String) : RuntimeException(code)

/** A public UUID receipt and a private command inbox row are committed together. */
@Service
class GeneralCreationService(
    private val receipts: CreationReceiptRepository,
    private val inbox: CommandInboxRepository,
    private val redis: StringRedisTemplate,
    private val transactions: TransactionOperations,
    private val worlds: WorldStateReadRepository,
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val retainers: RetainerReadRepository,
    private val spatial: SpatialStateReadRepository,
    private val members: MemberProfileClient,
    processWorld: GameApiProcessWorld,
    @Value("\${opensamguk.profile:pep:scenario_990002}") profile: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val worldId = processWorld.worldId
    private val commandStreamKey = TurnDaemonStreamKeys.of(profile, worldId).commandStream
    private val clock: Clock = Clock.systemUTC()
    private val nameRule: CreationNameRule = CreationNameRule.APPROVED

    fun submit(accountId: Long, request: GeneralCreationRequestDto): GeneralCreationAcceptedDto {
        if (accountId !in 1..Int.MAX_VALUE.toLong()) throw CreationAdmissionException("INVALID_REQUEST")
        val uuid = runCatching { UUID.fromString(request.clientRequestId) }.getOrNull()
            ?.takeIf { it.toString() == request.clientRequestId }
            ?: throw CreationAdmissionException("INVALID_REQUEST")
        val parsed = parseChoice(request.choice)
        val hash = CreationRequestFingerprint.sha256(request.expectedWorldId, parsed.fingerprint)
        if (request.expectedWorldId != worldId.value) throw CreationAdmissionException("WORLD_CHANGED")
        val internalId = CreationRequestFingerprint.commandRequestId(accountId, worldId.value, request.clientRequestId)
        val accepted = GeneralCreationAcceptedDto(requestId = request.clientRequestId, worldId = worldId.value)
        transactions.executeWithoutResult {
            val prior = receipts.find(worldId.value, accountId, uuid)
            if (prior != null) {
                if (prior.bodySha256 != hash || prior.choiceKind != parsed.kind.name)
                    throw CreationAdmissionException("REQUEST_ID_REUSED")
                return@executeWithoutResult
            }
            val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
                ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
            val state = worlds.findProcessWorld() ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
            val max = (state.config["maxgeneral"] as? Number)?.toInt() ?: GameConst.defaultMaxGeneral
            val gate = CreationAdmission.gate(CreationAdmission.Gate(
                expectedWorldId = request.expectedWorldId,
                routedWorldId = worldId.value,
                isHwiha = worlds.processRuleProfile() == RuleProfile.HWIHA,
                serverOpen = state.status == "OPEN",
                seasonRunning = state.isunited == 0,
                alreadyOwnsGeneral = generals.findByUserId(accountId.toString()) != null,
                kind = parsed.kind,
                sourceReady = true,
                capacityAvailable = generals.countByNpcStateLessThan(2) < max,
            ), policy)
            if (gate != null) throw CreationAdmissionException(gate.name)
            if (parsed.kind == CreationKind.CUSTOM) validateCustom(parsed, policy)
            else validateHistorical(parsed)
            val row = CreationReceiptRow(worldId.value, accountId, uuid, internalId, hash, parsed.kind.name)
            if (!receipts.insertIfAbsent(row)) {
                val concurrent = receipts.find(worldId.value, accountId, uuid)
                if (concurrent?.bodySha256 != hash || concurrent.choiceKind != parsed.kind.name)
                    throw CreationAdmissionException("REQUEST_ID_REUSED")
                return@executeWithoutResult
            }
            val portrait = try {
                if (parsed.kind == CreationKind.CUSTOM) members.get(accountId) else null
            } catch (_: MemberProfileUnavailableException) {
                throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
            }
            val sourceCommand = parsed.command
            val command = sourceCommand.copy(accountId = accountId.toInt(), worldId = worldId.value,
                clientRequestId = request.clientRequestId,
                custom = sourceCommand.custom?.copy(
                    picture = portrait?.picture?.takeIf { it.isNotBlank() },
                    imageServer = portrait?.imageServer?.takeIf { it in 0..1 } ?: 0,
                ))
            val envelope = TurnDaemonCommandEnvelope(internalId, Instant.now(clock).toString(), command)
            when (inbox.insertAccepted(AcceptedCommand(
                worldId = worldId, requestId = internalId, commandKind = CommandKind.IMMEDIATE,
                intentFingerprint = hash, generalId = null, turnIdx = 0, actionCode = "createGeneral",
                payloadJson = encodeCommandPayload(envelope), ownerUserId = accountId.toInt(),
            ))) {
                CommandInboxRepository.InsertResult.Inserted -> Unit
                else -> throw IllegalStateException("creation receipt/inbox identity collision")
            }
            publishAfterCommit(envelope)
        }
        return accepted
    }

    private data class ParsedChoice(
        val kind: CreationKind,
        val fingerprint: CreationRequestFingerprint.Choice,
        val command: TurnDaemonCommand.CreateGeneral,
    )

    private fun parseChoice(choice: GeneralCreationChoiceDto): ParsedChoice = when (choice.kind) {
        "CUSTOM" -> {
            if (choice.historicalGeneralId != null) throw CreationAdmissionException("INVALID_REQUEST")
            val stats = choice.stats ?: throw CreationAdmissionException("INVALID_REQUEST")
            val custom = CreationAdmission.Custom(
                choice.name ?: throw CreationAdmissionException("INVALID_REQUEST"),
                choice.nativeCountyId ?: throw CreationAdmissionException("INVALID_REQUEST"),
                CreationAdmission.Stats(stats.leadership, stats.strength, stats.intel, stats.politics, stats.charm),
                choice.ideologyId ?: throw CreationAdmissionException("INVALID_REQUEST"),
                choice.traitId ?: throw CreationAdmissionException("INVALID_REQUEST"),
            )
            ParsedChoice(CreationKind.CUSTOM, CreationRequestFingerprint.Choice.Custom(custom),
                TurnDaemonCommand.CreateGeneral(0, 0, "", "CUSTOM", custom = CreationCustomChoice(
                    custom.name, custom.nativeCountyId, stats.leadership, stats.strength,
                    stats.intel, stats.politics, stats.charm, custom.ideologyId, custom.traitId,
                )))
        }
        "HISTORICAL" -> {
            if (choice.name != null || choice.nativeCountyId != null || choice.stats != null ||
                choice.ideologyId != null || choice.traitId != null)
                throw CreationAdmissionException("INVALID_REQUEST")
            val id = choice.historicalGeneralId ?: throw CreationAdmissionException("INVALID_REQUEST")
            ParsedChoice(CreationKind.HISTORICAL,
                CreationRequestFingerprint.Choice.Historical(CreationAdmission.Historical(id)),
                TurnDaemonCommand.CreateGeneral(0, 0, "", "HISTORICAL", historicalGeneralId = id))
        }
        else -> throw CreationAdmissionException("INVALID_REQUEST")
    }

    private fun validateCustom(choice: ParsedChoice, policy: CreationSelectionPolicy) {
        val custom = (choice.fingerprint as CreationRequestFingerprint.Choice.Custom).value
        val rule = nameRule
        val selected = artifacts.resolve()?.artifacts
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val countyIds = selected.projection.administrativeCountyIds.filter { id ->
            selected.projection.bindingsByCityId[id]?.landProvinceId != null && cities.existsById(id)
        }.toSet()
        CreationAdmission.custom(custom, policy, countyIds, rule::normalize)?.let {
            throw CreationAdmissionException(it.name)
        }
        val key = rule.uniqueKey(custom.name) ?: throw CreationAdmissionException("INVALID_NAME")
        if (generals.findAll().any { rule.collisionKeyExisting(it.name) == key })
            throw CreationAdmissionException("NAME_ALREADY_USED")
    }

    private fun validateHistorical(choice: ParsedChoice) {
        val id = (choice.fingerprint as CreationRequestFingerprint.Choice.Historical).value.generalId
        if (id <= 0 || generals.findById(id).isEmpty)
            throw CreationAdmissionException("HISTORICAL_PERSON_NOT_APPEARED")
        val general = generals.findById(id).orElseThrow()
        val currentYear = worlds.findProcessWorld()?.currentYear
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val appearanceYear = (general.meta["rtk14_appearance_year"] as? Number)?.toInt()
        if (appearanceYear != null && appearanceYear > currentYear)
            throw CreationAdmissionException("HISTORICAL_PERSON_NOT_APPEARED")
        val deathYear = (general.meta["dead_year"] as? Number)?.toInt()
        if (general.npcState != 2 || general.userId?.toLongOrNull()?.let { it > 0 } == true ||
            (deathYear != null && currentYear >= deathYear) ||
            general.cityId <= 0 || !cities.existsById(general.cityId))
            throw CreationAdmissionException("HISTORICAL_PERSON_UNAVAILABLE")
        val bundle = artifacts.resolve()?.artifacts
            ?: throw CreationAdmissionException("CREATION_POLICY_UNAVAILABLE")
        val position = runCatching { spatial.readSnapshot(worldId.value, bundle.projection.topology)
            .generalPositionSnapshot.stateFor(id) }.getOrNull()
            ?: throw CreationAdmissionException("HISTORICAL_PERSON_UNAVAILABLE")
        if (position.generalId != id || retainers.isBound(id))
            throw CreationAdmissionException("HISTORICAL_PERSON_UNAVAILABLE")
    }

    private fun publishAfterCommit(envelope: TurnDaemonCommandEnvelope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = publishBestEffort(envelope)
            })
        } else publishBestEffort(envelope)
    }

    private fun publishBestEffort(envelope: TurnDaemonCommandEnvelope) {
        try {
            val record: ObjectRecord<String, Map<String, String>> = StreamRecords.newRecord()
                .ofObject(mapOf(WIRE_PAYLOAD_FIELD to encodeCommandPayload(envelope)))
                .withStreamKey(commandStreamKey)
            @Suppress("UNCHECKED_CAST")
            redis.opsForStream<Any, Any>().add(record as ObjectRecord<String, Any>)
            inbox.markRedisWakePublished(worldId, envelope.requestId, Instant.now(clock))
        } catch (_: Exception) {
            log.warn("creation Redis wake/marker failed; worldId={}, requestId={}",
                worldId.value, envelope.requestId)
        }
    }
}

/** Receipt ownership is resolved before reading an internal result or a general body. */
@Service
class GeneralCreationResultService(
    private val receipts: CreationReceiptRepository,
    private val results: CommandResultRepository,
    private val generals: GeneralReadRepository,
    processWorld: GameApiProcessWorld,
) : CreationResultSources {
    private val worldId = processWorld.worldId
    fun read(accountId: Long, requestId: String): CreationResultProjection =
        CreationResultReader(this).read(accountId, worldId.value, requestId)

    override fun receipt(accountId: Long, worldId: Int, clientRequestId: String): CreationReceiptView? {
        val uuid = runCatching { UUID.fromString(clientRequestId) }.getOrNull()
            ?.takeIf { it.toString() == clientRequestId } ?: return null
        return receipts.find(worldId, accountId, uuid)?.let {
            CreationReceiptView(it.accountId, it.worldId, it.clientRequestId.toString(),
                it.internalCommandRequestId)
        }
    }

    override fun terminal(worldId: Int, internalCommandRequestId: String): CreationTerminalView? {
        val payload = results.findResultPayload(this.worldId, internalCommandRequestId) ?: return null
        val envelope = runCatching {
            WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), payload)
        }.getOrNull() ?: return null
        if (envelope.requestId != internalCommandRequestId) return null
        val result = (envelope.event as? TurnDaemonEvent.CommandResult)?.result as? CreateGeneralResult
            ?: return null
        return CreationTerminalView(internalCommandRequestId, envelope.committedWorldVersion ?: -1,
            result.generalId, result.errorCode, result.errorCode?.let(CreationErrorMessages::forCode))
    }

    override fun owner(worldId: Int, generalId: Int): CreationOwnedGeneralView? {
        val general = generals.findById(generalId).orElse(null) ?: return null
        if (general.npcState >= 2) return null
        val account = general.userId?.toLongOrNull() ?: return null
        return CreationOwnedGeneralView(worldId, generalId, account)
    }
}
