package opensamguk.infra.v2

import java.security.MessageDigest
import opensamguk.infra.seed.ScenarioCity
import opensamguk.infra.seed.ScenarioJson
import org.springframework.core.io.ClassPathResource

class V2CityCatalogAdapter(
    private val catalog: V2ContentCatalog = V2ContentCatalog(),
) {

    fun load(): V2CityCatalogSnapshot {
        val metadata = catalog.load(CITY_CONTENT_ID)
        val sourceBytes = ClassPathResource(metadata.source).inputStream.use { it.readBytes() }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(sourceBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actualSha256 == metadata.sha256) {
            "v2 city source sha256 does not match metadata"
        }

        val cities = ScenarioJson.loadCities(sourceBytes.toString(Charsets.UTF_8))
        require(cities.size == metadata.cityCount) {
            "v2 city source count does not match metadata"
        }
        require(cities.count { it.nationId != 0 } == metadata.scenarioOwnedCityCount) {
            "v2 owned city count does not match metadata"
        }
        require(cities.map(ScenarioCity::id).toSet().size == cities.size) {
            "v2 city source contains duplicate city ids"
        }
        return V2CityCatalogSnapshot(metadata, cities)
    }

    companion object {
        const val CITY_CONTENT_ID: String = "cities_1010"
    }
}

data class V2CityCatalogSnapshot(
    val metadata: V2ContentMetadata,
    val cities: List<ScenarioCity>,
) {
    fun diff(other: V2CityCatalogSnapshot): V2CityCatalogDiff {
        val firstById = cities.associateBy(ScenarioCity::id)
        val secondById = other.cities.associateBy(ScenarioCity::id)
        return V2CityCatalogDiff(
            metadataChanged = metadata != other.metadata,
            onlyInFirst = (firstById.keys - secondById.keys).sorted(),
            onlyInSecond = (secondById.keys - firstById.keys).sorted(),
            changedCityIds = firstById.keys.intersect(secondById.keys)
                .filter { firstById[it] != secondById[it] }
                .sorted(),
        )
    }
}

data class V2CityCatalogDiff(
    val metadataChanged: Boolean,
    val onlyInFirst: List<Int>,
    val onlyInSecond: List<Int>,
    val changedCityIds: List<Int>,
) {
    val isEmpty: Boolean
        get() = !metadataChanged && onlyInFirst.isEmpty() && onlyInSecond.isEmpty() && changedCityIds.isEmpty()
}
