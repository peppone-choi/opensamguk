package opensamguk.engine.boot

import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Synthetic tuples test rejection rules, not an actual daemon stop/restart observation. */
class D101ProcessObservationTest {
    private val start = Instant.parse("2026-10-06T00:00:00Z")
    private val first = D101ProcessIdentity(101, start, "00000000-0000-0000-0000-000000000001")
    private val exit = D101ProcessExit(101, start, start.plusMillis(500), start.plusSeconds(1), 0)
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
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit.copy(stopRequestedAtUtc = start.minusSeconds(1)), next)
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit.copy(stopRequestedAtUtc = start.plusSeconds(2)), next)
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first, exit.copy(exitCode = 143), next)
        }
        assertFailsWith<IllegalStateException> {
            observer.requireColdRestart(first.copy(reportedDaemonNonce = "00000000-0000-0000-0000-000000000000"), exit, next)
        }
    }

    @Test
    fun `real child process exit and new OS start are observed without claiming daemon custody`() {
        val observer = D101ProcessObservation()
        val firstChild = ProcessBuilder("/bin/sh", "-c", "read line").start()
        var secondChild: Process? = null
        try {
            // Fixed test nonces exercise only shape/order; no trusted daemon channel is represented.
            val before = observer.capture(firstChild, "00000000-0000-0000-0000-000000000011")
            val stopped = observer.requestAndAwaitExit(firstChild, before, Duration.ofSeconds(5)) { child ->
                child.outputStream.bufferedWriter().use { it.write("stop\n") }
            }
            // Linux ProcessHandle startInstant may be truncated to whole seconds. Start
            // in a later OS timestamp bucket while retaining the real process/order check.
            val nextStartSecond = stopped.exitObservedAtUtc.epochSecond + 2
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (Instant.now().epochSecond < nextStartSecond) {
                check(System.nanoTime() < deadline) { "OS clock did not reach a new start bucket" }
                Thread.sleep(10)
            }
            val restarted = ProcessBuilder("/bin/sh", "-c", "read line").start()
            secondChild = restarted
            val after = observer.capture(restarted, "00000000-0000-0000-0000-000000000022")
            observer.requireColdRestart(before, stopped, after)
        } finally {
            secondChild?.destroyForcibly()
            firstChild.destroyForcibly()
            secondChild?.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            firstChild.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
