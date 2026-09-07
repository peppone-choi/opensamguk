package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value

/**
 * 郡 내부 보급선을 읽는다(ADR-LITE-051).
 *
 * `han-world-v3` 의 보급은 프로빈스 소유 격자에서 물리적으로 맞닿은 프로빈스끼리만 흐른다.
 * 그 격자에서 같은 郡의 프로빈스가 조각으로 끊긴 곳이 있고, 그래서 프로덕션에서 공융의
 * 北海國 16城 중 14城이 개시 시점부터 수도에 닿지 않았다.
 *
 * 郡 은 후한의 행정·병참 단위이므로 **보급**은 행정선을 따라서도 흐른다고 본다. 이 파일이
 * 읽는 간선은 `SpatialSupplyNetwork.provinceAdjacency`(보급 전용)에만 더해지고,
 * **이동은 바뀌지 않는다** — 전략 위상의 LAND 간선은 그대로다.
 *
 * 산출물은 `tools/map/build_commandery_supply_links.py` 가 굽고 `--check` 로 드리프트를 막는다.
 * 좌표가 틀린 동명이지 縣은 그 도구가 판정 장부를 보고 제외한다 — 안 그러면 郡을 가로지르는
 * 가짜 보급선이 생긴다(제외 전 최대 1,190km, 제외 뒤 중앙값 47km).
 */
class CommanderySupplyLinkLoader(
    private val objectMapper: ObjectMapper,
    @Value("\${HAN_COMMANDERY_SUPPLY_LINKS_FILE:data/map/han-commandery-supply-links-v1.json}")
    private val linksPath: String,
) {
    @Volatile
    private var cached: List<Pair<Int, Int>>? = null

    fun load(): List<Pair<Int, Int>> = cached ?: synchronized(this) {
        cached ?: read().also { cached = it }
    }

    private fun read(): List<Pair<Int, Int>> {
        val path = Path.of(linksPath)
        // 파일이 없으면 링크 없음으로 둔다 — 보급이 오늘과 같아질 뿐, 부팅을 막지 않는다.
        if (!Files.isRegularFile(path)) return emptyList()
        val root = objectMapper.readTree(Files.readAllBytes(path))
        val links = root.get("links") ?: return emptyList()
        require(links.isArray) { "han-commandery-supply-links links must be an array" }
        return links.map { row ->
            val from = row.get("fromProvinceIndex")
            val to = row.get("toProvinceIndex")
            require(from != null && from.isInt && to != null && to.isInt) {
                "han-commandery-supply-links row needs integer province indices"
            }
            require(from.asInt() != to.asInt()) {
                "han-commandery-supply-links row links a province to itself"
            }
            from.asInt() to to.asInt()
        }
    }
}
