package opensamguk.engine.intake

import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay

/**
 * 토너먼트 참가 (enroll) 핸들러 — the `tnmt` toggle in `j_set_my_setting.php`.
 *
 * Mirrors [opensamguk.engine.betting.PlaceBetHandler]: resolves the acting general, clamps `tnmt`
 * via the pure-logic [opensamguk.logic.actions.intake.TournamentEnroll.clampTnmt], writes
 * `general.meta[tnmt]`, and records the dirty patch through [ChangeRecorder.diffGeneral] (the SINGLE
 * dirty source) so the flush persists it.
 *
 * `tnmt` rides `general.meta` (GeneralBase.php:162 var allow-list; V1 general table has no `tnmt`
 * column — the per-general toggles ride the meta jsonb).
 */
class TournamentEnrollHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handle(c: TurnDaemonCommand.TournamentEnroll): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return NationSettingResult(type = "tournamentEnroll", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")

        val tnmt = opensamguk.logic.actions.intake.TournamentEnroll.clampTnmt(c.value)

        val pre = PerTurnOverlay.toLogicGeneral(me)
        val nextMeta = LinkedHashMap(me.meta)
        nextMeta["tnmt"] = tnmt
        val next = me.copy(meta = nextMeta)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))

        return NationSettingResult(type = "tournamentEnroll", ok = true, generalId = c.generalId, nationId = me.nationId)
    }
}
