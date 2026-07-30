package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.GeneralBuilder
import kotlin.math.sqrt

/**
 * che_인재탐색 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_인재탐색.php`.
 *
 * Scout for a new NPC general, AI-emitted by 방랑군이동/국가선택/사망대비/중립 (GeneralAI.php:
 * 3185/3412/3440/3448) — and the che_해산 < init-turn `alternative` fallback (che_해산.php:78).
 *
 * argTest (che_인재탐색.php:27-31): `arg = null` → always true (no args).
 *
 * fullConditionConstraints (che_인재탐색.php:43-46), in PHP ORDER (first-deny-wins):
 *   [ReqGeneralGold(reqGold), ReqGeneralRice(reqRice)]   where getCost() = [env['develcost'], 0]
 * (reqGold = develcost, reqRice = 0).
 *
 * run() (che_인재탐색.php:113-226): a `nextBool(foundProp)` then a `choiceUsingWeight` over the three
 * exp pools, and on a success the NPC-pool generation (`pickGeneralFromPool` + age/lifespan draws).
 * The per-turn AI draw STREAM is the P5 gate target (catalog §5); the NPC-pool generation is its own
 * downstream subsystem (pickGeneralFromPool / Sightseeing-style content), modeled here as a seam:
 * the def ports the gate-critical constraint pack + argTest; the create-NPC resolve is a downstream
 * port that does not affect the AI SELECTION draw-for-draw gate.
 */
class CheInjaeTamsaek(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_인재탐색"
    override val name: String get() = "인재탐색"
    override val category: String get() = "인사"

    /** getCost (che_인재탐색.php:76-79) = [env['develcost'], 0]. */
    private fun reqGold(ctx: ConstraintContext): Int = (ctx.env["develcost"] as? Number ?: ctx.env["develCost"] as? Number)?.toInt() ?: 0

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        reqGeneralGold { c, _ -> reqGold(c) },
        reqGeneralRice { _, _ -> 0 },
    )

    /** che_인재탐색.php:27-31 argTest — no args. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    var lastBuiltNpc: BuiltScoutNpc? = null
        private set

    override fun resolve(context: GeneralActionResolveContext) {
        lastBuiltNpc = null
        val input = ScoutInput.from(context.args) ?: return
        val d = context.draft
        val foundProp = calcFoundProp(input.maxGenCnt, input.totalGenCnt, input.totalNpcCnt)
        val foundNpc = context.rng.nextBool(foundProp)
        var g = d.general

        if (!foundNpc) {
            context.addLog("인재를 찾을 수 없었습니다. <1>${context.date}</>")
            val incStat = context.rng.choiceUsingWeight(linkedMapOf(
                "leadership_exp" to g.leadership.toDouble(),
                "strength_exp" to g.strength.toDouble(),
                "intel_exp" to g.intel.toDouble(),
            ))
            g = applyActorDelta(context, g, incStat, 100.0, 70.0, reqGoldFrom(input.develCost))
            d.general = finalize(context, g)
            return
        }

        val exp = 100.0 * (sqrt(1.0 / foundProp) + 1.0)
        val ded = 150.0 * (sqrt(1.0 / foundProp) + 1.0)
        val age = context.rng.nextRangeInt(20, 25)
        val birthYear = input.year - age
        val deathYear = input.year + context.rng.nextRangeInt(10, 50)
        val npcName = pickRandomGeneralName(context.rng, input.existingGeneralNames)
        val specAge = phpRound((GameConst.retirementYear - age).toDouble() / 12.0).toInt() + age
        val specAge2 = phpRound((GameConst.retirementYear - age).toDouble() / 6.0).toInt() + age
        val built = GeneralBuilder(context.rng, npcName, 0)
            .setSpecial("None", "None")
            .setNPCType(3)
            .setMoney(1000, 1000)
            .setLifeSpan(birthYear, deathYear)
            .setSpecYear(specAge, specAge2)
            .fillRemainSpecAsRandom(
                linkedMapOf("무" to 6.0, "지" to 6.0, "무지" to 3.0),
                input.avgGenDexTotal,
                input.avgGenDex5,
                true,
                input.year,
                input.startYear,
                false,
            )
            .build(input.year, input.month, input.turnterm, input.cityPool)
        if (built != null) lastBuiltNpc = BuiltScoutNpc(built.name, built)

        val resolvedName = built?.name ?: npcName
        val actorName = (g.meta["name"] as? String) ?: ""
        val josaRa = JosaUtil.pick(resolvedName, "라")
        val josaYi = JosaUtil.pick(actorName, "이")
        context.addGeneralHistoryLog("<Y>$resolvedName</>${josaRa}는 <C>인재</>를 발견")
        context.addLog("<Y>$resolvedName</>${josaRa}는 <C>인재</>를 발견하였습니다! <1>${context.date}</>")
        context.addGlobalActionLog("<Y>$actorName</>$josaYi <Y>$resolvedName</>${josaRa}는 <C>인재</>를 발견하였습니다!")
        val incStat = context.rng.choiceUsingWeight(linkedMapOf(
            "leadership_exp" to g.leadership.toDouble(),
            "strength_exp" to g.strength.toDouble(),
            "intel_exp" to g.intel.toDouble(),
        ))
        g = applyActorDelta(context, g, incStat, 200.0, 300.0, reqGoldFrom(input.develCost))
        d.general = finalize(context, g)
        StaticEventHandler.handleEvent(d.general, d.destGeneral, key, emptyMap(), context.args)
    }

    private fun reqGoldFrom(develCost: Int): Int = develCost

    private fun applyActorDelta(
        context: GeneralActionResolveContext,
        general: opensamguk.logic.domain.General,
        incStat: String,
        exp: Double,
        ded: Double,
        goldCost: Int,
    ): opensamguk.logic.domain.General {
        var g = general.copy(gold = maxOf(0, general.gold - goldCost))
        val experience = addExperience(g, exp, pipeline)
        g = experience.general
        experience.plainLog?.let(context::addPlainLog)
        val dedication = addDedication(g, ded, pipeline)
        g = dedication.general
        dedication.plainLog?.let(context::addPlainLog)
        g = g.copy(
            meta = withMeta(g.meta, incStat to metaDouble(g.meta, incStat) + if (exp == 100.0) 1.0 else 3.0),
            lastTurn = LastTurn(name),
        )
        return g
    }

    private fun finalize(
        context: GeneralActionResolveContext,
        general: opensamguk.logic.domain.General,
    ): opensamguk.logic.domain.General {
        val result = checkStatChange(general)
        result.plainLogs.forEach(context::addPlainLog)
        return result.general
    }

    private fun calcFoundProp(maxGenCnt: Int, totalGenCnt: Int, totalNpcCnt: Int): Double {
        val current = (totalGenCnt + totalNpcCnt / 2.0).toInt()
        val remain = maxOf(0, maxGenCnt - current)
        val main = (remain.toDouble() / maxGenCnt).let { it * it * it * it * it * it }
        val small = 1.0 / (totalNpcCnt / 3.0 + 1.0)
        val big = 1.0 / maxGenCnt
        return if (totalNpcCnt < 50) maxOf(main, small) else maxOf(main, big)
    }

    data class BuiltScoutNpc(val name: String, val built: opensamguk.logic.world.BuiltGeneral)

    private data class ScoutInput(
        val maxGenCnt: Int,
        val totalGenCnt: Int,
        val totalNpcCnt: Int,
        val avgGenDexTotal: Double,
        val avgGenDex5: Int,
        val year: Int,
        val startYear: Int,
        val month: Int,
        val develCost: Int,
        val turnterm: Int,
        val cityPool: List<GeneralBuilder.CityChoice>,
        val existingGeneralNames: List<String>,
    ) {
        companion object {
            fun from(args: Map<String, Any?>): ScoutInput? {
                fun int(key: String) = (args[key] as? Number)?.toInt()
                val pool = (args["cityPool"] as? List<*>)?.mapNotNull { row ->
                    val map = row as? Map<*, *> ?: return@mapNotNull null
                    val id = (map["id"] as? Number)?.toInt() ?: return@mapNotNull null
                    val nation = (map["nationId"] as? Number)?.toInt() ?: 0
                    GeneralBuilder.CityChoice(id, nation)
                } ?: return null
                return ScoutInput(
                    int("maxGenCnt") ?: return null,
                    int("totalGenCnt") ?: return null,
                    int("totalNpcCnt") ?: return null,
                    (args["avgGenDexTotal"] as? Number)?.toDouble() ?: return null,
                    int("avgGenDex5") ?: return null,
                    int("year") ?: return null,
                    int("startYear") ?: return null,
                    int("month") ?: return null,
                    int("develCost") ?: return null,
                    int("turnterm") ?: return null,
                    pool,
                    (args["existingGeneralNames"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                )
            }
        }
    }

    companion object {
        internal fun pickRandomGeneralName(rng: opensamguk.common.rng.RandUtil, existingGeneralNames: List<String>): String {
            var loopCnt = 0
            while (true) {
                val generalName = listOf(
                    rng.choice(GameConst.randGenFirstName),
                    rng.choice(GameConst.randGenMiddleName),
                    rng.choice(GameConst.randGenLastName),
                ).joinToString("")
                val duplicateCnt = GENERAL_NAME_PREFIXES.sumOf { npcPrefix ->
                    existingGeneralNames.count { it.startsWith(npcPrefix + generalName) }
                }
                if (duplicateCnt == 0) return generalName
                if (loopCnt >= 99 || duplicateCnt < 2) return generalName + (duplicateCnt + 1)
                loopCnt += 1
            }
        }

        private val GENERAL_NAME_PREFIXES = listOf("", "ⓝ", "ⓝ", "ⓜ", "ⓖ", "㉥", "ⓤ", "ⓞ")
    }
}
