package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.LatestRead
import opensamguk.gameapi.dto.MessageArrayItem
import opensamguk.gameapi.dto.MessageResponse
import opensamguk.gameapi.dto.MsgTarget
import opensamguk.gameapi.dto.MsgTargetMap
import opensamguk.gameapi.dto.OldMessageResponse
import opensamguk.gameapi.dto.RecentMessageResponse
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.infra.entity.MessageEntity
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.message.Mailbox
import opensamguk.logic.message.MessageType
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonEncode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 메일함 read API (W3 enriched + D7/D8). PHP grand truth: `Message`/`MessageTarget`.
 *
 * W3에서 [MessageResponse]에 body jsonb를 디코드한 구조화 필드(text/srcTarget/destTarget/option)를
 * 채운다. 디코드는 ContactReader와 동일한 [jsonDecode](PHP-faithful) 사용 — body는
 * `{src:MsgTarget, dest:MsgTarget?, text, option}` 형태.
 *
 * D7 GetRecentMessage — `/api/mailbox/recent` (신규). 4섹션 봉투 + valid_until 필터 + diplomacy 마스킹.
 * D8 GetOldMessage — `/api/mailbox/old` (봉투 재구성). `to(id<to)+type` 페이징 + valid_until 필터.
 *
 * read-only(§7).
 */
@RestController
@RequestMapping("/api")
class MailboxController(
    private val messageRepository: MessageRepository,
    private val generals: GeneralReadRepository,
) {

    @GetMapping("/mailbox/{mailbox}")
    fun mailbox(
        @PathVariable mailbox: Int,
    ): ResponseEntity<List<MessageResponse>> {
        val me = currentGeneral()
        val permission = if (me != null) secretPermission(me) else -1
        val messages = messageRepository.findByMailboxOrderById(mailbox)
            .map { applyDiplomacyMask(it, permission).toResponse() }
        return ResponseEntity.ok(messages)
    }

    @GetMapping("/mailbox/{mailbox}/unread")
    fun unread(
        @PathVariable mailbox: Int,
    ): ResponseEntity<List<MessageResponse>> {
        val now = Instant.now()
        val me = currentGeneral()
        val permission = if (me != null) secretPermission(me) else -1
        val messages = messageRepository.findByMailboxAndValidUntilAfter(mailbox, now)
            .map { applyDiplomacyMask(it, permission).toResponse() }
        return ResponseEntity.ok(messages)
    }

    @GetMapping("/messages/{id}")
    fun message(@PathVariable id: Int): ResponseEntity<MessageResponse> {
        val msg = messageRepository.findById(id)
            .orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(msg.toResponse())
    }

    /**
     * D7 GetRecentMessage — `GET /api/mailbox/recent`.
     *
     * PHP `GetRecentMessage.php` 봉투:
     * `{result,private[],public[],national[],diplomacy[],sequence,nationID,generalName,latestRead}`.
     *
     * 4섹션 라우팅:
     *   private   → mailbox = generalID
     *   public    → mailbox = MAILBOX_PUBLIC (9999)
     *   national  → mailbox = MAILBOX_NATIONAL + nationID
     *   diplomacy → mailbox = MAILBOX_NATIONAL + nationID
     *
     * `valid_until > now` + `id DESC` + LIMIT 15.
     * diplomacy 마스킹: dest.nationId != 0 && permission < 3 → text='(외교 메시지입니다)', option.invalid=true.
     * `type = type.value` (not `.name`).
     * latestRead = BLOCKED (0,0) — general_stor 미구현.
     */
    @GetMapping("/mailbox/recent")
    fun recent(
        @RequestParam(name = "sequence", defaultValue = "0") sequence: Int,
    ): ResponseEntity<RecentMessageResponse> {
        val me = currentGeneral() ?: return ResponseEntity.ok(
            RecentMessageResponse(
                result = false,
                private = emptyList(),
                public = emptyList(),
                national = emptyList(),
                diplomacy = emptyList(),
                sequence = 0,
                nationID = 0,
                generalName = "",
                latestRead = LatestRead(),
            ),
        )

        val generalID = me.id
        val nationID = me.nationId
        val generalName = me.name
        val permission = secretPermission(me)
        val now = Instant.now()

        // PHP getMessagesFromMailBox: valid_until>now + (id>=fromSeq when fromSeq>0) + id DESC + LIMIT 15.
        val privateMsgs = fetchMessages(generalID, MessageType.PRIVATE, now, 15, sequence)
        val publicMsgs = fetchMessages(Mailbox.PUBLIC, MessageType.PUBLIC, now, 15, sequence)
        val nationalMsgs = fetchMessages(Mailbox.NATIONAL_BASE + nationID, MessageType.NATIONAL, now, 15, sequence)
        val diplomacyMsgs = fetchMessages(Mailbox.NATIONAL_BASE + nationID, MessageType.DIPLOMACY, now, 15, sequence)
            .map { applyDiplomacyMask(it, permission) }

        // PHP: $nextSequence/$minSequence는 reqSequence로 시드되어 섹션을 가로질러 변이된다(running).
        var nextSequence = sequence
        var minSequence = sequence
        var lastType: String? = null

        val privateItems = privateMsgs.map { msg ->
            updateSequence(msg, nextSequence, minSequence, { nextSequence = it }, { minSequence = it; lastType = "private" })
            msg.toArrayItem()
        }
        val publicItems = publicMsgs.map { msg ->
            updateSequence(msg, nextSequence, minSequence, { nextSequence = it }, { minSequence = it; lastType = "public" })
            msg.toArrayItem()
        }
        val nationalItems = nationalMsgs.map { msg ->
            updateSequence(msg, nextSequence, minSequence, { nextSequence = it }, { minSequence = it; lastType = "national" })
            msg.toArrayItem()
        }
        val diplomacyItems = diplomacyMsgs.map { msg ->
            updateSequence(msg, nextSequence, minSequence, { nextSequence = it }, { minSequence = it; lastType = "diplomacy" })
            msg.toArrayItem()
        }

        // PHP: 마지막 타입의 마지막 항목 제거 (중복 방지)
        val trimmedPrivate = if (lastType == "private") privateItems.dropLast(1) else privateItems
        val trimmedPublic = if (lastType == "public") publicItems.dropLast(1) else publicItems
        val trimmedNational = if (lastType == "national") nationalItems.dropLast(1) else nationalItems
        val trimmedDiplomacy = if (lastType == "diplomacy") diplomacyItems.dropLast(1) else diplomacyItems

        return ResponseEntity.ok(
            RecentMessageResponse(
                result = true,
                private = trimmedPrivate,
                public = trimmedPublic,
                national = trimmedNational,
                diplomacy = trimmedDiplomacy,
                sequence = nextSequence,
                nationID = nationID,
                generalName = generalName,
                latestRead = LatestRead(), // BLOCKED — general_stor 미구현
            ),
        )
    }

    /**
     * D8 GetOldMessage — `GET /api/mailbox/old`.
     *
     * PHP `GetOldMessage.php` 봉투:
     * `{private[],public[],national[],diplomacy[],result,keepRecent,sequence,nationID,generalName}`.
     *
     * `to(id<to)+type` 페이징 + LIMIT 15.
     * `valid_until > now` 필터 추가 (현재 무필터 버그 수정).
     * time/validUntil Instant → `yyyy-MM-dd HH:mm:ss`.
     */
    @GetMapping("/mailbox/old")
    fun old(
        @RequestParam(name = "to") to: Int,
        @RequestParam(name = "type") typeStr: String,
    ): ResponseEntity<OldMessageResponse> {
        val me = currentGeneral() ?: return ResponseEntity.ok(
            OldMessageResponse(
                private = emptyList(),
                public = emptyList(),
                national = emptyList(),
                diplomacy = emptyList(),
                result = false,
                keepRecent = true,
                sequence = 0,
                nationID = 0,
                generalName = "",
            ),
        )

        val generalID = me.id
        val nationID = me.nationId
        val generalName = me.name
        val permission = secretPermission(me)
        val now = Instant.now()

        val reqType = try {
            MessageType.from(typeStr)
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.ok(
                OldMessageResponse(
                    private = emptyList(),
                    public = emptyList(),
                    national = emptyList(),
                    diplomacy = emptyList(),
                    result = false,
                    keepRecent = true,
                    sequence = 0,
                    nationID = nationID,
                    generalName = generalName,
                ),
            )
        }

        val (mailbox, msgType) = when (reqType) {
            MessageType.PRIVATE -> generalID to MessageType.PRIVATE
            MessageType.PUBLIC -> Mailbox.PUBLIC to MessageType.PUBLIC
            MessageType.NATIONAL -> (Mailbox.NATIONAL_BASE + nationID) to MessageType.NATIONAL
            MessageType.DIPLOMACY -> (Mailbox.NATIONAL_BASE + nationID) to MessageType.DIPLOMACY
        }

        val msgs = messageRepository.findTop15ByMailboxAndTypeAndValidUntilAfterAndIdLessThanOrderByIdDesc(
            mailbox, msgType, now, to,
        )

        var nextSequence = to
        val items = msgs.map { msg ->
            val msgId = msg.id ?: 0
            if (msgId > nextSequence) nextSequence = msgId
            msg.toArrayItem()
        }

        // diplomacy 마스킹
        val maskedItems = if (reqType == MessageType.DIPLOMACY) {
            items.map { applyDiplomacyMask(it, permission) }
        } else {
            items
        }

        val result = when (reqType) {
            MessageType.PRIVATE -> OldMessageResponse(
                private = maskedItems, public = emptyList(), national = emptyList(), diplomacy = emptyList(),
                sequence = nextSequence, nationID = nationID, generalName = generalName,
            )
            MessageType.PUBLIC -> OldMessageResponse(
                private = emptyList(), public = maskedItems, national = emptyList(), diplomacy = emptyList(),
                sequence = nextSequence, nationID = nationID, generalName = generalName,
            )
            MessageType.NATIONAL -> OldMessageResponse(
                private = emptyList(), public = emptyList(), national = maskedItems, diplomacy = emptyList(),
                sequence = nextSequence, nationID = nationID, generalName = generalName,
            )
            MessageType.DIPLOMACY -> OldMessageResponse(
                private = emptyList(), public = emptyList(), national = emptyList(), diplomacy = maskedItems,
                sequence = nextSequence, nationID = nationID, generalName = generalName,
            )
        }

        return ResponseEntity.ok(result)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** 현재 로그인한 장수 조회. game-api는 세션 기반 — 여기서는 첫 번째 playable 장수로 폴백. */
    private fun currentGeneral(): GeneralReadEntity? {
        // TODO: 세션 기반 userID → general 매핑 (현재는 첫 번째 playable 장수로 폴백)
        return generals.findAll().firstOrNull { it.npcState < 2 }
    }

    /** D6/D7/D8 공용 — PHP `checkSecretPermission` (func.php:390-434) 포팅. */
    private fun secretPermission(g: GeneralReadEntity): Int {
        if (g.nationId == 0) return -1
        if (g.officerLevel == 0) return -1

        // PHP `Json::decode($me['penalty'])` (func.php:395) — penalty는 general 전용 jsonb 컬럼
        // (GeneralReadEntity.penalty)이지 meta 안의 키가 아니다(meta["penalty"]는 항상 빈 맵).
        val penalty = g.penalty
        if (phpTruthy(penalty[PENALTY_NO_CHIEF])) return 0

        val secretMax = secretMaxPermission(penalty)

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

    /**
     * PHP `Message::getMessagesFromMailBox($mailbox,$type,15,$fromSeq)` (Message.php:170-193).
     * WHERE `valid_until > now` AND (`id >= fromSeq` WHEN fromSeq>0) ORDER BY id DESC LIMIT 15.
     *
     * sequence(fromSeq)>0 커서는 SQL의 `id >= fromSeq`와 동일하게 take(15) 이전에 적용해야
     * PHP와 같은 15행을 고른다. 정렬은 이미 id DESC이므로 필터 후 take(15) == SQL LIMIT 15.
     */
    private fun fetchMessages(
        mailbox: Int,
        type: MessageType,
        now: Instant,
        limit: Int,
        fromSeq: Int,
    ): List<MessageEntity> {
        val all = messageRepository.findByMailboxAndTypeAndValidUntilAfterOrderByIdDesc(mailbox, type, now)
        val cursored = if (fromSeq > 0) all.filter { (it.id ?: 0) >= fromSeq } else all
        return cursored.take(limit)
    }

    /**
     * PHP GetRecentMessage.php:92-139 — `$nextSequence`는 단조 증가(max), `$minSequence`는 섹션을
     * 가로질러 변이되는 running-min(reqSequence로 시드). 비교는 **현재** minSequence와 한다(고정
     * reqSequence가 아니라). lastType은 minSequence가 갱신될 때마다 그 섹션으로 갱신된다.
     */
    private fun updateSequence(
        msg: MessageEntity,
        next: Int,
        min: Int,
        setNext: (Int) -> Unit,
        setMin: (Int) -> Unit,
    ) {
        val id = msg.id ?: 0
        if (id > next) setNext(id)
        if (id <= min) setMin(id)
    }

    /** D7 diplomacy 마스킹 — MessageEntity 수준. */
    private fun applyDiplomacyMask(msg: MessageEntity, permission: Int): MessageEntity {
        if (permission >= 3) return msg
        val body = runCatching { jsonDecode(msg.message) }.getOrDefault(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val destMap = body["dest"] as? Map<String, Any?>
        val destNationId = (destMap?.get("nation_id") as? Number)?.toInt() ?: 0
        if (destNationId == 0) return msg

        // 마스킹: text → '(외교 메시지입니다)', option.invalid = true
        val newBody = body.toMutableMap()
        newBody["text"] = "(외교 메시지입니다)"
        @Suppress("UNCHECKED_CAST")
        val option = (newBody["option"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        option["invalid"] = true
        newBody["option"] = option

        return MessageEntity(
            mailbox = msg.mailbox,
            type = msg.type,
            src = msg.src,
            dest = msg.dest,
            time = msg.time,
            validUntil = msg.validUntil,
            message = opensamguk.logic.util.jsonEncode(newBody),
            id = msg.id,
        )
    }

    /** D7 diplomacy 마스킹 — MessageArrayItem 수준 (D8용). */
    private fun applyDiplomacyMask(item: MessageArrayItem, permission: Int): MessageArrayItem {
        if (permission >= 3) return item
        val destNationId = item.dest?.nation_id ?: 0
        if (destNationId == 0) return item
        val newOption = (item.option ?: emptyMap()).toMutableMap()
        newOption["invalid"] = true
        return item.copy(
            text = "(외교 메시지입니다)",
            option = newOption,
        )
    }

    // ------------------------------------------------------------------
    // MessageEntity → DTO conversions
    // ------------------------------------------------------------------

    private fun opensamguk.infra.entity.MessageEntity.toResponse(): MessageResponse {
        val body = runCatching { jsonDecode(message) }.getOrDefault(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val srcMap = body["src"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val destMap = body["dest"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val optionMap = body["option"] as? Map<String, Any?>
        return MessageResponse(
            id = id,
            mailbox = mailbox,
            type = type.value,           // D7 fix: type.name → type.value
            src = src,
            dest = dest,
            time = time,
            validUntil = validUntil,
            message = message,
            text = body["text"] as? String,
            srcTarget = srcMap?.toMsgTarget(),
            destTarget = destMap?.toMsgTarget(),
            option = optionMap,
        )
    }

    /** PHP `Message::toArray()` 형태로 변환 — D7/D8 봉투용. */
    private fun opensamguk.infra.entity.MessageEntity.toArrayItem(): MessageArrayItem {
        val body = runCatching { jsonDecode(message) }.getOrDefault(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val srcMap = body["src"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val destMap = body["dest"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val optionMap = body["option"] as? Map<String, Any?>
        return MessageArrayItem(
            id = id,
            msgType = type.value,
            src = srcMap?.toMsgTargetMap(),
            dest = if (type == MessageType.PUBLIC) null else destMap?.toMsgTargetMap(),
            text = (body["text"] as? String) ?: "",
            option = optionMap,
            time = PHP_TIME_FORMAT.format(time.atZone(PHP_ZONE)),
        )
    }

    /** MessageTarget 블록 맵 → [MsgTarget]. PHP `toArray()` 키(`id,name,nation_id,nation,color,icon`). */
    private fun Map<String, Any?>.toMsgTarget() = MsgTarget(
        id = (this["id"] as? Number)?.toInt() ?: 0,
        name = this["name"] as? String ?: "",
        nationId = (this["nation_id"] as? Number)?.toInt() ?: 0,
        nation = this["nation"] as? String ?: "",
        color = this["color"] as? String ?: "",
        icon = this["icon"] as? String,
    )

    /** PHP `MessageTarget::toArray()` → [MsgTargetMap] (snake_case 키 보존). */
    private fun Map<String, Any?>.toMsgTargetMap() = MsgTargetMap(
        id = (this["id"] as? Number)?.toInt() ?: 0,
        name = this["name"] as? String ?: "",
        nation_id = (this["nation_id"] as? Number)?.toInt() ?: 0,
        nation = this["nation"] as? String ?: "",
        color = this["color"] as? String ?: "",
        icon = this["icon"] as? String,
    )

    companion object {
        private val PHP_ZONE = ZoneId.of("Asia/Seoul")
        private val PHP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        const val AMBASSADOR = "ambassador"
        const val AUDITOR = "auditor"
        const val PENALTY_NO_TOP_SECRET = "noTopSecret"
        const val PENALTY_NO_CHIEF = "noChief"
        const val PENALTY_NO_AMBASSADOR = "noAmbassador"
    }
}
