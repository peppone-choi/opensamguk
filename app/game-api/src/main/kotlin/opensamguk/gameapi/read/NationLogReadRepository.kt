package opensamguk.gameapi.read

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.data.repository.Repository as SpringDataRepository

interface NationLogReadRawRepository : SpringDataRepository<WorldLogReadEntity, Int> {
    @Query(
        value = """
            SELECT id, year, month, phase, text FROM log_entry
            WHERE world_id = :worldId AND scope = 'NATION' AND nation_id = :nationId AND category = 'HISTORY'
            ORDER BY id DESC
        """,
        nativeQuery = true,
    )
    fun findAllNationHistory(@Param("worldId") worldId: Int, @Param("nationId") nationId: Int): List<WorldLogReadEntity>
}

@Repository
class NationLogReadRepository(
    private val raw: NationLogReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findAllNationHistory(nationId: Int): List<WorldLogReadEntity> =
        raw.findAllNationHistory(worldId.value, nationId)
}
