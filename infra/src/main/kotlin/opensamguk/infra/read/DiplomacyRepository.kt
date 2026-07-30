package opensamguk.infra.read

import opensamguk.common.world.WorldId
import opensamguk.infra.entity.DiplomacyEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository as SpringDataRepository

/**
 * JPA read repository for `diplomacy` table.
 *
 * P7 read API uses this for nation diplomacy state lookups. The write path
 * (daemon flush) goes through [DiplomacyRowMapper] + [JdbcFlushExecutor].
 *
 * Diplomacy is directional: `(src_nation_id, dest_nation_id)` with a unique constraint.
 * `findBetween` looks up the directional row from A's perspective (A = src).
 */
interface DiplomacyRepository {
    fun findBySrcNationId(nationId: Int): List<DiplomacyEntity>
    fun findByDestNationId(nationId: Int): List<DiplomacyEntity>
    fun findByNationId(nationId: Int): List<DiplomacyEntity>
    fun findBySrcNationIdAndDestNationId(srcNationId: Int, destNationId: Int): DiplomacyEntity?
}

internal interface DiplomacyRawRepository : SpringDataRepository<DiplomacyEntity, Int> {
    @Query(value = "SELECT * FROM diplomacy WHERE world_id = :worldId AND src_nation_id = :nationId", nativeQuery = true)
    fun findByWorldIdAndSrcNationId(
        @Param("worldId") worldId: Int,
        @Param("nationId") nationId: Int,
    ): List<DiplomacyEntity>

    @Query(value = "SELECT * FROM diplomacy WHERE world_id = :worldId AND dest_nation_id = :nationId", nativeQuery = true)
    fun findByWorldIdAndDestNationId(
        @Param("worldId") worldId: Int,
        @Param("nationId") nationId: Int,
    ): List<DiplomacyEntity>

    @Query(
        value = """
            SELECT * FROM diplomacy
            WHERE world_id = :worldId
              AND (src_nation_id = :nationId OR dest_nation_id = :nationId)
        """,
        nativeQuery = true,
    )
    fun findByWorldIdAndNationId(
        @Param("worldId") worldId: Int,
        @Param("nationId") nationId: Int,
    ): List<DiplomacyEntity>

    @Query(
        value = """
            SELECT * FROM diplomacy
            WHERE world_id = :worldId
              AND src_nation_id = :srcNationId
              AND dest_nation_id = :destNationId
        """,
        nativeQuery = true,
    )
    fun findByWorldIdAndSrcNationIdAndDestNationId(
        @Param("worldId") worldId: Int,
        @Param("srcNationId") srcNationId: Int,
        @Param("destNationId") destNationId: Int,
    ): DiplomacyEntity?
}

internal class WorldScopedDiplomacyRepository(
    private val raw: DiplomacyRawRepository,
    private val worldId: WorldId,
) : DiplomacyRepository {
    override fun findBySrcNationId(nationId: Int): List<DiplomacyEntity> =
        raw.findByWorldIdAndSrcNationId(worldId.value, nationId)

    override fun findByDestNationId(nationId: Int): List<DiplomacyEntity> =
        raw.findByWorldIdAndDestNationId(worldId.value, nationId)

    override fun findByNationId(nationId: Int): List<DiplomacyEntity> =
        raw.findByWorldIdAndNationId(worldId.value, nationId)

    override fun findBySrcNationIdAndDestNationId(srcNationId: Int, destNationId: Int): DiplomacyEntity? =
        raw.findByWorldIdAndSrcNationIdAndDestNationId(worldId.value, srcNationId, destNationId)
}
