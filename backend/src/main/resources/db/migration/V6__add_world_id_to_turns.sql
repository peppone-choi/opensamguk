-- Add world_id column to general_turn and nation_turn for proper world isolation

ALTER TABLE general_turn ADD COLUMN world_id BIGINT;
UPDATE general_turn SET world_id = (SELECT g.world_id FROM general g WHERE g.id = general_turn.general_id);
ALTER TABLE general_turn ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE general_turn ADD CONSTRAINT fk_general_turn_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;

ALTER TABLE nation_turn ADD COLUMN world_id BIGINT;
UPDATE nation_turn SET world_id = (SELECT n.world_id FROM nation n WHERE n.id = nation_turn.nation_id);
ALTER TABLE nation_turn ALTER COLUMN world_id SET NOT NULL;
ALTER TABLE nation_turn ADD CONSTRAINT fk_nation_turn_world FOREIGN KEY (world_id) REFERENCES world_state(id) ON DELETE CASCADE;
