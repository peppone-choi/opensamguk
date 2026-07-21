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
            "RankDataReadRepository.kt" to "RankDataReadRawRepository",
            "AuctionCountReadRepository.kt" to "AuctionCountReadRawRepository",
            "DiplomacyReadRepository.kt" to "DiplomacyReadRawRepository",
            "LogFeedReadRepository.kt" to "LogFeedReadRawRepository",
            "GameKvReadRepository.kt" to "GameKvReadRawRepository",
            "WorldStateReadRepository.kt" to "WorldStateReadRawRepository",
        )

        repositories.forEach { (fileName, rawName) ->
            val source = readRepository(fileName)
            assertTrue(source.contains("private val worldId: WorldId"), fileName)
            assertFalse(
                Regex("""interface\s+""" + fileName.removeSuffix(".kt") + """\s*:\s*JpaRepository""")
                    .containsMatchIn(source),
                "$fileName must not be a public JpaRepository",
            )
            assertTrue(source.contains("interface $rawName"), fileName)
            val rawSection = source.substringAfter("interface $rawName")
                .substringBefore("\n@Repository")
            val funs = rawSection.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("fun ") }
                .toList()
            assertTrue(funs.isNotEmpty(), "$fileName raw has methods")
            // WorldState anchors on PK=id==world_id; facade filters. Others require worldId args.
            if (fileName != "WorldStateReadRepository.kt") {
                assertTrue(
                    funs.all { it.contains("WorldId") || it.contains("worldId") },
                    "$fileName raw methods must take worldId: $funs",
                )
            }
        }
    }

    private fun readRepository(fileName: String): String {
        val relativePath = "src/main/kotlin/opensamguk/gameapi/read/$fileName"
        val candidates = listOf(Path.of(relativePath), Path.of("app/game-api").resolve(relativePath))
        return Files.readString(candidates.first { Files.exists(it) })
    }
}
