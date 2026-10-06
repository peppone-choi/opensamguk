package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Pure bounded archive/JUnit subcheck; it authenticates no GitHub collector or job. */
internal class D101CiArchiveOriginalCheck(private val mapper: ObjectMapper = ObjectMapper()) {
    private val json = D101StrictJson(mapper)
    private val refs = D101Original6ScopeCheck(json)

    fun verify(artifact: JsonNode, run: JsonNode, originals: Map<String, ByteArray>) {
        val runId = json.positiveLong(run["runId"])
        val attempt = json.positiveLong(run["attempt"])
        val jobId = json.positiveLong(artifact["jobId"])
        val source = refs.source(run["sourceSha"])
        if (json.positiveLong(artifact["runId"]) != runId || json.positiveLong(artifact["attempt"]) != attempt ||
            refs.source(artifact["headSourceSha"]) != source || json.positiveLong(artifact["tests"]) <= 0 ||
            listOf("failures", "errors", "skipped").any { artifact[it] == null || !artifact[it].isIntegralNumber || artifact[it].bigIntegerValue().signum() != 0 }) json.invalid()
        val inventoryRef = refs.ref(artifact["junitInventoryRef"])
        val archiveRef = refs.ref(artifact["archiveRef"])
        if (inventoryRef.mediaType != "application/json" || archiveRef.mediaType != "application/zip") json.invalid()
        val inventory = json.objectBytes(bound(inventoryRef, originals), INVENTORY_KEYS, 64 * 1024 * 1024)
        if (json.positiveLong(inventory["schemaVersion"]) != 1L || json.text(inventory["kind"]) != "D101_JUNIT_INVENTORY_V1" ||
            json.positiveLong(inventory["runId"]) != runId || json.positiveLong(inventory["attempt"]) != attempt ||
            json.positiveLong(inventory["jobId"]) != jobId || refs.source(inventory["headSourceSha"]) != source) json.invalid()
        val entries = inventory["entries"]
        if (!entries.isArray || entries.size() !in 1..4096 || entries.any { it.isNull }) json.invalid()
        val ids = HashSet<String>()
        val xmlHashes = HashMap<String, Int>()
        var tests = 0L
        for (entry in entries) {
            val ref = refs.ref(entry)
            if (!ref.logicalId.startsWith("raw:") || ref.mediaType != "application/xml" || !ids.add(ref.logicalId)) json.invalid()
            val raw = bound(ref, originals)
            tests = safeAdd(tests, junitCount(raw).toLong())
            xmlHashes.merge(ref.sha256, 1, Int::plus)
        }
        if (tests != json.positiveLong(artifact["tests"]) || zipXmlHashes(bound(archiveRef, originals)) != xmlHashes) json.invalid()
    }

    private fun bound(ref: D101Original6ScopeCheck.RawRef, originals: Map<String, ByteArray>): ByteArray {
        val raw = originals[ref.logicalId] ?: json.invalid()
        val frozen = raw.copyOf()
        if (frozen.isEmpty() || frozen.size.toLong() != ref.byteLength || D101StrictJson.hash(frozen) != ref.sha256) json.invalid()
        return frozen
    }

    private fun junitCount(raw: ByteArray): Int {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val parser = factory.newDocumentBuilder()
            parser.setEntityResolver { _, _ -> throw IllegalArgumentException("external entity") }
            val root = parser.parse(ByteArrayInputStream(raw)).documentElement ?: json.invalid()
            if (root.tagName !in setOf("testsuite", "testsuites")) json.invalid()
            val suites = root.getElementsByTagName("testsuite")
            val count = if (root.tagName == "testsuite") suites.length + 1 else suites.length
            if (count <= 0) json.invalid()
            var total = 0
            for (i in 0 until count) {
                val suite = (if (root.tagName == "testsuite" && i == 0) root else
                    suites.item(if (root.tagName == "testsuite") i - 1 else i)) as Element
                val directCases = (0 until suite.childNodes.length).count {
                    (suite.childNodes.item(it) as? Element)?.tagName == "testcase"
                }
                val declared = suite.getAttribute("tests").toIntOrNull() ?: json.invalid()
                if (declared != directCases || listOf("failures", "errors", "skipped").any {
                        (suite.getAttribute(it).ifEmpty { "0" }).toIntOrNull() != 0
                    }) json.invalid()
                total = Math.addExact(total, directCases)
            }
            if (total <= 0 || root.getElementsByTagName("testcase").length != total ||
                listOf("failure", "error", "skipped").any { root.getElementsByTagName(it).length != 0 }) json.invalid()
            return total
        } catch (_: Exception) { json.invalid() }
    }

    private data class Member(val name: String, val size: Long, val compressed: Long, val crc: Long, val method: Int)

    private fun zipXmlHashes(raw: ByteArray): Map<String, Int> {
        try {
            val members = centralMembers(raw)
            val hashes = HashMap<String, Int>()
            var expanded = 0L
            var ordinal = 0
            ZipInputStream(ByteArrayInputStream(raw), Charset.forName("Cp437")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (ordinal >= members.size) json.invalid()
                    val expected = members[ordinal++]
                    if (entry.name != expected.name || entry.method != expected.method ||
                        (entry.isDirectory && expected.size != 0L)) json.invalid()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size = safeAdd(size, n.toLong())
                        expanded = safeAdd(expanded, n.toLong())
                        if (expanded > RAW_LIMIT || size > expected.size) json.invalid()
                        digest.update(buffer, 0, n)
                    }
                    zip.closeEntry() // ZipInputStream verifies CRC when an entry ends.
                    if (size != expected.size || entry.size != expected.size ||
                        entry.compressedSize != expected.compressed || entry.crc != expected.crc) json.invalid()
                    if (entry.name.endsWith(".xml")) hashes.merge(HexFormat.of().formatHex(digest.digest()), 1, Int::plus)
                }
            }
            if (ordinal != members.size) json.invalid()
            return hashes
        } catch (_: Exception) { json.invalid() }
    }

    private fun centralMembers(raw: ByteArray): List<Member> {
        // Read the central directory as well as the stream: data-descriptor ZIP
        // entries have no local size until decompression, so local headers alone
        // cannot enforce the declared 64 MiB bound before reading.
        val end = (raw.size - 22 downTo maxOf(0, raw.size - 65557)).firstOrNull { u32(raw, it) == 0x06054b50L } ?: json.invalid()
        if (u16(raw, end + 4) != 0 || u16(raw, end + 6) != 0 || u16(raw, end + 8) != u16(raw, end + 10) ||
            end + 22 + u16(raw, end + 20) != raw.size) json.invalid()
        val count = u16(raw, end + 10)
        val offset = u32(raw, end + 16)
        val length = u32(raw, end + 12)
        if (count == 0 || count == 65535 || offset == 0xffffffffL || length == 0xffffffffL ||
            offset + length != end.toLong() || length > raw.size) json.invalid()
        var cursor = offset.toInt()
        var declared = 0L
        var xmlCount = 0
        val names = HashSet<String>()
        val result = ArrayList<Member>(count)
        repeat(count) {
            if (u32(raw, cursor) != 0x02014b50L || cursor + 46 > end) json.invalid()
            val flags = u16(raw, cursor + 8)
            val method = u16(raw, cursor + 10)
            val crc = u32(raw, cursor + 16)
            val compressed = u32(raw, cursor + 20)
            val size = u32(raw, cursor + 24)
            val nameLength = u16(raw, cursor + 28)
            val extraLength = u16(raw, cursor + 30)
            val commentLength = u16(raw, cursor + 32)
            val localOffset = u32(raw, cursor + 42)
            val next = cursor.toLong() + 46 + nameLength + extraLength + commentLength
            if (flags and 1 != 0 || method !in setOf(0, 8) || compressed == 0xffffffffL || size == 0xffffffffL ||
                localOffset == 0xffffffffL || localOffset >= offset || next > end) json.invalid()
            val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("Cp437")
            val name = String(raw, cursor + 46, nameLength, charset)
            if (name.isEmpty() || !names.add(name)) json.invalid()
            declared = safeAdd(declared, size)
            if (declared > RAW_LIMIT) json.invalid()
            if (name.endsWith(".xml")) xmlCount++
            result += Member(name, size, compressed, crc, method)
            cursor = next.toInt()
        }
        if (cursor != end || xmlCount !in 1..4096) json.invalid()
        return result
    }

    private fun u16(raw: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > raw.size) json.invalid()
        return ByteBuffer.wrap(raw, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }
    private fun u32(raw: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > raw.size) json.invalid()
        return ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }
    private fun safeAdd(a: Long, b: Long): Long = try { Math.addExact(a, b) } catch (_: ArithmeticException) { json.invalid() }

    companion object {
        private const val RAW_LIMIT = 64L * 1024 * 1024
        private val INVENTORY_KEYS = setOf("schemaVersion", "kind", "runId", "attempt", "jobId", "headSourceSha", "entries")
    }
}
