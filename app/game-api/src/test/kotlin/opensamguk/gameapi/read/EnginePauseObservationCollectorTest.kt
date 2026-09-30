package opensamguk.gameapi.read

import opensamguk.common.turn.TurnDaemonObservation
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnginePauseObservationCollectorTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val settings = EnginePauseCollectorSettings(URI("http://pep-game-engine:8082"), "pep", 1, 300,
        Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(15))
    private val current = TurnDaemonObservation("pep", 1, now.minusSeconds(1), now, true)

    @Test
    fun `explicit local profile validates while unsafe origins and fast tick assumptions fail`() {
        for (bad in listOf("http://user:password@engine", "http://engine/path", "http://engine?url=elsewhere", "file:///tmp/status")) {
            assertFailsWith<IllegalArgumentException> { settings.copy(origin = URI(bad)) }
        }
        assertFailsWith<IllegalArgumentException> { settings.copy(deadline = settings.poll) }
        assertFailsWith<IllegalArgumentException> { settings.copy(maxAge = Duration.ofSeconds(6)) }
        assertFailsWith<IllegalArgumentException> { settings.copy(tickSeconds = 5) }
        assertFailsWith<IllegalArgumentException> { settings.copy(worldId = 0) }
    }

    @Test
    fun `public cache reads do not fetch and read failures clear previously paused truth`() {
        var calls = 0
        var candidate: TurnDaemonObservation? = current
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { calls++; candidate }, clock)
        assertNull(collector.snapshot())
        collector.collectOnce()
        repeat(5) {
            assertEquals(current, collector.snapshot())
            assertEquals(true, collector.project(now, now, null, 300, false).paused)
        }
        assertNull(collector.project(now.plusSeconds(16), now, null, 300, false).paused)
        assertNull(collector.project(now, now, null, 5, false).paused)
        assertEquals(1, calls)
        candidate = null
        collector.collectOnce()
        assertNull(collector.snapshot())
    }

    @Test
    fun `wrong world stale future and wrong server cannot enter the cache`() {
        for (bad in listOf(current.copy(worldId = 2), current.copy(serverId = "other"),
            current.copy(sourceObservedAt = now.minusSeconds(16)), current.copy(sourceObservedAt = now.plusSeconds(1)))) {
            val collector = EnginePauseObservationCollector(settings, EnginePauseSource { bad }, clock)
            collector.collectOnce()
            assertNull(collector.snapshot())
        }
    }

    @Test
    fun `reset discards old cache and even an in flight response cannot restore it`() {
        lateinit var collector: EnginePauseObservationCollector
        collector = EnginePauseObservationCollector(settings, EnginePauseSource {
            collector.invalidateAfterReset(now)
            current
        }, clock)
        collector.collectOnce()
        assertNull(collector.snapshot())
    }

    @Test
    fun `source captured before reset completion stays unavailable`() {
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { current }, clock)
        collector.collectOnce()
        assertEquals(current, collector.snapshot())
        collector.invalidateAfterReset(now)
        collector.collectOnce()
        assertNull(collector.snapshot())
    }
}
