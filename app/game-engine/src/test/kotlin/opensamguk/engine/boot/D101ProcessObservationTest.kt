package opensamguk.engine.boot

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Synthetic tuples test rejection rules, not an actual daemon stop/restart observation. */
class D101ProcessObservationTest {
    private val start = Instant.parse("2026-10-06T00:00:00Z")
    private val first = D101ProcessIdentity(101, start, "00000000-0000-0000-0000-000000000001")
    private val exit = D101ProcessExit(101, start, start.plusSeconds(1), 0)
    private val next = D101ProcessIdentity(202, start.plusSeconds(2), "00000000-0000-0000-0000-000000000002")

    @Test
    fun `cold restart requires witnessed old exit and distinct process identity and child nonce`() {
        val observer = D101ProcessObservation()
        observer.requireColdRestart(first, exit, next)
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit.copy(pid = 999), next)
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit, first.copy(reportedDaemonNonce = next.reportedDaemonNonce))
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit, next.copy(reportedDaemonNonce = first.reportedDaemonNonce))
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit, next.copy(osStartAtUtc = start.minusSeconds(1)))
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit.copy(exitObservedAtUtc = start.minusSeconds(1)), next)
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit.copy(exitObservedAtUtc = start.plusSeconds(3)), next)
        }
    }
}
