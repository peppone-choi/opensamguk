package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.D101RequestInvalid
import opensamguk.gateway.d101.security.D101IndependentReviewOriginalCheck
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class D101IndependentReviewOriginalCheckTest {
    @Test
    fun `valid original Codex issue and publication window retains the unchanged old verdict`() {
        Packet().verify() // No current clock or expiry cache grants or removes authority.
    }

    @Test
    fun `actual Claude marker is distinguished from replies and Codex markers`() {
        val p = Packet(); p.record.put("backend", "claude-independent")
        p.commentBody("<!-- pr-loop v1 claude-verdict sha=${p.f.app} verdict=MERGEABLE -->")
        p.verify()
        p.commentBody("<!-- pr-loop v1 codex-reply sha=${p.f.app} --> MERGEABLE")
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    @Test
    fun `matching head window and MERGEABLE substrings are not an actual verdict marker`() {
        val p = Packet()
        p.commentBody("MERGEABLE ${p.f.app} synthetic-window")
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    @Test
    fun `old head blocked verdict wrong window and ambiguous markers reject after raw rebind`() {
        for (body in listOf(
            marker("a".repeat(40)), marker(verdict = "BLOCKED"), marker(window = "wrong-window"),
            marker() + marker(), marker() + "<!-- pr-loop v1 claude-verdict sha=${"a".repeat(40)} verdict=MERGEABLE -->",
        )) {
            val p = Packet(); p.commentBody(body)
            assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `both marker issuance and publication must be in half open original window and ordered`() {
        for (issued in listOf(989L, 1010L, 1001L)) {
            val p = Packet(); p.commentBody(marker(issued = issued))
            assertThrows<D101RequestInvalid> { p.verify() }
        }
        val p = Packet(); p.record.put("issuedAtUnix", 1010)
        p.raw("verdict", p.f.obj("id" to 17, "created_at" to stamp(1010), "body" to marker(issued = 1009)))
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    @Test
    fun `original window authorization times unique identity and schema must agree`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("version", 2) },
            { (it["windows"][0] as ObjectNode).put("authorization", "collector") },
            { (it["windows"][0] as ObjectNode).put("backend", "claude") },
            { (it["windows"][0] as ObjectNode).put("startsAt", stamp(991)) },
            { (it["windows"][0] as ObjectNode).put("id", "other-window") },
            { (it["windows"] as com.fasterxml.jackson.databind.node.ArrayNode).add(it["windows"][0].deepCopy<JsonNode>()) },
        )
        for (change in changes) { val p = Packet(); val w = p.window(); change(w); p.raw("window", w)
            assertThrows<D101RequestInvalid> { p.verify() } }
    }

    @Test
    fun `actual comment identifier and timestamp reject metadata relabeling`() {
        for (comment in listOf(
            mapOf("id" to 18, "created_at" to stamp(1000), "body" to marker()),
            mapOf("id" to 17.0, "created_at" to stamp(1000), "body" to marker()),
            mapOf("id" to 17, "created_at" to stamp(999), "body" to marker()),
            mapOf("id" to 17, "created_at" to "2026-10-06T08:00:00", "body" to marker()),
        )) { val p = Packet(); p.raw("verdict", p.f.mapper.valueToTree(comment))
            assertThrows<D101RequestInvalid> { p.verify() } }
    }

    @Test
    fun `changed complete filename set duplicate filename and noncanonical paths reject`() {
        for (files in listOf(listOf("other.kt"), listOf("synthetic.kt", "synthetic.kt"),
            listOf("./synthetic.kt"), listOf("dir//synthetic.kt"), listOf("dir/../synthetic.kt"))) {
            val p = Packet(); p.files(files)
            assertThrows<D101RequestInvalid> { p.verify() }
        }
        for (path in listOf("./synthetic.kt", "dir//synthetic.kt", "/synthetic.kt", "dir/../synthetic.kt")) {
            val p = Packet(); p.fileScope.set<JsonNode>("paths", p.f.mapper.valueToTree(listOf(path))); p.files(listOf(path))
            assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `file scope cannot change PR number source head or project under old review labels`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("prNumber", 8) }, { it.put("prHeadSha", "e".repeat(40)) },
            { it.put("sourceSha", "e".repeat(40)) }, { it.put("project", "docker") },
        )
        for (change in changes) { val p = Packet(); change(p.fileScope)
            assertThrows<D101RequestInvalid> { p.verify() } }
    }

    @Test
    fun `reviewed four originals must match current bytes despite unchanged reference labels`() {
        for (id in listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings")) {
            val p = Packet(); p.fixed[id] = "{\"changed\":true}".toByteArray()
            assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `raw comment drift missing separation and wrong comment media reject`() {
        val drift = Packet(); drift.raws["raw:verdict"] = "{}".toByteArray()
        assertThrows<D101RequestInvalid> { drift.verify() }
        val missing = Packet(); missing.raws.remove("raw:separation")
        assertThrows<D101RequestInvalid> { missing.verify() }
        val media = Packet(); (media.record["verdictRawRef"] as ObjectNode).put("mediaType", "text/plain")
        assertThrows<D101RequestInvalid> { media.verify() }
    }

    @Test
    fun `malformed raw duplicate trailing BOM and invalid UTF8 reject even after hash rebind`() {
        for (bytes in listOf("{\"id\":17,\"id\":17}".toByteArray(), "{} {}".toByteArray(),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "{}".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), "null".toByteArray())) {
            val p = Packet(); p.rawBytes("verdict", bytes)
            assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `self reviewing author and repeated implementer sessions are rejected`() {
        val self = Packet(); self.record.put("authorSessionId", "implementation-session")
        assertThrows<D101RequestInvalid> { self.verify() }
        val duplicate = Packet(); duplicate.record.set<JsonNode>("implementerSessionIds",
            duplicate.f.mapper.valueToTree(listOf("implementation-session", "implementation-session")))
        assertThrows<D101RequestInvalid> { duplicate.verify() }
    }

    @Test
    fun `code merge marker and rebound review class cannot grant preinstallation review`() {
        val p = Packet(); p.record.put("reviewClass", "D101_PREINSTALL_SCOPE")
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    private class Packet {
        val f = D101Fixture()
        private val r = D101RawEvidenceFixture()
        val intent = f.intent()
        val fixed = listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings")
            .associateWith { r.wire(r.obj("syntheticUnproduced" to it)) }.toMutableMap()
        val raws = mutableMapOf("raw:target" to intent.target.originalBytes(),
            "raw:separation" to "{}".toByteArray(), "raw:preinstall" to "{}".toByteArray())
        val fileScope = r.obj("project" to "app", "sourceSha" to f.app, "prNumber" to 7, "prHeadSha" to f.app,
            "baseSha" to "f".repeat(40), "paths" to listOf("synthetic.kt"), "filesRawRef" to r.ref("raw:files", "[]".toByteArray()))
        val record = r.obj("backend" to "codex-independent", "reviewerId" to "synthetic-reviewer",
            "authorSessionId" to "review-session", "implementerSessionIds" to listOf("implementation-session"),
            "independenceEvidenceRef" to r.ref("raw:separation", raws.getValue("raw:separation")),
            "reviewClass" to "CODE_MERGE", "repository" to "peppone-choi/opensamguk", "prNumber" to 7,
            "headSourceSha" to f.app, "verdictCommentId" to 17, "verdict" to "MERGEABLE",
            "verdictRawRef" to r.ref("raw:verdict", "{}".toByteArray()), "reviewRawRefs" to emptyList<String>(),
            "windowId" to "synthetic-window", "windowStartsAtUnix" to 990, "windowEndsAtUnix" to 1010,
            "issuedAtUnix" to 1000, "windowOriginalRef" to r.ref("raw:window", "{}".toByteArray()),
            "fileScopes" to listOf(fileScope), "reviewedOriginals" to (fixed.mapValues { r.ref(it.key, it.value) } +
                mapOf("preinstallEvidence" to r.ref("raw:preinstall", raws.getValue("raw:preinstall")))))
        init {
            // ObjectMapper copies constructor nodes; keep the editable file scope in record.
            record.set<JsonNode>("fileScopes", f.mapper.createArrayNode().add(fileScope))
            commentBody(marker()); raw("window", window()); files(listOf("synthetic.kt"))
        }
        fun window() = r.obj("version" to 1, "windows" to listOf(r.obj("id" to "synthetic-window", "backend" to "codex",
            "authorization" to "user", "startsAt" to stamp(990), "endsAt" to stamp(1010))))
        fun commentBody(body: String) = raw("verdict", r.obj("id" to 17, "created_at" to stamp(1000), "body" to body))
        fun files(paths: List<String>) {
            val bytes = r.wire(f.mapper.valueToTree(paths.map { mapOf("filename" to it, "status" to "modified") }))
            raws["raw:files"] = bytes; fileScope.set<JsonNode>("filesRawRef", r.ref("raw:files", bytes))
        }
        fun raw(name: String, node: JsonNode) = rawBytes(name, r.wire(node))
        fun rawBytes(name: String, bytes: ByteArray) {
            raws["raw:$name"] = bytes
            record.set<JsonNode>(if (name == "window") "windowOriginalRef" else "verdictRawRef", r.ref("raw:$name", bytes))
        }
        fun verify() {
            val tree = f.intentTree()
            val scope = r.obj("operationId" to intent.operationId, "serverId" to "pep", "worldId" to 1,
                "targetFingerprint" to intent.targetFingerprint, "typedTargetRef" to r.ref("raw:target", intent.target.originalBytes()),
                "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40), "oldImageDigests" to intent.oldImageDigests,
                "newImageDigests" to intent.newImageDigests, "initialPublicRevision" to intent.initialPublicRevision.toString(),
                "window" to r.obj("windowOpensAtUnix" to intent.windowOpensAtUnix, "destructiveCutoffUnix" to intent.destructiveCutoffUnix,
                    "recoveryDeadlineUnix" to intent.recoveryDeadlineUnix), "spaceBudget" to tree["spaceBudget"])
            val basis = r.obj("schemaVersion" to 1, "kind" to "D101_REVIEW_BASIS_V1", "scope" to scope,
                "sourceAuthority" to "ACTUAL_INDEPENDENT_REVIEW_RECORDS", "records" to listOf(record))
            D101IndependentReviewOriginalCheck().verifyCodeMergeTuples(r.wire(basis), intent, "c".repeat(40), fixed, raws)
        }
    }

    companion object {
        private fun stamp(epoch: Long) = Instant.ofEpochSecond(epoch).toString()
        private fun marker(head: String = "b".repeat(40), verdict: String = "MERGEABLE", window: String = "synthetic-window", issued: Long = 999) =
            "<!-- pr-loop v1 codex-review-verdict sha=$head verdict=$verdict window=$window issued=${stamp(issued)} -->"
    }
}
