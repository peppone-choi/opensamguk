package opensamguk.gameapi.precheck

import opensamguk.logic.domestic.DomesticRules

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaLegacyCourtChoice(val label: String, val arguments: Map<String, Any>,
    val available: Boolean, val code: String? = null, val reason: String? = null,
    val maxAmount: Long? = null)
data class HwihaLegacyCourtOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val choices: List<HwihaLegacyCourtChoice> = emptyList())

@Service
class HwihaLegacyCourtOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long, inputId: String): HwihaLegacyCourtOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in CourtInput.INPUT_IDS || catalog[inputId]?.deliveryState?.hasHandler != true)
            return HwihaLegacyCourtOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return HwihaLegacyCourtOptions(inputId, false,
            CourtFailure.STATE_UNAVAILABLE.name, CourtFailure.STATE_UNAVAILABLE.message)
        val actor = state.person(actorId) ?: return HwihaLegacyCourtOptions(inputId, false,
            CourtFailure.ACTOR_NOT_FOUND.name, CourtFailure.ACTOR_NOT_FOUND.message)
        val ownedCounties = state.counties.filter { it.nationId == actor.nationId }.sortedBy { it.id }
        val others = state.nations.filter { it.id != actor.nationId }.sortedBy { it.id }
        val requests: List<Pair<String, Map<String, Any>>> = when (inputId) {
            CourtExpansionInput.RELEASE_CORPS -> DomesticRules.deployedCorps(state)
                .filter { it.ownerGeneralId == actorId }.map { corps ->
                    "${state.person(corps.commanderGeneralId)?.name ?: corps.commanderGeneralId}의 군단" to
                        mapOf("targetGeneralId" to corps.commanderGeneralId)
                }
            CourtExpansionInput.ABANDON_COUNTY, CourtExpansionInput.MOVE_CAPITAL ->
                ownedCounties.map { "${it.name} (${it.id})" to mapOf("countyId" to it.id) }
            CourtInput.INSTITUTION -> listOf("기술 연구 · 전 100" to emptyMap())
            CourtResourceInput.CONFISCATE -> state.cards.filter { it.masterId == actorId }
                .mapNotNull { it.generalId?.let(state::person) }.distinctBy { it.id }.sortedBy { it.id }.flatMap { person ->
                    TransferResource.entries.map { resource ->
                        "${person.name} · ${resource.name}" to mapOf("targetGeneralId" to person.id,
                            "resource" to resource.name, "amount" to 1)
                    }
                }
            CourtResourceInput.AID -> others.flatMap { nation -> TransferResource.entries.map { resource ->
                "${nation.name} · ${resource.name}" to mapOf("targetNationId" to nation.id,
                    "resource" to resource.name, "amount" to 1)
            } }
            in DiplomacyInput.INPUT_IDS -> others.map { "${it.name} (${it.id})" to mapOf("targetNationId" to it.id) }
            else -> emptyList()
        }
        val choices = requests.map { (label, args) ->
            val json = buildJsonObject { args.forEach { (key, value) ->
                when (value) { is Int -> put(key, value); is String -> put(key, value); else -> error("unsupported argument") }
            } }.toString()
            val result = CourtRules.assess(actorId, inputId, json, state)
            val failure = (result as? CourtAssessment.Rejected)?.reason
            val maxAmount = (result as? CourtAssessment.Eligible)?.ready?.sourceStock?.let { stock ->
                when (args["resource"]) {
                    "MONEY" -> stock.money; "GRAIN" -> stock.grain; "IRON" -> stock.iron
                    "TIMBER" -> stock.timber; "HORSES" -> stock.horses; else -> null
                }
            }
            HwihaLegacyCourtChoice(label, args, failure == null, failure?.name, failure?.message, maxAmount)
        }
        val available = choices.any { it.available }
        val reason = if (available) null else choices.firstOrNull()?.let { it.code to it.reason }
            ?: (CourtFailure.TARGET_UNAVAILABLE.name to CourtFailure.TARGET_UNAVAILABLE.message)
        return HwihaLegacyCourtOptions(inputId, available, reason?.first, reason?.second, choices)
    }
}
