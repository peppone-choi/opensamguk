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
        p.raw("verdict", p.f.mapper.valueToTree(mapOf("id" to 17, "created_at" to stamp(1010), "body" to marker(issued = 1009))))
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

/** Synthetic r5 binding data only; no real publisher, signer or origin authority. */
class D101PreinstallReviewOriginalCheckTest {
    @Test
    fun `delayed Codex publication binds both projects and permits another nonprimary row`() {
        val p = Packet(); val extra = p.scopeFile("app", 9, "extra.kt", "extra")
        p.scopes.add(extra); p.syncReport(); p.verify()
    }

    @Test
    fun `Claude publication uses its marker and report stamp with later actual publication`() {
        val p = Packet(); p.record.put("backend", "claude-independent"); p.syncReport(); p.verify()
    }

    @Test
    fun `publication fence is unique column zero LF with strict JSON`() {
        val p = Packet(); val body = p.body()
        for (bad in listOf(body.replace("```d101", " ```d101"), body.replace("publication\n", "publication\r\n"),
            body.replace("```d101-preinstall-publication", "```json"), body + body, body.replace("\n```\n", "\n``\n"),
            body.replace("\n```\n", " {}\n```\n"))) assertThrows<D101RequestInvalid> { p.verify(bad) }
    }

    @Test
    fun `publication exact fields type class session window verdict and scope digest bind record`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("extra", true) }, { it.remove("reportRef"); Unit }, { it.putNull("windowId") },
            { it.put("schemaVersion", 1.0) }, { it.put("reviewClass", "CODE_MERGE") },
            { it.put("authorSessionId", "other-reviewer") }, { it.put("windowId", "other-window") },
            { it.put("issuedAtUnix", 999) }, { it.put("verdict", "BLOCKED") }, { it.put("scopeSha256", "e".repeat(64)) },
        )
        for (change in changes) { val p = Packet(); change(p.publication); assertThrows<D101RequestInvalid> { p.verify() } }
    }

    @Test
    fun `dedicated backend marker rejects mixed duplicate incomplete and stale tuples`() {
        val p = Packet(); val body = p.body()
        for (bad in listOf(body.replace(p.f.app, "a".repeat(40)), body.replace("issued=${stamp(1000)}", "issued=${stamp(999)}"),
            body + p.marker(), body + "<!-- pr-loop v1 claude-verdict", body.replace(" verdict=MERGEABLE window=", " verdict=BLOCKED window="),
            body.replace("window=synthetic-window", "window=other-window"), body.replace(p.marker(), "MERGEABLE ${p.f.app} synthetic-window")))
            assertThrows<D101RequestInvalid> { p.verify(bad) }
    }

    @Test
    fun `actual report bytes SHA length media and exactly one reviewed reference bind publication`() {
        for (field in listOf("sha256", "byteLength", "mediaType")) {
            val p = Packet(); val ref = p.publication["reportRef"] as ObjectNode
            when (field) { "sha256" -> ref.put(field, "e".repeat(64)); "byteLength" -> ref.put(field, 1); else -> ref.put(field, "text/plain") }
            p.record.set<JsonNode>("reviewRawRefs", p.r.mapper.createArrayNode().add(ref))
            assertThrows<D101RequestInvalid> { p.verify() }
        }
        for (count in listOf(0, 2)) { val p = Packet(); val refs = p.r.mapper.createArrayNode()
            repeat(count) { refs.add(p.publication["reportRef"]) }; p.record.set<JsonNode>("reviewRawRefs", refs)
            assertThrows<D101RequestInvalid> { p.verify() } }
        val drift = Packet(); drift.raws["raw:report"] = "{}".toByteArray()
        assertThrows<D101RequestInvalid> { drift.verify() }
    }

    @Test
    fun `rebound report cannot change scope or any record binding despite a correct publication`() {
        for (field in Packet.REPORT_FIELDS + "scope") { val p = Packet()
            p.report.set<JsonNode>(field, p.r.mapper.valueToTree("changed")); p.bindReport()
            assertThrows<D101RequestInvalid> { p.verify() } }
        for (change in listOf<(ObjectNode) -> Unit>({ it.put("extra", true) }, { it.remove("findingsRef"); Unit },
            { it.put("schemaVersion", 1.0) }, { it.put("kind", "CODE_MERGE") }, { it.put("reviewClass", "CODE_MERGE") })) {
            val p = Packet(); change(p.report); p.bindReport(); assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `logical review issued differs from publication but both remain ordered inside old window`() {
        for (published in listOf(999L, 1010L)) { val p = Packet(); assertThrows<D101RequestInvalid> { p.verify(published = published) } }
        val p = Packet(); val fractional = p.body().replace("issued=${stamp(1000)}", "issued=1970-01-01T00:16:40.001Z")
        assertThrows<D101RequestInvalid> { p.verify(fractional) }
    }

    @Test
    fun `both project sources and exactly one primary row are required without forcing same PR`() {
        val missing = Packet(); missing.scopes.remove(1); missing.syncReport(); assertThrows<D101RequestInvalid> { missing.verify() }
        val duplicate = Packet(); duplicate.scopes.add(duplicate.scopes[0].deepCopy<JsonNode>()); duplicate.syncReport()
        assertThrows<D101RequestInvalid> { duplicate.verify() }
        val stale = Packet(); (stale.scopes[1] as ObjectNode).put("sourceSha", "e".repeat(40)); stale.syncReport()
        assertThrows<D101RequestInvalid> { stale.verify() }
        val wrong = Packet(); wrong.record.put("repository", "peppone-choi/opensamguk-docker"); wrong.syncReport()
        assertThrows<D101RequestInvalid> { wrong.verify() }
    }

    @Test
    fun `rebound actual filename array must exactly match canonical unique declared paths`() {
        for (names in listOf(listOf("other.kt"), listOf("app.kt", "app.kt"), listOf("./app.kt"), listOf("dir//app.kt"))) {
            val p = Packet(); p.files(p.scopes[0] as ObjectNode, "app", names); p.syncReport()
            assertThrows<D101RequestInvalid> { p.verify() }
        }
        val wrapper = Packet(); val bytes = wrapper.r.wire(wrapper.r.obj("files" to listOf(mapOf("filename" to "app.kt"))))
        wrapper.raws["raw:files-app"] = bytes
        (wrapper.scopes[0] as ObjectNode).set<JsonNode>("filesRawRef", wrapper.r.ref("raw:files-app", bytes)); wrapper.syncReport()
        assertThrows<D101RequestInvalid> { wrapper.verify() }
    }

    @Test
    fun `frozen four preinstall and findings bytes must exist and match their whole references`() {
        for (id in listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings")) {
            val p = Packet(); p.fixed[id] = "{}".toByteArray(); assertThrows<D101RequestInvalid> { p.verify() }
        }
        for (id in listOf("raw:preinstall", "raw:findings", "raw:separation")) {
            val p = Packet(); p.raws.remove(id); assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `malformed actual report JSON rejects even when its reference SHA and length are rebound`() {
        for (bytes in listOf("{} {}".toByteArray(), "{\"kind\":1,\"kind\":2}".toByteArray(), "null".toByteArray(),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "{}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            val p = Packet(); p.bindReportBytes(bytes); assertThrows<D101RequestInvalid> { p.verify() }
        }
    }

    @Test
    fun `separate methods do not relabel CODE as PREINSTALL or PREINSTALL as CODE`() {
        val p = Packet(); p.record.put("reviewClass", "CODE_MERGE"); p.syncReport()
        assertThrows<D101RequestInvalid> { p.verify() }
        val preinstall = Packet(); assertThrows<D101RequestInvalid> {
            D101IndependentReviewOriginalCheck().verifyCodeMergeTuples(preinstall.wire(), preinstall.intent, "c".repeat(40), preinstall.fixed, preinstall.raws)
        }
    }

    @Test
    fun `rebound raw preinstall evidence cannot hide a descendant card inside the report closure`() {
        val p = Packet(); val bytes = p.r.wire(p.r.obj("kind" to "D101_PEP_EXECUTION_CARD")); p.raws["raw:preinstall"] = bytes
        (p.record["reviewedOriginals"] as ObjectNode).set<JsonNode>("preinstallEvidence", p.r.ref("raw:preinstall", bytes)); p.syncReport()
        assertThrows<D101RequestInvalid> { p.verify() }
    }

    private class Packet {
        val f = D101Fixture(); val r = D101RawEvidenceFixture(); val intent = f.intent()
        val fixed = listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings")
            .associateWith { r.wire(r.obj("syntheticUnproduced" to it)) }.toMutableMap()
        val raws = mutableMapOf("raw:target" to intent.target.originalBytes(), "raw:separation" to "{}".toByteArray(),
            "raw:preinstall" to "{}".toByteArray(), "raw:findings" to "{}".toByteArray())
        val scope = r.obj("operationId" to intent.operationId, "serverId" to "pep", "worldId" to 1,
            "targetFingerprint" to intent.targetFingerprint, "typedTargetRef" to r.ref("raw:target", intent.target.originalBytes()),
            "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40), "oldImageDigests" to intent.oldImageDigests,
            "newImageDigests" to intent.newImageDigests, "initialPublicRevision" to intent.initialPublicRevision.toString(),
            "window" to r.obj("windowOpensAtUnix" to intent.windowOpensAtUnix, "destructiveCutoffUnix" to intent.destructiveCutoffUnix,
                "recoveryDeadlineUnix" to intent.recoveryDeadlineUnix), "spaceBudget" to f.intentTree()["spaceBudget"])
        val record = r.obj("backend" to "codex-independent", "reviewerId" to "synthetic-reviewer", "authorSessionId" to "review-session",
            "implementerSessionIds" to listOf("implementation-session"), "independenceEvidenceRef" to r.ref("raw:separation", raws.getValue("raw:separation")),
            "reviewClass" to "D101_PREINSTALL_SCOPE", "repository" to "peppone-choi/opensamguk", "prNumber" to 7, "headSourceSha" to f.app,
            "verdictCommentId" to 17, "verdict" to "MERGEABLE", "verdictRawRef" to r.ref("raw:verdict", "{}".toByteArray()),
            "reviewRawRefs" to emptyList<String>(), "windowId" to "synthetic-window", "windowStartsAtUnix" to 990, "windowEndsAtUnix" to 1010,
            "issuedAtUnix" to 1000, "windowOriginalRef" to r.ref("raw:window", "{}".toByteArray()), "fileScopes" to emptyList<String>(),
            "reviewedOriginals" to (fixed.mapValues { r.ref(it.key, it.value) } + mapOf("preinstallEvidence" to r.ref("raw:preinstall", raws.getValue("raw:preinstall")))))
        val scopes get() = record["fileScopes"] as com.fasterxml.jackson.databind.node.ArrayNode
        lateinit var report: ObjectNode
        val publication = r.obj("schemaVersion" to 1, "kind" to "D101_PREINSTALL_VERDICT_BINDING_V1", "reviewClass" to "D101_PREINSTALL_SCOPE",
            "reportRef" to r.ref("raw:report", "{}".toByteArray()), "authorSessionId" to "review-session", "windowId" to "synthetic-window",
            "issuedAtUnix" to 1000, "scopeSha256" to scopeHash(), "verdict" to "MERGEABLE")
        init {
            val window = r.wire(r.obj("version" to 1, "windows" to listOf(r.obj("id" to "synthetic-window", "backend" to "codex",
                "authorization" to "user", "startsAt" to stamp(990), "endsAt" to stamp(1010)))))
            raws["raw:window"] = window; record.set<JsonNode>("windowOriginalRef", r.ref("raw:window", window))
            record.set<JsonNode>("fileScopes", r.mapper.createArrayNode().add(scopeFile("app", 7, "app.kt", "app"))
                .add(scopeFile("docker", 8, "docker.go", "docker"))); syncReport()
        }
        fun scopeFile(project: String, pr: Long, path: String, id: String): ObjectNode {
            val source = if (project == "app") f.app else "c".repeat(40)
            return r.obj("project" to project, "sourceSha" to source, "prNumber" to pr, "prHeadSha" to source,
                "baseSha" to "f".repeat(40), "paths" to listOf(path), "filesRawRef" to r.ref("raw:files-$id", "[]".toByteArray()))
                .also { files(it, id, listOf(path)) }
        }
        fun files(file: ObjectNode, id: String, names: List<String>) {
            val bytes = r.wire(r.mapper.valueToTree(names.map { mapOf("filename" to it, "status" to "modified") }))
            raws["raw:files-$id"] = bytes; file.set<JsonNode>("filesRawRef", r.ref("raw:files-$id", bytes))
        }
        fun syncReport() {
            report = r.obj("schemaVersion" to 1, "kind" to "D101_INDEPENDENT_PREINSTALL_REPORT_V1", "reviewClass" to "D101_PREINSTALL_SCOPE",
                "scope" to scope, "findingsRef" to r.ref("raw:findings", raws.getValue("raw:findings")))
            for (field in REPORT_FIELDS) report.set<JsonNode>(field, record[field].deepCopy<JsonNode>())
            bindReport()
        }
        fun bindReport() = bindReportBytes(r.wire(report))
        fun bindReportBytes(bytes: ByteArray) {
            raws["raw:report"] = bytes; val ref = r.ref("raw:report", bytes); publication.set<JsonNode>("reportRef", ref)
            record.set<JsonNode>("reviewRawRefs", r.mapper.createArrayNode().add(ref))
        }
        fun marker() = if (record["backend"].textValue() == "claude-independent")
            "<!-- pr-loop v1 claude-verdict sha=${f.app} verdict=MERGEABLE -->" else
            "<!-- pr-loop v1 codex-review-verdict sha=${f.app} verdict=MERGEABLE window=synthetic-window issued=${stamp(1000)} -->"
        fun body() = "```d101-preinstall-publication\n${r.wire(publication).toString(Charsets.UTF_8)}\n```\n${marker()}"
        fun wire(body: String? = null, published: Long = 1005): ByteArray {
            val verdict = r.wire(r.obj("id" to 17, "created_at" to stamp(published), "body" to (body ?: this.body())))
            raws["raw:verdict"] = verdict; record.set<JsonNode>("verdictRawRef", r.ref("raw:verdict", verdict))
            return r.wire(r.obj("schemaVersion" to 1, "kind" to "D101_REVIEW_BASIS_V1", "scope" to scope,
                "sourceAuthority" to "ACTUAL_INDEPENDENT_REVIEW_RECORDS", "records" to listOf(record)))
        }
        fun verify(body: String? = null, published: Long = 1005) = D101IndependentReviewOriginalCheck()
            .verifyPreinstallTuples(wire(body, published), intent, "c".repeat(40), fixed, raws)
        private fun scopeHash(): String {
            fun ordered(value: Any?): Any? = when (value) {
                is Map<*, *> -> java.util.TreeMap<String, Any?>().also { out -> value.forEach { (key, child) -> out[key as String] = ordered(child) } }
                is List<*> -> value.map { ordered(it) }; else -> value
            }
            return opensamguk.gateway.d101.domain.D101StrictJson.hash(r.mapper.writeValueAsBytes(ordered(r.mapper.convertValue(scope, Map::class.java))))
        }
        companion object {
            val REPORT_FIELDS = setOf("backend", "reviewerId", "authorSessionId", "implementerSessionIds", "independenceEvidenceRef",
                "windowId", "windowStartsAtUnix", "windowEndsAtUnix", "windowOriginalRef", "issuedAtUnix", "fileScopes", "reviewedOriginals", "verdict")
        }
    }
    companion object { private fun stamp(epoch: Long) = Instant.ofEpochSecond(epoch).toString() }
}
