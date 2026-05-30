package opensamguk.logic.domain

/**
 * 4-field last-turn VO — faithful port of `legacy/devsam-core/hwe/sammo/LastTurn.php`.
 *
 * Rides the `general.last_turn` jsonb (general-command `setResultTurn` target) and is also the
 * capset-seq term-stack carrier for nation commands (감축/증축/천도; see TermStack in F-SUBSTRATE).
 *
 * `toRaw()` (PHP `LastTurn::toRaw()` lines 74-88) ALWAYS emits `command` (defaulting to `휴식` when
 * null, PHP `setCommand`) and emits `arg`/`term`/`seq` ONLY when non-null — the delete-on-default
 * jsonb shape the golden `last_turn` dump expects. Key order is fixed: command, arg, term, seq.
 */
data class LastTurn(
    val command: String = DEFAULT_COMMAND,
    val arg: Map<String, Any?>? = null,
    val term: Int? = null,
    val seq: Int? = null,
) {
    /**
     * PHP `toRaw()`: `{command}` always present; `arg`/`term`/`seq` included only when non-null,
     * in command→arg→term→seq insertion order (byte-comparable jsonb).
     */
    fun toRaw(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["command"] = command
        if (arg != null) result["arg"] = arg
        if (term != null) result["term"] = term
        if (seq != null) result["seq"] = seq
        return result
    }

    companion object {
        const val DEFAULT_COMMAND: String = "휴식"

        /**
         * PHP `LastTurn::fromRaw()` (lines 19-29): an empty/null raw map yields the default
         * `LastTurn("휴식")`; a `command` of null defaults to `휴식` (PHP `setCommand`).
         */
        fun fromRaw(raw: Map<String, Any?>?): LastTurn {
            if (raw.isNullOrEmpty()) return LastTurn()
            @Suppress("UNCHECKED_CAST")
            return LastTurn(
                command = (raw["command"] as? String) ?: DEFAULT_COMMAND,
                arg = raw["arg"] as? Map<String, Any?>,
                term = (raw["term"] as? Number)?.toInt(),
                seq = (raw["seq"] as? Number)?.toInt(),
            )
        }
    }
}
