package opensamguk.logic.battle.realtime

import opensamguk.common.world.WorldId
import java.util.Collections

data class BattleOwnerNativeGeneral(
    val worldId: Int,
    val generalId: Int,
    val userId: String?,
    val npcState: Int,
)

/** Read facts only; constructing these values neither authenticates a source nor grants a ticket. */
class BattleOwnerAuthorityFacts(
    val ownerGeneralId: Int,
    val nativeUserId: String?,
    val nativeNpcState: Int,
    val accountId: Int?,
    val playable: Boolean,
    val authorityRevision: Long,
    val status: String,
    val side: BattleSide,
    controlledSourceKeys: Set<TacticalV2SourceKey>,
) {
    val controlledSourceKeys: Set<TacticalV2SourceKey> =
        Collections.unmodifiableSet(LinkedHashSet(controlledSourceKeys))
}

/** One actual owner, including the complete union of sources commanded by other generals. */
class BattleOwnerAuthorityBinding private constructor(
    val worldId: WorldId,
    val battleId: String,
    val originSha256: String,
    val ownerGeneralId: Int,
    val accountId: Int,
    val side: BattleSide,
    val authorityRevision: Long,
    controlledSourceKeys: Set<TacticalV2SourceKey>,
    sourceRevisions: Map<TacticalV2SourceKey, Long>,
) {
    val controlledSourceKeys: Set<TacticalV2SourceKey> =
        Collections.unmodifiableSet(LinkedHashSet(controlledSourceKeys))
    val sourceRevisions: Map<TacticalV2SourceKey, Long> =
        Collections.unmodifiableMap(LinkedHashMap(sourceRevisions))

    fun matches(other: BattleOwnerAuthorityBinding): Boolean =
        worldId == other.worldId && battleId == other.battleId && originSha256 == other.originSha256 &&
            ownerGeneralId == other.ownerGeneralId && accountId == other.accountId && side == other.side &&
            authorityRevision == other.authorityRevision && controlledSourceKeys == other.controlledSourceKeys &&
            sourceRevisions == other.sourceRevisions

    companion object {
        /** Decode the supplier's exact canonical bytes and project native rows one-to-one; no second wire. */
        fun fromOrigin(
            origin: CampaignBattleOriginSnapshot,
            nativeGenerals: List<BattleOwnerNativeGeneral>,
        ): List<BattleOwnerAuthorityBinding> {
            val bytes = CampaignBattleOriginCodec.encode(origin)
            val frozen = CampaignBattleOriginCodec.decode(bytes)
            require(nativeGenerals.map { it.generalId }.distinct().size == nativeGenerals.size)
            require(nativeGenerals.all { it.worldId == frozen.worldId })
            require(nativeGenerals.map { it.generalId }.toSet() == frozen.owners.map { it.ownerGeneralId }.toSet())
            val nativeById = nativeGenerals.associateBy { it.generalId }
            val sources = frozen.units.map { unit ->
                TacticalV2UnitSource(unit.sourceKey, unit.side, unit.ownerGeneralId,
                    unit.commanderGeneralId, unit.troops, unit.sourceRevision)
            }
            val owners = frozen.owners.map { owner ->
                val native = nativeById.getValue(owner.ownerGeneralId)
                require(native.npcState == owner.npcState)
                require(owner.controlledSourceKeys.distinct().size == owner.controlledSourceKeys.size)
                BattleOwnerAuthorityFacts(owner.ownerGeneralId, native.userId, native.npcState,
                    owner.accountId, owner.playable, owner.authorityRevision, owner.authorityStatus.name,
                    owner.side, owner.controlledSourceKeys.toSet())
            }
            return bind(WorldId(frozen.worldId), frozen.battleId, CampaignBattleOriginCodec.sha256(bytes),
                sources, owners)
        }

        /** Pure projection. The installed reader must independently verify origin bytes and current DB facts. */
        fun bind(
            worldId: WorldId,
            battleId: String,
            originSha256: String,
            sources: List<TacticalV2UnitSource>,
            owners: List<BattleOwnerAuthorityFacts>,
        ): List<BattleOwnerAuthorityBinding> {
            require(battleId.isNotBlank() && battleId.length <= 128 && battleId == battleId.trim())
            require(originSha256.matches(Regex("[0-9a-f]{64}")))
            require(sources.isNotEmpty() && sources.all {
                it.key.kind == TacticalV2SourceKind.RETINUE && it.sourceRevision > 0
            })
            require(sources.map { it.key }.distinct().size == sources.size)
            val sourceOwners = sources.groupBy { requireNotNull(it.ownerGeneralId) }
            require(owners.map { it.ownerGeneralId }.distinct().size == owners.size)
            require(owners.map { it.ownerGeneralId }.toSet() == sourceOwners.keys)
            val accounts = owners.mapNotNull { it.accountId }
            require(accounts.all { it > 0 } && accounts.distinct().size == accounts.size)
            return Collections.unmodifiableList(owners.sortedBy { it.ownerGeneralId }.mapNotNull { owner ->
                val owned = sourceOwners.getValue(owner.ownerGeneralId).sortedBy { it.key }
                val keys = owned.map { it.key }.toSet()
                require(owner.ownerGeneralId > 0 && owner.authorityRevision > 0 && owner.status == "ACTIVE")
                require(owned.all { it.side == owner.side } && owner.controlledSourceKeys == keys)
                require(owner.nativeNpcState >= 0)
                if (owner.accountId == null) {
                    // Missing human metadata must not silently become an AI participant.
                    require(!owner.playable && owner.nativeUserId == null && owner.nativeNpcState >= 2)
                    null
                } else {
                    require(owner.playable && owner.nativeNpcState < 2 &&
                        owner.nativeUserId == owner.accountId.toString())
                    BattleOwnerAuthorityBinding(worldId, battleId, originSha256, owner.ownerGeneralId,
                        owner.accountId, owner.side, owner.authorityRevision, keys,
                        owned.associate { it.key to it.sourceRevision })
                }
            })
        }
    }
}

/** Fixed installed read source only. A read observation is not an atomic ticket insertion or a grant. */
fun interface BattleOwnerAuthorityCurrentSource {
    fun read(expected: BattleOwnerAuthorityBinding): BattleOwnerAuthorityBinding?
}
