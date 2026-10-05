package opensamguk.engine.boot

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** OS facts about a child daemon. The nonce must come from that child through a trusted channel. */
data class D101ProcessIdentity(
    val pid: Long,
    val osStartAtUtc: Instant,
    val reportedDaemonNonce: String,
)

data class D101ProcessExit(
    val pid: Long,
    val osStartAtUtc: Instant,
    val stopRequestedAtUtc: Instant,
    val exitObservedAtUtc: Instant,
    val exitCode: Int,
)

/** Collects process facts for the private sidecar. It never issues a FINAL_SELECTED proof. */
class D101ProcessObservation {
    fun capture(child: Process, reportedDaemonNonce: String): D101ProcessIdentity {
        val handle = child.toHandle()
        check(handle.isAlive) { "daemon child is not running" }
        requireNonce(reportedDaemonNonce)
        val osStart = handle.info().startInstant().orElse(null)
            ?: error("daemon OS process start time is unavailable")
        return D101ProcessIdentity(handle.pid(), osStart, reportedDaemonNonce)
    }

    fun requestAndAwaitExit(
        child: Process,
        before: D101ProcessIdentity,
        timeout: Duration,
        requestStop: (Process) -> Unit,
    ): D101ProcessExit {
        require(!timeout.isNegative && !timeout.isZero) { "positive process exit timeout is required" }
        require(timeout.toMillis() > 0) { "process exit timeout must be at least one millisecond" }
        check(child.pid() == before.pid) { "stop witness belongs to another process" }
        check(child.isAlive) { "old daemon already exited before stop request" }
        val requestedAt = Instant.now()
        requestStop(child)
        check(child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "daemon exit was not observed" }
        return D101ProcessExit(before.pid, before.osStartAtUtc, requestedAt, Instant.now(), child.exitValue())
    }

    fun requireColdRestart(before: D101ProcessIdentity, stop: D101ProcessExit, after: D101ProcessIdentity) {
        check(before.pid > 0 && after.pid > 0) { "daemon PID must be positive" }
        requireNonce(before.reportedDaemonNonce)
        requireNonce(after.reportedDaemonNonce)
        check(stop.pid == before.pid && stop.osStartAtUtc == before.osStartAtUtc) {
            "old daemon exit witness differs from the captured process"
        }
        check(stop.exitCode == 0) { "old daemon did not exit cleanly" }
        check(!stop.stopRequestedAtUtc.isBefore(before.osStartAtUtc) &&
            !stop.exitObservedAtUtc.isBefore(stop.stopRequestedAtUtc)) { "old daemon stop order is invalid" }
        check(after.pid != before.pid || after.osStartAtUtc != before.osStartAtUtc) {
            "a new Spring context in the old OS process is not a cold restart"
        }
        check(!after.osStartAtUtc.isBefore(stop.exitObservedAtUtc)) { "new daemon starts before old exit was observed" }
        check(after.reportedDaemonNonce != before.reportedDaemonNonce) { "daemon startup nonce did not change" }
    }

    private fun requireNonce(value: String) {
        val parsed = runCatching { UUID.fromString(value) }.getOrNull()
        check(parsed != null && parsed != UUID(0, 0) && parsed.toString() == value) {
            "daemon nonce must be a canonical nonnil UUID reported by the child"
        }
    }
}
