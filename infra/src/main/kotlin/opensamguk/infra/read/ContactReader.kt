package opensamguk.infra.read

import opensamguk.logic.util.jsonDecode
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

/**
 * 연락처(국가/장수) + 메시지 read seam (W6a 메시지).
 *
 * 데몬 [opensamguk.engine.intake.MessageHandler]의 삭제 게이트(메시지 존재/본인/외교시스템/5분/deletable)
 * 와 `GET /api/contacts`의 연락처 목록(`GetContactList` = PHP `getMailboxList`)은 live general/nation/message
 * 행을 read 해야 한다. [VotePollRepository]와 동일한 plain JDBC 클래스(스프링 빈 아님) — 데몬 디스패처가
 * nullable-주입하며, null이면 핸들러가 stub-empty로 동작한다.
 *
 * READ 전용 — write 경로(message INSERT/UPDATE, general.newmsg)는
 * [opensamguk.infra.persistence.JdbcFlushExecutor]를 거치며 절대 여기서 쓰지 않는다(one-daemon-write 규칙).
 *
 * **Q-A1 RESOLVED:** `getMailboxList`는 live `general WHERE npc < 2` 행을 그대로 read 한다(캐시 아님 —
 * PHP func_message.php:9). 캐싱은 PHP 주석의 미래 제안일 뿐 현재는 매 호출 live read. 그대로 따른다.
 *
 * **Q-A3 RESOLVED:** `삭제된 메시지입니다.`는 invalidate(hideMsg=false)가 본문 text에 쓰는 *런타임 데이터*
 * (Message.php:480)이지 골든 로그가 아니다. 핸들러가 무효화 body를 구성하므로 reader는 원문 text + option만
 * 돌려주면 된다.
 */
class ContactReader(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    /**
     * 장수 한 명의 연락처 read (send 게이트의 상대 존재/플래그 가드 보조). 데몬 핸들러는 send 게이트를
     * world(in-memory source of truth)로 충족하므로 이 메서드는 보조용(GET /api/contacts 외 직접 조회).
     *
     * @param generalId 대상 장수 id.
     * @return 연락처 DTO, 또는 없으면 null.
     */
    fun findGeneral(generalId: Int): ContactReadRow? {
        val rows = jdbc.query(
            """
            SELECT id, name, nation_id, officer_level, npc_state, meta
              FROM general
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", generalId),
        ) { rs, _ -> mapContactRow(rs) }
        return rows.firstOrNull()
    }

    /**
     * 연락처 목록 read (`GetContactList` — PHP `getMailboxList`). live `general WHERE npc_state < 2`.
     * flags 비트필드: 1=lord(officer_level==12) · 2=npc(npc_state==1) · 4=diplomat(meta.permission==4/외교관).
     * id 오름차순(결정적 출력; PHP는 SELECT 자연 순서지만 결정성을 위해 정렬 — 표시 전용, 패러티 무관).
     *
     * @return 연락처 DTO 목록, 비어 있으면 emptyList.
     */
    fun listContacts(): List<ContactReadRow> = jdbc.query(
        """
        SELECT id, name, nation_id, officer_level, npc_state, meta
          FROM general
         WHERE npc_state < 2
         ORDER BY id ASC
        """.trimIndent(),
    ) { rs, _ -> mapContactRow(rs) }

    /**
     * 삭제 게이트용 메시지 read (PHP `Message::getMessageByID`). `message WHERE id = :id`. body jsonb를
     * 디코드해 src/dest 객체 + text + option + receiverMessageID + action 존재 여부를 함께 돌려준다.
     *
     * @param msgId 메시지 id.
     * @return 메시지 DTO, 또는 없으면 null ('메시지가 없습니다').
     */
    fun findMessage(msgId: Int): MessageReadRow? {
        val rows = jdbc.query(
            """
            SELECT id, mailbox, type, src, dest, time, valid_until, message
              FROM message
             WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", msgId),
        ) { rs, _ ->
            val body = jsonDecode(rs.getString("message"))
            @Suppress("UNCHECKED_CAST")
            val src = body["src"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val dest = body["dest"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val option = body["option"] as? Map<String, Any?> ?: emptyMap()
            MessageReadRow(
                id = rs.getInt("id"),
                type = rs.getString("type"),
                srcGeneralId = (src["id"] as? Number)?.toInt() ?: 0,
                srcNationId = (src["nation_id"] as? Number)?.toInt() ?: 0,
                destGeneralId = (dest?.get("id") as? Number)?.toInt() ?: 0,
                destNationId = (dest?.get("nation_id") as? Number)?.toInt() ?: 0,
                time = rs.getTimestamp("time").toInstant(),
                validUntil = rs.getTimestamp("valid_until").toInstant(),
                hasAction = option["action"] != null,
                deletable = option["deletable"] as? Boolean,
                receiverMessageId = (option["receiverMessageID"] as? Number)?.toInt(),
                text = body["text"] as? String ?: "",
                srcArray = src,
                destArray = dest,
                option = option,
            )
        }
        return rows.firstOrNull()
    }

    private fun mapContactRow(rs: java.sql.ResultSet): ContactReadRow {
        val officerLevel = rs.getInt("officer_level")
        val npcState = rs.getInt("npc_state")
        val meta = jsonDecode(rs.getString("meta"))
        var flags = 0
        if (officerLevel == 12) flags = flags or 1 // lord
        if (npcState == 1) flags = flags or 2 // npc
        if (secretPermission(meta) == 4) flags = flags or 4 // diplomat
        return ContactReadRow(
            generalId = rs.getInt("id"),
            name = rs.getString("name"),
            nationId = rs.getInt("nation_id"),
            npc = npcState,
            flags = flags,
        )
    }

    /**
     * PHP `checkSecretPermission`: 외교관(ambassador)/감사관(auditor) = 4. opensamguk은 외교권 enum 컬럼이
     * 없어 general.meta["permission"]로 운반한다(부재 시 0 — 외교권자 아님; fabricate 아님).
     */
    private fun secretPermission(meta: Map<String, Any?>): Int = when (val p = meta["permission"]) {
        is Number -> p.toInt()
        "ambassador", "auditor" -> 4
        else -> 0
    }
}

/**
 * 연락처 한 행의 게이트 read 서브셋 (W6a). 엔진 핸들러가 send 게이트/연락처 목록을 충족시킨다.
 * infra-local DTO — 엔진 의존 사이클 회피를 위해 엔진 측 모델과 독립 미러링 ([VotePollReadRow] 패턴).
 * flags 비트필드: 1=lord(officer_level==12) · 2=npc(npc_state==1) · 4=diplomat(meta.permission==4).
 */
data class ContactReadRow(
    /** general.id. */
    val generalId: Int,
    /** general.name. */
    val name: String,
    /** 소속 국가 id (nation_id). 무소속이면 0. */
    val nationId: Int,
    /** npc 여부 (npc_state 값; >=2면 연락처에서 제외). */
    val npc: Int,
    /** 연락처 플래그 비트필드 (1=lord · 2=npc · 4=diplomat). */
    val flags: Int = 0,
)

/**
 * 삭제 게이트용 메시지 한 행의 read 서브셋 (PHP `Message` 객체의 deleteMsg-게이트 부분집합). 엔진의
 * [opensamguk.engine.intake.MessageSnapshot]으로 매핑돼 5분 윈도우/본인/외교시스템/deletable/상호무효화
 * 게이트를 충족시킨다. infra-local DTO — 모듈 사이클 회피를 위해 엔진 MessageSnapshot과 독립 미러링.
 */
data class MessageReadRow(
    val id: Int,
    val type: String,
    val srcGeneralId: Int,
    val srcNationId: Int,
    val destGeneralId: Int,
    val destNationId: Int,
    val time: Instant,
    val validUntil: Instant,
    val hasAction: Boolean,
    val deletable: Boolean?,
    val receiverMessageId: Int?,
    val text: String,
    val srcArray: Map<String, Any?>,
    val destArray: Map<String, Any?>?,
    val option: Map<String, Any?>,
)
