package opensamguk.logic.actions.founding

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinAction
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.beOpeningPart
import opensamguk.logic.constraints.checkNationNameDuplicate
import opensamguk.logic.constraints.constructableCity
import opensamguk.logic.constraints.noPenalty
import opensamguk.logic.constraints.reqNationValue
import opensamguk.logic.constraints.wanderingNation
import opensamguk.logic.domain.GetNationColors
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.withMeta
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.StringUtil
import opensamguk.logic.traits.NationTypeModule
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.world.foundAssaultCrewCost

/**
 * Typed intent for PHP's trailing `tryUniqueItemLottery(genGenericUniqueRNGFromGeneral(...), ...)`.
 *
 * The actual lottery is DB-wide engine work: it needs live human general count, occupied non-buyable
 * item slots, inheritRandomUnique/return-point handling, and the unique item grant/update channel.
 * Logic resolvers therefore only publish this tail intent after the PHP-ordered writes/checkStat/static
 * hooks have completed; production must consume it in the engine instead of fabricating local odds.
 */
data class GeneralUniqueLotteryIntent(
    val generalId: Int,
    val year: Int,
    val month: Int,
    val seedReason: String,
    val acquireType: String,
    val afterTail: String,
)

/**
 * che_건국 — faithful port of `che_건국.php`. RAISES the actor's existing WANDERING nation (level 0→1)
 * by claiming the home city, in contrast to 거병 (FND3) which INSERTs a brand-new nation.
 *
 * Run sequence (`che_건국.php` run() lines 134-213), preserved exactly:
 *   1. same-month guard: `joinYearMonth(year,month) <= joinYearMonth(init_year,init_month)` → push
 *      "다음 턴부터 건국할 수 있습니다." + alternative che_인재탐색, return WITHOUT any write. The decision is
 *      engine/env math (init_year/init_month are not on the logic WorldEnv) → preloaded as the
 *      `sameMonthOrBefore` arg.
 *   2. resolve nationName/nationType (the arg map) + colorType → GetNationColors()[colorType].
 *   3. general/global action logs, then global/general/national history logs.
 *   4. exp += 1000, ded += 1000 (folded through the pipeline).
 *   5. aux['can_국기변경'] = 1 on the nation.
 *   6. city UPDATE nation=me, conflict='{}' (the home-city claim).
 *   7. nation UPDATE name/color/level=1/type=nationType/capital=cityId/aux.
 *   8. increaseInheritancePoint(active_action, 1) — the P6 succession seam (no write).
 *   9. increaseInheritancePoint(unifier, **250**) — the che_건국-ONLY unifier grant ([lastUnifierGrant]);
 *      cr_건국 diverges (NO unifier).
 *  10. setResultTurn(LastTurn(getName()='건국', $this->arg)) → general.lastTurn; checkStatChange finalize.
 *  11. tryUniqueItemLottery(reason '건국') AFTER all writes — the SEPARATE unique rng (NPCType>=2
 *      short-circuit, engine seam); it never draws from the action stream and the default human wins nothing.
 *
 * Adapter-preloaded inputs (PHP DB/env work the resolver does not run): `nationName`/`nationType`/
 * `colorType` (the parsed arg), `relYear` (env year-startyear), `sameMonthOrBefore` (the env same-month math).
 */
open class CheGeonguk(protected val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_건국"
    override val name: String get() = "건국"
    override val category: String get() = "인사"
    override val rawClassName: String get() = "che_건국"
    override val argsSchema: Map<String, Any?> get() =
        mapOf("nationName" to "string", "nationType" to "string", "colorType" to "int")

    /** The unifier inheritance-point grant observed on the last [resolve] — 250 for che_건국, 0 for cr_건국. */
    var lastUnifierGrant: Int = 0
        protected set

    /** The alternative command-key swapped in on a blocked run (che_인재탐색), or null on success. */
    var lastAlternative: String? = null
        protected set

    var lastUniqueLotteryIntent: GeneralUniqueLotteryIntent? = null
        protected set

    /**
     * 마지막 [resolve] 에서 공백지 수비병을 뚫는 데 쓴 병력 (han 전용 divergence). che 는 항상 0.
     * **패러티 아님 · 튜닝값** — 계산은 [foundAssaultCrewCost].
     */
    var lastAssaultCrewCost: Int = 0
        protected set

    /** che_건국 grants unifier +250; cr_건국 overrides to 0 (the divergence). */
    protected open val unifierGrant: Int get() = 250

    /** che_건국 uses ConstructableCity + NoPenalty; cr_건국 overrides to NeutralCity (no NoPenalty). */
    protected open fun cityConstraint(): Constraint = constructableCity()
    protected open fun extraFullConstraints(): List<Constraint> = listOf(noPenalty(PENALTY_NO_FOUND_NATION))

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val nationName = raw["nationName"] as? String ?: return emptyMap()
        val nationType = raw["nationType"] as? String ?: return emptyMap()
        val colorType = raw["colorType"] as? Int ?: return emptyMap()
        if (nationName.isEmpty() || StringUtil.mbStrwidth(nationName) > 18) return emptyMap()
        if (!isKnownNationTypeAlias(nationType)) return emptyMap()
        if (colorType !in GetNationColors().indices) return emptyMap()
        return linkedMapOf(
            "nationName" to nationName,
            "nationType" to nationType,
            "colorType" to colorType,
        )
    }

    // che_건국.php:84-88 — min: BeOpeningPart(relYear+1), ReqNationValue('level','국가규모','==',0), NoPenalty.
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beOpeningPart { c, _ -> relYearOf(c) + 1 },
        reqNationValue("level", "국가규모", "==", 0, "정식 국가가 아니어야합니다."),
        noPenalty(PENALTY_NO_FOUND_NATION),
    )

    // che_건국.php:100-109 — full: BeLord, WanderingNation, ReqNationValue('gennum','수하 장수','>=',2),
    //   BeOpeningPart(relYear+1), CheckNationNameDuplicate(name), AllowJoinAction, ConstructableCity, NoPenalty.
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = buildList {
        add(beLord())
        add(wanderingNation())
        add(reqNationValue("gennum", "수하 장수", ">=", 2))
        add(beOpeningPart { c, _ -> relYearOf(c) + 1 })
        add(checkNationNameDuplicate("nationName"))
        add(allowJoinAction())
        add(cityConstraint())
        addAll(extraFullConstraints())
    }

    override fun resolve(context: GeneralActionResolveContext) {
        lastUnifierGrant = 0
        lastAlternative = null
        lastUniqueLotteryIntent = null
        lastAssaultCrewCost = 0

        val d = context.draft
        val nation = d.nation ?: return

        // 1. same-month guard (che_건국.php:148-154) — no write.
        if (context.args["sameMonthOrBefore"] == true) {
            context.addLog("다음 턴부터 건국할 수 있습니다. <1>${context.date}</>")
            lastAlternative = ALTERNATIVE_BLOCKED
            return
        }

        foundNation(context, nation, d.city.id)
    }

    /** Shared founding write-set (che_건국.php:156-211); 무작위건국 supplies its own dest city + extra aux. */
    protected fun foundNation(
        context: GeneralActionResolveContext,
        nation: Nation,
        cityId: Int,
        extraAux: List<Pair<String, Any?>> = emptyList(),
    ) {
        val d = context.draft
        val nationName = context.args["nationName"] as? String ?: return
        val nationType = context.args["nationType"] as? String ?: return
        val colorType = (context.args["colorType"] as? Number)?.toInt() ?: return
        val color = GetNationColors().getOrNull(colorType) ?: return
        val cityName = CityConst.byId(cityId)?.name ?: ""

        // 3. logs (che_건국.php:172-178).
        val josaUl = JosaUtil.pick(nationName, "을")
        val josaYi = JosaUtil.pick(context.generalName, "이")
        context.addLog("<D><b>$nationName</b></>$josaUl 건국하였습니다. <1>${context.date}</>")
        context.addGlobalActionLog(
            "<Y>${context.generalName}</>$josaYi <G><b>$cityName</b></>에 국가를 건설하였습니다.")
        val nationTypeName = (NationTypeRegistry.resolve(nationTypeModuleKey(nationType)) as? NationTypeModule)?.typeName ?: "-"
        val josaNationYi = JosaUtil.pick(nationName, "이")
        context.addGlobalHistoryLog("<Y><b>【건국】</b></>$nationTypeName <D><b>$nationName</b></>$josaNationYi 새로이 등장하였습니다.")
        context.addGeneralHistoryLog("<D><b>$nationName</b></>$josaUl 건국")
        context.addNationalHistoryLog("<Y>${context.generalName}</>$josaYi <D><b>$nationName</b></>$josaUl 건국")

        // 4. exp/ded +1000 (che_건국.php:180-184).
        val expRes = addExperienceFold(context)
        // 6. city claim: nation=me, conflict={} (che_건국.php:189-192).
        val nextCityMeta = LinkedHashMap(d.city.meta)
        nextCityMeta.remove("conflict")
        d.city = d.city.copy(nationId = nation.id, conflict = "{}", meta = nextCityMeta)

        // 6-b. han 전용 수비병 돌파(패러티 아님 · 튜닝값 [FOUND_ASSAULT_RATIO]). 병력으로 수비병을 쓸어내고
        //      그만큼 crew 를 잃는다. che 는 비용이 항상 0이라 crew/def 델타가 생기지 않는다 = 골든 무변.
        //      두 변경 모두 draft(=ChangeRecorder 델타) 위에서만 일어난다.
        lastAssaultCrewCost = foundAssaultCrewCost(context.args["mapName"] as? String, d.city.defense)
        if (lastAssaultCrewCost > 0) {
            d.city = d.city.copy(defense = 0)
        }

        // 5 + 7. nation: name/color/level=1/type/capital + aux can_국기변경=1 (+ extras for 무작위건국)
        //         (che_건국.php:186-201). PHP sets aux BEFORE the UPDATE; aux key order = set order.
        d.nation = nation.copy(
            name = nationName,
            color = color,
            level = 1,
            typeCode = nationType,
            capitalCityId = cityId,
            meta = withFoundingNationAux(nation.meta, listOf<Pair<String, Any?>>("can_국기변경" to 1) + extraAux),
        )

        // 9. unifier grant (che_건국.php:206 — che_건국 only; cr_건국/무작위건국 override to 0).
        lastUnifierGrant = unifierGrant

        // 10. setResultTurn(LastTurn('건국', arg)) + checkStatChange (che_건국.php:207-208).
        val arg = linkedMapOf<String, Any?>(
            "nationName" to nationName,
            "nationType" to nationType,
            "colorType" to colorType,
        )
        var g = expRes.copy(
            crew = (expRes.crew - lastAssaultCrewCost).coerceAtLeast(0),
            lastTurn = LastTurn(command = name, arg = arg),
        )
        val statRes = opensamguk.logic.domestic.checkStatChange(g)
        statRes.plainLogs.forEach { context.addPlainLog(it) }
        d.general = statRes.general
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), context.args)

        // 11. tryUniqueItemLottery(genGenericUniqueRNGFromGeneral(general, static::$actionName), general, '건국')
        //     — the SEPARATE unique-rng seam (NPCType>=2 short-circuit), fired AFTER all writes by the
        //     engine handler. It NEVER draws from the action stream; the default P2 human wins nothing.
        lastUniqueLotteryIntent = GeneralUniqueLotteryIntent(
            generalId = d.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = lotteryActionName,
            acquireType = "건국",
            afterTail = "setResultTurn>checkStatChange>StaticEventHandler",
        )
    }

    /** exp/ded +1000 through the pipeline (che_건국.php:183-184); PLAIN level logs routed to the context. */
    private fun addExperienceFold(context: GeneralActionResolveContext): opensamguk.logic.domain.General {
        val expRes = opensamguk.logic.domestic.addExperience(context.draft.general, EXP_DED_GRANT, pipeline)
        val dedRes = opensamguk.logic.domestic.addDedication(expRes.general, EXP_DED_GRANT, pipeline)
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }
        return dedRes.general
    }

    protected fun relYearOf(ctx: ConstraintContext): Int =
        (ctx.args["relYear"] as? Number)?.toInt() ?: 0

    private fun isKnownNationTypeAlias(nationType: String): Boolean {
        val normalized = nationTypeModuleKey(nationType)
        return normalized == "None" || normalized == GameConst.neutralNationType || normalized in GameConst.availableNationType
    }

    private fun nationTypeModuleKey(nationType: String): String =
        when {
            nationType.isEmpty() -> GameConst.neutralNationType
            nationType == "None" -> nationType
            nationType.startsWith("che_") -> nationType
            else -> "che_$nationType"
        }

    companion object {
        /** PenaltyKey::NoFoundNation enum VALUE (Enums/PenaltyKey.php) — the penalty-map key. */
        const val PENALTY_NO_FOUND_NATION: String = "noFoundNation"

        /** exp/ded grant on success (che_건국.php:180-181). */
        const val EXP_DED_GRANT: Double = 1000.0

        /** The alternative command swapped in on the same-month block (che_건국.php:152). */
        const val ALTERNATIVE_BLOCKED: String = "che_인재탐색"
    }
}

/**
 * Write `key=value` pairs into the nested `aux` map on a NATION's `meta` jsonb, preserving existing
 * entries + insertion order. Mirrors PHP `$nation['aux'][key] = value` + Json::encode (aux key order =
 * the PHP set order). The founding package's own copy (the nation package's [withNationAux] is internal
 * to that package).
 */
internal fun withFoundingNationAux(meta: Map<String, Any?>, pairs: List<Pair<String, Any?>>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val curAux = (meta["aux"] as? Map<String, Any?>) ?: emptyMap()
    val nextAux = LinkedHashMap(curAux)
    for ((k, v) in pairs) nextAux[k] = v
    return withMeta(meta, "aux" to nextAux)
}
