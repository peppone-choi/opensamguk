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

        // 부팅 id 할당자 시드(message.findMaxId)는 월드 범위여야 한다. #917 A2 전에는 경매 저장소가 이 검사를 대신 짊어졌다.
        assertTrue(sources.contains("""fun findMaxId(@Param("worldId") worldId: Int)"""))
        assertTrue(
            Regex(
                """fun findByWorldIdAndIdAndNationId\(\s*@Param\("worldId"\) worldId: Int,""",
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
            "read/MessageRepository.kt",
            "read/BettingRepository.kt",
            "read/BoardPostRepository.kt",
            "read/GameKvRepository.kt",
            "read/InheritanceRepository.kt",
            "read/DiplomacyRepository.kt",
            "worldstate/WorldStateRepository.kt",
        )
    }
}
