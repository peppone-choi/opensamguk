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
            "HistoryReadRepository.kt" to "HistoryReadRawRepository",
            "TroopReadRepository.kt" to "TroopReadRawRepository",
            "GeneralAccessLogReadRepository.kt" to "GeneralAccessLogReadRawRepository",
            "WorldLogReadRepository.kt" to "WorldLogReadRawRepository",
            "NationLogReadRepository.kt" to "NationLogReadRawRepository",
            "AdminGeneralLogReadRepository.kt" to "AdminGeneralLogReadRawRepository",
            "DiplomacyLetterReadRepository.kt" to "DiplomacyLetterReadRawRepository",
            "BoardReadRepository.kt" to "BoardPostReadRawRepository",
            "RankingExtraReadRepository.kt" to "HallReadRawRepository",
            "VoteReadRepository.kt" to "VotePollReadRawRepository",
            "NationEnvReadRepository.kt" to "NationEnvReadRawRepository",
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
            // Collapse multiline signatures so worldId on next lines still counts.
            val collapsed = rawSection.replace(Regex("""\s+"""), " ")
            val funs = Regex("""fun \w+\([^)]*\)""").findAll(collapsed).map { it.value }.toList()
            assertTrue(funs.isNotEmpty(), "$fileName raw has methods: ${rawSection.take(200)}")
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
