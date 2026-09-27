package opensamguk.logic.input

/** Actor-private identities of existing free people met through a local talent search. */
object TalentDiscovery {
    const val META_KEY = "talentDiscovery"

    fun read(meta: Map<String, Any?>): Set<Int> {
        if (META_KEY !in meta) return emptySet()
        val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
        require(raw.keys == setOf("version", "generalIds") && raw["version"] == 1)
        val ids = (raw["generalIds"] as? List<*>)?.map { it as? Int ?: invalid() } ?: invalid()
        require(ids.all { it > 0 } && ids == ids.distinct().sorted())
        return ids.toSet()
    }

    fun add(meta: Map<String, Any?>, generalId: Int): Map<String, Any?> {
        require(generalId > 0)
        val ids = (read(meta) + generalId).sorted()
        return meta + (META_KEY to linkedMapOf("version" to 1, "generalIds" to ids))
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid talent discovery state")
}
