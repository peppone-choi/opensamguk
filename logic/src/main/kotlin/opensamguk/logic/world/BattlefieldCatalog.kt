package opensamguk.logic.world

import java.util.Collections

enum class BattlefieldRole { FIELD, NAVAL, FORTRESS, PASS }

/** A reviewed deployment binding. Null node and ingress explicitly withhold activation. */
data class BattlefieldCatalogEntry(
    val id: String,
    val name: String,
    val node: StrategicNodeRef?,
    val ingressCityId: Int?,
    val role: BattlefieldRole,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]*")))
        require(name.isNotBlank())
        require((node == null) == (ingressCityId == null))
        require(ingressCityId == null || ingressCityId > 0)
        require(node == null || when (role) {
            BattlefieldRole.NAVAL -> node is StrategicNodeRef.WaterZone
            else -> node is StrategicNodeRef.LandProvince
        }) { "Battlefield role does not match physical node" }
    }
}

class BattlefieldCatalog(val contentHash: String, entries: List<BattlefieldCatalogEntry>) {
    val entries: Map<String, BattlefieldCatalogEntry>
    init {
        require(contentHash.matches(Regex("[0-9a-f]{64}")))
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate battlefield identity" }
        this.entries = Collections.unmodifiableMap(entries.sortedBy { it.id }.associateBy { it.id })
    }
}
