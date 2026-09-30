package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class Archive1428ArtifactsIntegrityTest {
    @TempDir lateinit var temporary: Path

    @Test fun `1428 retires exactly the duplicate synthetic counties and adds the four homonym gaps`() {
        val release = Archive1428Artifacts.load(Path.of(".."))
        val previous = Archive1447Map4Artifacts.load(Path.of("..")).cityConst.all().keys
        val cities = MapJson.loadCityDetails(release.artifactBytes(
            "infra/src/main/resources/map/han-world-v3.json").toString(Charsets.UTF_8)).associateBy { it.id }
        assertEquals(cities.keys, release.cityConst.all().keys)
        // gap-county-duplicate-retirements-v1: 지도에 다른 이름으로 이미 있던 縣의 합성 城. 번호는 다시 쓰지 않는다.
        val retired = setOf(1399, 1401, 1403, 1405, 1410, 1423, 1429, 1431, 1432, 1433, 1436, 1437,
            1443, 1446, 1448, 1452, 1461, 1469, 1471, 1473, 1476, 1585, 1603)
        assertEquals(retired, previous - cities.keys)
        // 山陽 髙平 · 南陽 成都 · 上郡 候官 · 鴈門 卤城 — 이름만 같은 다른 땅이라 이름 대조가 놓쳤다.
        assertEquals(setOf(1621, 1622, 1623, 1624), cities.keys - previous)
        // 卷113 簡體 원문을 hanja 가 오독하던 이름(广汉 → 엄한)이 고쳐졌다.
        assertEquals("광한", cities.getValue(1474).name)
    }

    @Test fun `changed catalog and corrupt or missing blob never fall back to current files`() {
        val relative = "data/map/han-world-v3-1428-artifacts-v1"
        val source = Path.of("..").resolve(relative)
        val catalog = Files.readAllBytes(source.resolve("catalog.json"))
        val blob = ObjectMapper().readTree(catalog).path("files").first().path("blob").asText()
        for (mutation in listOf("catalog", "blob", "missing")) {
            val root = temporary.resolve(mutation)
            val destination = root.resolve(relative)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            Archive1428Artifacts.load(root) // Establish a valid fixture before introducing the fault.
            when (mutation) {
                "catalog" -> Files.write(destination.resolve("catalog.json"), catalog + byteArrayOf(10))
                "blob" -> Files.write(destination.resolve(blob), byteArrayOf(0, 1, 2))
                "missing" -> Files.delete(destination.resolve(blob))
            }
            if (mutation == "missing") {
                assertFailsWith<java.nio.file.NoSuchFileException> { Archive1428Artifacts.load(root) }
            } else {
                assertFailsWith<IllegalArgumentException>(mutation) { Archive1428Artifacts.load(root) }
            }
        }
    }
}
