package opensamguk.logic.v2.geo

/**
 * V2 G0-A 물리 장소 계약 (OPENSAM-36 / T1-B05, B07, B08, B10, B15, E08).
 * 행정 범위(TemporalAdministrativeUnit)와 물리 장소(PhysicalPlace)를 분리하는 것이 요점이다.
 */

/**
 * T1-B07 — 상호 배타 예산 class. 정식 catalog 합계 2,000개.
 * [budget]은 class별 목표 수량이며 [validatePlaceBudget]이 실제 catalog와 대조한다.
 */
enum class PlaceBudgetClass(val budget: Int) {
    ADMINISTRATIVE_SETTLEMENT(1_200),
    STRATEGIC_NON_ADMINISTRATIVE(200),
    EXTERNAL_PLACE(500),
    MARITIME_REMOTE_GATE(100),
    ;

    companion object {
        /** 정식 `PhysicalPlace` 목표 총합. */
        val TOTAL_BUDGET: Int = entries.sumOf { it.budget }
    }
}

/** T1-B05/B08 — 좌표 확정 여부. 확정 못 하면 점을 날조하지 않고 후보 region을 쓴다. */
enum class LocationResolution { RESOLVED_POINT, CANDIDATE_REGION }

/** 물리 장소 종류. */
enum class PlaceType { SETTLEMENT, FORT, PASS, PORT, OASIS, CAMP, MARKET_GATE }

/** 취락 발전 단계. 치소 여부는 여기 저장하지 않는다 — canonical owner는 SeatAssignment다. */
enum class DevelopmentClass { HAMLET, VILLAGE, TOWN, CITY, METROPOLIS }

/** world coordinate. 투영 버전은 replay 결정성을 위해 좌표와 함께 다닌다. */
data class GeoPoint(val x: Double, val y: Double, val projectionVersion: String)

/**
 * T1-B08 — 좌표 미상 시 상위 군·국 envelope에서 유도한 후보 영역.
 * [uncertaintyRadius]는 UI(T1-B10)가 개연 경계를 확정 사료처럼 그리지 못하게 하는 신호다.
 */
data class CandidateRegion(
    val id: String,
    val derivedFromUnitId: String,
    val envelope: List<GeoPoint>,
    val sourceRefs: List<String> = emptyList(),
) {
    init {
        require(envelope.size >= 3) { "CandidateRegion $id envelope needs >= 3 points" }
    }
}

/**
 * T1-B05 — 물리 장소.
 *
 * `RESOLVED_POINT`는 coordinate를, `CANDIDATE_REGION`은 candidateRegions[]를 요구한다 (T1-B08:
 * 근거 없는 단일 좌표 날조 금지). 동일 기간·동일 [placeIdentityKey] 중복은
 * [distinctPlaceClaimIds]로 "서로 다른 장소"라는 근거를 대야만 허용된다 (T1-B06 / G0A-q).
 */
data class PhysicalPlace(
    val id: String,
    val names: List<TemporalName>,
    val type: PlaceType,
    val developmentClass: DevelopmentClass,
    val placeIdentityKey: String,
    val placeBudgetClass: PlaceBudgetClass,
    val locationResolution: LocationResolution,
    val coordinate: GeoPoint? = null,
    val candidateRegions: List<CandidateRegion> = emptyList(),
    val uncertaintyRadius: Double? = null,
    val distinctPlaceClaimIds: List<String> = emptyList(),
    val time: ValidTime,
    val sourceRefs: List<String> = emptyList(),
    val confidence: Confidence = Confidence.ATTESTED,
    val license: String? = null,
) {
    init {
        require(names.isNotEmpty()) { "PhysicalPlace $id must have at least one name" }
        when (locationResolution) {
            LocationResolution.RESOLVED_POINT -> {
                require(coordinate != null) { "PhysicalPlace $id RESOLVED_POINT requires a coordinate" }
            }
            LocationResolution.CANDIDATE_REGION -> {
                require(coordinate == null) {
                    "PhysicalPlace $id CANDIDATE_REGION must not carry a fabricated exact coordinate"
                }
                require(candidateRegions.isNotEmpty()) {
                    "PhysicalPlace $id CANDIDATE_REGION requires at least one candidate region"
                }
            }
        }
    }

    /** T1-B10 — 불확실 위치 UI는 이 배지로만 복원 여부를 표시한다. */
    val showsReconstructionBadge: Boolean
        get() = locationResolution == LocationResolution.CANDIDATE_REGION ||
            confidence != Confidence.ATTESTED
}

/** PlaceControl이 행사하는 권한 축. */
enum class AuthorityScope { CIVIL, MILITARY, FISCAL, JUDICIAL }

/** T1-B15 — 물리 장소에 대한 지배. 점령은 이 레코드의 변경으로 표현한다. */
data class PlaceControl(
    val id: String,
    val physicalPlaceId: String,
    val controllerActorId: String,
    val exercisingOfficeIds: List<String> = emptyList(),
    val authorityScopes: Set<AuthorityScope> = emptySet(),
    val time: ValidTime,
    val sourceRefs: List<String> = emptyList(),
    val confidence: Confidence = Confidence.ATTESTED,
) {
    init {
        require(authorityScopes.isNotEmpty()) { "PlaceControl $id must exercise at least one authority scope" }
    }
}

/** T1-E08 — anchor 선택 방식. */
enum class PlacementMode { RESOLVED, DETERMINISTIC_RECONSTRUCTION }

/**
 * T1-E08 — 시나리오별 실제 배치. 위치가 불확실해도 region 안에서 결정적으로 anchor를 고르므로
 * 모든 치소가 조회·점령·주둔·징병·세입·보급에 참여한다 (EXCLUDED 없음).
 */
data class ScenarioPlacement(
    val scenarioId: String,
    val physicalPlaceId: String,
    val reconstructionId: String,
    val playableAnchor: GeoPoint,
    val admissibleRegion: CandidateRegion? = null,
    val placementMode: PlacementMode,
    val claimIds: List<String> = emptyList(),
) {
    init {
        require(placementMode != PlacementMode.DETERMINISTIC_RECONSTRUCTION || admissibleRegion != null) {
            "ScenarioPlacement $scenarioId/$physicalPlaceId reconstruction requires an admissible region"
        }
    }
}
