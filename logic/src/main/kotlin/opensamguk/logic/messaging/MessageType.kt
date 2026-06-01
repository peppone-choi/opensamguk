package opensamguk.logic.messaging

/**
 * Message type enum — faithful port of PHP Message type constants.
 *
 * PHP defines: 'public' | 'private' | 'national' | 'diplomacy'
 * These determine the routing mailbox and visibility rules.
 */
enum class MessageType {
    /** 전체 공지 / 전역 방송 → PUBLIC mailbox (9999) */
    PUBLIC,

    /** 장수 간 1:1 개인 쪽지 → recipient generalId mailbox */
    PRIVATE,

    /** 국가 내 방송 (전 장수 수신) → NATIONAL_BASE + nationId */
    NATIONAL,

    /** 외교 제의 / 수락 / 거절 → NATIONAL_BASE + destNationId */
    DIPLOMACY,
}
