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
     * 등록된 핸들러 맵을 비운다 — 테스트/레지스트리 리셋(단일 소유자 싱글톤이므로 테스트 격리에 필요).
     * scenario 1010 게이트는 빈 맵이 정본이므로, [register]로 핸들러를 넣은 테스트는 이걸로 원복한다.
     * ([opensamguk.logic.message.Message.clearBuilders]와 동일한 단일-소유자 리셋 패턴.)
     */
    fun clear() = handlers.clear()

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
