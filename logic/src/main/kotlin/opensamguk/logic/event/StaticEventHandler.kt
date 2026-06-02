package opensamguk.logic.event

import opensamguk.logic.domain.General

/**
 * Faithful port of PHP `sammo\StaticEventHandler` — the static event dispatch skeleton.
 *
 * Dispatches over a runtime-registered handler map (empty in scenario 1010). The handler receives
 * event name + args and returns Unit (no-op for empty map).
 *
 * Later phases append handlers via [register] without widening the base file — the frozen empty
 * `LinkedHashMap` is the insertion-ordered registry.
 */
object StaticEventHandler {

    private val handlers = LinkedHashMap<String, MutableList<(General, General?, Map<String, Any?>, Map<String, Any?>) -> Unit>>()

    /**
     * Register a handler for an event type. Later phases (e.g., 부대발령즉시집합, 부대탑승즉시이동)
     * call this to append handlers without modifying the base [StaticEventHandler] source.
     */
    fun register(
        eventType: String,
        handler: (general: General, destGeneral: General?, env: Map<String, Any?>, params: Map<String, Any?>) -> Unit,
    ) {
        handlers.getOrPut(eventType) { mutableListOf() }.add(handler)
    }

    /**
     * PHP `StaticEventHandler::handleEvent` — dispatch to all registered handlers for [eventType].
     * No-op when the map is empty (scenario 1010 gate).
     */
    fun handleEvent(
        general: General,
        destGeneral: General?,
        eventType: String,
        env: Map<String, Any?>,
        params: Map<String, Any?>,
    ) {
        val list = handlers[eventType] ?: return
        for (h in list) {
            h(general, destGeneral, env, params)
        }
    }
}
