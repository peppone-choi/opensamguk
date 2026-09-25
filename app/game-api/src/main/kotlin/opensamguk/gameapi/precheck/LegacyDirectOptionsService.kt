package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaLegacyDirectChoice(val label: String, val arguments: Map<String, Any>,
    val available: Boolean, val code: String? = null, val reason: String? = null,
    val maxAmount: Int? = null)
data class HwihaLegacyDirectOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val choices: List<HwihaLegacyDirectChoice> = emptyList())

@Service
class HwihaLegacyDirectOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long, inputId: String): HwihaLegacyDirectOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in DirectInput.INPUT_IDS || catalog[inputId]?.deliveryState?.hasHandler != true)
            return HwihaLegacyDirectOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return HwihaLegacyDirectOptions(inputId, false,
            DirectFailure.STATE_UNAVAILABLE.name, DirectFailure.STATE_UNAVAILABLE.message)
        val actor = state.person(actorId) ?: return HwihaLegacyDirectOptions(inputId, false,
            DirectFailure.ACTOR_NOT_FOUND.name, DirectFailure.ACTOR_NOT_FOUND.message)
        val requests: List<Pair<String, DirectRequest>> = when (inputId) {
            DirectInput.CONVERT -> state.bugoks.filter { it.masterGeneralId == actorId }.flatMap { unit ->
                state.supportedCrewTypeIds.sorted().map { crew ->
                    "${unit.id}번 부곡 → ${crew}번 병종" to DirectRequest.Convert(actorId, unit.id, crew)
                }
            }
            DirectInput.EQUIPMENT -> ItemCatalogJson.CANON.treasures
                .filter { it.issuedCopies != null }.sortedBy { it.sourceRowIndex }.flatMap { card ->
                    TradeSide.entries.map { side ->
                        "${card.header.name} ${if (side == TradeSide.BUY) "매입" else "매각"} · 전 ${card.purchaseCost}" to
                            DirectRequest.Equipment(actorId, card.sourceRowIndex, side)
                    }
                }
            DirectInput.GRAIN -> TradeSide.entries.map { side ->
                (if (side == TradeSide.BUY) "전 100 → 곡물 300" else "곡물 300 → 전 100") to
                    DirectRequest.Grain(actorId, side, 1)
            }
            else -> state.countyAdjacency.keys.sorted().flatMap { sourceId ->
                val source = state.county(sourceId) ?: return@flatMap emptyList()
                if (source.provinceId != actor.node) return@flatMap emptyList()
                state.countyAdjacency[sourceId].orEmpty().sorted().flatMap { targetId ->
                    val target = state.county(targetId) ?: return@flatMap emptyList()
                    Cargo.entries.map { cargo ->
                        "${target.name} · ${cargo.name}" to
                            DirectRequest.Transport(actorId, targetId, cargo, 1)
                    }
                }
            }
        }
        val choices = requests.map { (label, request) ->
            val assessment = DirectRules.assess(request, state)
            val failure = (assessment as? DirectAssessment.Rejected)?.reason
            val args = when (request) {
                is DirectRequest.Convert -> mapOf("bugokId" to request.bugokId, "crewTypeId" to request.crewTypeId)
                is DirectRequest.Equipment -> mapOf("treasureId" to request.treasureId, "side" to request.side.name)
                is DirectRequest.Grain -> mapOf("side" to request.side.name, "amount" to 1)
                is DirectRequest.Transport -> mapOf("targetCountyId" to request.targetCountyId,
                    "cargo" to request.cargo.name, "amount" to 1)
            }
            val maxAmount = if (request is DirectRequest.Transport && failure == null) {
                val ready = assessment as DirectAssessment.Eligible
                val stock = checkNotNull(ready.warehouse).stock
                minOf(1000L, when (request.cargo) {
                    Cargo.MONEY -> stock.money; Cargo.GRAIN -> stock.grain
                    Cargo.IRON -> stock.iron; Cargo.TIMBER -> stock.timber; Cargo.HORSES -> stock.horses
                }).toInt()
            } else null
            HwihaLegacyDirectChoice(label, args, failure == null, failure?.name, failure?.message, maxAmount)
        }
        val first = choices.firstOrNull { it.available }
        val failure = if (first == null) choices.firstOrNull()?.let { it.code to it.reason }
            ?: (DirectFailure.STATE_UNAVAILABLE.name to DirectFailure.STATE_UNAVAILABLE.message)
            else null
        return HwihaLegacyDirectOptions(inputId, first != null, failure?.first, failure?.second, choices)
    }
}
