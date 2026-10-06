package opensamguk.gateway.d101

import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.*

class D101MigrationSourceTest {
    @Test
    fun `actual classpath migration versions are unique and execution migration is present`() {
        val resources = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")
        assertTrue(resources.isNotEmpty())
        val versions = resources.map { resource ->
            val filename = requireNotNull(resource.filename)
            requireNotNull(Regex("^V([0-9]+)__.+\\.sql$").matchEntire(filename)).groupValues[1].toLong()
        }
        assertEquals(versions.size, versions.toSet().size, "duplicate migration version in actual runtime classpath")
        assertEquals(1, resources.count { it.filename?.endsWith("__game_server_d101_execution.sql") == true })
    }
}
