package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.ContactListResponse
import opensamguk.gameapi.dto.ContactNation
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * W6a — `GET /api/contacts` (연락처 목록, PHP `GetContactList` / `getMailboxList`). READ-only.
 *
 * PHP `func_message.php::getMailboxList` (Q-A1 RESOLVED — live read, 캐시 아님):
 *  1. `SELECT … FROM general WHERE npc < 2` (playable 장수 전부) — opensamguk은 `npc_state < 2`.
 *  2. 장수별 flags 비트필드: 1=lord(officer_level==12) · 2=npc(npc_state==1) · 4=diplomat(외교권자).
 *  3. 국가 = `[getNationStaticInfo(0)] + getAllNationStaticInfo()` — 재야(0) 먼저, 그다음 전 국가.
 *  4. 국가별 mailbox = nationId + 9000(재야=9000), generals = `[[id, name, flags], …]`.
 *
 * opensamguk은 외교권 enum 컬럼이 없어 diplomat(4) 비트는 general.meta["permission"]로 운반한다
 * (부재 시 0 — 시드 1010엔 사전 지정 외교권자가 없으므로 항상 0; fabricate 아님). 정렬은 id 오름차순
 * (결정적 출력 — PHP는 SELECT 자연 순서지만 표시 전용이라 패러티 무관).
 *
 * game-api JPA read = 합법 precheck-path read(§7). 데몬 write 경로는 JPA를 절대 안 건드린다.
 */
@RestController
@RequestMapping("/api")
class ContactController(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
) {
    @GetMapping("/contacts")
    fun contacts(): ResponseEntity<ContactListResponse> {
        // 1) playable 장수(npc_state < 2)를 국가별로 묶는다(id 오름차순).
        val byNation = LinkedHashMap<Int, MutableList<List<Any>>>()
        for (g in generals.findAll().filter { it.npcState < 2 }.sortedBy { it.id }) {
            byNation.getOrPut(g.nationId) { mutableListOf() }
                .add(listOf(g.id, g.name, flagsOf(g)))
        }

        // 2) 국가 목록 = 재야(0) + 전 국가(id 오름차순). mailbox = nationId + 9000.
        val result = mutableListOf<ContactNation>()
        result.add(
            ContactNation(
                mailbox = 0 + MAILBOX_NATIONAL,
                name = "재야",
                color = "#000000",
                general = byNation[0] ?: emptyList(),
            ),
        )
        for (n in nations.findAll().sortedBy { it.id }) {
            result.add(
                ContactNation(
                    mailbox = n.id + MAILBOX_NATIONAL,
                    name = n.name,
                    color = n.color,
                    general = byNation[n.id] ?: emptyList(),
                ),
            )
        }

        return ResponseEntity.ok(ContactListResponse(nation = result))
    }

    /** flags 비트필드: 1=lord(officer_level==12) · 2=npc(npc_state==1) · 4=diplomat(meta.permission==4). */
    private fun flagsOf(g: GeneralReadEntity): Int {
        var flags = 0
        if (g.officerLevel == 12) flags = flags or 1
        if (g.npcState == 1) flags = flags or 2
        if (secretPermission(g) == 4) flags = flags or 4
        return flags
    }

    /** PHP checkSecretPermission: 외교관(ambassador)/감사관(auditor) = 4. opensamguk meta["permission"]. */
    private fun secretPermission(g: GeneralReadEntity): Int = when (val p = g.meta["permission"]) {
        is Number -> p.toInt()
        "ambassador", "auditor" -> 4
        else -> 0
    }

    companion object {
        /** Message::MAILBOX_NATIONAL. */
        const val MAILBOX_NATIONAL = 9000
    }
}
