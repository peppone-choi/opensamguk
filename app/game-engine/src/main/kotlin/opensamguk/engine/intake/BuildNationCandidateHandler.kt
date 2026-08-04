package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.constants.GameConst
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.EngineGeneralActionPipelineBuilder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Instant

class BuildNationCandidateHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handle(command: TurnDaemonCommand.BuildNationCandidate): TurnDaemonCommandResult {
        val actor = world.getGeneralById(command.generalId)
            ?: return failure(command.generalId, "장수가 없습니다")
        AccessLogThrottle(world, recorder).increaseAndBlocked(command.generalId)
        if (world.getState().lastTurnTime.isAfter(openTime())) {
            return failure(command.generalId, "게임이 시작되었습니다.")
        }
        if (actor.nationId != 0) {
            return failure(command.generalId, "이미 국가에 소속되어있습니다.")
        }
        val available = value("availableGeneralCommand") as? Collection<*>
        if (available != null && "che_거병" !in available) {
            return failure(command.generalId, "거병할 수 없는 모드입니다.")
        }

        val startYear = intValue("startyear", "startYear") ?: world.getState().currentYear
        if (world.getState().currentYear - startYear + 1 >= GameConst.openingPartYear) {
            return failure(command.generalId, "초반이 지났습니다.")
        }
        if (((actor.meta["makelimit"] as? Number)?.toInt() ?: 0) != 0) {
            return failure(command.generalId, "재야가 된지 ${GameConst.joinActionLimit}턴이 지나야 합니다.")
        }
        val penalty = actor.meta["penalty"] as? Map<*, *>
        penalty?.get("noFoundNation")?.let { reason ->
            return failure(command.generalId, "징계 사유: $reason")
        }
        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = value("hiddenSeed")?.toString() ?: "",
            startYear = startYear,
            scenario = intValue("scenario") ?: 0,
            turnTerm = intValue("turnterm", "turnTerm") ?: 60,
            pipelineBuilder = EngineGeneralActionPipelineBuilder(world, startYear),
            recorder = recorder,
        )
        val now = world.getState().lastTurnTime.atZone(ZoneOffset.UTC)
        val outcome = handler.handle(
            command.generalId,
            "che_거병",
            world.getState().currentYear,
            world.getState().currentMonth,
            now.format(DateTimeFormatter.ofPattern("HH:mm")),
        )
        if (outcome.fellBack) {
            return failure(command.generalId, outcome.denyReason ?: "거병을 실패했습니다.")
        }

        val current = world.getGeneralById(command.generalId) ?: actor
        val nextMeta = LinkedHashMap(current.meta)
        nextMeta["killturn"] = intValue("killturn") ?: 0
        val post = current.copy(meta = nextMeta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(current), PerTurnOverlay.toLogicGeneral(post))
        world.applyGeneralDirtyFree(post)
        return GeneralBoolResult(type = "buildNationCandidate", ok = true, generalId = command.generalId)
    }

    private fun value(key: String): Any? = world.getState().config[key] ?: world.getState().meta[key]

    private fun intValue(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { (value(it) as? Number)?.toInt() }

    private fun openTime(): Instant = when (val raw = value("opentime") ?: value("openTime")) {
        is Instant -> raw
        is String -> runCatching { Instant.parse(raw) }.getOrDefault(world.getState().lastTurnTime)
        else -> world.getState().lastTurnTime
    }

    private fun failure(generalId: Int, reason: String) =
        GeneralBoolResult(type = "buildNationCandidate", ok = false, generalId = generalId, reason = reason)
}
