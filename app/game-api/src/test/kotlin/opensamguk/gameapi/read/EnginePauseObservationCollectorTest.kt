package opensamguk.gameapi.read

import opensamguk.common.turn.TurnDaemonObservation
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            collector.invalidateAfterReset("pep", 1, now)
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
        collector.invalidateAfterReset("pep", 1, now)
        collector.collectOnce()
        assertNull(collector.snapshot())
    }

    private class MutableClock(var time: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = time
    }

    @Test
    fun `continuous unknown starts at collector boot and repeated failures do not restart the clock`() {
        val time = MutableClock(now)
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { null }, time)
        time.time = now.plusSeconds(900)
        collector.collectOnce()
        val boundary = collector.project(time.instant(), time.instant(), time.instant().plusSeconds(300), 300, false)
        assertEquals(now, boundary.unknownSince)
        assertFalse(boundary.unknownAlertDue)
        assertFalse(boundary.healthy)
        time.time = now.plusSeconds(901)
        collector.collectOnce()
        assertTrue(collector.project(time.instant(), time.instant(), null, 300, false).unknownAlertDue)
    }

    @Test
    fun `expiry uses source freshness boundary and recovery clears before the next unknown streak`() {
        val time = MutableClock(now)
        var candidate: TurnDaemonObservation? = current
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { candidate }, time)
        collector.collectOnce()
        time.time = now.plusSeconds(16)
        val expired = collector.project(time.instant(), time.instant(), null, 300, false)
        assertEquals(now.plusSeconds(14), expired.unknownSince)
        candidate = null
        collector.collectOnce()
        assertEquals(expired.unknownSince, collector.project(time.instant(), time.instant(), null, 300, false).unknownSince)
        time.time = now.plusSeconds(20)
        candidate = current.copy(sourceObservedAt = time.instant(), receivedAt = time.instant(), paused = false)
        collector.collectOnce()
        val normal = collector.project(time.instant(), time.instant(), time.instant().plusSeconds(300), 300, false)
        assertTrue(normal.healthy)
        assertNull(normal.unknownSince)
        time.time = now.plusSeconds(25)
        candidate = null
        collector.collectOnce()
        assertEquals(time.instant(), collector.project(time.instant(), time.instant(), null, 300, false).unknownSince)
    }

    @Test
    fun `reset completion begins a distinct unknown interval and discards pre-reset timing`() {
        val time = MutableClock(now)
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { null }, time)
        time.time = now.plusSeconds(300)
        collector.invalidateAfterReset("pep", 1, time.instant())
        collector.collectOnce()
        assertEquals(time.instant(), collector.project(time.instant(), null, null, 300, false).unknownSince)
    }


    @Test
    fun `future source and received timestamps cannot postpone an existing unknown incident`() {
        val time = MutableClock(now)
        var candidate: TurnDaemonObservation? = null
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { candidate }, time)
        time.time = now.plusSeconds(901)
        for (bad in listOf(current.copy(sourceObservedAt = time.instant().plusSeconds(10000)),
            current.copy(receivedAt = time.instant().plusSeconds(10000)))) {
            candidate = bad
            collector.collectOnce()
            val result = collector.project(time.instant(), time.instant(), null, 300, false)
            assertEquals(now, result.unknownSince)
            assertTrue(result.unknownAlertDue)
        }
    }

    @Test
    fun `reset identity future timestamps and duplicate completion cannot erase an unknown interval`() {
        val time = MutableClock(now)
        val collector = EnginePauseObservationCollector(settings, EnginePauseSource { null }, time)
        time.time = now.plusSeconds(300)
        assertFailsWith<IllegalArgumentException> { collector.invalidateAfterReset("other", 1, time.instant()) }
        assertFailsWith<IllegalArgumentException> { collector.invalidateAfterReset("pep", 2, time.instant()) }
        assertFailsWith<IllegalArgumentException> { collector.invalidateAfterReset("pep", 1, time.instant().plusSeconds(1)) }
        assertEquals(now, collector.project(time.instant(), null, null, 300, false).unknownSince)
        collector.invalidateAfterReset("pep", 1, time.instant())
        time.time = now.plusSeconds(1201)
        collector.invalidateAfterReset("pep", 1, now.plusSeconds(300))
        val result = collector.project(time.instant(), null, null, 300, false)
        assertEquals(now.plusSeconds(300), result.unknownSince)
        assertTrue(result.unknownAlertDue)
    }

}
