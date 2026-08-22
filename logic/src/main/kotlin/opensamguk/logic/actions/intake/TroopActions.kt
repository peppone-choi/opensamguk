package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General
import opensamguk.logic.util.StringUtil

/**
 * Pure-logic resolvers for the 부대 (troop) intake commands — faithful ports of the PHP
 * `sammo/API/Troop/` `validateArgs()` + `launch()` guard chains. NO Spring/DB/world here: each
 * resolver takes the acting (or target) [General] + args and returns an outcome the engine
 * `TroopHandler` applies (world troop lifecycle + ChangeRecorder general delta). Mirrors the slice-A
 * [NationFinanceSetters] split (logic = pure validators, handler = world effects).
 *
 * Troop identity convention (frozen historical baseline (ADR-LITE-042; not current product authority) `troop` table): a troop's id IS its `troop_leader`, which
 * equals the leader general's id. A general's `troop` field is `0` (no troop) or the `troop_leader`
 * id of its troop; the leader's own `troop` equals its own id.
 *
 * The `validateArgs` `required`/`integer` rules are structurally satisfied by the typed wire command
 * (Int/String, non-null), so only `stringWidthBetween(troopName, 1, 18)` needs a runtime port. The
 * world-state checks PHP performs in `launch()` (troopExists SELECT, UPDATE affectedRows, target
 * general lookup) stay in the handler — they are not pure.
 */
object TroopActions {

    /** `Validator::stringWidthBetween('troopName', 1, 18)` — NewTroop / SetTroopName. */
    const val NAME_WIDTH_MIN = 1
    const val NAME_WIDTH_MAX = 18

    // ── NewTroop.php ───────────────────────────────────────────────────────────────────────────
    sealed interface NewTroopOutcome {
        data class Denied(val reason: String) : NewTroopOutcome
        /** Insert a troop row `(troop_leader = me.id, nation = me.nationId, name)` then `me.troop = me.id`. */
        data class Create(val troopName: String) : NewTroopOutcome
    }

    fun newTroop(me: General, rawTroopName: String?): NewTroopOutcome {
        widthDeny(rawTroopName)?.let { return NewTroopOutcome.Denied(it) }       // validateArgs
        val troopName = StringUtil.neutralize(rawTroopName)                      // launch():39
        if (troopName.isEmpty()) return NewTroopOutcome.Denied("부대 이름이 없습니다.")     // :40-42
        if (me.troop != 0) return NewTroopOutcome.Denied("이미 부대에 소속되어 있습니다.")    // :46-49
        if (me.nationId == 0) return NewTroopOutcome.Denied("국가에 소속되어 있지 않습니다.") // :50-53
        return NewTroopOutcome.Create(troopName)
    }

    // ── JoinTroop.php ──────────────────────────────────────────────────────────────────────────
    sealed interface JoinTroopOutcome {
        data class Denied(val reason: String) : JoinTroopOutcome
        /** Pure guards passed; the handler still verifies `troopExists(troopId, nationId)` in-world. */
        data class Proceed(val nationId: Int) : JoinTroopOutcome
    }

    fun joinTroop(me: General): JoinTroopOutcome {
        if (me.troop != 0) return JoinTroopOutcome.Denied("이미 부대에 소속되어 있습니다.")    // :48-50
        if (me.nationId == 0) return JoinTroopOutcome.Denied("국가에 소속되어 있지 않습니다.") // :52-55
        return JoinTroopOutcome.Proceed(me.nationId)
    }

    // ── ExitTroop.php ──────────────────────────────────────────────────────────────────────────
    sealed interface ExitTroopOutcome {
        data class Denied(val reason: String) : ExitTroopOutcome
        /** Non-leader exit: set `me.troop = 0` only. */
        object ExitMember : ExitTroopOutcome
        /** Leader exit (disband): free every member (`general.troop = 0 WHERE troop = id`) then delete the troop row. */
        object Disband : ExitTroopOutcome
    }

    fun exitTroop(me: General): ExitTroopOutcome {
        val troopId = me.troop
        if (troopId == 0) return ExitTroopOutcome.Denied("부대에 소속되어 있지 않습니다.")     // :36-38
        return if (troopId != me.id) ExitTroopOutcome.ExitMember else ExitTroopOutcome.Disband // :40 vs :49
    }

    // ── KickFromTroop.php ──────────────────────────────────────────────────────────────────────
    // PHP NOTE: `launch()` operates on `args['generalID']` (the TARGET) and NEVER references the
    // acting session general — there is NO kicker permission/ownership check. This is ported VERBATIM
    // (faithful-port rule 5: PHP is frozen historical baseline (ADR-LITE-042; not current product authority)). The missing authz is logged as a P8 parity-fidelity
    // backlog item, NOT silently hardened here (a check would diverge from devsam/core).
    sealed interface KickOutcome {
        data class Denied(val reason: String) : KickOutcome
        /** Set the TARGET general's `troop = 0`. */
        object Kick : KickOutcome
    }

    fun kickFromTroop(target: General, troopIdArg: Int): KickOutcome {
        val troopId = target.troop
        if (troopId == 0) return KickOutcome.Denied("부대에 소속되어 있지 않습니다.")        // :45-47
        if (troopId != troopIdArg) return KickOutcome.Denied("다른 부대에 소속되어 있습니다.") // :49-51
        if (troopId == target.id) return KickOutcome.Denied("부대장을 추방할 수 없습니다.")    // :53-55
        return KickOutcome.Kick
    }

    // ── SetTroopName.php ───────────────────────────────────────────────────────────────────────
    sealed interface SetNameOutcome {
        data class Denied(val reason: String) : SetNameOutcome
        /** UPDATE `troop SET name WHERE troop_leader = troopId AND nation = me.nationId`. */
        data class Rename(val troopName: String, val nationId: Int) : SetNameOutcome
    }

    /** `permission` = `checkSecretPermission(me.raw, checkSecretLimit = false)` resolved by the handler. */
    fun setTroopName(me: General, troopIdArg: Int, rawTroopName: String?, permission: Int): SetNameOutcome {
        widthDeny(rawTroopName)?.let { return SetNameOutcome.Denied(it) }            // validateArgs
        if (me.id != troopIdArg && permission < 4) return SetNameOutcome.Denied("권한이 부족합니다.") // :48-50
        val troopName = StringUtil.neutralize(rawTroopName)                          // :52
        if (troopName.isEmpty()) return SetNameOutcome.Denied("부대 이름이 없습니다.")          // :53-55
        return SetNameOutcome.Rename(troopName, me.nationId)
    }

    /**
     * `stringWidthBetween(troopName, 1, 18)` on the RAW arg (runs in `validateArgs`, before `launch`).
     * QUARANTINE: the exact Valitron `errorStr()` text is display-only and not byte-captured here
     * (same treatment slice A gave the numeric-range validator messages) — flagged for P8.
     */
    private fun widthDeny(raw: String?): String? {
        val width = StringUtil.mbStrwidth(raw ?: "")
        return if (width < NAME_WIDTH_MIN || width > NAME_WIDTH_MAX) {
            "부대 이름은 너비 $NAME_WIDTH_MIN~$NAME_WIDTH_MAX 사이여야 합니다."
        } else {
            null
        }
    }
}
