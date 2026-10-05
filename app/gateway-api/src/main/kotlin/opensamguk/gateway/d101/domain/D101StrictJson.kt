package opensamguk.gateway.d101.domain

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.HexFormat

internal class D101StrictJson(objectMapper: ObjectMapper) {
    private val mapper = objectMapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun objectBytes(wire: ByteArray, exactKeys: Set<String>, limit: Int): JsonNode {
        if (wire.isEmpty() || wire.size > limit ||
            wire.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) invalid()
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(wire))
            return mapper.readTree(wire).also { requireKeys(it, exactKeys) }
        } catch (_: Exception) {
            invalid()
        }
    }

    fun requireKeys(node: JsonNode?, exactKeys: Set<String>) {
        if (node == null || !node.isObject ||
            node.fieldNames().asSequence().toSet() != exactKeys ||
            exactKeys.any { node[it] == null || node[it].isNull }) invalid()
    }

    fun text(node: JsonNode?, allowEmpty: Boolean = false): String {
        if (node == null || !node.isTextual || (!allowEmpty && node.textValue().isEmpty())) invalid()
        return node.textValue()
    }

    fun sha(node: JsonNode?): String = text(node).also { if (!SHA.matches(it)) invalid() }

    fun positiveLong(node: JsonNode?): Long {
        if (node == null || !node.isIntegralNumber || !node.canConvertToLong() || node.longValue() <= 0) invalid()
        return node.longValue()
    }

    fun revision(node: JsonNode?): Long {
        val value = text(node)
        if (!REVISION.matches(value)) invalid()
        return value.toLongOrNull()?.takeIf { it > 0 } ?: invalid()
    }

    fun unsigned(node: JsonNode?): BigInteger {
        if (node == null || !node.isIntegralNumber) invalid()
        return node.bigIntegerValue().also { if (it.signum() < 0 || it > UINT64_MAX) invalid() }
    }

    fun base64url(value: String, decodedLimit: Int): ByteArray {
        if (value.isEmpty() || value.length > (decodedLimit * 4 + 2) / 3 || !BASE64URL.matches(value)) invalid()
        return try {
            Base64.getUrlDecoder().decode(value).also {
                if (it.size > decodedLimit ||
                    Base64.getUrlEncoder().withoutPadding().encodeToString(it) != value) invalid()
            }
        } catch (_: Exception) {
            invalid()
        }
    }

    fun stringMap(node: JsonNode?, keys: Set<String>, digest: Boolean): Map<String, String> {
        requireKeys(node, keys)
        return Collections.unmodifiableMap(keys.associateWith { key ->
            text(node!![key], allowEmpty = !digest).also { if (digest && !DIGEST.matches(it)) invalid() }
        }.toSortedMap())
    }

    fun invalid(): Nothing = throw D101RequestInvalid()

    companion object {
        val SHA = Regex("[a-f0-9]{64}")
        val SHA40 = Regex("[a-f0-9]{40}")
        val OPERATION = Regex("[a-f0-9]{32}")
        val KEY_ID = Regex("[a-z0-9._-]{1,64}")
        val REVISION = Regex("[1-9][0-9]{0,18}")
        val DIGEST = Regex("sha256:[a-f0-9]{64}")
        private val BASE64URL = Regex("[A-Za-z0-9_-]+")
        val UINT64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        fun hash(wire: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire))
    }
}
