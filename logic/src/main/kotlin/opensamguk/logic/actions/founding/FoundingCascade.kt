package opensamguk.logic.actions.founding

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.withMeta

/**
 * Shared nation-cascade transitions — the bulk multi-entity write-set 방랑 (che_방랑.php) performs and
 * the founding/거병 commands reuse. Created here (CMD-PERSONNEL PR2) and imported by CMD-FOUNDING.
 *
 * The 방랑 cascade ORDER is load-bearing (`che_방랑.php` run() lines 92-123). Replicated EXACTLY:
 *   1. DeleteConflict(nationID)            — global-history conflict purge (modeled by the per-city
 *                                            conflict={} reset in step 5; the standalone records are a
 *                                            GATE-RUNTIME global-history seam).
 *   2. nation UPDATE  name/color/level/type/tech/capital  (the wandering revert).
 *   3. general UPDATE makelimit=12 WHERE nation=nationID   (every member, including the lord).
 *   4. self      makelimit=12, officer_city=0             (the lord's own draft general).
 *   5. general UPDATE officer_level=1, officer_city=0 WHERE nation=nationID AND officer_level<12.
 *   6. city    UPDATE nation=0, front=0, conflict='{}' WHERE nation=nationID.
 *   7. diplomacy UPDATE state=2, term=0 WHERE me=nationID OR you=nationID.
 *
 * `conflict` is a V1 city jsonb column (delete-on-default) and rides `city.meta['conflict']` in the
 * logic model; resetting it to `{}` means removing the key. `belong`/`officer_city`/`makelimit` ride
 * `general.meta`. PHP `'#330000'` is the default wandering color.
 */
object FoundingCascade {

    const val WANDERING_COLOR = "#330000"
    const val WANDERING_TYPE = "None"

    /** Step 2 — revert a nation to the wandering state (level 0, lord's name, reset color/type/tech/capital). */
    fun revertNationToWandering(nation: Nation, lordName: String): Nation = nation.copy(
        name = lordName,
        color = WANDERING_COLOR,
        level = 0,
        typeCode = WANDERING_TYPE,
        tech = 0.0,
        capitalCityId = 0,
    )

    /**
     * Steps 3 + 5 applied to ONE nation member general: makelimit=12 always; officer_level=1 +
     * officer_city=0 only when officer_level < 12 (the lord keeps officer_level 12 but still loses
     * officer_city via the self step — see [demoteMember] vs [resetLordMembership]).
     */
    fun demoteMember(general: General): General {
        var g = general.copy(meta = withMeta(general.meta, "makelimit" to GameConst.maxChiefTurn))
        if (g.officerLevel < 12) {
            g = g.copy(officerLevel = 1, meta = withMeta(g.meta, "officer_city" to 0))
        }
        return g
    }

    /** Step 4 — the lord's own makelimit=12 + officer_city=0 (officer_level 12 stays; never demoted). */
    fun resetLordMembership(general: General): General = general.copy(
        meta = withMeta(general.meta, "makelimit" to GameConst.maxChiefTurn, "officer_city" to 0),
    )

    /** Step 6 — a city reverts to neutral: nation=0, front_state=0, conflict removed (={}). */
    fun neutralizeCity(city: City): City {
        val nextMeta = LinkedHashMap(city.meta)
        nextMeta.remove("conflict")
        return city.copy(nationId = 0, frontState = 0, meta = nextMeta)
    }

    /** Step 7 — a diplomacy row resets to 통상(state=2)/term=0. */
    fun resetDiplomacy(diplomacy: Diplomacy): Diplomacy = diplomacy.copy(state = 2, term = 0)

    /**
     * Apply the full 방랑 cascade over the draft's cascade-set IN THE PHP ORDER. The caller has populated
     * [GeneralActionDraft.cascadeGenerals]/[GeneralActionDraft.cascadeCities]/[GeneralActionDraft.cascadeDiplomacy]
     * with the nation's members/cities/diplomacy rows, and [GeneralActionDraft.nation] is the abandoned
     * nation; [GeneralActionDraft.general] is the lord. The draft general is NOT included in cascadeGenerals
     * (the lord's writes are the explicit self step).
     */
    fun applyWanderingCascade(draft: GeneralActionDraft, lordName: String) {
        val nation = draft.nation ?: error("applyWanderingCascade requires draft.nation")
        // 2. nation revert
        draft.nation = revertNationToWandering(nation, lordName)
        // 3 + 4. the lord's own membership (makelimit=12, officer_city=0; officer_level 12 untouched)
        draft.general = resetLordMembership(draft.general)
        // 3 + 5. each member: makelimit=12, and officer_level/officer_city reset when < 12
        for (i in draft.cascadeGenerals.indices) {
            draft.cascadeGenerals[i] = demoteMember(draft.cascadeGenerals[i])
        }
        // 6. cities → neutral
        for (i in draft.cascadeCities.indices) {
            draft.cascadeCities[i] = neutralizeCity(draft.cascadeCities[i])
        }
        // 7. diplomacy → 통상/0
        for (i in draft.cascadeDiplomacy.indices) {
            draft.cascadeDiplomacy[i] = resetDiplomacy(draft.cascadeDiplomacy[i])
        }
    }
}
