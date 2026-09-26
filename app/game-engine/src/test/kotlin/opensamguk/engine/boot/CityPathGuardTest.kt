package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.world.SpatialSupplyProvider
import opensamguk.gameapi.read.MapAdministrativeOwnership
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.infra.seed.StrategicTopologyJson
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.RepositoryInputTrace
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CalcCityDistance
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Test
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.test.assertTrue

/** Fast guard for the allowlist shared by CI path filtering and cityTest inputs. */
class CityPathGuardTest {
    private val root = Path.of("../..").toAbsolutePath().normalize()
    private val sections: Map<String, List<String>> by lazy {
        val entries = mutableMapOf("data" to mutableListOf<String>(), "code" to mutableListOf())
        var section: String? = null
        root.resolve(".github/city-paths.txt").toFile().forEachLine { raw ->
            val line = raw.trim()
            if (line == "[data]" || line == "[code]") section = line.removeSurrounding("[", "]")
            else if (line.isNotEmpty() && !line.startsWith("#")) {
                requireNotNull(section) { "city path outside a section: $line" }
                entries.getValue(section!!).add(line)
            }
        }
        entries.also { require(it.values.all { paths -> paths.isNotEmpty() }) }
    }

    private fun listed(section: String, path: String): Boolean = sections.getValue(section).any { pattern ->
        FileSystems.getDefault().getPathMatcher("glob:$pattern").matches(Path.of(path))
    }

    @Test fun `opened city input files are covered by data paths`() {
        val opened = RepositoryInputTrace.capture(root) {
            MapJson.loadFromClasspath("han-world-v3")
            MapJson.loadCityDetailsFromClasspath("han-world-v3")
            SeedBootstrap(scenarioCode = "scenario_990002", worldId = WorldId(1)).loadScenario()
            val resolver = WorldArtifactsResolver(root)
            resolver.artifacts(WorldMapVariant.V3_835)
            resolver.artifacts(WorldMapVariant.V3_1447)
        }
        assertTrue(opened.isNotEmpty(), "city loader trace must capture files")
        assertTrue(opened.all { listed("data", it) },
            "city loader opened paths absent from [data]: ${opened.filterNot { listed("data", it) }}")
    }

    @Test fun `command keys and city path classes are covered by code paths`() {
        val classes = listOf(
            DatabaseHooks::class.java,
            JdbcFlushExecutor::class.java, WorldSnapshotLoader::class.java,
            MapAdministrativeOwnership::class.java, SpatialSupplyProvider::class.java,
            StrategicRouteProjection::class.java, CalcCityDistance::class.java,
            StrategicTopologyJson::class.java,
        )
        val missing = classes.map { type ->
            val sourcePath = type.name.substringBefore('$').replace('.', '/') + ".kt"
            listOf("common", "logic", "infra", "app/game-api", "app/game-engine")
                .map { "$it/src/main/kotlin/$sourcePath" }.firstOrNull { root.resolve(it).toFile().isFile }
                ?: error("source file for ${type.name} was not found")
        }.filterNot { listed("code", it) }
        assertTrue(missing.isEmpty(), "executed city command classes absent from [code]: $missing")
    }
}
