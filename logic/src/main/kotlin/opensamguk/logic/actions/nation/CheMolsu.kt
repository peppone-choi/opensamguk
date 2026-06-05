package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.friendlyDestGeneral
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notOpeningPart
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.message.Message
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.message.MessageType
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit

/**
 * che_몰수 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_몰수.php`.
 *
 * personnel(인사) 사령 커맨드. 아군(friendly) dest 장수에게서 금/쌀(`amount`)을 국고로 몰수한다.
 * dest PLAIN action 라인. NPCType>=2일 때만 `rng->nextBool(npcSeizureMessageProb=0.01)`(메시지 여부),
 * hit이면 `rng->choice`로 npc 메시지 텍스트(draw-for-draw 패리티 타겟). logLines=1, crossGeneral.
 *
 * argTest(che_몰수.php:30-72): `isGold`(bool) + `amount`(numeric → round(-2) → valueFit[100, max]) +
 *   `destGeneralID`(int,>0) 필요 → canonical `{isGold, amount, destGeneralID}`.
 *
 * fullConditionConstraints(che_몰수.php:93-116), PHP ORDER:
 *   - self-target(destGeneralID==actor): AlwaysFail('본인입니다')
 *   - else: [NotBeNeutral, OccupiedCity, BeChief, NotOpeningPart(relYear), SuppliedCity,
 *            ExistsDestGeneral, FriendlyDestGeneral].
 *
 * TODO(포팅): run() — dest 장수 gold/rice -= amount, nation 국고 += amount, NPCType>=2 메시지 RNG(nextBool→choice),
 *   dest PLAIN action 라인 + 몰수 발동 로그.
 */
fun cheMolsu(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheMolsu =
    CheMolsu(pipeline)

class CheMolsu(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_몰수"
    override val name: String get() = "몰수"
    override val category: String get() = "인사"
    override val argsSchema: Map<String, Any?> get() =
        linkedMapOf("isGold" to "bool", "amount" to "int", "destGeneralID" to "int")

    override fun getPreReqTurn(): Int = 0

    /**
     * che_몰수.php:30-72 argTest. amount는 round(-2) 후 valueFit[100, maxResourceActionAmount].
     * `Util::round($amount, -2)`는 half-away → phpRound(amount, -2). 0 이하/타입 불일치 시 null.
     */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("isGold") || !raw.containsKey("amount") || !raw.containsKey("destGeneralID")) return null
        val isGold = (raw["isGold"] as? Boolean) ?: return null  // PHP is_bool
        val rawAmount = (raw["amount"] as? Number)?.toDouble() ?: return null  // PHP is_numeric
        var amount = phpRound(rawAmount, -2)
        amount = amount.coerceIn(100, GameConst.maxResourceActionAmount)  // Util::valueFit
        if (amount <= 0) return null
        val destGeneralID = (raw["destGeneralID"] as? Int) ?: return null  // PHP is_int
        if (destGeneralID <= 0) return null
        return linkedMapOf("isGold" to isGold, "amount" to amount, "destGeneralID" to destGeneralID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), occupiedCity(), beChief(),
        notOpeningPart { c, _ -> (c.args["relYear"] as? Number)?.toInt() ?: (c.env["relYear"] as? Number)?.toInt() ?: 0 },
        suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> {
        // self-target → AlwaysFail '본인입니다' (che_몰수.php:101-105).
        if ((ctx.args["destGeneralID"] as? Number)?.toInt() == ctx.actorId) {
            return listOf(alwaysFail("본인입니다"))
        }
        return listOf(
            notBeNeutral(), occupiedCity(), beChief(),
            notOpeningPart { c, _ -> (c.args["relYear"] as? Number)?.toInt() ?: (c.env["relYear"] as? Number)?.toInt() ?: 0 },
            suppliedCity(), existsDestGeneral(), friendlyDestGeneral(),
        )
    }

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    /**
     * che_몰수.php:147-221 run() 충실 포팅.
     *
     * 흐름:
     *  1. `amount = valueFit(amount, 0, dest[resKey])` — dest 보유 자원으로 재클램프 (:167-171).
     *  2. NPCType>=2 이면 `rng->nextBool(0.01)` (메시지 발동 여부, draw-for-draw 단일 추첨).
     *     hit이면 `rng->choice(npcTexts)`로 항의 텍스트를 골라 public 메시지 send (:174-201).
     *  3. dest `increaseVarWithLimit(resKey, -amount, 0)` → dest 자원 -= amount (바닥 0) (:205).
     *  4. nation 국고 resKey += amount (:206-208).
     *  5. dest PLAIN 로그 + 몰수 발동 actor 로그 (:212-213).
     *
     * 추첨 순서/개수/메서드 인자가 패러티 타겟: NPCType>=2 일 때만, 그리고 그 1회의 nextBool만이
     * 유일한 추첨(hit일 때 choice 추가). actor 본인의 gold/rice는 변하지 않음(국고로 들어감).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val nation = d.nation ?: return
        val destGeneral = d.destGeneral ?: return
        val isGold = context.args["isGold"] as? Boolean ?: return
        val argAmount = (context.args["amount"] as? Number)?.toInt() ?: return

        // valueFit(amount, 0, dest[resKey]) — dest 보유 자원 한도로 재클램프 (che_몰수.php:167-171).
        val destBalance = if (isGold) destGeneral.gold else destGeneral.rice
        val amount = valueFit(argAmount.toDouble(), 0.0, destBalance.toDouble()).toInt()
        val amountText = numberFormat(amount)
        val resName = if (isGold) "금" else "쌀"

        // NPCType>=2 일 때만 nextBool(npcSeizureMessageProb=0.01) (che_몰수.php:174). 유일한 추첨.
        if (destGeneral.npcType >= 2 && context.rng.nextBool(GameConst.npcSeizureMessageProb)) {
            val npcTexts = listOf(
                "몰수를 하다니... 이것이 윗사람이 할 짓이란 말입니까...",
                "사유재산까지 몰수해가면서 이 나라가 잘 될거라 믿습니까? 정말 이해할 수가 없군요...",
                "내 돈 내놔라! 내 돈! 몰수가 웬 말이냐!",
                "몰수해간 내 자금... 언젠가 몰래 다시 빼내올 것이다...",
                "몰수로 인한 사기 저하는 몰수로 얻은 물자보다 더 손해란걸 모른단 말인가!",
            )
            // hit이면 choice(npcTexts) — 추가 추첨 (che_몰수.php:182).
            val text = context.rng.choice(npcTexts)
            val src = MessageTarget(
                generalId = destGeneral.id,
                generalName = context.destGeneralName,
                nationId = nation.id,
                nationName = nation.name,
                color = nation.color,
            )
            context.sendMessage(
                Message(
                    msgType = MessageType.PUBLIC,
                    src = src,
                    dest = src,
                    msg = text,
                    date = context.date,
                    validUntil = "9999-12-31 00:00:00",
                    msgOption = null,
                ),
            )
        }

        // dest 자원 -= amount (바닥 0); nation 국고 += amount (che_몰수.php:205-208).
        d.destGeneral = if (isGold) destGeneral.copy(gold = (destGeneral.gold - amount).coerceAtLeast(0))
        else destGeneral.copy(rice = (destGeneral.rice - amount).coerceAtLeast(0))
        d.nation = if (isGold) nation.copy(gold = nation.gold + amount)
        else nation.copy(rice = nation.rice + amount)

        // dest PLAIN 로그 (:212) + 몰수 발동 actor 로그 (:213).
        val josaUl = JosaUtil.pick(amountText, "을")
        context.addPlainLogTo(destGeneral.id, "$resName $amountText$josaUl 몰수 당했습니다.")
        context.addLog(
            "<Y>${context.destGeneralName}</>에게서 $resName <C>$amountText</>$josaUl 몰수했습니다. <1>${context.date}</>")
    }
}
