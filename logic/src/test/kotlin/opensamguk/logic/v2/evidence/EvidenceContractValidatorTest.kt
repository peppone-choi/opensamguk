package opensamguk.logic.v2.evidence

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-37 [G0-A②] — 출처·확실성 계약 validator 검증.
 *
 * 확인 대상: 후대 사료의 189년 역투영이 실제로 거부되는지(T1-A05), 혼합 등급이 막히는지(T1-A06),
 * overlay가 CHRONICLE을 수정하지 못하는지(T1-A15), 엄격 고증이 연의·게임·LEGACY를 거부하는지(T1-A16).
 */
class EvidenceContractValidatorTest {

    private val publicDomain = SourceLicense(
        id = "lic-pd",
        name = "Public domain (pre-1929 text)",
        url = null,
        bundling = LicenseBundling.BUNDLING_ALLOWED,
        note = "저작권 만료 고전 원문",
    )

    private fun ref(
        id: String,
        proximity: SourceProximity,
        license: SourceLicense = publicDomain,
    ) = EvidenceRef(
        id = id,
        sourceType = "사서",
        sourceProximity = proximity,
        title = "title-$id",
        author = null,
        work = null,
        passage = null,
        url = null,
        sourceLicense = license,
    )

    private fun claim(
        id: String,
        evidenceClass: EvidenceClass = EvidenceClass.PRIMARY_ATTESTED,
        attestationDate: Int = 190,
        subjectPeriodFrom: Int = 189,
        subjectPeriodTo: Int = 200,
        validFrom: Int? = null,
        validTo: Int? = null,
        refs: List<EvidenceRef> = listOf(ref("r-$id", SourceProximity.OFFICIAL_HISTORY)),
        interpretationNote: String? = null,
        subject: String = "subject-$id",
        predicate: String = "predicate",
        obj: String = "object",
    ) = HistoricalClaim(
        id = id,
        subject = subject,
        predicate = predicate,
        obj = obj,
        attestationDate = attestationDate,
        subjectPeriodFrom = subjectPeriodFrom,
        subjectPeriodTo = subjectPeriodTo,
        validFrom = validFrom,
        validTo = validTo,
        geographyScope = "예주",
        evidenceClass = evidenceClass,
        confidence = 0.8,
        evidenceRefs = refs,
        interpretationNote = interpretationNote,
    )

    private fun codes(violations: List<ContractViolation>) = violations.map { it.code }

    // ---------------------------------------------------------------- 값 집합

    @Test
    fun `sourceProximity 7값 evidenceClass 5값 WorldContentProfile 3값만 존재한다`() {
        assertEquals(7, SourceProximity.entries.size)
        assertEquals(5, EvidenceClass.entries.size)
        assertEquals(3, WorldContentProfile.entries.size)
    }

    // ---------------------------------------------------------------- T1-A05

    @Test
    fun `후대 사료는 189년 월드에 역투영되지 않는다`() {
        // 서기 429년 배송지주가 서술한 250-280년 사건. 이름이 사료에 등장한다는 사실만으로
        // 189년 월드에서 활성화되면 안 된다.
        val late = claim(
            id = "c-late",
            evidenceClass = EvidenceClass.SCHOLARLY_RECONSTRUCTION,
            attestationDate = 429,
            subjectPeriodFrom = 250,
            subjectPeriodTo = 280,
            refs = listOf(ref("r-late", SourceProximity.EARLY_ANNOTATION)),
            interpretationNote = "429년 주석이 3세기 중반을 서술",
        )
        assertTrue(EvidenceContractValidator.validateClaimPeriod(late).isEmpty(), "그 자체로는 적법한 claim이다")
        assertFalse(EvidenceContractValidator.isActivatableAt(late, 189), "189년 월드에서 활성화 불가")
        assertTrue(EvidenceContractValidator.isActivatableAt(late, 260))
    }

    @Test
    fun `validFrom을 대상 시기 이전으로 넓히면 거부된다`() {
        val backProjected = claim(
            id = "c-back",
            evidenceClass = EvidenceClass.SCHOLARLY_RECONSTRUCTION,
            attestationDate = 429,
            subjectPeriodFrom = 250,
            subjectPeriodTo = 280,
            validFrom = 189,
            validTo = 280,
            refs = listOf(ref("r-back", SourceProximity.LATER_TRADITION)),
            interpretationNote = "note",
        )
        assertContains(codes(EvidenceContractValidator.validateClaimPeriod(backProjected)), "V-A05-3")
    }

    @Test
    fun `validTo를 대상 시기 이후로 넓히면 거부된다`() {
        val stretched = claim(id = "c-stretch", validFrom = 190, validTo = 260)
        assertContains(codes(EvidenceContractValidator.validateClaimPeriod(stretched)), "V-A05-3")
    }

    @Test
    fun `기록 시점이 대상 시기보다 이르면 거부된다`() {
        val impossible = claim(id = "c-early", attestationDate = 100)
        assertContains(codes(EvidenceContractValidator.validateClaimPeriod(impossible)), "V-A05-2")
    }

    @Test
    fun `후대 기록은 해석 노트 없이 통과하지 못한다`() {
        val bare = claim(
            id = "c-bare",
            evidenceClass = EvidenceClass.SCHOLARLY_RECONSTRUCTION,
            attestationDate = 429,
            refs = listOf(ref("r-bare", SourceProximity.EARLY_ANNOTATION)),
            interpretationNote = null,
        )
        assertContains(codes(EvidenceContractValidator.validateClaimPeriod(bare)), "V-A05-4")
        val noted = bare.copy(interpretationNote = "배송지주 기준")
        assertTrue(EvidenceContractValidator.validateClaimPeriod(noted).isEmpty())
    }

    @Test
    fun `유효 구간과 대상 시기 모두 만족해야 활성화된다`() {
        val narrow = claim(id = "c-narrow", validFrom = 195, validTo = 198)
        assertFalse(EvidenceContractValidator.isActivatableAt(narrow, 190))
        assertTrue(EvidenceContractValidator.isActivatableAt(narrow, 196))
        assertFalse(EvidenceContractValidator.isActivatableAt(narrow, 199))
    }

    // ---------------------------------------------------------------- T1-A06

    @Test
    fun `정사 claim에 연의 근거를 섞으면 거부된다`() {
        val mixed = claim(
            id = "c-mixed",
            evidenceClass = EvidenceClass.PRIMARY_ATTESTED,
            refs = listOf(
                ref("r-hist", SourceProximity.OFFICIAL_HISTORY),
                ref("r-fiction", SourceProximity.FICTION),
            ),
        )
        assertContains(codes(EvidenceContractValidator.validateClaimEvidence(mixed)), "V-A06-1")
    }

    @Test
    fun `게임 참고 근거는 역사 등급에 붙지 못하고 BALANCE_ONLY는 근거를 갖지 못한다`() {
        val gameRefOnHistory = claim(
            id = "c-game",
            evidenceClass = EvidenceClass.SCHOLARLY_RECONSTRUCTION,
            refs = listOf(ref("r-game", SourceProximity.GAME)),
        )
        assertContains(codes(EvidenceContractValidator.validateClaimEvidence(gameRefOnHistory)), "V-A06-1")

        val balanceWithRef = claim(
            id = "c-balance",
            evidenceClass = EvidenceClass.BALANCE_ONLY,
            refs = listOf(ref("r-balance", SourceProximity.OFFICIAL_HISTORY)),
        )
        assertContains(codes(EvidenceContractValidator.validateClaimEvidence(balanceWithRef)), "V-A06-1")

        val balanceBare = claim(id = "c-balance2", evidenceClass = EvidenceClass.BALANCE_ONLY, refs = emptyList())
        assertTrue(EvidenceContractValidator.validateClaimEvidence(balanceBare).isEmpty())
    }

    @Test
    fun `같은 사실의 정사와 연의는 등급이 다른 두 claim으로 공존한다`() {
        val official = claim(
            id = "c-official",
            subject = "관우",
            predicate = "무기",
            obj = "청룡언월도",
            evidenceClass = EvidenceClass.PRIMARY_ATTESTED,
            refs = listOf(ref("r-o", SourceProximity.OFFICIAL_HISTORY)),
        )
        val romance = official.copy(
            id = "c-romance",
            evidenceClass = EvidenceClass.ROMANCE_ATTESTED,
            evidenceRefs = listOf(ref("r-r", SourceProximity.FICTION)),
        )
        assertEquals(emptyList(), EvidenceContractValidator.validateClaims(listOf(official, romance)))
    }

    @Test
    fun `같은 변형을 같은 등급으로 두 번 만들면 중복으로 거부된다`() {
        val a = claim(id = "c-dup1", subject = "관우", predicate = "무기", obj = "청룡언월도")
        val b = a.copy(id = "c-dup2")
        assertContains(codes(EvidenceContractValidator.validateClaims(listOf(a, b))), "V-A06-4")
    }

    @Test
    fun `claim id 중복은 거부된다`() {
        val a = claim(id = "c-same", subject = "s1")
        val b = claim(id = "c-same", subject = "s2")
        assertContains(codes(EvidenceContractValidator.validateClaims(listOf(a, b))), "V-A06-3")
    }

    // ---------------------------------------------------------------- T1-A15

    @Test
    fun `CHRONICLE은 overlay를 활성화하지 않고 CLASSIC은 자기 overlay만 활성화한다`() {
        val classicOverlay = WorldContentOverlay("ov-classic", WorldContentProfile.CLASSIC, listOf("c-romance"))
        val legacyOverlay = WorldContentOverlay("ov-legacy", WorldContentProfile.LEGACY, listOf("c-legacy"))
        val overlays = listOf(classicOverlay, legacyOverlay)

        assertEquals(
            WorldContentSnapshot(WorldContentProfile.CHRONICLE, emptyList()),
            EvidenceContractValidator.resolveSnapshot(WorldContentProfile.CHRONICLE, overlays),
        )
        assertEquals(
            WorldContentSnapshot(WorldContentProfile.CLASSIC, listOf("ov-classic")),
            EvidenceContractValidator.resolveSnapshot(WorldContentProfile.CLASSIC, overlays),
        )
        assertEquals(
            WorldContentSnapshot(WorldContentProfile.LEGACY, listOf("ov-legacy")),
            EvidenceContractValidator.resolveSnapshot(WorldContentProfile.LEGACY, overlays),
        )
    }

    @Test
    fun `overlay가 base claim을 다시 선언하면 거부된다`() {
        val base = claim(id = "c-base")
        val romance = claim(
            id = "c-romance",
            evidenceClass = EvidenceClass.ROMANCE_ATTESTED,
            refs = listOf(ref("r-fic", SourceProximity.FICTION)),
            subject = "등갑군",
        )
        val overlay = WorldContentOverlay("ov", WorldContentProfile.CLASSIC, listOf("c-base", "c-romance"))
        val violations = EvidenceContractValidator.validateWorld(
            profile = WorldContentProfile.CLASSIC,
            claims = listOf(base, romance),
            baseClaimIds = setOf("c-base"),
            overlays = listOf(overlay),
        )
        assertContains(codes(violations), "V-A15-1")
    }

    @Test
    fun `연의 claim이 base 계층에 있으면 거부된다`() {
        val romanceInBase = claim(
            id = "c-r",
            evidenceClass = EvidenceClass.ROMANCE_ATTESTED,
            refs = listOf(ref("r-f", SourceProximity.FICTION)),
        )
        val violations = EvidenceContractValidator.validateWorld(
            profile = WorldContentProfile.CLASSIC,
            claims = listOf(romanceInBase),
            baseClaimIds = setOf("c-r"),
            overlays = emptyList(),
        )
        assertContains(codes(violations), "V-A15-3")
    }

    @Test
    fun `CLASSIC 월드에서 연의 overlay는 base를 건드리지 않고 통과한다`() {
        val base = claim(id = "c-base")
        val romance = claim(
            id = "c-romance",
            evidenceClass = EvidenceClass.ROMANCE_ATTESTED,
            refs = listOf(ref("r-fic", SourceProximity.FICTION)),
            subject = "등갑군",
        )
        val overlay = WorldContentOverlay("ov", WorldContentProfile.CLASSIC, listOf("c-romance"))
        assertEquals(
            emptyList(),
            EvidenceContractValidator.validateWorld(
                profile = WorldContentProfile.CLASSIC,
                claims = listOf(base, romance),
                baseClaimIds = setOf("c-base"),
                overlays = listOf(overlay),
            ),
        )
    }

    // ---------------------------------------------------------------- T1-A16

    @Test
    fun `엄격 고증에서 연의 claim이 활성화되면 실패한다`() {
        val base = claim(id = "c-base")
        val romance = claim(
            id = "c-romance",
            evidenceClass = EvidenceClass.ROMANCE_ATTESTED,
            refs = listOf(ref("r-fic", SourceProximity.FICTION)),
            subject = "등갑군",
        )
        val overlay = WorldContentOverlay("ov", WorldContentProfile.CLASSIC, listOf("c-romance"))
        // 손으로 만든 snapshot이 CHRONICLE 월드에 연의 overlay를 억지로 켠 경우.
        val forced = WorldContentSnapshot(WorldContentProfile.CHRONICLE, listOf("ov"))
        val violations = EvidenceContractValidator.validateWorld(
            profile = WorldContentProfile.CHRONICLE,
            claims = listOf(base, romance),
            baseClaimIds = setOf("c-base"),
            overlays = listOf(overlay),
            snapshot = forced,
        )
        assertContains(codes(violations), "V-A16-2")
        assertContains(codes(violations), "V-A16-1")
    }

    @Test
    fun `엄격 고증에서 LEGACY overlay가 활성화되면 실패한다`() {
        val base = claim(id = "c-base")
        val legacy = claim(
            id = "c-legacy",
            evidenceClass = EvidenceClass.GAME_REFERENCE,
            refs = listOf(ref("r-game", SourceProximity.GAME)),
            subject = "귀병",
        )
        val overlay = WorldContentOverlay("ov-legacy", WorldContentProfile.LEGACY, listOf("c-legacy"))
        val forced = WorldContentSnapshot(WorldContentProfile.CHRONICLE, listOf("ov-legacy"))
        val violations = EvidenceContractValidator.validateWorld(
            profile = WorldContentProfile.CHRONICLE,
            claims = listOf(base, legacy),
            baseClaimIds = setOf("c-base"),
            overlays = listOf(overlay),
            snapshot = forced,
        )
        assertContains(codes(violations), "V-A16-2")
        assertContains(codes(violations), "V-A16-1")
    }

    @Test
    fun `엄격 고증 월드가 정사만 담으면 통과한다`() {
        val base = claim(id = "c-base")
        assertEquals(
            emptyList(),
            EvidenceContractValidator.validateWorld(
                profile = WorldContentProfile.CHRONICLE,
                claims = listOf(base),
                baseClaimIds = setOf("c-base"),
                overlays = emptyList(),
            ),
        )
    }

    // ---------------------------------------------------------------- T1-K01

    @Test
    fun `번들 미허가 라이선스는 제품 자산 검증에서 차단된다`() {
        val researchOnly = SourceLicense(
            id = "lic-research",
            name = "research only",
            url = null,
            bundling = LicenseBundling.RESEARCH_ONLY,
            note = "위치 검증 연구만",
        )
        val unknown = researchOnly.copy(id = "lic-unknown", bundling = LicenseBundling.UNKNOWN, note = "문구 미확인")
        val violations = EvidenceContractValidator.validateBundling(
            listOf(
                ref("r-ok", SourceProximity.OFFICIAL_HISTORY),
                ref("r-research", SourceProximity.MODERN_STUDY, researchOnly),
                ref("r-unknown", SourceProximity.MODERN_STUDY, unknown),
            ),
        )
        assertEquals(listOf("V-K01-1", "V-K01-1"), codes(violations), "UNKNOWN은 통과가 아니라 차단이다")
    }
}
