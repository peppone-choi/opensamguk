package opensamguk.infra.read

import opensamguk.infra.entity.DiplomacyEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * JPA read repository for `diplomacy` table.
 *
 * P7 read API uses this for nation diplomacy state lookups. The write path
 * (daemon flush) goes through [DiplomacyRowMapper] + [JdbcFlushExecutor].
 *
 * Diplomacy is directional: `(src_nation_id, dest_nation_id)` with a unique constraint.
 * `findBetween` looks up the directional row from A's perspective (A = src).
 */
@Repository
interface DiplomacyRepository : JpaRepository<DiplomacyEntity, Int> {

    /** All outgoing diplomacy rows for a nation (src = nationId). */
    fun findBySrcNationId(nationId: Int): List<DiplomacyEntity>

    /** All incoming diplomacy rows for a nation (dest = nationId). */
    fun findByDestNationId(nationId: Int): List<DiplomacyEntity>

    /** All diplomacy rows where this nation appears on either side. */
    @Query(
        """
        SELECT d FROM DiplomacyEntity d
        WHERE d.srcNationId = :nationId OR d.destNationId = :nationId
        """
    )
    fun findByNationId(@Param("nationId") nationId: Int): List<DiplomacyEntity>

    /** The directional diplomacy row from A to B (A = src, B = dest). */
    fun findBySrcNationIdAndDestNationId(srcNationId: Int, destNationId: Int): DiplomacyEntity?
}
