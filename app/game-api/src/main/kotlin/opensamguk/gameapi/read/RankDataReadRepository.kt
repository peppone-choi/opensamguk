package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * W3-F2 — `rank_data` 행의 READ-only JPA 매핑(precheck 경로가 아닌 READ DTO enrichment 전용,
 * game-api ONLY — §7). 데몬 write 경로는 절대 JPA를 쓰지 않으며, rank_data write는
 * `ChangeRecorder` rank 채널 → `JdbcFlushExecutor`(엔진)로만 흐른다.
 *
 * rank_data 테이블(V1__baseline.sql:162)은 `(general_id, type)`가 UNIQUE이며 `value`(integer)에
 * 누적 카운터를 담는다. `type` 문자열은 엔진 `RankColumn` enum의 backing value와 byte-동일하다
 * (전투 통계: `warnum/killnum/deathnum/killcrew/deathcrew/firenum`). 이 엔티티는 game-api에
 * 존재하지 않던 rank_data READ 경로를 처음 여는 **공유 blocker-breaker**로, FrontGeneralInfo +
 * GeneralList의 6개 전투-통계 필드가 이를 소비한다(W3_PLAN §2).
 *
 * 선언한 컬럼만 `ddl-auto: validate`로 검증되며, 나머지 rank_data 컬럼은 매핑하지 않는다.
 */
@Entity
@Table(name = "rank_data")
class RankDataReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "nation_id")
    var nationId: Int = 0,

    @Column(name = "general_id")
    var generalId: Int = 0,

    @Column(name = "type")
    var type: String = "",

    @Column(name = "value")
    var value: Int = 0,
)

interface RankDataReadRepository : JpaRepository<RankDataReadEntity, Int> {
    /**
     * 한 장수의 모든 rank_data 행(전투 통계 6종 포함). 호출부는 `type`별로 색인해 사용한다.
     * 단건 장수 페이지(FrontGeneralInfo)에서 N+1 없이 1회 조회.
     */
    fun findByGeneralId(generalId: Int): List<RankDataReadEntity>

    /**
     * 한 장수의 특정 `type` 단일 값. 없으면 null(미기록 = 0으로 해석). `(general_id, type)`은
     * UNIQUE이므로 최대 1행.
     */
    fun findByGeneralIdAndType(generalId: Int, type: String): RankDataReadEntity?

    /**
     * GeneralList(다수 장수)용 N+1-safe 일괄 조회 — 장수 id 집합의 전투-통계 `type`만 한 번에.
     * 호출부는 `Map<Pair<generalId,type>, value>`로 접어 각 장수 행에 매핑한다. `type`은
     * 호출부가 RankColumn backing value 목록(warnum 등)을 넘긴다.
     */
    @Query(
        "select r from RankDataReadEntity r " +
            "where r.generalId in :generalIds and r.type in :types",
    )
    fun findByGeneralIdsAndTypes(
        @Param("generalIds") generalIds: Collection<Int>,
        @Param("types") types: Collection<String>,
    ): List<RankDataReadEntity>
}
