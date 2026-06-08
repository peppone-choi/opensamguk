package opensamguk.gameapi.owner

import opensamguk.gameapi.read.GeneralReadRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * F2 Wave 1 — possession ("장수 점유"/빙의) flow, ACCOUNT-side only.
 *
 * ## What this does (account-side, game-api-owned)
 * Claiming a general writes ONE row to the V10 `general_owner` table (`general_id`, `user_id`,
 * `claimed_at`). That table is NOT a game-state table, so this JPA write does NOT violate the
 * one-daemon-write rule. Guards mirror the legacy `j_select_npc.php` update predicate
 * `owner <= 0 AND npc = 2 AND no = %i`:
 *   * the user must not already own a general on this server (UNIQUE user_id), and
 *   * the target general must be unowned (UNIQUE general_id), and
 *   * the target general must exist and be a claimable NPC (npc_state == 2 candidate pool).
 *
 * ## What this DELIBERATELY does NOT do — the DEFERRED game-state side-effect
 * Legacy possession ALSO mutates the `general` GAME-STATE row: `npc=2 → npc=1`, `owner=userID`,
 * `killturn=6`, `defence_train=80`, `permission='normal'`, plus pushes 빙의 logs. Those are GAME-STATE
 * writes — game-api MUST NOT perform them (one-daemon-write rule). They are DEFERRED: a follow-up wave
 * routes the npc-flip through the existing intake path (a `TurnDaemonCommand` published on the command
 * stream, consumed by the daemon, applied via ChangeRecorder→JdbcFlushExecutor) — the SAME mechanism
 * [opensamguk.gameapi.reserve.CommandReserveService] already uses to wake the daemon. Until that wave
 * lands, `general_owner` is the authoritative "who plays this general" link for read/identity purposes,
 * and the general's `npc_state` is left untouched by game-api.
 *
 * This split keeps possession working for identity (my-general/nation/city resolution) today, with the
 * game-state activation flip cleanly isolated as intake work.
 */
@Service
class GeneralPossessionService(
    private val owners: GeneralOwnerRepository,
    private val generals: GeneralReadRepository,
    private val npcTokens: SelectNpcTokenRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Outcome of a claim attempt. */
    sealed interface ClaimResult {
        data class Claimed(val generalId: Int) : ClaimResult
        /** Idempotent: the caller already owns exactly this general. */
        data class AlreadyOwnedBySelf(val generalId: Int) : ClaimResult
        /** The caller already owns a DIFFERENT general (one-per-user). */
        data class UserAlreadyHasGeneral(val ownedGeneralId: Int) : ClaimResult
        /** The target general is claimed by someone else. */
        object GeneralAlreadyClaimed : ClaimResult
        /** The target general does not exist or is not a claimable NPC candidate. */
        object NotClaimable : ClaimResult
    }

    @Transactional
    fun claim(userId: Long, generalId: Int): ClaimResult {
        // 1. one-per-user guard (idempotent on the same general).
        val existingForUser = owners.findByUserId(userId)
        if (existingForUser != null) {
            return if (existingForUser.generalId.toInt() == generalId) {
                ClaimResult.AlreadyOwnedBySelf(generalId)
            } else {
                ClaimResult.UserAlreadyHasGeneral(existingForUser.generalId.toInt())
            }
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

        owners.save(
            GeneralOwnerEntity(
                generalId = generalId.toLong(),
                userId = userId,
                claimedAt = now,
            ),
        )
        npcTokens.deleteOwnerOrExpired(userId, now)
        return ClaimResult.Claimed(generalId)
    }

    companion object {
        /**
         * Legacy `npc=2` = the pure-NPC pool a user may 빙의(possess). After claim the legacy flips it to
         * `npc=1` (possessed-player-NPC) — that flip is the DEFERRED game-state side-effect (see class doc).
         */
        const val CLAIMABLE_NPC_STATE = 2
    }
}
