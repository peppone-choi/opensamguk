package opensamguk.gameapi.read

import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.world.HAN_WORLD_V3_MAP_NAME
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class ActiveWorldArtifactSnapshot(
    val world: WorldStateReadEntity,
    val cities: List<CityReadEntity>,
    val artifacts: ResolvedHanWorldArtifacts?,
)

/** Select on each read, so a reset cannot retain the previous world's artifact identity. */
@Component
class ActiveWorldArtifactResolver(
    private val worlds: WorldStateReadRepository,
    private val cities: CityReadRepository,
    private val pins: WorldArtifactIdentityReadRepository,
    private val artifactResolver: HanWorldArtifactsResolver,
) {
    @Autowired
    constructor(worlds: WorldStateReadRepository, cities: CityReadRepository, pins: WorldArtifactIdentityReadRepository) :
        this(worlds, cities, pins, HanWorldArtifactsResolver())

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun resolve(): ActiveWorldArtifactSnapshot? {
        val world = worlds.findProcessWorld() ?: return null
        val mapName = ActiveWorldMap.requireName(world)
        val roster = cities.findAll()
        require(roster.all { it.worldId == world.id }) { "City roster does not match process world" }
        val artifacts = if (mapName == HAN_WORLD_V3_MAP_NAME) {
            artifactResolver.resolve(roster.map { it.id }, pins.readPins(world.id))
        } else null
        return ActiveWorldArtifactSnapshot(world, roster, artifacts)
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun cityNames(): Map<Int, String> {
        val selected = resolve() ?: return emptyMap()
        val map = selected.artifacts?.artifactBytes("infra/src/main/resources/map/han-world-v3.json")
            ?.toString(Charsets.UTF_8)?.let(opensamguk.infra.seed.MapJson::loadMap)
            ?: opensamguk.infra.seed.MapJson.loadFromClasspath(ActiveWorldMap.requireName(selected.world))
        return map.cities.mapNotNull { city -> city.nameCh?.let { city.id to it } }.toMap()
    }

}
