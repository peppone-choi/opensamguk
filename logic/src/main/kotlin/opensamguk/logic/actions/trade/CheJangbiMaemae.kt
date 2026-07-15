package opensamguk.logic.actions.trade

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.reqCityCapacity
import opensamguk.logic.constraints.reqCityTrader
import opensamguk.logic.constraints.reqGeneralGold
import opensamguk.logic.constraints.reqGeneralRice
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.domestic.checkStatChange
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.actions.founding.GeneralUniqueLotteryIntent
import opensamguk.logic.items.BaseStatItemModule
import opensamguk.logic.items.ItemRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.valueFit
import kotlin.math.truncate

/**
 * che_장비매매 — PHP che_장비매매.php:27-205 (Research Unit 6). The reqArg equip-slot trade command:
 * BUY an item (pay its gold cost, equip the slot) or SELL the equipped slot item (refund cost/2 gold,
 * clear the slot, broadcast when the item is a rare non-buyable).
 *
 * argTest (che_장비매매.php:39-62): `itemType` must be one of the four slot keys (horse/weapon/book/item);
 * `itemCode` must be a key of `GameConst.allItems[itemType]` OR the sell sentinel `'None'`; the
 * `buildItemClass(itemCode)` must be `isBuyable()` (the `None` class is buyable=true, so the sell sentinel
 * passes — but a rare non-buyable item can never be a BUY target). The normalized arg is the
 * `{itemType, itemCode}` insertion-order map.
 *
 * getCost (che_장비매매.php:104-115): reqGold = buildItemClass(itemCode).getCost(); reqRice = 0. (For the
 * sell sentinel `None` the cost is 0 — only the buy path gates on gold.)
 *
 * full-condition constraints (che_장비매매.php:88-101): ReqCityTrader(npcType) + ReqCityCapacity('secu',
 * '치안 수치', itemClass.getReqSecu()) + ReqGeneralGold(reqGold) + ReqGeneralRice(reqRice); plus, when
 * `itemCode === 'None'`, ReqGeneralValue(itemType, '!=', 'None') (the slot must hold something to sell);
 * when buying the SAME item already equipped → AlwaysFail('이미 가지고 있습니다.'); when the currently
 * equipped item is itself a non-buyable rare → AlwaysFail('이미 진귀한 것을 가지고 있습니다.'). The
 * min-condition is ReqCityTrader only (che_장비매매.php:72-74).
 *
 * run (che_장비매매.php:146-205):
 *   BUY  → log "<C>{name}</>{josa-을} 구입했습니다. <1>date</>"; gold -= cost (increaseVarWithLimit floor 0);
 *          setItem(type, code); onArbitraryAction('장비매매','구매',{itemCode}) (환약 initializes remain환약=3).
 *   SELL → itemCode = the equipped slot code; log "<C>{name}</>{josa-을} 판매했습니다. <1>date</>";
 *          gold += cost/2 (increaseVarWithLimit, no floor); onArbitraryAction('장비매매','판매',{itemCode});
 *          setItem(type, null→'None'); when the sold item is NOT buyable → a global action log
 *          "<Y>{generalName}</>{josa-이} <C>{name}</>{josa-을} 판매했습니다!" (the global HISTORY log is a
 *          GATE-RUNTIME history-bucket seam).
 *   Always → addExperience(10); setResultTurn(LastTurn(name, arg)); checkStatChange; trailing
 *            unique-item lottery (separate rng, not drawn in resolve).
 *
 * The item +stat fold behaviour is IMPORTED from ITEMS (BaseStatItemModule via ItemRegistry); only the
 * trade metadata (cost/buyable/reqSecu — NOT carried on the stat modules) is ported here from the PHP
 * ActionItem class declarations.
 */
class CheJangbiMaemae(
    private val pipeline: GeneralActionPipeline,
    private val maxLevel: Int = 255,
    /** The parsed arg (itemType/itemCode). null = not bound (registry default); resolve() is then a no-op. */
    private val arg: Map<String, Any?>? = null,
) : GeneralActionDefinition {
    override val key: String = "che_장비매매"
    override val name: String = "장비매매"
    override val category: String = "자원교역"
    override val argsSchema: Map<String, Any?> get() = mapOf("itemType" to "string", "itemCode" to "string")

    override fun bindArgs(parsed: Map<String, Any?>): CheJangbiMaemae =
        CheJangbiMaemae(pipeline, maxLevel, parsed)

    fun withArg(parsed: Map<String, Any?>): CheJangbiMaemae = bindArgs(parsed)

    var lastUniqueLotteryIntent: GeneralUniqueLotteryIntent? = null
        private set

    /** PHP $itemMap (che_장비매매.php:32-37): the four equip-slot keys → the Korean slot name. */
    private val itemMap: Map<String, String> = ITEM_MAP

    /**
     * PHP argTest (che_장비매매.php:39-62). itemType ∈ itemMap; itemCode ∈ allItems[type] or 'None';
     * buildItemClass(itemCode).isBuyable(). Returns the normalized {itemType, itemCode} map, or empty on
     * reject (PHP arg=null → non-runnable).
     */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val itemType = raw["itemType"] as? String ?: return emptyMap()
        if (itemType !in itemMap.keys) return emptyMap()
        val itemCode = raw["itemCode"] as? String ?: return emptyMap()
        val typeItems = GameConst.allItems[itemType] ?: return emptyMap()
        if (itemCode != "None" && itemCode !in typeItems.keys) return emptyMap()
        // a non-buyable item can never be a BUY target (None is buyable → sell sentinel passes).
        if (!itemTradeInfo(itemCode).buyable) return emptyMap()
        return linkedMapOf("itemType" to itemType, "itemCode" to itemCode)
    }

    /** PHP getCost (che_장비매매.php:104-115): reqGold = item cost; reqRice = 0. */
    fun getCost(itemCode: String): Pair<Int, Int> {
        if (itemCode == "None") return 0 to 0
        return itemTradeInfo(itemCode).cost to 0
    }

    private fun npcType(ctx: ConstraintContext, view: StateView): Int =
        (view.get(RequirementKey.General(ctx.actorId)) as? General)?.npcType ?: 0

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        val cons = mutableListOf<Constraint>(reqCityTrader(::npcType))
        val a = arg ?: ctx.args
        val itemType = a["itemType"] as? String
        val itemCode = a["itemCode"] as? String
        if (itemType == null || itemCode == null) return cons  // min-condition only when unbound

        val info = itemTradeInfo(itemCode)
        val (reqGold, reqRice) = getCost(itemCode)
        cons += reqCityCapacity("secu", "치안 수치", info.reqSecu)
        cons += reqGeneralGold { _, _ -> reqGold }
        cons += reqGeneralRice { _, _ -> reqRice }

        if (itemCode == "None") {
            // ReqGeneralValue(itemType, itemTypeName, '!=', 'None') — the slot must hold an item to sell.
            val typeName = itemMap.getValue(itemType)
            cons += itemSlotNotNone(itemType, typeName)
        } else {
            // initWithArg (che_장비매매.php:97-101): SAME item already equipped / already holding a rare item.
            val equipped = { g: General -> slotCode(g, itemType) }
            cons += object : Constraint {
                override val name = "JangbiMaemaeAlreadyOwn"
                override fun requires(c: ConstraintContext) = listOf(RequirementKey.General(c.actorId))
                override fun test(c: ConstraintContext, view: StateView): ConstraintResult {
                    val g = view.get(RequirementKey.General(c.actorId)) as? General
                        ?: return ConstraintResult.Unknown(requires(c))
                    val cur = equipped(g)
                    if (itemCode == cur) return ConstraintResult.Deny("이미 가지고 있습니다.")
                    if (!itemTradeInfo(cur).buyable) return ConstraintResult.Deny("이미 진귀한 것을 가지고 있습니다.")
                    return ConstraintResult.Allow
                }
            }
        }
        return cons
    }

    /** min-condition (che_장비매매.php:72-74): ReqCityTrader only. */
    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(reqCityTrader(::npcType))

    override fun resolve(context: GeneralActionResolveContext) {
        lastUniqueLotteryIntent = null
        val parsed = arg ?: return     // unbound → argTest would have failed → no run
        resolveWith(context, parsed)
    }

    fun resolveWith(context: GeneralActionResolveContext, parsed: Map<String, Any?>) {
        lastUniqueLotteryIntent = null
        val d = context.draft
        val itemType = parsed["itemType"] as String
        val itemCodeArg = parsed["itemCode"] as String

        val buying = itemCodeArg != "None"
        // SELL: the traded item is the general's CURRENTLY equipped slot code (che_장비매매.php:162).
        val itemCode = if (buying) itemCodeArg else slotCode(d.general, itemType)
        val info = itemTradeInfo(itemCode)
        val cost = info.cost
        val josaUl = JosaUtil.pick(info.rawName, "을")

        if (buying) {
            // che_장비매매.php:176-179.
            context.addLog("<C>${info.name}</>$josaUl 구입했습니다. <1>${context.date}</>")
            d.general = d.general.copy(gold = maxOf(d.general.gold - cost, 0))   // increaseVarWithLimit floor 0
            d.general = setSlot(d.general, itemType, itemCode)
            onArbitraryActionBuy(context, itemCode)
        } else {
            // che_장비매매.php:181-192.
            context.addLog("<C>${info.name}</>$josaUl 판매했습니다. <1>${context.date}</>")
            // increaseVarWithLimit('gold', cost/2) — PHP `$cost/2` is a float; gold is an int column → the
            // old+delta combination truncates toward zero at the write (D1 flush model). No lower floor.
            d.general = d.general.copy(gold = truncate(d.general.gold + cost / 2.0).toInt())
            // onArbitraryAction('장비매매','판매',{itemCode}) — fires while the slot is STILL equipped.
            onArbitraryActionSell(context, itemCode)
            d.general = setSlot(d.general, itemType, "None")   // setItem(type, null)

            if (!info.buyable) {
                // che_장비매매.php:187-191 — global broadcast (the HISTORY log is a GATE-RUNTIME seam).
                val generalName = context.generalName.ifEmpty { nameOf(d.general) }
                val josaYi = JosaUtil.pick(generalName, "이")
                context.addGlobalActionLog("<Y>$generalName</>$josaYi <C>${info.name}</>$josaUl 판매했습니다!")
            }
        }

        // che_장비매매.php:195-199 — fixed addExperience(10), setResultTurn, checkStatChange.
        val expRes = addExperience(d.general, 10.0, pipeline)
        d.general = expRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }

        d.general = d.general.copy(lastTurn = LastTurn(command = name, arg = parsed))

        val sc = checkStatChange(d.general)
        d.general = sc.general
        for (line in sc.plainLogs) context.addPlainLog(line)
        StaticEventHandler.handleEvent(d.general, d.destGeneral, rawClassName, emptyMap(), parsed)
        lastUniqueLotteryIntent = GeneralUniqueLotteryIntent(
            generalId = d.general.id,
            year = context.env.year,
            month = context.month,
            seedReason = lotteryActionName,
            acquireType = "아이템",
            afterTail = "setResultTurn>checkStatChange>StaticEventHandler",
        )
    }

    /**
     * che_보물_도기.onArbitraryAction (che_보물_도기.php:18-52) fires on SELL ('판매') when the sold item IS 도기.
     * Draws one rng `choice([금/gold, 쌀/쌀])`,
     * computes score=10000+5000*valueFit(intdiv(relYear,2),0), adds toInt(score/2) to the nation treasury
     * (when the general has a nation) and the remainder to the general, and pushes a general action log.
     * `onArbitraryAction` iterates the full action module list; 도기 is the sell-side resource hook.
     */
    private fun onArbitraryActionSell(context: GeneralActionResolveContext, soldItemCode: String) {
        if (soldItemCode != "che_보물_도기") return
        val d = context.draft
        val relYear = context.env.relYear
        // score = 10000 + 5000 * valueFit(intdiv(relYear, 2), 0). intdiv truncates toward zero.
        val score = 10000 + 5000 * valueFit((relYear / 2).toDouble(), 0.0, null).toInt()

        // rng->choice([['금','gold'],['쌀','rice']]) — drawn from the ACTION rng.
        val (resName, resKey) = context.rng.choice(listOf("금" to "gold", "쌀" to "rice"))

        val toNation = truncate(score / 2.0).toInt()      // Util::toInt(score/2)
        val toGeneral = score - toNation                  // score - toInt(score/2)

        // nation += toNation (only when the general belongs to a nation, PHP nationId != 0).
        val n = d.nation
        if (n != null && d.general.nationId != 0) {
            d.nation = if (resKey == "gold") n.copy(gold = n.gold + toNation) else n.copy(rice = n.rice + toNation)
        }
        d.general =
            if (resKey == "gold") d.general.copy(gold = d.general.gold + toGeneral)
            else d.general.copy(rice = d.general.rice + toGeneral)

        // Util::round(score) on an integer score is a no-op; number_format(score, 0) = comma grouping.
        val scoreText = numberFormat(score)
        context.addLog("재산과 국고에 총 $resName <C>$scoreText</>을 보충합니다.")
    }

    private fun onArbitraryActionBuy(context: GeneralActionResolveContext, boughtItemCode: String) {
        if (boughtItemCode != "che_치료_환약") return
        val aux = (context.draft.general.meta["aux"] as? Map<String, Any?>) ?: emptyMap()
        val nextAux = LinkedHashMap(aux)
        nextAux["remain환약"] = 3
        context.draft.general = context.draft.general.copy(
            meta = withMeta(context.draft.general.meta, "aux" to nextAux),
        )
    }

    private fun slotCode(g: General, itemType: String): String = when (itemType) {
        "horse" -> g.horse
        "weapon" -> g.weapon
        "book" -> g.book
        "item" -> g.item
        else -> error("unknown item slot $itemType")
    }

    private fun setSlot(g: General, itemType: String, code: String): General = when (itemType) {
        "horse" -> g.copy(horse = code)
        "weapon" -> g.copy(weapon = code)
        "book" -> g.copy(book = code)
        "item" -> g.copy(item = code)
        else -> error("unknown item slot $itemType")
    }

    /** ReqGeneralValue(itemType, itemTypeName, '!=', 'None'): the equip slot must NOT be 'None' to sell. */
    private fun itemSlotNotNone(itemType: String, typeName: String) = object : Constraint {
        override val name = "ReqGeneralValue"
        override fun requires(c: ConstraintContext) = listOf(RequirementKey.General(c.actorId))
        override fun test(c: ConstraintContext, view: StateView): ConstraintResult {
            val g = view.get(RequirementKey.General(c.actorId)) as? General
                ?: return ConstraintResult.Unknown(requires(c))
            if (slotCode(g, itemType) != "None") return ConstraintResult.Allow
            // ReqGeneralValue.php '!=' fail text: "올바르지 않은 {keyNick} 입니다." → "{keyNick}{josa-이} {text}".
            val josaYi = JosaUtil.pick(typeName, "이")
            return ConstraintResult.Deny("$typeName$josaYi 올바르지 않은 $typeName 입니다.")
        }
    }

    companion object {
        /** PHP che_장비매매::$itemMap (che_장비매매.php:32-37). */
        val ITEM_MAP: Map<String, String> = linkedMapOf(
            "horse" to "명마", "weapon" to "무기", "book" to "서적", "item" to "도구",
        )
    }
}

// ---- item trade metadata (ported from the PHP ActionItem class declarations) -----------------------

/** The trade-relevant facet of a `buildItemClass(code)` instance: cost/buyable/reqSecu + display names. */
data class ItemTradeInfo(
    val cost: Int,
    val buyable: Boolean,
    val reqSecu: Int,
    val name: String,
    val rawName: String,
)

/**
 * PHP `buildItemClass(code)` viewed through its trade facet. The +stat name/rawName are IMPORTED from
 * ITEMS ([BaseStatItemModule]); cost/buyable/reqSecu (NOT carried on the stat modules) are ported from the
 * per-class PHP declarations: the stat-item tier (NN prefix) maps uniformly across 명마/무기/서적 to
 * (cost, reqSecu, buyable); tiers 01-06 are the buyable shop tiers, 07-15 are rare non-buyables (cost 200).
 * Non-stat 도구 items (도기 …) carry explicit metadata. 'None'/null → the buyable cost-0 sell sentinel.
 */
private val statItemRegistry = ItemRegistry()

/** Tier (NN) → (cost, reqSecu, buyable) for the stat-item shop (uniform across 명마/무기/서적). */
private val STAT_TIER: Map<Int, Triple<Int, Int, Boolean>> = linkedMapOf(
    1 to Triple(1000, 1000, true),
    2 to Triple(3000, 2000, true),
    3 to Triple(6000, 3000, true),
    4 to Triple(10000, 4000, true),
    5 to Triple(15000, 5000, true),
    6 to Triple(21000, 6000, true),
)

/** Explicit metadata for the non-stat 도구 items 장비매매 may sell (도기 …). */
private val ITEM_SLOT_META: Map<String, ItemTradeInfo> = linkedMapOf(
    "che_보물_도기" to ItemTradeInfo(cost = 200, buyable = false, reqSecu = 0, name = "도기(보물)", rawName = "도기"),
    "che_치료_환약" to ItemTradeInfo(cost = 200, buyable = true, reqSecu = 0, name = "환약(치료)", rawName = "환약"),
)

fun itemTradeInfo(code: String?): ItemTradeInfo {
    val c = code ?: "None"
    if (c == "None") return ItemTradeInfo(cost = 0, buyable = true, reqSecu = 0, name = "-", rawName = "-")
    ITEM_SLOT_META[c]?.let { return it }

    // declarative +stat item: reuse the IT1 token parser for name/rawName, derive trade meta from the tier.
    val mod = statItemRegistry.resolve(c) as? BaseStatItemModule
        ?: error("itemTradeInfo: not a known tradable item $c")
    // tier = the NN token (token[-2]); maps uniformly to (cost, reqSecu, buyable).
    val nn = c.split('_').let { it.getOrNull(it.size - 2) }?.toIntOrNull()
        ?: error("itemTradeInfo: no tier token in $c")
    val (cost, reqSecu, buyable) = STAT_TIER[nn] ?: Triple(200, 0, false)   // 07-15 → rare non-buyable
    return ItemTradeInfo(cost = cost, buyable = buyable, reqSecu = reqSecu, name = mod.name, rawName = mod.rawName)
}
