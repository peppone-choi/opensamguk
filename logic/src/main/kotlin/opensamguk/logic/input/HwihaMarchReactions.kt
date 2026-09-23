package opensamguk.logic.input

/**
 * 요격·회피 방침을 건 출전 군단 한 개(군단 방침 `INTERCEPT`/`EVADE` 의 현행). [since] 는 방침이 효력을 얻은 순이다.
 * 이 기록은 「그 군단이 반응 방침을 걸었다」는 사실뿐이다 — 요격 범위·물러나기 판정은 조우 소비자가 정한다.
 */
data class HwihaReactionOrder(
    val orderId: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val nationId: Int,
    val since: HwihaPhase,
) {
    init { require(HwihaDomesticIds.order(orderId) && ownerGeneralId > 0 && commanderGeneralId > 0 && nationId >= 0) }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("orderId" to orderId, "ownerGeneralId" to ownerGeneralId,
        "commanderGeneralId" to commanderGeneralId, "nationId" to nationId, "since" to since.toMetaValue())

    companion object {
        private val fields = setOf("orderId", "ownerGeneralId", "commanderGeneralId", "nationId", "since")
        fun read(raw: Any?): HwihaReactionOrder {
            val value = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid HWIHA reaction order")
            require(value.keys == fields) { "Invalid HWIHA reaction order fields" }
            return HwihaReactionOrder(value["orderId"] as? String ?: bad(), value["ownerGeneralId"] as? Int ?: bad(),
                value["commanderGeneralId"] as? Int ?: bad(), value["nationId"] as? Int ?: bad(), HwihaPhase.read(value["since"]))
        }
        private fun bad(): Nothing = throw IllegalArgumentException("Invalid HWIHA reaction order")
    }
}

/**
 * 월드 meta `hwihaMarchReactions`. 버전 1 의 세 목록 가운데 `interceptions`(요격)·`avoidanceOrders`(회피)는 군단 방침이
 * 채운다(지휘 장수 id, 명령 id 순). `installedSchemes`(설치 계책)는 아직 입력·판정 계약이 없어 비어 있어야 한다.
 * 키가 없거나 꼴·항목이 어긋나면 빈 목록으로 바꿔 읽지 않는다.
 */
sealed interface HwihaMarchReactions {
    val interceptions: List<HwihaReactionOrder>
    val avoidanceOrders: List<HwihaReactionOrder>

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "installedSchemes" to emptyList<Any>(),
        "interceptions" to interceptions.map { it.toMetaValue() },
        "avoidanceOrders" to avoidanceOrders.map { it.toMetaValue() },
    )

    data object Empty : HwihaMarchReactions {
        override val interceptions: List<HwihaReactionOrder> = emptyList()
        override val avoidanceOrders: List<HwihaReactionOrder> = emptyList()
    }

    /** 반응 방침이 하나 이상 걸린 목록. 조우 소비자가 이 기록을 해결할 수 있어야 통행을 판정할 수 있다. */
    data class Inventory(override val interceptions: List<HwihaReactionOrder>, override val avoidanceOrders: List<HwihaReactionOrder>) :
        HwihaMarchReactions {
        init {
            require(interceptions.isNotEmpty() || avoidanceOrders.isNotEmpty()) { "an empty inventory is Empty" }
            for (list in listOf(interceptions, avoidanceOrders)) {
                require(list == list.sortedWith(compareBy({ it.commanderGeneralId }, { it.orderId }))) { "reaction orders must be sorted" }
                require(list.map { it.orderId }.distinct().size == list.size)
            }
            require(interceptions.none { i -> avoidanceOrders.any { it.orderId == i.orderId } }) { "a corps has one reaction policy" }
        }
    }

    companion object {
        const val META_KEY = "hwihaMarchReactions"
        private val fields = setOf("version", "installedSchemes", "interceptions", "avoidanceOrders")

        fun of(interceptions: List<HwihaReactionOrder>, avoidanceOrders: List<HwihaReactionOrder>): HwihaMarchReactions {
            val order = compareBy<HwihaReactionOrder>({ it.commanderGeneralId }, { it.orderId })
            return if (interceptions.isEmpty() && avoidanceOrders.isEmpty()) Empty
            else Inventory(interceptions.sortedWith(order), avoidanceOrders.sortedWith(order))
        }

        fun read(meta: Map<String, Any?>): HwihaMarchReactions? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(raw.keys == fields && raw["version"] == 1) { "Unsupported march reaction schema" }
            val schemes = raw["installedSchemes"] as? List<*> ?: invalid()
            // Installed schemes require their own validated execution contract; never discard entries.
            require(schemes.isEmpty()) { "Installed schemes cannot be resolved by this reader" }
            val interceptions = (raw["interceptions"] as? List<*> ?: invalid()).map(HwihaReactionOrder::read)
            val avoidance = (raw["avoidanceOrders"] as? List<*> ?: invalid()).map(HwihaReactionOrder::read)
            return if (interceptions.isEmpty() && avoidance.isEmpty()) Empty else Inventory(interceptions, avoidance)
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march reaction state")
    }
}
