package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

@Entity
@Table(name = "hall")
class HallReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "server_id")
    var serverId: String = "",

    @Column(name = "season")
    var season: Int = 0,

    @Column(name = "scenario")
    var scenario: Int = 0,

    @Column(name = "general_no")
    var generalNo: Int = 0,

    @Column(name = "type")
    var type: String = "",

    @Column(name = "value")
    var value: Double = 0.0,

    @Column(name = "owner")
    var owner: String? = null,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "aux", columnDefinition = "jsonb")
    var aux: Map<String, Any?> = linkedMapOf(),
)

interface HallReadRepository : JpaRepository<HallReadEntity, Int> {
    fun findAllByOrderByTypeAscValueDescIdAsc(): List<HallReadEntity>
}

@Entity
@Table(name = "statistic")
class StatisticReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "year")
    var year: Int = 0,

    @Column(name = "month")
    var month: Int = 0,

    @Column(name = "nation_count")
    var nationCount: Int = 0,

    @Column(name = "nation_name")
    var nationName: String = "",

    @Column(name = "nation_hist")
    var nationHist: String = "",

    @Column(name = "gen_count")
    var genCount: String = "",

    @Column(name = "personal_hist")
    var personalHist: String = "",

    @Column(name = "special_hist")
    var specialHist: String = "",

    @Column(name = "power_hist")
    var powerHist: String = "",

    @Column(name = "crewtype")
    var crewtype: String = "",

    @Column(name = "etc")
    var etc: String = "",

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "aux", columnDefinition = "jsonb")
    var aux: Map<String, Any?>? = null,
)

interface StatisticReadRepository : JpaRepository<StatisticReadEntity, Int> {
    fun findFirstByOrderByIdDesc(): StatisticReadEntity?
}
