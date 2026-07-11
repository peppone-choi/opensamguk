package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld

class AdminWorldSettingsHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handle(command: TurnDaemonCommand.AdminWorldSettings): GeneralBoolResult {
        if (command.status != null && command.status !in SERVER_STATUSES) {
            return fail(command, "지원하지 않는 서버 상태입니다.")
        }

        val configPatch = linkedMapOf<String, Any?>()
        var message: String? = null
        for (setting in command.settings) {
            if (setting.key !in SETTING_KEYS || (setting.intValue == null) == (setting.stringValue == null)) {
                return fail(command, "잘못된 게임 설정입니다: ${setting.key}")
            }
            val value: Any = setting.intValue ?: setting.stringValue!!
            if (setting.key == "msg") message = value.toString() else configPatch[setting.key] = value
        }

        val turnSeconds = (configPatch["turnterm"] as? Int)?.times(60)
        world.applyAdminWorldSettings(command.status, configPatch, turnSeconds)
        message?.let {
            world.setGameEnvValue("msg", it)
            recorder.recordKv("game_env", "", "msg", it)
        }
        return GeneralBoolResult(command.type, true, 0)
    }

    private fun fail(command: TurnDaemonCommand.AdminWorldSettings, reason: String) =
        GeneralBoolResult(command.type, false, 0, reason)

    companion object {
        private val SERVER_STATUSES = setOf("CLOSED", "PRE_OPEN", "OPEN")
        private val SETTING_KEYS = setOf(
            "npcmode", "block_general_create", "maxgeneral", "maxnation",
            "startyear", "starttime", "turnterm", "msg",
        )
    }
}
