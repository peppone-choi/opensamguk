package com.opensam.engine.turn.cqrs

enum class TurnLifecycleState {
    IDLE,
    LOADING,
    PROCESSING,
    PERSISTING,
    PUBLISHING,
    FAILED,
}
