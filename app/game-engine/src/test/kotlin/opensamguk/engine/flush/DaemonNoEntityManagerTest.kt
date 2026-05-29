package opensamguk.engine.flush

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enforces design §0.1 #3 / research N4 STRUCTURALLY: the daemon WRITE path must never reference a
 * JPA `EntityManager`/`EntityManagerFactory` or a Spring-Data write repository. This is a class-file
 * constant-pool scan (no ArchUnit dependency) — a class that imported/used either type carries the
 * slash-form internal name in its constant pool.
 *
 * The write path = the packages listed in [DaemonWriteGuard.writePathPackages]
 * (`opensamguk.engine.flush` + `opensamguk.engine.turn` + `opensamguk.engine.run`), and the banned
 * types = [DaemonWriteGuard.forbiddenInternalNames]. Both lists are read from [DaemonWriteGuard] so
 * the invariant and its enforcement can never drift. The `:infra` half of the same write path is
 * covered by the sibling `InfraNoEntityManagerTest`.
 *
 * If a future edit attaches an `EntityManager` (or a Spring-Data repository) to the daemon write
 * path, this test fails — the "JDBC-only" rule is a test, not a convention.
 */
class DaemonNoEntityManagerTest {

    private val forbidden = DaemonWriteGuard.forbiddenInternalNames

    /**
     * Resolve the compiled main-classes root for the engine. The test working directory is the
     * module root (`app/game-engine`) or the project root depending on the runner; probe both.
     */
    private fun mainClassesRoot(): File {
        val candidates = listOf(
            File("build/classes/kotlin/main"),
            File("app/game-engine/build/classes/kotlin/main"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("compiled engine main classes not found in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `daemon write-path packages reference no EntityManager EntityManagerFactory or Spring-Data repository`() {
        val root = mainClassesRoot()

        // Scan whichever write-path package dirs exist. The `run` package (Task F5) may be absent in
        // this worktree — an absent directory is vacuously clean. flush + turn MUST be present.
        val packageDirs = DaemonWriteGuard.writePathPackages.map { pkg -> pkg to File(root, pkg) }
        val present = packageDirs.filter { (_, dir) -> dir.isDirectory }

        for (required in listOf("opensamguk/engine/flush", "opensamguk/engine/turn")) {
            assertTrue(
                present.any { (pkg, _) -> pkg == required },
                "expected the write-path package $required to be compiled under ${root.absolutePath}",
            )
        }

        val classFiles = present
            .flatMap { (_, dir) -> dir.walkTopDown().filter { it.isFile && it.extension == "class" } }
            // [DaemonWriteGuard] is the guard's own vocabulary: it carries the forbidden internal
            // names as STRING constants (the banned-list definition), so its constant pool matches
            // by construction. It references none of these as a TYPE — exclude the data holder.
            .filterNot { it.name.startsWith("DaemonWriteGuard") }
        assertTrue(
            classFiles.isNotEmpty(),
            "expected at least one compiled write-path class under ${root.absolutePath}",
        )

        for (classFile in classFiles) {
            val text = String(classFile.readBytes(), Charsets.ISO_8859_1)
            for (needle in forbidden) {
                assertTrue(
                    !text.contains(needle),
                    "${classFile.path} constant pool references forbidden persistence type $needle " +
                        "(daemon write path must stay JDBC-only)",
                )
            }
        }
    }
}
