package opensamguk.logic.v2.geo

import java.time.LocalDate

/**
 * V2 G0-A 행정 기준선 계약 (OPENSAM-36 / T1-B01, T1-E01).
 *
 * 순수 in-memory 계약이다. DB write·마이그레이션·flush 채널은 이 티켓의 범위가 아니며
 * v1 동결 회귀 코어(RNG/로그/골든)와 완전히 분리돼 있다.
 */

/** 근거 강도. 근거 없는 단정을 막기 위해 모든 시간 범위 레코드가 보유한다. */
enum class Confidence { ATTESTED, INFERRED, RECONSTRUCTED, GAME_REFERENCE }

/**
 * 반열림 유효 기간 `[validFrom, validTo)`. `validTo == null`은 열린 끝(현재까지 유효).
 * 겹침 판정의 유일 정본 — validator가 전부 이 함수를 경유한다.
 */
data class ValidTime(val validFrom: LocalDate, val validTo: LocalDate? = null) {
    init {
        require(validTo == null || validTo > validFrom) {
            "validTo must be after validFrom (half-open interval): $validFrom..$validTo"
        }
    }

    fun overlaps(other: ValidTime): Boolean =
        (validTo == null || other.validFrom < validTo) &&
            (other.validTo == null || validFrom < other.validTo)

    fun contains(date: LocalDate): Boolean =
        date >= validFrom && (validTo == null || date < validTo)
}

/** 시기별 이름. 개명은 새 이름 항목으로 표현하고 ID는 보존한다. */
data class TemporalName(
    val name: String,
    val time: ValidTime,
    val sourceRefs: List<String> = emptyList(),
)

/** T1-B01 — 주·군·국·현 등 시간 범위를 가진 행정 단위. */
data class TemporalAdministrativeUnit(
    val id: String,
    val names: List<TemporalName>,
    val level: AdministrativeLevel,
    val parentUnitId: String?,
    val time: ValidTime,
    val sourceRefs: List<String> = emptyList(),
    val confidence: Confidence = Confidence.ATTESTED,
) {
    init {
        require(names.isNotEmpty()) { "TemporalAdministrativeUnit $id must have at least one name" }
        require(parentUnitId != id) { "TemporalAdministrativeUnit $id cannot be its own parent" }
    }
}

enum class AdministrativeLevel { PROVINCE, COMMANDERY, KINGDOM, COUNTY, MARQUISATE }

/**
 * T1-E01 — 140년 baseline에 접어 시나리오 snapshot을 만드는 행정 변경.
 * [priority]는 T1-E02의 접기 순서(effectiveFrom → type priority → change id) 두 번째 키다.
 */
enum class AdministrativeChangeType(val priority: Int) {
    CREATE(10),
    SPLIT(20),
    MERGE(30),
    RENAME(40),
    REPARENT(50),
    MOVE_SEAT(60),
    RETIRE(70),
}

/** 사료 날짜의 정밀도. 불확실 날짜는 branch 규칙으로 갈라지므로 버리지 않고 기록한다. */
enum class DatePrecision { EXACT, YEAR, DECADE, UNCERTAIN }

/** T1-E01 — AdministrativeChange. */
data class AdministrativeChange(
    val id: String,
    val type: AdministrativeChangeType,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val datePrecision: DatePrecision = DatePrecision.EXACT,
    val dependsOnChangeIds: List<String> = emptyList(),
    val subjectUnitIds: List<String> = emptyList(),
    val predecessorUnitIds: List<String> = emptyList(),
    val successorUnitIds: List<String> = emptyList(),
    val beforeStateHash: String? = null,
    val statePatch: Map<String, String> = emptyMap(),
    val sourceRefs: List<String> = emptyList(),
    val confidence: Confidence = Confidence.ATTESTED,
) {
    init {
        require(id !in dependsOnChangeIds) { "AdministrativeChange $id cannot depend on itself" }
        // [effectiveFrom, effectiveTo) 도 ValidTime 과 같은 반열림 규칙을 따른다 — 역전·0길이 창 금지.
        require(effectiveTo == null || effectiveTo > effectiveFrom) {
            "AdministrativeChange $id effective window must be half-open: $effectiveFrom..$effectiveTo"
        }
        require(subjectUnitIds.isNotEmpty() || successorUnitIds.isNotEmpty()) {
            "AdministrativeChange $id must name at least one subject or successor unit"
        }
    }
}
