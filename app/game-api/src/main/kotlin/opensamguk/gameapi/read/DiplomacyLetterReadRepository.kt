package opensamguk.gameapi.read

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * F4 READ-only JPA mapping of the `diplomacy_letter` row (V1 baseline) for the 외교부 letters page.
 *
 * game-api ONLY (§7); never written here. The letter state enum is
 * `diplomacy_letter_state { PROPOSED, ACTIVATED, CANCELLED, REPLACED }` — mapped as the raw text and
 * rendered through [F4StateText.letterStateText] (verbatim 제안됨/승인됨/거부됨/대체됨).
 */
@Entity
@Table(name = "diplomacy_letter")
class DiplomacyLetterReadEntity(
    @Id
    @Column(name = "id")
    var id: Int = 0,

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

interface DiplomacyLetterReadRepository : JpaRepository<DiplomacyLetterReadEntity, Int> {
    /** Letters where the nation is the source OR destination (the nation's full letter feed), newest last. */
    fun findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(srcNationId: Int, destNationId: Int): List<DiplomacyLetterReadEntity>
}
