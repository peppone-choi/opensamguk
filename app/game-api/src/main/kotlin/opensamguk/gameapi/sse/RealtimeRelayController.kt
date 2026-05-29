package opensamguk.gameapi.sse

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Closes the CQRS loop edge (design §4): fans the engine's `turnCompleted` realtime signal out
 * to connected browsers over Server-Sent Events. Coarse signal only.
 */
@RestController
@RequestMapping("/realtime")
class RealtimeRelayController {
    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    @GetMapping("/events")
    fun events(): SseEmitter {
        val emitter = SseEmitter(0L) // no timeout
        emitters.add(emitter)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        return emitter
    }

    /** Sends [json] to every connected emitter, pruning any that fail. */
    fun fanOut(json: String) {
        val dead = mutableListOf<SseEmitter>()
        for (emitter in emitters) {
            try {
                emitter.send(SseEmitter.event().name("realtime").data(json))
            } catch (_: Exception) {
                dead.add(emitter)
            }
        }
        emitters.removeAll(dead)
    }

    fun emitterCount(): Int = emitters.size
}
