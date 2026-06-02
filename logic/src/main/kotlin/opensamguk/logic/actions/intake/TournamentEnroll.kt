package opensamguk.logic.actions.intake

/**
 * 토너먼트 참가 (enroll) — faithful port of the `tnmt` toggle in `j_set_my_setting.php:17,34-36,60`:
 *
 * ```php
 * $tnmt = Util::getPost('tnmt', 'int', 1);
 * if ($tnmt < 0 || $tnmt > 1) { $tnmt = 1; }
 * $me->setVar('tnmt', $tnmt);
 * ```
 *
 * The general column `tnmt` (0 = 불참, 1 = 참가) gates whether the general is drawn into the
 * tournament bracket (`GetFrontInfo.php` exposes `tournament`/`tnmt_type` state; the engine
 * tournament worker reads `tnmt`). This slice ports ONLY the participation toggle (DEFER:
 * tournament-admin open/close, bracket seeding — later C2/C3 waves).
 *
 * Pure logic: returns the clamped tnmt value the engine handler writes onto the general row. The
 * other `j_set_my_setting` knobs (defence_train rounding, use_treatment/use_auto_nation_turn aux,
 * detachNPC killturn, member-penalty sync) are OUT of this slice's scope (separate my-settings wave).
 */
object TournamentEnroll {

    /** PHP `if ($tnmt < 0 || $tnmt > 1) $tnmt = 1;` — out-of-range collapses to 1 (참가). */
    fun clampTnmt(tnmt: Int): Int = if (tnmt < 0 || tnmt > 1) 1 else tnmt
}
