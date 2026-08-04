package opensamguk.logic.actions

data class CommandFieldSpec(
    val name: String,
    val valueType: String,
    val control: String,
    val optionSource: String? = null,
    val required: Boolean = true,
    val min: Int? = null,
    val max: Int? = null,
)

data class CommandFormSpec(
    val fields: List<CommandFieldSpec>,
) {
    companion object {
        fun fromArgsSchema(argsSchema: Map<String, Any?>): CommandFormSpec =
            CommandFormSpec(
                argsSchema.map { (name, type) ->
                    val valueType = type?.toString() ?: "unknown"
                    val (control, optionSource) = defaultControl(name, valueType)
                    CommandFieldSpec(
                        name = name,
                        valueType = valueType,
                        control = control,
                        optionSource = optionSource,
                    )
                },
            )

        private fun defaultControl(name: String, valueType: String): Pair<String, String?> = when (name) {
            "destCityID" -> "select" to "cities"
            "destNationID" -> "select" to "nations"
            "destGeneralID" -> "select" to "generals"
            "amount" -> "amount" to null
            "crewType" -> "select" to "crewTypes"
            "colorType" -> "select" to "nationColors"
            "srcArmType", "destArmType" -> "select" to "armTypes"
            "itemType" -> "select" to "itemTypes"
            "itemCode" -> "select" to "items"
            "commandType" -> "select" to "strategyCommands"
            "nationType" -> "select" to "nationTypes"
            else -> when (valueType) {
                "bool" -> "toggle" to null
                "int" -> "number" to null
                "intList" -> "amountList" to null
                else -> "text" to null
            }
        }
    }
}
