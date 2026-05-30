package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinAction
import opensamguk.logic.constraints.beNeutral
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A candidate nation for the NPC-foreign branch (`che_랜덤임관.php:138-148` query rows): the dest nation
 * name/id/gennum/affinity plus the dest LORD's city id (PHP joins `officer_level = 12`). The adapter
 * loads these (scout=0 AND gennum<genLimit); the resolver does NOT touch the DB.
 */
data class RandomImgwanNpcCandidate(
    val nationId: Int,
    val name: String,
    val gennum: Int,
    val affinity: Int,
    val lordCityId: Int,
)

/**
 * A candidate nation for the ELSE (weighted-pair) branch (`che_랜덤임관.php:183-205` grouped query): the
 * SQL-aggregated warpower/develpower per nation + name/gennum/npc-flag. `npcLeq1` is the per-nation
 * `g.npc < 2` flag the PHP `str_starts_with('ⓤ')` *100 multiplier consults via the actor (`getNPCType()<2`).
 */
data class RandomImgwanWeightedCandidate(
    val nationId: Int,
    val name: String,
    val gennum: Int,
    val warpower: Double,
    val develpower: Double,
    val npcLeq1: Boolean,
    val lordCityId: Int,
)

/**
 * che_랜덤임관 — faithful port of `che_랜덤임관.php:113-289` (the highest parity-risk personnel command).
 *
 * The dest-nation pick has two branches (`useNpcForeignBranch` = PHP
 * `getNPCType()>=2 && !fiction && 1000<=scenario<2000`, computed at the adapter boundary since
 * scenario/fiction are not on the typed WorldEnv):
 *
 *  - NPC-foreign branch: `shuffle($nations)` FIRST (one RNG draw), then per-nation score loop keeping the
 *    MIN score (`maxScore` init `1<<30`). Each iteration BUG-FAITHFULLY ACCUMULATES `$affinity`
 *    (it is reassigned from the previous iteration's value — `che_랜덤임관.php:159-160`):
 *      affinity = abs(affinity - testNation.affinity); affinity = min(affinity, abs(affinity - 150));
 *      score = log2(affinity+1) + rng.nextFloat1() + sqrt(testNation.gennum / allGen).
 *    The nextFloat1 is drawn ONCE per nation, in shuffled order.
 *  - ELSE branch: weighted pair `(1/(warpower+develpower))^3` (×100 when actor npc<2 AND nation name ⓤ),
 *    one `choiceUsingWeightPair` draw.
 *
 * AFTER the dest is chosen: `randomTalk = rng.choice(talkList)`. Draw order is therefore
 * [shuffle | weightedPair] → choice(talk) → (trailing unique lottery on the SEPARATE "unique" rng with
 * reason '랜덤 임관'). When no candidate exists, the PHP logs "임관 가능한 국가가 없습니다." and returns
 * false (alternative che_인재탐색) WITHOUT a talk draw or transition.
 *
 * The candidate sets are loaded by the adapter (PHP `initWithArg`/run query); they default to empty so the
 * registry can register the command for constraint-only / no-candidate paths.
 */
class CheRandomImgwan(
    private val pipeline: GeneralActionPipeline,
    private val npcCandidates: List<RandomImgwanNpcCandidate> = emptyList(),
    private val weightedCandidates: List<RandomImgwanWeightedCandidate> = emptyList(),
    private val useNpcForeignBranch: Boolean = false,
) : GeneralActionDefinition {

    override val key: String get() = "che_랜덤임관"
    override val name: String get() = "무작위 국가로 임관"
    override val category: String get() = "인사"

    // Divergent seed tokens (GeneralActionDefinition doc): the 6th serializeSeed token is the raw class
    // name `che_랜덤임관`; the lottery uses the SPACED actionName `무작위 국가로 임관` (already == name here).
    override val rawClassName: String get() = "che_랜덤임관"
    override val lotteryActionName: String get() = "무작위 국가로 임관"

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beNeutral(),
        allowJoinAction(),
    )

    /** A chosen destination — nation id, name, gennum, lord's city — common to both branches. */
    private data class Chosen(val nationId: Int, val name: String, val gennum: Int, val lordCityId: Int)

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val rng = context.rng

        val chosen: Chosen? = if (useNpcForeignBranch) {
            pickNpcForeign(context)
        } else {
            pickWeighted(context)
        }

        if (chosen == null) {
            // 임관 가능한 국가가 없습니다 — no talk draw, no transition (PHP returns false + che_인재탐색 alternative).
            context.addLog("임관 가능한 국가가 없습니다. <1>${context.date}</>")
            return
        }

        // talk choice — AFTER the dest pick (draw order: [shuffle|weighted] → choice(talk)).
        val randomTalk = rng.choice(TALK_LIST)

        val generalName = (d.general.meta["name"] as? String) ?: ""
        val josaYi = JosaUtil.pick(generalName, "이")
        context.addLog("<D>${chosen.name}</>에 랜덤 임관했습니다. <1>${context.date}</>")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi $randomTalk <D><b>${chosen.name}</b></>에 <S>임관</>했습니다.")

        val exp = if (chosen.gennum < GameConst.initialNationGenLimit) 700.0 else 100.0

        // JOIN transition (PHP setVar block): NOTE 랜덤임관 does NOT reset troop (no setVar('troop', ...)).
        val joinedCity = d.destGeneral?.cityId ?: chosen.lordCityId
        var g = d.general.copy(
            nationId = chosen.nationId,
            officerLevel = 1,
            cityId = joinedCity,
            meta = withMeta(d.general.meta, "officer_city" to 0, "belong" to 1),
        )

        // dest nation gennum + 1 (modeled via the destNation carrier when the adapter preloaded it).
        d.destNation?.let { dn ->
            if (dn.id == chosen.nationId) {
                d.destNation = dn.copy(gennum = dn.gennum + 1, meta = withMeta(dn.meta, "gennum" to dn.gennum + 1))
            }
        }

        // increaseInheritancePoint(active_action, 1) — succession seam; no write here.
        g = g.copy(experience = g.experience + exp)
        d.general = g

        // trailing unique lottery rides the SEPARATE "unique" rng (reason '랜덤 임관'); a downstream seam.
    }

    /** che_랜덤임관.php:150-173 — shuffle FIRST, per-nation nextFloat1, keep MIN, accumulate affinity. */
    private fun pickNpcForeign(context: GeneralActionResolveContext): Chosen? {
        if (npcCandidates.isEmpty()) return null
        val rng = context.rng
        val shuffled = rng.shuffle(npcCandidates)
        val allGen = shuffled.sumOf { it.gennum }.toDouble()
        var maxScore = (1 shl 30).toDouble()
        var affinity = metaInt(context.draft.general.meta, "affinity")
        var pick: RandomImgwanNpcCandidate? = null
        for (n in shuffled) {
            affinity = abs(affinity - n.affinity)
            affinity = min(affinity, abs(affinity - 150))
            var score = ln((affinity + 1).toDouble()) / ln(2.0)   // PHP log($affinity+1, 2)
            score += rng.nextFloat1()
            score += sqrt(n.gennum / allGen)
            if (score < maxScore) {
                maxScore = score
                pick = n
            }
        }
        return pick?.let { Chosen(it.nationId, it.name, it.gennum, it.lordCityId) }
    }

    /** che_랜덤임관.php:208-220 — weighted pair (1/(warpower+develpower))^3, ×100 when actor npc<2 AND name ⓤ. */
    private fun pickWeighted(context: GeneralActionResolveContext): Chosen? {
        if (weightedCandidates.isEmpty()) return null
        val rng = context.rng
        val actorNpc = context.draft.general.npcType
        val pairs = weightedCandidates.map { n ->
            var calcCnt = n.warpower + n.develpower
            if (actorNpc < 2 && n.name.startsWith("ⓤ")) {
                calcCnt *= 100
            }
            val inv = 1.0 / calcCnt
            n to (inv * inv * inv)            // (1/calcCnt)^3
        }
        val picked = rng.choiceUsingWeightPair(pairs)
        return Chosen(picked.nationId, picked.name, picked.gennum, picked.lordCityId)
    }

    companion object {
        /** che_랜덤임관.php:238-250 — the 11 talk fragments choice() picks AFTER the dest is chosen. */
        val TALK_LIST: List<String> = listOf(
            "어쩌다 보니",
            "인연이 닿아",
            "발길이 닿는 대로",
            "소문을 듣고",
            "점괘에 따라",
            "천거를 받아",
            "유명한",
            "뜻을 펼칠 곳을 찾아",
            "고향에 가까운",
            "천하의 균형을 맞추기 위해",
            "오랜 은거를 마치고",
        )
    }
}
