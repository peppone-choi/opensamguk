package opensamguk.gateway.d101.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import opensamguk.gateway.d101.domain.D101ApprovalIntent
import opensamguk.gateway.d101.domain.D101StrictJson
import java.time.Instant
import java.time.OffsetDateTime

/** Separate CODE_MERGE and r5 PREINSTALL format bindings only.
 * Neither subcheck authenticates actors, independent sessions, official origins or
 * native custody. Neither is registered or capable of granting any authority.
 * CODE markers alone cannot satisfy the separate PREINSTALL report/publication.
 */
internal class D101IndependentReviewOriginalCheck(private val mapper: ObjectMapper = ObjectMapper()) {
    private val json = D101StrictJson(mapper)
    private val refs = D101Original6ScopeCheck(json)
    private val parser = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun verifyCodeMergeTuples(original: ByteArray, intent: D101ApprovalIntent, dockerSourceSha: String,
                             fixedOriginals: Map<String, ByteArray>, rawOriginals: Map<String, ByteArray>) {
        D101Original6HeaderChecks(json).verify("reviewBasis", original, intent, dockerSourceSha, fixedOriginals)
        val root = json.objectBytes(original, TOP_KEYS, 64 * 1024)
        for (record in root["records"]) {
            if (json.text(record["reviewClass"]) != "CODE_MERGE") json.invalid()
            val verdict = rawJson(record["verdictRawRef"], rawOriginals)
            if (!verdict.isObject || json.positiveLong(verdict["id"]) != json.positiveLong(record["verdictCommentId"])) json.invalid()
            val published = instant(verdict["created_at"])
            if (published != Instant.ofEpochSecond(json.positiveLong(record["issuedAtUnix"]))) json.invalid()
            val window = window(record, rawOriginals)
            marker(record, json.text(verdict["body"]), published, window)
            files(record, rawOriginals)
            // HeaderChecks binds the four named references to current frozen bytes.
            // DAG additionally checks every supplied raw reference, including opaque
            // separation/preinstallation evidence, without inferring their authority.
        }
        D101RawEvidenceDagCheck(mapper).verify("reviewBasis", original, rawOriginals, fixedOriginals)
    }

    fun verifyPreinstallTuples(original: ByteArray, intent: D101ApprovalIntent, dockerSourceSha: String,
                              fixedOriginals: Map<String, ByteArray>, rawOriginals: Map<String, ByteArray>) {
        val frozen = original.copyOf()
        D101Original6HeaderChecks(json).verify("reviewBasis", frozen, intent, dockerSourceSha, fixedOriginals)
        val root = json.objectBytes(frozen, TOP_KEYS, 64 * 1024)
        for (record in root["records"]) {
            if (json.text(record["reviewClass"]) != PREINSTALL_CLASS) json.invalid()
            val verdict = rawJson(record["verdictRawRef"], rawOriginals)
            if (!verdict.isObject || json.positiveLong(verdict["id"]) != json.positiveLong(record["verdictCommentId"])) json.invalid()
            val published = utc(verdict["created_at"])
            val issued = Instant.ofEpochSecond(json.positiveLong(record["issuedAtUnix"]))
            val window = window(record, rawOriginals)
            if (issued < window.start || issued > published || published >= window.end) json.invalid()
            val body = json.text(verdict["body"])
            val publication = publication(body, record, root["scope"])
            preinstallMarker(body, record, published, issued, window)
            preinstallReport(publication, record, root["scope"], fixedOriginals, rawOriginals)
            preinstallFiles(record, rawOriginals)
        }
        D101RawEvidenceDagCheck(mapper).verify("reviewBasis", frozen, rawOriginals, fixedOriginals)
        // Success is a pure format binding, never an authenticated PREINSTALL verdict.
    }

    private fun publication(body: String, record: JsonNode, scope: JsonNode): JsonNode {
        val opening = body.indexOf(FENCE_OPEN)
        if (opening < 0 || body.indexOf(FENCE_OPEN, opening + FENCE_OPEN.length) >= 0) json.invalid()
        val frames = PUBLICATION_FENCE.findAll(body).toList()
        if (frames.size != 1) json.invalid()
        val node = json.objectBytes(frames.single().groupValues[1].toByteArray(Charsets.UTF_8), PUBLICATION_KEYS, 64 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L ||
            json.text(node["kind"]) != "D101_PREINSTALL_VERDICT_BINDING_V1" ||
            json.text(node["reviewClass"]) != PREINSTALL_CLASS ||
            json.positiveLong(node["issuedAtUnix"]) != json.positiveLong(record["issuedAtUnix"]) ||
            PUBLICATION_BINDINGS.any { node[it] != record[it] } ||
            json.sha(node["scopeSha256"]) != D101StrictJson.hash(mapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(canonical(scope)))) json.invalid()
        val ref = refs.ref(node["reportRef"])
        if (!ref.logicalId.startsWith("raw:") || ref.mediaType != "application/json" ||
            record["reviewRawRefs"].count { it == node["reportRef"] } != 1) json.invalid()
        return node
    }

    private fun preinstallMarker(body: String, record: JsonNode, published: Instant, issued: Instant, window: Window) {
        if (VERDICT_START.findAll(body).count() != 1) json.invalid()
        marker(record, body, published, window)
        if (json.text(record["backend"]) == "codex-independent" &&
            utc(mapper.nodeFactory.textNode(CODEX_MARKER.findAll(body).single().groupValues[4])) != issued) json.invalid()
    }

    private fun preinstallReport(publication: JsonNode, record: JsonNode, scope: JsonNode,
                                 fixedOriginals: Map<String, ByteArray>, rawOriginals: Map<String, ByteArray>) {
        val report = rawJson(publication["reportRef"], rawOriginals)
        json.requireKeys(report, REPORT_KEYS)
        if (json.positiveLong(report["schemaVersion"]) != 1L ||
            json.text(report["kind"]) != "D101_INDEPENDENT_PREINSTALL_REPORT_V1" ||
            json.text(report["reviewClass"]) != PREINSTALL_CLASS) json.invalid()
        if (report["scope"] != scope || REPORT_BINDINGS.any { report[it] != record[it] }) json.invalid()
        val findings = refs.ref(report["findingsRef"])
        val bytes = if (findings.logicalId.startsWith("raw:")) rawOriginals[findings.logicalId]
            else fixedOriginals[findings.logicalId]
        D101RawEvidenceBinding.bind(report["findingsRef"], bytes ?: json.invalid(), mapper)
        // Signature, origin, session separation and findings meaning require the
        // actual fixed producer/verifier. Reference labels cannot provide them.
    }

    private fun preinstallFiles(record: JsonNode, rawOriginals: Map<String, ByteArray>) {
        val scopes = record["fileScopes"]
        if (scopes.map { json.text(it["project"]) }.toSet() != setOf("app", "docker")) json.invalid()
        val primary = if (json.text(record["repository"]) == "peppone-choi/opensamguk-docker") "docker" else "app"
        if (scopes.count { json.text(it["project"]) == primary && it["prNumber"] == record["prNumber"] &&
                it["prHeadSha"] == record["headSourceSha"] } != 1) json.invalid()
        for (scope in scopes) {
            // HeaderChecks already binds each project's source/prHead to final scope.
            val expected = scope["paths"].map { path(it) }
            val actual = rawJson(scope["filesRawRef"], rawOriginals)
            if (!actual.isArray || actual.size() !in 1..4096) json.invalid()
            val names = actual.map { if (!it.isObject) json.invalid(); path(it["filename"]) }
            if (names.size != names.toSet().size || names.size != expected.size || names.toSet() != expected.toSet()) json.invalid()
        }
        // The existing 1..8 row bound remains; a two-project set is not a two-row cap.
    }

    private fun canonical(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { out ->
            for (key in node.fieldNames().asSequence().sorted()) out.set<JsonNode>(key, canonical(node[key]))
        }
        node.isArray -> mapper.createArrayNode().also { out -> for (child in node) out.add(canonical(child)) }
        else -> node.deepCopy<JsonNode>()
    }

    private fun utc(node: JsonNode?): Instant {
        val value = json.text(node)
        if (!value.endsWith("Z")) json.invalid()
        return instant(value)
    }

    private fun window(record: JsonNode, rawOriginals: Map<String, ByteArray>): Window {
        val raw = rawJson(record["windowOriginalRef"], rawOriginals)
        if (!raw.isObject || json.positiveLong(raw["version"]) != 1L || raw["windows"]?.isArray != true) json.invalid()
        val id = json.text(record["windowId"])
        val matches = raw["windows"].filter { it.isObject && it["id"]?.isTextual == true && it["id"].textValue() == id }
        if (matches.size != 1) json.invalid()
        val found = matches.single()
        val start = instant(found["startsAt"])
        val end = instant(found["endsAt"])
        if (start != Instant.ofEpochSecond(json.positiveLong(record["windowStartsAtUnix"])) ||
            end != Instant.ofEpochSecond(json.positiveLong(record["windowEndsAtUnix"])) || start >= end) json.invalid()
        if (json.text(record["backend"]) == "codex-independent" &&
            (json.text(found["backend"]) != "codex" || json.text(found["authorization"]) != "user")) json.invalid()
        return Window(start, end)
    }

    private fun marker(record: JsonNode, body: String, published: Instant, window: Window) {
        val head = json.text(record["headSourceSha"])
        val claude = CLAUDE_MARKER.findAll(body).filter { it.groupValues[1] == "claude-verdict" }.toList()
        val codex = CODEX_MARKER.findAll(body).toList()
        when (json.text(record["backend"])) {
            "claude-independent" -> {
                if (claude.size != 1 || codex.isNotEmpty() || claude.single().groupValues[2] != head ||
                    claude.single().groupValues[3] != "MERGEABLE") json.invalid()
            }
            "codex-independent" -> {
                if (codex.size != 1 || claude.isNotEmpty()) json.invalid()
                val match = codex.single().groupValues
                if (match[1] != head || match[2] != "MERGEABLE" || match[3] != json.text(record["windowId"])) json.invalid()
                val issued = instant(match[4])
                // Match existing valid_codex_verdict: both issue/publication in the
                // original half-open window, issue <= publication. No current-now
                // expiry test invalidates an otherwise unchanged historical verdict.
                if (issued < window.start || issued >= window.end || issued > published) json.invalid()
            }
            else -> json.invalid()
        }
        if (published < window.start || published >= window.end) json.invalid()
    }

    private fun files(record: JsonNode, rawOriginals: Map<String, ByteArray>) {
        for (scope in record["fileScopes"]) {
            if (json.positiveLong(scope["prNumber"]) != json.positiveLong(record["prNumber"]) ||
                json.text(scope["prHeadSha"]) != json.text(record["headSourceSha"])) json.invalid()
            val expected = scope["paths"].map { path(it) }
            val actual = rawJson(scope["filesRawRef"], rawOriginals)
            if (!actual.isArray || actual.size() !in 1..4096) json.invalid()
            val names = actual.map { if (!it.isObject) json.invalid(); path(it["filename"]) }
            if (names.size != names.toSet().size || expected.size != expected.toSet().size ||
                names.toSet() != expected.toSet()) json.invalid()
            // GitHub filenames alone cannot prove a complete page set, PR base/head,
            // checkout or official origin; those actual source proofs stay denied.
        }
    }

    private fun path(node: JsonNode?): String = json.text(node).also {
        if (!PATH.matches(it) || it.split('/').any { part -> part.isEmpty() || part == "." || part == ".." }) json.invalid()
    }

    private fun rawJson(reference: JsonNode, originals: Map<String, ByteArray>): JsonNode {
        val ref = refs.ref(reference)
        if (!ref.logicalId.startsWith("raw:") || ref.mediaType != "application/json") json.invalid()
        val bytes = D101RawEvidenceBinding.bind(reference, originals[ref.logicalId] ?: json.invalid(), mapper).originalBytes()
        if (bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) json.invalid()
        try {
            D101RawEvidenceBinding.requireUtf8(bytes)
            return parser.readTree(bytes) ?: json.invalid()
        } catch (_: Exception) { json.invalid() }
    }

    private fun instant(node: JsonNode?): Instant = instant(json.text(node))
    private fun instant(value: String): Instant = try { OffsetDateTime.parse(value).toInstant() }
        catch (_: Exception) { json.invalid() }
    private data class Window(val start: Instant, val end: Instant)

    companion object {
        private const val PREINSTALL_CLASS = "D101_PREINSTALL_SCOPE"
        private const val FENCE_OPEN = "```d101-preinstall-publication"
        private val PUBLICATION_FENCE = Regex("^```d101-preinstall-publication\n(.*?)\n```(?=\n|$)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        private val VERDICT_START = Regex("<!--\\s*pr-loop v1 (?:claude-verdict|codex-review-verdict)\\b")
        private val PUBLICATION_BINDINGS = setOf("authorSessionId", "windowId", "verdict")
        private val PUBLICATION_KEYS = setOf("schemaVersion", "kind", "reviewClass", "reportRef", "authorSessionId",
            "windowId", "issuedAtUnix", "scopeSha256", "verdict")
        private val REPORT_BINDINGS = setOf("backend", "reviewerId", "authorSessionId", "implementerSessionIds",
            "independenceEvidenceRef", "windowId", "windowStartsAtUnix", "windowEndsAtUnix", "windowOriginalRef",
            "issuedAtUnix", "fileScopes", "reviewedOriginals", "verdict")
        private val REPORT_KEYS = REPORT_BINDINGS + setOf("schemaVersion", "kind", "reviewClass", "scope", "findingsRef")
        private val TOP_KEYS = setOf("schemaVersion", "kind", "scope", "sourceAuthority", "records")
        private val PATH = Regex("(?!/)[A-Za-z0-9_./@+\\-]{1,512}")
        private val CLAUDE_MARKER = Regex("<!--\\s*pr-loop v1 (claude-verdict|codex-reply|codex-merged) sha=([0-9a-f]{40})(?: verdict=(MERGEABLE|BLOCKED))?\\s*-->")
        private val CODEX_MARKER = Regex("<!--\\s*pr-loop v1 codex-review-verdict sha=([0-9a-f]{40}) verdict=(MERGEABLE|BLOCKED) window=([A-Za-z0-9_-]+) issued=([^\\s]+)\\s*-->")
    }
}
