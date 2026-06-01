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
import java.time.format.DateTimeFormatter

/**
 * che_불가침파기제의 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기제의.php`.
 *
 * The non-aggression CANCELLATION PROPOSAL command (paired with the [CheBulgachimPagiSuak]
 * acceptance). It sends a diplomacy [Message] (action=cancel_na) to the dest nation; the diplomacy
 * state reverts to TRADE only when the recipient accepts. Mirrors the [CheBulgachimJeui] pattern.
 *
 * argTest (che_불가침파기제의.php:30-50): require `destNationID` (int, >= 1) → `{destNationID}`.
 *
 * Constraints (PHP ORDER, first-deny-wins):
 *  - min (che_불가침파기제의.php:62-67): `[BeChief, NotBeNeutral, OccupiedCity, SuppliedCity]`.
 *  - full (che_불가침파기제의.php:74-84): `+ [ExistsDestNation, AllowDiplomacyBetweenStatus([7],
 *    '불가침 중인 상대국에게만 가능합니다.')]` — only NON_AGGRESSION(7) nations are cancellation targets.
 *
 * getPreReqTurn = getPostReqTurn = 0, getCost = [0,0].
 */
fun cheBulgachimPagijeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimPagijeui =
    CheBulgachimPagijeui(pipeline)

class CheBulgachimPagijeui(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침파기제의"
    override val name: String get() = "불가침 파기 제의"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("destNationID" to "int")

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), suppliedCity(),
        existsDestNation(),
        allowDiplomacyBetweenStatus(listOf(7), "불가침 중인 상대국에게만 가능합니다."),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = run {
        val destNationID = (raw["destNationID"] as? Int) ?: return@run emptyMap()
        if (destNationID < 1) return@run emptyMap()
        linkedMapOf("destNationID" to destNationID)
    }

    /**
     * che_불가침파기제의.php:107-175 run(). Pushes the action log + sends a cancelNA diplomacy [Message].
     * The engine routes the buffered message to the mailbox channel (receiver-before-sender).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val destNationID = (context.args["destNationID"] as? Int) ?: return
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }
        val josaRo = JosaUtil.pick(destNationName, "로")
        context.addLog("<D><b>$destNationName</b></>$josaRo 불가침 파기 제의 서신을 보냈습니다.<1>${context.date}</>")

        val draft = context.draft
        val general = draft.general
        val nation = draft.nation ?: return

        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val validMinutes = maxOf(30, 60 * 3) // default turnterm=60; quarantined wall-clock
        val validUntilStr = now.plusMinutes(validMinutes.toLong()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

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
            msg = "${nation.name}의 불가침 파기 제의 서신",
            date = dateStr,
            validUntil = validUntilStr,
            msgOption = linkedMapOf(
                "action" to "cancel_na",
                "deletable" to false,
            ),
        )
        context.sendMessage(msg)
    }
}
