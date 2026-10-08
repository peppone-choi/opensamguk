package opensamguk.engine.campaign

import opensamguk.engine.turn.*
import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.actions.nation.DiplomacySeam
import opensamguk.logic.input.*
import opensamguk.logic.message.Message
import opensamguk.logic.message.MessageRowDraft
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.message.MessageType
import opensamguk.infra.persistence.MetaJson
import opensamguk.engine.intake.MessageHandler
import java.time.Instant

/** Queued on submission; replayed exactly once at the issuer's next turn. */
internal data class QueuedCourtAction(val requestId: String, val ownerUserId: Int, val inputId: String, val argJson: String) {
    fun toMetaValue(): Map<String, Any?> = mapOf("version" to 1, "requestId" to requestId,
        "ownerUserId" to ownerUserId, "inputId" to inputId, "argJson" to argJson)
    companion object {
        const val META_KEY = "queuedCourt"
        fun read(meta: Map<String, Any?>): QueuedCourtAction? {
            val value = meta[META_KEY] ?: return null
            val row = value as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "requestId", "ownerUserId", "inputId", "argJson") && row["version"] == 1)
            val requestId = row["requestId"] as? String ?: invalid()
            val owner = row["ownerUserId"] as? Int ?: invalid()
            val inputId = row["inputId"] as? String ?: invalid()
            val json = row["argJson"] as? String ?: invalid()
            require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && owner > 0 && inputId in CourtInput.INPUT_IDS)
            require(json.length <= 4096)
            return QueuedCourtAction(requestId, owner, inputId, json)
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("invalid queued legacy court input")
    }
}

/** One executor and one assessment for every court legacy mode. */
internal class CourtActionExecutor(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder,
    private val context: DomesticContext, private val now: () -> Instant = Instant::now) {
    fun assess(actorId: Int, inputId: String, json: String) =
        CourtRules.assess(actorId, inputId, json, context.projection(world))

    fun execute(actorId: Int, inputId: String, json: String): CourtAssessment.Rejected? {
        val result = assess(actorId, inputId, json)
        if (result is CourtAssessment.Rejected) return result
        val ready = (result as CourtAssessment.Eligible).ready
        val nation = world.getNationById(ready.nation.id) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
        when (inputId) {
            CourtExpansionInput.RELEASE_CORPS -> {
                val corps = ready.corps!!
                val owner = world.getGeneralById(corps.ownerGeneralId) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
                val deployment = DeploymentState.read(owner.meta) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
                val remaining = deployment.corps.filterNot { it.orderId == corps.orderId }
                world.updateGeneralMeta(recorder, owner, owner.meta.withKey(DeploymentState.META_KEY,
                    remaining.takeIf { it.isNotEmpty() }?.let { DeploymentState(it).toMetaValue() }) +
                    ("releaseCorpsLast" to corps.orderId))
                val commander = world.getGeneralById(corps.commanderGeneralId) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
                world.updateGeneralMeta(recorder, commander,
                    (commander.meta - CorpsOrder.META_KEY - CorpsMarchState.META_KEY) +
                        ("releaseCorpsLast" to corps.orderId))
            }
            CourtExpansionInput.ABANDON_COUNTY -> {
                val county = world.getCityById(ready.county!!.id) ?: return reject(CourtFailure.COUNTY_UNAVAILABLE)
                val next = county.copy(nationId = 0)
                recorder.diffCity(PerTurnOverlay.toLogicCity(county), PerTurnOverlay.toLogicCity(next))
                world.applyCityDirtyFree(next)
            }
            CourtExpansionInput.MOVE_CAPITAL -> {
                val next = nation.copy(capitalCityId = ready.county!!.id)
                recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(next))
                world.applyNationDirtyFree(next)
            }
            CourtInput.INSTITUTION -> {
                val stock = ready.sourceStock!!.debit(with(TransferRules) { TransferResource.MONEY.amount(100) })!!
                val next = nation.copy(gold = PortableStock.checkedColumn(stock.money), tech = nation.tech + 10.0,
                    meta = PortableStock.withStock(nation.meta, stock))
                recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(next))
                world.applyNationDirtyFree(next)
            }
            in CourtResourceInput.INPUT_IDS -> {
                val request = CourtResourceInput.parse(actorId, inputId, json)!!
                val delta = with(TransferRules) { request.resource.amount(request.amount.toLong()) }
                val source = ready.sourceStock!!.debit(delta)!!
                val destination = ready.destinationStock!!.credit(delta)
                if (inputId == CourtResourceInput.CONFISCATE) {
                    val person = world.getGeneralById(ready.targetPerson!!.id) ?: return reject(CourtFailure.TARGET_UNAVAILABLE)
                    updatePerson(person, source)
                    updateNation(nation, destination)
                } else {
                    updateNation(nation, source)
                    val target = world.getNationById(ready.targetNation!!.id) ?: return reject(CourtFailure.TARGET_UNAVAILABLE)
                    updateNation(target, destination)
                }
            }
            DiplomacyInput.OFFER_PEACE -> {
                val target = world.getNationById(ready.targetNation!!.id)
                    ?: return reject(CourtFailure.TARGET_UNAVAILABLE)
                val actor = world.getGeneralById(actorId) ?: return reject(CourtFailure.ACTOR_NOT_FOUND)
                val state = world.getState()
                // Validity is wall time; explicit UTC offsets survive PostgreSQL timestamptz casts.
                val sentAt = now()
                val message = Message(
                    MessageType.DIPLOMACY,
                    MessageTarget(actor.id, actor.name, nation.id, nation.name, nation.color,
                        actor.meta["icon"] as? String ?: ""),
                    MessageTarget(0, "", target.id, target.name, target.color),
                    "${nation.name}의 종전 제의 서신",
                    MessageHandler.formatPhpDate(sentAt),
                    MessageHandler.formatPhpDate(sentAt.plusSeconds(
                        DiplomacySeam.validMinutes(state.tickSeconds / 60).toLong() * 60)),
                    linkedMapOf("action" to "stop_war", "deletable" to false),
                )
                var receiverId: Int? = null
                for (draft in message.send()) {
                    val option = draft.option?.let { LinkedHashMap<String, Any?>(it) }
                    if (option != null && receiverId != null) option["receiverMessageID"] = receiverId
                    val id = recorder.recordMessageInsert(
                        mailbox = draft.mailbox, type = draft.type.value,
                        srcId = draft.srcId, destId = draft.destId,
                        time = message.date, validUntil = message.validUntil,
                        bodyJson = MetaJson.encode(linkedMapOf(
                            "src" to draft.src.toArray(), "dest" to draft.dest.toArray(),
                            "text" to draft.text, "option" to option,
                        )),
                    )
                    if (draft.whichRow == MessageRowDraft.Row.RECEIVER) receiverId = id
                }
            }
            in DiplomacyInput.INPUT_IDS -> {
                val other = ready.targetNation!!.id
                val forward = world.getDiplomacy(nation.id, other) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
                val backward = world.getDiplomacy(other, nation.id) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
                val nextState = when (inputId) {
                    DiplomacyInput.NON_AGGRESSION -> DiplomacyState.NON_AGGRESSION
                    DiplomacyInput.DECLARE_WAR -> DiplomacyState.DECLARATION
                    DiplomacyInput.BREAK_NON_AGGRESSION -> DiplomacyState.WAR
                    else -> return reject(CourtFailure.INVALID_INPUT)
                }
                for (pre in listOf(forward, backward)) {
                    val next = world.updateDiplomacy(pre.fromNationId, pre.toNationId, nextState,
                        when (nextState) {
                            DiplomacyState.NON_AGGRESSION -> DiplomacyConst.MIN_NON_AGGRESSION_MONTHS
                            DiplomacyState.DECLARATION -> DiplomacyConst.DEFAULT_DECLARE_WAR_TERM
                            DiplomacyState.WAR -> DiplomacyConst.DEFAULT_WAR_TERM
                            else -> 0
                        }) ?: return reject(CourtFailure.STATE_UNAVAILABLE)
                    recorder.diffDiplomacy(pre, next)
                }
            }
            else -> return reject(CourtFailure.INVALID_INPUT)
        }
        Records.general(world, actorId, RecordKind.PERSONAL_APPLIED,
            if (inputId == DiplomacyInput.OFFER_PEACE) "${ready.actor.name}의 종전 제의 서신을 보냈습니다."
            else "${ready.actor.name}의 조정 결정을 실행했습니다.", mapOf("inputId" to inputId))
        return null
    }

    private fun updatePerson(before: TurnGeneral, stock: opensamguk.logic.economy.Resources) {
        val next = before.copy(gold = PortableStock.checkedColumn(stock.money),
            rice = PortableStock.checkedColumn(stock.grain), meta = PortableStock.withStock(before.meta, stock))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
    }
    private fun updateNation(before: Nation, stock: opensamguk.logic.economy.Resources) {
        val next = before.copy(gold = PortableStock.checkedColumn(stock.money),
            rice = PortableStock.checkedColumn(stock.grain), meta = PortableStock.withStock(before.meta, stock))
        recorder.diffNation(PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(next))
        world.applyNationDirtyFree(next)
    }
    private fun reject(reason: CourtFailure) = CourtAssessment.Rejected(reason)
}
