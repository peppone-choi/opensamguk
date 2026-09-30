package opensamguk.engine.campaign

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Explicitly enabled only in the isolated QA world. Each committed encounter has one atomic file. */
class QaBattleOutcomeFileSink(
    directory: Path,
    private val objectMapper: ObjectMapper,
) : BattleOutcomeBatchSink {
    private val files = AtomicEncounterFileWriter(directory)

    override fun publish(batch: CommittedBattleOutcomeBatch) {
        for (observation in batch.observations) {
            val record = linkedMapOf<String, Any?>(
                "schemaVersion" to "qa-committed-battle-v1",
                "worldId" to batch.worldId,
                "generation" to batch.generation,
                "encounterId" to observation.encounterId,
                "resolvedYear" to observation.resolvedYear,
                "resolvedMonth" to observation.resolvedMonth,
                "resolvedPhase" to observation.resolvedPhase,
                "provinceId" to observation.provinceId,
                "approachProvinceId" to observation.approachProvinceId,
                "worldMapVariant" to observation.worldMapVariant,
                "topologyRevision" to observation.topologyRevision,
                "topologyHash" to observation.topologyHash,
                "tilesContentHash" to observation.tilesContentHash,
                "deploymentRuleVersion" to observation.deploymentRuleVersion,
                "layoutRuleVersion" to observation.layoutRuleVersion,
                "geometryRuleVersion" to observation.geometryRuleVersion,
                "resolutionRuleVersion" to observation.resolutionRuleVersion,
                "initialSeparationSteps" to observation.initialSeparationSteps,
                "outcome" to observation.outcome,
                "winners" to observation.winners,
                "statuses" to observation.statuses.sortedBy { it.generalId }.map { linkedMapOf(
                    "generalId" to it.generalId, "status" to it.status) },
                "barrier" to observation.barrier,
                "rounds" to observation.rounds,
                "replayHash" to observation.replayHash,
                "callbackInvoked" to observation.callbackInvoked,
            )
            files.write(batch.worldId, observation.encounterId, objectMapper.writeValueAsBytes(record))
        }
    }
}

/** A retry may repeat a committed batch, including after a partial multi-encounter publish. */
internal class AtomicEncounterFileWriter(private val directory: Path) {
    init {
        require(directory.isAbsolute) { "QA battle output directory must be absolute" }
        Files.createDirectories(directory)
    }

    fun write(worldId: Int, encounterId: String, bytes: ByteArray) {
        require(worldId > 0 && encounterId.isNotBlank() && bytes.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encounterId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val target = directory.resolve("battle-$worldId-$digest.json")
        val temporary = Files.createTempFile(directory, ".battle-pending-", ".json")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val content = ByteBuffer.wrap(bytes)
                while (content.hasRemaining()) channel.write(content)
                channel.force(true)
            }
            try {
                Files.createLink(target, temporary)
            } catch (_: FileAlreadyExistsException) {
                check(Files.readAllBytes(target).contentEquals(bytes)) {
                    "committed battle file differs on retry for world $worldId and encounter $encounterId"
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
