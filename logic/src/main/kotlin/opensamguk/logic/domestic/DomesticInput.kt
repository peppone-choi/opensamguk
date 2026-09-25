package opensamguk.logic.domestic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.logic.input.HwihaFlatArguments

/**
 * 배치 자리(§4·§8.2, 2026-09-23 사용자 결정). 사람 장수는 배치하지 않는다 — 그것은 발령이다.
 * 太守·刺史는 관직(2층)이라 이 목록에 없다. [NONE] 은 자리를 비우는 입력이다.
 */
enum class PlacementPost(val label: String) {
    MAGISTRATE("현령"), CORPS_COMMANDER("군단장"), ENVOY("사자"), SCOUT("정찰"), NONE("해제");
}

/** 縣 방침 6(2026-09-23 사용자 결정: §4 와 §8.2 목록의 합). 郡에 걸면 소속 縣 전체에 적용된다. */
enum class CountyPolicy(val label: String) {
    AGRICULTURE("권농"), COMMERCE("중상"), HEAVY_TAX("중세"), RELIEF("휼민"), MILITARY_FARM("둔전"), LEVY("징발");
}

/** 군단 방침 5(2026-09-23 사용자 결정). 수비는 방어 측 진형 방침이다(§6.3). */
enum class CorpsPolicy(val label: String) {
    DRILL("조련"), ASSAULT("공략"), DEFEND("수비"), INTERCEPT("요격"), EVADE("회피");
}

/**
 * 공사 9종(2026-09-23 사용자 결정): §4(수리·둔전·성방·도로·역참·창고)와 §8.2(곡창·역참·망루봉화·성벽관문·병영·시장수운
 * + 수리·둔전)의 합집합에서 곡창은 창고로, 성벽관문은 성방으로 합쳤다. 망루봉화는 §8.2 가 한 건물로 적은 그대로 하나다.
 */
enum class DomesticWork(val label: String) {
    IRRIGATION("수리"), MILITARY_FARM("둔전"), FORTIFICATION("성방"), ROAD("도로"), POST_STATION("역참"),
    WAREHOUSE("창고"), WATCHTOWER_BEACON("망루봉화"), BARRACKS("병영"), MARKET_WATERWAY("시장수운");
}

sealed interface PlacementTarget {
    data class County(val countyId: Int) : PlacementTarget { init { require(countyId > 0) } }
    data class Province(val provinceId: String) : PlacementTarget { init { require(DomesticIds.province(provinceId)) } }
    data class Nation(val nationId: Int) : PlacementTarget { init { require(nationId > 0) } }
    /** 군단장은 주인 곁으로 모이고, 해제는 목적지가 없다. */
    data object None : PlacementTarget

    fun toMetaValue(): Map<String, Any> = when (this) {
        is County -> linkedMapOf("countyId" to countyId)
        is Province -> linkedMapOf("provinceId" to provinceId)
        is Nation -> linkedMapOf("nationId" to nationId)
        None -> linkedMapOf()
    }

    companion object {
        fun read(raw: Any?): PlacementTarget {
            val value = raw as? Map<*, *> ?: throw IllegalArgumentException("invalid placement target")
            return when (value.keys) {
                emptySet<Any>() -> None
                setOf("countyId") -> County(value["countyId"] as? Int ?: throw IllegalArgumentException("invalid county"))
                setOf("provinceId") -> Province(value["provinceId"] as? String ?: throw IllegalArgumentException("invalid province"))
                setOf("nationId") -> Nation(value["nationId"] as? Int ?: throw IllegalArgumentException("invalid nation"))
                else -> throw IllegalArgumentException("invalid placement target fields")
            }
        }

        /** 자리마다 목적지 꼴이 하나다. */
        fun matches(post: PlacementPost, target: PlacementTarget): Boolean = when (post) {
            PlacementPost.MAGISTRATE -> target is County
            PlacementPost.SCOUT -> target is Province
            PlacementPost.ENVOY -> target is Nation
            PlacementPost.CORPS_COMMANDER, PlacementPost.NONE -> target == None
        }
    }
}

sealed interface PolicyTarget {
    data class County(val countyId: Int) : PolicyTarget { init { require(countyId > 0) } }
    data class Commandery(val commanderyId: String) : PolicyTarget { init { require(DomesticIds.commandery(commanderyId)) } }
    data class Corps(val orderId: String) : PolicyTarget { init { require(DomesticIds.order(orderId)) } }
}

data class PlacementRequest(val actorId: Int, val cardId: Int, val post: PlacementPost, val target: PlacementTarget) {
    init { require(actorId > 0 && cardId > 0 && PlacementTarget.matches(post, target)) }
}

/** [policy] 가 null 이면 걸려 있는 방침을 거둔다. 縣·郡은 [CountyPolicy], 군단은 [CorpsPolicy] 이름이다. */
data class PolicyRequest(val actorId: Int, val target: PolicyTarget, val policy: String?) {
    init {
        require(actorId > 0)
        require(policy == null || when (target) {
            is PolicyTarget.Corps -> CorpsPolicy.entries.any { it.name == policy }
            else -> CountyPolicy.entries.any { it.name == policy }
        })
    }
}

data class WorkRequest(
    val actorId: Int, val countyId: Int, val work: DomesticWork,
    val edgeId: String? = null, val row: Int? = null, val col: Int? = null,
) {
    init {
        require(actorId > 0 && countyId > 0)
        require(edgeId == null || (work == DomesticWork.ROAD || work == DomesticWork.FORTIFICATION) &&
            DomesticIds.order(edgeId))
        require((row == null) == (col == null) && (row == null ||
            (work == DomesticWork.FORTIFICATION && edgeId != null && row >= 0 && col!! >= 0)))
    }
}

internal object DomesticIds {
    private val provincePattern = Regex("[A-Za-z0-9._:-]{1,64}")
    private val orderPattern = Regex("[A-Za-z0-9._:-]{1,128}")
    fun province(value: String) = provincePattern.matches(value)
    fun order(value: String) = orderPattern.matches(value)
    /** 郡 식별자는 런타임 지도 `meta.junCh`(한자) 그대로다. 제어 문자·공백·과도한 길이만 막는다. */
    fun commandery(value: String) = value.length in 1..32 && value.none { it.isWhitespace() || it.isISOControl() }
}

/**
 * 배치·방침·공사 접수 인자. 행위자 ID 는 인증된 호출자가 주고 본문에 없다. 키는 정확히 맞아야 하며
 * (중복·미지 키·문자열 숫자·소수·넘침·뒤따르는 내용은 거절) 같은 입력은 같은 canonical JSON 으로 저장된다.
 */
object DomesticInput {
    const val PLACEMENT = "placement.assign"
    const val POLICY = "policy.set"
    const val WORK = "work.start"
    const val REDUCE = "work.reduce"
    val INPUT_IDS = setOf(PLACEMENT, POLICY, WORK, REDUCE)

    fun parsePlacement(actorId: Int, raw: String?): PlacementRequest? = parse(actorId, raw) { fields ->
        val post = enumOf<PlacementPost>(fields["post"]) ?: return@parse null
        val cardId = positiveId(fields["cardId"]) ?: return@parse null
        val target = when (post) {
            PlacementPost.MAGISTRATE -> {
                if (fields.keys != setOf("cardId", "post", "countyId")) return@parse null
                PlacementTarget.County(positiveId(fields["countyId"]) ?: return@parse null)
            }
            PlacementPost.SCOUT -> {
                if (fields.keys != setOf("cardId", "post", "provinceId")) return@parse null
                PlacementTarget.Province(text(fields["provinceId"])?.takeIf(DomesticIds::province) ?: return@parse null)
            }
            PlacementPost.ENVOY -> {
                if (fields.keys != setOf("cardId", "post", "nationId")) return@parse null
                PlacementTarget.Nation(positiveId(fields["nationId"]) ?: return@parse null)
            }
            PlacementPost.CORPS_COMMANDER, PlacementPost.NONE -> {
                if (fields.keys != setOf("cardId", "post")) return@parse null
                PlacementTarget.None
            }
        }
        PlacementRequest(actorId, cardId, post, target)
    }

    fun parsePolicy(actorId: Int, raw: String?): PolicyRequest? = parse(actorId, raw) { fields ->
        val scope = text(fields["scope"]) ?: return@parse null
        val policy = text(fields["policy"]) ?: return@parse null
        val (target, allowed) = when (scope) {
            "COUNTY" -> {
                if (fields.keys != setOf("scope", "countyId", "policy")) return@parse null
                PolicyTarget.County(positiveId(fields["countyId"]) ?: return@parse null) to CountyPolicy.entries.map { it.name }
            }
            "COMMANDERY" -> {
                if (fields.keys != setOf("scope", "commanderyId", "policy")) return@parse null
                PolicyTarget.Commandery(text(fields["commanderyId"])?.takeIf(DomesticIds::commandery) ?: return@parse null) to
                    CountyPolicy.entries.map { it.name }
            }
            "CORPS" -> {
                if (fields.keys != setOf("scope", "orderId", "policy")) return@parse null
                PolicyTarget.Corps(text(fields["orderId"])?.takeIf(DomesticIds::order) ?: return@parse null) to
                    CorpsPolicy.entries.map { it.name }
            }
            else -> return@parse null
        }
        val value = if (policy == NONE) null else policy.takeIf { it in allowed } ?: return@parse null
        PolicyRequest(actorId, target, value)
    }

    fun parseWork(actorId: Int, raw: String?): WorkRequest? = parse(actorId, raw) { fields ->
        val work = enumOf<DomesticWork>(fields["work"]) ?: return@parse null
        val keys = when (work) {
            DomesticWork.ROAD -> setOf("countyId", "work", "edgeId")
            DomesticWork.FORTIFICATION -> setOf("countyId", "work", "edgeId", "row", "col")
            else -> setOf("countyId", "work")
        }
        if (fields.keys != keys && fields.keys != setOf("countyId", "work")) return@parse null
        val row = if ("row" in fields) nonnegativeId(fields["row"]) ?: return@parse null else null
        val col = if ("col" in fields) nonnegativeId(fields["col"]) ?: return@parse null else null
        WorkRequest(actorId, positiveId(fields["countyId"]) ?: return@parse null, work,
            text(fields["edgeId"]), row, col)
    }

    fun canonicalJson(request: PlacementRequest): String = buildJsonObject {
        put("cardId", request.cardId)
        put("post", request.post.name)
        when (val target = request.target) {
            is PlacementTarget.County -> put("countyId", target.countyId)
            is PlacementTarget.Province -> put("provinceId", target.provinceId)
            is PlacementTarget.Nation -> put("nationId", target.nationId)
            PlacementTarget.None -> Unit
        }
    }.toString()

    fun canonicalJson(request: PolicyRequest): String = buildJsonObject {
        when (val target = request.target) {
            is PolicyTarget.County -> { put("scope", "COUNTY"); put("countyId", target.countyId) }
            is PolicyTarget.Commandery -> { put("scope", "COMMANDERY"); put("commanderyId", target.commanderyId) }
            is PolicyTarget.Corps -> { put("scope", "CORPS"); put("orderId", target.orderId) }
        }
        put("policy", request.policy ?: NONE)
    }.toString()

    fun canonicalJson(request: WorkRequest): String = buildJsonObject {
        put("countyId", request.countyId)
        put("work", request.work.name)
        request.edgeId?.let { put("edgeId", it) }
        request.row?.let { put("row", it) }
        request.col?.let { put("col", it) }
    }.toString()

    const val NONE = "NONE"

    private fun <T> parse(actorId: Int, raw: String?, read: (Map<String, JsonElement>) -> T?): T? {
        if (actorId <= 0 || raw == null) return null
        return try { read(HwihaFlatArguments(raw).read()) } catch (_: IllegalArgumentException) { null }
    }

    private fun positiveId(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || !Regex("[1-9][0-9]*").matches(primitive.content)) return null
        return primitive.content.toIntOrNull()
    }

    private fun nonnegativeId(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || !Regex("(0|[1-9][0-9]*)").matches(primitive.content)) return null
        return primitive.content.toIntOrNull()
    }

    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private inline fun <reified E : Enum<E>> enumOf(value: JsonElement?): E? =
        text(value)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
}
