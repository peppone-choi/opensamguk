-- A ticket's pacing is frozen from its human-eligible participants at handoff.
-- Existing tickets retain their original immutable payload bytes and SHA pins.
ALTER TABLE battle_ticket ADD COLUMN pacing_mode VARCHAR(16);

UPDATE battle_ticket AS ticket
   SET pacing_mode = CASE WHEN EXISTS (
       SELECT 1 FROM battle_participant AS participant
        WHERE participant.world_id = ticket.world_id AND participant.battle_id = ticket.battle_id
   ) THEN 'REALTIME' ELSE 'ACCELERATED_NPC' END;

ALTER TABLE battle_ticket ALTER COLUMN pacing_mode SET NOT NULL;
ALTER TABLE battle_ticket ADD CONSTRAINT battle_ticket_pacing_mode_ck
    CHECK (pacing_mode IN ('REALTIME', 'ACCELERATED_NPC'));

CREATE INDEX battle_ticket_pacing_mode_idx
    ON battle_ticket (world_id, pacing_mode, battle_id);

ALTER TABLE battle_result_outbox ADD COLUMN pacing_mode VARCHAR(16);

UPDATE battle_result_outbox AS result
   SET pacing_mode = ticket.pacing_mode
  FROM battle_ticket AS ticket
 WHERE ticket.world_id = result.world_id AND ticket.battle_id = result.battle_id;

ALTER TABLE battle_result_outbox ALTER COLUMN pacing_mode SET NOT NULL;
ALTER TABLE battle_result_outbox ADD CONSTRAINT battle_result_pacing_mode_ck
    CHECK (pacing_mode IN ('REALTIME', 'ACCELERATED_NPC'));
