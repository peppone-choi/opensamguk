package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat

/** Published RFC8032 TEST1 key material only; never a generated/operating credential. */
internal class D101Fixture {
    val mapper = ObjectMapper()
    val json = D101StrictJson(mapper)
    val codec = D101ApprovalIntentCodec(json)
    val requestCodec = D101RequestCodec(json, codec)
    val operation = "a".repeat(32)
    val app = "b".repeat(40)
    val now = Instant.parse("2026-10-06T09:00:00Z").epochSecond
    val clock = MutableD101Clock(now)
    val publicDer = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val privateDer = hex("302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val pins = D101ApprovalIntentCodec.FIVE_IMAGES.associateWith { "sha256:" + "c".repeat(64) }

    fun intentTree(): ObjectNode {
        val updates = sortedMapOf(
            "SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0", "SCENARIO_CODE" to "scenario_3190",
            "SCENARIO_SEED_ENABLED" to "true", "RESET_MAXGENERAL" to "50", "RESET_FIRST_TURN" to "immediate",
            "SCENARIO_LOOKUP_DIR" to "", "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_TURNTERM" to "60",
            // Explicit synthetic input for the current legacy Root plan boundary.
            "RESET_EXTEND" to "1",
            "IMAGE_TAG" to app, "WEB_GAME_TAG" to app,
            "GAME_POSTGRES_IMAGE" to ("postgres:16-alpine@" + pins.getValue("game-postgres")),
            "GAME_REDIS_IMAGE" to ("redis:7-alpine@" + pins.getValue("game-redis")),
        )
        // Match existing Go struct field order and sorted map keys. No actual Root issuer.
        val target = linkedMapOf<String, Any>(
            "storageImageDigests" to pins.filterKeys { it in D101ApprovalIntentCodec.STORAGE_IMAGES }.toSortedMap(),
            "scenarioCode" to "scenario_3190", "generation" to 0, "scenarioSeedEnabled" to true, "updates" to updates,
            "imageDigests" to pins.filterKeys { it in D101ApprovalIntentCodec.APP_IMAGES }.toSortedMap(),
        )
        val targetWire = mapper.writeValueAsBytes(linkedMapOf("id" to "pep", "target" to target))
        val root = linkedMapOf<String, Any>(
            "schemaVersion" to 1, "serverId" to "pep", "worldId" to 1, "operationId" to operation, "serverName" to "빼섭",
            "targetFingerprint" to hash(targetWire), "rootTargetBytesBase64url" to b64(targetWire), "appSourceSha" to app,
            "oldImageDigests" to pins, "newImageDigests" to pins, "initialPublicRevision" to "1",
            "windowOpensAtUnix" to now - 1, "destructiveCutoffUnix" to now + 3600, "recoveryDeadlineUnix" to now + 7200,
            "approvalReceiptSha256" to "d".repeat(64), "combinedCiReceiptSha256" to "e".repeat(64),
            "selectedSourceReceiptSha256" to "f".repeat(64), "isolatedSeedTickReceiptSha256" to "1".repeat(64),
            "spaceInventoryReceiptSha256" to "2".repeat(64),
            "spaceBudget" to D101ApprovalIntentCodec.SPACE_KEYS.associateWith { 0 },
        )
        return mapper.valueToTree(root)
    }

    fun intent(tree: ObjectNode = intentTree()): D101ApprovalIntent {
        val wire = mapper.writeValueAsBytes(tree)
        return codec.decode(wire, hash(wire))
    }

    fun prepareBody(tree: ObjectNode = intentTree()): ByteArray {
        val wire = mapper.writeValueAsBytes(tree)
        return mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "approvalIntentSha256" to hash(wire), "approvalIntentBytesBase64url" to b64(wire),
        ))
    }

    fun request(action: D101PurposeAction = D101PurposeAction.PREPARE, tree: ObjectNode = intentTree()): D101PurposeRequest {
        val intent = intent(tree)
        val prepare = prepareBody(tree)
        val body = if (action == D101PurposeAction.QUERY) byteArrayOf() else if (action == D101PurposeAction.PREPARE) prepare else "{}".toByteArray()
        return D101PurposeRequest(action, operation, intent.targetFingerprint, intent.sha256, hash(prepare), 1,
            action.method, action.path(operation), body)
    }

    fun claims(request: D101PurposeRequest = request()): ObjectNode = mapper.valueToTree(linkedMapOf(
        "schemaVersion" to 1, "keyId" to "rfc8032-fixture", "issuer" to "opensamguk-root-issuer",
        "subject" to "opensamguk-d101-root-executor", "audience" to "opensamguk-gateway-d101-v1",
        "purpose" to "D101_PEP_RESET", "action" to request.action.name, "serverId" to "pep", "operationId" to operation,
        "targetFingerprint" to request.targetFingerprint, "approvalIntentSha256" to request.approvalIntentSha256,
        "gatewayPayloadSha256" to request.gatewayPayloadSha256, "initialPublicRevision" to "1",
        "method" to request.method, "path" to request.path, "requestBodySha256" to hash(request.body()),
        "issuedAtUnix" to now, "expiresAtUnix" to now + 60, "nonce" to "3".repeat(32),
    ))

    fun authority(tree: ObjectNode = intentTree(), der: ByteArray = publicDer) = D101PurposeAuthority {
        D101VerifiedPurposeAuthority("rfc8032-fixture", der, hash(der), "4".repeat(64), "5".repeat(64), "6".repeat(64), intent(tree))
    }

    fun verifier(source: D101PurposeAuthority = authority()) = D101PurposeGrantVerifier(json, source, clock)

    fun header(claims: ObjectNode = claims()): String = headerBytes(mapper.writeValueAsBytes(claims))
    fun headerBytes(wire: ByteArray, domain: ByteArray = D101PurposeGrantVerifier.DOMAIN): String =
        b64(wire) + "." + b64(sign(domain + wire))

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateDer)))
        update(message)
        sign()
    }

    companion object {
        fun hash(wire: ByteArray) = D101StrictJson.hash(wire)
        fun b64(wire: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
        fun hex(text: String) = HexFormat.of().parseHex(text)
    }
}

internal class MutableD101Clock(var epoch: Long) : Clock() {
    override fun instant(): Instant = Instant.ofEpochSecond(epoch)
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
