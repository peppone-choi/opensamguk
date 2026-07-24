package opensamguk.gameapi.consistency

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.stereotype.Component
import kotlin.math.min

data class VersionVisibility(
    val visible: Boolean,
    val requiredVersion: Long,
    val currentVersion: Long?,
    val retryAfterMs: Long,
)

interface MinVersionBarrier {
    fun await(requiredVersion: Long): VersionVisibility
}

@Component
class ReadConsistencyBarrier(
    private val reader: WorldVersionReader,
    @Value("\${opensamguk.read-barrier.max-wait-ms:400}") maxWaitMs: Long,
    @Value("\${opensamguk.read-barrier.poll-interval-ms:25}") pollMs: Long,
    @Value("\${opensamguk.read-barrier.retry-after-ms:150}") retryAfterMs: Long,
) : MinVersionBarrier {
    private val maxWaitNanos = maxWaitMs.coerceAtLeast(0).times(1_000_000)
    private val pollMs = pollMs.coerceAtLeast(1)
    private val retryAfterMs = retryAfterMs.coerceAtLeast(1)

    override fun await(requiredVersion: Long): VersionVisibility {
        require(requiredVersion >= 0) { "requiredVersion must be non-negative" }
        val deadline = System.nanoTime() + maxWaitNanos
        var current: Long? = null
        while (true) {
            when (val read = readCurrentVersion()) {
                is VersionRead.Current -> current = read.value
                VersionRead.Unavailable ->
                    return notVisible(requiredVersion = requiredVersion, currentVersion = null)
            }
            if (current != null && current >= requiredVersion) {
                return VersionVisibility(
                    visible = true,
                    requiredVersion = requiredVersion,
                    currentVersion = current,
                    retryAfterMs = retryAfterMs,
                )
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                return notVisible(requiredVersion = requiredVersion, currentVersion = current)
            }
            Thread.sleep(min(pollMs, remainingNanos / 1_000_000L + 1L))
        }
    }

    private fun readCurrentVersion(): VersionRead =
        try {
            VersionRead.Current(reader.currentWorldVersion())
        } catch (_: DataAccessResourceFailureException) {
            VersionRead.Unavailable
        }

    private fun notVisible(requiredVersion: Long, currentVersion: Long?): VersionVisibility =
        VersionVisibility(
            visible = false,
            requiredVersion = requiredVersion,
            currentVersion = currentVersion,
            retryAfterMs = retryAfterMs,
        )

    private sealed interface VersionRead {
        data class Current(val value: Long?) : VersionRead
        data object Unavailable : VersionRead
    }
}
