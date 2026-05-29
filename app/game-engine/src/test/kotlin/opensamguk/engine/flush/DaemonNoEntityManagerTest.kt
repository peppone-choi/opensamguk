package opensamguk.engine.flush

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enforces design §0.1 #3 structurally: the daemon write path (`opensamguk.engine.flush`)
 * must never reference a JPA EntityManager or a Spring-Data JpaRepository. This is a class-file
 * constant-pool scan (no ArchUnit dependency): a class that imported/used either type would carry
 * the slash-form internal name in its constant pool.
 */
class DaemonNoEntityManagerTest {
    private val forbidden = listOf(
        "jakarta/persistence/EntityManager",
        "org/springframework/data/jpa/repository/JpaRepository",
    )

    private fun flushClassesDir(): File {
        // Resolve the compiled main classes for the engine.flush package. The test working
        // directory is the module root (app/game-engine) or the project root depending on the
        // runner; probe both candidate layouts.
        val candidates = listOf(
            File("build/classes/kotlin/main/opensamguk/engine/flush"),
            File("app/game-engine/build/classes/kotlin/main/opensamguk/engine/flush"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("compiled engine.flush classes not found in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `engine flush classes reference neither EntityManager nor JpaRepository`() {
        val dir = flushClassesDir()
        val classFiles = dir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "expected at least one compiled flush class in ${dir.absolutePath}")

        for (classFile in classFiles) {
            val bytes = classFile.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            for (needle in forbidden) {
                assertTrue(
                    !text.contains(needle),
                    "${classFile.name} constant pool references forbidden type $needle",
                )
            }
        }
    }
}
