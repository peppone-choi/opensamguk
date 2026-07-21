package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Repository
import java.time.Instant
import org.springframework.data.repository.Repository as SpringDataRepository

@Entity
@Table(name = "diplomacy_letter")
class DiplomacyLetterReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

    @Column(name = "world_id")
    var worldId: Int = 0,

    @Column(name = "src_nation_id")
    var srcNationId: Int = 0,

    @Column(name = "dest_nation_id")
    var destNationId: Int = 0,

    @Column(name = "prev_id")
    var prevId: Int? = null,

    @Column(name = "state")
    var state: String = "PROPOSED",

    @Column(name = "text_brief")
    var textBrief: String = "",

    @Column(name = "text_detail")
    var textDetail: String = "",

    @Column(name = "date")
    var date: Instant = Instant.EPOCH,

    @Column(name = "src_signer")
    var srcSigner: Int = 0,

    @Column(name = "dest_signer")
    var destSigner: Int? = null,

    @Convert(converter = MetaJsonConverter::class)
    @Column(name = "aux", columnDefinition = "jsonb")
    var aux: Map<String, Any?> = linkedMapOf(),
)

interface DiplomacyLetterReadRawRepository : SpringDataRepository<DiplomacyLetterReadEntity, Int> {
    fun findByWorldIdAndSrcNationIdOrWorldIdAndDestNationIdOrderByDateAscIdAsc(
        worldId: Int,
        srcNationId: Int,
        worldId2: Int,
        destNationId: Int,
    ): List<DiplomacyLetterReadEntity>
}

@Repository
class DiplomacyLetterReadRepository(
    private val raw: DiplomacyLetterReadRawRepository,
    processWorld: GameApiProcessWorld,
) {
    private val worldId: WorldId = processWorld.worldId

    fun findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(
        srcNationId: Int,
        destNationId: Int,
    ): List<DiplomacyLetterReadEntity> =
        raw.findByWorldIdAndSrcNationIdOrWorldIdAndDestNationIdOrderByDateAscIdAsc(
            worldId.value, srcNationId, worldId.value, destNationId,
        )
}
