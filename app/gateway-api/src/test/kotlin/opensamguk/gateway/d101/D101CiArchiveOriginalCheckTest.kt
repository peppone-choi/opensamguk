package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.D101RequestInvalid
import opensamguk.gateway.d101.security.D101CiArchiveOriginalCheck
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class D101CiArchiveOriginalCheckTest {
    @Test fun `whole ZIP and every JUnit original bind by multiset and actual testcase count`() {
        val p = Packet()
        p.verify()
        p.xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><testsuite tests=\"1\"><testcase name=\"x\"/></testsuite>".toByteArray(Charsets.UTF_16)
        p.zip = zip("TEST-a.xml" to p.xml, "note.bin" to byteArrayOf(1, 2, 3))
        p.refresh()
        p.verify()
    }

    @Test fun `missing XML changed bytes extra ZIP XML and skipped testcase stay closed`() {
        val p = Packet()
        p.raw.remove("raw:xml")
        assertThrows<D101RequestInvalid> { p.verify() }
        val changed = Packet()
        changed.xml = "<testsuite tests=\"1\"><testcase name=\"changed\"/></testsuite>".toByteArray()
        changed.refreshInventoryOnly()
        assertThrows<D101RequestInvalid> { changed.verify() }
        val extra = Packet()
        extra.zip = zip("TEST-a.xml" to extra.xml, "TEST-extra.xml" to extra.xml)
        extra.refreshArchiveOnly()
        assertThrows<D101RequestInvalid> { extra.verify() }
        val skipped = Packet()
        skipped.xml = "<testsuite tests=\"1\" skipped=\"1\"><testcase name=\"x\"><skipped/></testcase></testsuite>".toByteArray()
        skipped.refresh()
        assertThrows<D101RequestInvalid> { skipped.verify() }
    }

    @Test fun `DTD UTF16 entity bad CRC duplicate names and declared oversized ZIP reject`() {
        val dtd = Packet()
        dtd.xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE testsuite [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><testsuite tests=\"1\"><testcase name=\"x\"/></testsuite>".toByteArray(Charsets.UTF_16)
        dtd.refresh()
        assertThrows<D101RequestInvalid> { dtd.verify() }
        val corrupt = Packet()
        corrupt.zip = corrupt.zip.copyOf().also { bytes ->
            val central = signature(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        }
        corrupt.refreshArchiveOnly()
        assertThrows<D101RequestInvalid> { corrupt.verify() }
        val duplicate = Packet()
        duplicate.zip = zip("TEST-a.xml" to duplicate.xml, "TEST-b.xml" to duplicate.xml)
        duplicate.zip = duplicate.zip.copyOf().also { bytes ->
            val first = signature(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            val second = signature(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02), first + 4)
            bytes[second + 46 + 5] = 'a'.code.toByte() // TEST-b.xml -> TEST-a.xml in central directory.
        }
        duplicate.refreshArchiveOnly()
        assertThrows<D101RequestInvalid> { duplicate.verify() }
        val oversized = Packet()
        oversized.zip = oversized.zip.copyOf().also { bytes ->
            val central = signature(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
            val declared = 64 * 1024 * 1024 + 1
            for (i in 0..3) bytes[central + 24 + i] = (declared ushr (8 * i)).toByte()
        }
        oversized.refreshArchiveOnly()
        assertThrows<D101RequestInvalid> { oversized.verify() }
    }

    private class Packet {
        val f = D101RawEvidenceFixture()
        val source = "a".repeat(40)
        var xml = "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"x\"/></testsuite>".toByteArray()
        var zip = zip("TEST-a.xml" to xml, "note.bin" to byteArrayOf(1, 2, 3))
        val raw = mutableMapOf<String, ByteArray>()
        val run = f.obj("runId" to 10, "attempt" to 2, "sourceSha" to source)
        val artifact = f.obj("runId" to 10, "attempt" to 2, "jobId" to 12,
            "headSourceSha" to source, "tests" to 1, "failures" to 0, "errors" to 0, "skipped" to 0)
        init { refresh() }
        fun refreshInventoryOnly() {
            raw["raw:xml"] = xml
            val inventory = f.obj("schemaVersion" to 1, "kind" to "D101_JUNIT_INVENTORY_V1", "runId" to 10,
                "attempt" to 2, "jobId" to 12, "headSourceSha" to source,
                "entries" to listOf(f.ref("raw:xml", xml, "application/xml")))
            raw["raw:inventory"] = f.wire(inventory)
            artifact.set<ObjectNode>("junitInventoryRef", f.ref("raw:inventory", raw.getValue("raw:inventory")))
        }
        fun refreshArchiveOnly() {
            raw["raw:archive"] = zip
            artifact.set<ObjectNode>("archiveRef", f.ref("raw:archive", zip, "application/zip"))
        }
        fun refresh() { refreshInventoryOnly(); refreshArchiveOnly() }
        fun verify() = D101CiArchiveOriginalCheck().verify(artifact, run, raw)
    }

    companion object {
        private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { stream ->
                for ((name, bytes) in entries) {
                    stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry()
                }
            }
            return out.toByteArray()
        }
        private fun signature(bytes: ByteArray, marker: ByteArray, start: Int = 0): Int =
            (start..bytes.size - marker.size).first { index -> marker.indices.all { bytes[index + it] == marker[it] } }
    }
}
