package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.turn.TurnDaemonObservation
import opensamguk.common.turn.TurnDaemonProjection
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit operator settings. No URL or identity is taken from a public request. */
data class EnginePauseCollectorSettings(
    val origin: URI,
    val serverId: String,
    val worldId: Int,
    val tickSeconds: Int,
    val poll: Duration,
    val deadline: Duration,
    val maxAge: Duration,
) {
    init {
        require(origin.scheme in setOf("http", "https") && !origin.host.isNullOrBlank())
        require(origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null)
        require(origin.rawPath.isNullOrEmpty() || origin.rawPath == "/")
        require(origin.port == -1 || origin.port in 1..65535)
        require(serverId.isNotBlank() && worldId > 0 && tickSeconds > 0)
        require(deadline.toMillis() > 0 && deadline < poll)
        require(maxAge >= poll.plus(deadline) && maxAge < Duration.ofSeconds(tickSeconds.toLong() * 3L))
    }
}

fun interface EnginePauseSource {
    fun read(): TurnDaemonObservation?
}

/** Read-only internal HTTP adapter. Redirects, tokens and raw errors are never forwarded. */
class InternalEnginePauseSource(
    private val settings: EnginePauseCollectorSettings,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val rest: RestClient = createClient(settings),
) : EnginePauseSource {
    override fun read(): TurnDaemonObservation? = try {
        val raw = rest.get().uri("/admin/turn-daemon/status").retrieve().body(String::class.java)
        val receivedAt = clock.instant()
        val root = raw?.takeIf { it.length <= 64 * 1024 }?.let(mapper::readTree)
        val sourceClock = root?.get("clock")
        val paused = root?.get("paused")?.takeIf { it.isBoolean }?.asBoolean()
        val worldId = sourceClock?.get("worldId")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
        val serverId = sourceClock?.get("serverId")?.takeIf { it.isTextual }?.asText()
        val observedAt = root?.get("serverTime")?.takeIf { it.isTextual }?.asText()
        val tickSeconds = sourceClock?.get("tickSeconds")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
        if (paused == null || worldId == null || serverId == null || observedAt == null || tickSeconds == null ||
            tickSeconds <= 0 || settings.maxAge >= Duration.ofSeconds(tickSeconds.toLong() * 3L)) null
        else TurnDaemonObservation(serverId, worldId, Instant.parse(observedAt), receivedAt, paused)
    } catch (_: Exception) {
        null
    }

    companion object {
        private fun createClient(settings: EnginePauseCollectorSettings): RestClient {
            val client = HttpClient.newBuilder().connectTimeout(settings.deadline)
                .followRedirects(HttpClient.Redirect.NEVER).build()
            val factory = JdkClientHttpRequestFactory(client).apply { setReadTimeout(settings.deadline) }
            return RestClient.builder().baseUrl(settings.origin.toString().trimEnd('/')).requestFactory(factory).build()
        }
    }
}

/** One collector and a process-local cache. Public reads never trigger HTTP. Not enabled by default. */
class EnginePauseObservationCollector(
    private val settings: EnginePauseCollectorSettings,
    private val source: EnginePauseSource,
    private val clock: Clock,
) : AutoCloseable {
    private data class Entry(val epoch: Long, val resetCompletedAt: Instant?,
        val observation: TurnDaemonObservation?, val unknownSince: Instant?)
    private val cache = AtomicReference(Entry(0, null, null, clock.instant()))
    private var scheduler: ScheduledExecutorService? = null

    @Synchronized
    fun start() {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "engine-pause-observation").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({ collectOnce() }, 0, settings.poll.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun collectOnce() {
        val before = cache.get()
        val candidate = try { source.read() } catch (_: Exception) { null }
        val now = clock.instant()
        val valid = candidate?.takeIf {
            val projection = TurnDaemonProjection.observe(settings.serverId, settings.worldId, now, it,
                settings.maxAge, null, null, settings.tickSeconds, false)
            projection.observationState == TurnDaemonProjection.ObservationState.CURRENT &&
                (before.resetCompletedAt == null || it.sourceObservedAt > before.resetCompletedAt) &&
                (before.observation == null || it.sourceObservedAt >= before.observation.sourceObservedAt)
        }
        // A reset or another collector update during HTTP makes the old response unusable.
        val since = if (valid != null) null else unknownStartedAt(before, now)
        cache.compareAndSet(before, before.copy(observation = valid, unknownSince = since))
    }

    internal fun snapshot(): TurnDaemonObservation? = cache.get().observation

    /** The API and session must share this one projection using their single observation time. */
    fun project(serverTime: Instant, lastTickExecutedAt: Instant?, nextTurnAt: Instant?,
        tickSeconds: Int, catchUpActive: Boolean): TurnDaemonProjection.Result {
        val entry = cache.get()
        val initial = TurnDaemonProjection.observe(settings.serverId, settings.worldId, serverTime, entry.observation,
            settings.maxAge, lastTickExecutedAt, nextTurnAt, tickSeconds, catchUpActive, resetCompletedAt = entry.resetCompletedAt)
        if (initial.state != TurnDaemonProjection.State.UNKNOWN) return initial
        val since = unknownStartedAt(entry, serverTime)
        cache.compareAndSet(entry, entry.copy(unknownSince = since))
        return TurnDaemonProjection.observe(settings.serverId, settings.worldId, serverTime, entry.observation,
            settings.maxAge, lastTickExecutedAt, nextTurnAt, tickSeconds, catchUpActive, since, entry.resetCompletedAt)
    }

    private fun unknownStartedAt(entry: Entry, now: Instant): Instant {
        entry.unknownSince?.takeIf { it <= now }?.let { return it }
        val expiresAt = entry.observation?.let { it.sourceObservedAt.plus(settings.maxAge) }
        return expiresAt?.takeIf { it < now } ?: now
    }

    /** Reset completion must explicitly invalidate both old cache and any in-flight request. */
    fun invalidateAfterReset(serverId: String, worldId: Int, completedAt: Instant) {
        require(serverId == settings.serverId && worldId == settings.worldId) { "Reset observation identity mismatch" }
        require(completedAt <= clock.instant()) { "Reset completion cannot be in the future" }
        cache.updateAndGet {
            if (it.resetCompletedAt != null && completedAt <= it.resetCompletedAt) it
            else Entry(it.epoch + 1, completedAt, null, completedAt)
        }
    }

    @Synchronized
    override fun close() {
        scheduler?.shutdownNow()
        scheduler = null
        cache.updateAndGet { Entry(it.epoch + 1, it.resetCompletedAt, null, it.unknownSince ?: clock.instant()) }
    }
}
