package opensamguk.infra.persistence

import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventPayloadCodec
import opensamguk.logic.record.GameEvent

/** Validated event projected to V65 columns; the daemon flush remains the only insert path. */
data class GameEventRow(
    val worldId: Int,
    val eventKey: String,
    val kind: String,
    val section: String,
    val audience: String,
    val audienceGeneralId: Int?,
    val audienceNationId: Int?,
    val recipientGeneralIds: List<Int>?,
    val occurredYear: Int,
    val occurredMonth: Int,
    val occurredPhase: Int,
    val occurredOrdinal: Int,
    val refsJson: String,
    val factsJson: String,
    val publicationState: String,
) {
    companion object {
        fun from(event: GameEvent): GameEventRow {
            val target = event.audience
            return GameEventRow(
                worldId = event.worldId,
                eventKey = event.eventKey.value,
                kind = event.kind.code,
                section = event.section.name,
                audience = target.audience.name,
                audienceGeneralId = when (target) {
                    is AudienceTarget.Self -> target.generalId
                    is AudienceTarget.Retinue -> target.ownerGeneralId
                    else -> null
                },
                audienceNationId = when (target) {
                    is AudienceTarget.Nation -> target.nationId
                    is AudienceTarget.Court -> target.nationId
                    else -> null
                },
                recipientGeneralIds = when (target) {
                    is AudienceTarget.Retinue -> target.authorizedGeneralIds.sorted()
                    is AudienceTarget.Court -> target.authorizedGeneralIds.sorted()
                    else -> null
                },
                occurredYear = event.occurredAt.year,
                occurredMonth = event.occurredAt.month,
                occurredPhase = event.occurredAt.phase,
                occurredOrdinal = event.occurredAt.ordinal,
                refsJson = EventPayloadCodec.encodeRefs(event.refs),
                factsJson = EventPayloadCodec.encodeFacts(event.facts),
                publicationState = event.publication.state.name,
            )
        }
    }
}
