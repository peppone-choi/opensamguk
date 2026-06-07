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

    /**
     * D6 — PHP `checkSecretPermission` (func.php:390-434) 포팅.
     *
     * - nationId==0 || officerLevel==0  → -1
     * - NoChief penalty                 → 0
     * - secretMax = checkSecretMaxPermission(penalty)
     *   (NoTopSecret||NoChief → 1, NoAmbassador → 2, else 4)
     * - secretMin = (level12→4, ambassador→4, auditor→3, level>=5→2, level>1→1, else 0)
     * - return min(secretMin, secretMax)
     */
    private fun secretPermission(g: GeneralReadEntity): Int {
        if (g.nationId == 0) return -1
        if (g.officerLevel == 0) return -1

        // PHP `$penalty = Json::decode($me['penalty'])` (func.php:395) — penalty는 general의 전용
        // jsonb 컬럼(GeneralReadEntity.penalty)이지 meta 안의 키가 아니다. meta["penalty"]를 읽으면
        // 항상 빈 맵이 되어 모든 클램프 분기가 무음(no-op)이 된다.
        val penalty = g.penalty
        if (phpTruthy(penalty[PENALTY_NO_CHIEF])) return 0

        val secretMax = secretMaxPermission(penalty)

        // permission(ambassador/auditor 문자열)은 opensamguk 전용 컬럼이 없어 meta로 운반한다.
        val permission = g.meta["permission"] as? String
        val secretMin = when {
            g.officerLevel == 12 -> 4
            permission == AMBASSADOR -> 4
            permission == AUDITOR -> 3
            g.officerLevel >= 5 -> 2
            g.officerLevel > 1 -> 1
            else -> 0
        }
        return minOf(secretMin, secretMax)
    }

    /** PHP `checkSecretMaxPermission` (func.php:377-388). */
    private fun secretMaxPermission(penalty: Map<String, Any?>): Int = when {
        phpTruthy(penalty[PENALTY_NO_TOP_SECRET]) -> 1
        phpTruthy(penalty[PENALTY_NO_CHIEF]) -> 1
        phpTruthy(penalty[PENALTY_NO_AMBASSADOR]) -> 2
        else -> 4
    }

    /**
     * PHP `($penalty[key] ?? false)` 진리값 — PHP truthy: null/false/0/"0"/"" 만 falsy.
     * 저장 시 플래그가 boolean true 가 아니라 1/문자열로 인코드돼도 패러티를 유지한다.
     */
    private fun phpTruthy(v: Any?): Boolean = when (v) {
        null, false -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty() && v != "0"
        else -> true
    }

    companion object {
        /** Message::MAILBOX_NATIONAL. */
        const val MAILBOX_NATIONAL = 9000

        const val AMBASSADOR = "ambassador"
        const val AUDITOR = "auditor"
        const val PENALTY_NO_TOP_SECRET = "noTopSecret"
        const val PENALTY_NO_CHIEF = "noChief"
        const val PENALTY_NO_AMBASSADOR = "noAmbassador"
    }

}
