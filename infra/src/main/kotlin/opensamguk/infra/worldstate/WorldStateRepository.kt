package opensamguk.infra.worldstate

import opensamguk.common.world.WorldId
import java.util.Optional
import org.springframework.data.repository.Repository as SpringDataRepository

interface WorldStateRepository {
    fun findById(id: Int): Optional<WorldStateEntity>
    fun findProcessWorld(): WorldStateEntity?
}

internal interface WorldStateRawRepository : SpringDataRepository<WorldStateEntity, Int> {
    fun findById(id: Int): Optional<WorldStateEntity>
}

internal class ProcessWorldStateRepository(
    private val raw: WorldStateRawRepository,
    private val worldId: WorldId,
) : WorldStateRepository {
    override fun findById(id: Int): Optional<WorldStateEntity> =
        if (id == worldId.value) raw.findById(id) else Optional.empty()

    override fun findProcessWorld(): WorldStateEntity? = raw.findById(worldId.value).orElse(null)
}
