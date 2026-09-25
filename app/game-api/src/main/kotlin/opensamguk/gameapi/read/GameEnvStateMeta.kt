package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper

/** Read the process world's persisted strategic state before using seed metadata as fallback. */
object GameEnvStateMeta {
    fun value(gameKv: GameKvReadRepository?, mapper: ObjectMapper, key: String): Map<String, Any?>? {
        val raw = gameKv?.findByTableAndNamespaceAndKey("game_env", "game_env", key)?.value ?: return null
        @Suppress("UNCHECKED_CAST")
        return mapper.readValue(raw, Map::class.java) as Map<String, Any?>
    }

    fun overlay(base: Map<String, Any?>, gameKv: GameKvReadRepository?, mapper: ObjectMapper,
        key: String): Map<String, Any?> = value(gameKv, mapper, key)?.let { base + (key to it) } ?: base
}
