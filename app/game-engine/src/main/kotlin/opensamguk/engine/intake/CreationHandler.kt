package opensamguk.engine.intake

import opensamguk.common.wire.CreateGeneralResult
import opensamguk.common.wire.CreationCustomChoice
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.constants.GameConst
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.creation.CreationAdmission
import opensamguk.logic.creation.CreationKind
import opensamguk.logic.creation.CreationEntryRole
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationSelectionPolicy
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.NativeCountyLedger
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.renown.RenownRules
import java.time.Clock
import java.time.Instant

/** New product creation path. The legacy MakeGeneralHandler keeps its parity rules. */
class CreationHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val nameRule: CreationNameRule = CreationNameRule.APPROVED,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: TurnDaemonCommand.CreateGeneral): CreateGeneralResult {
        fun reject(code: String) = CreateGeneralResult(ok = false, errorCode = code)
        val state = world.getState()
        val policy = runCatching { CreationSelectionPolicy.load() }.getOrNull()
        val kind = runCatching { CreationKind.valueOf(command.choiceKind) }.getOrNull()
            ?: return reject(CreationAdmission.Failure.INVALID_REQUEST.name)
        if (command.accountId <= 0 || command.clientRequestId.isBlank() ||
            (kind == CreationKind.CUSTOM) != (command.custom != null) ||
            (kind == CreationKind.HISTORICAL) != (command.historicalGeneralId != null))
            return reject(CreationAdmission.Failure.INVALID_REQUEST.name)
        val sourceReady = policy != null
        val admitted = CreationAdmission.gate(
            CreationAdmission.Gate(
                expectedWorldId = command.worldId,
                routedWorldId = world.worldId.value,
                isHwiha = state.ruleProfile == RuleProfile.HWIHA,
                serverOpen = state.status == "OPEN",
                seasonRunning = (state.meta["isunited"] as? Number)?.toInt() == 0,
                alreadyOwnsGeneral = world.listGenerals().any {
                    it.userId == command.accountId.toString() && it.npcState < 2
                },
                kind = kind,
                sourceReady = sourceReady,
                capacityAvailable = world.listGenerals().count { it.npcState < 2 } <
                    ((state.config["maxgeneral"] as? Number)?.toInt() ?: GameConst.defaultMaxGeneral),
            ), policy,
        )
        if (admitted != null) return reject(admitted.name)
        val activePolicy = requireNotNull(policy)
        return when (kind) {
            CreationKind.CUSTOM -> createCustom(command.accountId, requireNotNull(command.custom), activePolicy,
                nameRule)
            CreationKind.HISTORICAL -> claimHistorical(command.accountId, requireNotNull(command.historicalGeneralId))
        }
    }

    private fun createCustom(accountId: Int, choice: CreationCustomChoice,
        policy: CreationSelectionPolicy, rule: CreationNameRule): CreateGeneralResult {
        fun reject(code: String) = CreateGeneralResult(ok = false, errorCode = code)
        val county = world.getCityById(choice.nativeCountyId)
        val role = choice.role?.let { runCatching { CreationEntryRole.valueOf(it) }.getOrNull() }
            ?: return reject(CreationAdmission.Failure.INVALID_REQUEST.name)
        val selectable = if (county != null && world.landNodeOfCity(county.id) != null)
            setOf(county.id) else emptySet()
        val input = CreationAdmission.Custom(choice.name, choice.nativeCountyId,
            CreationAdmission.Stats(choice.leadership, choice.strength, choice.intel, choice.politics, choice.charm),
            choice.ideologyId, choice.traitId, role)
        CreationAdmission.custom(input, policy, selectable, rule::normalize)?.let { return reject(it.name) }
        val name = requireNotNull(rule.normalize(choice.name))
        val key = requireNotNull(rule.uniqueKey(name))
        if (world.listGenerals().any { rule.collisionKeyExisting(it.name) == key })
            return reject(CreationAdmission.Failure.NAME_ALREADY_USED.name)
        if (choice.imageServer !in 0..1 || (choice.imageServer == 1 && choice.picture.isNullOrBlank()))
            return reject(CreationAdmission.Failure.INVALID_REQUEST.name)
        val state = world.getState()
        val generalId = world.allocateGeneralId()
        val age = 20
        val metadata = linkedMapOf<String, Any?>(
            "born_year" to (state.currentYear - age),
            "dead_year" to 300,
            "start_age" to age,
            "picture" to (choice.picture?.takeIf(String::isNotBlank) ?: "default.jpg"),
            "image_server" to choice.imageServer,
            "nativeCountyId" to choice.nativeCountyId,
            "creationIdeologyId" to choice.ideologyId,
            "creationTraitId" to choice.traitId,
            "creationNameKeyV1" to key,
            LordStatus.META_KEY to false,
            PersonPolicyState.META_KEY to PersonPolicyState(
                RenownRules.INITIAL_CAPACITY, false, NativeCountyLedger.CREATED_GENERAL_SOURCE,
                "v1", generalId,
            ).toMetaValue(),
        )
        val general = TurnGeneral(
            id = generalId, userId = accountId.toString(), name = name, nationId = 0,
            cityId = choice.nativeCountyId, troopId = 0,
            stats = GeneralStats(choice.leadership, choice.strength, choice.intel, choice.politics, choice.charm),
            experience = 0, dedication = 0, officerLevel = 0, age = age, npcState = 0,
            turnTime = state.lastTurnTime.plusSeconds(state.tickSeconds.toLong().coerceAtLeast(1)),
            meta = metadata,
        )
        recorder.recordGeneralCreate(world, general)
        recorder.recordAccessLogUpsert(world,
            GeneralAccessLog(generalId = generalId, userId = accountId.toLong(), lastRefresh = Instant.now(clock)))
        return CreateGeneralResult(ok = true, generalId = generalId)
    }

    private fun claimHistorical(accountId: Int, generalId: Int): CreateGeneralResult {
        fun reject(code: String) = CreateGeneralResult(ok = false, errorCode = code)
        val before = world.getGeneralById(generalId)
        val state = before?.let { person ->
            CreationAdmission.HistoricalState(
                appeared = (person.meta["rtk14_appearance_year"] as? Number)?.toInt()?.let {
                    it <= world.getState().currentYear
                } ?: true,
                alive = CreationAdmission.historicalAliveInYear(world.getState().currentYear, person.meta),
                alreadyClaimed = person.userId?.toLongOrNull()?.let { it > 0 } == true || person.npcState != 2,
                // D81: a bound historical person keeps the existing retinue relation on claim.
                affiliationSelectable = true,
                locationValid = world.positionOf(person.id) != null,
                nativeCountyId = null,
            )
        }
        CreationAdmission.historical(CreationAdmission.Historical(generalId), state)?.let {
            return reject(it.name)
        }
        val original = requireNotNull(before)
        val claimed = original.copy(userId = accountId.toString(), npcState = 1)
        world.applyGeneralDirtyFree(claimed)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(original), PerTurnOverlay.toLogicGeneral(claimed))
        recorder.recordAccessLogUpsert(world,
            GeneralAccessLog(generalId = generalId, userId = accountId.toLong(), lastRefresh = Instant.now(clock)))
        return CreateGeneralResult(ok = true, generalId = generalId)
    }
}
