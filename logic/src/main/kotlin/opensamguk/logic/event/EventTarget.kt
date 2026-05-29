package opensamguk.logic.event

/**
 * The five dynamic-event lifecycle targets (PHP `Enums/EventTarget.php:4-12`).
 *
 * Created here as part of the F1 dispatcher CONTRACT so the [opensamguk.logic.tick.MonthlyPipeline]
 * skeleton lands first; F2 (FE1) loads scenario rows via `EventTarget.valueOf(s.uppercase())` —
 * scenario rows are lowercase (`pre_month`/`month`/...), normalized in Kotlin (do NOT rely on DB
 * collation, plan port_notes).
 */
enum class EventTarget {
    PRE_MONTH,
    MONTH,
    OCCUPY_CITY,
    DESTROY_NATION,
    UNITED,
    ;

    companion object {
        /** Load a scenario/DB `target_code` (lowercase) → enum (uppercase, no collation reliance). */
        fun fromCode(code: String): EventTarget = valueOf(code.uppercase())
    }
}
