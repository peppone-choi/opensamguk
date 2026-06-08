package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * F4 READ-only JPA mapping of the `yearbook_history` row (V1 baseline) for the 연감 page.
 *
 * There is NO `ng_history` table in this schema (the spec names `ng_history`; the faithful port stores
 * the per-month yearbook snapshots in `yearbook_history`: `profile_name, year, month, map, nations`).
 * The table EXISTS but carries ZERO rows in the fresh scenario_1010 seed (the monthly pipeline writes
 * them as months advance) — the controller returns an empty list gracefully (200, no fabrication).
 *
 * game-api ONLY (§7); never written here.
 */
@Entity
@Table(name = "yearbook_history")
class YearbookHistoryReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "profile_name")
    var profileName: String = "",

    @Column(name = "year")
    var year: Int = 0,

    @Column(name = "month")
    var month: Int = 0,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "map", columnDefinition = "jsonb")
    var map: Map<String, Any?> = linkedMapOf(),

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "nations", columnDefinition = "jsonb")
    var nations: Map<String, Any?> = linkedMapOf(),

    // hash — GetHistory.php의 캐시 etag 해시(V1__baseline.sql:234 `hash text NOT NULL DEFAULT ''`).
    // 연감 레코드 식별/캐시용으로 그대로 전달(read-only enrichment 1줄 add, ddl-auto validate 유지).
    @Column(name = "hash")
    var hash: String = "",
)

interface HistoryReadRepository : JpaRepository<YearbookHistoryReadEntity, Int> {
    /** All yearbook rows in chronological order (year, month). */
    fun findAllByOrderByYearAscMonthAsc(): List<YearbookHistoryReadEntity>

    /** Yearbook rows for a single year-month (the 연감?yearMonth filter). */
    fun findByYearAndMonthOrderByIdAsc(year: Int, month: Int): List<YearbookHistoryReadEntity>
}
