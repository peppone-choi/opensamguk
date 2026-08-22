package opensamguk.boardapi.config

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse

class TestResourceShadowingTest {
    private val testResources = Path.of("src", "test", "resources")

    @Test
    fun `test resources must not shadow the main application configuration`() {
        for (name in listOf("application.yml", "application.yaml", "application.properties")) {
            assertFalse(
                testResources.resolve(name).exists(),
                "src/test/resources/$name shadows the main application configuration; " +
                    "use application-test.yml with @ActiveProfiles(\"test\") (OPENSAM-223).",
            )
        }
    }
}
