package opensamguk.logic.auction

import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonEncode

/**
 * Faithful port of PHP `sammo\Enums\AuctionType` (입찰자 기준). String-backed; the `ng_auction.type`
 * column stores `.value`.
 */
enum class AuctionType(val value: String) {
    BUY_RICE("buyRice"),     // 쌀 매물 등록, 금으로 구매
    SELL_RICE("sellRice"),   // 금 매물 등록, 쌀로 판매
    UNIQUE_ITEM("uniqueItem"); // 유니크 매물 등록, 유산 포인트로 구매

    companion object {
        fun from(value: String): AuctionType =
            entries.firstOrNull { it.value == value } ?: throw IllegalArgumentException("unknown AuctionType: $value")
    }
}

/** The resource a buyer pays (PHP `ResourceType`). */
enum class ResourceType(val value: String) {
    GOLD("gold"), RICE("rice"), INHERITANCE_POINT("inheritPoint");

    companion object {
        fun from(value: String): ResourceType =
            entries.firstOrNull { it.value == value } ?: throw IllegalArgumentException("unknown ResourceType: $value")
    }
}

/**
 * `ng_auction.detail` jsonb (PHP `AuctionInfoDetail`, stored via `#[JsonString]`). `NullIsUndefined`
 * fields are OMITTED from the encoded map when null (isReverse/finishBidAmount/remainCloseDateExtensionCnt/
 * availableLatestBidCloseDate). LinkedHashMap insertion order = the PHP property order.
 */
data class AuctionInfoDetail(
    val title: String,
    val hostName: String,
    val amount: Int,
    val isReverse: Boolean? = null,
    val startBidAmount: Int,
    val finishBidAmount: Int? = null,
    var remainCloseDateExtensionCnt: Int? = null,
    /** wall-clock (quarantined) — carried as an ISO string; null = no extension cap. */
    var availableLatestBidCloseDate: String? = null,
) {
    /** Encode to the detail map (NullIsUndefined: omit null keys), insertion order = PHP property order. */
    fun toArray(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["title"] = title
        m["hostName"] = hostName
        m["amount"] = amount
        if (isReverse != null) m["isReverse"] = isReverse
        m["startBidAmount"] = startBidAmount
        if (finishBidAmount != null) m["finishBidAmount"] = finishBidAmount
        if (remainCloseDateExtensionCnt != null) m["remainCloseDateExtensionCnt"] = remainCloseDateExtensionCnt
        if (availableLatestBidCloseDate != null) m["availableLatestBidCloseDate"] = availableLatestBidCloseDate
        return m
    }

    fun toJson(): String = jsonEncode(toArray())

    companion object {
        fun fromArray(m: Map<String, Any?>): AuctionInfoDetail = AuctionInfoDetail(
            title = m["title"] as? String ?: "",
            hostName = m["hostName"] as? String ?: "",
            amount = (m["amount"] as? Number)?.toInt() ?: 0,
            isReverse = m["isReverse"] as? Boolean,
            startBidAmount = (m["startBidAmount"] as? Number)?.toInt() ?: 0,
            finishBidAmount = (m["finishBidAmount"] as? Number)?.toInt(),
            remainCloseDateExtensionCnt = (m["remainCloseDateExtensionCnt"] as? Number)?.toInt(),
            availableLatestBidCloseDate = m["availableLatestBidCloseDate"] as? String,
        )
    }
}

/**
 * `ng_auction` row (PHP `AuctionInfo`). `id` is NullIsUndefined (omitted on INSERT). `detail` is the
 * JsonString-encoded [AuctionInfoDetail]. `openDate`/`closeDate` are wall-clock (quarantined) ISO
 * strings. The column names use the RawName mapping (`host_general_id`, `req_resource`, `open_date`,
 * `close_date`).
 */
data class AuctionInfo(
    val id: Int?,
    val type: AuctionType,
    val finished: Boolean,
    val target: String?,
    val hostGeneralId: Int,
    val reqResource: ResourceType,
    val openDate: String,
    var closeDate: String,
    val detail: AuctionInfoDetail,
) {
    /**
     * The `ng_auction` column map. `withoutId` true (PHP `toArray('id')`) omits the id column (used by
     * the UPDATE WHERE-id path). `detail` is JsonString (the nested DTO as a json STRING).
     */
    fun toArray(withoutId: Boolean = false): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (!withoutId && id != null) m["id"] = id
        m["type"] = type.value
        m["finished"] = finished
        m["target"] = target
        m["host_general_id"] = hostGeneralId
        m["req_resource"] = reqResource.value
        m["open_date"] = openDate
        m["close_date"] = closeDate
        m["detail"] = detail.toJson()
        return m
    }

    companion object {
        fun fromArray(m: Map<String, Any?>): AuctionInfo = AuctionInfo(
            id = (m["id"] as? Number)?.toInt(),
            type = AuctionType.from(m["type"] as String),
            finished = m["finished"] as? Boolean ?: false,
            target = m["target"] as? String,
            hostGeneralId = (m["host_general_id"] as? Number)?.toInt() ?: 0,
            reqResource = ResourceType.from(m["req_resource"] as String),
            openDate = m["open_date"]?.toString() ?: "",
            closeDate = m["close_date"]?.toString() ?: "",
            detail = decodeDetail(m["detail"]),
        )

        @Suppress("UNCHECKED_CAST")
        private fun decodeDetail(v: Any?): AuctionInfoDetail = when (v) {
            is Map<*, *> -> AuctionInfoDetail.fromArray(v as Map<String, Any?>)
            is String -> AuctionInfoDetail.fromArray(jsonDecode(v))
            else -> AuctionInfoDetail.fromArray(emptyMap())
        }
    }
}

/** `ng_auction_bid.aux` jsonb (PHP `AuctionBidItemData`, JsonString). NullIsUndefined: ownerName/tryExtend. */
data class AuctionBidItemData(
    val ownerName: String? = null,
    val generalName: String,
    val tryExtendCloseDate: Boolean? = null,
) {
    fun toArray(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (ownerName != null) m["ownerName"] = ownerName
        m["generalName"] = generalName
        if (tryExtendCloseDate != null) m["tryExtendCloseDate"] = tryExtendCloseDate
        return m
    }

    fun toJson(): String = jsonEncode(toArray())

    companion object {
        fun fromArray(m: Map<String, Any?>): AuctionBidItemData = AuctionBidItemData(
            ownerName = m["ownerName"] as? String,
            generalName = m["generalName"] as? String ?: "",
            tryExtendCloseDate = m["tryExtendCloseDate"] as? Boolean,
        )
    }
}

/** `ng_auction_bid` row (PHP `AuctionBidItem`). `no` NullIsUndefined (omitted on INSERT). */
data class AuctionBidItem(
    val no: Int?,
    val auctionId: Int,
    val owner: Int?,
    val generalId: Int,
    val amount: Int,
    val date: String,
    val aux: AuctionBidItemData,
) {
    fun toArray(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        if (no != null) m["no"] = no
        m["auction_id"] = auctionId
        m["owner"] = owner
        m["general_id"] = generalId
        m["amount"] = amount
        m["date"] = date
        m["aux"] = aux.toJson()
        return m
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromArray(m: Map<String, Any?>): AuctionBidItem = AuctionBidItem(
            no = (m["no"] as? Number)?.toInt(),
            auctionId = (m["auction_id"] as? Number)?.toInt() ?: 0,
            owner = (m["owner"] as? Number)?.toInt(),
            generalId = (m["general_id"] as? Number)?.toInt() ?: 0,
            amount = (m["amount"] as? Number)?.toInt() ?: 0,
            date = m["date"]?.toString() ?: "",
            aux = when (val a = m["aux"]) {
                is Map<*, *> -> AuctionBidItemData.fromArray(a as Map<String, Any?>)
                is String -> AuctionBidItemData.fromArray(jsonDecode(a))
                else -> AuctionBidItemData.fromArray(emptyMap())
            },
        )
    }
}
