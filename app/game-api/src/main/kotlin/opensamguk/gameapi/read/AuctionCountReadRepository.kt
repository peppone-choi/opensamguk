package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * W3 FrontGlobalInfo `auctionCount` 전용 READ-only 매핑 — `ng_auction` 행의 최소 셸(count만 필요).
 *
 * PHP `GetFrontInfo::generateGlobalInfo`:
 *   `$db->queryFirstField('SELECT count(*) FROM ng_auction WHERE finished = 0')`
 * → 진행중(미종료) 경매 수. opensamguk `ng_auction` 테이블(V7__p6_messaging_economy.sql)은 PHP와
 * 동일하게 `finished boolean NOT NULL DEFAULT false` 컬럼을 가지므로 byte-동일 카운트가 가능하다
 * (W3_frontglobalinfo_gates §5: 이미 enum이 아닌 `auction`이 아니라 `ng_auction`을 써야 함 — 여기서
 * 정정해 ng_auction.finished=false를 카운트한다).
 *
 * 선언한 `id`/`finished` 컬럼만 `ddl-auto: validate`로 검증된다. game-api ONLY(§7); 여기서 write 없음.
 */
@Entity
@Table(name = "ng_auction")
class AuctionCountReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "finished")
    var finished: Boolean = false,
)

interface AuctionCountReadRepository : JpaRepository<AuctionCountReadEntity, Int> {
    /** 진행중(미종료) 경매 수 = PHP `count(*) FROM ng_auction WHERE finished = 0`. */
    fun countByFinished(finished: Boolean): Long
}
