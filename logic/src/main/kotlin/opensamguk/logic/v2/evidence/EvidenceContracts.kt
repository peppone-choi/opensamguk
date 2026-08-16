package opensamguk.logic.v2.evidence

/**
 * OPENSAM-37 [G0-A②] 출처·확실성 계약 — in-memory 모델만. DB write·마이그레이션 없음.
 *
 * 정본: `docs/superpowers/specs/2026-07-13-v2-historical-city-army-terrain-design.md` §3, §11.
 * v1 패러티 코어(RNG/로그/골든)와 완전히 분리된 v2 전용 계약이며 어떤 v1 경로도 참조하지 않는다.
 */

/**
 * T1-A01 — 사료와의 **시간적 거리**. 스펙 §3 L69의 7값만 존재하며 여기에 값을 추가하는 것은
 * "혼합 등급 신설"과 같은 위반이다. [EvidenceClass]와 혼동하지 말 것: 이쪽은 출처의 성격,
 * 저쪽은 그 출처로 만든 주장의 등급이다.
 */
enum class SourceProximity {
    /** 당대 기록. */
    CONTEMPORARY,

    /** 정사(삼국지·후한서 등). */
    OFFICIAL_HISTORY,

    /** 초기 주석(배송지주 등). */
    EARLY_ANNOTATION,

    /** 후대 전승. */
    LATER_TRADITION,

    /** 현대 학술 연구. */
    MODERN_STUDY,

    /** 창작 — 『삼국지연의』 등. */
    FICTION,

    /** 게임 설계 참고. */
    GAME,
}

/**
 * T1-A04 — 주장의 **확실성 등급**. 스펙 §3 L44-50의 5값만 존재한다.
 * 정사·주석·연의·게임 참고가 한 콘텐츠에 함께 있으면 등급을 섞은 새 값을 만들지 않고
 * [HistoricalClaim]을 여러 개 만든다.
 */
enum class EvidenceClass {
    /** 당대·근접 시기 사료에 직접 확인. */
    PRIMARY_ATTESTED,

    /** 사료·고고를 학술적으로 복원. */
    SCHOLARLY_RECONSTRUCTION,

    /** 『삼국지연의』에 직접 등장. */
    ROMANCE_ATTESTED,

    /** 게임 설계 참고 — 역사 주장에 사용 금지. */
    GAME_REFERENCE,

    /** 플레이 가능성을 위한 수치 — 사료 근거처럼 표시 금지. */
    BALANCE_ONLY,
    ;

    /**
     * 이 등급이 근거로 받아들이는 [SourceProximity]. 표 밖의 조합은 등급 혼합이므로
     * [EvidenceContractValidator]가 거부한다.
     *
     * [BALANCE_ONLY]는 빈 집합이다 — 밸런스 수치에 사료 근거를 붙이면 사료처럼 보이기 때문.
     */
    val allowedProximities: Set<SourceProximity>
        get() = when (this) {
            PRIMARY_ATTESTED -> setOf(SourceProximity.CONTEMPORARY, SourceProximity.OFFICIAL_HISTORY)
            SCHOLARLY_RECONSTRUCTION -> setOf(
                SourceProximity.CONTEMPORARY,
                SourceProximity.OFFICIAL_HISTORY,
                SourceProximity.EARLY_ANNOTATION,
                SourceProximity.LATER_TRADITION,
                SourceProximity.MODERN_STUDY,
            )
            ROMANCE_ATTESTED -> setOf(SourceProximity.FICTION)
            GAME_REFERENCE -> setOf(SourceProximity.GAME)
            BALANCE_ONLY -> emptySet()
        }

    /** 엄격 고증([WorldContentProfile.CHRONICLE])에서 활성화할 수 없는 등급. 스펙 §10 L379. */
    val allowedInChronicle: Boolean
        get() = this != ROMANCE_ATTESTED && this != GAME_REFERENCE
}

/** T1-K01 — 제품 번들 가부. 명시 문구가 없으면 [UNKNOWN]이며 추측으로 [BUNDLING_ALLOWED]로 올리지 않는다. */
enum class LicenseBundling {
    /** 제품 자산으로 번들·재배포 가능. */
    BUNDLING_ALLOWED,

    /** 위치 검증 등 내부 연구에는 쓸 수 있으나 번들 전 별도 검토 필요. */
    RESEARCH_ONLY,

    /** 라이선스 문구를 확인하지 못했다. 번들 금지. */
    UNKNOWN,
}

/**
 * T1-K01 — 모든 외부 데이터 행에 붙는 라이선스. 스펙 §11 L420.
 * [bundling]이 [LicenseBundling.BUNDLING_ALLOWED]가 아닌 데이터는 제품 자산으로 내보낼 수 없다.
 */
data class SourceLicense(
    val id: String,
    val name: String,
    val url: String?,
    val bundling: LicenseBundling,
    /** 검토 근거(URL·인용 문구·검토 문서 경로). [LicenseBundling.UNKNOWN]이면 무엇을 확인하지 못했는지 적는다. */
    val note: String,
)

/** T1-A01 — 개별 근거 자료 한 건. */
data class EvidenceRef(
    val id: String,
    /** 자료 종류(사서·비문·발굴보고·논문·소설·게임 등) — 자유 문자열. 등급은 [sourceProximity]가 담는다. */
    val sourceType: String,
    val sourceProximity: SourceProximity,
    val title: String,
    val author: String?,
    val work: String?,
    /** 권·편·쪽 등 인용 위치. */
    val passage: String?,
    val url: String?,
    /** T1-K01. 외부 데이터 행은 라이선스 없이 존재할 수 없다. */
    val sourceLicense: SourceLicense,
)

/**
 * T1-A04 — 하나의 역사 주장.
 *
 * 연도는 모두 서기 연도(음수 = 기원전)이며 [attestationDate]는 기록이 **작성된** 시점,
 * [subjectPeriodFrom]/[subjectPeriodTo]는 그 기록이 **주장하는 대상 시기**다.
 * [validFrom]/[validTo]는 여러 claim과 activation 결정을 정규화한 **시나리오 유효 구간**이며
 * 원문 시기 필드를 덮어쓰지 않는다.
 */
data class HistoricalClaim(
    val id: String,
    val subject: String,
    val predicate: String,
    val obj: String,
    val attestationDate: Int,
    val subjectPeriodFrom: Int,
    val subjectPeriodTo: Int,
    val validFrom: Int?,
    val validTo: Int?,
    val geographyScope: String,
    val evidenceClass: EvidenceClass,
    /** 0.0..1.0. */
    val confidence: Double,
    val evidenceRefs: List<EvidenceRef>,
    val interpretationNote: String?,
) {
    /** 같은 사실에 대한 정사/연의 변형을 묶는 키. 등급을 섞지 않고 변형끼리 비교하기 위한 것. */
    val variantKey: Triple<String, String, String> get() = Triple(subject, predicate, obj)

    /** 기록이 대상 시기보다 늦게 작성됐는가 — 후대 사료 여부. */
    val isRetrospective: Boolean get() = attestationDate > subjectPeriodTo
}

/**
 * T1-A15 — 월드 콘텐츠 프로필. 스펙 §3 L79-83.
 * [EvidenceClass]가 아니며, `CLASSIC`은 `CHRONICLE` 데이터를 **수정하지 않고** overlay로만 더한다.
 */
enum class WorldContentProfile {
    /** 정사·후한서·학술 복원. 엄격 고증 플레이와 검증. */
    CHRONICLE,

    /** CHRONICLE + 연의 대표 콘텐츠. v2 기본값. */
    CLASSIC,

    /** v1 PHP 패리티 병종과 규칙. v1 월드·회귀 검증 전용. */
    LEGACY,
}

/**
 * T1-A15 — overlay는 base claim 집합에 **추가만** 한다.
 * [claimIds]가 base와 겹치면 CHRONICLE 데이터를 덮어쓰는 것이므로 검증이 실패한다.
 */
data class WorldContentOverlay(
    val id: String,
    /** 이 overlay를 활성화하는 프로필. */
    val profile: WorldContentProfile,
    val claimIds: List<String>,
)

/**
 * T1-A15 — simulation snapshot에는 **활성 overlay 목록만** 기록한다(claim 본문 복제 금지).
 */
data class WorldContentSnapshot(
    val profile: WorldContentProfile,
    val activeOverlayIds: List<String>,
)
