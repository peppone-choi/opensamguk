package opensamguk.infra.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The `:infra` half of the daemon JDBC-only guard (design §0.1 #3 / research N4 — sibling of the
 * `:app:game-engine` `DaemonNoEntityManagerTest`).
 *
 * `:infra` carries `kotlin("jpa")` + `spring-boot-starter-data-jpa` (the read side that lives in
 * `:app:game-api` needs JPA on the classpath), so a stray `EntityManager`/`EntityManagerFactory` or a
 * Spring-Data write repository could leak onto the flush SINK and still compile. This guard scans the
 * compiled `opensamguk.infra.persistence` classes — `JdbcFlushExecutor`, the row mappers
 * (`GeneralRowMapper`/`CityRowMapper`/`MetaJson`), and `ReservedTurnRepository` — and asserts NONE of
 * them references a banned persistence type. Those classes must speak ONLY
 * `org.springframework.jdbc.*` for persistence.
 *
 * Constant-pool substring scan (no ArchUnit dep): a class that imported/used a forbidden type carries
 * its slash-form internal name in the constant pool. We also positively assert the JDBC sink
 * (`JdbcFlushExecutor`) DOES reference `org.springframework.jdbc.*`, so the scan is anchored to the
 * real write path and not silently matching an empty/relocated package.
 */
class InfraNoEntityManagerTest {

    private val forbidden = listOf(
        "jakarta/persistence/EntityManager",
        "jakarta/persistence/EntityManagerFactory",
        "org/springframework/data/jpa/repository/JpaRepository",
        "org/springframework/data/repository/CrudRepository",
    )

    private val jdbcMarker = "org/springframework/jdbc/"

    private fun persistenceClassesDir(): File {
        val candidates = listOf(
            File("build/classes/kotlin/main/opensamguk/infra/persistence"),
            File("infra/build/classes/kotlin/main/opensamguk/infra/persistence"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("compiled infra.persistence classes not found in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `infra persistence write classes reference no JPA EntityManager EntityManagerFactory or Spring-Data repository`() {
        val dir = persistenceClassesDir()
        val classFiles = dir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        assertTrue(
            classFiles.isNotEmpty(),
            "expected at least one compiled infra.persistence class in ${dir.absolutePath}",
        )

        var sawJdbcSink = false
        for (classFile in classFiles) {
            val text = String(classFile.readBytes(), Charsets.ISO_8859_1)
            for (needle in forbidden) {
                assertTrue(
                    !text.contains(needle),
                    "${classFile.path} constant pool references forbidden persistence type $needle " +
                        "(infra write path must stay JDBC-only)",
                )
            }
            if (classFile.name.startsWith("JdbcFlushExecutor") && text.contains(jdbcMarker)) {
                sawJdbcSink = true
            }
        }

        assertTrue(
            sawJdbcSink,
            "expected JdbcFlushExecutor to reference org.springframework.jdbc.* — the scan must be " +
                "anchored to the real JDBC write sink",
        )
    }
}
