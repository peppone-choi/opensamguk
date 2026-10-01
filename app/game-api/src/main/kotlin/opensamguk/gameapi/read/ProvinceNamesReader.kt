package opensamguk.gameapi.read

import opensamguk.logic.world.WORLD_ARCHIVE_MAP_NAME
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Revalidate world and stored pins before any cache/conditional response, including pinned URLs. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class ProvinceNamesReader(
    private val states: WorldStateReadRepository,
    private val worlds: ActiveWorldArtifactResolver,
    private val pins: WorldArtifactIdentityReadRepository,
) {
    private val cache = ProvinceNamesCache()

    fun current(): ProvinceNamesRepresentation? {
        val world = states.findProcessWorld() ?: return null
        if (ActiveWorldMap.requireName(world) != WORLD_ARCHIVE_MAP_NAME) return null
        val stored = pins.readPins(world.id)
        require(stored.isNotEmpty()) { "Province names require stored world pins" }
        val selected = worlds.resolve() ?: return null
        require(selected.world.id == world.id) { "Province names world changed during selection" }
        val artifacts = selected.artifacts ?: return null
        val topology = artifacts.projection.topology
        require(stored.all { it.revision == topology.topologyRevision && it.hash == topology.contentHash }) {
            "Province names world pins differ from selected artifacts"
        }
        return cache.get(world.id, artifacts)
    }
}
