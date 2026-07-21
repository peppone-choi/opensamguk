package opensamguk.gameapi.read

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.entity.NationEnvEntity
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

interface NationEnvReadRawRepository : SpringDataRepository<NationEnvEntity, Int> {
    fun findByWorldIdAndNamespaceAndKey(worldId: Int, namespace: Int, key: String): NationEnvEntity?
}

@Repository
class NationEnvReadRepository(
    private val raw: NationEnvReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findByNamespaceAndKey(namespace: Int, key: String): NationEnvEntity? =
        raw.findByWorldIdAndNamespaceAndKey(worldId.value, namespace, key)
}
