package opensamguk.gameapi.dto

import java.time.Instant

/**
 * 메시지 응답 (W3 enriched). PHP grand truth: `Message` 객체 + `MessageTarget`.
 *
 * `message` jsonb body는 `{src:MsgTarget, dest:MsgTarget?, text, option}` 형태로 저장된다
 * (`MessageTarget::toArray()` = `{id, name, nation_id, nation, color, icon}`). W3에서 이 body를
 * 디코드해 구조화 필드를 노출한다(FE가 raw jsonb를 다시 파싱하지 않도록):
 *  - [srcTarget]/[destTarget] = `src`/`dest` MsgTarget 블록(없으면 null).
 *  - [text]   = `text`(표시 본문).
 *  - [option] = `option` 블록(action/deletable/receiverMessageID 등) — raw 맵 그대로.
 *
 * `src`/`dest`(Int)는 기존 `message.src`/`message.dest` int 컬럼(장수 id 라우팅 키)으로 그대로 유지한다
 * (와이어 호환). 구조화 타깃은 [srcTarget]/[destTarget]에 별도로 담는다.
 */
data class MessageResponse(
    val id: Int?,
    val mailbox: Int,
    val type: String,
    val src: Int,
    val dest: Int,
    val time: Instant,
    val validUntil: Instant,
    /** 원본 body jsonb 문자열(호환 보존 — FE가 추가 필드를 직접 디코드 가능). */
    val message: String,
    /** body `text`(표시 본문). 없으면 null. */
    val text: String?,
    /** 발신 MsgTarget(`{id, name, nation, nationId, color, icon}`). 없으면 null. */
    val srcTarget: MsgTarget?,
    /** 수신 MsgTarget. 없으면 null(공개 메시지 등). */
    val destTarget: MsgTarget?,
    /** body `option` 블록(action/deletable/receiverMessageID 등). 없으면 null. */
    val option: Map<String, Any?>?,
)

/**
 * 메시지 발/수신 대상 — PHP `MessageTarget::toArray()` 동형.
 * `{id, name, nation_id, nation, color, icon}` → camelCase 와이어.
 * nation_id가 0/누락이면 PHP는 재야('재야', '#000000')로 보정하지만, 여기서는 저장된 body 값을 그대로
 * 디코드한다(보정은 write 시점에 이미 적용됨; reader는 fabricate 금지 — 저장값 그대로).
 */
data class MsgTarget(
    /** 장수 id(general). 시스템 타깃은 0. */
    val id: Int,
    /** 장수 이름. 시스템 타깃은 빈 문자열. */
    val name: String,
    /** 소속 국가 id. 재야/시스템은 0. */
    val nationId: Int,
    /** 소속 국가 이름. 재야는 '재야', 시스템은 'System'. */
    val nation: String,
    /** 국가 색(hex). */
    val color: String,
    /** 아이콘 경로. 없으면 null. */
    val icon: String?,
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

/**
 * D7 GetRecentMessage 응답 봉투. PHP `GetRecentMessage.php` shape:
 * `{result,private[],public[],national[],diplomacy[],sequence,nationID,generalName,latestRead}`.
 */
data class RecentMessageResponse(
    val result: Boolean = true,
    val private: List<MessageArrayItem>,
    val public: List<MessageArrayItem>,
    val national: List<MessageArrayItem>,
    val diplomacy: List<MessageArrayItem>,
    val sequence: Int,
    val nationID: Int,
    val generalName: String,
    val latestRead: LatestRead,
)

/**
 * D8 GetOldMessage 응답 봉투. PHP `GetOldMessage.php` shape:
 * `{private[],public[],national[],diplomacy[],result,keepRecent,sequence,nationID,generalName}`.
 */
data class OldMessageResponse(
    val private: List<MessageArrayItem>,
    val public: List<MessageArrayItem>,
    val national: List<MessageArrayItem>,
    val diplomacy: List<MessageArrayItem>,
    val result: Boolean = true,
    val keepRecent: Boolean = true,
    val sequence: Int,
    val nationID: Int,
    val generalName: String,
)

/**
 * PHP `Message::toArray()` 형태 — `{id,msgType,src,dest,text,option,time}`.
 * `time`은 `yyyy-MM-dd HH:mm:ss` 문자열(Instant 아님).
 */
data class MessageArrayItem(
    val id: Int?,
    val msgType: String,
    val src: MsgTargetMap?,
    val dest: MsgTargetMap?,
    val text: String,
    val option: Map<String, Any?>?,
    val time: String,
)

/** PHP `MessageTarget::toArray()` — `{id,name,nation_id,nation,color,icon}` 그대로. */
data class MsgTargetMap(
    val id: Int,
    val name: String,
    val nation_id: Int,
    val nation: String,
    val color: String,
    val icon: String?,
)

/** D7 `latestRead` 블록 — `{diplomacy,private}`. */
data class LatestRead(
    val diplomacy: Int = 0,
    val private: Int = 0,
)
