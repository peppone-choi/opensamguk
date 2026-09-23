package opensamguk.logic.input

/**
 * 월단평 사건 종류 — 정본 설계 §2.8 의 오르는 경로(전공·치적·관직·결속)와 떨어지는 경로(패전·배신·실정·발령 거절).
 *
 * [key] 는 [HwihaRenownAssessment.Tally]·`hwiha-renown-assessment-v1.json` 의 필드 이름과 같다. 종류와 라벨은
 * 월단평 발표(§2.8)에 실리는 공개 정보다 — 무슨 일이 있었는지(어느 조우·어느 縣)는 싣지 않는다.
 */
enum class HwihaRenownEventKind(val key: String, val label: String) {
    WAR_MERIT("warMerit", "전공"),
    DOMESTIC_MERIT("domesticMerit", "치적"),
    OFFICE("office", "관직"),
    BOND_EVENT("bondEvent", "결속"),
    DEFEAT("defeat", "패전"),
    BETRAYAL("betrayal", "배신"),
    MISRULE("misrule", "실정"),
    DISPATCH_REFUSAL("dispatchRefusal", "발령 거절"),
    ;

    /** 이 사건 한 건이 [curve] 에서 움직이는 명망(오르면 양수, 떨어지면 음수). */
    fun amountIn(curve: HwihaRenownAssessment.Curve): Int = when (this) {
        WAR_MERIT -> curve.warMerit
        DOMESTIC_MERIT -> curve.domesticMerit
        OFFICE -> curve.office
        BOND_EVENT -> curve.bondEvent
        DEFEAT -> curve.defeat
        BETRAYAL -> curve.betrayal
        MISRULE -> curve.misrule
        DISPATCH_REFUSAL -> curve.dispatchRefusal
    }

    companion object {
        fun ofKey(key: String): HwihaRenownEventKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 사건을 일으킨 원인. 2026-09-23 사용자 결정의 「큰 사건만」 목록이다 — 여기 없는 일은 월단평 사건이 아니다.
 *
 * 원인은 본인에게만 보인다(자기 집계). 순위표에는 [HwihaRenownEventKind] 만 싣는다 — 조우 승패는 시야 밖
 * 군단 정보일 수 있다(#343).
 */
enum class HwihaRenownEventSource(val kind: HwihaRenownEventKind, val label: String) {
    ENCOUNTER_VICTORY(HwihaRenownEventKind.WAR_MERIT, "조우 승리"),
    COUNTY_CAPTURE(HwihaRenownEventKind.WAR_MERIT, "縣 점령"),
    ENCOUNTER_DEFEAT(HwihaRenownEventKind.DEFEAT, "조우 패배"),
    COUNTY_LOSS(HwihaRenownEventKind.DEFEAT, "縣 상실"),
    COUNTY_INDICATOR_RISE(HwihaRenownEventKind.DOMESTIC_MERIT, "관할 縣 지표 상승"),
    /**
     * 실제 배반만 배신이다 — 원천은 아직 없다(훅 자리). 코스트 상한 초과 이탈은 배신이 아니며 월단평 사건도
     * 아니다(2026-09-23 사용자 결정 「이탈과 배신은 구분해야지」: 이탈 0, 배신 −8 유지).
     */
    DEFECTION(HwihaRenownEventKind.BETRAYAL, "배반"),
    SWORN_OATH(HwihaRenownEventKind.BOND_EVENT, "결의"),
    RECOMMENDATION(HwihaRenownEventKind.BOND_EVENT, "천거"),
    REWARD(HwihaRenownEventKind.BOND_EVENT, "상사"),
    /** 관직 임명 입력이 아직 없다 — 훅만 둔다. */
    OFFICE_APPOINTMENT(HwihaRenownEventKind.OFFICE, "관직 임명"),
    /** 실정의 원인은 아직 정해지지 않았다 — 훅만 둔다. */
    MISRULE(HwihaRenownEventKind.MISRULE, "실정"),
    DISPATCH_REFUSAL(HwihaRenownEventKind.DISPATCH_REFUSAL, "발령 거절"),
}

/** 집계 한 줄. [stamp] 는 사건이 일어난 달(`YYYY-MM`)이다. */
data class HwihaRenownEntry(
    val kind: HwihaRenownEventKind,
    val stamp: String,
    val source: HwihaRenownEventSource? = null,
) {
    init {
        HwihaRenownEvents.monthOrdinal(stamp)
        require(source == null || source.kind == kind) { "source $source does not belong to kind $kind" }
    }

    fun toMetaValue(): Map<String, Any?> = linkedMapOf("kind" to kind.key, "stamp" to stamp) +
        (source?.let { mapOf("source" to it.name) } ?: emptyMap())
}

/**
 * 월단평 사건 집계 — 장수 meta [META_KEY] 에 쌓이고 [HwihaRenownAssessment] 가 월 경계에서 적용한다.
 *
 * ### 한 달에 종류당 한 번(2026-09-23 사용자 결정)
 *
 * 같은 달에 같은 종류가 두 번 일어나도 한 건이다. 조우 세 번을 이긴 달도 전공 한 건, 발령을 두 번 거절한
 * 달도 발령 거절 한 건이다 — 월단평은 「그 달에 무엇을 했는가」의 품평이지 횟수 경쟁이 아니다.
 * 원인([HwihaRenownEventSource])은 먼저 기록된 것 하나만 남는다.
 *
 * ### 적용 창
 *
 * 월단평은 **그 달 이전**에 찍힌 사건만 적용한다([split]). 월 경계 안에서(월단평 앞이든 뒤든) 이번 달
 * 도장으로 찍힌 사건은 다음 달 월단평으로 넘어간다 — 같은 경계에서 기록과 적용이 섞이지 않는다.
 *
 * 저장 형태: `{"entries":[{"kind":"warMerit","stamp":"0200-01","source":"COUNTY_CAPTURE"}]}`.
 * 읽을 수 없는 줄은 건너뛴다 — 월 경계에서 던지면 턴 루프가 영구히 멈춘다.
 */
object HwihaRenownEvents {
    const val META_KEY = "hwihaRenownTally"
    private const val ENTRIES = "entries"
    private val STAMP = Regex("""(\d{4})-(\d{2})""")

    fun stampOf(year: Int, month: Int): String {
        require(year in 0..9999 && month in 1..12) { "invalid month $year-$month" }
        return "%04d-%02d".format(year, month)
    }

    /** `YYYY-MM` → 월 서수. 형식이 틀리면 [IllegalArgumentException]. */
    fun monthOrdinal(stamp: String): Int {
        val match = requireNotNull(STAMP.matchEntire(stamp)) { "invalid renown stamp $stamp" }
        val month = match.groupValues[2].toInt()
        require(month in 1..12) { "invalid renown stamp $stamp" }
        return match.groupValues[1].toInt() * 12 + month - 1
    }

    /** 저장된 집계. 없거나 읽을 수 없는 줄은 빠진다. */
    fun entries(meta: Map<String, Any?>): List<HwihaRenownEntry> {
        val raw = meta[META_KEY] as? Map<*, *> ?: return emptyList()
        val list = raw[ENTRIES] as? List<*> ?: return emptyList()
        return list.mapNotNull { row ->
            val value = row as? Map<*, *> ?: return@mapNotNull null
            val kind = (value["kind"] as? String)?.let(HwihaRenownEventKind::ofKey) ?: return@mapNotNull null
            val stamp = value["stamp"] as? String ?: return@mapNotNull null
            val source = when (val name = value["source"]) {
                null -> null
                is String -> HwihaRenownEventSource.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            try { HwihaRenownEntry(kind, stamp, source) } catch (_: IllegalArgumentException) { null }
        }.distinctBy { it.kind to it.stamp }
    }

    /** @property recorded false 면 같은 달 같은 종류가 이미 있어 [meta] 가 그대로다. */
    data class Recorded(val meta: Map<String, Any?>, val recorded: Boolean, val entry: HwihaRenownEntry)

    /**
     * 사건 한 건을 [meta] 의 집계에 더한다 — 같은 달([stamp]) 같은 종류([kind])가 이미 있으면 무동작.
     *
     * 순수 함수다. 호출부가 돌려받은 meta 를 저장한다(엔진은 ChangeRecorder 경로).
     */
    fun recordRenownEvent(
        meta: Map<String, Any?>,
        kind: HwihaRenownEventKind,
        stamp: String,
        source: HwihaRenownEventSource? = null,
    ): Recorded {
        val entry = HwihaRenownEntry(kind, stamp, source)
        val current = entries(meta)
        if (current.any { it.kind == kind && it.stamp == stamp }) return Recorded(meta, false, entry)
        return Recorded(withEntries(meta, current + entry), true, entry)
    }

    fun recordRenownEvent(meta: Map<String, Any?>, source: HwihaRenownEventSource, stamp: String): Recorded =
        recordRenownEvent(meta, source.kind, stamp, source)

    /** @property applied [currentStamp] 이전 달의 사건 — 이번 월단평이 적용한다. @property remaining 남길 사건. */
    data class Split(val applied: List<HwihaRenownEntry>, val remaining: List<HwihaRenownEntry>)

    fun split(meta: Map<String, Any?>, currentStamp: String): Split {
        val now = monthOrdinal(currentStamp)
        val (applied, remaining) = entries(meta).partition { monthOrdinal(it.stamp) < now }
        return Split(applied, remaining)
    }

    fun tallyOf(entries: List<HwihaRenownEntry>): HwihaRenownAssessment.Tally {
        fun count(kind: HwihaRenownEventKind) = entries.count { it.kind == kind }
        return HwihaRenownAssessment.Tally(
            warMerit = count(HwihaRenownEventKind.WAR_MERIT),
            domesticMerit = count(HwihaRenownEventKind.DOMESTIC_MERIT),
            office = count(HwihaRenownEventKind.OFFICE),
            bondEvent = count(HwihaRenownEventKind.BOND_EVENT),
            defeat = count(HwihaRenownEventKind.DEFEAT),
            betrayal = count(HwihaRenownEventKind.BETRAYAL),
            misrule = count(HwihaRenownEventKind.MISRULE),
            dispatchRefusal = count(HwihaRenownEventKind.DISPATCH_REFUSAL),
        )
    }

    /** 집계를 [entries] 로 바꾼다. 비면 키를 지운다 — 빈 집계를 남기지 않는다. */
    fun withEntries(meta: Map<String, Any?>, entries: List<HwihaRenownEntry>): Map<String, Any?> {
        val next = LinkedHashMap(meta)
        if (entries.isEmpty()) next.remove(META_KEY)
        else next[META_KEY] = linkedMapOf(ENTRIES to entries.map { it.toMetaValue() })
        return next
    }
}

/**
 * 다른 흐름(조우 판정·縣 점령 — hwiha-s3-core)이 부르는 월단평 훅. **순수 함수**다: 장수 meta 를 받아
 * 바뀐 meta 만 돌려준다. 저장·로그는 호출부 몫이다(엔진 어댑터 `HwihaRenownEventRecorder`).
 *
 * 결과는 장수 id 오름차순이다 — 같은 입력이 같은 순서로 쓰여야 리플레이가 갈라지지 않는다.
 * 같은 달 같은 종류가 이미 있는 장수는 결과에 없다([HwihaRenownEvents.recordRenownEvent]).
 */
object HwihaRenownHooks {
    data class MetaUpdate(val generalId: Int, val meta: Map<String, Any?>, val entry: HwihaRenownEntry)

    /**
     * 조우 전투 한 판이 판정된 뒤 한 번. 이긴 쪽 지휘 장수는 전공(조우 승리), 진 쪽은 패전(조우 패배).
     * 무승부·판정 불가면 부르지 않는다. 두 목록은 겹칠 수 없다.
     *
     * @param metaOf 장수 meta. null 이면(없는 장수) 건너뛴다.
     */
    fun onEncounterResolved(
        winnerIds: Collection<Int>,
        loserIds: Collection<Int>,
        year: Int,
        month: Int,
        metaOf: (Int) -> Map<String, Any?>?,
    ): List<MetaUpdate> = record(
        winnerIds, HwihaRenownEventSource.ENCOUNTER_VICTORY,
        loserIds, HwihaRenownEventSource.ENCOUNTER_DEFEAT, year, month, metaOf,
    )

    /**
     * 縣 함락이 정산된 뒤 한 번. 점령한 장수는 전공(縣 점령), 그 縣 을 관할하던 장수는 패전(縣 상실).
     * 관할 장수는 [countyHolderIds] 로 구한다(점령 **전** 소유 세력 기준).
     */
    fun onCountyCaptured(
        capturerIds: Collection<Int>,
        previousHolderIds: Collection<Int>,
        year: Int,
        month: Int,
        metaOf: (Int) -> Map<String, Any?>?,
    ): List<MetaUpdate> = record(
        capturerIds, HwihaRenownEventSource.COUNTY_CAPTURE,
        previousHolderIds, HwihaRenownEventSource.COUNTY_LOSS, year, month, metaOf,
    )

    /**
     * 縣의 관할 장수 — 발령을 수락해 이 縣 을 부임지로 받은 장수([HwihaCountyAssignment])이고, 그 발령
     * 세력이 [ownerNationId] 인 사람. 읽을 수 없는 발령 meta 는 관할이 아니다. id 오름차순.
     */
    fun countyHolderIds(countyId: Int, ownerNationId: Int, metaById: Map<Int, Map<String, Any?>>): List<Int> =
        metaById.entries.filter { (_, meta) ->
            val assignment = try { HwihaCountyAssignment.read(meta) } catch (_: IllegalArgumentException) { null }
            assignment != null && assignment.countyId == countyId && assignment.nationId == ownerNationId
        }.map { it.key }.sorted()

    private fun record(
        risingIds: Collection<Int>,
        rising: HwihaRenownEventSource,
        fallingIds: Collection<Int>,
        falling: HwihaRenownEventSource,
        year: Int,
        month: Int,
        metaOf: (Int) -> Map<String, Any?>?,
    ): List<MetaUpdate> {
        require(risingIds.toSet().intersect(fallingIds.toSet()).isEmpty()) {
            "a general cannot be on both sides of one event"
        }
        val stamp = HwihaRenownEvents.stampOf(year, month)
        val sources = risingIds.associateWith { rising } + fallingIds.associateWith { falling }
        return sources.keys.sorted().mapNotNull { id ->
            val meta = metaOf(id) ?: return@mapNotNull null
            val result = HwihaRenownEvents.recordRenownEvent(meta, sources.getValue(id), stamp)
            if (result.recorded) MetaUpdate(id, result.meta, result.entry) else null
        }
    }
}

/**
 * 치적 판정 — 관할 縣 의 호구·전답·시장이 한 달 동안 올랐는가.
 *
 * 한 달은 **순 경계 사건을 뺀** 창이다: 월 경계의 월간 사건(반기 도시 성장 등)이 끝난 뒤 값을 열고, 다음
 * 월 경계의 월간 사건 **전** 값으로 닫는다. 그래야 가만히 있어도 오르는 자연 성장이 치적이 되지 않는다.
 *
 * [MIN_RISE_BASIS_POINTS] 는 2026-09-23 사용자 결정으로 확정됐다(`data/curated/han/hwiha-renown-events-v1.json`).
 */
object HwihaDomesticMerit {
    /** 확정 — 지표 상한의 2%. 근거와 상태는 데이터 파일에 있다. */
    const val MIN_RISE_BASIS_POINTS = 200

    data class Indicators(val population: Int, val agriculture: Int, val commerce: Int)

    /** 세 지표 가운데 하나라도 상한의 [minRiseBasisPoints] 이상 올랐으면 true. 상한이 0 이하인 지표는 보지 않는다. */
    fun risen(
        open: Indicators,
        close: Indicators,
        max: Indicators,
        minRiseBasisPoints: Int = MIN_RISE_BASIS_POINTS,
    ): Boolean {
        require(minRiseBasisPoints > 0) { "threshold must be positive" }
        fun rose(from: Int, to: Int, cap: Int): Boolean =
            cap > 0 && to > from && (to.toLong() - from) * 10_000L >= cap.toLong() * minRiseBasisPoints
        return rose(open.population, close.population, max.population) ||
            rose(open.agriculture, close.agriculture, max.agriculture) ||
            rose(open.commerce, close.commerce, max.commerce)
    }
}
