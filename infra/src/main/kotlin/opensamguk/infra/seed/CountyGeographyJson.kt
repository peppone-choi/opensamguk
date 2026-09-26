package opensamguk.infra.seed

import java.util.concurrent.ConcurrentHashMap
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.input.CountyGeography
import opensamguk.logic.input.CountyPlace
import opensamguk.logic.world.WorldMapVariant

/**
 * 부팅이 고른 판의 고정 번들에서 縣治 城 → 郡(런타임 지도 `meta.junCh`/`meta.jun`)·관할(`han-tiles provinceRecords[provinceId]
 * .jurisdictionId`)을 푼다. 판마다 한 번만 만든다. 행정 縣이 아닌 城과 `junCh` 가 없는 城은 싣지 않는다.
 */
object CountyGeographyJson {
    const val RUNTIME_MAP = "infra/src/main/resources/map/han-world-v3.json"
    const val TILES = "data/map/han-tiles.json"
    private val cache = ConcurrentHashMap<WorldMapVariant, CountyGeography>()

    fun load(artifacts: ResolvedWorldArtifacts): CountyGeography = cache.computeIfAbsent(artifacts.variant) {
        parse(artifacts.artifactBytes(RUNTIME_MAP), artifacts.artifactBytes(TILES), artifacts.projection.administrativeCountyIds)
    }

    fun parse(runtimeMap: ByteArray, tiles: ByteArray, administrativeCountyIds: Set<Int>): CountyGeography {
        val cities = MetaJson.decode(runtimeMap.toString(Charsets.UTF_8))["cities"] as? List<*>
            ?: error("runtime map cities missing")
        val provinces = MetaJson.decode(tiles.toString(Charsets.UTF_8))["provinceRecords"] as? List<*>
            ?: error("han-tiles provinceRecords missing")
        val places = cities.mapNotNull { raw ->
            val city = raw as? Map<*, *> ?: error("runtime map city is not an object")
            val id = (city["id"] as? Number)?.toInt() ?: error("runtime map city id missing")
            if (id !in administrativeCountyIds) return@mapNotNull null
            val meta = city["meta"] as? Map<*, *> ?: return@mapNotNull null
            val commandery = (meta["junCh"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val provinceIndex = (city["provinceId"] as? Number)?.toInt()
            val jurisdiction = provinceIndex?.takeIf { it in provinces.indices }
                ?.let { (provinces[it] as? Map<*, *>)?.get("jurisdictionId") as? String }?.takeIf { it.isNotBlank() }
            CountyPlace(id, commandery, (meta["jun"] as? String)?.takeIf { it.isNotBlank() }, jurisdiction)
        }
        val provinceIdsByJurisdiction = provinces.mapNotNull { raw ->
            val province = raw as? Map<*, *> ?: error("han-tiles province is not an object")
            val jurisdictionId = province["jurisdictionId"] as? String ?: return@mapNotNull null
            val provinceId = province["id"] as? String ?: error("han-tiles province id missing")
            jurisdictionId to provinceId
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return CountyGeography(places, provinceIdsByJurisdiction)
    }
}
