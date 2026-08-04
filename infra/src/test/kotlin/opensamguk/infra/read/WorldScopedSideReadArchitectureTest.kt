package opensamguk.infra.read

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldScopedSideReadArchitectureTest {
    @Test
    fun `public side read facades do not expose Spring Data CRUD`() {
        repositorySources.forEach { relativePath ->
            val source = Files.readString(repositoryRoot.resolve(relativePath))
            assertFalse(
                source.contains("JpaRepository"),
                "$relativePath must not inherit JpaRepository CRUD",
            )
            assertTrue(
                source.contains("SpringDataRepository"),
                "$relativePath must retain only a raw Spring Data Repository",
            )
        }
    }

    @Test
    fun `raw side read methods require an explicit world scope`() {
        val sources = repositorySources
            .map { repositoryRoot.resolve(it) }
            .joinToString("\n") { Files.readString(it) }

        assertTrue(sources.contains("""fun findMaxId(@Param("worldId") worldId: Int)"""))
        assertTrue(
            Regex(
                """fun findHighestBidsByAuctionIds\(\s*@Param\("worldId"\) worldId: Int,""",
            ).containsMatchIn(sources),
        )
        assertTrue(
            sources.contains(
                """fun aggregateTotalAmountByBetting(@Param("worldId") worldId: Int)""",
            ),
        )
        assertTrue(sources.contains("findByWorldIdAndIdAndNationId"))
        assertTrue(sources.contains("findByWorldIdAndTable"))
        assertTrue(sources.contains("kv.worldId IS NULL"))
    }

    private companion object {
        val repositoryRoot: Path = Path.of("src/main/kotlin/opensamguk/infra")
        val repositorySources = listOf(
            "read/AuctionRepository.kt",
            "read/AuctionBidRepository.kt",
            "read/BettingRepository.kt",
            "read/BoardPostRepository.kt",
            "read/GameKvRepository.kt",
            "read/InheritanceRepository.kt",
            "read/DiplomacyRepository.kt",
            "worldstate/WorldStateRepository.kt",
        )
    }
}
