package opensamguk.logic.v2.evidence

/** 위반 한 건. [code]는 티켓 번호에 대응하는 안정 식별자이며 테스트가 이 값으로 단언한다. */
data class ContractViolation(val code: String, val message: String)

/**
 * OPENSAM-37 — T1-A05(시기분리) / T1-A06(복수 claim) / T1-A15(overlay) / T1-A16(엄격 고증) validator.
 *
 * 순수 함수 집합이다. DB·Spring·RNG를 쓰지 않으며 v1 동결 회귀 경로를 전혀 건드리지 않는다.
 */
object EvidenceContractValidator {

    // ---------------------------------------------------------------- T1-A05

    /**
     * T1-A05 — 한 claim의 시기 정합성.
     *
     * 핵심: **후대 사료에 이름이 등장했다는 이유만으로 대상 시기를 넓히지 못한다.**
     * 유효 구간([HistoricalClaim.validFrom]/[HistoricalClaim.validTo])은 주장 대상 시기를 벗어날 수 없고,
     * 실제 월드 활성화는 [isActivatableAt]이 대상 시기로 다시 막는다.
     */
    fun validateClaimPeriod(claim: HistoricalClaim): List<ContractViolation> = buildList {
        if (claim.subjectPeriodFrom > claim.subjectPeriodTo) {
            add(
                ContractViolation(
                    "V-A05-1",
                    "claim ${claim.id}: subjectPeriodFrom(${claim.subjectPeriodFrom}) > " +
                        "subjectPeriodTo(${claim.subjectPeriodTo})",
                ),
            )
        }
        if (claim.attestationDate < claim.subjectPeriodFrom) {
            add(
                ContractViolation(
                    "V-A05-2",
                    "claim ${claim.id}: 기록 시점(${claim.attestationDate})이 대상 시기 시작" +
                        "(${claim.subjectPeriodFrom})보다 이르다",
                ),
            )
        }
        val from = claim.validFrom
        val to = claim.validTo
        if (from != null && to != null && from > to) {
            add(ContractViolation("V-A05-1", "claim ${claim.id}: validFrom($from) > validTo($to)"))
        }
        if (from != null && from < claim.subjectPeriodFrom) {
            add(
                ContractViolation(
                    "V-A05-3",
                    "claim ${claim.id}: validFrom($from)이 대상 시기(${claim.subjectPeriodFrom}.." +
                        "${claim.subjectPeriodTo}) 이전으로 역투영됐다",
                ),
            )
        }
        if (to != null && to > claim.subjectPeriodTo) {
            add(
                ContractViolation(
                    "V-A05-3",
                    "claim ${claim.id}: validTo($to)이 대상 시기(${claim.subjectPeriodFrom}.." +
                        "${claim.subjectPeriodTo})를 넘어 확장됐다",
                ),
            )
        }
        if (claim.isRetrospective && claim.interpretationNote.isNullOrBlank()) {
            add(
                ContractViolation(
                    "V-A05-4",
                    "claim ${claim.id}: 후대 기록(${claim.attestationDate} > ${claim.subjectPeriodTo})은 " +
                        "interpretationNote가 필요하다",
                ),
            )
        }
        if (claim.confidence !in 0.0..1.0) {
            add(ContractViolation("V-A05-5", "claim ${claim.id}: confidence(${claim.confidence})가 0.0..1.0 밖이다"))
        }
    }

    /**
     * T1-A05 — 월드 연도 [worldYear]에서 이 claim을 활성화할 수 있는가.
     *
     * 기록 시점([HistoricalClaim.attestationDate])이 아니라 **주장 대상 시기**로 판정한다.
     * 후대 사료가 250년대를 서술한다는 사실은 189년 월드에서 아무것도 활성화하지 못한다.
     */
    fun isActivatableAt(claim: HistoricalClaim, worldYear: Int): Boolean {
        if (worldYear < claim.subjectPeriodFrom || worldYear > claim.subjectPeriodTo) return false
        val from = claim.validFrom
        val to = claim.validTo
        if (from != null && worldYear < from) return false
        if (to != null && worldYear > to) return false
        return true
    }

    // ---------------------------------------------------------------- T1-A06

    /**
     * T1-A06 — 등급 혼합 금지.
     *
     * 등급을 섞은 새 값을 만드는 대신 claim을 여러 개 만들라는 규칙을 데이터 층에서 강제한다:
     * claim의 근거는 그 등급이 허용하는 [SourceProximity]만 가질 수 있고
     * ([EvidenceClass.allowedProximities]), 같은 (subject, predicate, object, evidenceClass)
     * 조합이 두 번 나오면 그것은 변형이 아니라 중복이다.
     */
    fun validateClaimEvidence(claim: HistoricalClaim): List<ContractViolation> = buildList {
        val allowed = claim.evidenceClass.allowedProximities
        claim.evidenceRefs.forEach { ref ->
            if (ref.sourceProximity !in allowed) {
                add(
                    ContractViolation(
                        "V-A06-1",
                        "claim ${claim.id}(${claim.evidenceClass}): 근거 ${ref.id}의 " +
                            "sourceProximity=${ref.sourceProximity}는 이 등급이 허용하지 않는다 " +
                            "(허용: ${allowed.ifEmpty { "없음" }}). 등급을 섞지 말고 claim을 나눠라",
                    ),
                )
            }
        }
        if (claim.evidenceClass != EvidenceClass.BALANCE_ONLY && claim.evidenceRefs.isEmpty()) {
            add(ContractViolation("V-A06-2", "claim ${claim.id}: ${claim.evidenceClass} claim에 근거가 없다"))
        }
    }

    /** T1-A06 — claim 집합 수준 검증(중복 id, 중복 변형). */
    fun validateClaims(claims: List<HistoricalClaim>): List<ContractViolation> = buildList {
        claims.forEach { addAll(validateClaimPeriod(it)) }
        claims.forEach { addAll(validateClaimEvidence(it)) }

        claims.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted().forEach { id ->
            add(ContractViolation("V-A06-3", "claim id 중복: $id"))
        }
        claims.groupBy { it.variantKey to it.evidenceClass }
            .filterValues { it.size > 1 }
            .forEach { (key, dupes) ->
                add(
                    ContractViolation(
                        "V-A06-4",
                        "동일 (subject, predicate, object, evidenceClass)=$key claim이 " +
                            "${dupes.map { it.id }.sorted()}로 중복됐다 — 변형이라면 등급이 달라야 한다",
                    ),
                )
            }
    }

    // ---------------------------------------------------------- T1-A15 / A16

    /**
     * T1-A15 — 프로필이 활성화하는 overlay 목록. `CHRONICLE`은 어떤 overlay도 활성화하지 않는다.
     * snapshot에는 overlay **id만** 담는다.
     */
    fun resolveSnapshot(
        profile: WorldContentProfile,
        overlays: List<WorldContentOverlay>,
    ): WorldContentSnapshot = WorldContentSnapshot(
        profile = profile,
        activeOverlayIds = if (profile == WorldContentProfile.CHRONICLE) {
            emptyList()
        } else {
            overlays.filter { it.profile == profile }.map { it.id }
        },
    )

    /**
     * T1-A15 + T1-A16 — 월드 단위 검증.
     *
     * - overlay는 base claim을 **덮어쓰지 못한다**(추가만).
     * - overlay는 자기 프로필 월드에서만 활성화된다(LEGACY overlay가 CLASSIC 월드에 새지 못한다).
     * - CHRONICLE base 계층에는 연의·게임 claim이 들어올 수 없다.
     * - 엄격 고증(`CHRONICLE`)에서 `ROMANCE_ATTESTED`/`GAME_REFERENCE` claim이 활성이거나
     *   overlay(특히 `LEGACY`)가 활성이면 실패한다.
     *
     * @param snapshot 검사할 snapshot. 기본값은 [resolveSnapshot] 결과이며, 손으로 만든 snapshot을
     *   넘기면 그 활성 목록 자체가 검증 대상이 된다.
     */
    fun validateWorld(
        profile: WorldContentProfile,
        claims: List<HistoricalClaim>,
        baseClaimIds: Set<String>,
        overlays: List<WorldContentOverlay>,
        snapshot: WorldContentSnapshot = resolveSnapshot(profile, overlays),
    ): List<ContractViolation> = buildList {
        addAll(validateClaims(claims))

        val byId = claims.associateBy { it.id }
        baseClaimIds.sorted().forEach { id ->
            val claim = byId[id]
            if (claim == null) {
                add(ContractViolation("V-A15-2", "base claim id ${id}에 해당하는 claim이 없다"))
            } else if (!claim.evidenceClass.allowedInChronicle) {
                add(
                    ContractViolation(
                        "V-A15-3",
                        "base(CHRONICLE) 계층에 ${claim.evidenceClass} claim ${id}이 있다 — " +
                            "연의·게임 콘텐츠는 overlay로만 더한다",
                    ),
                )
            }
        }

        overlays.forEach { overlay ->
            if (overlay.profile == WorldContentProfile.CHRONICLE) {
                add(
                    ContractViolation(
                        "V-A15-4",
                        "overlay ${overlay.id}: CHRONICLE은 base 계층이므로 overlay 프로필이 될 수 없다",
                    ),
                )
            }
            overlay.claimIds.forEach { id ->
                if (id in baseClaimIds) {
                    add(
                        ContractViolation(
                            "V-A15-1",
                            "overlay ${overlay.id}가 base claim ${id}을 다시 선언한다 — " +
                                "overlay는 CHRONICLE 데이터를 수정하지 못하고 추가만 한다",
                        ),
                    )
                }
                if (id !in byId) {
                    add(ContractViolation("V-A15-2", "overlay ${overlay.id}가 없는 claim ${id}을 참조한다"))
                }
            }
        }

        if (snapshot.profile != profile) {
            add(ContractViolation("V-A15-5", "snapshot profile ${snapshot.profile}이 월드 프로필 $profile 과 다르다"))
        }
        val overlayById = overlays.associateBy { it.id }
        snapshot.activeOverlayIds.forEach { id ->
            val overlay = overlayById[id]
            if (overlay == null) {
                add(ContractViolation("V-A15-2", "snapshot이 없는 overlay ${id}을 활성화한다"))
            } else if (overlay.profile != profile) {
                // overlay는 자기 프로필에서만 활성화된다. 손으로 만든 snapshot이 LEGACY overlay(v1 동결 회귀 콘텐츠)를
                // CLASSIC 월드에 끼워 넣는 경로를 막는다 — resolveSnapshot은 만들지 않지만 snapshot은 외부 입력이다.
                add(
                    ContractViolation(
                        "V-A15-6",
                        "snapshot이 $profile 월드에서 ${overlay.profile} overlay ${id}을 활성화한다",
                    ),
                )
            }
        }

        if (profile == WorldContentProfile.CHRONICLE) {
            snapshot.activeOverlayIds.forEach { id ->
                val overlayProfile = overlayById[id]?.profile
                add(
                    ContractViolation(
                        "V-A16-2",
                        "엄격 고증(CHRONICLE)에서 overlay $id(${overlayProfile ?: "unknown"})이 활성화됐다",
                    ),
                )
            }
            val activeIds = baseClaimIds + snapshot.activeOverlayIds.flatMap { overlayById[it]?.claimIds.orEmpty() }
            activeIds.sorted().mapNotNull { byId[it] }
                .filterNot { it.evidenceClass.allowedInChronicle }
                .forEach {
                    add(
                        ContractViolation(
                            "V-A16-1",
                            "엄격 고증(CHRONICLE)에서 ${it.evidenceClass} claim ${it.id}이 활성화됐다",
                        ),
                    )
                }
        }
    }

    // ---------------------------------------------------------------- T1-K01

    /**
     * T1-K01/K02 — 제품 자산으로 번들 가능한 근거만 통과시킨다.
     * [LicenseBundling.UNKNOWN]은 "아마 괜찮다"가 아니라 차단 사유다.
     */
    fun validateBundling(refs: List<EvidenceRef>): List<ContractViolation> = refs
        .filter { it.sourceLicense.bundling != LicenseBundling.BUNDLING_ALLOWED }
        .map {
            ContractViolation(
                "V-K01-1",
                "근거 ${it.id}의 라이선스 ${it.sourceLicense.id}(${it.sourceLicense.bundling})는 " +
                    "제품 번들이 허용되지 않았다: ${it.sourceLicense.note}",
            )
        }
}
