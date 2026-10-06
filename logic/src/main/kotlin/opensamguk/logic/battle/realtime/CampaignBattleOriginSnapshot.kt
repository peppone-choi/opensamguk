package opensamguk.logic.battle.realtime

/** Facts captured by the campaign flush. A pending encounter is not a committed origin. */
data class CampaignBattleOriginParticipant(
    val orderId: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val nationId: Int,
    val bugokIds: List<Int>,
) {
    init {
        require(orderId.isNotBlank() && orderId.length <= 128)
        require(ownerGeneralId > 0 && commanderGeneralId > 0 && nationId >= 0)
        require(bugokIds.isNotEmpty() && bugokIds.all { it > 0 })
        require(bugokIds == bugokIds.distinct().sorted())
    }
}

data class CampaignBattleOriginUnit(
    val sourceKey: TacticalV2SourceKey,
    val side: BattleSide,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val crewTypeId: Int,
    val troops: Int,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val provisions: Int,
    val commanderRetainerId: Int?,
    val sourceRevision: Long,
) {
    init {
        require(sourceKey.kind == TacticalV2SourceKind.RETINUE)
        require(ownerGeneralId > 0 && commanderGeneralId > 0 && crewTypeId > 0 && troops > 0)
        require(training in 0..100 && morale in 0..100 && fatigue in 0..100 && provisions >= 0)
        require(commanderRetainerId == null || commanderRetainerId > 0)
        require(sourceRevision > 0)
    }
}

enum class CampaignBattleAuthorityStatus { ACTIVE, REVOKED }

data class CampaignBattleOriginOwner(
    val ownerGeneralId: Int,
    val accountId: Int?,
    val npcState: Int,
    val playable: Boolean,
    val side: BattleSide,
    val controlledSourceKeys: List<TacticalV2SourceKey>,
    val authorityRevision: Long,
    val authorityStatus: CampaignBattleAuthorityStatus,
) {
    init {
        require(ownerGeneralId > 0 && (accountId == null || accountId > 0) && npcState >= 0)
        require(!playable || accountId != null)
        require(controlledSourceKeys.isNotEmpty() && controlledSourceKeys == controlledSourceKeys.distinct().sorted())
        require(controlledSourceKeys.all { it.kind == TacticalV2SourceKind.RETINUE })
        require(authorityRevision > 0)
    }
}

/** Exact immutable origin bytes are persisted after the native world CAS commits. */
data class CampaignBattleOriginSnapshot(
    val worldId: Int,
    val battleId: String,
    val encounterId: String,
    val forcesSnapshotId: String,
    val committedWorldVersion: Long,
    val writerEpoch: Long,
    val topologyRevision: String,
    val topologyHash: String,
    val provinceKey: String,
    val approachKey: String,
    val tilesHash: String,
    val lockGeneration: Long,
    val lockSetRevision: Long,
    val attacker: CampaignBattleOriginParticipant,
    val defenders: List<CampaignBattleOriginParticipant>,
    val units: List<CampaignBattleOriginUnit>,
    val owners: List<CampaignBattleOriginOwner>,
) {
    init {
        val sha = Regex("[0-9a-f]{64}")
        require(worldId > 0 && battleId.isNotBlank() && battleId.length <= 128)
        require(encounterId.matches(sha) && forcesSnapshotId.matches(sha))
        require(committedWorldVersion > 0 && writerEpoch >= 0 && lockGeneration >= 0 && lockSetRevision >= 0)
        require(topologyRevision.isNotBlank() && topologyHash.matches(sha) && tilesHash.matches(sha))
        require(provinceKey.isNotBlank() && approachKey.isNotBlank() && provinceKey != approachKey)
        require(defenders.isNotEmpty() && defenders == defenders.sortedWith(compareBy(
            CampaignBattleOriginParticipant::commanderGeneralId,
            CampaignBattleOriginParticipant::ownerGeneralId,
            CampaignBattleOriginParticipant::orderId,
        )))
        val participants = listOf(attacker) + defenders
        require(participants.map { it.orderId }.distinct().size == participants.size)
        require(participants.map { it.commanderGeneralId }.distinct().size == participants.size)
        val allBugokIds = participants.flatMap { it.bugokIds }
        require(allBugokIds.distinct().size == allBugokIds.size)
        require(units.isNotEmpty() && units == units.sortedBy { it.sourceKey })
        require(units.map { it.sourceKey }.distinct().size == units.size)
        require(units.map { it.sourceKey.sourceId.toInt() }.toSet() == allBugokIds.toSet())
        participants.forEachIndexed { index, participant ->
            val side = if (index == 0) BattleSide.ATTACKER else BattleSide.DEFENDER
            participant.bugokIds.forEach { id ->
                val unit = units.single { it.sourceKey.sourceId == id.toString() }
                require(unit.side == side && unit.ownerGeneralId == participant.ownerGeneralId &&
                    unit.commanderGeneralId == participant.commanderGeneralId)
            }
        }
        require(owners.isNotEmpty() && owners == owners.sortedBy { it.ownerGeneralId })
        require(owners.map { it.ownerGeneralId }.distinct().size == owners.size)
        require(owners.mapNotNull { it.accountId }.distinct().size == owners.count { it.accountId != null })
        require(owners.map { it.ownerGeneralId }.toSet() == participants.map { it.ownerGeneralId }.toSet())
        owners.forEach { owner ->
            val owned = units.filter { it.ownerGeneralId == owner.ownerGeneralId }
            require(owned.isNotEmpty() && owned.all { it.side == owner.side })
            require(owner.controlledSourceKeys == owned.map { it.sourceKey }.sorted())
        }
    }
}
