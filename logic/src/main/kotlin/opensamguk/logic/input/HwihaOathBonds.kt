package opensamguk.logic.input

object HwihaOathBonds {
    const val META_KEY = "hwihaOathBonds"
    fun read(meta: Map<String, Any?>): Set<Int> {
        val value = meta[META_KEY] ?: return emptySet()
        require(value is List<*>)
        val ids = value.map { (it as? Number)?.toInt()?.takeIf { id -> id > 0 }
            ?: throw IllegalArgumentException("Invalid oath bond") }
        require(ids.size == ids.toSet().size)
        return ids.toSet()
    }
    fun withBond(meta: Map<String, Any?>, id: Int): Map<String, Any?> {
        require(id > 0)
        return meta + (META_KEY to (read(meta) + id).sorted())
    }
}
