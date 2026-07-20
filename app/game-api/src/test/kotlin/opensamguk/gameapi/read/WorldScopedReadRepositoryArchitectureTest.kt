package opensamguk.gameapi.read

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldScopedReadRepositoryArchitectureTest {
    @Test
    fun `world scoped read repositories expose no inherited unscoped CRUD`() {
        val repositories = listOf(
            "GeneralReadRepository.kt" to "GeneralReadRawRepository",
            "NationReadRepository.kt" to "NationReadRawRepository",
            "CityReadRepository.kt" to "CityReadRawRepository",
            "GeneralTurnReadRepository.kt" to "GeneralTurnReadRawRepository",
            "NationTurnReadRepository.kt" to "NationTurnReadRawRepository",
        )

        repositories.forEach { (fileName, rawName) ->
            val source = readRepository(fileName)
            val rawSection = source.substringAfter("interface $rawName : SpringDataRepository")
                .substringBefore("\n@Repository")

            assertTrue(source.contains("@Column(name = \"world_id\")"), fileName)
            assertFalse(source.contains("JpaRepository"), fileName)
            assertTrue(source.contains("private val worldId: WorldId"), fileName)
            assertTrue(
                rawSection.lineSequence()
                    .filter { it.trimStart().startsWith("fun ") }
                    .all { it.contains("WorldId") },
                fileName,
            )
            assertTrue(
                rawSection.split("@Query").drop(1)
                    .all { it.substringBefore("fun ").contains("worldId") },
                fileName,
            )
        }
    }

    private fun readRepository(fileName: String): String {
        val relativePath = "src/main/kotlin/opensamguk/gameapi/read/$fileName"
        val candidates = listOf(Path.of(relativePath), Path.of("app/game-api").resolve(relativePath))
        return Files.readString(candidates.first { Files.exists(it) })
    }
}
