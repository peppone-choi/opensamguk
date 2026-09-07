package opensamguk.engine.world

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * ADR-LITE-051 — 郡 내부 보급선.
 *
 * 이 간선이 없으면 프로덕션에서 공융의 北海國 16城 중 14城이 개시 시점부터 수도에 닿지 않는다.
 * 산출물이 비거나 로더가 빈 목록을 내면 그 상태로 되돌아가므로, 아래 단언들이 그 되돌림을 잡는다.
 */
class CommanderySupplyLinkTest {
    private val mapper = ObjectMapper()

    /** 테스트 작업 디렉터리는 모듈 루트다 — 저장소 루트를 거슬러 올라가 찾는다. */
    private val repoRoot: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("data/map")) }

    private val artifact: Path = repoRoot.resolve("data/map/han-commandery-supply-links-v1.json")

    private fun loader(path: String) = CommanderySupplyLinkLoader(mapper, path)

    @Test
    fun `저장소 산출물에서 보급선을 읽는다`() {
        val links = loader(artifact.toString()).load()
        assertTrue(links.isNotEmpty(), "郡 내부 보급선이 비어 있으면 공융의 北海國 이 다시 끊긴다")
        links.forEach { (from, to) ->
            assertTrue(from != to, "보급선이 프로빈스를 자기 자신에 잇는다")
            assertTrue(from >= 0 && to >= 0, "프로빈스 인덱스는 음수일 수 없다")
        }
        assertEquals(links.size, links.map { setOf(it.first, it.second) }.distinct().size, "중복 보급선")
    }

    /** 산출물이 없어도 부팅은 막지 않는다 — 보급이 오늘과 같아질 뿐이다(ADR 의 되돌리기 경로). */
    @Test
    fun `파일이 없으면 빈 목록이다`() {
        assertEquals(emptyList(), loader(repoRoot.resolve("data/map/does-not-exist.json").toString()).load())
    }

    @Test
    fun `자기 자신을 잇는 행은 거절한다`(@TempDir dir: Path) {
        val path = dir.resolve("links.json")
        path.writeText("""{"links":[{"fromProvinceIndex":7,"toProvinceIndex":7}]}""")
        assertThrows<IllegalArgumentException> { loader(path.toString()).load() }
    }

    @Test
    fun `정수가 아닌 인덱스는 거절한다`(@TempDir dir: Path) {
        val path = dir.resolve("links.json")
        path.writeText("""{"links":[{"fromProvinceIndex":"3","toProvinceIndex":4}]}""")
        assertThrows<IllegalArgumentException> { loader(path.toString()).load() }
    }

    /**
     * 동명이지 5건은 규칙에서 빠져 있어야 한다. 빠지지 않으면 郡을 가로지르는 1,190km 짜리
     * 가짜 보급선이 생긴다.
     */
    @Test
    fun `산출물이 동명이지 제외를 기록한다`() {
        val root = mapper.readTree(Files.readAllBytes(artifact))
        val excluded = root.get("excludedByMisbinding").map { it.asText() }
        assertEquals(5, excluded.size, "동명이지 판정 5건이 그대로 제외돼야 한다")
        val longest = root.get("stats").get("maxKm").asDouble()
        assertTrue(longest < 500.0, "가장 긴 보급선이 ${longest}km 다 — 동명이지가 섞였을 수 있다")
    }
}
