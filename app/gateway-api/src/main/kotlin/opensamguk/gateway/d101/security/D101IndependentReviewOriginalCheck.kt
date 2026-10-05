package opensamguk.gateway.d101.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101ApprovalIntent
import opensamguk.gateway.d101.domain.D101StrictJson
import java.time.Instant
import java.time.OffsetDateTime

/** Known CODE_MERGE tuples only. This authenticates no actor or independent session.
 * Existing PR-loop markers do not bind D101 preinstallation or its four originals.
 * D101_PREINSTALL_SCOPE therefore remains rejected until its actual producer contract
 * is supplied. This subcheck is not registered and cannot grant any authority.
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
        private val TOP_KEYS = setOf("schemaVersion", "kind", "scope", "sourceAuthority", "records")
        private val PATH = Regex("(?!/)[A-Za-z0-9_./@+\\-]{1,512}")
        private val CLAUDE_MARKER = Regex("<!--\\s*pr-loop v1 (claude-verdict|codex-reply|codex-merged) sha=([0-9a-f]{40})(?: verdict=(MERGEABLE|BLOCKED))?\\s*-->")
        private val CODEX_MARKER = Regex("<!--\\s*pr-loop v1 codex-review-verdict sha=([0-9a-f]{40}) verdict=(MERGEABLE|BLOCKED) window=([A-Za-z0-9_-]+) issued=([^\\s]+)\\s*-->")
    }
}
