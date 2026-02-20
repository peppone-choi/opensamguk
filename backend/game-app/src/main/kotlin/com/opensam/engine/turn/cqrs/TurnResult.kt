package com.opensam.engine.turn.cqrs

data class TurnResult(
    val advancedTurns: Int,
    val events: List<TurnDomainEvent> = emptyList(),
)

data class TurnDomainEvent(
    val type: String,
    val payload: Map<String, Any> = emptyMap(),
)
