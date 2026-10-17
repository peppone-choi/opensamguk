package opensamguk.logic.world

/** Room-wide strategy timing. Tactical combat remains realtime in either mode. */
enum class WorldTimeMode {
    GENERAL_TURN,
    REALTIME,
}

/** Creation-time snapshot only; no season-time mutation API or engine integration. */
class WorldModeSettings private constructor(
    val timeMode: WorldTimeMode,
    val allowPlayerPermadeath: Boolean,
) {
    companion object {
        /**
         * Reads the two room options from world config without changing it.
         * Missing timing preserves general turns; missing permadeath remains undecided (C60).
         * Invalid supplied values fail even when another option is undecided.
         */
        fun fromWorldConfig(config: Map<String, Any?>): WorldModeSettingsLoad {
            val rawTimeMode = config["timeMode"]
            val timeMode = if (rawTimeMode == null) WorldTimeMode.GENERAL_TURN else {
                require(rawTimeMode is String) { "timeMode must be a string" }
                requireNotNull(WorldTimeMode.entries.firstOrNull { it.name == rawTimeMode }) {
                    "unknown timeMode in world config: '$rawTimeMode'"
                }
            }
            val rawPermadeath = config["allowPlayerPermadeath"]
                ?: return WorldModeSettingsLoad.NotDecided("NOT_DECIDED: allowPlayerPermadeath default / required choice")
            require(rawPermadeath is Boolean) { "allowPlayerPermadeath must be a boolean" }
            return WorldModeSettingsLoad.Ready(WorldModeSettings(timeMode, rawPermadeath))
        }
    }
}

sealed interface WorldModeSettingsLoad {
    data class Ready(val settings: WorldModeSettings) : WorldModeSettingsLoad
    data class NotDecided(val reason: String) : WorldModeSettingsLoad
}
