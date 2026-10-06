package opensamguk.logic.battle.realtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** One canonical, versioned binary representation for campaign battle source facts. */
object CampaignBattleOriginCodec {
    private const val MAGIC = 0x43424f31 // CBO1
    private const val VERSION = 1
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_ROWS = 100_000

    fun encode(value: CampaignBattleOriginSnapshot): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(value.worldId)
            out.text(value.battleId)
            out.text(value.encounterId)
            out.text(value.forcesSnapshotId)
            out.writeLong(value.committedWorldVersion)
            out.writeLong(value.writerEpoch)
            out.text(value.topologyRevision)
            out.text(value.topologyHash)
            out.text(value.provinceKey)
            out.text(value.approachKey)
            out.text(value.tilesHash)
            out.writeLong(value.lockGeneration)
            out.writeLong(value.lockSetRevision)
            out.participant(value.attacker)
            out.writeInt(value.defenders.size)
            value.defenders.forEach { out.participant(it) }
            out.writeInt(value.units.size)
            value.units.forEach { unit ->
                out.key(unit.sourceKey)
                out.text(unit.side.name)
                out.writeInt(unit.ownerGeneralId)
                out.writeInt(unit.commanderGeneralId)
                out.writeInt(unit.crewTypeId)
                out.writeInt(unit.troops)
                out.writeInt(unit.training)
                out.writeInt(unit.morale)
                out.writeInt(unit.fatigue)
                out.writeInt(unit.provisions)
                out.nullableInt(unit.commanderRetainerId)
                out.writeLong(unit.sourceRevision)
            }
            out.writeInt(value.owners.size)
            value.owners.forEach { owner ->
                out.writeInt(owner.ownerGeneralId)
                out.nullableInt(owner.accountId)
                out.writeInt(owner.npcState)
                out.writeBoolean(owner.playable)
                out.text(owner.side.name)
                out.writeInt(owner.controlledSourceKeys.size)
                owner.controlledSourceKeys.forEach { out.key(it) }
                out.writeLong(owner.authorityRevision)
                out.text(owner.authorityStatus.name)
            }
        }
        require(buffer.size() <= MAX_BYTES) { "Origin exceeds binary limit" }
    }.toByteArray()

    fun decode(bytes: ByteArray): CampaignBattleOriginSnapshot {
        require(bytes.size in 8..MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        val worldId = input.readInt()
        val battleId = input.text()
        val encounterId = input.text()
        val forcesSnapshotId = input.text()
        val committedWorldVersion = input.readLong()
        val writerEpoch = input.readLong()
        val topologyRevision = input.text()
        val topologyHash = input.text()
        val provinceKey = input.text()
        val approachKey = input.text()
        val tilesHash = input.text()
        val lockGeneration = input.readLong()
        val lockSetRevision = input.readLong()
        val attacker = input.participant()
        val defenders = List(input.count()) { input.participant() }
        val units = List(input.count()) {
            CampaignBattleOriginUnit(input.key(), input.enum(), input.readInt(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readInt(), input.nullableInt(), input.readLong())
        }
        val owners = List(input.count()) {
            val ownerGeneralId = input.readInt()
            val accountId = input.nullableInt()
            val npcState = input.readInt()
            val playable = input.readBoolean()
            val side: BattleSide = input.enum()
            val keys = List(input.count()) { input.key() }
            val revision = input.readLong()
            val status: CampaignBattleAuthorityStatus = input.enum()
            CampaignBattleOriginOwner(ownerGeneralId, accountId, npcState, playable, side,
                keys, revision, status)
        }
        require(input.available() == 0) { "Trailing origin bytes" }
        val value = CampaignBattleOriginSnapshot(worldId, battleId, encounterId, forcesSnapshotId,
            committedWorldVersion, writerEpoch, topologyRevision, topologyHash, provinceKey,
            approachKey, tilesHash, lockGeneration, lockSetRevision, attacker, defenders, units, owners)
        require(encode(value).contentEquals(bytes)) { "Noncanonical origin bytes" }
        return value
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun controlledSourceSha256(
        owner: CampaignBattleOriginOwner,
        units: List<CampaignBattleOriginUnit>,
    ): String {
        val owned = units.filter { it.ownerGeneralId == owner.ownerGeneralId }
        require(owned.isNotEmpty() && owned.all { it.side == owner.side })
        require(owner.controlledSourceKeys == owned.map { it.sourceKey }.sorted())
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write("CBO-OWNER-SET-1".toByteArray(StandardCharsets.US_ASCII))
            out.writeInt(owner.ownerGeneralId)
            out.text(owner.side.name)
            out.writeInt(owned.size)
            owned.sortedBy { it.sourceKey }.forEach { unit ->
                out.key(unit.sourceKey)
                out.writeLong(unit.sourceRevision)
            }
        }
        return sha256(buffer.toByteArray())
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES && size <= available())
        val bytes = ByteArray(size)
        readFully(bytes)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 in origin", exception)
        }
    }

    private fun DataOutputStream.nullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.nullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataOutputStream.key(key: TacticalV2SourceKey) {
        text(key.kind.name)
        text(key.sourceId)
    }

    private fun DataInputStream.key(): TacticalV2SourceKey {
        val kind: TacticalV2SourceKind = enum()
        return TacticalV2SourceKey(kind, text())
    }

    private inline fun <reified T : Enum<T>> DataInputStream.enum(): T = enumValueOf<T>(text())

    private fun DataOutputStream.participant(value: CampaignBattleOriginParticipant) {
        text(value.orderId)
        writeInt(value.ownerGeneralId)
        writeInt(value.commanderGeneralId)
        writeInt(value.nationId)
        writeInt(value.bugokIds.size)
        value.bugokIds.forEach(::writeInt)
    }

    private fun DataInputStream.participant(): CampaignBattleOriginParticipant =
        CampaignBattleOriginParticipant(text(), readInt(), readInt(), readInt(),
            List(count()) { readInt() })

    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_ROWS) }
}
