-- The event-time faction of a shared RETINUE event is pinned in the existing audience_nation_id.
-- V65 required NULL for RETINUE; replace that target check before any new-world RETINUE writer runs.
ALTER TABLE game_event DROP CONSTRAINT game_event_target_ck;

ALTER TABLE game_event ADD CONSTRAINT game_event_target_ck CHECK (
    (audience = 'SELF' AND audience_general_id IS NOT NULL AND audience_general_id > 0
        AND audience_nation_id IS NULL AND recipient_general_ids IS NULL)
    OR (audience = 'RETINUE' AND audience_general_id IS NOT NULL AND audience_general_id > 0
        AND audience_nation_id IS NOT NULL AND audience_nation_id > 0
        AND recipient_general_ids IS NOT NULL AND cardinality(recipient_general_ids) > 0
        AND 0 < ALL(recipient_general_ids) AND array_position(recipient_general_ids, NULL) IS NULL)
    OR (audience = 'NATION' AND audience_general_id IS NULL AND audience_nation_id IS NOT NULL
        AND audience_nation_id > 0 AND recipient_general_ids IS NULL)
    OR (audience = 'COURT' AND audience_general_id IS NULL AND audience_nation_id IS NOT NULL
        AND audience_nation_id > 0 AND recipient_general_ids IS NOT NULL
        AND cardinality(recipient_general_ids) > 0 AND 0 < ALL(recipient_general_ids)
        AND array_position(recipient_general_ids, NULL) IS NULL)
    OR (audience = 'PUBLIC' AND audience_general_id IS NULL AND audience_nation_id IS NULL
        AND recipient_general_ids IS NULL)
);
