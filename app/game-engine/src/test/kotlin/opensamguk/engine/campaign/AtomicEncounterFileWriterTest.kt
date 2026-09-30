package opensamguk.engine.campaign

import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicEncounterFileWriterTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `same committed encounter may be retried but conflicting bytes are rejected`() {
        val writer = AtomicEncounterFileWriter(directory)
        val original = "{\"encounterId\":\"enc-1\",\"replayHash\":\"a\"}".toByteArray()
        writer.write(990002, "enc-1", original)
        writer.write(990002, "enc-1", original)
        assertFailsWith<IllegalStateException> {
            writer.write(990002, "enc-1", "different".toByteArray())
        }
        val entries = Files.list(directory).use { it.toList() }
        assertEquals(1, entries.size)
        assertContentEquals(original, Files.readAllBytes(entries.single()))
    }
}
