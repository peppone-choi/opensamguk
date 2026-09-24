package opensamguk.infra.seed

import java.nio.file.Path

/** Records repository files opened by the Han loaders during a test-only capture. */
object RepositoryInputTrace {
    private data class Capture(val root: Path, val paths: MutableSet<String>)
    private val current = ThreadLocal<Capture?>()

    fun capture(root: Path, action: () -> Unit): Set<String> {
        val previous = current.get()
        val capture = Capture(root.toAbsolutePath().normalize(), linkedSetOf())
        current.set(capture)
        try {
            action()
            return capture.paths.toSet()
        } finally {
            current.set(previous)
        }
    }

    fun file(path: Path) {
        val capture = current.get() ?: return
        val absolute = path.toAbsolutePath().normalize()
        capture.paths.add(capture.root.relativize(absolute).toString().replace('\\', '/'))
    }

    fun resource(path: String) {
        current.get()?.paths?.add("infra/src/main/resources/$path")
    }
}
