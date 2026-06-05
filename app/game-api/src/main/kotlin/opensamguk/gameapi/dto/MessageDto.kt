package opensamguk.gameapi.dto

import java.time.Instant

/** 메시지 응답 */
data class MessageResponse(
    val id: Int?,
    val mailbox: Int,
    val type: String,
    val src: Int,
    val dest: Int,
    val time: Instant,
    val validUntil: Instant,
    val message: String,
)

/**
 * 연락처 목록 응답 (`GET /api/contacts` — PHP `GetContactList` / `getMailboxList`).
 * PHP shape: `{nation: [{mailbox, name, color, general: [[id, name, flags], ...]}]}`.
 * 국가 목록 = [재야(id 0)] + 전 국가, mailbox = nationId + 9000(재야는 9000).
 */
data class ContactListResponse(
    val nation: List<ContactNation>,
)

/** 한 국가의 연락처 묶음. `general`은 `[id, name, flags]` 트리플 배열(PHP 배열 형태 그대로). */
data class ContactNation(
    val mailbox: Int,
    val name: String,
    val color: String,
    val general: List<List<Any>>,
)
