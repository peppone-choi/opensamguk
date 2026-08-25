package opensamguk.gameapi.controller

import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/map/terrain` — 후한 군현 지형 격자(256×256)를 그대로 흘려보낸다.
 *
 * **왜 정적 리소스가 아니라 런타임 파일인가.** 이 격자는 CHGIS 파생물이고, ADR-LITE-039 는
 * CHGIS 파생물을 **저장소 번들·CDN·배포 이미지·런타임 allowlist 에 올리는 것을 금지**한다.
 * 그래서 jar 에 넣지 않고, 운영자가 읽기 전용으로 마운트한 경로를 읽는다 — F1 `SCENARIO_DIR`
 * 선례와 같은 형태다(`docker-compose.yml:106`). 파일이 없으면 **404** 이고 500 이 아니다.
 * 프런트는 404 를 보고 준비/오류 상태를 보인다. 다른 맵으로 바꾸지 않는다.
 *
 * **파싱하지 않는다.** `tools/map/build_tile_grid.py` 가 이미 굽고 불변식까지 검사한 blob 이라
 * 여기서 DTO 로 되돌렸다가 다시 직렬화할 이유가 없다. 바이트를 그대로 준다.
 *
 * **캐시.** 격자는 턴마다 바뀌지 않는다(소유 색칠은 `/api/map/preview` 가 라이브로 준다).
 * 크기·수정시각으로 만든 ETag 로 재방문을 304 로 끊는다 — 0.3MB 를 매번 다시 보내지 않는다.
 */
@RestController
@RequestMapping("/api/map")
class TerrainMapController(
    @Value("\${HAN_MAP_FILE:data/map/han-tiles.json}") private val mapFile: String,
) {

    @GetMapping("/terrain")
    fun terrain(
        @RequestParam(defaultValue = "han") mapCode: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> {
        if (!MAP_CODE.matches(mapCode)) return ResponseEntity.notFound().build()
        val configured = Path.of(mapFile)
        val path: Path = if (mapCode == "han") configured else configured.resolveSibling("$mapCode-tiles.json")
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build()

        val tag = "\"${Files.size(path)}-${Files.getLastModifiedTime(path).toMillis()}\""
        if (ifNoneMatch == tag) {
            return ResponseEntity.status(304).eTag(tag).build()
        }
        return ResponseEntity.ok()
            .eTag(tag)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Files.readAllBytes(path))
    }

    private companion object {
        val MAP_CODE = Regex("[a-z0-9_]+")
    }
}
