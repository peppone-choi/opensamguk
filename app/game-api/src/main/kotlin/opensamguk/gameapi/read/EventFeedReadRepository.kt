package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.logic.record.EventSection
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** A database candidate is never an authorized response. EventFeedReader checks it again. */
data class EventFeedRow(
    val id: Long,
    val kind: String,
    val section: String,
    val audience: String,
    val audienceGeneralId: Int?,
    val audienceNationId: Int?,
    val recipientGeneralIds: Set<Int>,
    val year: Int,
    val month: Int,
    val phase: Int,
    val ordinal: Int,
    val refsJson: String,
    val factsJson: String,
    val publicationState: String,
    val hasDelayedPublication: Boolean,
) {
    val position: EventFeedPosition get() = EventFeedPosition(year, month, phase, ordinal, id)
}

data class EventFeedPosition(val year: Int, val month: Int, val phase: Int, val ordinal: Int, val id: Long) {
    init { require(year in 1..9999 && month in 1..12 && phase in 1..3 && ordinal >= 0 && id > 0) }
}

@Repository
class EventFeedReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
) {
    private val processWorldId = processWorld.worldId.value

    fun privateCandidates(
        worldId: Int,
        section: EventSection,
        generalId: Int,
        nationId: Int,
        permission: Int,
        before: EventFeedPosition?,
        limit: Int,
    ): List<EventFeedRow> {
        require(worldId == processWorldId && section != EventSection.WORLD && generalId > 0 && limit in 1..128)
        return jdbc.query(
            """SELECT $COLUMNS FROM game_event
               WHERE world_id = :world AND section = :section
                 AND ((audience = 'SELF' AND audience_general_id = :general)
                   OR (audience = 'RETINUE' AND :nation > 0
                     AND :permission >= CASE WHEN :section = 'BATTLE' THEN 2 ELSE 1 END
                     AND audience_nation_id = :nation
                     AND :general = ANY(recipient_general_ids))
                   OR (audience = 'NATION' AND :nation > 0 AND :permission >= 2
                     AND audience_nation_id = :nation)
                   OR (audience = 'COURT' AND :nation > 0 AND :permission >= 0
                     AND audience_nation_id = :nation
                     AND :general = ANY(recipient_general_ids)))
                 ${if (before == null) "" else CURSOR}
               ORDER BY occurred_year DESC, occurred_month DESC, occurred_phase DESC,
                 occurred_ordinal DESC, id DESC LIMIT :limit""",
            params(worldId, section, before, limit) + mapOf("general" to generalId, "nation" to nationId,
                "permission" to permission),
            ROW,
        )
    }

    fun publicCandidates(worldId: Int, before: EventFeedPosition?, limit: Int): List<EventFeedRow> {
        require(worldId == processWorldId && limit in 1..128)
        return jdbc.query(
            """SELECT $COLUMNS FROM game_event
               WHERE world_id = :world AND section = 'WORLD' AND audience = 'PUBLIC'
                 AND publication_state = 'PUBLISHED' ${if (before == null) "" else CURSOR}
               ORDER BY occurred_year DESC, occurred_month DESC, occurred_phase DESC,
                 occurred_ordinal DESC, id DESC LIMIT :limit""",
            params(worldId, EventSection.WORLD, before, limit),
            ROW,
        )
    }

    private fun params(worldId: Int, section: EventSection, before: EventFeedPosition?, limit: Int): Map<String, Any?> =
        mapOf("world" to worldId, "section" to section.name, "year" to before?.year,
            "month" to before?.month, "phase" to before?.phase, "ordinal" to before?.ordinal,
            "id" to before?.id, "limit" to limit)

    private companion object {
        const val COLUMNS = """id,kind,section,audience,audience_general_id,audience_nation_id,
            recipient_general_ids,occurred_year,occurred_month,occurred_phase,occurred_ordinal,
            refs::text AS refs_json,facts::text AS facts_json,publication_state,
            (publish_after_year IS NOT NULL OR publish_after_month IS NOT NULL OR
             publish_after_phase IS NOT NULL) AS delayed"""
        const val CURSOR = """AND (occurred_year,occurred_month,occurred_phase,occurred_ordinal,id)
              < (:year,:month,:phase,:ordinal,:id)"""
        val ROW = RowMapper { rs, _ ->
            val recipients = (rs.getArray("recipient_general_ids")?.array as? Array<*>)
                ?.map { (it as Number).toInt() }?.toSet().orEmpty()
            EventFeedRow(
                rs.getLong("id"), rs.getString("kind"), rs.getString("section"), rs.getString("audience"),
                rs.getInt("audience_general_id").takeUnless { rs.wasNull() },
                rs.getInt("audience_nation_id").takeUnless { rs.wasNull() },
                recipients, rs.getInt("occurred_year"), rs.getInt("occurred_month"),
                rs.getInt("occurred_phase"), rs.getInt("occurred_ordinal"),
                rs.getString("refs_json"), rs.getString("facts_json"), rs.getString("publication_state"),
                rs.getBoolean("delayed"),
            )
        }
    }
}
