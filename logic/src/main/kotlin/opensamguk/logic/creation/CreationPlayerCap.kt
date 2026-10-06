package opensamguk.logic.creation

/** The creation paths must read one exact positive integer from the world config. */
object CreationPlayerCap {
    private val positiveInteger = Regex("[1-9][0-9]*")

    fun maxGeneral(config: Map<String, Any?>): Int? {
        val value = config["maxgeneral"] as? Number ?: return null
        val decimal = value.toString()
        if (!positiveInteger.matches(decimal)) return null
        return decimal.toIntOrNull()
    }
}
