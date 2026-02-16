package com.opensam.engine.ai

enum class DiplomacyState {
    PEACE,
    DECLARED,
    RECRUITING,
    IMMINENT,
    AT_WAR,
}

enum class GeneralType(val flag: Int) {
    WARRIOR(1),
    STRATEGIST(2),
    COMMANDER(4),
}
