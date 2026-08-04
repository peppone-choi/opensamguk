package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.logic.actions.CommandFormSpec
import opensamguk.logic.actions.GeneralActionDefinition

data class CommandCatalogRow(
    val value: String,
    val simpleName: String,
    val title: String,
    val category: String,
    val compensation: Int,
    val possible: Boolean,
    val reqArg: Boolean,
    val argType: String? = null,
    val form: CommandFormSpec? = null,
    val reason: String? = null,
)

object CommandCatalogRowFactory {
    fun create(
        definition: GeneralActionDefinition,
        result: PrecheckResult?,
        category: String,
    ): CommandCatalogRow {
        val form = definition.formSpec.takeIf { it.fields.isNotEmpty() }
        val (possible, reason) = when (result) {
            null -> true to null
            PrecheckResult.Available -> true to null
            is PrecheckResult.Blocked -> false to result.reason
            is PrecheckResult.Unknown -> if (form != null) true to null else false to UNKNOWN_REASON
        }
        return CommandCatalogRow(
            value = definition.key,
            simpleName = definition.name,
            title = definition.name,
            category = category,
            compensation = 0,
            possible = possible,
            reqArg = form != null,
            argType = legacyArgType(form),
            form = form,
            reason = reason,
        )
    }

    private fun legacyArgType(form: CommandFormSpec?): String? {
        val fields = form?.fields ?: return null
        val names = fields.map { it.name }
        if (names.containsAll(listOf("nationName", "nationType", "colorType"))) return "founding"
        if (names.containsAll(listOf("crewType", "amount"))) return "recruit"
        if (fields.size != 1) return null
        return when (fields.single().optionSource) {
            "cities" -> "city"
            "nations" -> "nation"
            "generals" -> "general"
            else -> if (fields.single().control == "amount") "amount" else null
        }
    }

    private const val UNKNOWN_REASON = "정보 부족"
}
