package opensamguk.engine.tournament

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.tournament.TournamentBettingPort
import java.time.Instant

class TournamentAdminHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val now: () -> Instant = Instant::now,
    private val lastBettingIdReader: () -> Int = { 0 },
    private val bettingPort: TournamentBettingPort? = null,
) {
    private val service = TournamentAdminService()
    private val permissionDeniedReason = "권한이 부족합니다. 수뇌부가 아닙니다."

    fun handleStart(command: TurnDaemonCommand.TournamentStart): TurnDaemonCommandResult {
        val general = world.getGeneralById(command.generalId)
        if (general == null) {
            return GeneralBoolResult("tournamentStart", false, command.generalId, "장수가 존재하지 않습니다.")
        }
        if (tournamentAdminPermission(general.officerLevel) < 2) {
            return GeneralBoolResult("tournamentStart", false, command.generalId, permissionDeniedReason)
        }
        service.startTournament(world, recorder, command.tournamentType, now())
        return GeneralBoolResult("tournamentStart", true, command.generalId)
    }

    fun handleReset(command: TurnDaemonCommand.TournamentReset): TurnDaemonCommandResult {
        val general = world.getGeneralById(command.generalId)
        if (general == null) {
            return GeneralBoolResult("tournamentReset", false, command.generalId, "장수가 존재하지 않습니다.")
        }
        if (tournamentAdminPermission(general.officerLevel) < 2) {
            return GeneralBoolResult("tournamentReset", false, command.generalId, permissionDeniedReason)
        }
        val bettingId = lastBettingIdReader()
        if (bettingId > 0) bettingPort?.refund(bettingId)
        service.resetTournament(recorder)
        return GeneralBoolResult("tournamentReset", true, command.generalId)
    }

    private fun tournamentAdminPermission(officerLevel: Int): Int = when {
        officerLevel >= 5 -> 2
        officerLevel >= 2 -> 1
        else -> 0
    }
}
