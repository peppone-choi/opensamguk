package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import opensamguk.infra.battle.realtime.BattleCommandRecord
import opensamguk.infra.battle.realtime.BattleCommandReceipt
import opensamguk.infra.battle.realtime.BattleCommandVerdict
import opensamguk.infra.battle.realtime.BattleDeploymentMove
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.CommandAdmission

/** JOINING placement only. COMMAND and combat effects belong to subsequent stages. */
class BattleV2CommandService(
    private val store: BattleSessionStore,
    private val frozen: BattleV2FrozenPlacementCodec,
    private val codec: BattleV2WireCodec,
) {
    fun move(identity: BattleJoinIdentity, move: BattleV2DeploymentMoveWire): String {
        val ticket = requireNotNull(store.ticket(identity.worldId, identity.battleId))
        require(ticket.participants.any { it.participantId == identity.participantId &&
            it.accountId == identity.accountId && it.generalId == identity.generalId && it.side == identity.side }) {
            "socket participant mismatch"
        }
        val initial = frozen.initialDeployment(ticket)
        val controlled = BattleV2AuthorityScopes.resolve(ticket.participants, initial.units)
            .getValue(identity.participantId)
        val intent = codec.intent(move)
        val record = BattleCommandRecord(identity.worldId, identity.battleId, identity.participantId,
            move.clientCommandId, sha(intent), move.expectedEpoch, move.expectedAuthorityRevision,
            0, identity.side, intent, BattleDeploymentMove(initial,
                move.sourceKey, move.targetCell, move.expectedDeploymentRevision, controlled))
        val receipt = when (val admission = store.admit(record)) {
            is CommandAdmission.Receipt -> admission.value
            CommandAdmission.IdempotencyConflict -> BattleCommandReceipt(move.clientCommandId,
                BattleCommandVerdict.REJECTED, "IDEMPOTENCY_CONFLICT", 0, null, null,
                identity.authorityRevision)
        }
        return codec.ack(identity.battleId, identity.sessionEpoch, receipt)
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
