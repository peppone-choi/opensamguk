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

    /**
     * Check if this LastTurn matches the given command code and arg.
     * Used to determine if the same command is being repeated.
     */
    fun isSameCommand(actionCode: String, newArg: Map<String, Any>?): Boolean {
        val lastArg = arg ?: emptyMap()
        val nowArg = newArg ?: emptyMap()
        return command == actionCode && lastArg == nowArg
    }

    /**
     * Advance the term stack when the same command repeats.
     * Returns a new LastTurn with incremented term (capped at maxTerm).
     * If the command differs, resets to term=1.
     */
    fun addTermStack(actionCode: String, newArg: Map<String, Any>?, maxTerm: Int): LastTurn {
        return if (isSameCommand(actionCode, newArg)) {
            val nextTerm = ((term ?: 0) + 1).coerceAtMost(maxTerm)
            LastTurn(command = actionCode, arg = newArg, term = nextTerm)
        } else {
            LastTurn(command = actionCode, arg = newArg, term = 1)
        }
    }

    /**
     * Get the current term stack count for the given command.
     * Returns 0 if the command doesn't match.
     */
    fun getTermStack(actionCode: String, newArg: Map<String, Any>?): Int {
        return if (isSameCommand(actionCode, newArg)) (term ?: 0) else 0
    }

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
