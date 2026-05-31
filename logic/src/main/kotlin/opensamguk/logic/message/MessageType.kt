package opensamguk.logic.message

/**
 * Faithful port of PHP `sammo\Enums\MessageType` (string-backed). The DB `message.type` column
 * stores the `.value` string (`private`/`public`/`national`/`diplomacy`).
 */
enum class MessageType(val value: String) {
    PRIVATE("private"),
    PUBLIC("public"),
    NATIONAL("national"),
    DIPLOMACY("diplomacy");

    companion object {
        /** PHP `MessageType::from($string)` — throws on an unknown value. */
        fun from(value: String): MessageType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("unknown MessageType: $value")
    }
}

/**
 * Mailbox constants + validity (PHP `Message::MAILBOX_PUBLIC`/`MAILBOX_NATIONAL` + `isValidMailBox`).
 * A national mailbox is `nationID + NATIONAL_BASE`; the public mailbox is the single [PUBLIC] value.
 */
object Mailbox {
    const val PUBLIC = 9999
    const val NATIONAL_BASE = 9000

    /** PHP `isValidMailBox`: `0 < mailbox <= 9999`. */
    fun isValidMailbox(mailbox: Int): Boolean = mailbox in 1..PUBLIC
}
