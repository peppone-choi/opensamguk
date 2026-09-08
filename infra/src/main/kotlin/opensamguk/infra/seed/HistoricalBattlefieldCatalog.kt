package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.logic.world.*
import java.security.MessageDigest
import java.util.Collections

/** Reviewed playable bindings, shared by the daemon and read API. */
object HistoricalBattlefieldCatalog {
    data class Site(val id: String, val name: String, val latitude: Double, val longitude: Double,
                    val confidence: String, val sourceId: String, val sourceLine: Int)
    private val mapper = ObjectMapper()
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing battlefield resource $path"
    }.use { it.readBytes() }
    private val bytes by lazy { resource("map/han-world-v3-battlefields.json") }
    private val document by lazy { mapper.readTree(bytes) }
    private val runtimeCities by lazy { mapper.readTree(resource("map/han-world-v3.json")).get("cities") }
    private val anchors by lazy {
        val rows = runtimeCities
        val result = linkedMapOf<Int, StrategicNodeRef>()
        rows.forEach { row ->
            val id = row.get("id").intValue()
            val province = row.get("spatialProvinceId")?.textValue()
            if (province == null) {
                require(row.path("physicalPlaceRef").asText().startsWith("external:v1:")) {
                    "Runtime city $id has no physical binding"
                }
                return@forEach // External enclaves have no authoritative land-province node.
            }
            require(row.get("physicalPlaceRef").textValue() == "chgis:v6:cnty:$province") {
                "Runtime city $id has inconsistent physical binding"
            }
            require(result.put(id, StrategicNodeRef.LandProvince(province)) == null) { "Duplicate runtime city" }
        }
        Collections.unmodifiableMap(result)
    }
    private val catalog by lazy {
        require(document.get("schemaVersion").intValue() == 1)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        BattlefieldCatalog(hash, document.get("sites").map { row ->
            val node = StrategicNodeRef.LandProvince(row.get("provinceId").textValue())
            val city = row.get("ingressCityId").intValue()
            require(anchors[city] == node) { "Battlefield ingress does not match runtime geography" }
            BattlefieldCatalogEntry(row.get("id").textValue(), row.get("name").textValue(), node, city,
                BattlefieldRole.valueOf(row.get("role").textValue()))
        })
    }
    fun load(): BattlefieldCatalog = catalog
    fun cityAnchors(): Map<Int, StrategicNodeRef> = anchors
    fun cityName(cityId: Int): String = requireNotNull(runtimeCities.firstOrNull { it.get("id").intValue() == cityId })
        .get("name").textValue()
    fun sites(): List<Site> = document.get("sites").map { row ->
        Site(row.get("id").textValue(), row.get("name").textValue(), row.get("latitude").doubleValue(),
            row.get("longitude").doubleValue(), row.get("confidence").textValue(),
            row.get("sourceId").textValue(), row.get("sourceLine").intValue())
    }
    fun validatePresence(node: StrategicNodeRef, presence: BattlefieldPresence) {
        val entry = load().entries[presence.siteId]
        require(entry != null && entry.node == node && entry.ingressCityId == presence.returnCityId &&
            presence.catalogHash == load().contentHash) { "Battlefield presence does not match the active catalog" }
    }
}
