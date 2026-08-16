package opensamguk.logic.v2.geo

import java.time.LocalDate

/**
 * V2 G0-A 치소 계약 (OPENSAM-36 / T1-B11, B13).
 * 치소의 canonical owner는 SeatAssignment 하나뿐이다 — PhysicalPlace나 발전 단계가 아니다.
 */

enum class SeatRole { COUNTY_SEAT, COMMANDERY_SEAT, PROVINCE_SEAT, CAPITAL }

/**
 * T1-B11 — 어느 행정 단위의 치소가 어느 물리 장소에 있었는지.
 * 군치·현치 co-location은 한 PhysicalPlace를 가리키는 두 assignment로 표현한다.
 */
data class SeatAssignment(
    val id: String,
    val administrativeUnitId: String,
    val physicalPlaceId: String,
    val role: SeatRole,
    val time: ValidTime,
    val sourceRefs: List<String> = emptyList(),
    val confidence: Confidence = Confidence.ATTESTED,
)

/**
 * T1-B13 — 현치·군치·수도 배지는 **활성 SeatAssignment.role에서만** 파생한다.
 * 다른 어떤 필드(발전 단계, 시설, 인구)도 배지를 만들지 못한다.
 */
fun badgesFor(
    physicalPlaceId: String,
    assignments: List<SeatAssignment>,
    on: LocalDate,
): Set<SeatRole> =
    assignments.asSequence()
        .filter { it.physicalPlaceId == physicalPlaceId && it.time.contains(on) }
        .map { it.role }
        .toSet()
