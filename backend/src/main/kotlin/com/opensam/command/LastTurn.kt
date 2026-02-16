package com.opensam.command

/**
 * Tracks the last executed command and multi-turn term state.
 * Stored in general.lastTurn (JSONB) for general commands,
 * and nation.meta["turn_last_{officerLevel}"] for nation commands.
 */
data class LastTurn(
    val command: String = "휴식",
    val arg: Map<String, Any>? = null,
    val term: Int? = null,
) {
    @Suppress("UNCHECKED_CAST")
    fun toMap(): MutableMap<String, Any> = buildMap {
        put("command", command)
        if (arg != null) put("arg", arg)
        if (term != null) put("term", term)
    }.toMutableMap() as MutableMap<String, Any>

    companion object {
        fun fromMap(raw: Map<String, Any>?): LastTurn {
            if (raw == null) return LastTurn()
            val command = raw["command"] as? String ?: "휴식"
            @Suppress("UNCHECKED_CAST")
            val arg = raw["arg"] as? Map<String, Any>
            val term = (raw["term"] as? Number)?.toInt()
            return LastTurn(command = command, arg = arg, term = term)
        }
    }
}
