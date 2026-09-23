package opensamguk.logic.input

/**
 * 명망 사건 기록기 — 월단평([HwihaRenownAssessment])이 다음 월 경계에 읽어 비우는 집계에 사건을 한 건 더한다.
 *
 * 순수 함수다: 장수 meta 를 받아 새 meta 를 돌려준다. 저장(ChangeRecorder)은 호출부가 한다. 지금 쓰는 곳은
 * 상사(賞賜)의 결속 사건뿐이다.
 *
 * **같은 종류는 한 달에 한 번만** 센다(2026-09-23 사용자 지시). 종류별 마지막 기록 달을 [STAMPS_META_KEY]
 * 에 남긴다. 집계는 월단평이 비우지만 도장은 남으므로, 같은 달 두 번째 상사가 결속 사건을 또 쌓지 않는다.
 */
object HwihaRenownEvents {
    /** 월단평이 읽는 집계 키 — 엔진 `HwihaMonthlyAssessment.TALLY_META_KEY` 와 같은 값이어야 한다. */
    const val TALLY_META_KEY = "hwihaRenownTally"

    /** 종류별 마지막 기록 달(`YYYY-MM`). 월단평이 지우지 않는다. */
    const val STAMPS_META_KEY = "hwihaRenownEventStamps"

    /**
     * 사건 종류와 그것이 들어가는 집계 칸([HwihaRenownAssessment.Tally] 필드 이름). 전공·패전·縣 점령/상실은 엔진의
     * 전쟁 결과 경계(`HwihaWarOutcomeListener`)를 거쳐 기록 스트림이 쓴다 — 여기에는 상사(결속 사건)만 둔다.
     */
    enum class Kind(val tallyField: String) {
        REWARD_RECEIVED("bondEvent"),
    }

    private val tallyFields = listOf("warMerit", "domesticMerit", "office", "bondEvent",
        "defeat", "betrayal", "misrule", "dispatchRefusal")

    fun stampOf(year: Int, month: Int): String {
        require(month in 1..12) { "month must be within 1..12, was $month" }
        return "%04d-%02d".format(year, month)
    }

    /** 이번 달에 이 종류가 이미 기록됐는지. */
    fun alreadyRecorded(meta: Map<String, Any?>, kind: Kind, year: Int, month: Int): Boolean =
        (meta[STAMPS_META_KEY] as? Map<*, *>)?.get(kind.name) == stampOf(year, month)

    /**
     * [kind] 사건을 한 건 기록한 meta 를 돌려준다. 이번 달에 이미 기록했으면 null(변경 없음)이다.
     *
     * 읽을 수 없는 기존 집계 값은 0 으로 본다 — 월단평의 [HwihaRenownAssessment] 쪽 읽기와 같은 관용이다.
     * 월 경계·개인 턴에서 던지면 턴 루프가 멈춘다.
     */
    fun record(meta: Map<String, Any?>, kind: Kind, year: Int, month: Int): Map<String, Any?>? {
        if (alreadyRecorded(meta, kind, year, month)) return null
        val raw = meta[TALLY_META_KEY] as? Map<*, *> ?: emptyMap<String, Any?>()
        val tally = linkedMapOf<String, Any?>()
        for (field in tallyFields) {
            val current = (raw[field] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            tally[field] = if (field == kind.tallyField) Math.addExact(current, 1) else current
        }
        val stamps = linkedMapOf<String, Any?>()
        (meta[STAMPS_META_KEY] as? Map<*, *>)?.forEach { (key, value) ->
            if (key is String && value is String && Kind.entries.any { it.name == key }) stamps[key] = value
        }
        stamps[kind.name] = stampOf(year, month)
        return meta + mapOf(TALLY_META_KEY to tally, STAMPS_META_KEY to LinkedHashMap(stamps.toSortedMap()))
    }
}
