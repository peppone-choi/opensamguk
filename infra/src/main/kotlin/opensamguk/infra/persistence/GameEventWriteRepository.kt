package opensamguk.infra.persistence

import opensamguk.logic.record.GameEvent
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** Inserts only validated events. The caller owns the turn flush transaction. */
class GameEventWriteRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** True for a new row, false for an identical retry. A reused key with changed data fails closed. */
    fun insert(event: GameEvent): Boolean {
        val row = GameEventRow.from(event)
        val params = mapOf(
            "world_id" to row.worldId,
            "event_key" to row.eventKey,
            "kind" to row.kind,
            "section" to row.section,
            "audience" to row.audience,
            "audience_general_id" to row.audienceGeneralId,
            "audience_nation_id" to row.audienceNationId,
            "recipient_general_ids" to row.recipientGeneralIds?.joinToString(",", "{", "}"),
            "occurred_year" to row.occurredYear,
            "occurred_month" to row.occurredMonth,
            "occurred_phase" to row.occurredPhase,
            "occurred_ordinal" to row.occurredOrdinal,
            "refs" to row.refsJson,
            "facts" to row.factsJson,
            "publication_state" to row.publicationState,
        )
        val inserted = jdbc.update(
            """INSERT INTO game_event (
                   world_id, event_key, kind, section, audience, audience_general_id,
                   audience_nation_id, recipient_general_ids, occurred_year, occurred_month,
                   occurred_phase, occurred_ordinal, refs, facts, publication_state
               ) VALUES (
                   :world_id, :event_key, :kind, :section, :audience, :audience_general_id,
                   :audience_nation_id, CAST(:recipient_general_ids AS integer[]), :occurred_year,
                   :occurred_month, :occurred_phase, :occurred_ordinal, CAST(:refs AS jsonb),
                   CAST(:facts AS jsonb), :publication_state
               ) ON CONFLICT (world_id, event_key) DO NOTHING""",
            params,
        )
        if (inserted == 1) return true
        check(inserted == 0)

        val same = jdbc.queryForObject(
            """SELECT EXISTS (
                   SELECT 1 FROM game_event WHERE world_id = :world_id AND event_key = :event_key
                     AND kind = :kind AND section = :section AND audience = :audience
                     AND audience_general_id IS NOT DISTINCT FROM :audience_general_id
                     AND audience_nation_id IS NOT DISTINCT FROM :audience_nation_id
                     AND recipient_general_ids IS NOT DISTINCT FROM CAST(:recipient_general_ids AS integer[])
                     AND occurred_year = :occurred_year AND occurred_month = :occurred_month
                     AND occurred_phase = :occurred_phase AND occurred_ordinal = :occurred_ordinal
                     AND refs = CAST(:refs AS jsonb) AND facts = CAST(:facts AS jsonb)
                     AND publication_state = :publication_state
                     AND publish_after_year IS NULL AND publish_after_month IS NULL AND publish_after_phase IS NULL
               )""",
            params,
            Boolean::class.java,
        ) ?: false
        check(same) { "game_event event_key replay changed payload for world ${row.worldId}" }
        return false
    }
}
