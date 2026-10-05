package opensamguk.infra.seed

import opensamguk.logic.imperial.ImperialWorldCodec

/** Reads an explicit scenario declaration; omission never manufactures a seed. */
object ScenarioImperialSeedCodec {
    fun read(root: Map<String, Any?>): ScenarioImperialSeed? {
        require(ImperialWorldCodec.META_KEY !in root || root[ImperialWorldCodec.META_KEY] is Map<*, *>)
        return null
    }
}
