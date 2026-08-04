package opensamguk.engine.intake

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.MySettingResult
import opensamguk.common.wire.ReadLatestMessageResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.common.wire.VacationResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.EngineGeneralActionPipelineBuilder
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.util.phpRound

class AccountCommandHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handleSetting(command: TurnDaemonCommand.SetMySetting): TurnDaemonCommandResult {
        val current = world.getGeneralById(command.generalId)
            ?: return MySettingResult(ok = false, generalId = command.generalId, reason = "장수가 없습니다")
        val settings = command.settings
        val defenceTrain = normalizeDefenceTrain(settings.defenceTrain ?: 80)
        val tnmt = (settings.tnmt ?: 1).takeIf { it in 0..1 } ?: 1
        val useTreatment = (settings.useTreatment ?: 10).coerceIn(10, 100)
        val useAutoNationTurn = settings.useAutoNationTurn ?: 1
        val nextMeta = LinkedHashMap(current.meta)

        var nextTrain = current.train
        var nextAtmos = current.atmos
        if (metaInt(current, "defence_train", 80) != defenceTrain) {
            nextMeta["myset"] = metaInt(current, "myset", 0) - 1
            nextMeta["defence_train"] = defenceTrain
            if (defenceTrain == 999) {
                val startYear = (worldValue("startyear") as? Number)?.toInt()
                    ?: (worldValue("startYear") as? Number)?.toInt()
                    ?: world.getState().currentYear
                val pipeline = EngineGeneralActionPipelineBuilder(world, startYear).pipelineFor(current)
                val logicGeneral = PerTurnOverlay.toLogicGeneral(current)
                val affectedTrain = pipeline.onCalcDomestic(
                    logicGeneral,
                    "changeDefenceTrain",
                    "train999",
                    -3.0,
                )
                val affectedAtmos = pipeline.onCalcDomestic(
                    logicGeneral,
                    "changeDefenceTrain",
                    "atmos999",
                    -6.0,
                )
                nextTrain = phpRound(current.train + affectedTrain).coerceIn(20, GameConst.maxTrainByWar)
                nextAtmos = phpRound(current.atmos + affectedAtmos).coerceIn(20, GameConst.maxAtmosByWar)
            }
        }
        nextMeta["use_treatment"] = useTreatment
        nextMeta["use_auto_nation_turn"] = useAutoNationTurn
        nextMeta["tnmt"] = tnmt

        apply(current, current.copy(train = nextTrain, atmos = nextAtmos, meta = nextMeta))
        return MySettingResult(ok = true, generalId = command.generalId)
    }

    fun handleVacation(command: TurnDaemonCommand.Vacation): TurnDaemonCommandResult {
        val current = world.getGeneralById(command.generalId)
            ?: return VacationResult(ok = false, generalId = command.generalId, reason = "장수가 없습니다")
        val autorunUser = worldValue("autorun_user") as? Map<*, *>
        if (phpTruthy(autorunUser?.get("limit_minutes"))) {
            return VacationResult(
                ok = false,
                generalId = command.generalId,
                reason = "자동 턴인 경우에는 휴가 명령이 불가능합니다.",
            )
        }

        val targetKillturn = ((worldValue("killturn") as? Number)?.toInt() ?: 0) * 3
        val owner = ownerKey(current)
        for (general in world.listGenerals().filter { ownerKey(it) == owner }) {
            val nextMeta = LinkedHashMap(general.meta)
            nextMeta["killturn"] = targetKillturn
            apply(general, general.copy(meta = nextMeta))
        }
        return VacationResult(ok = true, generalId = command.generalId)
    }

    fun handleReadLatest(command: TurnDaemonCommand.ReadLatestMessage): TurnDaemonCommandResult {
        val current = world.getGeneralById(command.generalId)
            ?: return ReadLatestMessageResult(
                ok = false,
                generalId = command.generalId,
                messageType = command.messageType,
                latestRead = 0,
                reason = "장수가 없습니다",
            )
        val key = when (command.messageType) {
            "private" -> "latestReadPrivateMsg"
            "diplomacy" -> "latestReadDiplomacyMsg"
            else -> return ReadLatestMessageResult(
                ok = false,
                generalId = command.generalId,
                messageType = command.messageType,
                latestRead = 0,
                reason = "메시지 종류가 올바르지 않습니다.",
            )
        }
        val oldValue = metaInt(current, key, 0)
        val latestRead = maxOf(oldValue, command.msgID)
        if (latestRead != oldValue) {
            val nextMeta = LinkedHashMap(current.meta)
            nextMeta[key] = latestRead
            apply(current, current.copy(meta = nextMeta))
        }
        return ReadLatestMessageResult(
            ok = true,
            generalId = command.generalId,
            messageType = command.messageType,
            latestRead = latestRead,
        )
    }

    private fun apply(pre: TurnGeneral, post: TurnGeneral) {
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post))
        world.applyGeneralDirtyFree(post)
    }

    private fun worldValue(key: String): Any? = world.getState().config[key] ?: world.getState().meta[key]

    private fun ownerKey(general: TurnGeneral): String =
        general.userId ?: general.meta["owner"]?.toString() ?: general.id.toString()

    private fun metaInt(general: TurnGeneral, key: String, default: Int): Int =
        (general.meta[key] as? Number)?.toInt() ?: default

    private fun normalizeDefenceTrain(value: Int): Int {
        val clamped = maxOf(value, 40)
        return if (clamped <= 90) phpRound(clamped.toDouble(), -1) else 999
    }

    private fun phpTruthy(value: Any?): Boolean = when (value) {
        null, false, 0, 0L, 0.0, "", "0" -> false
        else -> true
    }
}
