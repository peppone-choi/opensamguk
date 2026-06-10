package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * W0-3 — `checkSecretPermission()` 정본화 테스트. PHP 정본:
 *  - `legacy/devsam-core/hwe/func.php:390-435` (checkSecretPermission)
 *  - `legacy/devsam-core/hwe/func.php:377-389` (checkSecretMaxPermission — penalty 상한)
 *  - `legacy/devsam-core/hwe/sammo/Enums/PenaltyKey.php` — penalty 키는 camelCase enum 값
 *    (`noTopSecret`/`noChief`/`noAmbassador`), snake_case가 아니다.
 *  - `legacy/devsam-core/hwe/sql/schema.sql:57` general.belong DDL 기본값 1,
 *    `:126` nation.secretlimit DDL 기본값 3 — meta 키 부재 시의 충실 복원값.
 *
 * penalty는 PHP `Json::decode($me['penalty'])`(func.php:395) — general의 **전용 jsonb 컬럼**
 * (`V1__baseline.sql:99`, `General.penalty` 필드)이지 `meta["penalty"]` 키가 아니다.
 */
class SecretPermissionTest {

    private fun general(
        nationId: Int = 1,
        officerLevel: Int = 1,
        meta: Map<String, Any?> = linkedMapOf(),
        penalty: Map<String, Any?> = linkedMapOf(),
    ) = General(
        id = 1, nationId = nationId, cityId = 1,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = 1000, rice = 1000,
        penalty = penalty, meta = meta,
    )

    // ── 하드 deny (func.php:397-403) ─────────────────────────────────────────────

    @Test
    fun `재야는 -1`() =
        assertEquals(-1, SecretPermission.check(general(nationId = 0, officerLevel = 12)))

    @Test
    fun `officer_level 0은 -1`() =
        assertEquals(-1, SecretPermission.check(general(officerLevel = 0)))

    // ── noChief 즉시 0 (func.php:407-409, PenaltyKey 'noChief' camelCase) ─────────

    @Test
    fun `noChief penalty는 군주라도 0 - 전용 penalty 컬럼과 camelCase 키`() =
        assertEquals(0, SecretPermission.check(general(officerLevel = 12, penalty = mapOf("noChief" to true))))

    @Test
    fun `noChief 숫자 1 플래그도 PHP truthy로 0`() =
        assertEquals(0, SecretPermission.check(general(officerLevel = 12, penalty = mapOf("noChief" to 1))))

    @Test
    fun `meta penalty 키는 더이상 읽지 않는다 - 전용 컬럼만이 PHP 원천`() =
        // penalty가 meta에 들어있어도(잘못된 운반) PHP는 전용 컬럼만 읽으므로 무시 → 군주 4.
        assertEquals(4, SecretPermission.check(general(officerLevel = 12, meta = mapOf("penalty" to mapOf("noChief" to true)))))

    // ── secretMin 사다리 (func.php:411-421) ──────────────────────────────────────

    @Test
    fun `군주 lv12는 4`() = assertEquals(4, SecretPermission.check(general(officerLevel = 12)))

    @Test
    fun `ambassador는 직급 무관 4`() =
        assertEquals(4, SecretPermission.check(general(officerLevel = 1, meta = mapOf("permission" to "ambassador"))))

    @Test
    fun `auditor는 직급 무관 3`() =
        assertEquals(3, SecretPermission.check(general(officerLevel = 1, meta = mapOf("permission" to "auditor"))))

    @Test
    fun `수뇌 lv5는 2`() = assertEquals(2, SecretPermission.check(general(officerLevel = 5)))

    @Test
    fun `관직자 lv2는 1`() = assertEquals(1, SecretPermission.check(general(officerLevel = 2)))

    @Test
    fun `lv1은 checkSecretLimit=false면 0`() =
        assertEquals(0, SecretPermission.check(general(officerLevel = 1)))

    // ── checkSecretMaxPermission 상한 (func.php:377-389) ─────────────────────────

    @Test
    fun `noTopSecret은 상한 1 - ambassador도 1로 클램프`() =
        assertEquals(
            1,
            SecretPermission.check(
                general(officerLevel = 1, meta = mapOf("permission" to "ambassador"), penalty = mapOf("noTopSecret" to true)),
            ),
        )

    @Test
    fun `noAmbassador는 상한 2 - ambassador가 2로 클램프`() =
        assertEquals(
            2,
            SecretPermission.check(
                general(officerLevel = 1, meta = mapOf("permission" to "ambassador"), penalty = mapOf("noAmbassador" to true)),
            ),
        )

    @Test
    fun `noAmbassador는 군주도 2로 클램프`() =
        assertEquals(2, SecretPermission.check(general(officerLevel = 12, penalty = mapOf("noAmbassador" to true))))

    // ── belong-secretlimit 분기 (func.php:421-427, raw 오버로드) ──────────────────

    @Test
    fun `lv1 belong 충족이면 1 - belong 부재는 DDL 기본 1로 복원`() =
        assertEquals(
            1,
            SecretPermission.check(
                nationId = 1, officerLevel = 1, meta = emptyMap(), penalty = emptyMap(),
                checkSecretLimit = true, secretLimit = { 1 },
            ),
        )

    @Test
    fun `lv1 belong 미달이면 0 - 사관년도 부족`() =
        assertEquals(
            0,
            SecretPermission.check(
                nationId = 1, officerLevel = 1, meta = mapOf("belong" to 1), penalty = emptyMap(),
                checkSecretLimit = true, secretLimit = { 3 },
            ),
        )

    @Test
    fun `secretLimit 공급자는 lv1 분기에서만 게으르게 평가된다`() =
        // PHP는 이 분기에서만 DB를 조회한다(func.php:422-424) — 상위 분기에선 공급자 미호출.
        assertEquals(
            2,
            SecretPermission.check(
                nationId = 1, officerLevel = 5, meta = emptyMap(), penalty = emptyMap(),
                checkSecretLimit = true, secretLimit = { throw AssertionError("lv5에서 secretLimit 조회 금지") },
            ),
        )

    // ── financeSetterDenyReason 회귀 (기존 게이트 보존) ───────────────────────────

    @Test
    fun `재정 세터 - 수뇌 미만 비외교권자는 deny`() =
        assertEquals("권한이 부족합니다.", SecretPermission.financeSetterDenyReason(general(officerLevel = 4)))

    @Test
    fun `재정 세터 - ambassador는 수뇌 미만이어도 허용`() =
        assertNull(SecretPermission.financeSetterDenyReason(general(officerLevel = 4, meta = mapOf("permission" to "ambassador"))))

    @Test
    fun `재정 세터 - 수뇌는 허용`() =
        assertNull(SecretPermission.financeSetterDenyReason(general(officerLevel = 5)))
}
