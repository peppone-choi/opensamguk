package opensamguk.logic.v2.geo

/**
 * V2 G0-A validator (OPENSAM-36 / G0A-p, G0A-q, T1-B06, B07, B12).
 *
 * 전부 in-memory 검사다. 위반은 문자열 목록으로 돌려주고, 게이트에서 실패시키려면
 * [requireNoViolations]를 쓴다.
 */

/** 위반이 하나라도 있으면 던진다. G0 exit 게이트가 이 함수를 경유한다. */
fun requireNoViolations(violations: List<String>) {
    require(violations.isEmpty()) {
        "geo contract violations (${violations.size}):\n" + violations.joinToString("\n")
    }
}

/**
 * G0A-p / T1-B12 — 같은 `(administrativeUnitId, role)`의 유효 기간이 겹치거나 행이 완전히
 * 동일하면 실패. 한 단위·한 역할의 치소는 어느 시점에도 하나뿐이다.
 *
 * 군치·현치 co-location(한 PhysicalPlace + 두 role)은 role이 다르므로 위반이 아니다.
 */
fun validateSeatAssignments(assignments: List<SeatAssignment>): List<String> {
    val violations = mutableListOf<String>()

    assignments.groupingBy { it.id }.eachCount()
        .filterValues { it > 1 }
        .forEach { (id, n) -> violations += "SeatAssignment id '$id' appears $n times" }

    assignments.groupBy { it.administrativeUnitId to it.role }.forEach { (key, group) ->
        val (unitId, role) = key
        for (i in group.indices) {
            for (j in i + 1 until group.size) {
                val a = group[i]
                val b = group[j]
                if (!a.time.overlaps(b.time)) continue
                val identical = a.physicalPlaceId == b.physicalPlaceId && a.time == b.time
                val kind = if (identical) "duplicate" else "overlapping"
                violations += "SeatAssignment $kind for ($unitId, $role): " +
                    "'${a.id}' ${a.time.validFrom}..${a.time.validTo} vs " +
                    "'${b.id}' ${b.time.validFrom}..${b.time.validTo}"
            }
        }
    }
    return violations
}

/**
 * G0A-q / T1-B06 — 같은 기간에 같은 [PhysicalPlace.placeIdentityKey]를 쓰는 장소가 둘 이상이면,
 * 서로를 [PhysicalPlace.distinctPlaceClaimIds]로 "다른 장소"라고 근거 지어야만 통과한다.
 * 근거 없는 복제는 실패다.
 */
fun validatePlaceIdentityKeys(places: List<PhysicalPlace>): List<String> {
    val violations = mutableListOf<String>()

    places.groupingBy { it.id }.eachCount()
        .filterValues { it > 1 }
        .forEach { (id, n) -> violations += "PhysicalPlace id '$id' appears $n times" }

    places.groupBy { it.placeIdentityKey }.forEach { (key, group) ->
        for (i in group.indices) {
            for (j in i + 1 until group.size) {
                val a = group[i]
                val b = group[j]
                if (!a.time.overlaps(b.time)) continue
                if (b.id in a.distinctPlaceClaimIds && a.id in b.distinctPlaceClaimIds) continue
                violations += "PhysicalPlace placeIdentityKey '$key' duplicated in the same period by " +
                    "'${a.id}' and '${b.id}' without a mutual distinctPlaceClaimIds claim"
            }
        }
    }
    return violations
}

/**
 * T1-B07 — class별 수량과 합계 검증. 각 장소는 상호 배타 class 정확히 하나에 속한다(타입으로 보장).
 * presence·seasonal range 같은 영역 객체는 catalog에 들어오지 않으므로 세지 않는다.
 */
fun validatePlaceBudget(places: List<PhysicalPlace>): List<String> {
    val violations = mutableListOf<String>()
    val counts = places.groupingBy { it.placeBudgetClass }.eachCount()

    PlaceBudgetClass.entries.forEach { cls ->
        val actual = counts[cls] ?: 0
        if (actual != cls.budget) {
            violations += "PlaceBudgetClass $cls count $actual != budget ${cls.budget}"
        }
    }
    if (places.size != PlaceBudgetClass.TOTAL_BUDGET) {
        violations += "PhysicalPlace catalog total ${places.size} != ${PlaceBudgetClass.TOTAL_BUDGET}"
    }
    return violations
}
