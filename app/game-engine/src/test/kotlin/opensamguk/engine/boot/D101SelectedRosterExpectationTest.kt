package opensamguk.engine.boot

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Static classpath fixture only; these tests do not establish actual selected-source custody. */
class D101SelectedRosterExpectationTest {
    private val fixture = requireNotNull(javaClass.classLoader.getResourceAsStream("scenario/scenario_3190.json"))
        .use { it.readBytes() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(fixture)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `both typed extend options derive active denominators from the same fixture bytes`() {
        val calculator = D101SelectedRosterExpectation()
        val without = calculator.calculate(fixture, sha, fixture.size.toLong(), 0)
        val with = calculator.calculate(fixture, sha, fixture.size.toLong(), 1)
        assertTrue(without.activeGeneralRows > 0)
        assertTrue(without.activeRetainerRows > 0)
        assertEquals(without.selectedGeneralRows, with.selectedGeneralRows)
        assertEquals(without.activeGeneralRows, with.activeGeneralRows)
        assertEquals(without.activeRetainerRows, with.activeRetainerRows)
    }

    @Test
    fun `raw drift and unavailable effective option fail closed`() {
        val calculator = D101SelectedRosterExpectation()
        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(byteArrayOf(), sha, 0, 1)
        }
        assertFailsWith<IllegalStateException> {
            calculator.calculate(fixture, "0".repeat(64), fixture.size.toLong(), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(fixture, sha, fixture.size.toLong(), -1)
        }
        assertFailsWith<IllegalArgumentException> {
            calculator.calculate(fixture, sha, fixture.size.toLong() + 1, 1)
        }
    }

    @Test
    fun `database match rejects each count and option mismatch`() {
        val calculator = D101SelectedRosterExpectation()
        val counts = calculator.calculate(fixture, sha, fixture.size.toLong(), 1)
        val matching = D101ProjectionSnapshotReader.SeedMembership(
            generals = List(counts.activeGeneralRows) { emptyList() },
            retainers = List(counts.activeRetainerRows) { emptyList() },
            extendedGeneral = true,
            persistedStartTime = Instant.EPOCH,
        )
        calculator.requireDatabaseMatch(counts, matching)
        assertFailsWith<IllegalStateException> {
            calculator.requireDatabaseMatch(counts, matching.copy(generals = matching.generals.dropLast(1)))
        }
        assertFailsWith<IllegalStateException> {
            calculator.requireDatabaseMatch(counts, matching.copy(retainers = matching.retainers.dropLast(1)))
        }
        assertFailsWith<IllegalStateException> {
            calculator.requireDatabaseMatch(counts, matching.copy(extendedGeneral = false))
        }
    }
}
