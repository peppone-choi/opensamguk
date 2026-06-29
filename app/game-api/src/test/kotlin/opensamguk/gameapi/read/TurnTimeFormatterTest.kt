package opensamguk.gameapi.read

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * W3-F3 — TurnTimeFormatter 단위 테스트.
 *
 * 패러티 타깃: PHP `getTurnTime(TURNTIME_FULL)` = `substr($turntime, 0, 19)` = "YYYY-MM-DD HH:MM:SS".
 * 'T'→공백 치환 + 앞 19자. 마이크로초/오프셋/Z 꼬리는 잘리고, 초가 없으면 ":00"으로 패딩한다.
 */
class TurnTimeFormatterTest {

    @Test
    fun `초 단위 Instant는 한국 서버 시간의 YYYY-MM-DD HH-MM-SS로 포맷된다`() {
        // 2026-06-03T10:30:45Z
        val t = OffsetDateTime.of(2026, 6, 3, 10, 30, 45, 0, ZoneOffset.UTC).toInstant()
        assertEquals("2026-06-03 19:30:45", TurnTimeFormatter.full(t))
    }

    @Test
    fun `정각 초0도 SS 자리가 00으로 채워진다`() {
        val t = OffsetDateTime.of(2026, 1, 9, 8, 5, 0, 0, ZoneOffset.UTC).toInstant()
        assertEquals("2026-01-09 17:05:00", TurnTimeFormatter.full(t))
    }

    @Test
    fun `마이크로초가 있어도 앞 19자만 남는다`() {
        // 나노초 → Instant.toString()은 .123456789 꼬리를 붙인다. '.' 경계에서 잘려야 한다.
        val t = OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 123_456_789, ZoneOffset.UTC).toInstant()
        assertEquals("2027-01-01 08:59:59", TurnTimeFormatter.full(t))
    }

    @Test
    fun `OffsetDateTime 오버로드는 한국 서버 시간으로 표시된다`() {
        val odt = OffsetDateTime.of(2026, 6, 3, 9, 30, 0, 0, ZoneOffset.ofHours(9))
        assertEquals("2026-06-03 09:30:00", TurnTimeFormatter.full(odt))
    }

    @Test
    fun `parseFull interprets wall-clock strings as Korean server time`() {
        val parsed = TurnTimeFormatter.parseFull("2026-06-03 09:30:00")
        assertEquals(Instant.parse("2026-06-03T00:30:00Z"), parsed)
    }

    @Test
    fun `null은 null을 반환한다 (PHP turntime 키 부재 시 null)`() {
        assertNull(TurnTimeFormatter.full(null as Instant?))
        assertNull(TurnTimeFormatter.full(null as OffsetDateTime?))
    }
}
