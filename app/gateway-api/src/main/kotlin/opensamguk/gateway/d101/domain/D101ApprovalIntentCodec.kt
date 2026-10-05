package opensamguk.gateway.d101.domain

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.util.Collections

/** No source/approval/VM observation is performed by this strict transport decoder. */
internal class D101ApprovalIntentCodec(private val json: D101StrictJson) {
    fun decode(original: ByteArray, expectedSha256: String): D101ApprovalIntent {
        val wire = original.copyOf()
        if (!D101StrictJson.SHA.matches(expectedSha256) || D101StrictJson.hash(wire) != expectedSha256) json.invalid()
        val root = json.objectBytes(wire, INTENT_KEYS, 32 * 1024)
        if (!root["schemaVersion"].isIntegralNumber || root["schemaVersion"].bigIntegerValue() != BigInteger.ONE ||
            !root["worldId"].isIntegralNumber || root["worldId"].bigIntegerValue() != BigInteger.ONE ||
            json.text(root["serverId"]) != "pep" || json.text(root["serverName"]) != "빼섭") json.invalid()
        val operation = json.text(root["operationId"])
        val app = json.text(root["appSourceSha"])
        if (!D101StrictJson.OPERATION.matches(operation) || !D101StrictJson.SHA40.matches(app)) json.invalid()
        val opens = json.positiveLong(root["windowOpensAtUnix"])
        val cutoff = json.positiveLong(root["destructiveCutoffUnix"])
        val deadline = json.positiveLong(root["recoveryDeadlineUnix"])
        if (opens >= cutoff || cutoff >= deadline) json.invalid()
        val targetFingerprint = json.sha(root["targetFingerprint"])
        val targetBytes = json.base64url(json.text(root["rootTargetBytesBase64url"]), 16 * 1024)
        if (D101StrictJson.hash(targetBytes) != targetFingerprint) json.invalid()
        val oldPins = json.stringMap(root["oldImageDigests"], FIVE_IMAGES, true)
        val newPins = json.stringMap(root["newImageDigests"], FIVE_IMAGES, true)
        val target = target(targetBytes, app, newPins)
        return D101ApprovalIntent(
            expectedSha256, operation, targetFingerprint, app, json.revision(root["initialPublicRevision"]),
            opens, cutoff, deadline, json.sha(root["approvalReceiptSha256"]), json.sha(root["combinedCiReceiptSha256"]),
            json.sha(root["selectedSourceReceiptSha256"]), json.sha(root["isolatedSeedTickReceiptSha256"]),
            json.sha(root["spaceInventoryReceiptSha256"]), oldPins, newPins, space(root["spaceBudget"]), target,
        )
    }

    private fun target(wire: ByteArray, app: String, pins: Map<String, String>): D101ResetTarget {
        val wrapper = json.objectBytes(wire, setOf("id", "target"), 16 * 1024)
        if (json.text(wrapper["id"]) != "pep") json.invalid()
        val target = wrapper["target"]
        json.requireKeys(target, TARGET_KEYS)
        if (json.text(target["scenarioCode"]) != "scenario_3190" ||
            !target["generation"].isIntegralNumber || target["generation"].bigIntegerValue() != BigInteger.ZERO ||
            !target["scenarioSeedEnabled"].isBoolean || !target["scenarioSeedEnabled"].booleanValue()) json.invalid()
        val images = json.stringMap(target["imageDigests"], APP_IMAGES, true)
        val storage = json.stringMap(target["storageImageDigests"], STORAGE_IMAGES, true)
        if (images.any { pins[it.key] != it.value } || storage.any { pins[it.key] != it.value }) json.invalid()
        val node = target["updates"]
        if (!node.isObject) json.invalid()
        val keys = node.fieldNames().asSequence().toSet()
        if (!UPDATE_KEYS.containsAll(keys) || !keys.containsAll(REQUIRED_UPDATES)) json.invalid()
        val updates = Collections.unmodifiableMap(keys.associateWith { key ->
            json.text(node[key], allowEmpty = key == "SCENARIO_LOOKUP_DIR").also {
                if (it != it.trim() || it.any { char -> Character.isISOControl(char) }) json.invalid()
            }
        }.toSortedMap())
        val fixed = mapOf(
            "SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0", "SCENARIO_CODE" to "scenario_3190",
            "SCENARIO_SEED_ENABLED" to "true", "RESET_MAXGENERAL" to "50", "RESET_FIRST_TURN" to "immediate",
            "SCENARIO_LOOKUP_DIR" to "", "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_TURNTERM" to "60",
            // Current legacy Root plan requires explicit 1; defaults are not evidence.
            // Executable plan v2 support remains a separate approved contract.
            "RESET_EXTEND" to "1",
            "IMAGE_TAG" to app, "WEB_GAME_TAG" to app,
            "GAME_POSTGRES_IMAGE" to ("postgres:16-alpine@" + storage.getValue("game-postgres")),
            "GAME_REDIS_IMAGE" to ("redis:7-alpine@" + storage.getValue("game-redis")),
        )
        if (fixed.any { updates[it.key] != it.value }) json.invalid()
        return D101ResetTarget(wire, images, storage, updates)
    }

    private fun space(node: JsonNode): D101SpaceBudget {
        json.requireKeys(node, SPACE_KEYS)
        val values = SPACE_KEYS.associateWith { json.unsigned(node[it]) }
        val bytes = values.getValue("CandidateUnpackedBytes") + values.getValue("BackupBytes") +
            values.getValue("RecoveryBytes") + values.getValue("TemporaryBytes") + BigInteger.valueOf(10L * 1024 * 1024 * 1024)
        val inodes = values.getValue("NewFileCount") + values.getValue("InodeReserve")
        if (bytes > D101StrictJson.UINT64_MAX || inodes > D101StrictJson.UINT64_MAX) json.invalid()
        return D101SpaceBudget(
            values.getValue("CandidateUnpackedBytes"), values.getValue("BackupBytes"), values.getValue("RecoveryBytes"),
            values.getValue("TemporaryBytes"), values.getValue("NewFileCount"), values.getValue("InodeReserve"),
        )
    }

    companion object {
        val APP_IMAGES = setOf("game-api", "game-engine", "web-game")
        val STORAGE_IMAGES = setOf("game-postgres", "game-redis")
        val FIVE_IMAGES = APP_IMAGES + STORAGE_IMAGES
        val INTENT_KEYS = setOf(
            "schemaVersion", "serverId", "worldId", "operationId", "serverName", "targetFingerprint",
            "rootTargetBytesBase64url", "appSourceSha", "oldImageDigests", "newImageDigests", "initialPublicRevision",
            "windowOpensAtUnix", "destructiveCutoffUnix", "recoveryDeadlineUnix", "approvalReceiptSha256",
            "combinedCiReceiptSha256", "selectedSourceReceiptSha256", "isolatedSeedTickReceiptSha256",
            "spaceInventoryReceiptSha256", "spaceBudget",
        )
        val SPACE_KEYS = setOf(
            "CandidateUnpackedBytes", "BackupBytes", "RecoveryBytes", "TemporaryBytes", "NewFileCount", "InodeReserve",
        )
        private val TARGET_KEYS = setOf(
            "storageImageDigests", "scenarioCode", "generation", "scenarioSeedEnabled", "updates", "imageDigests",
        )
        private val REQUIRED_UPDATES = setOf(
            "GAME_POSTGRES_IMAGE", "GAME_REDIS_IMAGE", "SERVER_NAME", "RESET_MAXGENERAL", "RESET_FIRST_TURN",
            "SCENARIO_LOOKUP_DIR", "IMAGE_TAG", "WEB_GAME_TAG", "SCENARIO_CODE", "SCENARIO_SEED_ENABLED",
            "SERVER_GENERATION", "RESET_BLOCK_GENERAL_CREATE", "RESET_TURNTERM", "RESET_EXTEND",
        )
        private val UPDATE_KEYS = REQUIRED_UPDATES + setOf(
            "RESET_SYNC", "RESET_FICTION", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL",
            "RESET_AUTORUN_USER_OPTIONS", "RESET_AUTORUN_USER_MINUTES", "RESET_JOIN_MODE", "RESET_TOURNAMENT_TRIG",
            "RESET_RESERVE_OPEN", "RESET_PRE_RESERVE_OPEN",
        )
    }
}
