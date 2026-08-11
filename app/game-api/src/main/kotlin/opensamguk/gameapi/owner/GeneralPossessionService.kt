package opensamguk.gameapi.owner

import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * F2 Wave 1 — possession ("장수 점유"/빙의) flow, ACCOUNT-side only.
 *
 * ## What this does (account-side, game-api-owned)
 * Claiming a general writes ONE row to `general_owner` (`world_id`, `general_id`, `user_id`,
 * `claimed_at`) through the process-world repository. That table is NOT a game-state table, so this JPA write does NOT violate the
 * one-daemon-write rule. Guards mirror the legacy `j_select_npc.php` update predicate
 * `owner <= 0 AND npc = 2 AND no = %i`:
 *   * the user must not already own a playable typed general or hold a correlated pending claim, and
 *   * the target general must be unowned in this world (PRIMARY KEY world_id + general_id), and
 *   * the target general must exist and be a claimable NPC (npc_state == 2 candidate pool).
 *
 * The API then publishes `TurnDaemonCommand.ClaimNpc`; the daemon exclusively applies the legacy
 * game-state mutation (`npc=2 → npc=1`, typed owner, lifecycle fields, and logs) through
 * `ChangeRecorder → JdbcFlushExecutor`. `general_owner` records the durable reservation/link; a live typed
 * general is the authoritative owner for identity and further admission.
 */
@Service
class GeneralPossessionService(
    private val owners: GeneralOwnerRepository,
    private val generals: GeneralReadRepository,
    private val ownership: GeneralOwnershipClassifier,
    private val npcTokens: SelectNpcTokenRepository,
    private val worldStates: WorldStateReadRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Outcome of a claim attempt. */
    sealed interface ClaimResult {
        data class Claimed(val generalId: Int, val requestId: String) : ClaimResult
        data class AwaitingDaemon(val generalId: Int, val requestId: String) : ClaimResult
        /** Idempotent: the caller already owns exactly this general. */
        data class AlreadyOwnedBySelf(val generalId: Int) : ClaimResult
        data class TerminalDenied(val generalId: Int, val reason: String) : ClaimResult
        data class UncorrelatedReservation(val generalId: Int) : ClaimResult
        data class InvalidClaimResult(val generalId: Int) : ClaimResult
        data class ReservationChanged(val generalId: Int) : ClaimResult
        /** The caller already owns a DIFFERENT general (one-per-user). */
        data class UserAlreadyHasGeneral(val ownedGeneralId: Int) : ClaimResult
        /** The target general is claimed by someone else. */
        object GeneralAlreadyClaimed : ClaimResult
        /** The target general does not exist or is not a claimable NPC candidate. */
        object NotClaimable : ClaimResult
        /** This server does not allow legacy possession mode. */
        object ServerModeBlocked : ClaimResult
    }

    @Transactional
    fun claim(userId: Long, generalId: Int, admitClaim: (Int) -> String): ClaimResult {
        if (npcMode() != 1) return ClaimResult.ServerModeBlocked

        var current = ownership.classify(userId)
        repeat(2) {
            when (current) {
                is GeneralOwnershipClassifier.Ownership.Stale -> {
                    val stale = current
                    val repair = ownership.repair(stale)
                    if (repair is GeneralOwnershipClassifier.RepairResult.TerminalRejected &&
                        stale.reservation.generalId.toInt() == generalId
                    ) {
                        return ClaimResult.TerminalDenied(generalId, repair.reason)
                    }
                    current = ownership.classify(userId)
                }

                is GeneralOwnershipClassifier.Ownership.CorrelatedPending -> {
                    val pending = current
                    val refreshed = ownership.classify(userId)
                    if (refreshed is GeneralOwnershipClassifier.Ownership.CorrelatedPending &&
                        refreshed.reservation == pending.reservation
                    ) {
                        val requestId = refreshed.reservation.claimRequestId
                            ?: return ClaimResult.UncorrelatedReservation(generalId)
                        return if (refreshed.reservation.generalId.toInt() == generalId) {
                            ClaimResult.AwaitingDaemon(generalId, requestId)
                        } else {
                            ClaimResult.UserAlreadyHasGeneral(refreshed.reservation.generalId.toInt())
                        }
                    }
                    current = refreshed
                }

                else -> return@repeat
            }
        }

        when (current) {
            is GeneralOwnershipClassifier.Ownership.LiveOwned -> {
                return if (current.body.id == generalId) {
                    ClaimResult.AlreadyOwnedBySelf(generalId)
                } else {
                    ClaimResult.UserAlreadyHasGeneral(current.body.id)
                }
            }

            is GeneralOwnershipClassifier.Ownership.CorrelatedPending -> {
                val requestId = current.reservation.claimRequestId
                    ?: return ClaimResult.UncorrelatedReservation(generalId)
                return if (current.reservation.generalId.toInt() == generalId) {
                    ClaimResult.AwaitingDaemon(generalId, requestId)
                } else {
                    ClaimResult.UserAlreadyHasGeneral(current.reservation.generalId.toInt())
                }
            }

            is GeneralOwnershipClassifier.Ownership.Stale ->
                return if (current.disposition == GeneralOwnershipClassifier.StaleDisposition.Invalid) {
                    ClaimResult.InvalidClaimResult(generalId)
                } else {
                    ClaimResult.ReservationChanged(generalId)
                }

            GeneralOwnershipClassifier.Ownership.None -> Unit
        }

        // 2. target must exist and be a claimable NPC candidate (npc_state == 2 pool, legacy npc=2).
        val general = generals.findById(generalId).orElse(null) ?: return ClaimResult.NotClaimable
        if (general.npcState != CLAIMABLE_NPC_STATE) return ClaimResult.NotClaimable

        val now = Instant.now(clock)
        val token = npcTokens.findFirstByOwnerIdAndValidUntilAfterOrderByIdDesc(userId, now)
            ?: return ClaimResult.NotClaimable
        if (!token.pickResult.containsKey(generalId.toString())) return ClaimResult.NotClaimable

        // 3. target must be unowned (UNIQUE general_id; re-checked in-tx, DB UNIQUE is the backstop).
        if (owners.existsByGeneralId(generalId.toLong())) return ClaimResult.GeneralAlreadyClaimed

        val requestId = admitClaim(generalId)
        owners.save(
            GeneralOwnerEntity(
                generalId = generalId.toLong(),
                userId = userId,
                claimedAt = now,
                claimRequestId = requestId,
            ),
        )
        npcTokens.deleteOwnerOrExpired(userId, now)
        return ClaimResult.Claimed(generalId, requestId)
    }

    private fun npcMode(): Int =
        intOf(runCatching { worldStates.findById(1)?.orElse(null) }.getOrNull()?.config?.get("npcmode")) ?: 0

    companion object {
        /**
         * Legacy `npc=2` = the pure-NPC pool a user may 빙의(possess). The daemon ClaimNpc handler flips a
         * successful claim to `npc=1` (possessed-player-NPC).
         */
        const val CLAIMABLE_NPC_STATE = 2
        const val POSSESSED_NPC_STATE = 1

        private fun intOf(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
