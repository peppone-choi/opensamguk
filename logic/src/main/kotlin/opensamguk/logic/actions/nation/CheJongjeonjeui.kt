package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.message.Message
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.message.MessageType
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.LocalDateTime

/**
 * che_종전제의 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전제의.php`.
 *
 * The cease-fire PROPOSAL command (paired with the [CheJongjeonSuak] acceptance). It sends a
 * diplomacy [Message] (action=stop_war) to the dest nation; the diplomacy state mutation happens only
 * when the recipient accepts. Mirrors the [CheBulgachimJeui] proposal pattern.
 *
 * argTest (che_종전제의.php:30-50): require `destNationID` (int, >= 1) → canonical `{destNationID}`.
 * The dest nation need NOT exist at argTest (`멸망 직전에 턴을 넣을 수 있으므로`).
 *
 * Constraints (PHP ORDER, first-deny-wins):
 *  - min (che_종전제의.php:61-66): `[BeChief, NotBeNeutral, OccupiedCity, SuppliedCity]`.
 *  - full (che_종전제의.php:73-83): `+ [ExistsDestNation, AllowDiplomacyBetweenStatus([0,1],
 *    '선포, 전쟁중인 상대국에게만 가능합니다.')]` — only DECLARED(1)/WAR(0) nations are cease-fire targets.
 *
 * getPreReqTurn = getPostReqTurn = 0, getCost = [0,0].
 */
fun cheJongjeonjeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheJongjeonjeui =
    CheJongjeonjeui(pipeline)

class CheJongjeonjeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_종전제의"
    override val name: String get() = "종전 제의"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
        existsDestNation(),
        allowDiplomacyBetweenStatus(listOf(0, 1), "선포, 전쟁중인 상대국에게만 가능합니다."),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = run {
        val destNationID = (raw["destNationID"] as? Int) ?: return@run emptyMap()
        if (destNationID < 1) return@run emptyMap()
        linkedMapOf("destNationID" to destNationID)
    }

    /**
     * che_종전제의.php:105-173 run(). draw COUNT = 0 — no RNG. Pushes the action log + sends a
     * stopWar diplomacy [Message]. The engine routes the buffered message to the국가 메일함
     * (9000+destNationID, receiver-before-sender) via [GeneralActionResolveContext.sendMessage].
     *
     * Resolve 순서(PHP run 본체 그대로):
     *  1. 장수 액션 로그 `<D><b>{상대국}</b></>{로} 종전 제의 서신을 보냈습니다.<1>{date}</>`
     *     (che_종전제의.php:129, `josaRo = JosaUtil::pick($destNationName, '로')`).
     *  2. DiplomaticMessage(action=stop_war, deletable=false) buffer — title `{국명}의 종전 제의 서신`
     *     (che_종전제의.php:157), validUntil = date + max(30, turnterm*3)분([DiplomacySeam] 단일 공식).
     *  3. setResultTurn(LastTurn)은 엔진(ReservedTurnHandler)이 소유. 그 뒤 StaticEventHandler 외교
     *     훅(che_종전제의.php:168) — scenario 1010 핸들러 맵이 비어 no-op(게이트 동일).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val destNationID = (context.args["destNationID"] as? Int) ?: return
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }
        val josaRo = JosaUtil.pick(destNationName, "로")
        context.addLog("<D><b>$destNationName</b></>$josaRo 종전 제의 서신을 보냈습니다.<1>${context.date}</>")

        val draft = context.draft
        val general = draft.general
        val nation = draft.nation ?: return

        // validUntil = date + max(30, turnterm*3)분 — A2 공통 인프라([DiplomacySeam]) 단일 공식
        // (PHP che_종전제의.php:149-151). turnterm은 엔진이 per-game 주입(기본 60).
        val now = LocalDateTime.now()
        val dateStr = now.format(DiplomacySeam.YMDHIS)
        val validUntilStr = DiplomacySeam.validUntil(now, context.turnterm)

        val src = MessageTarget(
            generalId = general.id,
            generalName = context.generalName,
            nationId = nation.id,
            nationName = nation.name,
            color = nation.color,
            icon = "",
        )
        val dest = MessageTarget(
            generalId = 0,
            generalName = "",
            nationId = destNationID,
            nationName = destNationName,
            color = "#000000",
            icon = "",
        )

        val msg = Message(
            msgType = MessageType.DIPLOMACY,
            src = src,
            dest = dest,
            msg = "${nation.name}의 종전 제의 서신",
            date = dateStr,
            validUntil = validUntilStr,
            msgOption = linkedMapOf(
                "action" to "stop_war",
                "deletable" to false,
            ),
        )
        context.sendMessage(msg)

        // StaticEventHandler 외교 훅 (A2 공통 인프라) — PHP run() 말미
        // `StaticEventHandler::handleEvent($general, $destGeneral, $this::class, $env, $arg)`
        // (che_종전제의.php:168). scenario 1010에서 핸들러 맵이 비어 no-op(게이트 동일). 제의는 상대
        // 장수 객체를 들지 않으므로 destGeneral=null(PHP destGeneralObj가 null).
        DiplomacySeam.fireDiplomacyEvent(
            general = general,
            destGeneral = null,
            commandKey = key,
            env = linkedMapOf("year" to context.env.year, "month" to context.month, "turnterm" to context.turnterm),
            args = context.args,
        )
    }
}
