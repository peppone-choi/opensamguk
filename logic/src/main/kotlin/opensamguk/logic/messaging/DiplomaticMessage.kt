package opensamguk.logic.messaging

import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.Nation
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The action type embedded in a diplomatic message's option.
 *
 * PHP reference: `DiplomaticMessage::TYPE_NO_AGGRESSION`, `TYPE_CANCEL_NA`, `TYPE_STOP_WAR`.
 */
enum class DiplomaticActionType(val code: String) {
    NO_AGGRESSION("no_aggression"),
    CANCEL_NA("cancel_na"),
    STOP_WAR("stop_war"),
}

/**
 * Domain model for a diplomatic message.
 *
 * In the PHP flow, `DiplomaticMessage::agreeMessage()` validates the message then executes
 * the corresponding nation command (che_불가침수락, che_종전수락, che_불가침파기수락).
 *
 * The Kotlin version is a data class (not a polymorphic entity); the accept logic is handled
 * by the engine layer that wires [MessageStore] + [CommandRegistry] together.
 *
 * @property id The message ID (assigned after DB insert).
 * @property fromNationId Source nation.
 * @property toNationId   Destination nation.
 * @property fromGeneralId Source general (the diplomat / chief).
 * @property actionType   Which diplomatic action this message represents.
 * @property args         Additional arguments (destNationId, year, month, etc.).
 * @property validUntil   Expiration timestamp (yearMonth integer or absolute timestamp).
 * @property accepted     Whether the message has already been accepted.
 */
data class DiplomaticMessage(
    val id: Int,
    val fromNationId: Int,
    val toNationId: Int,
    val fromGeneralId: Int,
    val actionType: DiplomaticActionType,
    val args: Map<String, Any?>,
    val validUntil: Int,
    val accepted: Boolean = false,
) {
    /**
     * The nation-command key triggered when this message is accepted.
     */
    val acceptCommandKey: String = when (actionType) {
        DiplomaticActionType.NO_AGGRESSION -> "che_불가침수락"
        DiplomaticActionType.CANCEL_NA -> "che_불가침파기수락"
        DiplomaticActionType.STOP_WAR -> "che_종전수락"
    }

    /**
     * Build the canonical args map for the accept command.
     * The accept command expects `destNationID` (the sender nation) plus any
     * additional args (year/month for non-aggression).
     */
    fun acceptCommandArgs(): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>("destNationID" to fromNationId)
        when (actionType) {
            DiplomaticActionType.NO_AGGRESSION -> {
                args["year"]?.let { base["year"] = it }
                args["month"]?.let { base["month"] = it }
            }
            else -> { /* stop_war / cancel_na only need destNationID */ }
        }
        return base
    }

    companion object {
        /**
         * Build a [DiplomaticMessage] from raw DB/map data.
         * Used by [MessageFactory] for the polymorphic deserialization path.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Map<String, Any?>): DiplomaticMessage {
            val payload = (raw["payload"] as? Map<String, Any?>) ?: emptyMap()
            val option = (payload["option"] as? Map<String, Any?>) ?: emptyMap()
            val actionCode = (option["action"] as? String) ?: "no_aggression"
            val actionType = DiplomaticActionType.entries.find { it.code == actionCode }
                ?: DiplomaticActionType.NO_AGGRESSION

            return DiplomaticMessage(
                id = (raw["id"] as? Number)?.toInt() ?: 0,
                fromNationId = (option["fromNationId"] as? Number)?.toInt() ?: 0,
                toNationId = (option["toNationId"] as? Number)?.toInt() ?: 0,
                fromGeneralId = (option["fromGeneralId"] as? Number)?.toInt() ?: 0,
                actionType = actionType,
                args = (option["args"] as? Map<String, Any?>) ?: emptyMap(),
                validUntil = (raw["valid_until"] as? Number)?.toInt() ?: 0,
                accepted = (raw["accepted"] as? Boolean) ?: false,
            )
        }
    }
}

/**
 * Result of accepting a diplomatic message.
 *
 * @property commandKey The nation command that was executed.
 * @property commandArgs The args passed to the command.
 * @property logs Any log lines produced.
 * @property confirmationMessages The confirmation messages sent back to both parties.
 */
data class DiplomaticResult(
    val commandKey: String,
    val commandArgs: Map<String, Any?>,
    val logs: List<String>,
    val confirmationMessages: List<MessageDraft> = emptyList(),
)

/**
 * Result of declining a diplomatic message.
 *
 * @property invalidated Whether the message was marked invalid.
 * @property confirmationMessages The decline notification messages sent back.
 */
data class DeclineResult(
    val invalidated: Boolean,
    val confirmationMessages: List<MessageDraft> = emptyList(),
)

/**
 * Service that handles diplomatic message accept/decline logic.
 *
 * This is a pure logic-layer service (no Spring/DB dependencies). It consumes a
 * [CommandRegistry] to resolve and run the accept command, and produces [MessageDraft]
 * confirmation messages that the caller (engine or controller) routes to the mailbox channel.
 *
 * The accept path runs on a [NoRng]-backed [RandUtil] — a zero-draw invariant enforced
 * by throwing on any accidental draw (research §8g).
 */
class DiplomaticMessageService(
    private val registry: CommandRegistry,
) {

    /**
     * Accept (agree to) a diplomatic message.
     *
     * Flow (mirroring PHP `DiplomaticMessage::agreeMessage()`):
     *  1. Look up the accept command via [CommandRegistry].
     *  2. Build a [GeneralActionResolveContext] with a [NoRng] (zero-draw invariant).
     *  3. Run the command's `resolve()` — mutates draft + buffers side effects.
     *  4. Build confirmation messages (수락 알림) for both parties.
     *
     * @param message The diplomatic message being accepted.
     * @param actorGeneralId The general id of the accepting chief.
     * @param actorNation The accepting nation's draft (mutated in place by the resolver).
     * @param senderNation The sender nation's draft (mutated for bidirectional diplomacy changes).
     * @param contextBuilder Factory to build the resolve context (caller supplies world state).
     * @return [DiplomaticResult] with the command key, args, logs, and confirmation message drafts.
     */
    fun agree(
        message: DiplomaticMessage,
        actorGeneralId: Int,
        actorNation: Nation,
        senderNation: Nation,
        contextBuilder: (GeneralActionDefinition, Map<String, Any?>) -> GeneralActionResolveContext,
    ): DiplomaticResult {
        require(!message.accepted) { "이미 수락한 메시지입니다." }

        val command = registry.resolve(message.acceptCommandKey)
        require(command !== registry.fallback) { "알 수 없는 외교 수락 명령입니다: ${message.acceptCommandKey}" }

        val args = message.acceptCommandArgs()
        val ctx = contextBuilder(command, args)

        // Run the accept command — zero-draw path (NoRng throws on any draw)
        command.resolve(ctx)

        // Build confirmation messages
        val confirmationMessages = buildAcceptConfirmationMessages(message, actorNation, senderNation)

        return DiplomaticResult(
            commandKey = message.acceptCommandKey,
            commandArgs = args,
            logs = ctx.logs(),
            confirmationMessages = confirmationMessages,
        )
    }

    /**
     * Decline a diplomatic message.
     *
     * Flow (mirroring PHP `DiplomaticMessage::declineMessage()`):
     *  1. Mark the message invalid (valid_until past — the caller issues the UPDATE).
     *  2. Build decline notification messages for both parties.
     *
     * @param message The diplomatic message being declined.
     * @param actorNation The declining nation's draft (for name/color in the notification).
     * @param senderNation The sender nation's draft (for name/color in the notification).
     * @return [DeclineResult] with the invalidated flag and confirmation message drafts.
     */
    fun decline(
        message: DiplomaticMessage,
        actorNation: Nation,
        senderNation: Nation,
    ): DeclineResult {
        val confirmationMessages = buildDeclineConfirmationMessages(message, actorNation, senderNation)
        return DeclineResult(invalidated = true, confirmationMessages = confirmationMessages)
    }

    // ------------------------------------------------------------------
    // Private helpers — confirmation message builders
    // ------------------------------------------------------------------

    private fun buildAcceptConfirmationMessages(
        message: DiplomaticMessage,
        actorNation: Nation,
        senderNation: Nation,
    ): List<MessageDraft> {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val validUntil = now.plusMinutes(30)

        val actorTarget = MessageTarget(
            generalId = 0, generalName = "", nationId = actorNation.id,
            nationName = actorNation.name, color = actorNation.color, icon = "",
        )
        val senderTarget = MessageTarget(
            generalId = 0, generalName = "", nationId = senderNation.id,
            nationName = senderNation.name, color = senderNation.color, icon = "",
        )

        val actionLabel = when (message.actionType) {
            DiplomaticActionType.NO_AGGRESSION -> "불가침"
            DiplomaticActionType.CANCEL_NA -> "불가침 파기"
            DiplomaticActionType.STOP_WAR -> "종전"
        }

        // Receiver (actor) confirmation
        val receiverDraft = MessageDraft(
            msgType = MessageType.NATIONAL,
            src = actorTarget,
            dest = senderTarget,
            text = "${senderNation.name}의 ${actionLabel} 제의를 수락했습니다.",
            time = now,
            validUntil = validUntil,
        )

        // Sender confirmation
        val senderDraft = MessageDraft(
            msgType = MessageType.NATIONAL,
            src = senderTarget,
            dest = actorTarget,
            text = "${actorNation.name}이(가) ${actionLabel} 제의를 수락했습니다.",
            time = now,
            validUntil = validUntil,
        )

        return listOf(receiverDraft, senderDraft)
    }

    private fun buildDeclineConfirmationMessages(
        message: DiplomaticMessage,
        actorNation: Nation,
        senderNation: Nation,
    ): List<MessageDraft> {
        val now = LocalDateTime.now()
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val validUntil = now.plusMinutes(30)

        val actorTarget = MessageTarget(
            generalId = 0, generalName = "", nationId = actorNation.id,
            nationName = actorNation.name, color = actorNation.color, icon = "",
        )
        val senderTarget = MessageTarget(
            generalId = 0, generalName = "", nationId = senderNation.id,
            nationName = senderNation.name, color = senderNation.color, icon = "",
        )

        val actionLabel = when (message.actionType) {
            DiplomaticActionType.NO_AGGRESSION -> "불가침"
            DiplomaticActionType.CANCEL_NA -> "불가침 파기"
            DiplomaticActionType.STOP_WAR -> "종전"
        }

        // Receiver (actor) notification
        val receiverDraft = MessageDraft(
            msgType = MessageType.NATIONAL,
            src = actorTarget,
            dest = senderTarget,
            text = "${senderNation.name}의 ${actionLabel} 제의를 거절했습니다.",
            time = now,
            validUntil = validUntil,
        )

        // Sender notification
        val senderDraft = MessageDraft(
            msgType = MessageType.NATIONAL,
            src = senderTarget,
            dest = actorTarget,
            text = "${actorNation.name}이(가) ${actionLabel} 제의를 거절했습니다.",
            time = now,
            validUntil = validUntil,
        )

        return listOf(receiverDraft, senderDraft)
    }
}
