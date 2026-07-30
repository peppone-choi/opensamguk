package opensamguk.logic.actions.develop

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.stats.GeneralActionPipeline

data class SightseeingCandidate(
    val type: Int,
    val weight: Double,
    val texts: List<String>,
)

data class SightseeingExternalOutcome(
    val type: Int,
    val text: String,
    val woundedDraw: Int?,
    val heavyWoundedDraw: Int?,
)

/**
 * Replays PHP's ambient `SightseeingMessage` selections without advancing the action RNG.
 *
 * PHP makes these picks with ambient MT rather than the command [opensamguk.common.rng.RandUtil], so the
 * production fallback deliberately retains the existing action-RNG consumer while an oracle replay can inject
 * the externally captured selection and follow-up wound values.
 */
fun interface SightseeingExternalSelector {
    fun select(
        actorGeneralId: Int,
        year: Int,
        month: Int,
        date: String,
        candidates: List<SightseeingCandidate>,
    ): SightseeingExternalOutcome
}

/**
 * che_견문 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_견문.php`.
 *
 * Sightseeing, AI-emitted by 사망대비/중립 (GeneralAI.php:3414/3442/3462).
 *
 * argTest (che_견문.php:23-26): `arg = null` → always true (no args).
 *
 * fullConditionConstraints (che_견문.php:33-35): **EMPTY** → the AI's full-condition gate passes
 * unconditionally (gate ≈ argValid).
 *
 * run() (che_견문.php:55-122): a `SightseeingMessage::pickAction()` content draw + the per-outcome
 * exp/gold/rice/injury increments and the action log. The outcome content (the SightseeingMessage
 * table + the wound `nextRangeInt` draws) is its own downstream content subsystem; the def ports the
 * gate-critical EMPTY pack + the no-arg argTest, and the resolve is a downstream seam (it does not
 * affect the AI SELECTION draw-for-draw gate).
 */
fun cheGyeonmun(
    pipeline: GeneralActionPipeline,
    externalSelector: SightseeingExternalSelector? = null,
): CheGyeonmun = CheGyeonmun(pipeline, externalSelector)

class CheGyeonmun(
    private val pipeline: GeneralActionPipeline,
    private val externalSelector: SightseeingExternalSelector? = null,
) : GeneralActionDefinition {
    override val key: String get() = "che_견문"
    override val name: String get() = "견문"
    override val category: String get() = "군사"

    /** EMPTY fullConditionConstraints (che_견문.php:33-35). */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()

    /** che_견문.php:23-26 argTest — no args. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val rng = context.rng

        val replayed = externalSelector?.select(
            actorGeneralId = d.general.id,
            year = context.env.year,
            month = context.month,
            date = context.date,
            candidates = OUTCOMES.map { SightseeingCandidate(it.type, it.weight, it.texts) },
        )
        val outcome: Outcome
        var text: String
        if (replayed == null) {
            outcome = rng.choiceUsingWeightPair(OUTCOMES.map { it to it.weight })
            text = rng.choice(outcome.texts)
        } else {
            outcome = OUTCOMES.singleOrNull { it.type == replayed.type }
                ?: error("captured sightseeing type ${replayed.type} is absent from the live candidate table")
            require(replayed.text in outcome.texts) {
                "captured sightseeing text does not belong to type ${replayed.type}"
            }
            text = replayed.text
        }
        var g = d.general
        var exp = 0.0

        if (outcome.flags and INC_EXP != 0) exp += 30.0
        if (outcome.flags and INC_HEAVY_EXP != 0) exp += 60.0
        if (outcome.flags and INC_LEADERSHIP != 0) {
            g = g.copy(meta = withMeta(g.meta, "leadership_exp" to metaInt(g.meta, "leadership_exp") + 2))
        }
        if (outcome.flags and INC_STRENGTH != 0) {
            g = g.copy(meta = withMeta(g.meta, "strength_exp" to metaInt(g.meta, "strength_exp") + 2))
        }
        if (outcome.flags and INC_INTEL != 0) {
            g = g.copy(meta = withMeta(g.meta, "intel_exp" to metaInt(g.meta, "intel_exp") + 2))
        }
        if (outcome.flags and INC_GOLD != 0) {
            g = g.copy(gold = g.gold + 300)
            text = text.replace(":goldAmount:", "300")
        }
        if (outcome.flags and INC_RICE != 0) {
            g = g.copy(rice = g.rice + 300)
            text = text.replace(":riceAmount:", "300")
        }
        if (outcome.flags and DEC_GOLD != 0) {
            g = g.copy(gold = maxOf(0, g.gold - 200))
            text = text.replace(":goldAmount:", "200")
        }
        if (outcome.flags and DEC_RICE != 0) {
            g = g.copy(rice = maxOf(0, g.rice - 200))
            text = text.replace(":riceAmount:", "200")
        }
        if (outcome.flags and WOUNDED != 0) {
            val woundedDraw = if (replayed == null) rng.nextRangeInt(10, 20) else checkNotNull(replayed.woundedDraw)
            require(woundedDraw in 10..20) { "captured sightseeing wounded draw is outside 10..20" }
            g = g.copy(injury = minOf(80, g.injury + woundedDraw))
        } else if (replayed?.woundedDraw != null) {
            error("captured sightseeing wounded draw has no matching outcome flag")
        }
        if (outcome.flags and HEAVY_WOUNDED != 0) {
            val heavyWoundedDraw =
                if (replayed == null) rng.nextRangeInt(20, 50) else checkNotNull(replayed.heavyWoundedDraw)
            require(heavyWoundedDraw in 20..50) { "captured sightseeing heavy-wounded draw is outside 20..50" }
            g = g.copy(injury = minOf(80, g.injury + heavyWoundedDraw))
        } else if (replayed?.heavyWoundedDraw != null) {
            error("captured sightseeing heavy-wounded draw has no matching outcome flag")
        }

        context.addLog("$text <1>${context.date}</>")
        val expResult = addExperience(g, exp, pipeline)
        g = expResult.general
        expResult.plainLog?.let(context::addPlainLog)
        g = g.copy(lastTurn = LastTurn(name))
        val statResult = checkStatChange(g)
        d.general = statResult.general
        statResult.plainLogs.forEach(context::addPlainLog)
    }

    private data class Outcome(
        val flags: Int,
        val texts: List<String>,
        val weight: Double = 1.0,
    ) {
        val type: Int get() = flags
    }

    private companion object {
        const val INC_EXP = 0x1
        const val INC_HEAVY_EXP = 0x2
        const val INC_LEADERSHIP = 0x10
        const val INC_STRENGTH = 0x20
        const val INC_INTEL = 0x40
        const val INC_GOLD = 0x100
        const val INC_RICE = 0x200
        const val DEC_GOLD = 0x400
        const val DEC_RICE = 0x800
        const val WOUNDED = 0x1000
        const val HEAVY_WOUNDED = 0x2000

        val OUTCOMES = listOf(
            Outcome(INC_EXP, listOf("아무일도 일어나지 않았습니다.", "명사와 설전을 벌였으나 망신만 당했습니다.", "동네 장사와 힘겨루기를 했지만 망신만 당했습니다.")),
            Outcome(INC_HEAVY_EXP, listOf("주점에서 사람들과 어울려 술을 마셨습니다.", "위기에 빠진 사람을 구해주었습니다.")),
            Outcome(INC_HEAVY_EXP or INC_LEADERSHIP, listOf("백성들에게 현인의 가르침을 설파했습니다.", "어느 집의 도망친 가축을 되찾아 주었습니다."), 2.0),
            Outcome(INC_HEAVY_EXP or INC_STRENGTH, listOf("동네 장사와 힘겨루기를 하여 멋지게 이겼습니다.", "어느 집의 무너진 울타리를 고쳐주었습니다."), 2.0),
            Outcome(INC_HEAVY_EXP or INC_INTEL, listOf("어느 명사와 설전을 벌여 멋지게 이겼습니다.", "거리에서 글 모르는 아이들을 모아 글을 가르쳤습니다."), 2.0),
            Outcome(INC_EXP or INC_GOLD, listOf("지나가는 행인에게서 금을 <C>:goldAmount:</> 받았습니다.")),
            Outcome(INC_EXP or INC_RICE, listOf("지나가는 행인에게서 쌀을 <C>:riceAmount:</> 받았습니다.")),
            Outcome(INC_EXP or DEC_GOLD, listOf("산적을 만나 금 <C>:goldAmount:</>을 빼앗겼습니다.", "돈을 <C>:goldAmount:</> 빌려주었다가 떼어먹혔습니다.")),
            Outcome(INC_EXP or DEC_RICE, listOf("쌀을 <C>:riceAmount:</> 빌려주었다가 떼어먹혔습니다.")),
            Outcome(INC_EXP or WOUNDED, listOf("호랑이에게 물려 다쳤습니다.", "곰에게 할퀴어 다쳤습니다.")),
            Outcome(INC_HEAVY_EXP or WOUNDED, listOf("위기에 빠진 사람을 구해주다가 다쳤습니다.")),
            Outcome(INC_EXP or HEAVY_WOUNDED, listOf("호랑이에게 물려 크게 다쳤습니다.", "곰에게 할퀴어 크게 다쳤습니다.")),
            Outcome(INC_HEAVY_EXP or WOUNDED or HEAVY_WOUNDED, listOf("위기에 빠진 사람을 구하다가 죽을뻔 했습니다.")),
            Outcome(INC_HEAVY_EXP or INC_STRENGTH or INC_GOLD, listOf("산적과 싸워 금 <C>:goldAmount:</>을 빼앗았습니다.")),
            Outcome(INC_HEAVY_EXP or INC_STRENGTH or INC_RICE, listOf("호랑이를 잡아 고기 <C>:riceAmount:</>을 얻었습니다.", "곰을 잡아 고기 <C>:riceAmount:</>을 얻었습니다.")),
            Outcome(INC_HEAVY_EXP or INC_INTEL or INC_GOLD, listOf("돈을 빌려주었다가 이자 <C>:goldAmount:</>을 받았습니다.")),
            Outcome(INC_HEAVY_EXP or INC_INTEL or INC_RICE, listOf("쌀을 빌려주었다가 이자 <C>:riceAmount:</>을 받았습니다.")),
        )
    }
}
