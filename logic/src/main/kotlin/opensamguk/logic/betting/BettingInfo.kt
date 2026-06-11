package opensamguk.logic.betting

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 베팅 정보 — PHP `sammo\DTO\BettingInfo` 의 Kotlin 포팅.
 *
 * KV 스토리지에 JSON으로 저장되는 베팅 마스터 데이터.
 * Key: `betting:{id}` (KV Storage)
 *
 * @property id 베팅 고유 ID (Int — PHP `Betting::$bettingID`)
 * @property type 베팅 타입 문자열 ("bettingNation" | "tournament" | …)
 * @property name 베팅 이름 (로그 표시용)
 * @property finished 보상 분배 완료 여부
 * @property selectCnt 선택해야 할 후보 수 (1 = 단일, 2+ = 복합)
 * @property isExclusive 독점 보상 여부 (true = 1명이 전부, false = match-point 기반 분배)
 * @property reqInheritancePoint true = 유산포인트 베팅, false = 금 베팅
 * @property openYearMonth 개시 연월 (PHP `Util::joinYearMonth` = year*12 + month)
 * @property closeYearMonth 마감 연월 (베팅 종료 시점)
 * @property candidates 후보 목록 (key = 후보 인덱스, value = SelectItem)
 * @property winner 당첨 후보 인덱스 목록 (보상 분배 후 설정)
 */
@Serializable
data class BettingInfo(
    val id: Int,
    val type: String,
    val name: String,
    var finished: Boolean = false,
    val selectCnt: Int = 1,
    val isExclusive: Boolean? = null,
    val reqInheritancePoint: Boolean = false,
    val openYearMonth: Int = 0,
    var closeYearMonth: Int = 0,
    val candidates: Map<Int, SelectItem> = linkedMapOf(),
    var winner: List<Int>? = null,
) {
    companion object {
        /** KV 스토리지에서 사용하는 키 접두사 */
        const val KV_KEY_PREFIX = "betting:"

        /**
         * game_kv(table='betting') value 맵 → [BettingInfo] — PHP `BettingInfo::fromArray`
         * (DTO/BettingInfo.php:13-28) 동형 디코드. logic 모듈은 serialization 컴파일러 플러그인을
         * 적용하지 않으므로(런타임 라이브러리만) `jsonDecode` 맵 경유가 정본 디코드 경로다.
         * candidates 키 순서는 입력 맵(LinkedHashMap) 삽입 순서를 보존한다.
         */
        fun fromKvMap(map: Map<String, Any?>): BettingInfo? {
            // PHP fromArray(LDTO)는 필수 필드 결손 시 실패한다(DTO/BettingInfo.php:13-28) —
            // 관용 기본값으로 selectCnt=1/open-forever 베팅을 발명하지 않는다(마스터-부재 취급).
            val id = (map["id"] as? Number)?.toInt() ?: return null
            val type = map["type"] as? String ?: return null
            val name = map["name"] as? String ?: return null
            val finished = map["finished"] as? Boolean ?: return null
            val selectCnt = (map["selectCnt"] as? Number)?.toInt() ?: return null
            val reqInheritancePoint = map["reqInheritancePoint"] as? Boolean ?: return null
            val openYearMonth = (map["openYearMonth"] as? Number)?.toInt() ?: return null
            val closeYearMonth = (map["closeYearMonth"] as? Number)?.toInt() ?: return null
            val rawCandidates = map["candidates"] as? Map<*, *> ?: return null
            val candidates = LinkedHashMap<Int, SelectItem>()
            for ((k, v) in rawCandidates) {
                val key = (k as? String)?.toIntOrNull() ?: (k as? Number)?.toInt() ?: continue
                val c = v as? Map<*, *> ?: continue
                @Suppress("UNCHECKED_CAST")
                candidates[key] = SelectItem(
                    title = c["title"] as? String ?: "",
                    info = c["info"] as? String,
                    isHtml = c["isHtml"] as? Boolean,
                    aux = c["aux"] as? Map<String, Any?>,
                )
            }
            return BettingInfo(
                id = id,
                type = type,
                name = name,
                finished = finished,
                selectCnt = selectCnt,
                isExclusive = map["isExclusive"] as? Boolean,
                reqInheritancePoint = reqInheritancePoint,
                openYearMonth = openYearMonth,
                closeYearMonth = closeYearMonth,
                candidates = candidates,
                winner = (map["winner"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() },
            )
        }
    }
}

/**
 * 후보 아이템 — PHP `sammo\DTO\SelectItem` 의 Kotlin 포팅.
 *
 * @property title 표시 이름
 * @property info 부가 정보 (nullable)
 * @property isHtml HTML 여부 (nullable)
 * @property aux 부가 데이터 맵 (nullable — nation 베팅의 경우 nation ID 등)
 */
@Serializable
data class SelectItem(
    val title: String,
    val info: String? = null,
    val isHtml: Boolean? = null,
    val aux: Map<String, @Serializable(with = AnySerializer::class) Any?>? = null,
)

/**
 * 베팅 참여 레코드 — PHP `sammo\DTO\BettingItem` + `ng_betting` 행의 Kotlin 포팅.
 *
 * @property rowId DB 행 ID (nullable — 신규 삽입 시 null)
 * @property bettingId 베팅 마스터 ID
 * @property generalId 장수 ID (NPC/시스템 베팅 시 0)
 * @property userId 사용자 ID (NPC/시스템 베팅 시 null)
 * @property bettingType 베팅 선택 키 (JSON 인코딩된 Int 배열, e.g. "[1,2]")
 * @property amount 베팅 금액
 */
data class BettingItem(
    val rowId: Int? = null,
    val bettingId: Int,
    val generalId: Int,
    val userId: Int? = null,
    val bettingType: String,
    val amount: Int,
)

/**
 * 보상 계산 결과 — [BettingEngine.calcReward] 의 출력.
 *
 * @property generalId 장수 ID
 * @property userId 사용자 ID (nullable)
 * @property amount 지급액 (Float — PHP `amount * multiplier` 그대로)
 * @property matchPoint 맞춘 개수 (selectCnt = 전체 당첨, 그 미만 = 부분 당첨)
 */
data class RewardItem(
    val generalId: Int,
    val userId: Int?,
    val amount: Double,
    val matchPoint: Int,
)

/**
 * 베팅 결과 — [FinishNationBettingAction] 의 반환 타입 (PHP parity: 로직 레이어는 Unit,
 * 이 타입은 외부 호출자가 결과를 확인할 때 사용).
 */
sealed class BettingResult {
    /** 아직 마감 조건 미달 */
    data class NotYet(val remainingNations: Int) : BettingResult()
    /** 베팅 정보를 찾을 수 없음 */
    data object NotFound : BettingResult()
    /** 보상 분배 완료 */
    data class PayoutDone(val winnerCount: Int, val totalPayout: Int) : BettingResult()
    /** 베팅 취소 (승자 없음 — 전원 환불) */
    data class Cancelled(val refundCount: Int) : BettingResult()
}

// ── kotlinx.serialization polymorphic Any helper ──

/** A best-effort serializer for the `aux` Map values (string/int/float/bool/null). */
object AnySerializer : KSerializer<Any?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any?) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("AnySerializer requires JsonEncoder")
        when (value) {
            null -> jsonEncoder.encodeJsonElement(JsonNull)
            is String -> jsonEncoder.encodeString(value)
            is Int -> jsonEncoder.encodeInt(value)
            is Long -> jsonEncoder.encodeLong(value)
            is Double -> jsonEncoder.encodeDouble(value)
            is Float -> jsonEncoder.encodeFloat(value)
            is Boolean -> jsonEncoder.encodeBoolean(value)
            else -> jsonEncoder.encodeString(value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Any? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("AnySerializer requires JsonDecoder")
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        return primitive.booleanOrNull
            ?: primitive.intOrNull
            ?: primitive.longOrNull
            ?: primitive.doubleOrNull
            ?: primitive.content
    }
}
