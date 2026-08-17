package opensamguk.gameapi.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Sends [json] to every connected emitter under the event name carried by the payload's own
     * `type` field, pruning dead emitters.
     *
     * OPENSAM-45 (1-a~1-d): this used to label EVERY payload `turnCompleted` unconditionally, so a
     * browser could not tell payload kinds apart — a `commandSettled` listener would never fire.
     * (`RealtimeEvent.MessageCreated` shares that latent flaw, but it has no producer in main
     * source today, so no live feature was broken — structural, not observed.) The name now follows
     * the payload; unknown or unparseable payloads keep the old name so existing `turnCompleted`
     * listeners never lose a signal.
     *
     * NOTE: this channel has **no per-recipient filtering** — every payload reaches every connected
     * browser. Nothing user-specific may be put on it.
     */
    fun fanOut(json: String) {
        val dead = mutableListOf<SseEmitter>()
        for (emitter in emitters) {
            try {
                emitter.send(eventFor(json))
            } catch (_: Exception) {
                dead.add(emitter)
            }
        }
        emitters.removeAll(dead)
    }

    fun emitterCount(): Int = emitters.size

    /**
     * Builds the wire event [fanOut] sends. Extracted so the name-follows-payload behaviour is
     * covered by a test — inlined in the loop it would be the one production line no test touches.
     */
    internal fun eventFor(json: String): SseEmitter.SseEventBuilder =
        SseEmitter.event().name(eventNameOf(json)).data(json)

    /**
     * Reads the payload's `type` discriminator without binding to the sealed hierarchy — the relay
     * only routes, it never interprets. A payload with no usable `type` falls back to
     * [DEFAULT_EVENT_NAME] rather than being dropped.
     */
    private fun eventNameOf(json: String): String = runCatching {
        val node = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching null
        (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: DEFAULT_EVENT_NAME

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

    companion object {
        /** Name used when the payload carries no `type` — keeps pre-OPENSAM-45 listeners working. */
        const val DEFAULT_EVENT_NAME: String = "turnCompleted"
    }
}
