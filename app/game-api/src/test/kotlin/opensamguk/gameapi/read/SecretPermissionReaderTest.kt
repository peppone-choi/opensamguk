package opensamguk.gameapi.read

import opensamguk.gameapi.owner.GeneralResolver
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals

/**
 * W0-3 — read 표면 공용 secretPermission 헬퍼 테스트. PHP 정본:
 *  - `legacy/devsam-core/hwe/func.php:390-435` checkSecretPermission — read API 호출자
 *    (`j_diplomacy_get_letter.php:33`, `API/Nation/GetGeneralLog.php:60` 등)는 모두
 *    기본 인자(`$checkSecretLimit = true`)로 호출한다 → belong/secretlimit 분기 LIVE.
 *  - `hwe/sql/schema.sql:57` belong DDL 기본 1, `:126` secretlimit DDL 기본 3 —
 *    meta 키 부재(시드 미기록) 시의 충실 복원값.
 */
class SecretPermissionReaderTest {

    private val nations = mock(NationReadRepository::class.java)
    private val reader = SecretPermissionReader(nations)

    private fun gen(
        nationId: Int = 1,
        officerLevel: Int = 1,
        meta: Map<String, Any?> = linkedMapOf(),
        penalty: Map<String, Any?> = linkedMapOf(),
    ) = GeneralReadEntity(id = 1, name = "테스트", nationId = nationId, officerLevel = officerLevel).also {
        it.meta = meta
        it.penalty = penalty
    }

    private fun nation(id: Int, meta: Map<String, Any?>) =
        NationReadEntity(id = id, name = "국가$id").also { it.meta = meta }

    private fun resolved(g: GeneralReadEntity) = GeneralResolver.ResolvedGeneral(
        general = g,
        officerLevel = g.officerLevel,
        permission = GeneralResolver.derivePermission(g.officerLevel),
        nationId = g.nationId,
        nationLevel = 0,
    )

    // ── 하드 deny ────────────────────────────────────────────────────────────────

    @Test
    fun `미인증-미보유는 -1`() = assertEquals(-1, reader.of(null))

    @Test
    fun `재야는 -1`() = assertEquals(-1, reader.of(resolved(gen(nationId = 0, officerLevel = 0))))

    // ── officer_level 사다리(상위 분기 — nation 조회 없음) ────────────────────────

    @Test
    fun `군주 lv12는 4 - nation 조회 안 함`() {
        assertEquals(4, reader.of(resolved(gen(officerLevel = 12))))
        verify(nations, never()).findById(anyInt())
    }

    @Test
    fun `수뇌 lv5는 2 - nation 조회 안 함`() {
        assertEquals(2, reader.of(resolved(gen(officerLevel = 5))))
        verify(nations, never()).findById(anyInt())
    }

    @Test
    fun `관직자 lv2는 1`() = assertEquals(1, reader.of(resolved(gen(officerLevel = 2))))

    // ── ambassador-auditor meta 분기(func.php:413-416) ───────────────────────────

    @Test
    fun `ambassador는 직급 무관 4`() =
        assertEquals(4, reader.of(resolved(gen(officerLevel = 1, meta = mapOf("permission" to "ambassador")))))

    @Test
    fun `auditor는 직급 무관 3`() =
        assertEquals(3, reader.of(resolved(gen(officerLevel = 1, meta = mapOf("permission" to "auditor")))))

    // ── penalty 분기(전용 컬럼, func.php:377-389 + :407-409) ─────────────────────

    @Test
    fun `noChief penalty는 군주라도 0`() =
        assertEquals(0, reader.of(resolved(gen(officerLevel = 12, penalty = mapOf("noChief" to true)))))

    @Test
    fun `noAmbassador penalty는 ambassador를 2로 클램프`() =
        assertEquals(
            2,
            reader.of(resolved(gen(officerLevel = 1, meta = mapOf("permission" to "ambassador"), penalty = mapOf("noAmbassador" to true)))),
        )

    // ── lv1 belong-secretlimit 분기(func.php:421-427, read 표면 LIVE) ─────────────

    @Test
    fun `lv1 사관년도 충족이면 1 - belong 3 vs secretlimit 1`() {
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, mapOf("secretlimit" to 1))))
        assertEquals(1, reader.of(resolved(gen(officerLevel = 1, meta = mapOf("belong" to 3)))))
    }

    @Test
    fun `lv1 사관년도 미달이면 0 - belong 1 vs secretlimit 3`() {
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, mapOf("secretlimit" to 3))))
        assertEquals(0, reader.of(resolved(gen(officerLevel = 1, meta = mapOf("belong" to 1)))))
    }

    @Test
    fun `secretlimit meta 키 부재는 DDL 기본 3 복원 - belong 기본 1이면 0`() {
        // 시드 1010 국가 meta엔 secretlimit이 없다 → PHP 신설 행의 DDL 기본 3(schema.sql:126)과 등가.
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, emptyMap())))
        assertEquals(0, reader.of(resolved(gen(officerLevel = 1))))
    }

    @Test
    fun `nation 행 부재(불능 상태)도 보수적으로 DDL 기본 3`() {
        `when`(nations.findById(1)).thenReturn(Optional.empty())
        assertEquals(0, reader.of(resolved(gen(officerLevel = 1))))
    }
}
