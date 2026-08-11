package opensamguk.gameapi.owner

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet

data class GeneralOwnershipSnapshot(
    val id: Int,
    val worldId: Int,
    val userId: String?,
    val npcState: Int,
    val detail: GeneralReadEntity? = null,
)

interface GeneralOwnershipReadSource {
    fun findPlayableByUserId(userId: String): GeneralOwnershipSnapshot?

    fun findById(id: Int): GeneralOwnershipSnapshot?
}

@Service
class JdbcGeneralOwnershipReadSource(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) : GeneralOwnershipReadSource {
    private val worldId = processWorld.worldId.value

    override fun findPlayableByUserId(userId: String): GeneralOwnershipSnapshot? = queryOne(
        """
        SELECT id, world_id, user_id, npc_state
        FROM general
        WHERE world_id = :worldId AND user_id = :userId AND npc_state < :playableNpcState
        ORDER BY id ASC
        LIMIT 1
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("worldId", worldId)
            .addValue("userId", userId)
            .addValue("playableNpcState", GeneralPossessionService.CLAIMABLE_NPC_STATE),
    )

    override fun findById(id: Int): GeneralOwnershipSnapshot? = queryOne(
        """
        SELECT id, world_id, user_id, npc_state
        FROM general
        WHERE world_id = :worldId AND id = :id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("worldId", worldId)
            .addValue("id", id),
    )

    private fun queryOne(sql: String, params: MapSqlParameterSource): GeneralOwnershipSnapshot? =
        jdbc.query(sql, params) { rs, _ -> rs.toOwnershipSnapshot() }.firstOrNull()

    private fun ResultSet.toOwnershipSnapshot(): GeneralOwnershipSnapshot = GeneralOwnershipSnapshot(
        id = getInt("id"),
        worldId = getInt("world_id"),
        userId = getString("user_id"),
        npcState = getInt("npc_state"),
    )
}

internal class RepositoryGeneralOwnershipReadSource(
    private val generals: GeneralReadRepository,
) : GeneralOwnershipReadSource {
    override fun findPlayableByUserId(userId: String): GeneralOwnershipSnapshot? =
        generals.findByUserId(userId)?.toOwnershipSnapshot()

    override fun findById(id: Int): GeneralOwnershipSnapshot? =
        generals.findById(id).orElse(null)?.toOwnershipSnapshot()

    private fun GeneralReadEntity.toOwnershipSnapshot(): GeneralOwnershipSnapshot = GeneralOwnershipSnapshot(
        id = id,
        worldId = worldId,
        userId = userId,
        npcState = npcState,
        detail = this,
    )
}
