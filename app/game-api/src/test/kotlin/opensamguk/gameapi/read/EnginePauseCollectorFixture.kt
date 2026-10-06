package opensamguk.gameapi.read

import opensamguk.common.turn.TurnDaemonObservation
import java.net.URI
import java.time.Clock
import java.time.Duration

/** 실제 collector 경로를 사용하되 HTTP와 스케줄러는 시작하지 않는다. */
fun enginePauseCollectorFixture(clock: Clock, paused: Boolean = false): EnginePauseObservationCollector {
    val settings = EnginePauseCollectorSettings(URI("http://engine:8082"), "pep", 1, 300,
        Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(10))
    return EnginePauseObservationCollector(settings, EnginePauseSource {
        val now = clock.instant()
        TurnDaemonObservation("pep", 1, now, now, paused)
    }, clock).also { it.collectOnce() }
}
