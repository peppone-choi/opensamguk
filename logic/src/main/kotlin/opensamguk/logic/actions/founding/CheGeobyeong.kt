package opensamguk.logic.actions.founding

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinAction
import opensamguk.logic.constraints.beNeutral
import opensamguk.logic.constraints.beOpeningPart
import opensamguk.logic.constraints.noPenalty
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_거병 — faithful port of `che_거병.php:25-186`. The CANONICAL created-set command: it INSERTs
 * (rather than mutates) a brand-new neutral nation, a diplomacy PAIR per existing nation, and the
 * nation's 24 reserved-turn rows, then JOINs the actor as its lord.
 *
 * Run sequence (PHP run() lines 65-185), preserved exactly:
 *   1. nationName = actor name, with the `㉥` (U+3265) dedup: if `name` collides, prepend one `㉥` and
 *      `mb_strimwidth(.,0,18)` truncate; if STILL colliding, prepend a second `㉥` (no truncate).
 *   2. secretlimit = (scenario >= 1000) ? 1 : 3.
 *   3. INSERT nation (color #330000, gold 0, rice baserice=2000, rate 20, bill 100,
 *      strategic_cmd_limit 12, surlimit 72, secretlimit, type che_중립, gennum 1) → [GeneralActionDraft.createdNations].
 *   4. For EACH other nation (getAllNationStaticInfo insertion order): two diplomacy rows
 *      `{me:dest, you:new}` then `{me:new, you:dest}`, state 2, term 0 → [GeneralActionDraft.createdDiplomacy].
 *   5. 24 nation_turn rows: outer officer_level [12, 11], inner turn_idx 0..maxChiefTurn-1, action 휴식,
 *      arg null, brief 휴식 → [GeneralActionDraft.createdNationTurns].
 *   6. general action log "거병에 성공하였습니다." + global action log; the THREE history logs
 *      (global/general/national) are GATE-RUNTIME history-bucket seams (no history bucket in the context).
 *   7. exp += 100, ded += 100 (folded through the pipeline); increaseInheritancePoint(active_action, 1)
 *      is the P6 succession seam (no write); the JOIN transition belong 1 / officer_level 12 /
 *      officer_city 0 / nation new.
 *   8. setResultTurn(LastTurn('거병', $this->arg=[])) → general.lastTurn; checkStatChange() finalize.
 *   9. tryUniqueItemLottery(reason '거병') AFTER all writes — rides the SEPARATE unique rng (a downstream
 *      seam wired by the engine handler); it never draws from the action stream, and the default human
 *      general wins nothing in P2.
 *
 * Adapter-preloaded inputs (PHP DB queries that the resolver does not run): `newNationId` (insertId
 * placeholder, reconciled at flush), `existingNationIds` (getAllNationStaticInfo), `existingNationNames`
 * (the dup-name count scan), `scenario`.
 */
class CheGeobyeong(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_거병"
    override val name: String get() = "거병"
    override val category: String get() = "인사"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beNeutral(),
        // BeOpeningPart($relYear+1) — che_거병.php:47 passes relYear+1 as the opening-part arg.
        beOpeningPart { c, _ -> ((c.args["relYear"] as? Number)?.toInt() ?: 0) + 1 },
        allowJoinAction(),
        // NoPenalty(PenaltyKey::NoFoundNation) — the penalty map is keyed by the enum VALUE 'noFoundNation'.
        noPenalty(PENALTY_NO_FOUND_NATION),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val newNationId = (context.args["newNationId"] as? Number)?.toInt()
            ?: error("거병 requires a preloaded newNationId (insertId placeholder)")
        @Suppress("UNCHECKED_CAST")
        val existingNationIds = (context.args["existingNationIds"] as? List<Int>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val existingNationNames = (context.args["existingNationNames"] as? Set<String>) ?: emptySet()
        val scenario = (context.args["scenario"] as? Number)?.toInt() ?: 0

        val generalName = context.generalName.ifEmpty { (d.general.meta["name"] as? String) ?: "" }
        val cityName = CityConst.byId(d.city.id)?.name ?: ""

        // 1. nationName + ㉥ dedup (che_거병.php:79-91).
        var nationName = generalName
        if (nationName in existingNationNames) {
            nationName = mbStrimwidth("$PREFIX$nationName", 18)
        }
        if (nationName in existingNationNames) {
            // still colliding: prepend another ㉥, ignore the width limit.
            nationName = "$PREFIX$nationName"
        }

        // 2. secretlimit (che_거병.php:93-96).
        val secretlimit = if (scenario >= 1000) 1 else 3

        // 3. INSERT nation (che_거병.php:98-110). level defaults to 0 (wandering — not set in the INSERT);
        //    meta carries rate/bill/strategic_cmd_limit/surlimit/secretlimit/gennum in PHP INSERT order.
        val newNation = Nation(
            id = newNationId,
            level = 0,
            capitalCityId = 0,
            name = nationName,
            color = WANDERING_COLOR,
            typeCode = GameConst.neutralNationType,
            gold = 0,
            rice = GameConst.baserice,
            tech = 0.0,
            gennum = 1,
            capset = 0,
            meta = linkedMapOf(
                "rate" to 20,
                "bill" to 100,
                "strategic_cmd_limit" to 12,
                "surlimit" to 72,
                "secretlimit" to secretlimit,
                "gennum" to 1,
            ),
        )
        d.createdNations.add(newNation)

        // 4. diplomacy pair per OTHER nation, PHP query insertion order (che_거병.php:114-138).
        for (destNationId in existingNationIds) {
            if (destNationId == newNationId) continue
            d.createdDiplomacy.add(Diplomacy(me = destNationId, you = newNationId, state = 2, term = 0))
            d.createdDiplomacy.add(Diplomacy(me = newNationId, you = destNationId, state = 2, term = 0))
        }

        // 5. 24 nation_turn rows: outer [12, 11], inner 0..maxChiefTurn-1 (che_거병.php:142-156).
        for (chiefLevel in CHIEF_LEVELS) {
            for (turnIdx in 0 until GameConst.maxChiefTurn) {
                d.createdNationTurns.add(
                    NationTurn(
                        nationId = newNationId,
                        officerLevel = chiefLevel,
                        turnIdx = turnIdx,
                        action = "휴식",
                        arg = null,
                        brief = "휴식",
                    ),
                )
            }
        }

        // 6. logs (che_거병.php:160-165).
        val josaYi = JosaUtil.pick(generalName, "이")
        context.addLog("거병에 성공하였습니다. <1>${context.date}</>")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi <G><b>$cityName</b></>에 거병하였습니다.")
        context.addGlobalHistoryLog("<Y><b>【거병】</b></><D><b>$generalName</b></>$josaYi 세력을 결성하였습니다.")
        context.addGeneralHistoryLog("<G><b>$cityName</b></>에서 거병")
        context.addNationalHistoryLog("<Y>$generalName</>$josaYi <G><b>$cityName</b></>에서 거병")

        // 7. exp/ded +100 + JOIN transition (che_거병.php:167-176). active_action+1 is a P6 seam (no write).
        val expRes = addExperience(d.general, EXP_DED_GRANT, pipeline)
        val dedRes = addDedication(expRes.general, EXP_DED_GRANT, pipeline)
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }
        var g = dedRes.general.copy(
            nationId = newNationId,
            officerLevel = 12,
            meta = withMeta(dedRes.general.meta, "belong" to 1, "officer_city" to 0),
        )

        // 8. setResultTurn(LastTurn('거병', $this->arg)) + checkStatChange (che_거병.php:178-179).
        //    PHP `$this->arg = []` → a NON-null empty arg (LastTurn::toRaw emits an empty arg).
        g = g.copy(lastTurn = LastTurn(command = name, arg = emptyMap()))
        val statRes = checkStatChange(g)
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = statRes.general

        // 9. tryUniqueItemLottery(genGenericUniqueRNGFromGeneral(general, '거병'), general) — the SEPARATE
        //    unique-rng seam (NPCType>=2 short-circuit), fired AFTER all writes by the engine handler. It
        //    NEVER draws from the action stream; the default P2 human general acquires nothing.
    }

    companion object {
        /** func_legacy default wandering flag color (che_거병.php:100). */
        const val WANDERING_COLOR: String = "#330000"

        /** PenaltyKey::NoFoundNation enum VALUE (Enums/PenaltyKey.php:20) — the penalty-map key. */
        const val PENALTY_NO_FOUND_NATION: String = "noFoundNation"

        /** The `㉥` (U+3265, CIRCLED HANGUL RIEUL A) duplicate-name prefix (che_거병.php:85/90). */
        const val PREFIX: String = "㉥"

        /** exp/ded grant on success (che_거병.php:167-168). */
        private const val EXP_DED_GRANT: Double = 100.0

        /** The nation_turn outer officer-level loop (che_거병.php:143). */
        private val CHIEF_LEVELS: List<Int> = listOf(12, 11)
    }
}

/**
 * Faithful port of PHP `mb_strimwidth($str, 0, $width)` (trim-marker empty): return the longest prefix
 * of [str] whose cumulative DISPLAY width does not exceed [width]. East-Asian Wide/Fullwidth code points
 * (incl. Hangul syllables and the `㉥` Enclosed-CJK prefix) count as width 2; everything else width 1.
 */
internal fun mbStrimwidth(str: String, width: Int): String {
    val sb = StringBuilder()
    var used = 0
    var i = 0
    while (i < str.length) {
        val cp = str.codePointAt(i)
        val charLen = Character.charCount(cp)
        val w = if (isEastAsianWide(cp)) 2 else 1
        if (used + w > width) break
        sb.appendCodePoint(cp)
        used += w
        i += charLen
    }
    return sb.toString()
}

/**
 * East-Asian Wide/Fullwidth predicate covering the code-point ranges PHP `mb_strwidth` treats as width 2
 * (UAX #11 W + F). Sufficient for game names (Hangul / CJK / Enclosed-CJK / Fullwidth forms).
 */
private fun isEastAsianWide(cp: Int): Boolean = when (cp) {
    in 0x1100..0x115F,      // Hangul Jamo
    in 0x2E80..0x303E,      // CJK Radicals … Kangxi … CJK Symbols (excl. ideographic space tail)
    in 0x3041..0x33FF,      // Hiragana/Katakana/CJK punct/Enclosed-CJK (incl. ㉥ U+3265)/CJK compat
    in 0x3400..0x4DBF,      // CJK Ext A
    in 0x4E00..0x9FFF,      // CJK Unified
    in 0xA000..0xA4CF,      // Yi
    in 0xAC00..0xD7A3,      // Hangul syllables
    in 0xF900..0xFAFF,      // CJK Compatibility Ideographs
    in 0xFE30..0xFE4F,      // CJK Compatibility Forms
    in 0xFF00..0xFF60,      // Fullwidth Forms
    in 0xFFE0..0xFFE6,      // Fullwidth signs
    in 0x20000..0x3FFFD,    // CJK Ext B+ (supplementary)
    -> true
    else -> false
}
