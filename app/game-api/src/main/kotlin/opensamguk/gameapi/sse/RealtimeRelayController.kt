package opensamguk.gameapi.sse

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Closes the CQRS loop edge (design §4): fans the engine's `turnCompleted` realtime signal out
 * to connected browsers over Server-Sent Events. Coarse signal only — clients refresh read state
 * via REST on each event.
 *
 * Endpoint: `/sse/turn` — emits `turnCompleted` events with `{at, lastTurnTime, year, month, turnNumber}`.
 * Heartbeat every 30 seconds to keep connections alive through proxies.
 */
@RestController
@RequestMapping("/sse")
class RealtimeRelayController {
    private val emitters = CopyOnWriteArrayList<SseEmitter>()
    private val heartbeatExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sse-heartbeat").apply { isDaemon = true }
        }

    @GetMapping("/turn")
    fun turn(): SseEmitter {
        val emitter = SseEmitter(0L) // no timeout
        emitters.add(emitter)

        emitter.onCompletion { removeEmitter(emitter) }
        emitter.onTimeout { removeEmitter(emitter) }
        emitter.onError { removeEmitter(emitter) }

        // Send initial connection comment (keeps some proxies happy)
        try {
            emitter.send(SseEmitter.event().comment("connected"))
        } catch (_: Exception) {
            removeEmitter(emitter)
        }

        return emitter
    }

    /** Sends [json] as a `turnCompleted` event to every connected emitter, pruning dead ones. */
    fun fanOut(json: String) {
        val dead = mutableListOf<SseEmitter>()
        for (emitter in emitters) {
            try {
                emitter.send(SseEmitter.event().name("turnCompleted").data(json))
            } catch (_: Exception) {
                dead.add(emitter)
            }
        }
        emitters.removeAll(dead)
    }

    fun emitterCount(): Int = emitters.size

    private fun removeEmitter(emitter: SseEmitter) {
        emitters.remove(emitter)
    }

    /** Heartbeat every 30s — sends a comment line to keep the connection alive. */
    private fun startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate({
            val dead = mutableListOf<SseEmitter>()
            for (emitter in emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"))
                } catch (_: Exception) {
                    dead.add(emitter)
                }
            }
            emitters.removeAll(dead)
        }, 30L, 30L, TimeUnit.SECONDS)
    }

    init {
        startHeartbeat()
    }
}
