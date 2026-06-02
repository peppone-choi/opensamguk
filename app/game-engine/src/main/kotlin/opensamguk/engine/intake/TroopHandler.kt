package opensamguk.engine.intake

import opensamguk.common.wire.TroopActionResult
import opensamguk.common.wire.TroopExitFail
import opensamguk.common.wire.TroopExitOk
import opensamguk.common.wire.TroopJoinFail
import opensamguk.common.wire.TroopJoinOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.Troop
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.actions.intake.TroopActions

/**
 * 부대 (troop) intake handler — faithful port of the `sammo/API/Troop/` `launch()` bodies (F4 Wave
 * C2 slice B). Per-run (world + recorder), mirroring [NationFinanceSetterHandler] / [TournamentEnrollHandler].
 *
 * The pure guard chains live in [TroopActions]; this handler owns the world-state effects PHP performs
 * via `DB::db()` — the troopExists SELECT, the UPDATE affectedRows guard, and the troop-table lifecycle.
 * General-row mutations (`general.troop`) are recorded through [ChangeRecorder.diffGeneral] (the SINGLE
 * dirty source); troop-table create/rename/delete go through the world's troop lifecycle (createTroop /
 * updateTroop / removeTroop), which the flush maps to the `troop` INSERT/UPDATE/DELETE batches.
 *
 * Troop identity: a troop's id IS its `troop_leader` (== the leader general's id); a general's `troop`
 * is `0` (none) or the troop_leader id of its troop. RNG-free and game-log-free (PHP emits only
 * StaticEventHandler events, not game-log rows).
 */
class TroopHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    // ── NewTroop.php ───────────────────────────────────────────────────────────────────────────
    fun handleNew(c: TurnDaemonCommand.TroopNew): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return TroopActionResult("troopNew", ok = false, generalId = c.generalId, reason = "장수가 존재하지 않습니다.")

        return when (val out = TroopActions.newTroop(PerTurnOverlay.toLogicGeneral(me), c.troopName)) {
            is TroopActions.NewTroopOutcome.Denied ->
                TroopActionResult("troopNew", ok = false, generalId = c.generalId, reason = out.reason)
            is TroopActions.NewTroopOutcome.Create -> {
                // PHP `$db->insert('troop', …); if(affectedRows()==0) return '부대가 생성되지 않았습니다…'`.
                // A pre-existing row at this troop_leader (PK) is the insert-fails case.
                if (world.getTroopById(me.id) != null) {
                    return TroopActionResult("troopNew", ok = false, generalId = c.generalId, reason = "부대가 생성되지 않았습니다. 버그일 수 있습니다.")
                }
                world.createTroop(Troop(id = me.id, nationId = me.nationId, name = out.troopName))
                setGeneralTroop(me, me.id)   // PHP `$me->setVar('troop', $generalID)`
                TroopActionResult("troopNew", ok = true, generalId = c.generalId, troopId = me.id)
            }
        }
    }

    // ── JoinTroop.php ──────────────────────────────────────────────────────────────────────────
    fun handleJoin(c: TurnDaemonCommand.TroopJoin): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return TroopJoinFail(generalId = c.generalId, troopId = c.troopId, reason = "장수 정보를 불러올 수 없습니다.")

        return when (val out = TroopActions.joinTroop(PerTurnOverlay.toLogicGeneral(me))) {
            is TroopActions.JoinTroopOutcome.Denied ->
                TroopJoinFail(generalId = c.generalId, troopId = c.troopId, reason = out.reason)
            is TroopActions.JoinTroopOutcome.Proceed -> {
                // PHP `SELECT troop_leader FROM troop WHERE troop_leader=%i AND nation=%i`.
                val troop = world.getTroopById(c.troopId)
                if (troop == null || troop.nationId != out.nationId) {
                    return TroopJoinFail(generalId = c.generalId, troopId = c.troopId, reason = "부대가 올바르지 않습니다.")
                }
                setGeneralTroop(me, c.troopId)
                TroopJoinOk(generalId = c.generalId, troopId = c.troopId)
            }
        }
    }

    // ── ExitTroop.php ──────────────────────────────────────────────────────────────────────────
    fun handleExit(c: TurnDaemonCommand.TroopExit): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return TroopExitFail(generalId = c.generalId, reason = "장수 정보를 불러올 수 없습니다.")

        return when (val out = TroopActions.exitTroop(PerTurnOverlay.toLogicGeneral(me))) {
            is TroopActions.ExitTroopOutcome.Denied ->
                TroopExitFail(generalId = c.generalId, reason = out.reason)
            TroopActions.ExitTroopOutcome.ExitMember -> {
                setGeneralTroop(me, 0)
                TroopExitOk(generalId = c.generalId, wasLeader = false)
            }
            TroopActions.ExitTroopOutcome.Disband -> {
                // PHP: `UPDATE general SET troop=0 WHERE troop=troopID` (every member, the leader
                // included since leader.troop == troopID) THEN `DELETE troop WHERE troop_leader=troopID`.
                val troopId = me.id
                for (member in world.listGenerals().filter { it.troopId == troopId }) {
                    setGeneralTroop(member, 0)
                }
                world.removeTroop(troopId)
                TroopExitOk(generalId = c.generalId, wasLeader = true)
            }
        }
    }

    // ── KickFromTroop.php ──────────────────────────────────────────────────────────────────────
    // PHP operates on `args['generalID']` (the TARGET) and never the acting session general — there
    // is NO kicker authz. Ported verbatim (faithful-port rule 5); the missing check is a P8 backlog
    // item, not silently hardened here.
    fun handleKick(c: TurnDaemonCommand.TroopKick): TurnDaemonCommandResult {
        val target = world.getGeneralById(c.targetGeneralId)
            ?: return TroopActionResult("troopKick", ok = false, generalId = c.generalId, troopId = c.troopId, reason = "장수가 존재하지 않습니다.")

        return when (val out = TroopActions.kickFromTroop(PerTurnOverlay.toLogicGeneral(target), c.troopId)) {
            is TroopActions.KickOutcome.Denied ->
                TroopActionResult("troopKick", ok = false, generalId = c.generalId, troopId = c.troopId, targetGeneralId = c.targetGeneralId, reason = out.reason)
            TroopActions.KickOutcome.Kick -> {
                setGeneralTroop(target, 0)
                TroopActionResult("troopKick", ok = true, generalId = c.generalId, troopId = c.troopId, targetGeneralId = c.targetGeneralId)
            }
        }
    }

    // ── SetTroopName.php ───────────────────────────────────────────────────────────────────────
    fun handleSetName(c: TurnDaemonCommand.TroopSetName): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return TroopActionResult("troopSetName", ok = false, generalId = c.generalId, troopId = c.troopId, reason = "장수가 존재하지 않습니다.")

        // `checkSecretPermission($me->getRaw(), false)` → SecretPermission.check defaults (checkSecretLimit=false).
        val permission = SecretPermission.check(PerTurnOverlay.toLogicGeneral(me))
        return when (val out = TroopActions.setTroopName(PerTurnOverlay.toLogicGeneral(me), c.troopId, c.troopName, permission)) {
            is TroopActions.SetNameOutcome.Denied ->
                TroopActionResult("troopSetName", ok = false, generalId = c.generalId, troopId = c.troopId, reason = out.reason)
            is TroopActions.SetNameOutcome.Rename -> {
                // PHP `UPDATE troop SET name WHERE troop_leader=%i AND nation=%i; if(affectedRows==0) '부대가 없습니다.'`.
                val troop = world.getTroopById(c.troopId)
                if (troop == null || troop.nationId != out.nationId) {
                    return TroopActionResult("troopSetName", ok = false, generalId = c.generalId, troopId = c.troopId, reason = "부대가 없습니다.")
                }
                world.updateTroop(troop.copy(name = out.troopName))
                TroopActionResult("troopSetName", ok = true, generalId = c.generalId, troopId = c.troopId)
            }
        }
    }

    /** Apply `general.troop = value` to the world read-state + record the dirty patch (single source). */
    private fun setGeneralTroop(g: opensamguk.engine.turn.TurnGeneral, value: Int) {
        if (g.troopId == value) return
        val pre = PerTurnOverlay.toLogicGeneral(g)
        val next = g.copy(troopId = value)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
    }
}
