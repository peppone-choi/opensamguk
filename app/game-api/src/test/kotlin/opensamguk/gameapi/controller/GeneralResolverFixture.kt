package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.ClaimNpcRequestStatus
import opensamguk.gameapi.owner.ClaimNpcRequestStatusReader
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralOwnershipClassifier
import opensamguk.gameapi.owner.GeneralOwnershipReadSource
import opensamguk.gameapi.owner.GeneralOwnershipSnapshot
import opensamguk.gameapi.owner.GeneralPossessionService
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository

internal fun fixtureGeneralResolver(
    owners: GeneralOwnerRepository,
    generals: GeneralReadRepository,
    nations: NationReadRepository,
): GeneralResolver = GeneralResolver(
    GeneralOwnershipClassifier(
        owners,
        FixtureGeneralOwnershipReadSource(owners, generals),
        ClaimNpcRequestStatusReader { _, _ -> ClaimNpcRequestStatus.Pending },
    ),
    generals,
    nations,
)

private class FixtureGeneralOwnershipReadSource(
    private val owners: GeneralOwnerRepository,
    private val generals: GeneralReadRepository,
) : GeneralOwnershipReadSource {
    override fun findPlayableByUserId(userId: String): GeneralOwnershipSnapshot? {
        val direct = generals.findByUserId(userId)
        if (direct != null) {
            direct.userId = userId
            return direct.toSnapshot(userId)
        }

        val numericUserId = userId.toLongOrNull() ?: return null
        val owner = owners.findByUserId(numericUserId) ?: return null
        val general = generals.findById(owner.generalId.toInt()).orElse(null) ?: return null
        if (general.npcState >= GeneralPossessionService.CLAIMABLE_NPC_STATE) return null
        general.userId = userId
        return general.toSnapshot(userId)
    }

    override fun findById(id: Int): GeneralOwnershipSnapshot? =
        generals.findById(id).orElse(null)?.toSnapshot()

    private fun GeneralReadEntity.toSnapshot(effectiveUserId: String? = userId): GeneralOwnershipSnapshot =
        GeneralOwnershipSnapshot(
            id = id,
            worldId = worldId,
            userId = effectiveUserId,
            npcState = npcState,
            detail = this,
        )
}
