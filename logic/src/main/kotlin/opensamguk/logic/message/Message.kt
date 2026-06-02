package opensamguk.logic.message

/**
 * One materialized `message` row INTENT the daemon mailbox channel will INSERT (T0.5). Carries the
 * routed `mailbox`/`src`/`dest` ids + the body components; the jsonb body string is built by the
 * channel (it folds in the assigned receiver/sender message ids — see [Message.send]). `whichRow`
 * distinguishes the receiver row (inserted FIRST) from the sender row (inserted second).
 */
data class MessageRowDraft(
    val mailbox: Int,
    val srcId: Int,
    val destId: Int,
    val type: MessageType,
    val src: MessageTarget,
    val dest: MessageTarget,
    val text: String,
    val option: Map<String, Any?>?,
    val whichRow: Row,
) {
    enum class Row { RECEIVER, SENDER }
}

/**
 * Faithful port of PHP `sammo\Message` — the polymorphic message base + mailbox routing kernel.
 *
 * The DB-bound PHP methods (`sendRaw` INSERT, `getMessageByID`/`getMessagesFromMailBox` SELECT,
 * `invalidate` UPDATE) are split: the **routing decisions** (which mailbox, which src/dest id, the
 * two-row receiver-before-sender decomposition, the diplomacy sender-row action-nulling, the
 * `setSentInfo` inbox state machine, `toArray`, `buildFromArray` polymorphic dispatch, `invalidate`
 * body rewrite) live here in pure logic; the daemon mailbox channel (engine `ChangeRecorder
 * .recordMessageInsert` → `JdbcFlushExecutor`) owns the actual INSERT/UPDATE + id assignment.
 *
 * Subclasses ([DiplomaticMessage]/[ScoutMessage]/[RaiseInvaderMessage], Wave A2) override
 * [agreeMessage]/[declineMessage]; the base throws (PHP `NotInheritedMethodException`).
 *
 * QUARANTINE: `date`/`validUntil` are wall-clock (research §11) — carried verbatim, gated only on
 * interval MATH + Y-m-d H:i:s FORMAT. `getMessageByID`'s `valid_until`-with-no-comparison bug is
 * replicated by the read side (P7), not the daemon write path.
 */
open class Message(
    val msgType: MessageType,
    val src: MessageTarget,
    val dest: MessageTarget,
    var msg: String,
    /** ISO/`Y-m-d H:i:s` send time (wall-clock — quarantined). */
    val date: String,
    /** `Y-m-d H:i:s` valid-until (wall-clock — quarantined; default sentinel `9999-12-31`). */
    var validUntil: String,
    msgOption: Map<String, Any?>?,
) {
    var msgOption: MutableMap<String, Any?>? = msgOption?.let { LinkedHashMap(it) }
        protected set

    var mailbox: Int? = null
        protected set
    var id: Int? = null
        protected set
    var isInboxMail: Boolean = false
        protected set

    /** PHP `$sendCnt` double-send guard. */
    protected var sendCnt: Int = 0

    /**
     * PHP `setSentInfo` — the inbox-vs-outbox state machine. Validates the mailbox against the msgType
     * + src/dest nation/general ids and sets [isInboxMail]. Throws on an inconsistent mailbox (faithful).
     */
    fun setSentInfo(mailbox: Int, messageID: Int): Message {
        require(Mailbox.isValidMailbox(mailbox)) { "올바르지 않은 mailbox" }
        run {
            if (mailbox == Mailbox.PUBLIC) {
                require(msgType == MessageType.PUBLIC) { "올바르지 않은 mailbox, msgType !== MessageType::public" }
                isInboxMail = true; return@run
            }
            if (mailbox >= Mailbox.NATIONAL_BASE) {
                if (msgType == MessageType.DIPLOMACY) { isInboxMail = true; return@run }
                require(msgType == MessageType.NATIONAL) {
                    "올바르지 않은 mailbox, msgType not in (MessageType::diplomacy, MessageType::national)"
                }
                if (dest.nationId + Mailbox.NATIONAL_BASE == mailbox) { isInboxMail = true; return@run }
                if (src.nationId + Mailbox.NATIONAL_BASE == mailbox) { isInboxMail = false; return@run }
                throw IllegalArgumentException("송신, 수신국 둘 중의 어느 메일함도 아닙니다")
            }
            require(msgType == MessageType.PRIVATE) { "올바르지 않은 mailbox, msgType !== MSGTYPE_PRIVATE" }
            if (dest.generalId == mailbox) { isInboxMail = true; return@run }
            if (src.generalId == mailbox) { isInboxMail = false; return@run }
            throw IllegalArgumentException("송신자, 수신자 둘 중의 어느 메일함도 아닙니다")
        }
        this.id = messageID
        this.mailbox = mailbox
        return this
    }

    /** PHP `Message::toArray()` — `{id, msgType, src, dest, text, option, time}`. `dest` is null for public. */
    fun toArray(): Map<String, Any?> {
        val destArr: Map<String, Any?>? = if (msgType == MessageType.PUBLIC) null else dest.toArray()
        return linkedMapOf(
            "id" to id,
            "msgType" to msgType.value,
            "src" to src.toArray(),
            "dest" to destArr,
            "text" to msg,
            "option" to msgOption,
            "time" to date,
        )
    }

    /**
     * The routed `(mailbox, src_id, dest_id)` for a `sendRaw` to `mailbox` (PHP `sendRaw`). Public →
     * src.generalID / PUBLIC; national+ → src.nationID+BASE / dest.nationID+BASE; private → src/dest
     * generalID.
     */
    private fun routeIds(mailbox: Int): Triple<Int, Int, MessageType> = when {
        mailbox == Mailbox.PUBLIC -> Triple(src.generalId, Mailbox.PUBLIC, msgType)
        mailbox >= Mailbox.NATIONAL_BASE -> Triple(src.nationId + Mailbox.NATIONAL_BASE, dest.nationId + Mailbox.NATIONAL_BASE, msgType)
        else -> Triple(src.generalId, dest.generalId, msgType)
    }

    private fun sendRawDraft(mailbox: Int, which: MessageRowDraft.Row): MessageRowDraft {
        val (srcId, destId, type) = routeIds(mailbox)
        return MessageRowDraft(
            mailbox = mailbox, srcId = srcId, destId = destId, type = type,
            src = src, dest = dest, text = msg, option = msgOption?.let { LinkedHashMap(it) }, whichRow = which,
        )
    }

    /**
     * The receiver row (PHP `sendToReceiver`). Always produced; the channel INSERTs it FIRST.
     * Returns null only for the unreachable msgType (PHP throws there — modeled as an error).
     */
    private fun receiverDraft(): MessageRowDraft = when (msgType) {
        MessageType.PRIVATE -> sendRawDraft(dest.generalId, MessageRowDraft.Row.RECEIVER)
        MessageType.NATIONAL -> sendRawDraft(dest.nationId + Mailbox.NATIONAL_BASE, MessageRowDraft.Row.RECEIVER)
        MessageType.DIPLOMACY -> sendRawDraft(dest.nationId + Mailbox.NATIONAL_BASE, MessageRowDraft.Row.RECEIVER)
        MessageType.PUBLIC -> sendRawDraft(Mailbox.PUBLIC, MessageRowDraft.Row.RECEIVER)
    }

    /**
     * The sender row (PHP `sendToSender`), or null when there is no separate sender row (a self-send:
     * private src==dest, national src==dest nation, or public). The diplomacy sender row NULLS the
     * option when it carries an `action` (PHP nulls then restores msgOption around the sender sendRaw).
     */
    private fun senderDraft(): MessageRowDraft? = when (msgType) {
        MessageType.PRIVATE ->
            if (src.generalId != dest.generalId) sendRawDraft(src.generalId, MessageRowDraft.Row.SENDER) else null
        MessageType.NATIONAL ->
            if (src.nationId != dest.nationId) sendRawDraft(src.nationId + Mailbox.NATIONAL_BASE, MessageRowDraft.Row.SENDER) else null
        MessageType.DIPLOMACY -> {
            val base = sendRawDraft(src.nationId + Mailbox.NATIONAL_BASE, MessageRowDraft.Row.SENDER)
            // diplomacy sender row: if the option carries 'action', the sender copy stores a NULL option.
            if (msgOption?.containsKey("action") == true) base.copy(option = null) else base
        }
        MessageType.PUBLIC -> null
    }

    /**
     * PHP `Message::send` — the two-row decomposition, **receiver row BEFORE sender row** (load-bearing
     * order). Returns the ordered drafts the daemon channel INSERTs (receiver first). `sendDestOnly`
     * suppresses the sender row (e.g. system-notice). A self-send yields only the receiver row.
     *
     * The PHP `receiverMessageID`/`senderMessageID` back-references are NOT resolvable in the pure VO
     * (PHP gets them from the AUTO_INCREMENT mid-send); the daemon channel assigns the in-memory ids
     * and folds them into each row's option before the byte-faithful jsonb is written (research §2:
     * the monotonic in-memory sequence MUST match the flushed SERIAL). The `sendCnt` guard is enforced.
     */
    fun send(sendDestOnly: Boolean = false): List<MessageRowDraft> {
        if (sendCnt > 0) throw IllegalStateException("이미 전송한 메일입니다.")
        val drafts = mutableListOf(receiverDraft())
        isInboxMail = true
        sendCnt = 1
        if (!sendDestOnly) {
            senderDraft()?.let {
                drafts.add(it)
                isInboxMail = false
                sendCnt = 2
            }
        }
        return drafts
    }

    /**
     * PHP `Message::invalidate` — the body rewrite. `hideMsg` true → validUntil = `2000-12-31` (hidden);
     * false → text becomes `삭제된 메시지입니다.` (and the original text is preserved under
     * `originalText` when a `receiverMessageID` exists). `option.invalid = true` always. Returns the
     * post-invalidate body components (the channel emits the UPDATE).
     */
    fun invalidate(newMsgOption: Map<String, Any?>? = null, hideMsg: Boolean = true) {
        if (newMsgOption != null) msgOption = LinkedHashMap(newMsgOption)
        val opt = msgOption ?: LinkedHashMap<String, Any?>().also { msgOption = it }
        opt["invalid"] = true
        if (hideMsg) {
            validUntil = "2000-12-31 00:00:00"
        } else {
            if (opt.containsKey("receiverMessageID")) opt["originalText"] = msg
            msg = "삭제된 메시지입니다."
        }
    }

    /** The byte-faithful `message` jsonb body (PHP `sendRaw` payload): `{src, dest, text, option}`. */
    fun bodyArray(optionOverride: Map<String, Any?>? = msgOption): Map<String, Any?> = linkedMapOf(
        "src" to src.toArray(),
        "dest" to dest.toArray(),
        "text" to msg,
        "option" to optionOverride,
    )

    /** Accept-flow extension point (Wave A2 subclasses). Base throws — PHP `NotInheritedMethodException`. */
    open fun agreeMessage(receiverId: Int): Int =
        throw NotInheritedMethodException("agreeMessage not inherited by ${this::class.simpleName}")

    /** Decline-flow extension point (Wave A2 subclasses). Base throws — PHP `NotInheritedMethodException`. */
    open fun declineMessage(receiverId: Int): Int =
        throw NotInheritedMethodException("declineMessage not inherited by ${this::class.simpleName}")

    companion object {
        /**
         * PHP `Message::buildFromArray` — the polymorphic deserializer + dispatch registry. Dispatches by
         * `option.action`: diplomacy+action → [DiplomaticMessage]; 'scout' → [ScoutMessage];
         * 'raiseInvader' → [RaiseInvaderMessage]; else base [Message]. The concrete subclass factories
         * are registered by Wave A2 via [registerBuilder]; until then an unregistered tag falls back to
         * the base [Message] (so the daemon never crashes on a tag whose family has not landed).
         */
        private val builders = LinkedHashMap<String, (BuildArgs) -> Message>()

        /** Register a subclass factory for a dispatch key (Wave A2). Keys: "diplomacy", "scout", "raiseInvader". */
        fun registerBuilder(key: String, factory: (BuildArgs) -> Message) {
            builders[key] = factory
        }

        /** Test/registry reset (single-owner — only Wave A2 registers; tests may clear). */
        fun clearBuilders() = builders.clear()

        data class BuildArgs(
            val msgType: MessageType,
            val src: MessageTarget,
            val dest: MessageTarget,
            val text: String,
            val date: String,
            val validUntil: String,
            val option: Map<String, Any?>,
        )

        /**
         * Build a [Message] (or subclass) from a DB `message` row map (`{id, type, mailbox, time,
         * valid_until, message}` where `message` is the decoded jsonb `{src, dest, text, option}`).
         */
        fun buildFromArray(
            row: Map<String, Any?>,
            decodedBody: Map<String, Any?>,
        ): Message {
            val msgType = MessageType.from(row["type"] as String)
            val src = MessageTarget.buildFromArray(asMap(decodedBody["src"]))
            val dest = MessageTarget.buildFromArray(asMap(decodedBody["dest"]))
            @Suppress("UNCHECKED_CAST")
            val option = (decodedBody["option"] as? Map<String, Any?>) ?: emptyMap()
            val text = decodedBody["text"] as? String ?: ""
            val date = row["time"]?.toString() ?: ""
            val validUntil = row["valid_until"]?.toString() ?: ""

            val args = BuildArgs(msgType, src, dest, text, date, validUntil, option)
            val action = option["action"] as? String

            val message: Message = when {
                msgType == MessageType.DIPLOMACY && action != null ->
                    builders["diplomacy"]?.invoke(args) ?: baseMessage(args)
                action == "scout" -> builders["scout"]?.invoke(args) ?: baseMessage(args)
                action == "raiseInvader" -> builders["raiseInvader"]?.invoke(args) ?: baseMessage(args)
                else -> baseMessage(args)
            }
            message.setSentInfo((row["mailbox"] as Number).toInt(), (row["id"] as Number).toInt())
            return message
        }

        private fun baseMessage(a: BuildArgs): Message =
            Message(a.msgType, a.src, a.dest, a.text, a.date, a.validUntil, a.option)

        @Suppress("UNCHECKED_CAST")
        private fun asMap(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()
    }
}

/** Faithful to PHP `sammo\NotInheritedMethodException` — a base Message's agree/decline is not overridden. */
class NotInheritedMethodException(message: String) : IllegalStateException(message)
